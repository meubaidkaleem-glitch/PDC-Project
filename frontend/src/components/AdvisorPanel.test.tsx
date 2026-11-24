import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { Advice } from '../api/types';
import recorded from '../test/fixtures/advice.json';
import { AdvisorPanel } from './AdvisorPanel';

const unreliable = recorded as unknown as Advice;

describe('AdvisorPanel', () => {
  it('says plainly when no reliable recommendation can be made', () => {
    expect(unreliable.reliable).toBe(false);
    render(<AdvisorPanel advice={unreliable} selected="SEQUENTIAL_CPU" onSelect={() => {}} advanced={false} />);
    expect(screen.getByText('A reliable recommendation cannot be made yet.')).toBeInTheDocument();
    expect(screen.getByText(/Neither an estimate nor a measurement exists/)).toBeInTheDocument();
    expect(screen.getByText('preferred, unconfirmed')).toBeInTheDocument();
    expect(screen.queryByText('recommended')).not.toBeInTheDocument();
  });

  it('presents a reliable recommendation with its basis and lets the user override it', async () => {
    const advice: Advice = {
      ...unreliable, reliable: true, recommendedMode: 'MULTITHREADED_CPU', confidence: 'MEDIUM', basis: 'HISTORICAL',
      explanation: 'Parallel execution is estimated to be fastest.',
      modes: unreliable.modes.map((m) => m.mode === 'MULTITHREADED_CPU'
        ? { ...m, verdict: 'RECOMMENDED', estimatedSeconds: 0.2, estimateBasis: 'HISTORICAL' }
        : m.mode === 'SEQUENTIAL_CPU' ? { ...m, verdict: 'SUITABLE', estimatedSeconds: 0.7, estimateBasis: 'HISTORICAL' } : m),
    };
    const onSelect = vi.fn();
    render(<AdvisorPanel advice={advice} selected="MULTITHREADED_CPU" onSelect={onSelect} advanced />);
    expect(screen.getByText('Recommended: Parallel CPU (threads).')).toBeInTheDocument();
    expect(screen.getByText('confidence: medium')).toBeInTheDocument();
    const parallel = screen.getByRole('row', { name: /Parallel CPU/ });
    expect(within(parallel).getByText('200 ms')).toBeInTheDocument();
    expect(within(parallel).getByText('calibrated estimate')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('radio', { name: 'Run in Sequential CPU' }));
    expect(onSelect).toHaveBeenCalledWith('SEQUENTIAL_CPU');
  });

  it('never offers modes the components do not implement', () => {
    render(<AdvisorPanel advice={unreliable} selected="SEQUENTIAL_CPU" onSelect={() => {}} advanced={false} />);
    expect(screen.getByRole('radio', { name: 'Run in GPU' })).toBeDisabled();
    expect(screen.getByRole('radio', { name: 'Run in Hybrid' })).toBeDisabled();
  });
});
