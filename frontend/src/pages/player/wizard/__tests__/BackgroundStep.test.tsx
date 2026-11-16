import { describe, it, expect } from 'vitest';
import { useReducer } from 'react';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import BackgroundStep from '../BackgroundStep';
import { initialWizardState, wizardReducer, type WizardState } from '../wizardReducer';
import { bgReplacementsComplete, buildCreateRequest } from '../selectors';
import { background, fighter, highElf, soldier } from './fixtures';

// A Fighter who chose Athletics and Perception, so the Soldier's Athletics is already held (PHB p.125, D226)
const athleteFighter: WizardState = {
  ...initialWizardState,
  name: 'Brienne',
  selectedRace: highElf,
  selectedClass: fighter,
  classEntries: [{ cls: fighter, level: 1, subclass: null, subclasses: [] }],
  selectedClassSkills: ['Athletics', 'Survival'],
};

let latest: WizardState = athleteFighter;

function Harness({ initial, backgrounds }: { initial: WizardState; backgrounds: Parameters<typeof BackgroundStep>[0]['backgrounds'] }) {
  const [state, dispatch] = useReducer(wizardReducer, initial);
  latest = state;
  return <BackgroundStep backgrounds={backgrounds} state={state} dispatch={dispatch} />;
}

describe('BackgroundStep: a background skill already held (PHB p.125, D226)', () => {
  it('asks for a replacement skill, offering only skills not held, and sends it', async () => {
    const user = userEvent.setup();
    render(<Harness initial={athleteFighter} backgrounds={[soldier]} />);

    await user.click(screen.getByRole('button', { name: /^Soldier/ }));
    expect(screen.getByRole('button', { name: /^Soldier/ })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText(/already granted by your race or class/)).toBeInTheDocument();
    expect(screen.queryByText(/from the character sheet/)).not.toBeInTheDocument();
    expect(screen.getByText('Choose a replacement skill to continue')).toBeInTheDocument();
    expect(bgReplacementsComplete(latest)).toBe(false);

    const picker = screen.getByRole('group', { name: 'Replacement for Athletics' });
    // Athletics and Survival (class), Perception (High Elf) and Intimidation (Soldier) are held
    for (const held of ['Athletics', 'Survival', 'Perception', 'Intimidation']) {
      expect(within(picker).queryByRole('button', { name: held })).not.toBeInTheDocument();
    }
    await user.click(within(picker).getByRole('button', { name: 'Stealth' }));

    expect(within(picker).getByRole('button', { name: 'Stealth' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByText('Choose a replacement skill to continue')).not.toBeInTheDocument();
    expect(bgReplacementsComplete(latest)).toBe(true);
    expect(buildCreateRequest(latest)?.skillProficiencies).toEqual(['Perception', 'Athletics', 'Survival', 'Intimidation', 'Stealth']);
  });

  it("leaves skills already held out of the background's skill choices", async () => {
    const user = userEvent.setup();
    const wanderer = background('bg-wanderer', 'Wanderer', { skillProficiencies: '[{"any":2}]' });
    render(<Harness initial={athleteFighter} backgrounds={[wanderer]} />);

    await user.click(screen.getByRole('button', { name: /^Wanderer/ }));

    expect(screen.queryByRole('button', { name: 'Athletics' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Perception' })).not.toBeInTheDocument();
    const arcana = screen.getByRole('button', { name: 'Arcana' });
    expect(arcana).toHaveAttribute('aria-pressed', 'false');
    await user.click(arcana);
    expect(arcana).toHaveAttribute('aria-pressed', 'true');
  });

  it("leaves languages the character already speaks out of the background's language choices", async () => {
    const user = userEvent.setup();
    const sage = background('bg-sage', 'Sage', { languageProficiencies: '[{"anyStandard":2}]' });
    render(<Harness initial={athleteFighter} backgrounds={[sage]} />);

    await user.click(screen.getByRole('button', { name: /^Sage/ }));

    // The High Elf speaks Common and Elvish
    expect(screen.queryByRole('button', { name: 'Common' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Elvish' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Dwarvish' })).toHaveAttribute('aria-pressed', 'false');
  });
});
