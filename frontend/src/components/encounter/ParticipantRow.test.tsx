import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { makeMockCondition, makeMockLogEntry, makeMockParticipant, makeMockSpellEffect } from '../../test/mocks/handlers';
import ParticipantRow from './ParticipantRow';
import ConditionChips from './ConditionChips';
import ActiveSpells from './ActiveSpells';
import DeathSaves from './DeathSaves';
import SpellSlotPips from './SpellSlotPips';
import HpReadout from './HpReadout';
import CombatLog from './CombatLog';

const legolas = (overrides: Parameters<typeof makeMockParticipant>[3] = {}) =>
  makeMockParticipant('p', 'Legolas', 'PLAYER', { initiative: 19, characterClass: 'Ranger', ...overrides });

describe('ParticipantRow', () => {
  it('shows the initiative and name, with the left edge in the class colour', () => {
    render(<ParticipantRow participant={legolas()} current={false} />);
    expect(screen.getByText('19')).toBeInTheDocument();
    const row = screen.getByText('Legolas').closest('[style]');
    expect(row?.getAttribute('style')).toContain('var(--color-cls-ranger)');
    expect(row).not.toHaveAttribute('aria-current');
  });

  it('a monster has the monster colour, and no initiative shows a dash', () => {
    render(<ParticipantRow participant={makeMockParticipant('m', 'Test Goblin', 'MONSTER', { initiative: null })} current={false} />);
    expect(screen.getByText('—')).toBeInTheDocument();
    expect(screen.getByText('Test Goblin').closest('[style]')?.getAttribute('style')).toContain('var(--color-monster)');
  });

  it('marks the current turn, and rings a highlighted row', () => {
    const { rerender } = render(<ParticipantRow participant={legolas()} current />);
    expect(screen.getByText('Legolas').closest('[style]')).toHaveAttribute('aria-current', 'step');

    rerender(<ParticipantRow participant={legolas()} current={false} highlighted />);
    expect(screen.getByText('Legolas').closest('[style]')).toHaveClass('ring-ink/30');
  });

  it('dims a participant that is down and strikes through the dead', () => {
    const { rerender } = render(<ParticipantRow participant={legolas({ lifeState: 'DYING' })} current={false} />);
    expect(screen.getByText('Legolas').closest('[style]')).toHaveClass('opacity-60');
    expect(screen.getByText('Legolas')).not.toHaveClass('line-through');

    rerender(<ParticipantRow participant={legolas({ lifeState: 'DEAD' })} current={false} />);
    expect(screen.getByText('Legolas')).toHaveClass('line-through');
  });

  it('puts each slot in its place', () => {
    render(
      <ParticipantRow participant={legolas()} current={false} leading={<span>arrows</span>} badges={<span>badge</span>}
        trailing={<span>trailing</span>}>
        <span>body</span>
      </ParticipantRow>,
    );
    for (const text of ['arrows', 'badge', 'body', 'trailing']) {
      expect(screen.getByText(text)).toBeInTheDocument();
    }
  });

  it('built without the DM-only props, offers no remove, Legendary Resistance or monster-action controls', () => {
    const dying = legolas({
      lifeState: 'DYING', hpCurrent: 0, deathSaveDue: true, concentrationSpell: 'Hunter\'s Mark',
      spellSlotsCurrent: { '1': { max: 2, used: 1 } },
      activeEffects: [makeMockSpellEffect()],
      activeConditions: [makeMockCondition('prone'), makeMockCondition('paralyzed', { repeatSave: { ability: 'CON', dc: 10, awaitingRoll: true } })],
    });
    render(
      <>
        <ParticipantRow participant={dying} current trailing={<HpReadout participant={dying} />}>
          <ConditionChips participant={dying} roundNumber={1} />
          <ActiveSpells participant={dying} roundNumber={1} />
          <DeathSaves participant={dying} />
          <SpellSlotPips slots={dying.spellSlotsCurrent} variant="row" />
        </ParticipantRow>
        <CombatLog entries={[makeMockLogEntry('log-1', 'Aboleth fails.', { legendaryResistanceEligible: true, lrResolved: false, lrSuccessDamage: 0 })]} />
      </>,
    );

    // The only button is the log's own toggle
    expect(screen.getAllByRole('button').map(b => b.textContent)).toEqual([expect.stringContaining('Combat Log')]);
    expect(screen.queryByText('Death save due')).not.toBeInTheDocument();
  });
});
