import { describe, it, expect, beforeEach, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import type { ComponentProps } from 'react';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockCastSpellResponse, makeMockEncounter, makeMockParticipant } from '../../test/mocks/handlers';
import type { SpellTargetingInfo } from '../../api/referenceApi';
import SpellCastModal from './SpellCastModal';

const wizard = makeMockParticipant('part-wizard', 'Elminster', 'PLAYER', {
  spellsKnown: JSON.stringify([
    { name: 'Fire Bolt', level: 0 },
    { name: 'Magic Missile', level: 1, prepared: true },
    { name: 'Shield', level: 1, prepared: false },
    { name: 'Hold Person', level: 2, alwaysPrepared: true },
  ]),
  spellSlotsCurrent: JSON.stringify({ 1: { max: 4, remaining: 2 }, 2: { max: 3, remaining: 0 }, 3: { max: 2, remaining: 1 } }),
});
const goblin = makeMockParticipant('part-goblin', 'Test Goblin', 'MONSTER', { hpMax: 7, hpCurrent: 7, armourClass: 15 });
const orc = makeMockParticipant('part-orc', 'Orc', 'MONSTER', { hpMax: 15, hpCurrent: 15, armourClass: 13 });
const kobold = makeMockParticipant('part-kobold', 'Fallen Kobold', 'MONSTER', { hpCurrent: 0, isAlive: false });
const legolas = makeMockParticipant('part-legolas', 'Legolas', 'PLAYER', { hpMax: 25, hpCurrent: 25 });
const participants = [wizard, goblin, orc, kobold, legolas];

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

function renderModal(props: Partial<ComponentProps<typeof SpellCastModal>> = {}) {
  const onUpdate = vi.fn();
  const onClose = vi.fn();
  render(
    <SpellCastModal encounterId="enc-001" caster={wizard} participants={participants} onUpdate={onUpdate} onClose={onClose} {...props} />,
  );
  return { onUpdate, onClose, user: userEvent.setup() };
}

