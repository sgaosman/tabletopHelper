import { describe, it, expect } from 'vitest';
import { useReducer } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AbilityScoresStep from '../AbilityScoresStep';
import { initialWizardState, wizardReducer, type BonusAssignment } from '../wizardReducer';

function Harness({ bonusAssignments = [] }: { bonusAssignments?: BonusAssignment[] }) {
  const [state, dispatch] = useReducer(wizardReducer, { ...initialWizardState, standardAssignments: {}, bonusAssignments });
  return <AbilityScoresStep state={state} dispatch={dispatch} />;
}

describe('AbilityScoresStep racial bonuses', () => {
  it('shows each racial bonus with its own sign, a negative one as -2, in every method', async () => {
    render(<Harness bonusAssignments={[{ bonus: 2, ability: 'strength' }, { bonus: -2, ability: 'intelligence' }]} />);

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
