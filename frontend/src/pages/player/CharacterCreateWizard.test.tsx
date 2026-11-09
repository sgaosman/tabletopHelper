import { describe, it, expect, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockCharacter, makeMockRace } from '../../test/mocks/handlers';
import type { Background, CharacterClassRef } from '../../types/reference';
import CharacterCreateWizard from './CharacterCreateWizard';

// A race whose spells are cast with the ability the player picks, as a Fairy's are (MotM)
const fairy = { ...makeMockRace('race-fairy', 'Fairy', '[]'), raceChoices: JSON.stringify({ spellAbility: ['INT', 'WIS', 'CHA'] }) };
const acolyte = {
  id: 'bg-acolyte', name: 'Acolyte', source: 'PHB',
  skillProficiencies: null, toolProficiencies: null, languageProficiencies: null,
} satisfies Background;
// A race with no choice of ability, and a class whose spells are cast with INT
const human = makeMockRace('race-human', 'Human', JSON.stringify([{ ability: 'STR', bonus: 1 }]));
const wizard = {
  id: 'cls-wizard', name: 'Wizard', source: 'PHB', hitDice: 6, primaryAbility: 'INT', savingThrowProficiencies: '["INT","WIS"]',
  armorProficiencies: '[]', weaponProficiencies: '["Daggers"]', toolProficiencies: '[]', skillChoices: '{}',
  spellcastingAbility: 'INT', features: '[]', startingEquipment: '[]', subclassLevel: 2, isSpellcaster: true,
  isPreparedCaster: true, isKnownCaster: false, isPactMagic: false, spellSlotProgression: '{}',
  multiclassRequirements: null, multiclassProficiencies: null,
} satisfies CharacterClassRef;

function renderWizard() {
  window.history.pushState({}, '', '/player/characters/new');
  return render(
    <Routes>
      <Route path="/player/characters/new" element={<CharacterCreateWizard />} />
      <Route path="/player/characters/:id" element={<p>Character sheet</p>} />
    </Routes>,
  );
}

describe('CharacterCreateWizard', () => {
  beforeEach(() => {
    localStorage.clear();
    setupAuthToken();
  });

  it("sends the race's chosen spellcasting ability, not the class's (D159)", async () => {
    let created: Record<string, unknown> | undefined;
    server.use(
      http.get('/api/reference/races', () => HttpResponse.json([fairy])),
      http.get('/api/reference/backgrounds', () => HttpResponse.json([acolyte])),
      http.post('/api/characters', async ({ request }) => {
        created = await request.json() as Record<string, unknown>;
        return HttpResponse.json(makeMockCharacter('char-new', 'Pip', 'Fighter', 1));
      }),
    );
    const user = userEvent.setup();
    renderWizard();
    const next = () => user.click(screen.getByRole('button', { name: /Next/ }));

    await user.type(screen.getByPlaceholderText('Enter character name'), 'Pip');
    await next();
    await user.click(await screen.findByRole('button', { name: /Fairy/ }));
    await user.click(screen.getByRole('button', { name: 'WIS' }));
    await next();
    await user.click(screen.getByRole('button', { name: 'Manual' }));
    await next();
    await user.click(await screen.findByRole('button', { name: /Fighter/ }));
    await waitFor(() => expect(screen.getByRole('button', { name: /Next/ })).toBeEnabled());
    await next();
    await user.click(await screen.findByRole('button', { name: /Acolyte/ }));
    await next();
    // Starting equipment: none in the mocks, so Next opens once it has loaded
    await waitFor(() => expect(screen.getByRole('button', { name: /Next/ })).toBeEnabled());
    await next();
    await user.click(screen.getByRole('button', { name: /Create Character/ }));

    expect(await screen.findByText('Character sheet')).toBeInTheDocument();
    expect(created).toMatchObject({ raceId: 'race-fairy', classId: 'cls-fighter', spellcastingAbility: 'WIS' });
  });

  it("sends no spellcasting ability when the race has no choice of it, leaving the class's to the server", async () => {
    let created: Record<string, unknown> | undefined;
    server.use(
      http.get('/api/reference/races', () => HttpResponse.json([human])),
      http.get('/api/reference/classes', () => HttpResponse.json([wizard])),
      http.get('/api/reference/backgrounds', () => HttpResponse.json([acolyte])),
      http.post('/api/characters', async ({ request }) => {
        created = await request.json() as Record<string, unknown>;
        return HttpResponse.json(makeMockCharacter('char-new', 'Pip', 'Wizard', 1));
      }),
    );
    const user = userEvent.setup();
    renderWizard();
    const next = () => user.click(screen.getByRole('button', { name: /Next/ }));

    await user.type(screen.getByPlaceholderText('Enter character name'), 'Pip');
    await next();
    await user.click(await screen.findByRole('button', { name: /Human/ }));
    await waitFor(() => expect(screen.getByRole('button', { name: /Next/ })).toBeEnabled());
    await next();
    await user.click(screen.getByRole('button', { name: 'Manual' }));
    await next();
    await user.click(await screen.findByRole('button', { name: /Wizard/ }));
    await waitFor(() => expect(screen.getByRole('button', { name: /Next/ })).toBeEnabled());
    await next();
    await user.click(await screen.findByRole('button', { name: /Acolyte/ }));
    await next();
    await waitFor(() => expect(screen.getByRole('button', { name: /Next/ })).toBeEnabled());
    await next();
    await next();
    await user.click(screen.getByRole('button', { name: /Create Character/ }));

    expect(await screen.findByText('Character sheet')).toBeInTheDocument();
    expect(created).toMatchObject({ raceId: 'race-human', classId: 'cls-wizard' });
    expect(created?.spellcastingAbility).toBeUndefined();
  });
});

