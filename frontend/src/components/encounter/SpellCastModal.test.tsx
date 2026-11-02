import { describe, it, expect, beforeEach, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import type { ComponentProps } from 'react';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockCastSpellResponse, makeMockEncounter, makeMockParticipant } from '../../test/mocks/handlers';
import type { SpellOption } from '../../types/combat';
import type { SpellTargetingInfo } from '../../types/reference';
import { EncounterProvider } from '../../context/EncounterContext';
import { queryKeys } from '../../queries/keys';
import SpellCastModal from './SpellCastModal';

const wizard = makeMockParticipant('part-wizard', 'Elminster', 'PLAYER', {
  spellSlotsCurrent: { 1: { max: 4, used: 2 }, 2: { max: 3, used: 3 }, 3: { max: 2, used: 1 } },
  spellAttackBonus: 7,
  spellSaveDc: 15,
});
const goblin = makeMockParticipant('part-goblin', 'Test Goblin', 'MONSTER', { hpMax: 7, hpCurrent: 7, armourClass: 15 });
const orc = makeMockParticipant('part-orc', 'Orc', 'MONSTER', { hpMax: 15, hpCurrent: 15, armourClass: 13 });
const kobold = makeMockParticipant('part-kobold', 'Fallen Kobold', 'MONSTER', { hpCurrent: 0, lifeState: 'DEAD' });
const legolas = makeMockParticipant('part-legolas', 'Legolas', 'PLAYER', { hpMax: 25, hpCurrent: 25 });
const participants = [wizard, goblin, orc, kobold, legolas];

// What the server says the wizard can cast now: no level 2 slots are left, so Hold Person takes a level 3 one
const WIZARD_OPTIONS: SpellOption[] = [
  { name: 'Fire Bolt', level: 0, sources: [{ source: 'NONE', levels: [0] }] },
  { name: 'Magic Missile', level: 1, sources: [{ source: 'SLOT', levels: [1, 3] }] },
  { name: 'Hold Person', level: 2, sources: [{ source: 'SLOT', levels: [3] }] },
];

const TARGET_ANYONE: SpellTargetingInfo = {
  maxTargets: -1, selfOnly: false, canTargetSelf: true, canTargetAllies: true, canTargetEnemies: true,
  targetType: 'SINGLE_TARGET', hasRepeatEffect: false,
};

/** Serves the targeting lookup and records the spell name and slot level it was asked about. */
function targeting(info: Partial<SpellTargetingInfo> = {}) {
  const lookups: { name: string | null; slotLevel: string | null }[] = [];
  server.use(
    http.get('/api/reference/spells/targeting', ({ request }) => {
      const params = new URL(request.url).searchParams;
      lookups.push({ name: params.get('name'), slotLevel: params.get('slotLevel') });
      return HttpResponse.json({ ...TARGET_ANYONE, ...info });
    }),
  );
  return lookups;
}

/** Serves the caster's spell options and records whose were asked for. */
function spellOptions(options: SpellOption[]) {
  const asked: (string | null)[] = [];
  server.use(
    http.get('/api/encounters/:id/combat/spell-options', ({ request }) => {
      asked.push(new URL(request.url).searchParams.get('participantId'));
      return HttpResponse.json(options);
    }),
  );
  return asked;
}

/** Records each cast request and its actor, answering with the response given. */
function castRequests(response = makeMockCastSpellResponse()) {
  const requests: { body: unknown; actorId: string | null }[] = [];
  server.use(
    http.post('/api/encounters/:id/combat/cast-spell', async ({ request }) => {
      requests.push({ body: await request.json(), actorId: new URL(request.url).searchParams.get('actorId') });
      return HttpResponse.json(response);
    }),
  );
  return requests;
}

const ENCOUNTER_KEY = queryKeys.encounters.detail('enc-001', 'player');

/** Renders the modal in the live encounter it casts in, once that encounter has loaded. */
async function renderModal(props: Partial<ComponentProps<typeof SpellCastModal>> = {}) {
  const onClose = vi.fn();
  const { queryClient } = render(
    <EncounterProvider encounterId="enc-001">
      <SpellCastModal caster={wizard} participants={participants} onClose={onClose} {...props} />
    </EncounterProvider>,
  );
  await waitFor(() => expect(queryClient.getQueryData(ENCOUNTER_KEY)).toBeDefined());
  return { onClose, cachedEncounter: () => queryClient.getQueryData(ENCOUNTER_KEY), user: userEvent.setup() };
}

