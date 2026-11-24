import { useState } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { Algorithm } from '../api/types';
import algorithms from '../test/fixtures/algorithms.json';
import { EMPTY_PARAMETERS, ParameterForm, toDraftParameters } from './ParameterForm';
import type { ParameterState } from './ParameterForm';

const all = algorithms as unknown as Algorithm[];
const multiply = all.find((a) => a.id === 'matrix.multiply')!;
const statistics = all.find((a) => a.id === 'data.statistics')!;

function Harness({ algorithm, advanced = false, violations = {} }: { algorithm: Algorithm; advanced?: boolean; violations?: Record<string, string> }) {
  const [state, setState] = useState<ParameterState>(EMPTY_PARAMETERS);
  const draft = toDraftParameters(algorithm, state);
  return (
    <>
      <ParameterForm algorithm={algorithm} state={state} onChange={setState} advanced={advanced} violations={violations} />
      <output data-testid="draft">{JSON.stringify(draft)}</output>
    </>
  );
}

const draft = () => JSON.parse(screen.getByTestId('draft').textContent!);

describe('ParameterForm', () => {
  it('shows the workload size to beginners and keeps tuning knobs behind "More parameters"', () => {
    render(<Harness algorithm={multiply} />);
    for (const name of ['rowsA', 'colsA', 'colsB']) expect(screen.getByLabelText(name)).toBeInTheDocument();
    // rowsB only matters for user data; seed and block size are tuning.
    for (const name of ['rowsB', 'seed', 'blockSize']) expect(screen.queryByLabelText(name)).not.toBeInTheDocument();
    expect(screen.getByText(/More parameters/)).toBeInTheDocument();
  });

  it('shows every parameter in advanced mode', () => {
    render(<Harness algorithm={multiply} advanced />);
    for (const name of ['rowsA', 'rowsB', 'blockSize', 'seed', 'distribution']) {
      expect(screen.getByLabelText(name)).toBeInTheDocument();
    }
  });

  it('sends only what was typed, as numbers, so Core applies its defaults to the rest', async () => {
    render(<Harness algorithm={multiply} />);
    await userEvent.type(screen.getByLabelText('rowsA'), '128');
    expect(draft()).toEqual({ parameters: { rowsA: 128 }, inputs: {}, problems: {} });
  });

  it('reports syntax problems before anything is sent', async () => {
    render(<Harness algorithm={multiply} />);
    await userEvent.type(screen.getByLabelText('rowsA'), '12.5');
    expect(draft().problems).toEqual({ rowsA: 'Enter a whole number' });
    expect(draft().parameters).toEqual({});
  });

  it('asks for the data itself when the user provides it', async () => {
    render(<Harness algorithm={statistics} />);
    expect(screen.queryByLabelText('values')).not.toBeInTheDocument();
    await userEvent.click(screen.getByLabelText(/Use my data/));
    const values = screen.getByLabelText('values');
    await userEvent.type(values, '1, 2.5 3');
    expect(draft().parameters).toEqual({ input: 'INLINE', values: [1, 2.5, 3] });
    await userEvent.type(values, ', x');
    expect(draft().problems.values).toMatch(/numbers/);
  });

  it('shows the violations the backend returned next to their field', () => {
    render(<Harness algorithm={multiply} violations={{ 'parameters.rowsA': 'must be at least 1, got 0' }} />);
    expect(screen.getByText('must be at least 1, got 0')).toBeInTheDocument();
  });
});