describe('SpellCastModal', () => {
  beforeEach(() => {
    setupAuthToken();
  });

  it('lists cantrips and prepared spells, leaving out unprepared ones', () => {
    renderModal();

    expect(screen.getByRole('dialog', { name: 'Cast Spell' })).toBeInTheDocument();
    expect(screen.getByText('Cantrips')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Fire Bolt/ })).toHaveTextContent('Cantrip');
    expect(screen.getByRole('button', { name: /Magic Missile/ })).toHaveTextContent('Lvl 1');
    expect(screen.getByRole('button', { name: /Hold Person/ })).toHaveTextContent('always');
    expect(screen.queryByText('Shield')).not.toBeInTheDocument();
  });

  it('filters the spell list as you type', async () => {
    const { user } = renderModal();

    await user.type(screen.getByPlaceholderText('Search spells...'), 'missile');

    expect(screen.getByRole('button', { name: /Magic Missile/ })).toBeInTheDocument();
    expect(screen.queryByText('Fire Bolt')).not.toBeInTheDocument();
    expect(screen.queryByText('Hold Person')).not.toBeInTheDocument();
  });

  it('casts a levelled spell through the slot, target and confirm steps', async () => {
    const lookups = targeting();
    let castRequest: { body: unknown; actorId: string | null } | undefined;
    const encounterState = makeMockEncounter({ roundNumber: 4 });
    server.use(
      http.post('/api/encounters/:id/combat/cast-spell', async ({ request }) => {
        castRequest = { body: await request.json(), actorId: new URL(request.url).searchParams.get('actorId') };
        return HttpResponse.json(makeMockCastSpellResponse({
          encounterState, spellName: 'Magic Missile', slotLevelUsed: 3,
          resultSummary: 'Five darts strike Test Goblin.',
          targets: [{ targetId: 'part-goblin', targetName: 'Test Goblin', outcome: 'hit', damage: 17, conditionsApplied: [] }],
        }));
      }),
    );
    const { user, onUpdate } = renderModal();

    await user.click(screen.getByRole('button', { name: /Magic Missile/ }));
    expect(screen.getByRole('button', { name: /^Level 1/ })).toHaveTextContent('2/4');
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
    expect(castRequest).toEqual({
      body: { spellName: 'Magic Missile', slotLevel: 3, targetIds: ['part-goblin'], advantage: true },
      actorId: 'part-wizard',
    });
    expect(onUpdate).toHaveBeenCalledWith(encounterState);
  });

  it('casts a monster spell through the monster-spell endpoint, with its overrides', async () => {
    targeting();
    const shaman = makeMockParticipant('part-shaman', 'Goblin Shaman', 'MONSTER', {
      spellsKnown: JSON.stringify([{ name: 'Hold Person', level: 2, prepared: true }]),
      spellSlotsCurrent: JSON.stringify({ 2: { max: 2, remaining: 2 } }),
      spellAttackBonus: 5,
      spellSaveDc: 13,
    });
    let monsterSpellRequest: { body: unknown; actorId: string | null } | undefined;
    const castSpell = vi.fn();
    server.use(
      http.post('/api/encounters/:id/combat/monster-spell', async ({ request }) => {
        monsterSpellRequest = { body: await request.json(), actorId: new URL(request.url).searchParams.get('actorId') };
        return HttpResponse.json(makeMockCastSpellResponse({ spellName: 'Hold Person', slotLevelUsed: 2, resultSummary: 'Legolas is paralyzed.' }));
      }),
      http.post('/api/encounters/:id/combat/cast-spell', () => {
        castSpell();
        return HttpResponse.json(makeMockCastSpellResponse());
      }),
    );
    const { user } = renderModal({ caster: shaman, participants: [shaman, goblin, legolas], isMonster: true });

    await user.click(screen.getByRole('button', { name: /Hold Person/ }));
    await user.click(screen.getByRole('button', { name: /^Level 2/ }));
    await user.click(await screen.findByRole('button', { name: /Legolas/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(screen.getByPlaceholderText('5')).toBeInTheDocument();
    await user.type(screen.getByPlaceholderText('13'), '15');
    await user.click(screen.getByRole('button', { name: 'Cast Spell' }));

    expect(await screen.findByText('Legolas is paralyzed.')).toBeInTheDocument();
    expect(monsterSpellRequest).toEqual({
      body: { monsterParticipantId: 'part-shaman', spellName: 'Hold Person', slotLevel: 2, targetParticipantIds: ['part-legolas'], advantage: null, overrideSaveDC: 15 },
      actorId: 'part-shaman',
    });
    expect(castSpell).not.toHaveBeenCalled();
  });

  it('goes straight to confirm when the spell takes no targets', async () => {
    targeting({ canTargetSelf: false, canTargetAllies: false, canTargetEnemies: false });
    const { user } = renderModal();

    await user.click(screen.getByRole('button', { name: /Fire Bolt/ }));

    expect(await screen.findByText('Targeting: None')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cast Spell' })).toBeEnabled();
  });

  it('stops adding targets once the spell limit is reached', async () => {
    targeting({ maxTargets: 1 });
    const { user } = renderModal();

    await user.click(screen.getByRole('button', { name: /Fire Bolt/ }));
    await user.click(await screen.findByRole('button', { name: /Test Goblin/ }));

    expect(screen.getByText('— Select targets (1/1)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Orc/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: /Test Goblin/ })).toBeEnabled();
  });

  it('offers only the caster for a self-only spell', async () => {
    targeting({ selfOnly: true });
    const { user } = renderModal();

    await user.click(screen.getByRole('button', { name: /Fire Bolt/ }));

    const targets = await screen.findAllByRole('button', { name: /AC \d+/ });
    expect(targets).toHaveLength(1);
    expect(targets[0]).toHaveTextContent('Elminster(self)');
  });

  it('shows the server error when the cast fails, and lets you try again', async () => {
    targeting();
    server.use(
      http.post('/api/encounters/:id/combat/cast-spell', () =>
        HttpResponse.json({ error: 'No spell slots remaining' }, { status: 400 }),
      ),
    );
    const { user, onUpdate } = renderModal();

    await user.click(screen.getByRole('button', { name: /Fire Bolt/ }));
    await user.click(await screen.findByRole('button', { name: /Test Goblin/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await user.click(screen.getByRole('button', { name: 'Cast Spell' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('No spell slots remaining');
    expect(screen.getByRole('button', { name: 'Cast Spell' })).toBeEnabled();
    expect(onUpdate).not.toHaveBeenCalled();
  });

  it('closes from the close button', async () => {
    const { user, onClose } = renderModal();

    await user.click(screen.getByRole('button', { name: 'Close' }));

    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
