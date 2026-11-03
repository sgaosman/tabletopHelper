import { describe, it, expect, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockCharacter, makeMockPendingChoice } from '../../test/mocks/handlers';
import type { ClassSpellcasting, PendingChoice, PlayerCharacter } from '../../types/character';
import type { PageResponse, Spell } from '../../types/reference';
import ManageSpellsModal from './ManageSpellsModal';

function spell(name: string, level: number): Spell {
  return {
    id: `spell-${name.toLowerCase().replace(/\s+/g, '-')}`, name, level, school: 'Evocation', castingTime: '1 action',
    rangeDistance: '60 feet', components: null, duration: 'Instantaneous', concentration: false, ritual: false,
    description: null, higherLevels: null, classes: ['Sorcerer', 'Wizard'], damageType: null, damageDice: null,
    saveAbility: null, source: 'PHB',
  };
}

// The class's list, by level, as the spell search filters it
const LIST: Spell[] = [
  spell('Fire Bolt', 0), spell('Light', 0), spell('Mage Hand', 0), spell('Ray of Frost', 0),
  spell('Chromatic Orb', 1), spell('Magic Missile', 1), spell('Shield', 1), spell('Sleep', 1), spell('Misty Step', 2),
];

function casting(overrides: Partial<ClassSpellcasting>): ClassSpellcasting {
  return {
    className: 'Sorcerer', classLevel: 3, ability: 'CHA', spellSaveDc: 13, spellAttackBonus: 5,
    highestSpellLevel: 2, cantripsKnown: 4, spellsKnown: 4, preparedSpells: null, pactMagic: false,
    ...overrides,
  };
}

function renderChoice(char: PlayerCharacter, choice: PendingChoice, onComplete = vi.fn(), onSkip = vi.fn()) {
  const className = choice.className ?? '';
  render(<ManageSpellsModal char={char} type="choice" className={className}
    casting={char.spellcasting.find(c => c.className === className)} spellListClass={className}
    currentSpells={char.spellsKnown ?? []} choice={choice} onComplete={onComplete} onSkip={onSkip} onClose={() => {}} />);
  return { onComplete, onSkip };
}

/** Answers apply-choices with the character given, recording each body it is sent. */
function recordChoice(answer: PlayerCharacter) {
  const bodies: unknown[] = [];
  server.use(http.post('/api/characters/:id/apply-choices', async ({ request }) => {
    bodies.push(await request.json());
    return HttpResponse.json(answer);
  }));
  return bodies;
}

