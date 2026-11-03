import { describe, it, expect } from 'vitest';
import { useState } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AbilityScoresStep from '../AbilityScoresStep';
import type { AbilityScores } from '../types';

function Harness({ racialBonuses = {} }: { racialBonuses?: Record<string, number> }) {
  const [method, setMethod] = useState<'standard' | 'pointbuy' | 'manual'>('standard');
  const [scores, setScores] = useState<AbilityScores>({
    strength: 10, dexterity: 10, constitution: 10, intelligence: 10, wisdom: 10, charisma: 10,
  });
  const [assignments, setAssignments] = useState<Record<string, number | null>>({});
  return (
    <AbilityScoresStep
      abilityMethod={method} setAbilityMethod={setMethod}
      scores={scores} setScores={setScores}
      standardAssignments={assignments} setStandardAssignments={setAssignments}
      racialBonuses={racialBonuses} pointBuyTotal={0}
    />
  );
}

describe('AbilityScoresStep racial bonuses', () => {
  it('shows each racial bonus with its own sign, a negative one as -2, in every method', async () => {
    render(<Harness racialBonuses={{ strength: 2, intelligence: -2 }} />);

    // The standard array, then point buy, then scores entered by hand
    for (const next of ['Point Buy', 'Manual', null]) {
      expect(screen.getByText('+2 racial')).toBeInTheDocument();
      expect(screen.getByText('-2 racial')).toBeInTheDocument();
      expect(screen.queryByText(/\+-/)).not.toBeInTheDocument();
      if (next) await userEvent.click(screen.getByRole('button', { name: next }));
    }
    // Point buy's 8, with INT -2, ends at 6
    expect(screen.getByText('Final: 6 (-2)')).toBeInTheDocument();
  });
});

describe('AbilityScoresStep point buy', () => {
  it('starts every score at 8 when you switch to point buy', async () => {
    render(<Harness />);

    await userEvent.click(screen.getByRole('button', { name: 'Point Buy' }));

    expect(screen.getAllByText('8', { exact: true })).toHaveLength(6);
  });

  it('keeps your point buy choices if you click Point Buy again', async () => {
    render(<Harness />);
    await userEvent.click(screen.getByRole('button', { name: 'Point Buy' }));
    await userEvent.click(screen.getAllByRole('button', { name: '+' })[0]);

    await userEvent.click(screen.getByRole('button', { name: 'Point Buy' }));

    expect(screen.getByText('9', { exact: true })).toBeInTheDocument();
    expect(screen.getAllByText('8', { exact: true })).toHaveLength(5);
  });
});
