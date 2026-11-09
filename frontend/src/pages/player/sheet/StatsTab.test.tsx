import { describe, it, expect } from 'vitest';
import { screen, within } from '@testing-library/react';
import { render } from '../../../test/test-utils';
import { makeMockAdjustment, makeMockCharacter } from '../../../test/mocks/handlers';
import StatsTab from './StatsTab';

describe('StatsTab', () => {
  const char = makeMockCharacter('char-001', 'Gimli', 'Fighter', 5);

  const defaultProps = {
    char,
    savingThrows: ['str', 'con'],
    skillProfs: ['athletics', 'perception'],
    skillExpertises: [] as string[],
    resistances: [] as string[],
  };

  it('renders all major sections', () => {
    render(<StatsTab {...defaultProps} />);
    expect(screen.getByText('Saving Throws')).toBeInTheDocument();
    expect(screen.getByText('Skills')).toBeInTheDocument();
    expect(screen.getByText('Proficiency')).toBeInTheDocument();
    expect(screen.getByText('Passive Perception')).toBeInTheDocument();
  });

  it('shows expertise star icon', () => {
    render(<StatsTab {...defaultProps} skillExpertises={['athletics']} />);
    expect(screen.getByText('Proficient')).toBeInTheDocument();
    expect(screen.getByText('Expertise')).toBeInTheDocument();
  });

  it('shows proficiency section when character has proficiencies', () => {
    render(<StatsTab {...defaultProps} />);
    expect(screen.getByText('Proficiencies')).toBeInTheDocument();
  });

  it('handles empty resistances array', () => {
    render(<StatsTab {...defaultProps} resistances={[]} />);
    expect(screen.queryByText('Damage Resistances')).not.toBeInTheDocument();
  });

  it('shows how the AC adds up, as the server worked it out (M13.5)', () => {
    const armoured = {
      ...char,
      armourClass: 19,
      armourClassBreakdown: {
        total: 19,
        parts: [{ label: 'Chain Mail', amount: 16 }, { label: 'Shield', amount: 2 }, { label: 'DM adjustments', amount: 1 }],
        warnings: [],
        armourEntries: [0, 1],
      },
    };
    render(<StatsTab {...defaultProps} char={armoured} />);

    const section = screen.getByRole('region', { name: 'Armour Class' });
    expect(within(section).getByText('19')).toBeInTheDocument();
    expect(within(section).getByText('Chain Mail 16 + Shield 2 + DM adjustments 1 = 19')).toBeInTheDocument();
    expect(within(section).queryByRole('listitem')).not.toBeInTheDocument();
  });

  it('lists what the armour worn costs, such as armour without proficiency or the Strength it needs', () => {
    const burdened = {
      ...char,
      armourClassBreakdown: {
        total: 16,
        parts: [{ label: 'Chain Mail', amount: 16 }],
        warnings: [
          "Not proficient with Chain Mail: disadvantage on STR and DEX rolls, and can't cast spells",
          'Chain Mail needs STR 13: speed -10 ft',
        ],
        armourEntries: [0],
      },
    };
    render(<StatsTab {...defaultProps} char={burdened} />);

    const items = within(screen.getByRole('region', { name: 'Armour Class' })).getAllByRole('listitem');
    expect(items.map(item => item.textContent)).toEqual([
      "Not proficient with Chain Mail: disadvantage on STR and DEX rolls, and can't cast spells",
      'Chain Mail needs STR 13: speed -10 ft',
    ]);
  });

  it('renders stat grid cards', () => {
    render(<StatsTab {...defaultProps} />);
    expect(screen.getByText('XP')).toBeInTheDocument();
    expect(screen.getByText('Hit Dice')).toBeInTheDocument();
  });

  it("shows what is left of each class's Hit Dice, as the Short Rest dialog does", () => {
    const { unmount } = render(<StatsTab {...defaultProps} />);
    expect(screen.getByText('5/5 d10')).toBeInTheDocument();
    expect(screen.getByText('remaining')).toBeInTheDocument();
    unmount();

    const multiclass = {
      ...char, hitDice: { Fighter: { total: 2, remaining: 2, dieSize: 10 }, Wizard: { total: 3, remaining: 1, dieSize: 6 } },
    };
    render(<StatsTab {...defaultProps} char={multiclass} />);
    expect(screen.getByText('2/2 d10 · 1/3 d6')).toBeInTheDocument();
  });

  it('shows Hit Dice that are all spent as none left, not as the total', () => {
    const spent = {
      ...char, hitDice: { Fighter: { total: 5, remaining: 0, dieSize: 10 }, Wizard: { total: 1, remaining: 0, dieSize: 6 } },
    };
    render(<StatsTab {...defaultProps} char={spent} />);

    expect(screen.getByText('0/5 d10 · 0/1 d6')).toBeInTheDocument();
    expect(screen.queryByText('5d10 + 1d6')).not.toBeInTheDocument();
  });

  it('shows the level with an unknown die when the sheet has no Hit Dice', () => {
    render(<StatsTab {...defaultProps} char={{ ...char, hitDice: null }} />);
    expect(screen.getByText('5d?')).toBeInTheDocument();
  });

  it("lists the DM's adjustments that count, each with its reason, and leaves out the removed ones (D158)", () => {
    const adjusted = {
      ...char,
      dmAdjustments: [
        makeMockAdjustment({ id: 'adj-ring' }),
        makeMockAdjustment({ id: 'adj-feat', kind: 'FEAT', target: 'Lucky', amount: null, reason: 'Blessing of Tymora' }),
        makeMockAdjustment({ id: 'adj-old', target: 'STR', amount: 2, reason: 'Gauntlets, lost',
          removedAt: '2026-11-03T11:00:00Z', removedBy: 'user-001' }),
      ],
    };
    render(<StatsTab {...defaultProps} char={adjusted} />);

    const list = screen.getByRole('region', { name: 'DM Adjustments' });
    expect(within(list).getByText('+1 AC')).toBeInTheDocument();
    expect(within(list).getByText(/Ring of Protection/)).toBeInTheDocument();
    expect(within(list).getByText('Feat: Lucky')).toBeInTheDocument();
    expect(within(list).getByText(/Blessing of Tymora/)).toBeInTheDocument();
    expect(screen.queryByText(/Gauntlets, lost/)).not.toBeInTheDocument();
  });

  it('has no adjustments section when the DM has made none', () => {
    render(<StatsTab {...defaultProps} />);
    expect(screen.queryByRole('region', { name: 'DM Adjustments' })).not.toBeInTheDocument();
  });

  it('shows resistances, immunities and vulnerabilities, the DM\'s among them (D161)', () => {
    render(<StatsTab {...defaultProps} resistances={['fire']}
      char={{ ...char, damageResistances: ['fire'], damageImmunities: ['poison'], damageVulnerabilities: ['cold'] }} />);

    expect(screen.getByText('Damage Defences')).toBeInTheDocument();
    expect(screen.getByText('Resistant:')).toBeInTheDocument();
    expect(screen.getByText('fire')).toBeInTheDocument();
    expect(screen.getByText('poison')).toBeInTheDocument();
    expect(screen.getByText('Vulnerable:')).toBeInTheDocument();
    expect(screen.getByText('cold')).toBeInTheDocument();
  });
});
