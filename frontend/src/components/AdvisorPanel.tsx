import { AlertTriangle, Info } from 'lucide-react';
import type { Advice, ExecutionMode } from '../api/types';
import { Badge, Card, KeyValue, Notice, Table } from './ui';
import type { Tone } from './ui';
import { formatBytes, formatDuration, formatNumber } from '../lib/format';
import { modeLabel } from '../lib/labels';

const BASIS: Record<string, { label: string; tone: Tone; help: string }> = {
  MEASURED: { label: 'measured', tone: 'green', help: 'Completed runs of the same workload on this platform' },
  HISTORICAL: { label: 'calibrated estimate', tone: 'blue', help: "Core's model calibrated with measured throughput" },
  THEORETICAL: { label: 'theoretical estimate', tone: 'amber', help: "Core's cost model without measurements" },
  NONE: { label: 'no estimate', tone: 'gray', help: 'Nothing to estimate from' },
};

const CONFIDENCE_TONE: Record<string, Tone> = { HIGH: 'green', MEDIUM: 'blue', LOW: 'amber', NONE: 'gray' };

interface Props {
  advice: Advice;
  selected: ExecutionMode | undefined;
  onSelect: (mode: ExecutionMode) => void;
  advanced: boolean;
}

export function AdvisorPanel({ advice, selected, onSelect, advanced }: Props) {
  const w = advice.workload;
  const r = advice.resources;
  return (
    <div className="space-y-4">
      <Card title="Execution strategy advisor" subtitle={`Computed ${new Date(advice.createdAt).toLocaleTimeString()} from Core's cost model and the cluster's current resources`}
        actions={<Badge tone={CONFIDENCE_TONE[advice.confidence]}>confidence: {advice.confidence.toLowerCase()}</Badge>}>
        {advice.reliable && advice.recommendedMode ? (
          <p className="text-sm">
            <span className="font-semibold">Recommended: {modeLabel(advice.recommendedMode)}.</span>{' '}
            <span className="text-slate-600 dark:text-slate-400">{advice.explanation}</span>
          </p>
        ) : (
          <Notice tone="amber"><span className="font-medium">A reliable recommendation cannot be made yet.</span> {advice.explanation}</Notice>
        )}
        <div className="mt-4">
          <Table>
            <thead>
              <tr><th className="w-8"><span className="sr-only">Choose</span></th><th>Mode</th><th>Verdict</th><th>Estimate</th><th>Measured here</th><th>Reasons</th></tr>
            </thead>
            <tbody>
              {advice.modes.map((m) => {
                const basis = BASIS[m.estimateBasis] ?? BASIS.NONE;
                return (
                  <tr key={m.mode} className={advice.reliable && m.mode === advice.recommendedMode ? 'bg-blue-50/60 dark:bg-blue-950/40' : undefined}>
                    <td>
                      <input type="radio" name="mode" aria-label={`Run in ${modeLabel(m.mode)}`} disabled={!m.executable}
                        checked={selected === m.mode} onChange={() => onSelect(m.mode)} />
                    </td>
                    <td>
                      <div className="font-medium">{modeLabel(m.mode)}</div>
                      {!m.executable && <div className="text-xs text-slate-500">not available here</div>}
                    </td>
                    <td><Verdict verdict={m.verdict} reliable={advice.reliable} /></td>
                    <td className="tabular-nums">
                      {m.estimatedSeconds !== null && <div>{formatDuration(m.estimatedSeconds * 1000)}</div>}
                      <Badge tone={basis.tone} title={basis.help}>{basis.label}</Badge>
                    </td>
                    <td className="tabular-nums">
                      {m.measured ? <span title={`min ${formatDuration(m.measured.minSeconds * 1000)}, max ${formatDuration(m.measured.maxSeconds * 1000)}`}>
                        {formatDuration(m.measured.medianSeconds * 1000)} <span className="text-xs text-slate-500">median of {m.measured.runs}</span></span> : '—'}
                    </td>
                    <td className="max-w-md text-xs text-slate-600 dark:text-slate-400">{m.reasons.join('; ')}</td>
                  </tr>
                );
              })}
            </tbody>
          </Table>
        </div>
        <p className="mt-2 text-xs text-slate-500">
          Estimates are predictions, not results. Measured values come from earlier runs of the same algorithm and dimensions on this platform. No mode is guaranteed to be optimal.
        </p>
      </Card>

      {advice.warnings.length > 0 && (
        <Notice tone="amber">
          <ul className="space-y-1">
            {advice.warnings.map((warning, i) => <li key={i} className="flex gap-2"><AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />{warning}</li>)}
          </ul>
        </Notice>
      )}

      <div className="grid gap-4 lg:grid-cols-2">
        <Card title="Workload (Core analysis)">
          <KeyValue items={[
            ['Dimensions', Object.entries(w.dimensions).map(([k, v]) => `${k} ${formatNumber(v)}`).join(', ') || '—'],
            ['Estimated work', `${formatNumber(w.estimatedOperations)} ${w.operationUnit}`],
            ['Complexity', w.timeComplexity],
            ['Input / output', `${formatBytes(w.inputBytes)} / ${formatBytes(w.outputBytes)}`],
            ['Estimated peak memory', formatBytes(w.estimatedPeakMemoryBytes)],
            ['Parallelization', w.partitionable ? `partitionable (${w.partitionStrategy?.toLowerCase().replace(/_/g, ' ')}, up to ${formatNumber(w.maxUsefulPartitions)} partitions)` : 'not partitionable'],
            ['Dependencies', w.dataDependency?.toLowerCase() ?? '—'],
            ['Aggregation', w.aggregation ?? '—'],
          ]} />
        </Card>
        <Card title="Resources considered">
          <KeyValue items={[
            ['Local processors', r.localCores],
            ['Eligible workers', r.eligibleWorkers.length ? r.eligibleWorkers.join(', ') : 'none'],
            ['Worker slots', `${r.busySlots} busy of ${r.totalSlots}`],
            ['GPU', r.gpuAvailable ? 'available' : `not available (${r.gpuStatus})`],
            ['Network', r.networkMeasured ? `${formatBytes(r.transferBytesPerSecond)}/s, latency ${formatNumber(r.latencyMillis)} ms (measured)` : 'not measured yet'],
            ['Data a distributed run moves', formatBytes(r.estimatedTransferBytes)],
            ['Estimated communication', r.estimatedCommunicationSeconds !== null ? formatDuration(r.estimatedCommunicationSeconds * 1000) : 'unknown until the network is measured'],
          ]} />
          {advanced && r.ineligibleWorkers.length > 0 && (
            <ul className="mt-3 space-y-1 text-xs text-slate-600 dark:text-slate-400">
              {r.ineligibleWorkers.map((x) => <li key={x.workerId}><span className="font-mono">{x.workerId}</span>: {x.reason}</li>)}
            </ul>
          )}
        </Card>
      </div>

      {advanced && advice.assumptions.length > 0 && (
        <Card title="Assumptions of the cost model">
          <ul className="space-y-1 text-sm text-slate-600 dark:text-slate-400">
            {advice.assumptions.map((a, i) => <li key={i} className="flex gap-2"><Info className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />{a}</li>)}
          </ul>
        </Card>
      )}
    </div>
  );
}

/** The runtime's verdict; without a reliable recommendation its pick is shown as a preference, not a recommendation. */
function Verdict({ verdict, reliable }: { verdict: string; reliable: boolean }) {
  if (verdict === 'RECOMMENDED') {
    return reliable ? <Badge tone="green">recommended</Badge>
      : <Badge tone="amber" title="Preferred for structural reasons only; there is no estimate or measurement to confirm it">preferred, unconfirmed</Badge>;
  }
  const tone: Tone = verdict === 'SUITABLE' ? 'blue' : verdict === 'UNSUPPORTED' ? 'gray' : 'amber';
  return <Badge tone={tone}>{verdict.toLowerCase().replace(/_/g, ' ')}</Badge>;
}
