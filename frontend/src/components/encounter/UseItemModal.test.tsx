import { describe, it, expect, beforeEach, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockCastSpellResponse, makeMockEncounter, makeMockParticipant } from '../../test/mocks/handlers';
import type { UsableItems } from '../../types/combat';
import type { SpellTargetingInfo } from '../../types/reference';
import { EncounterProvider } from '../../context/EncounterContext';
import { queryKeys } from '../../queries/keys';
import UseItemModal from './UseItemModal';

const kriv = makeMockParticipant('part-kriv', 'Kriv', 'PLAYER', { isCurrentTurn: true, characterId: 'char-kriv' });
const gimli = makeMockParticipant('part-gimli', 'Gimli', 'PLAYER', { characterId: 'char-gimli' });
const goblin = makeMockParticipant('part-goblin', 'Test Goblin', 'MONSTER');

const USABLE: UsableItems = {
  potions: [
    { name: 'Potion of Healing', left: 2, healing: '2d4 + 2', description: null },
    { name: 'Potion of Fire Breath', left: 1, healing: null, description: 'You can exhale fire.' },
  ],
  chargedItems: [{
    name: 'Wand of Fireballs', chargesLeft: 5, chargesMax: 7, saveDc: 15, attackBonus: null, unavailableReason: null,
    spells: [{
      name: 'Fireball', level: 3, castingTime: 'ACTION', unavailableReason: null,
      sources: [{
        source: 'ITEM', levels: [3, 4, 5, 6, 7], usesLeft: 5, usesMax: 7, itemName: 'Wand of Fireballs',
        chargeCosts: { 3: 1, 4: 2, 5: 3, 6: 4, 7: 5, 8: 6, 9: 7 },
      }],
    }],
  }],
  scrolls: [{
    name: 'Spell Scroll (1st Level)', level: 1, saveDc: 13, attackBonus: 5, checkDc: null, checkAbility: null,
    spell: {
      name: 'Shield', level: 1, castingTime: 'REACTION', unavailableReason: "Shield isn't on any of Kriv's classes' spell lists, so they can't read the scroll (DMG p.200)",
      sources: [{ source: 'SCROLL', levels: [], usesLeft: 1, usesMax: null, itemName: 'Spell Scroll (1st Level)' }],
    },
  }],
};

const TARGET_ANYONE: SpellTargetingInfo = {
  maxTargets: -1, selfOnly: false, canTargetSelf: true, canTargetAllies: true, canTargetEnemies: true,
  targetType: 'SINGLE_TARGET', hasRepeatEffect: false,
};

function usable(items: UsableItems = USABLE) {
  server.use(http.get('/api/encounters/:id/combat/usable-items', () => HttpResponse.json(items)));
}

/** Records each use-item request. */
function useItemRequests() {
  const bodies: unknown[] = [];
  server.use(http.post('/api/encounters/:id/combat/use-item', async ({ request }) => {
    bodies.push(await request.json());
    return HttpResponse.json(makeMockEncounter());
  }));
  return bodies;
}

async function renderModal() {
  const onClose = vi.fn();
  const { queryClient } = render(
    <EncounterProvider encounterId="enc-001">
      <UseItemModal participant={kriv} participants={[kriv, gimli, goblin]} onClose={onClose} />
    </EncounterProvider>,
  );
  await waitFor(() => expect(queryClient.getQueryData(queryKeys.encounters.detail('enc-001', 'player'))).toBeDefined());
  return { onClose, user: userEvent.setup() };
}

describe('UseItemModal (D210)', () => {
  beforeEach(() => {
    setupAuthToken();
  });

  it('lists potions, charged items and scrolls, and drinks a potion of healing as an action', async () => {
    usable();
    const bodies = useItemRequests();
    const { user } = await renderModal();

    const potions = await screen.findByRole('region', { name: 'Potions' });
    expect(within(potions).getByText('heals 2d4 + 2')).toBeInTheDocument();
    expect(within(potions).getByText('You can exhale fire.')).toBeInTheDocument();
    expect(screen.getByText('5/7 charges · save DC 15')).toBeInTheDocument();
    await user.click(within(potions).getAllByRole('button', { name: 'Drink (action)' })[0]);

    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toEqual({ participantId: 'part-kriv', itemName: 'Potion of Healing' });
    expect(await screen.findByText('Used Potion of Healing')).toBeInTheDocument();
  });

  it('gives a potion to another creature, naming it', async () => {
    usable();
    const bodies = useItemRequests();
    const { user } = await renderModal();

    const potions = await screen.findByRole('region', { name: 'Potions' });
    await user.selectOptions(within(potions).getAllByLabelText('Who drinks it')[0], 'Gimli');
    await user.click(within(potions).getByRole('button', { name: 'Give (action)' }));

    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toEqual({ participantId: 'part-kriv', itemName: 'Potion of Healing', targetId: 'part-gimli' });
  });

  it("casts a wand's spell through the cast flow, its charge cost per level shown and the item named in the request", async () => {
    usable();
    server.use(http.get('/api/reference/spells/targeting', () => HttpResponse.json(TARGET_ANYONE)));
    const casts: unknown[] = [];
    server.use(http.post('/api/encounters/:id/combat/cast-spell', async ({ request }) => {
      casts.push(await request.json());
      return HttpResponse.json(makeMockCastSpellResponse());
    }));
    const { user } = await renderModal();

    await user.click(await screen.findByRole('button', { name: 'Cast Fireball' }));
    await user.click(await screen.findByRole('button', { name: /Fireball/ }));
    expect(screen.getByRole('button', { name: /Level 4 — 2 charges/ })).toBeEnabled();
    expect(screen.getByRole('button', { name: /Level 8 — 6 charges/ })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: /Level 4 — 2 charges/ }));
    await user.click(await screen.findByRole('button', { name: /Test Goblin/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(screen.getByText("Save DC 15 (Wand of Fireballs's own)")).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cast Spell' }));

    await waitFor(() => expect(casts).toHaveLength(1));
    expect(casts[0]).toMatchObject({
      spellName: 'Fireball', spellSource: 'ITEM', slotLevel: 4, itemName: 'Wand of Fireballs', targetIds: ['part-goblin'],
    });
  });

  it("greys out a scroll whose spell isn't on the reader's lists, saying why", async () => {
    usable();
    await renderModal();

    const scrolls = await screen.findByRole('region', { name: 'Spell scrolls' });
    expect(within(scrolls).getByRole('button', { name: 'Read Shield' })).toBeDisabled();
    expect(within(scrolls).getByText(/isn't on any of Kriv's classes' spell lists/)).toBeInTheDocument();
  });

  it('says when there is nothing to use', async () => {
    usable({ potions: [], chargedItems: [], scrolls: [] });
    await renderModal();

    expect(await screen.findByText(/Nothing to use/)).toBeInTheDocument();
  });
});