describe('CharacterCreateWizard drafts', () => {
  // A draft as the wizard saved it before its state moved into one reducer (R8 G): a level 2 Elf Fighter acolyte
  // with the standard array, left on the Background step. The races, classes and backgrounds are the mock
  // handlers', with the acolyte added.
  const SAVED_DRAFT = '{"name":"Pip","alignment":"Chaotic Good","step":4,"abilityMethod":"standard",'
    + '"scores":{"strength":10,"dexterity":10,"constitution":10,"intelligence":10,"wisdom":10,"charisma":10},'
    + '"standardAssignments":{"strength":15,"dexterity":14,"constitution":13,"intelligence":12,"wisdom":10,"charisma":8},'
    + '"level":2,"raceId":"race-elf","classId":"cls-fighter","backgroundId":"bg-acolyte"}';

  // The request that draft made before R8 G, restored and created straight away
  const RESTORED_REQUEST = {
    name: 'Pip', level: 2, raceId: 'race-elf', classId: 'cls-fighter', backgroundId: 'bg-acolyte',
    alignment: 'Chaotic Good', abilityScoreMethod: 'standard', racialAbilityBonuses: [{ ability: 'DEX', bonus: 2 }],
    strength: 15, dexterity: 16, constitution: 13, intelligence: 12, wisdom: 10, charisma: 8,
    savingThrowProficiencies: ['STR', 'CON'], armorProficiencies: ['All Armor', 'Shields'],
    weaponProficiencies: ['Simple', 'Martial'],
  };

  let created: Record<string, unknown> | undefined;

  beforeEach(() => {
    localStorage.clear();
    setupAuthToken();
    created = undefined;
    localStorage.setItem('characterWizardDraft', SAVED_DRAFT);
  });

  function createHandler() {
    return http.post('/api/characters', async ({ request }) => {
      created = await request.json() as Record<string, unknown>;
      return HttpResponse.json(makeMockCharacter('char-new', 'Pip', 'Fighter', 2));
    });
  }

  /**
   * Goes back to the Class step and on again, which the restored class must allow, then on through the Equipment step
   * and creates the character.
   */
  async function checkClassAndCreate(user: ReturnType<typeof userEvent.setup>) {
    expect(await screen.findByText('Choose a Background')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Acolyte/ })).toHaveClass('border-ink');
    await user.click(screen.getByRole('button', { name: /Previous/ }));
    expect(screen.getByRole('button', { name: /^Fighter/ })).toHaveClass('border-ink');
    await waitFor(() => expect(screen.getByRole('button', { name: /Next/ })).toBeEnabled());
    await user.click(screen.getByRole('button', { name: /Next/ }));
    await user.click(screen.getByRole('button', { name: /Next/ }));
    await waitFor(() => expect(screen.getByRole('button', { name: /Next/ })).toBeEnabled());
    await user.click(screen.getByRole('button', { name: /Next/ }));
    await user.click(screen.getByRole('button', { name: /Create Character/ }));
    expect(await screen.findByText('Character sheet')).toBeInTheDocument();
  }

  it('restores a draft saved in the format it has always had, making the same request', async () => {
    server.use(http.get('/api/reference/backgrounds', () => HttpResponse.json([acolyte])), createHandler());
    const user = userEvent.setup();
    renderWizard();

    await user.click(await screen.findByRole('button', { name: 'Restore' }));

    await checkClassAndCreate(user);
    expect(created).toEqual(RESTORED_REQUEST);
  });

  it('restores the race, class and background when Restore is pressed before their lists have loaded', async () => {
    let release = () => {};
    const loaded = new Promise<void>(resolve => { release = resolve; });
    server.use(
      http.get('/api/reference/races', async () => {
        await loaded;
        return HttpResponse.json([makeMockRace('race-elf', 'Elf', JSON.stringify([{ ability: 'DEX', bonus: 2 }]))]);
      }),
      http.get('/api/reference/backgrounds', async () => {
        await loaded;
        return HttpResponse.json([acolyte]);
      }),
      createHandler(),
    );
    const user = userEvent.setup();
    renderWizard();

    await user.click(await screen.findByRole('button', { name: 'Restore' }));
    expect(screen.queryByText('You have an unsaved character draft. Restore it?')).not.toBeInTheDocument();
    release();

    await checkClassAndCreate(user);
    expect(created).toEqual(RESTORED_REQUEST);
  });

  it('forgets the draft when it is discarded', async () => {
    const user = userEvent.setup();
    renderWizard();

    await user.click(await screen.findByRole('button', { name: 'Discard' }));

    expect(localStorage.getItem('characterWizardDraft')).toBeNull();
    expect(screen.getByPlaceholderText('Enter character name')).toHaveValue('');
  });

  it('saves the draft as the player goes, in the same format', async () => {
    localStorage.clear();
    const user = userEvent.setup();
    renderWizard();

    await user.type(screen.getByPlaceholderText('Enter character name'), 'Pip');

    expect(JSON.parse(localStorage.getItem('characterWizardDraft') ?? 'null')).toEqual({
      name: 'Pip', alignment: '', step: 0, abilityMethod: 'standard',
      scores: { strength: 10, dexterity: 10, constitution: 10, intelligence: 10, wisdom: 10, charisma: 10 },
      standardAssignments: { strength: null, dexterity: null, constitution: null, intelligence: null, wisdom: null, charisma: null },
      level: 1, raceId: null, classId: null, backgroundId: null,
    });
  });
});