describe('ManageSpellsModal for a Pending Choice (D159)', () => {
  const sorcerer = {
    ...makeMockCharacter('char-001', 'Mira', 'Sorcerer', 3),
    spellsKnown: [
      { name: 'Fire Bolt', level: 0, source: 'class:Sorcerer' },
      { name: 'Magic Missile', level: 1, source: 'class:Sorcerer' },
      { name: 'Sleep', level: 1, source: 'class:Sorcerer' },
    ],
    spellcasting: [casting({})],
  };

  beforeEach(() => {
    setupAuthToken();
    server.use(http.get('/api/reference/spells', ({ request }) => {
      const params = new URL(request.url).searchParams;
      const levels = (params.get('level') ?? '').split(',').filter(Boolean).map(Number);
      const name = params.get('name')?.toLowerCase() ?? '';
      const content = LIST.filter(s => (levels.length === 0 || levels.includes(s.level)) && s.name.toLowerCase().includes(name));
      return HttpResponse.json({ content, totalElements: content.length, totalPages: 1, number: 0, size: 50 } satisfies PageResponse<Spell>);
    }));
  });

  it('learns exactly as many cantrips as the choice counts, none the class already knows', async () => {
    const choice = makeMockPendingChoice({
      id: 'choice-cantrips', type: 'CANTRIPS', className: 'Sorcerer', classLevel: 4, characterLevel: 4, count: 1,
      maxSpellLevel: 0,
    });
    const bodies = recordChoice(sorcerer);
    const { onComplete } = renderChoice(sorcerer, choice);
    const user = userEvent.setup();
    const dialog = screen.getByRole('dialog', { name: 'Learn Cantrips' });

    expect(within(dialog).getByText('Sorcerer 4: choose 1 (0/1)')).toBeInTheDocument();
    expect(await within(dialog).findByRole('button', { name: /Fire Bolt.*Known/ })).toBeDisabled();
    expect(within(dialog).queryByRole('button', { name: /Shield/ })).not.toBeInTheDocument();
    const learn = within(dialog).getByRole('button', { name: 'Learn Cantrips' });
    expect(learn).toBeDisabled();
    await user.click(within(dialog).getByRole('button', { name: /^Light/ }));
    // One of one: Mage Hand waits until Light goes
    expect(within(dialog).getByRole('button', { name: /Mage Hand/ })).toBeDisabled();
    await user.click(learn);

    await waitFor(() => expect(onComplete).toHaveBeenCalledWith(sorcerer));
    expect(bodies).toEqual([{ choiceId: 'choice-cantrips', spells: ['Light'] }]);
  });

  it("learns a known caster's spells up to the choice's level, and swaps one it knows for another", async () => {
    const choice = makeMockPendingChoice({
      id: 'choice-spells', type: 'SPELLS', className: 'Sorcerer', classLevel: 2, characterLevel: 2, count: 1,
      maxSpellLevel: 1, swap: true,
    });
    const bodies = recordChoice(sorcerer);
    const { onComplete } = renderChoice(sorcerer, choice);
    const user = userEvent.setup();
    const dialog = screen.getByRole('dialog', { name: 'Learn Spells' });

    expect(within(dialog).getByText('Sorcerer 2: choose 1 (0/1), up to level 1')).toBeInTheDocument();
    await user.click(await within(dialog).findByRole('button', { name: /^Shield/ }));
    // A 2nd-level spell is past what a Sorcerer 2 learns, and the spells it knows can't be learned again
    expect(within(dialog).queryByRole('button', { name: /Misty Step/ })).not.toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: /Sleep.*Known/ })).toBeDisabled();
    const replacement = within(dialog).getByRole('combobox', { name: 'Replacement spell' });
    expect(replacement).toBeDisabled();
    await user.selectOptions(within(dialog).getByRole('combobox', { name: 'Spell to replace' }), 'Sleep');
    // The swap needs its replacement, one neither known nor chosen, before the choice can be made
    expect(within(dialog).getByRole('button', { name: 'Learn Spells' })).toBeDisabled();
    expect(within(replacement).getAllByRole('option').map(o => o.textContent))
      .toEqual(['Choose its replacement', 'Chromatic Orb (Lv 1)']);
    await user.selectOptions(replacement, 'Chromatic Orb');
    await user.click(within(dialog).getByRole('button', { name: 'Learn Spells' }));

    await waitFor(() => expect(onComplete).toHaveBeenCalledWith(sorcerer));
    expect(bodies).toEqual([{
      choiceId: 'choice-spells', spells: ['Shield'], swap: { replace: 'Sleep', with: 'Chromatic Orb' },
    }]);
  });

  it("copies a wizard's level's spells into its spellbook, with no swap", async () => {
    const wizard = {
      ...makeMockCharacter('char-002', 'Elminster', 'Wizard', 3),
      spellsKnown: [{ name: 'Magic Missile', level: 1, source: 'class:Wizard', prepared: false }],
      spellcasting: [casting({ className: 'Wizard', ability: 'INT', spellsKnown: null, preparedSpells: 6, cantripsKnown: 3 })],
    };
    const choice = makeMockPendingChoice({
      id: 'choice-spellbook', type: 'SPELLS', className: 'Wizard', classLevel: 3, characterLevel: 3, count: 2,
      maxSpellLevel: 2,
    });
    const bodies = recordChoice(wizard);
    renderChoice(wizard, choice);
    const user = userEvent.setup();
    const dialog = screen.getByRole('dialog', { name: 'Add to Spellbook' });

    expect(await within(dialog).findByRole('button', { name: /Magic Missile.*In Spellbook/ })).toBeDisabled();
    expect(within(dialog).queryByRole('combobox', { name: 'Spell to replace' })).not.toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: /^Shield/ }));
    await user.click(within(dialog).getByRole('button', { name: /^Misty Step/ }));
    await user.click(within(dialog).getByRole('button', { name: 'Add to Spellbook' }));

    await waitFor(() => expect(bodies).toEqual([{ choiceId: 'choice-spellbook', spells: ['Shield', 'Misty Step'] }]));
  });

  it("leaves the choice pending when Skip is clicked, and shows the server's message when it refuses one", async () => {
    server.use(http.post('/api/characters/:id/apply-choices', () =>
      HttpResponse.json({ error: 'Choose 1 Sorcerer cantrip, not 2' }, { status: 400 })));
    const choice = makeMockPendingChoice({
      id: 'choice-cantrips', type: 'CANTRIPS', className: 'Sorcerer', classLevel: 4, characterLevel: 4, count: 1,
      maxSpellLevel: 0,
    });
    const { onSkip } = renderChoice(sorcerer, choice);
    const user = userEvent.setup();
    const dialog = screen.getByRole('dialog', { name: 'Learn Cantrips' });

    await user.click(await within(dialog).findByRole('button', { name: /^Light/ }));
    await user.click(within(dialog).getByRole('button', { name: 'Learn Cantrips' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Choose 1 Sorcerer cantrip, not 2');
    await user.click(within(dialog).getByRole('button', { name: 'Skip' }));

    expect(onSkip).toHaveBeenCalled();
  });
});
