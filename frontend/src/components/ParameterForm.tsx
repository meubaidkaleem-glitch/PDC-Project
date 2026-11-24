import { useState } from 'react';
import { Upload } from 'lucide-react';
import { api, ApiError } from '../api/client';
import type { Algorithm, InputUpload, ParameterSchema } from '../api/types';
import { Badge, Button, Field, SelectInput, TextArea, TextInput } from './ui';
import { formatBytes } from '../lib/format';

/** Values of the form: numbers and texts as typed; arrays as text; uploads by parameter. */
export interface ParameterState {
  values: Record<string, string>;
  uploads: Record<string, InputUpload>;
}

export const EMPTY_PARAMETERS: ParameterState = { values: {}, uploads: {} };

const isArray = (p: ParameterSchema) => p.type === 'INTEGER_ARRAY' || p.type === 'DECIMAL_ARRAY';

/** Whether the algorithm lets the user provide data instead of generating it. */
export function inputChoice(algorithm: Algorithm): ParameterSchema | undefined {
  return algorithm.parameters.find((p) => p.name === 'input' && p.allowedValues.includes('INLINE'));
}

/**
 * Converts the form into Core parameters and upload references. Empty fields are left out, so Core applies its
 * defaults. Returns the problems found (only syntax; Core validates the rest).
 */
export function toDraftParameters(algorithm: Algorithm, state: ParameterState):
  { parameters: Record<string, unknown>; inputs: Record<string, string>; problems: Record<string, string> } {
  const parameters: Record<string, unknown> = {};
  const inputs: Record<string, string> = {};
  const problems: Record<string, string> = {};
  const userData = state.values.input === 'INLINE';
  for (const p of algorithm.parameters) {
    if (state.uploads[p.name] && userData) {
      inputs[p.name] = state.uploads[p.name].id;
      continue;
    }
    const raw = (state.values[p.name] ?? '').trim();
    if (raw === '') continue;
    if (isArray(p)) {
      if (p.uploadable && algorithm.inputParameters.includes(p.name) && inputChoice(algorithm) && !userData) continue;
      const parts = raw.split(/[\s,;]+/).filter(Boolean);
      const numbers = parts.map(Number);
      if (numbers.some((n) => !Number.isFinite(n))) {
        problems[p.name] = 'Enter numbers separated by commas or spaces';
        continue;
      }
      if (p.type === 'INTEGER_ARRAY' && numbers.some((n) => !Number.isInteger(n))) {
        problems[p.name] = 'Enter whole numbers';
        continue;
      }
      parameters[p.name] = numbers;
    } else if (p.type === 'INTEGER' || p.type === 'DECIMAL') {
      const n = Number(raw);
      if (!Number.isFinite(n) || (p.type === 'INTEGER' && !Number.isInteger(n))) {
        problems[p.name] = p.type === 'INTEGER' ? 'Enter a whole number' : 'Enter a number';
        continue;
      }
      parameters[p.name] = n;
    } else if (p.type === 'BOOLEAN') {
      parameters[p.name] = raw === 'true';
    } else {
      parameters[p.name] = raw;
    }
  }
  return { parameters, inputs, problems };
}

/** Tuning knobs and generator settings that beginners rarely change; they stay under "More parameters". */
const TUNING = /^(seed|block\w*|tolerance|distribution)$/;

/**
 * Whether a parameter is shown directly: required ones, data, and the numbers that size the workload. An optional
 * parameter without a default only matters for user-provided data (for example, the rows of an uploaded matrix).
 */
function isPrimary(p: ParameterSchema, generating: boolean): boolean {
  if (p.required || isArray(p)) return true;
  if (p.defaultValue === null || p.defaultValue === undefined) return !generating;
  if (TUNING.test(p.name)) return false;
  return p.type === 'INTEGER' || p.type === 'DECIMAL';
}

const DIMENSIONS: Record<string, [string, string][]> = {
  matrixA: [['rowsA', 'colsA'], ['rows', 'cols']],
  matrixB: [['rowsB', 'colsB'], ['rows', 'cols']],
};

