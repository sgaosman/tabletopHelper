import { describe, it, expect } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render } from '../../../test/test-utils';
import { server } from '../../../test/mocks/server';
import { makeMockCharacter } from '../../../test/mocks/handlers';
import type { ClassSpellcasting, PlayerCharacter, PreparedSpellsRequest, SpellbookRequest } from '../../../types/character';
import type { PageResponse, Spell } from '../../../types/reference';
import type { RunCommand } from './types';
import SpellsTab from './SpellsTab';

// Passes the command through, as the sheet does, so a test sees what reached the server
const runCommand: RunCommand = async command => {
  await command;
  return true;
};

function spell(id: string, name: string, level: number): Spell {
  return {
    id, name, level, school: 'Evocation', castingTime: '1 action', rangeDistance: '60 feet', components: null,
    duration: 'Instantaneous', concentration: false, ritual: false, description: null, higherLevels: null,
    classes: ['Wizard'], damageType: null, damageDice: null, saveAbility: null, source: 'PHB',
  };
}

function casting(overrides: Partial<ClassSpellcasting>): ClassSpellcasting {
  return {
    className: 'Wizard', classLevel: 3, ability: 'INT', spellSaveDc: 13, spellAttackBonus: 5,
    highestSpellLevel: 2, cantripsKnown: 3, preparedSpells: 6, pactMagic: false,
    spellList: overrides.className ?? 'Wizard',
    ...overrides,
  };
}

function renderTab(char: PlayerCharacter) {
  return render(<SpellsTab char={char} spellsKnown={char.spellsKnown ?? []} spellSlots={char.spellSlots ?? {}}
    runCommand={runCommand} />);
}

// The box that a class's or race's spells are listed in, by its heading
function box(heading: string) {
  return screen.getByRole('heading', { name: heading }).closest('div.bg-card') as HTMLElement;
}