describe('SpellCastModal', () => {
  beforeEach(() => {
    setupAuthToken();
  });

  it('lists the spells the server says the caster can cast now', async () => {
    const asked = spellOptions(WIZARD_OPTIONS);
    await renderModal();

    expect(screen.getByRole('dialog', { name: 'Cast Spell' })).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: /Fire Bolt/ })).toHaveTextContent('Cantrip');
    expect(screen.getByText('Cantrips')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Magic Missile/ })).toHaveTextContent('Lvl 1');
    expect(screen.getByRole('button', { name: /Hold Person/ })).toHaveTextContent('Lvl 2');
    expect(asked).toEqual(['part-wizard']);
  });

  it('says so when the caster has nothing to cast', async () => {
    spellOptions([]);
    await renderModal();

    expect(await screen.findByText('No spells to cast')).toBeInTheDocument();
  });

  it('filters the spell list as you type', async () => {
    spellOptions(WIZARD_OPTIONS);
    const { user } = await renderModal();

    await screen.findByRole('button', { name: /Fire Bolt/ });
    await user.type(screen.getByPlaceholderText('Search spells...'), 'missile');

    expect(screen.getByRole('button', { name: /Magic Missile/ })).toBeInTheDocument();
    expect(screen.queryByText('Fire Bolt')).not.toBeInTheDocument();
    expect(screen.queryByText('Hold Person')).not.toBeInTheDocument();
  });

  it('casts a levelled spell with a slot, through the slot, target and confirm steps', async () => {
    spellOptions(WIZARD_OPTIONS);
    const lookups = targeting();
    const encounterState = makeMockEncounter({ roundNumber: 4 });
    const requests = castRequests(makeMockCastSpellResponse({
      encounterState, spellName: 'Magic Missile', slotLevelUsed: 3, spellSource: 'SLOT',
      resultSummary: 'Five darts strike Test Goblin.',
      targets: [{ targetId: 'part-goblin', targetName: 'Test Goblin', outcome: 'hit', damage: 17, conditionsApplied: [] }],
    }));
    const { user, cachedEncounter } = await renderModal();

    await user.click(await screen.findByRole('button', { name: /Magic Missile/ }));
    expect(screen.getByRole('button', { name: /^Level 1/ })).toHaveTextContent('2/4');
    // The level 2 slots are spent, so they show but can't be picked
    expect(screen.getByRole('button', { name: /^Level 2/ })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: /^Level 3/ }));

    const targetButtons = await screen.findAllByRole('button', { name: /AC \d+/ });
    expect(targetButtons.map(b => within(b).getByText(/Elminster|Test Goblin|Orc|Legolas/).textContent))
      .toEqual(['Elminster', 'Test Goblin', 'Orc', 'Legolas']);
    expect(lookups).toEqual([{ name: 'Magic Missile', slotLevel: '3' }]);
    await user.click(screen.getByRole('button', { name: /Test Goblin/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(screen.getByText('Targeting: Test Goblin')).toBeInTheDocument();
    expect(screen.getByText(/Using level 3 slot/)).toHaveTextContent('(upcast +2)');
    await user.click(screen.getByRole('button', { name: 'Advantage' }));
    await user.click(screen.getByRole('button', { name: 'Cast Spell' }));

    expect(await screen.findByText('Five darts strike Test Goblin.')).toBeInTheDocument();
    expect(screen.getByText('17 dmg')).toBeInTheDocument();
    expect(requests).toEqual([{
      body: { spellName: 'Magic Missile', spellSource: 'SLOT', slotLevel: 3, targetIds: ['part-goblin'], advantage: true },
      actorId: 'part-wizard',
    }]);
    // The encounter state in the result replaces the cached one
    expect(cachedEncounter()).toEqual(encounterState);
  });

  it('shows a spell whose sources are all spent, but not as one to pick', async () => {
    spellOptions([{ name: 'Fireball', level: 3, sources: [{ source: 'SLOT', levels: [] }] }]);
    await renderModal();

    expect(await screen.findByRole('button', { name: /Fireball/ })).toBeDisabled();
  });

  it("lists a monster's slot, innate and at-will spells, and casts them through the same endpoint (D165)", async () => {
    const asked = spellOptions([
      { name: 'Detect Magic', level: 1, sources: [{ source: 'NONE', levels: [1] }] },
      { name: 'Hold Person', level: 2, sources: [{ source: 'SLOT', levels: [2] }] },
      { name: 'Misty Step', level: 2, sources: [{ source: 'INNATE', levels: [2], usesLeft: 1, usesMax: 1 }] },
    ]);
    targeting({ selfOnly: true });
    const shaman = makeMockParticipant('part-shaman', 'Goblin Shaman', 'MONSTER', {
      spellSlotsCurrent: { 2: { max: 2, used: 0 } },
      spellAttackBonus: 5,
      spellSaveDc: 13,
    });
    const requests = castRequests(makeMockCastSpellResponse({
      spellName: 'Misty Step', slotLevelUsed: 2, spellSource: 'INNATE', resultSummary: 'Goblin Shaman vanishes.',
    }));
    const { user } = await renderModal({ caster: shaman, participants: [shaman, goblin, legolas], allowManualModifiers: true });

    expect(await screen.findByRole('button', { name: /Detect Magic/ })).toHaveTextContent('at will');
    expect(screen.getByRole('button', { name: /Hold Person/ })).toHaveTextContent('Lvl 2');
    expect(screen.getByRole('button', { name: /Misty Step/ })).toHaveTextContent('innate 1/1');
    // An innate spell has nothing to choose, so it goes straight to its targets
    await user.click(screen.getByRole('button', { name: /Misty Step/ }));
    await user.click(await screen.findByRole('button', { name: /Goblin Shaman/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(screen.getByText('Using an innate use (1/1 left)')).toBeInTheDocument();
    expect(screen.getByLabelText('Spell Save DC')).toHaveAttribute('placeholder', '13');
    await user.type(screen.getByLabelText('Spell Save DC'), '15');
    await user.click(screen.getByRole('button', { name: 'Cast Spell' }));

    expect(await screen.findByText('Goblin Shaman vanishes.')).toBeInTheDocument();
    expect(asked).toEqual(['part-shaman']);
    expect(requests).toEqual([{
      body: { spellName: 'Misty Step', spellSource: 'INNATE', slotLevel: 2, targetIds: ['part-shaman'], advantage: null, overrideSpellSaveDC: 15 },
      actorId: 'part-shaman',
    }]);
  });

  it("offers a warlock's pact slot, which casts at its own level", async () => {
    spellOptions([{ name: 'Hex', level: 1, sources: [{ source: 'PACT_SLOT', levels: [3], usesLeft: 1, usesMax: 2 }] }]);
    targeting();
    const warlock = makeMockParticipant('part-warlock', 'Hexblade', 'PLAYER', { spellSlotsCurrent: { pact_3: { max: 2, used: 1 } } });
    const requests = castRequests();
    const { user } = await renderModal({ caster: warlock, participants: [warlock, goblin] });

    await user.click(await screen.findByRole('button', { name: /Hex/ }));
    expect(screen.getByRole('button', { name: /Level 3 \(Pact\)/ })).toHaveTextContent('Upcast +2');
    await user.click(screen.getByRole('button', { name: /Level 3 \(Pact\)/ }));
    await user.click(await screen.findByRole('button', { name: /Test Goblin/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(screen.getByText(/Using level 3 pact slot/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cast Spell' }));

    await screen.findByText('Fire Bolt hits Test Goblin for 7 fire damage.');
    expect(requests[0].body).toMatchObject({ spellName: 'Hex', spellSource: 'PACT_SLOT', slotLevel: 3 });
  });

  it('offers no Manual Modifiers unless they are allowed (D164)', async () => {
    spellOptions(WIZARD_OPTIONS);
    targeting();
    const { user } = await renderModal();

    await user.click(await screen.findByRole('button', { name: /Fire Bolt/ }));
    await user.click(await screen.findByRole('button', { name: /Test Goblin/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(screen.getByRole('button', { name: 'Cast Spell' })).toBeInTheDocument();
    expect(screen.queryByLabelText('Spell Attack Bonus')).not.toBeInTheDocument();
  });

  it("sends a player's Manual Modifier when the campaign allows them", async () => {
    spellOptions(WIZARD_OPTIONS);
    targeting();
    const requests = castRequests();
    const { user } = await renderModal({ allowManualModifiers: true });

    await user.click(await screen.findByRole('button', { name: /Fire Bolt/ }));
    await user.click(await screen.findByRole('button', { name: /Test Goblin/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(screen.getByLabelText('Spell Attack Bonus')).toHaveAttribute('placeholder', '7');
    await user.type(screen.getByLabelText('Spell Attack Bonus'), '9');
    await user.click(screen.getByRole('button', { name: 'Cast Spell' }));

    await screen.findByText('Fire Bolt hits Test Goblin for 7 fire damage.');
    expect(requests[0].body).toEqual({
      spellName: 'Fire Bolt', spellSource: 'NONE', slotLevel: 0, targetIds: ['part-goblin'], advantage: null,
      overrideSpellAttackBonus: 9,
    });
  });

  it('goes straight to confirm when the spell takes no targets', async () => {
    spellOptions(WIZARD_OPTIONS);
    targeting({ canTargetSelf: false, canTargetAllies: false, canTargetEnemies: false });
    const { user } = await renderModal();

    await user.click(await screen.findByRole('button', { name: /Fire Bolt/ }));

    expect(await screen.findByText('Targeting: None')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cast Spell' })).toBeEnabled();
  });

  it('stops adding targets once the spell limit is reached', async () => {
    spellOptions(WIZARD_OPTIONS);
    targeting({ maxTargets: 1 });
    const { user } = await renderModal();

    await user.click(await screen.findByRole('button', { name: /Fire Bolt/ }));
    await user.click(await screen.findByRole('button', { name: /Test Goblin/ }));

    expect(screen.getByText('— Select targets (1/1)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Orc/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: /Test Goblin/ })).toBeEnabled();
  });

  it('labels each target with exact HP, or the Health Status the Player View sends instead', async () => {
    spellOptions(WIZARD_OPTIONS);
    targeting();
    const hiddenHpOrc = makeMockParticipant('part-orc', 'Orc', 'MONSTER', {
      hpMax: null, hpCurrent: null, hpTemp: null, healthStatus: 'BLOODIED', armourClass: 13,
    });
    const { user } = await renderModal({ participants: [wizard, goblin, hiddenHpOrc, legolas] });

    await user.click(await screen.findByRole('button', { name: /Fire Bolt/ }));

    expect(await screen.findByRole('button', { name: /Test Goblin/ })).toHaveTextContent('7/7 HP');
    expect(screen.getByRole('button', { name: /Orc/ })).toHaveTextContent('AC 13Bloodied');
    expect(screen.getByRole('button', { name: /Orc/ })).not.toHaveTextContent('HP');
    expect(screen.getByRole('button', { name: /Legolas/ })).toHaveTextContent('25/25 HP');
  });

  it('offers only the caster for a self-only spell', async () => {
    spellOptions(WIZARD_OPTIONS);
    targeting({ selfOnly: true });
    const { user } = await renderModal();

    await user.click(await screen.findByRole('button', { name: /Fire Bolt/ }));

    const targets = await screen.findAllByRole('button', { name: /AC \d+/ });
    expect(targets).toHaveLength(1);
    expect(targets[0]).toHaveTextContent('Elminster(self)');
  });

  it('shows the server error when the cast fails, and lets you try again', async () => {
    spellOptions(WIZARD_OPTIONS);
    targeting();
    server.use(
      http.post('/api/encounters/:id/combat/cast-spell', () =>
        HttpResponse.json({ error: 'Elminster has no level 3 spell slots left' }, { status: 400 }),
      ),
    );
    const { user, cachedEncounter } = await renderModal();
    const loaded = cachedEncounter();

    await user.click(await screen.findByRole('button', { name: /Fire Bolt/ }));
    await user.click(await screen.findByRole('button', { name: /Test Goblin/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await user.click(screen.getByRole('button', { name: 'Cast Spell' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Elminster has no level 3 spell slots left');
    expect(screen.getByRole('button', { name: 'Cast Spell' })).toBeEnabled();
    expect(cachedEncounter()).toBe(loaded);
  });

  it('closes from the close button', async () => {
    const { user, onClose } = await renderModal();

    await user.click(screen.getByRole('button', { name: 'Close' }));

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('opens with focus in the labelled spell search, and closes on Escape', async () => {
    const { user, onClose } = await renderModal();

    expect(screen.getByRole('textbox', { name: 'Search spells' })).toHaveFocus();
    await user.keyboard('{Escape}');

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('marks the chosen target and advantage as pressed', async () => {
    spellOptions(WIZARD_OPTIONS);
    targeting();
    const { user } = await renderModal();

    await user.click(await screen.findByRole('button', { name: /Fire Bolt/ }));
    await user.click(await screen.findByRole('button', { name: /Test Goblin/ }));
    expect(screen.getByRole('button', { name: /Test Goblin/ })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: /Orc/ })).toHaveAttribute('aria-pressed', 'false');
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await user.click(screen.getByRole('button', { name: 'Advantage' }));

    expect(screen.getByRole('button', { name: 'Advantage' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Normal' })).toHaveAttribute('aria-pressed', 'false');
  });
});