interface Props {
  algorithm: Algorithm;
  state: ParameterState;
  onChange: (state: ParameterState) => void;
  advanced: boolean;
  violations: Record<string, string>;
}

export function ParameterForm({ algorithm, state, onChange, advanced, violations }: Props) {
  const [showMore, setShowMore] = useState(false);
  const [uploadErrors, setUploadErrors] = useState<Record<string, string>>({});
  const choice = inputChoice(algorithm);
  const userData = state.values.input === 'INLINE';
  const names = new Set(algorithm.parameters.map((p) => p.name));
  const set = (name: string, value: string) => onChange({ ...state, values: { ...state.values, [name]: value } });

  const visible = algorithm.parameters.filter((p) => {
    if (p.name === 'input') return false;
    if (isArray(p) && choice && algorithm.inputParameters.includes(p.name)) return userData;
    return true;
  });
  const primary = visible.filter((p) => isPrimary(p, !!choice && !userData));
  const secondary = visible.filter((p) => !primary.includes(p));

  async function upload(p: ParameterSchema, file: File) {
    const form = new FormData();
    form.append('file', file);
    const params = new URLSearchParams({ algorithm: algorithm.id, parameter: p.name });
    try {
      const meta = await api<InputUpload>(`/api/v1/inputs?${params}`, { method: 'POST', form });
      const values = { ...state.values };
      for (const [r, c] of DIMENSIONS[p.name] ?? []) {
        if (names.has(r) && names.has(c)) {
          values[r] = String(meta.rows);
          values[c] = String(meta.columns);
          break;
        }
      }
      onChange({ values, uploads: { ...state.uploads, [p.name]: meta } });
      setUploadErrors((errors) => ({ ...errors, [p.name]: '' }));
    } catch (e) {
      const message = e instanceof ApiError ? e.message : String(e);
      setUploadErrors((errors) => ({ ...errors, [p.name]: `Upload failed: ${message}` }));
    }
  }

  function field(p: ParameterSchema) {
    const id = `param-${p.name}`;
    const hint = [p.description, p.unit ? `Unit: ${p.unit}` : null,
      p.minimum !== null || p.maximum !== null ? `Range ${p.minimum ?? '−∞'} to ${p.maximum ?? '∞'}` : null,
      p.defaultValue !== null && p.defaultValue !== undefined ? `Default ${JSON.stringify(p.defaultValue)}` : null]
      .filter(Boolean).join(' · ');
    const error = violations[`parameters.${p.name}`] ?? violations[p.name] ?? (uploadErrors[p.name] || undefined);
    if (p.type === 'ENUM' || p.type === 'BOOLEAN') {
      const options = p.type === 'BOOLEAN' ? ['true', 'false'] : p.allowedValues;
      return (
        <Field key={p.name} label={p.name} hint={hint} error={error} htmlFor={id}>
          <SelectInput id={id} value={state.values[p.name] ?? ''} onChange={(e) => set(p.name, e.target.value)}>
            <option value="">Default{p.defaultValue !== null && p.defaultValue !== undefined ? ` (${String(p.defaultValue)})` : ''}</option>
            {options.map((o) => <option key={o} value={o}>{o}</option>)}
          </SelectInput>
        </Field>
      );
    }
    if (isArray(p)) {
      const uploaded = state.uploads[p.name];
      return (
        <Field key={p.name} label={p.name} hint={hint} error={error} htmlFor={id}>
          {p.name === 'coefficients' && <PolynomialInput onParsed={(c) => set(p.name, c.join(', '))} />}
          {uploaded ? (
            <div className="flex flex-wrap items-center gap-2 rounded-md border border-slate-200 p-2 text-sm dark:border-slate-800">
              <Badge tone="green">uploaded</Badge>
              <span className="font-mono text-xs">{uploaded.fileName}</span>
              <span className="text-slate-500">{uploaded.rows} × {uploaded.columns} = {uploaded.count} values · {formatBytes(uploaded.bytes)}</span>
              <Button variant="ghost" onClick={() => {
                const uploads = { ...state.uploads };
                delete uploads[p.name];
                onChange({ ...state, uploads });
              }}>Remove</Button>
            </div>
          ) : (
            <>
              <TextArea id={id} rows={3} placeholder="Values separated by commas, spaces or new lines (up to 10 000)"
                value={state.values[p.name] ?? ''} onChange={(e) => set(p.name, e.target.value)} />
              {p.uploadable && (
                <label className="inline-flex cursor-pointer items-center gap-2 text-sm text-blue-700 hover:underline dark:text-blue-400">
                  <Upload className="h-4 w-4" aria-hidden /> Upload a CSV or text file
                  <input type="file" accept=".csv,.txt,text/csv,text/plain" className="sr-only"
                    aria-label={`Upload values for ${p.name}`}
                    onChange={(e) => { const f = e.target.files?.[0]; if (f) void upload(p, f); e.target.value = ''; }} />
                </label>
              )}
            </>
          )}
        </Field>
      );
    }
    return (
      <Field key={p.name} label={p.name} hint={hint} error={error} htmlFor={id}>
        <TextInput id={id} inputMode={p.type === 'INTEGER' ? 'numeric' : 'decimal'} value={state.values[p.name] ?? ''}
          placeholder={p.defaultValue !== null && p.defaultValue !== undefined ? String(p.defaultValue) : ''}
          onChange={(e) => set(p.name, e.target.value)} />
      </Field>
    );
  }

  return (
    <div className="space-y-4">
      {choice && (
        <fieldset className="space-y-2">
          <legend className="text-sm font-medium">Input data</legend>
          <div className="flex flex-wrap gap-4 text-sm">
            <label className="flex items-center gap-2">
              <input type="radio" name="input" checked={!userData} onChange={() => set('input', 'GENERATED')} /> Generate test data (seeded, reproducible)
            </label>
            <label className="flex items-center gap-2">
              <input type="radio" name="input" checked={userData} onChange={() => set('input', 'INLINE')} /> Use my data (upload or paste)
            </label>
          </div>
        </fieldset>
      )}
      <div className="grid gap-4 md:grid-cols-2">{primary.map(field)}</div>
      {secondary.length > 0 && (advanced || showMore ? (
        <div className="grid gap-4 md:grid-cols-2">{secondary.map(field)}</div>
      ) : (
        <Button variant="ghost" onClick={() => setShowMore(true)}>More parameters ({secondary.length}) — defaults apply</Button>
      ))}
    </div>
  );
}

