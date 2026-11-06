import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { makeMockCastSpellResponse } from '../../../test/mocks/handlers';
import ResultView from './ResultView';

describe('ResultView', () => {
  it("shows the spell, its slot level, the summary and each target's rolls and effects", () => {
    render(
      <ResultView
        onClose={vi.fn()}
        result={makeMockCastSpellResponse({
          spellName: 'Hold Person', slotLevelUsed: 2, resultSummary: 'Hold Person: one fails, one saves.',
          targets: [
            { targetId: 'part-goblin', targetName: 'Test Goblin', outcome: 'failed', saveRoll: 9, conditionsApplied: ['paralyzed'] },
            { targetId: 'part-orc', targetName: 'Orc', outcome: 'saved', saveRoll: 17, conditionsApplied: [] },
            { targetId: 'part-legolas', targetName: 'Legolas', outcome: 'hit', healing: 8, conditionsApplied: [] },
          ],
        })}
      />,
    );

    expect(screen.getByText('at level 2')).toBeInTheDocument();
    expect(screen.getByText('Hold Person: one fails, one saves.')).toBeInTheDocument();
    expect(screen.getByText('Save: 9')).toBeInTheDocument();
    expect(screen.getByText('paralyzed')).toBeInTheDocument();
    expect(screen.getByText('Save: 17')).toBeInTheDocument();
    expect(screen.getByText('8 heal')).toBeInTheDocument();
  });

  it('shows no slot level for a cantrip', () => {
    render(<ResultView onClose={vi.fn()} result={makeMockCastSpellResponse()} />);

    expect(screen.getByText('Fire Bolt hits Test Goblin for 7 fire damage.')).toBeInTheDocument();
    expect(screen.getByText('Roll: 18')).toBeInTheDocument();
    expect(screen.getByText('7 dmg')).toBeInTheDocument();
    expect(screen.queryByText(/at level/)).not.toBeInTheDocument();
  });

  it('asks the DM to adjudicate, without target rows, when the server did not resolve it', () => {
    render(
      <ResultView
        onClose={vi.fn()}
        result={makeMockCastSpellResponse({ autoResolved: false, manualResolutionReason: 'No effect template' })}
      />,
    );

    expect(screen.getByText(/Requires DM adjudication: No effect template/)).toBeInTheDocument();
    expect(screen.queryByText('7 dmg')).not.toBeInTheDocument();
  });

  it('closes from Done', async () => {
    const onClose = vi.fn();
    render(<ResultView onClose={onClose} result={makeMockCastSpellResponse()} />);

    await userEvent.setup().click(screen.getByRole('button', { name: 'Done' }));

    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
