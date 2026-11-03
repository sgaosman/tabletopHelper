import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import RaceStep, { type RaceStepProps } from '../RaceStep';
import { makeMockRace } from '../../../../test/mocks/handlers';

// The DMG's orc: STR +2, CON +1 and INT -2
const orc = makeMockRace('race-orc', 'Orc', JSON.stringify([
  { ability: 'STR', bonus: 2 }, { ability: 'CON', bonus: 1 }, { ability: 'INT', bonus: -2 },
]));
const halfElf = makeMockRace('race-half-elf', 'Half-Elf', JSON.stringify([
  { ability: 'CHA', bonus: 2 }, { ability: 'CHOOSE', from: ['STR', 'DEX', 'CON', 'INT', 'WIS'], count: 2, bonus: 1 },
]));

function renderStep(overrides: Partial<RaceStepProps> = {}) {
  const noop = () => {};
  const props: RaceStepProps = {
    filteredRaces: [orc, halfElf],
    raceSearch: '',
    setRaceSearch: noop,
    selectedRace: null,
    setSelectedRace: noop,
    raceChoiceReqs: [],
    raceChoiceSelections: {},
    handleRaceChoice: noop,
    raceChoicesComplete: true,
    raceFeats: 0,
    bonusAssignments: [],
    handleBonusAssignment: noop,
    isVanillaHuman: false,
    ...overrides,
  };
  return render(<RaceStep {...props} />);
}

describe('RaceStep', () => {
  it("shows a race card's negative ability bonus with its own sign", () => {
    renderStep();

    expect(screen.getByText('STR +2, CON +1, INT -2')).toBeInTheDocument();
    expect(screen.getByText('CHA +2, Choose 2x +1')).toBeInTheDocument();
    expect(screen.queryByText(/\+-/)).not.toBeInTheDocument();
  });

  it('offers to reassign a negative bonus with its own sign', () => {
    renderStep({
      selectedRace: orc,
      bonusAssignments: [
        { bonus: 2, ability: 'strength' }, { bonus: 1, ability: 'constitution' }, { bonus: -2, ability: 'intelligence' },
      ],
    });

    expect(screen.getByText('Apply +2 to:')).toBeInTheDocument();
    expect(screen.getByText('Apply -2 to:')).toBeInTheDocument();
    expect(screen.queryByText(/\+-/)).not.toBeInTheDocument();
  });
});
