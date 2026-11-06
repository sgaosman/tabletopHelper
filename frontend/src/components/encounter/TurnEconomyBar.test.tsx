import { describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { makeMockParticipant } from '../../test/mocks/handlers';
import type { EncounterParticipant, TurnEconomy } from '../../types/encounter';
import TurnEconomyBar from './TurnEconomyBar';

const FRESH: TurnEconomy = {
  actions: 1, actionsUsed: 0, attackActionTaken: false, attacksLeft: 0, bonusActionUsed: false,
  reactionUsed: false, objectInteractionUsed: false, bonusActionSpellCast: false, otherSpellCast: false,
  dashed: false, disengaged: false,
};

function fighter(economy: Partial<TurnEconomy> = {}, overrides: Partial<EncounterParticipant> = {}) {
  return makeMockParticipant('part-fighter', 'Brienne', 'PLAYER', {
    turnEconomy: { ...FRESH, ...economy }, attacksPerAction: 2, speed: { walk: 30 }, ...overrides,
  });
}

describe('TurnEconomyBar', () => {
  it('shows a fresh turn as available pips, with the speed', () => {
    render(<TurnEconomyBar participant={fighter()} variant="row" />);

    const bar = screen.getByRole('group', { name: "Brienne's turn economy" });
    expect(within(bar).getByRole('img', { name: 'Action available' })).toBeInTheDocument();
    expect(within(bar).getByRole('img', { name: 'Bonus action available' })).toBeInTheDocument();
    expect(within(bar).getByRole('img', { name: 'Reaction available' })).toBeInTheDocument();
    expect(within(bar).getByRole('img', { name: 'Object interaction available' })).toBeInTheDocument();
    expect(screen.getByText('Speed 30 ft')).toBeInTheDocument();
    expect(screen.queryByText(/^Attack \d/)).not.toBeInTheDocument();
    expect(screen.queryByText('Dashed')).not.toBeInTheDocument();
  });

  it('dims what is spent, and shows the attacks left while an Attack action is open (PHB p.72)', () => {
    render(<TurnEconomyBar participant={fighter({ actionsUsed: 1, attackActionTaken: true, attacksLeft: 1, reactionUsed: true })} variant="row" />);

    expect(screen.getByRole('img', { name: 'Action used' })).toHaveClass('opacity-50');
    expect(screen.getByRole('img', { name: 'Reaction used' })).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'Bonus action available' })).not.toHaveClass('opacity-50');
    expect(screen.getByLabelText('1 of 2 attacks left in the Attack action')).toHaveTextContent('Attack 1/2');
  });

  it('numbers the actions after Action Surge, and shows its badge with Dash and Disengage', () => {
    render(
      <TurnEconomyBar
        participant={fighter({ actions: 2, actionsUsed: 1, actionSurgeUsed: true, dashed: true, disengaged: true },
          { speed: { walk: 30, fly: 60 } })}
        variant="card"
      />,
    );

    expect(screen.getByRole('img', { name: 'Action 1 used' })).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'Action 2 available' })).toBeInTheDocument();
    expect(screen.getByText('Action Surge')).toBeInTheDocument();
    expect(screen.getByText('Dashed')).toBeInTheDocument();
    expect(screen.getByText('Disengaged')).toBeInTheDocument();
    expect(screen.getByText(/Speed 30 ft, fly 60 ft/)).toHaveTextContent('Speed 30 ft, fly 60 ft · Dash: 60 ft');
    expect(screen.getByText('This Turn')).toBeInTheDocument();
  });

  it('shows how far the DM has gone over the actions', () => {
    render(<TurnEconomyBar participant={fighter({ actionsUsed: 2 })} variant="row" />);

    expect(screen.getByText('+1 over')).toBeInTheDocument();
  });

  it('shows nothing for a participant without a turn economy, as a monster in the Player View (D157)', () => {
    const { container } = render(
      <TurnEconomyBar participant={makeMockParticipant('part-goblin', 'Goblin', 'MONSTER', { turnEconomy: null })} variant="row" />,
    );

    expect(container).toBeEmptyDOMElement();
  });

  it('has no controls without the DM\'s edit', () => {
    render(<TurnEconomyBar participant={fighter()} variant="card" />);

    expect(screen.queryAllByRole('button')).toHaveLength(0);
  });

  it('lets the DM toggle each pip, sending the whole economy back (D174)', async () => {
    const onEdit = vi.fn();
    const economy = { ...FRESH, actionsUsed: 1, attackActionTaken: true, attacksLeft: 1, dashed: true };
    render(<TurnEconomyBar participant={fighter(economy)} variant="row" onEdit={onEdit} />);
    const user = userEvent.setup();

    expect(screen.getByRole('button', { name: 'Action used' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Bonus action available' })).toHaveAttribute('aria-pressed', 'false');

    await user.click(screen.getByRole('button', { name: 'Bonus action available' }));
    expect(onEdit).toHaveBeenLastCalledWith({ ...economy, bonusActionUsed: true });

    await user.click(screen.getByRole('button', { name: 'Action used' }));
    expect(onEdit).toHaveBeenLastCalledWith({ ...economy, actionsUsed: 0 });

    await user.click(screen.getByRole('button', { name: 'Reaction available' }));
    expect(onEdit).toHaveBeenLastCalledWith({ ...economy, reactionUsed: true });

    await user.click(screen.getByRole('button', { name: 'Object interaction available' }));
    expect(onEdit).toHaveBeenLastCalledWith({ ...economy, objectInteractionUsed: true });
  });
});
