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
    await user.click(screen.getByRole('button', { name: /Create Character/ }));

    expect(await screen.findByText('Character sheet')).toBeInTheDocument();
    expect(created).toMatchObject({ raceId: 'race-fairy', classId: 'cls-fighter', spellcastingAbility: 'WIS' });
  });

  it("sends the class's spellcasting ability when the race has no choice of it", async () => {
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
    await next();
    await user.click(screen.getByRole('button', { name: /Create Character/ }));

    expect(await screen.findByText('Character sheet')).toBeInTheDocument();
    expect(created).toMatchObject({ raceId: 'race-human', classId: 'cls-wizard', spellcastingAbility: 'INT' });
  });
});
