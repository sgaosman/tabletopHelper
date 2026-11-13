import { describe, it, expect, beforeEach } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { render, setupAuthToken } from '../../test/test-utils';
import type { EncounterParticipant } from '../../types/encounter';
import EncounterBuilderPage from './EncounterBuilderPage';

describe('EncounterBuilderPage', () => {
  beforeEach(async () => {
    setupAuthToken();
    const { server } = await import('../../test/mocks/server');
    const { http, HttpResponse } = await import('msw');
    server.use(
      http.get('/api/encounters/campaign/:campaignId', () =>
        HttpResponse.json([
          { id: 'enc-001', campaignId: 'camp-001', campaignName: 'Test Campaign', name: 'Goblin Ambush', description: null, status: 'PREPARING', currentTurnIndex: 0, roundNumber: 1, sessionCode: null, participants: [], createdAt: new Date().toISOString() },
        ])
      ),
    );
  });

  it('renders the page with heading', async () => {
    render(<EncounterBuilderPage />);
    // The page has two phases: loading then campaign list. Wait for either.
    await waitFor(() => {
      const heading = screen.queryByText('Encounters');
      const loading = screen.queryByText('Loading...');
      expect(heading || loading).toBeTruthy();
    }, { timeout: 3000 });
  });

  it('shows create encounter form on New button click', async () => {
    render(<EncounterBuilderPage />);
    await waitFor(() => screen.getByText('Goblin Ambush'), { timeout: 3000 });
    const user = userEvent.setup();
    await user.click(screen.getByText('New'));
    expect(screen.getByPlaceholderText('Encounter name')).toBeInTheDocument();
  });

  it('hides a monster from players from the participants table', async () => {
    const { server } = await import('../../test/mocks/server');
    const { http, HttpResponse } = await import('msw');
    const { makeMockEncounter, makeMockParticipant } = await import('../../test/mocks/handlers');
    const encounter = (goblinVisible: boolean) => makeMockEncounter({
      status: 'PREPARING', sessionCode: undefined,
      participants: [
        makeMockParticipant('part-goblin', 'Test Goblin', 'MONSTER', { isVisibleToPlayers: goblinVisible }),
        makeMockParticipant('part-gimli', 'Gimli', 'PLAYER'),
      ],
    });
    let visibilityRequest: { participantId: string; body: unknown } | undefined;
    server.use(
      http.get('/api/encounters/campaign/:campaignId', () => HttpResponse.json([encounter(true)])),
      http.patch('/api/encounters/:id/participants/:participantId/visibility', async ({ params, request }) => {
        visibilityRequest = { participantId: params.participantId as string, body: await request.json() };
        return HttpResponse.json(encounter(false));
      }),
    );
    render(<EncounterBuilderPage />);
    const user = userEvent.setup();
    await user.click(await screen.findByText('Goblin Ambush'));

    // Player characters cannot be hidden, so only the goblin has the switch
    expect(screen.getAllByRole('button', { name: /from players|to players/ })).toHaveLength(1);
    await user.click(screen.getByRole('button', { name: 'Hide from players' }));

    expect(await screen.findByRole('button', { name: 'Show to players' })).toBeInTheDocument();
    expect(screen.getByText('Hidden')).toBeInTheDocument();
    expect(visibilityRequest).toEqual({ participantId: 'part-goblin', body: { visible: false } });
  });

  it('shows the encounter a change returns without fetching the list again', async () => {
    const { server } = await import('../../test/mocks/server');
    const { http, HttpResponse } = await import('msw');
    const { makeMockEncounter, makeMockParticipant } = await import('../../test/mocks/handlers');
    let listRequests = 0;
    let addRequest: unknown;
    server.use(
      http.get('/api/encounters/campaign/:campaignId', () => {
        listRequests += 1;
        return HttpResponse.json([makeMockEncounter({ status: 'PREPARING', sessionCode: undefined, participants: [] })]);
      }),
      http.post('/api/encounters/:id/participants', async ({ request }) => {
        addRequest = await request.json();
        return HttpResponse.json(makeMockEncounter({
          status: 'PREPARING', sessionCode: undefined,
          participants: [makeMockParticipant('part-cleric', 'Player 1 Char', 'PLAYER', { characterId: 'char-001' })],
        }));
      }),
    );
    render(<EncounterBuilderPage />);
    const user = userEvent.setup();
    await user.click(await screen.findByText('Goblin Ambush'));
    await user.click(await screen.findByRole('button', { name: 'Add' }));

    expect(await screen.findByRole('button', { name: 'Added' })).toBeDisabled();
    expect(screen.getByText('Participants (1)')).toBeInTheDocument();
    expect(screen.getByText('1 participants')).toBeInTheDocument();
    expect(addRequest).toEqual({ participantType: 'PLAYER', characterId: 'char-001', displayName: 'Player 1 Char' });
    expect(listRequests).toBe(1);
  });

  it("offers no Add for a character who died, who can't join until the DM revives them (D166)", async () => {
    const { server } = await import('../../test/mocks/server');
    const { http, HttpResponse } = await import('msw');
    const { makeMockCharacter, makeMockEncounter } = await import('../../test/mocks/handlers');
    server.use(
      http.get('/api/encounters/campaign/:campaignId', () =>
        HttpResponse.json([makeMockEncounter({ status: 'PREPARING', sessionCode: undefined, participants: [] })])),
      http.get('/api/characters/campaign/:campaignId', () => HttpResponse.json([
        { ...makeMockCharacter('char-001', 'Aria', 'Fighter', 3), hpCurrent: 0, dead: true },
        makeMockCharacter('char-002', 'Bram', 'Wizard', 3),
      ])),
    );
    render(<EncounterBuilderPage />);
    const user = userEvent.setup();
    await user.click(await screen.findByText('Goblin Ambush'));

    expect(await screen.findByRole('button', { name: 'Dead' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Add' })).toBeEnabled();
  });

  it('lists each campaign character with its hit points as they stand and its maximum', async () => {
    const { server } = await import('../../test/mocks/server');
    const { http, HttpResponse } = await import('msw');
    const { makeMockCharacter, makeMockEncounter } = await import('../../test/mocks/handlers');
    server.use(
      http.get('/api/encounters/campaign/:campaignId', () =>
        HttpResponse.json([makeMockEncounter({ status: 'PREPARING', sessionCode: undefined, participants: [] })])),
      http.get('/api/characters/campaign/:campaignId', () => HttpResponse.json([
        { ...makeMockCharacter('char-001', 'Aria', 'Paladin', 6), hpCurrent: 35, hpMax: 60 },
      ])),
    );
    render(<EncounterBuilderPage />);
    const user = userEvent.setup();
    await user.click(await screen.findByText('Goblin Ambush'));

    expect(await screen.findByText('Lv6 Paladin — HP 35/60 AC 16')).toBeInTheDocument();
  });

  it("names each of a multiclass character's classes with its level", async () => {
    const { server } = await import('../../test/mocks/server');
    const { http, HttpResponse } = await import('msw');
    const { makeMockCharacter, makeMockEncounter } = await import('../../test/mocks/handlers');
    server.use(
      http.get('/api/encounters/campaign/:campaignId', () =>
        HttpResponse.json([makeMockEncounter({ status: 'PREPARING', sessionCode: undefined, participants: [] })])),
      http.get('/api/characters/campaign/:campaignId', () => HttpResponse.json([{
        ...makeMockCharacter('char-001', 'Aria', 'Paladin', 6),
        multiclassEntries: [
          { classId: 'cls-paladin', className: 'Paladin', level: 5 },
          { classId: 'cls-sorcerer', className: 'Sorcerer', level: 1 },
        ],
      }])),
    );
    render(<EncounterBuilderPage />);
    const user = userEvent.setup();
    await user.click(await screen.findByText('Goblin Ambush'));

    expect(await screen.findByText('Lv6 Paladin 5 / Sorcerer 1 — HP 30/30 AC 16')).toBeInTheDocument();
  });

  it('shows a conflict in the builder and fetches the encounters again', async () => {
    const { server } = await import('../../test/mocks/server');
    const { http, HttpResponse } = await import('msw');
    const { makeMockEncounter, makeMockParticipant } = await import('../../test/mocks/handlers');
    let listRequests = 0;
    server.use(
      http.get('/api/encounters/campaign/:campaignId', () => {
        listRequests += 1;
        // Another DM tab started the encounter after this page loaded
        return HttpResponse.json([makeMockEncounter({
          status: listRequests > 1 ? 'ACTIVE' : 'PREPARING', sessionCode: undefined,
          participants: [makeMockParticipant('part-goblin', 'Test Goblin', 'MONSTER')],
        })]);
      }),
      http.post('/api/encounters/:id/initiatives/roll', () =>
        HttpResponse.json({ error: 'Encounter must be in PREPARING status' }, { status: 409 })),
    );
    render(<EncounterBuilderPage />);
    const user = userEvent.setup();
    await user.click(await screen.findByText('Goblin Ambush'));
    await user.click(screen.getByRole('button', { name: /Roll All Initiative/ }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Encounter must be in PREPARING status');
    expect(await screen.findByRole('button', { name: /Go to Session/ })).toBeInTheDocument();
    expect(listRequests).toBe(2);
  });

  it('reorders a tie on initiative and DEX with the arrows the session page has, and shows the order the server answers with', async () => {
    const { server } = await import('../../test/mocks/server');
    const { http, HttpResponse } = await import('msw');
    const { makeMockEncounter, makeMockParticipant } = await import('../../test/mocks/handlers');
    const goblin = makeMockParticipant('part-goblin', 'Test Goblin', 'MONSTER', { initiative: 12, sortOrder: 0 });
    const gimli = makeMockParticipant('part-gimli', 'Gimli', 'PLAYER', { initiative: 12, sortOrder: 1 });
    const tied = (participants: EncounterParticipant[]) => makeMockEncounter({
      status: 'PREPARING', sessionCode: undefined, participants,
      turnOrder: participants.map(p => ({
        participantId: p.id, name: p.displayName, initiative: 12, lair: false, skipped: false, current: false, tieGroup: 0,
      })),
    });
    let requested: unknown;
    server.use(
      http.get('/api/encounters/campaign/:campaignId', () => HttpResponse.json([tied([goblin, gimli])])),
      http.put('/api/encounters/:id/initiatives/tie-order', async ({ request }) => {
        requested = await request.json();
        return HttpResponse.json(tied([gimli, goblin]));
      }),
    );
    render(<EncounterBuilderPage />);
    const user = userEvent.setup();
    await user.click(await screen.findByText('Goblin Ambush'));

    expect(screen.getByRole('button', { name: 'Move Test Goblin up the tie' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Move Gimli up the tie' }));

    await waitFor(() => expect(requested).toEqual({ participantIds: ['part-gimli', 'part-goblin'] }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Move Gimli up the tie' })).toBeDisabled());
    expect(screen.getByRole('button', { name: 'Move Test Goblin down the tie' })).toBeDisabled();
  });

  it('shows a player character in its class colour among the participants, and a monster in the monster colour', async () => {
    const { server } = await import('../../test/mocks/server');
    const { http, HttpResponse } = await import('msw');
    const { makeMockEncounter, makeMockParticipant } = await import('../../test/mocks/handlers');
    server.use(
      http.get('/api/encounters/campaign/:campaignId', () => HttpResponse.json([makeMockEncounter({
        status: 'PREPARING', sessionCode: undefined,
        participants: [
          makeMockParticipant('part-goblin', 'Test Goblin', 'MONSTER'),
          makeMockParticipant('part-gimli', 'Gimli', 'PLAYER', { characterClass: 'Cleric' }),
        ],
      })])),
    );
    render(<EncounterBuilderPage />);
    const user = userEvent.setup();
    await user.click(await screen.findByText('Goblin Ambush'));

    expect(screen.getByDisplayValue('Gimli').getAttribute('style')).toContain('var(--color-cls-cleric)');
    expect(screen.getByDisplayValue('Test Goblin').getAttribute('style')).toContain('var(--color-monster)');
  });

  it('selects a new encounter as soon as it is created', async () => {
    const { server } = await import('../../test/mocks/server');
    const { http, HttpResponse } = await import('msw');
    const { makeMockEncounter } = await import('../../test/mocks/handlers');
    const encounters = [makeMockEncounter({ status: 'PREPARING', sessionCode: undefined, participants: [] })];
    server.use(
      http.get('/api/encounters/campaign/:campaignId', () => HttpResponse.json(encounters)),
      http.post('/api/encounters', async ({ request }) => {
        const { name } = await request.json() as { name: string };
        const created = makeMockEncounter({ id: 'enc-new', name, status: 'PREPARING', sessionCode: undefined, participants: [] });
        encounters.unshift(created);
        return HttpResponse.json(created, { status: 201 });
      }),
    );
    render(<EncounterBuilderPage />);
    const user = userEvent.setup();
    await user.click(await screen.findByText('New'));
    await user.type(screen.getByPlaceholderText('Encounter name'), 'Dragon Attack');
    await user.click(screen.getByRole('button', { name: 'Create' }));

    expect(await screen.findByRole('heading', { name: 'Dragon Attack' })).toBeInTheDocument();
    expect(screen.getByText('Add characters and monsters above.')).toBeInTheDocument();
    expect(screen.queryByPlaceholderText('Encounter name')).not.toBeInTheDocument();
  });

  it('creates encounter on form submit', async () => {
    render(<EncounterBuilderPage />);
    await waitFor(() => screen.getByText('Goblin Ambush'), { timeout: 3000 });
    const user = userEvent.setup();
    await user.click(screen.getByText('New'));
    await user.type(screen.getByPlaceholderText('Encounter name'), 'Dragon Attack');
    await user.click(screen.getByRole('button', { name: 'Create' }));
    await waitFor(() => {
      expect(screen.queryByPlaceholderText('Encounter name')).not.toBeInTheDocument();
    });
  });

  it("adds a player character's familiar with Add companion, with no slot spent (PHB p.240, D182)", async () => {
    const { server } = await import('../../test/mocks/server');
    const { http, HttpResponse } = await import('msw');
    const { makeMockEncounter, makeMockParticipant, makeMockSummonOptions } = await import('../../test/mocks/handlers');
    const gimli = makeMockParticipant('part-gimli', 'Gimli', 'PLAYER');
    const owl = makeMockParticipant('part-owl', 'Owl', 'COMPANION', {
      summonedByParticipantId: 'part-gimli', summon: { spell: 'Find Familiar', kind: 'FAMILIAR' },
    });
    const preparing = (participants: EncounterParticipant[]) =>
      makeMockEncounter({ status: 'PREPARING', sessionCode: undefined, participants });
    const asked: (string | null)[][] = [];
    const requests: unknown[] = [];
    server.use(
      http.get('/api/encounters/campaign/:campaignId', () => HttpResponse.json([preparing([gimli])])),
      http.get('/api/encounters/:id/combat/summon-options', ({ request }) => {
        const params = new URL(request.url).searchParams;
        asked.push([params.get('participantId'), params.get('spellName'), params.get('slotLevel')]);
        return HttpResponse.json(makeMockSummonOptions({
          spellName: 'Find Familiar', kind: 'FAMILIAR', slotLevel: 1, spellLevel: 1, concentration: false,
          options: [{ id: '1', count: 1, maxCr: null, label: '1 familiar', forms: [] }], initiativeModes: ['INDIVIDUAL'],
          defaultInitiativeMode: 'INDIVIDUAL', houseRuleModes: [], creatureTypes: ['celestial', 'fey', 'fiend'],
          creatureType: null, cannotAttack: true,
          candidates: [
            { monsterId: 'mon-owl', name: 'Owl', challengeRating: '0', challengeRatingValue: 0, hitPoints: 1, armourClass: 11, type: 'beast', size: 'T', source: 'MM' },
            { monsterId: 'mon-cat', name: 'Cat', challengeRating: '0', challengeRatingValue: 0, hitPoints: 2, armourClass: 12, type: 'beast', size: 'T', source: 'MM' },
          ],
        }));
      }),
      http.post('/api/encounters/:id/combat/summons/companion', async ({ request }) => {
        requests.push(await request.json());
        return HttpResponse.json(preparing([gimli, owl]));
      }),
    );
    render(<EncounterBuilderPage />);
    const user = userEvent.setup();
    await user.click(await screen.findByText('Goblin Ambush'));

    await user.click(screen.getByRole('button', { name: 'Add companion' }));
    const dialog = await screen.findByRole('dialog', { name: 'Add companion' });
    expect(screen.getByRole('combobox', { name: 'Owner' })).toHaveValue('part-gimli');
    expect(screen.getByRole('combobox', { name: 'Spell' })).toHaveValue('Find Familiar');
    await user.click(await screen.findByRole('button', { name: 'One more Owl' }));
    await user.click(screen.getByRole('radio', { name: 'fey' }));
    // Find Familiar needs no concentration, so the link starts off
    expect(screen.getByRole('checkbox', { name: /Ends with the owner's concentration/ })).not.toBeChecked();
    await user.click(screen.getAllByRole('button', { name: 'Add companion' }).find(b => dialog.contains(b))!);

    await waitFor(() => expect(requests).toHaveLength(1));
    expect(asked[0]).toEqual(['part-gimli', 'Find Familiar', null]);
    expect(requests[0]).toEqual({
      casterParticipantId: 'part-gimli', spellName: 'Find Familiar', slotLevel: 1, concentrationLinked: false,
      optionId: '1', creatures: [{ monsterId: 'mon-owl', count: 1 }], initiativeMode: 'INDIVIDUAL', creatureType: 'fey',
    });
    expect(await screen.findByText('Summoned by Gimli (Find Familiar)')).toBeInTheDocument();
    expect(screen.queryByRole('dialog', { name: 'Add companion' })).not.toBeInTheDocument();
  });

  // A hydra's heads, chosen as it is added: the stat block's count unless the DM changes it (D206)
  async function searchMonsters(heads: number | null) {
    const { server } = await import('../../test/mocks/server');
    const { http, HttpResponse } = await import('msw');
    const { makeMockEncounter, makeMockMonster } = await import('../../test/mocks/handlers');
    const requests: unknown[] = [];
    server.use(
      http.get('/api/encounters/campaign/:campaignId', () =>
        HttpResponse.json([makeMockEncounter({ status: 'PREPARING', sessionCode: undefined, participants: [] })])),
      http.get('/api/monsters/search', () => HttpResponse.json([
        { ...makeMockMonster('mon-hydra', 'Hydra', 'Monstrosity', '8', 172, 15), heads },
        makeMockMonster('mon-001', 'Goblin', 'Humanoid', '1/4', 7, 15),
      ])),
      http.post('/api/encounters/:id/participants', async ({ request }) => {
        requests.push(await request.json());
        return HttpResponse.json(makeMockEncounter({ status: 'PREPARING', sessionCode: undefined, participants: [] }));
      }),
    );
    render(<EncounterBuilderPage />);
    const user = userEvent.setup();
    await user.click(await screen.findByText('Goblin Ambush'));
    await user.type(screen.getByPlaceholderText('Search monsters (fuzzy)...'), 'hyd');
    await screen.findByText('Hydra');
    return { user, requests };
  }

  it("offers a Heads input, prefilled with the stat block's count, only for a monster with heads, and sends it", async () => {
    const { user, requests } = await searchMonsters(5);
    const heads = screen.getByRole('spinbutton', { name: 'Heads for Hydra' });
    expect(heads).toHaveValue(5);
    expect(screen.queryByRole('spinbutton', { name: 'Heads for Goblin' })).not.toBeInTheDocument();

    await user.clear(heads);
    await user.type(heads, '3');
    const hydraRow = heads.closest('div.flex.items-center.justify-between') as HTMLElement;
    await user.click(within(hydraRow).getByRole('button', { name: 'Add' }));
    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0]).toEqual({
      participantType: 'MONSTER', monsterId: 'mon-hydra', displayName: 'Hydra', quantity: 1, heads: 3,
    });
  });

  it('sends no heads for a monster without them', async () => {
    const { user, requests } = await searchMonsters(null);
    expect(screen.queryByRole('spinbutton', { name: /^Heads for/ })).not.toBeInTheDocument();
    const goblinRow = screen.getByText('Goblin').closest('div.flex.items-center.justify-between') as HTMLElement;
    await user.click(within(goblinRow).getByRole('button', { name: 'Add' }));
    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0]).toEqual({ participantType: 'MONSTER', monsterId: 'mon-001', displayName: 'Goblin', quantity: 1 });
  });
});