function PolynomialInput({ onParsed }: { onParsed: (coefficients: number[]) => void }) {
  const [text, setText] = useState('');
  const [result, setResult] = useState<string>();
  const [error, setError] = useState<string>();

  async function parse() {
    setError(undefined);
    try {
      const p = await api<{ coefficients: number[]; normalized: string; degree: number }>('/api/v1/expressions/polynomial',
        { body: { expression: text, variable: 'x' } });
      onParsed(p.coefficients);
      setResult(`${p.normalized} (degree ${p.degree})`);
    } catch (e) {
      setError(e instanceof ApiError ? (e.violations[0]?.message ?? e.message) : String(e));
    }
  }

  return (
    <div className="mb-2 space-y-1">
      <div className="flex gap-2">
        <TextInput aria-label="Polynomial" placeholder="Polynomial, for example 3x^2 - 2x + 1" value={text}
          onChange={(e) => setText(e.target.value)} />
        <Button onClick={() => void parse()} disabled={!text.trim()}>Use</Button>
      </div>
      {result && <p className="text-xs text-emerald-700 dark:text-emerald-400">Parsed as {result}; coefficients filled in below (constant term first).</p>}
      {error && <p className="text-xs text-rose-700 dark:text-rose-400">{error}</p>}
      <p className="text-xs text-slate-500">Only polynomials in x are accepted (numbers, x, ^, +, −, *). Nothing you type is executed.</p>
    </div>
  );
}
