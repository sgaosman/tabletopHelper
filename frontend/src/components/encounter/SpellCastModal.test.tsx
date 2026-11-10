import { describe, it, expect, beforeEach, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import type { ComponentProps } from 'react';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockCastSpellResponse, makeMockEncounter, makeMockParticipant, makeMockSpiritOptions } from '../../test/mocks/handlers';
import type { SpellOption } from '../../types/combat';
import type { SpellTargetingInfo } from '../../types/reference';
import { EncounterProvider } from '../../context/EncounterContext';
import { queryKeys } from '../../queries/keys';
import SpellCastModal from './SpellCastModal';

// On the wizard's own turn, with its whole turn economy left (D174)
const wizard = makeMockParticipant('part-wizard', 'Elminster', 'PLAYER', {
  isCurrentTurn: true,
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
  { name: 'Fire Bolt', level: 0, castingTime: 'ACTION', sources: [{ source: 'NONE', levels: [0] }] },
  { name: 'Magic Missile', level: 1, castingTime: 'ACTION', sources: [{ source: 'SLOT', levels: [1, 3] }] },
  { name: 'Hold Person', level: 2, castingTime: 'ACTION', sources: [{ source: 'SLOT', levels: [3] }] },
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

  it("shares Magic Missile's darts at the chosen level, sending a target once for each dart (D200)", async () => {
    spellOptions([{ name: 'Magic Missile', level: 1, castingTime: 'ACTION', sources: [{ source: 'SLOT', levels: [1, 3] }],
      targetRoles: { darts: { 1: 3, 2: 4, 3: 5 } } }]);
    targeting({ maxTargets: 5 });
    const requests = castRequests();
    const { user } = await renderModal();

    await user.click(await screen.findByRole('button', { name: /Magic Missile/ }));
    await user.click(screen.getByRole('button', { name: /^Level 3/ }));
    expect(await screen.findByText(/Assign darts \(0\/5\)/)).toBeInTheDocument();
    for (let i = 0; i < 3; i++) await user.click(screen.getByRole('button', { name: 'One more dart at Test Goblin' }));
    await user.click(screen.getByRole('button', { name: 'One more dart at Orc' }));
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'One more dart at Orc' }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(screen.getByText('Targeting: Test Goblin (3 darts), Orc (2 darts)')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cast Spell' }));
    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0].body).toMatchObject({ spellName: 'Magic Missile', slotLevel: 3,
      targetIds: ['part-goblin', 'part-goblin', 'part-goblin', 'part-orc', 'part-orc'] });
  });

  it("sends Ice Knife's main target first, the creature made main moving ahead of the picks (D200)", async () => {
    spellOptions([{ name: 'Ice Knife', level: 1, castingTime: 'ACTION', sources: [{ source: 'SLOT', levels: [1] }],
      targetRoles: { primaryTargets: 1, pickedWithinFeet: 5 } }]);
    targeting();
    const requests = castRequests();
    const { user } = await renderModal();

    await user.click(await screen.findByRole('button', { name: /Ice Knife/ }));
    await user.click(screen.getByRole('button', { name: /^Level 1/ }));
    await user.click(await screen.findByRole('button', { name: /^Test Goblin/ }));
    await user.click(screen.getByRole('button', { name: /^Orc/ }));
    expect(within(screen.getByRole('button', { name: /^Test Goblin/ })).getByText('Main target')).toBeInTheDocument();
    expect(within(screen.getByRole('button', { name: /^Orc/ })).getByText('Picked (within 5 ft)')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Make Orc the main target' }));
    expect(within(screen.getByRole('button', { name: /^Orc/ })).getByText('Main target')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(screen.getByText('Targeting: Orc (main); picked within 5 ft: Test Goblin')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cast Spell' }));
    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0].body).toMatchObject({ spellName: 'Ice Knife', targetIds: ['part-orc', 'part-goblin'] });
  });

  it('casts a summoning spell from its creature step straight to confirm, with no targets (D182)', async () => {
    spellOptions([{ name: 'Summon Beast', level: 2, castingTime: 'ACTION', sources: [{ source: 'SLOT', levels: [3] }] }]);
    const lookups = targeting();
    server.use(http.get('/api/encounters/:id/combat/summon-options', () =>
      HttpResponse.json(makeMockSpiritOptions({ slotLevel: 3 }))));
    const requests = castRequests(makeMockCastSpellResponse({ spellName: 'Summon Beast', slotLevelUsed: 3,
      spellSource: 'SLOT', resultSummary: 'Elminster casts Summon Beast — summons Bestial Spirit (Land)', targets: [] }));
    const { user } = await renderModal();

    await user.click(await screen.findByRole('button', { name: /Summon Beast/ }));
    await user.click(screen.getByRole('button', { name: /^Level 3/ }));
    await user.click(await screen.findByRole('radio', { name: 'Land' }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    // No target step: the spell targets no creature
    expect(screen.queryByText(/Select target/)).not.toBeInTheDocument();
    expect(lookups).toEqual([]);
    await user.click(await screen.findByRole('button', { name: 'Cast Spell' }));

    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0].body).toMatchObject({ spellName: 'Summon Beast', slotLevel: 3, targetIds: [],
      summon: { variant: 'Land' } });
  });

  it('shows a spell whose sources are all spent, but not as one to pick', async () => {
    spellOptions([{ name: 'Fireball', level: 3, castingTime: 'ACTION', sources: [{ source: 'SLOT', levels: [] }] }]);
    await renderModal();

    expect(await screen.findByRole('button', { name: /Fireball/ })).toBeDisabled();
  });

  it("lists a monster's slot, innate and at-will spells, and casts them through the same endpoint (D165)", async () => {
    const asked = spellOptions([
      { name: 'Detect Magic', level: 1, castingTime: 'ACTION', sources: [{ source: 'NONE', levels: [1] }] },
      { name: 'Hold Person', level: 2, castingTime: 'ACTION', sources: [{ source: 'SLOT', levels: [2] }] },
      { name: 'Misty Step', level: 2, castingTime: 'BONUS_ACTION', sources: [{ source: 'INNATE', levels: [2], usesLeft: 1, usesMax: 1 }] },
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
    const { user } = await renderModal({ caster: shaman, participants: [shaman, goblin, legolas], allowManualModifiers: true, asDm: true });

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
    spellOptions([{ name: 'Hex', level: 1, castingTime: 'BONUS_ACTION', sources: [{ source: 'PACT_SLOT', levels: [3], usesLeft: 1, usesMax: 2 }] }]);
    targeting();
    const warlock = makeMockParticipant('part-warlock', 'Hexblade', 'PLAYER', {
      isCurrentTurn: true, spellSlotsCurrent: { pact_3: { max: 2, used: 1 } },
    });
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

  it("casts a warlock's Mystic Arcanum at the spell's own level without a slot, and greys it out once spent (PHB p.108)", async () => {
    spellOptions([
      { name: 'Hold Monster', level: 5, castingTime: 'ACTION', sources: [{ source: 'PACT_SLOT', levels: [5], usesLeft: 3, usesMax: 3 }] },
      { name: 'Circle of Death', level: 6, castingTime: 'ACTION', sources: [{ source: 'MYSTIC_ARCANUM', levels: [6], usesLeft: 1, usesMax: 1 }] },
      { name: 'Plane Shift', level: 7, castingTime: 'ACTION', sources: [{ source: 'MYSTIC_ARCANUM', levels: [], usesLeft: 0, usesMax: 1 }] },
    ]);
    targeting();
    const warlock = makeMockParticipant('part-warlock', 'Hexblade', 'PLAYER', {
      isCurrentTurn: true, spellSlotsCurrent: { pact_5: { max: 3, used: 0 } },
    });
    const requests = castRequests();
    const { user } = await renderModal({ caster: warlock, participants: [warlock, goblin] });

    expect(await screen.findByRole('button', { name: /Circle of Death/ })).toHaveTextContent('arcanum 1/1');
    expect(screen.getByRole('button', { name: /Plane Shift/ })).toBeDisabled();
    // The arcanum is the only way to pay, so the spell goes straight to its targets
    await user.click(screen.getByRole('button', { name: /Circle of Death/ }));
    await user.click(await screen.findByRole('button', { name: /Test Goblin/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(screen.getByText('Using your Mystic Arcanum (6th) (1/1 left)')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cast Spell' }));

    await screen.findByText('Fire Bolt hits Test Goblin for 7 fire damage.');
    expect(requests[0].body).toMatchObject({ spellName: 'Circle of Death', spellSource: 'MYSTIC_ARCANUM', slotLevel: 6 });
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

describe('SpellCastModal and the turn economy (D174)', () => {
  beforeEach(() => {
    setupAuthToken();
  });

  const FRESH = wizard.turnEconomy!;
  const CLERIC_OPTIONS: SpellOption[] = [
    { name: 'Sacred Flame', level: 0, castingTime: 'ACTION', sources: [{ source: 'NONE', levels: [0] }] },
    { name: 'Guiding Bolt', level: 1, castingTime: 'ACTION', sources: [{ source: 'SLOT', levels: [1] }] },
    { name: 'Healing Word', level: 1, castingTime: 'BONUS_ACTION', sources: [{ source: 'SLOT', levels: [1] }] },
    { name: 'Shield', level: 1, castingTime: 'REACTION', reactionTrigger: 'which you take when you are hit by an attack', sources: [{ source: 'SLOT', levels: [1] }] },
    { name: 'Identify', level: 1, castingTime: 'LONGER', sources: [{ source: 'SLOT', levels: [1] }] },
  ];
  const cleric = (economy: Partial<typeof FRESH> = {}, overrides: Partial<typeof wizard> = {}) =>
    ({ ...wizard, turnEconomy: { ...FRESH, ...economy }, ...overrides });
  const spellButton = (name: string) => screen.getByRole('button', { name: new RegExp(`^${name}`) });

  it('shows each spell\'s casting time', async () => {
    spellOptions(CLERIC_OPTIONS);
    await renderModal({ caster: cleric() });

    expect(await screen.findByRole('button', { name: /^Sacred Flame/ })).toHaveTextContent('Action');
    expect(spellButton('Healing Word')).toHaveTextContent('Casting time: Bonus action');
    expect(spellButton('Shield')).toHaveTextContent('Casting time: Reaction');
    expect(spellButton('Identify')).toHaveTextContent('Casting time: Longer');
  });

  it('greys out a bonus-action spell once the bonus action is used, saying why', async () => {
    spellOptions(CLERIC_OPTIONS);
    await renderModal({ caster: cleric({ bonusActionUsed: true }) });

    await screen.findByRole('button', { name: /^Sacred Flame/ });
    expect(spellButton('Healing Word')).toBeDisabled();
    expect(spellButton('Healing Word')).toHaveAccessibleDescription('Bonus action already used this turn');
    expect(spellButton('Guiding Bolt')).toBeEnabled();
  });

  it('after a bonus-action spell allows only a 1-action cantrip (PHB p.202)', async () => {
    spellOptions(CLERIC_OPTIONS);
    await renderModal({ caster: cleric({ bonusActionUsed: true, bonusActionSpellCast: true }) });

    await screen.findByRole('button', { name: /^Sacred Flame/ });
    expect(spellButton('Sacred Flame')).toBeEnabled();
    expect(spellButton('Guiding Bolt')).toBeDisabled();
    expect(spellButton('Guiding Bolt')).toHaveAccessibleDescription(
      'A bonus-action spell was cast this turn, so only a cantrip with a casting time of 1 action can follow');
    expect(spellButton('Shield')).toBeDisabled();
  });

  it('after a levelled spell rules out a bonus-action spell, the rule\'s other order (PHB p.202)', async () => {
    spellOptions(CLERIC_OPTIONS);
    await renderModal({ caster: cleric({ actions: 2, actionsUsed: 1, otherSpellCast: true }) });

    await screen.findByRole('button', { name: /^Sacred Flame/ });
    expect(spellButton('Healing Word')).toBeDisabled();
    expect(spellButton('Healing Word')).toHaveAccessibleDescription(
      'A spell other than a 1-action cantrip was cast this turn, so no spell can be cast as a bonus action');
    expect(spellButton('Guiding Bolt')).toBeEnabled();
  });

  it('greys out a spell longer than a turn for a player, and off their turn everything but a reaction', async () => {
    spellOptions(CLERIC_OPTIONS);
    await renderModal({ caster: cleric({}, { isCurrentTurn: false }) });

    await screen.findByRole('button', { name: /^Sacred Flame/ });
    expect(spellButton('Identify')).toBeDisabled();
    expect(spellButton('Identify')).toHaveAccessibleDescription(
      "Its casting time is longer than a turn, so it can't be cast during combat");
    expect(spellButton('Sacred Flame')).toBeDisabled();
    expect(spellButton('Shield')).toBeEnabled();
  });

  it('shows a reaction spell\'s trigger at confirm', async () => {
    spellOptions(CLERIC_OPTIONS);
    targeting({ selfOnly: true });
    const { user } = await renderModal({ caster: cleric() });

    await user.click(await screen.findByRole('button', { name: /^Shield/ }));
    await user.click(screen.getByRole('button', { name: /Level 1/ }));
    await user.click(await screen.findByRole('button', { name: /Elminster/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(screen.getByText('Cast as a reaction, which you take when you are hit by an attack')).toBeInTheDocument();
  });

  it("greys out every spell, for the DM too, with the server's reason when armour worn without proficiency stops casting (M13.5)", async () => {
    const reason = "Elminster can't cast spells while wearing Chain Mail without proficiency";
    spellOptions(CLERIC_OPTIONS.map(option => ({ ...option, unavailableReason: reason })));
    await renderModal({ caster: cleric(), asDm: true });

    await screen.findByRole('button', { name: /^Sacred Flame/ });
    for (const name of ['Sacred Flame', 'Healing Word', 'Guiding Bolt', 'Shield']) {
      expect(spellButton(name)).toBeDisabled();
      expect(spellButton(name)).toHaveAccessibleDescription(reason);
    }
  });

  it('lets the DM cast over the action economy, with the reason as a warning', async () => {
    spellOptions(CLERIC_OPTIONS);
    targeting();
    const { user } = await renderModal({ caster: cleric({ bonusActionUsed: true }), asDm: true });

    await screen.findByRole('button', { name: /^Sacred Flame/ });
    expect(spellButton('Healing Word')).toBeEnabled();
    expect(spellButton('Healing Word')).toHaveAccessibleDescription('Over the action economy: Bonus action already used this turn');
    expect(spellButton('Identify')).toBeEnabled();

    await user.click(spellButton('Healing Word'));
    await user.click(screen.getByRole('button', { name: /Level 1/ }));
    await user.click(await screen.findByRole('button', { name: /Test Goblin/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(screen.getByText('Over the action economy: Bonus action already used this turn')).toBeInTheDocument();
  });

  it('still shows the server\'s refusal, which has the last word', async () => {
    spellOptions(CLERIC_OPTIONS);
    targeting();
    server.use(http.post('/api/encounters/:id/combat/cast-spell', () =>
      HttpResponse.json({ error: 'It has no action left this turn' }, { status: 409 })));
    const { user } = await renderModal({ caster: cleric() });

    await user.click(await screen.findByRole('button', { name: /^Sacred Flame/ }));
    await user.click(await screen.findByRole('button', { name: /Test Goblin/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await user.click(screen.getByRole('button', { name: 'Cast Spell' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('It has no action left this turn');
  });
});

describe('SpellCastModal and Metamagic (PHB p.102, M18)', () => {
  beforeEach(() => {
    setupAuthToken();
  });

  const POINTS = {
    poolId: 'class:sorcerer-sorcery-points', displayName: 'Sorcery Points', sourceType: 'CLASS', maxUses: 5,
    currentUses: 4, resetOn: 'longRest', spendActionType: 'FREE',
  };
  const sorcerer = (economy: Partial<NonNullable<typeof wizard.turnEconomy>> = {}) => ({
    ...wizard,
    turnEconomy: { ...wizard.turnEconomy!, ...economy },
    resourcePoolsCurrent: [POINTS],
    metamagicOptions: ['QUICKENED', 'TWINNED', 'CAREFUL'] as ('QUICKENED' | 'TWINNED' | 'CAREFUL')[],
  });
  const HOLD_PERSON: SpellOption = {
    name: 'Hold Person', level: 2, castingTime: 'ACTION', sources: [{ source: 'SLOT', levels: [3] }],
    metamagic: [
      { option: 'QUICKENED', name: 'Quickened Spell', cost: 2, costIsCastLevel: false, maxCastLevel: null, ineligibleReason: null },
      { option: 'TWINNED', name: 'Twinned Spell', cost: 2, costIsCastLevel: true, maxCastLevel: 2, ineligibleReason: null },
      { option: 'CAREFUL', name: 'Careful Spell', cost: 1, costIsCastLevel: false, maxCastLevel: null, ineligibleReason: null },
    ],
  };
  const FIRE_BOLT: SpellOption = {
    name: 'Fire Bolt', level: 0, castingTime: 'ACTION', sources: [{ source: 'NONE', levels: [0] }],
    metamagic: [
      { option: 'QUICKENED', name: 'Quickened Spell', cost: 2, costIsCastLevel: false, maxCastLevel: null, ineligibleReason: null },
      { option: 'TWINNED', name: 'Twinned Spell', cost: 1, costIsCastLevel: true, maxCastLevel: null, ineligibleReason: null },
      { option: 'CAREFUL', name: 'Careful Spell', cost: 1, costIsCastLevel: false, maxCastLevel: null,
        ineligibleReason: 'Careful Spell needs a spell that forces a saving throw' },
    ],
  };

  it('quickens a spell after the action is spent, sending the option and charging a bonus action', async () => {
    spellOptions([HOLD_PERSON]);
    targeting({ maxTargets: 1 });
    const requests = castRequests();
    const { user } = await renderModal({ caster: sorcerer({ actionsUsed: 1 }) });

    // The action is gone, but Quickened Spell could cast it as a bonus action
    expect(await screen.findByRole('button', { name: /^Hold Person/ })).toBeEnabled();
    await user.click(screen.getByRole('button', { name: /^Hold Person/ }));
    await user.click(screen.getByRole('button', { name: /^Level 3/ }));
    // Twinned can't change Hold Person at 3rd level
    expect(screen.getByRole('checkbox', { name: /^Twinned Spell/ })).toBeDisabled();
    await user.click(screen.getByRole('checkbox', { name: /^Quickened Spell/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await user.click(await screen.findByRole('button', { name: /Test Goblin/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(screen.getByText(/Quickened Spell: 2 sorcery points/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cast Spell' }));
    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0].body).toMatchObject({
      spellName: 'Hold Person', spellSource: 'SLOT', slotLevel: 3, targetIds: ['part-goblin'],
      metamagic: [{ option: 'QUICKENED' }],
    });
  });

  it('twins a cantrip, allowing a second target beyond its one', async () => {
    spellOptions([FIRE_BOLT]);
    targeting({ maxTargets: 1 });
    const requests = castRequests();
    const { user } = await renderModal({ caster: sorcerer() });

    await user.click(await screen.findByRole('button', { name: /^Fire Bolt/ }));
    expect(screen.getByRole('checkbox', { name: /^Careful Spell/ })).toBeDisabled();
    await user.click(screen.getByRole('checkbox', { name: /^Twinned Spell/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await user.click(await screen.findByRole('button', { name: /Test Goblin/ }));
    await user.click(screen.getByRole('button', { name: /Orc/ }));
    expect(screen.getByText(/Select targets \(2\/2\)/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await user.click(screen.getByRole('button', { name: 'Cast Spell' }));

    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0].body).toMatchObject({
      spellName: 'Fire Bolt', targetIds: ['part-goblin', 'part-orc'], metamagic: [{ option: 'TWINNED' }],
    });
  });

  it('asks which targets Careful Spell protects on the confirm step', async () => {
    spellOptions([{ ...HOLD_PERSON, sources: [{ source: 'SLOT', levels: [3] }] }]);
    targeting({ maxTargets: 2 });
    const requests = castRequests();
    const { user } = await renderModal({ caster: sorcerer() });

    await user.click(await screen.findByRole('button', { name: /^Hold Person/ }));
    await user.click(screen.getByRole('button', { name: /^Level 3/ }));
    await user.click(screen.getByRole('checkbox', { name: /^Careful Spell/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await user.click(await screen.findByRole('button', { name: /Test Goblin/ }));
    await user.click(screen.getByRole('button', { name: /Legolas/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await user.click(screen.getByRole('checkbox', { name: 'Legolas' }));
    await user.click(screen.getByRole('button', { name: 'Cast Spell' }));

    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0].body).toMatchObject({ metamagic: [{ option: 'CAREFUL', targetIds: ['part-legolas'] }] });
  });
});