describe('SpellsTab', () => {
  it("shows each class's save DC, attack bonus and highest spell level as the server works them out (D167)", () => {
    const char = {
      ...makeMockCharacter('char-001', 'Elara', 'Wizard / Cleric', 5),
      spellcasting: [
        casting({}),
        casting({ className: 'Cleric', classLevel: 2, ability: 'WIS', spellSaveDc: 12, spellAttackBonus: 4,
          highestSpellLevel: 1, preparedSpells: 3 }),
      ],
    };

    renderTab(char);

    expect(screen.getByText('Spellcasting by Class')).toBeInTheDocument();
    expect(screen.getByText('13')).toBeInTheDocument();
    expect(screen.getByText('12')).toBeInTheDocument();
    expect(screen.getByText('+4')).toBeInTheDocument();
    expect(within(box('Cleric Spells')).getByText('Prepared (0/3)')).toBeInTheDocument();
    expect(within(box('Wizard Spells')).getByText('Prepared (0/6)')).toBeInTheDocument();
  });

  it('gives a 1st-level Paladin no spells to prepare', () => {
    const char = {
      ...makeMockCharacter('char-002', 'Aric', 'Paladin', 1),
      spellcasting: [casting({ className: 'Paladin', classLevel: 1, ability: 'CHA', spellSaveDc: 10,
        spellAttackBonus: 2, highestSpellLevel: 0, cantripsKnown: 0, preparedSpells: 0 })],
    };

    renderTab(char);

    expect(within(box('Paladin Spells')).getByText('Prepared (0/0)')).toBeInTheDocument();
  });

  it("reads an Eldritch Knight's known spells and cantrips from the server", async () => {
    const char = {
      ...makeMockCharacter('char-003', 'Gwen', 'Fighter', 4),
      spellsKnown: [
        { name: 'Fire Bolt', level: 0, source: 'class:Fighter' },
        { name: 'Shield', level: 1, source: 'class:Fighter' },
      ],
      spellcasting: [casting({ className: 'Fighter', subclassName: 'Eldritch Knight', spellList: 'Wizard', classLevel: 4,
        highestSpellLevel: 1, cantripsKnown: 2, spellsKnown: 4, preparedSpells: null })],
    };

    renderTab(char);

    // The subclass is named once the class tables, served by the mocked endpoint, say it is a third caster
    await screen.findByRole('heading', { name: 'Fighter (Eldritch Knight) Spells' });
    const knight = box('Fighter (Eldritch Knight) Spells');
    expect(within(knight).getByText('Cantrips (1/2)')).toBeInTheDocument();
    expect(within(knight).getByText('Known Spells (1/4)')).toBeInTheDocument();
  });

  it('shows the main numbers for race and feat spells beside the class ones', () => {
    const char = {
      ...makeMockCharacter('char-004', 'Zara', 'Wizard', 3),
      spellcastingAbility: 'INT', spellSaveDc: 13, spellAttackBonus: 5,
      spellsKnown: [{ name: 'Hellish Rebuke', level: 1, source: 'race:Tiefling', usesPerLongRest: 1 }],
      spellcasting: [casting({})],
    };

    renderTab(char);

    expect(screen.getByText('Race & Feat')).toBeInTheDocument();
    expect(box('Tiefling Spells')).toBeInTheDocument();
  });

  it("prepares a spell by sending the class's prepared spells, which the server checks (D158)", async () => {
    const sent: PreparedSpellsRequest[] = [];
    server.use(http.put('/api/characters/:id/spells/prepared', async ({ params, request }) => {
      sent.push(await request.json() as PreparedSpellsRequest);
      return HttpResponse.json(makeMockCharacter(params.id as string, 'Elara', 'Cleric', 3));
    }));
    const char = {
      ...makeMockCharacter('char-005', 'Elara', 'Cleric', 3),
      spellsKnown: [
        { name: 'Bless', level: 1, source: 'class:Cleric', prepared: true },
        { name: 'Cure Wounds', level: 1, source: 'class:Cleric', prepared: false },
        { name: 'Sanctuary', level: 1, source: 'class:Cleric', prepared: true, alwaysPrepared: true },
      ],
      spellcasting: [casting({ className: 'Cleric', ability: 'WIS', preparedSpells: 4 })],
    };
    renderTab(char);
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: 'Prepare Cure Wounds' }));
    await waitFor(() => expect(sent).toEqual([{ className: 'Cleric', spells: ['Bless', 'Cure Wounds'] }]));
    await user.click(screen.getByRole('button', { name: 'Unprepare Bless' }));
    await waitFor(() => expect(sent[1]).toEqual({ className: 'Cleric', spells: [] }));
  });

  it("has no way to rewrite a class's cantrips or known spells: they come with its levels' choices (D159)", () => {
    const char = {
      ...makeMockCharacter('char-006', 'Mira', 'Sorcerer', 3),
      spellsKnown: [{ name: 'Fire Bolt', level: 0, source: 'class:Sorcerer' }, { name: 'Shield', level: 1, source: 'class:Sorcerer' }],
      spellcasting: [casting({ className: 'Sorcerer', ability: 'CHA', cantripsKnown: 4, spellsKnown: 4, preparedSpells: null })],
    };

    renderTab(char);

    expect(within(box('Sorcerer Spells')).getByText('Known Spells (1/4)')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Manage Cantrips' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Manage Known' })).not.toBeInTheDocument();
  });

  it("copies spells into a wizard's spellbook, which costs no choice", async () => {
    let sent: SpellbookRequest | undefined;
    server.use(
      http.get('/api/reference/spells', () => HttpResponse.json({
        content: [spell('spell-1', 'Magic Missile', 1), spell('spell-2', 'Shield', 1)],
        totalElements: 2, totalPages: 1, number: 0, size: 50,
      } satisfies PageResponse<Spell>)),
      http.post('/api/characters/:id/spellbook', async ({ params, request }) => {
        sent = await request.json() as SpellbookRequest;
        return HttpResponse.json(makeMockCharacter(params.id as string, 'Elminster', 'Wizard', 3));
      }),
    );
    const char = {
      ...makeMockCharacter('char-008', 'Elminster', 'Wizard', 3),
      spellsKnown: [{ name: 'Magic Missile', level: 1, source: 'class:Wizard', prepared: false }],
      spellcasting: [casting({})],
    };
    renderTab(char);
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: '+ Spellbook' }));
    const dialog = screen.getByRole('dialog', { name: 'Add to Spellbook' });
    await user.click(await within(dialog).findByRole('button', { name: /Shield/ }));
    await user.click(within(dialog).getByRole('button', { name: 'Save Changes' }));

    await waitFor(() => expect(sent).toEqual({ spells: ['Shield'] }));
  });

  it("has no way to add feat spells by hand: a feat's spells come with the feat", () => {
    const char = {
      ...makeMockCharacter('char-007', 'Zara', 'Wizard', 3),
      spellsKnown: [{ name: 'Misty Step', level: 2, source: 'feat:Fey Touched', usesPerLongRest: 1 }],
      spellcasting: [casting({})],
    };

    renderTab(char);

    expect(box('Fey Touched')).toBeInTheDocument();
    expect(screen.queryByText(/Add Feat Spells/)).not.toBeInTheDocument();
    expect(screen.queryByTitle('Remove feat spells')).not.toBeInTheDocument();
  });

  it("opens a spell's details in a dialog titled with its name, which Escape closes, giving focus back", async () => {
    server.use(http.get('/api/reference/spells', () => HttpResponse.json({
      // The search matches part of a name: the spell of exactly that name is the one shown
      content: [{ ...spell('s-2', 'Shield of Faith', 1), source: 'XGE' }, spell('s-1', 'Shield', 1)],
      totalElements: 2, totalPages: 1, number: 0, size: 20,
    } satisfies PageResponse<Spell>)));
    const char = {
      ...makeMockCharacter('char-007', 'Zara', 'Wizard', 3),
      spellsKnown: [{ name: 'Shield', level: 1, source: 'class:Wizard', prepared: true }],
      spellcasting: [casting({})],
    };
    renderTab(char);
    const user = userEvent.setup();
    const opener = screen.getByRole('button', { name: /^Shield/ });

    await user.click(opener);

    const dialog = screen.getByRole('dialog', { name: 'Shield' });
    expect(await within(dialog).findByText('Source: PHB')).toBeInTheDocument();
    expect(dialog.contains(document.activeElement)).toBe(true);
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(opener).toHaveFocus();
  });
});
