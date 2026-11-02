import { describe, it, expect } from 'vitest';
import { useState } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AbilityScoresStep from '../AbilityScoresStep';
import type { AbilityScores } from '../types';

function Harness() {
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
      racialBonuses={{}} pointBuyTotal={0}
    />
  );
}

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
