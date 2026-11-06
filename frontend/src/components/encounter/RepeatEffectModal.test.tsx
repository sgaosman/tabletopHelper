import { describe, it, expect, beforeEach, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import type { ComponentProps } from 'react';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockCastSpellResponse, makeMockEncounter, makeMockParticipant } from '../../test/mocks/handlers';
import type { SpellTargetingInfo } from '../../types/reference';
import { EncounterProvider } from '../../context/EncounterContext';
import { queryKeys } from '../../queries/keys';
import RepeatEffectModal from './RepeatEffectModal';

const cleric = makeMockParticipant('part-cleric', 'Brother Tuck', 'PLAYER', { concentrationSpell: 'Hold Person', concentrationSlotLevel: 2 });
const goblin = makeMockParticipant('part-goblin', 'Test Goblin', 'MONSTER', { hpMax: 7, hpCurrent: 7, armourClass: 15 });
const orc = makeMockParticipant('part-orc', 'Orc', 'MONSTER', { hpMax: 15, hpCurrent: 15, armourClass: 13 });
const kobold = makeMockParticipant('part-kobold', 'Fallen Kobold', 'MONSTER', { hpCurrent: 0, lifeState: 'DEAD' });
const legolas = makeMockParticipant('part-legolas', 'Legolas', 'PLAYER', { hpMax: 25, hpCurrent: 25 });
const participants = [cleric, goblin, orc, kobold, legolas];

/** Serves the targeting lookup and records the spell name and slot level it was asked about. */
function targeting(info: Partial<SpellTargetingInfo> = {}) {
  const lookups: { name: string | null; slotLevel: string | null }[] = [];
  server.use(
    http.get('/api/reference/spells/targeting', ({ request }) => {
      const params = new URL(request.url).searchParams;
      lookups.push({ name: params.get('name'), slotLevel: params.get('slotLevel') });
      return HttpResponse.json({
        maxTargets: -1, selfOnly: false, canTargetSelf: false, canTargetAllies: false, canTargetEnemies: true,
        targetType: 'SINGLE_TARGET', hasRepeatEffect: true, ...info,
      } satisfies SpellTargetingInfo);
    }),
  );
  return lookups;
}

const ENCOUNTER_KEY = queryKeys.encounters.detail('enc-001', 'player');

/** Renders the modal in the live encounter it acts in, once that encounter has loaded. */
async function renderModal(props: Partial<ComponentProps<typeof RepeatEffectModal>> = {}) {
  const onClose = vi.fn();
  const { queryClient } = render(
    <EncounterProvider encounterId="enc-001">
      <RepeatEffectModal caster={cleric} participants={participants} spellName="Hold Person" onClose={onClose} {...props} />
    </EncounterProvider>,
  );
  await waitFor(() => expect(queryClient.getQueryData(ENCOUNTER_KEY)).toBeDefined());
  return { onClose, cachedEncounter: () => queryClient.getQueryData(ENCOUNTER_KEY), user: userEvent.setup() };
}

async function targetNames() {
  const buttons = await screen.findAllByRole('button', { name: /AC \d+/ });
  return buttons.map(b => within(b).getByText(/Brother Tuck|Test Goblin|Orc|Fallen Kobold|Legolas/).textContent);
}

describe('RepeatEffectModal', () => {
  beforeEach(() => {
    setupAuthToken();
  });

  it('looks up targeting at the concentration slot level and offers the eligible targets', async () => {
    const lookups = targeting();
    await renderModal();

    expect(screen.getByRole('dialog', { name: 'Hold Person — Repeat Effect' })).toBeInTheDocument();
    expect(screen.getByText('(slot level 2)')).toBeInTheDocument();
    expect(screen.getByText('No spell slot consumed — repeating existing concentration effect')).toBeInTheDocument();
    expect(await targetNames()).toEqual(['Test Goblin', 'Orc']);
    expect(lookups).toEqual([{ name: 'Hold Person', slotLevel: '2' }]);
  });

  it('repeats the effect on the chosen targets', async () => {
    targeting();
    let repeatRequest: { body: unknown; actorId: string | null } | undefined;
    const encounterState = makeMockEncounter({ roundNumber: 3 });
    server.use(
      http.post('/api/encounters/:id/combat/repeat-spell-effect', async ({ request }) => {
        repeatRequest = { body: await request.json(), actorId: new URL(request.url).searchParams.get('actorId') };
        return HttpResponse.json(makeMockCastSpellResponse({
          encounterState, spellName: 'Hold Person', slotLevelUsed: 2,
          resultSummary: 'Test Goblin fails its save.',
          targets: [{ targetId: 'part-goblin', targetName: 'Test Goblin', outcome: 'failed', saveRoll: 9, conditionsApplied: ['paralyzed'] }],
        }));
      }),
    );
    const { user, cachedEncounter } = await renderModal();

    await user.click(await screen.findByRole('button', { name: /Test Goblin/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(screen.getByText('Targeting: Test Goblin')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Disadvantage' }));
    await user.click(screen.getByRole('button', { name: 'Repeat Effect' }));

    expect(await screen.findByText('Test Goblin fails its save.')).toBeInTheDocument();
    expect(screen.getByText('Save: 9')).toBeInTheDocument();
    expect(screen.getByText('paralyzed')).toBeInTheDocument();
    expect(repeatRequest).toEqual({ body: { targetIds: ['part-goblin'], advantage: false }, actorId: 'part-cleric' });
    // The encounter state in the result replaces the cached one
    expect(cachedEncounter()).toEqual(encounterState);
  });

  it('uses the active spell slot level when the caster is not concentrating', async () => {
    const lookups = targeting();
    const caster = makeMockParticipant('part-cleric', 'Brother Tuck', 'PLAYER', { activeSpell: 'Spiritual Weapon', activeSpellSlotLevel: 3 });
    await renderModal({ caster, participants: [caster, goblin], spellName: 'Spiritual Weapon' });

    expect(await targetNames()).toEqual(['Test Goblin']);
    expect(lookups).toEqual([{ name: 'Spiritual Weapon', slotLevel: '3' }]);
    expect(screen.getByText('(slot level 3)')).toBeInTheDocument();
  });

  it('offers everyone still standing when targeting info is unavailable', async () => {
    server.use(http.get('/api/reference/spells/targeting', () => new HttpResponse(null, { status: 404 })));
    await renderModal();

    expect(await targetNames()).toEqual(['Brother Tuck', 'Test Goblin', 'Orc', 'Legolas']);
    expect(screen.getByText('— Select target(s)')).toBeInTheDocument();
  });

  it('labels each target with exact HP, or the Health Status the Player View sends instead', async () => {
    targeting();
    const hiddenHpOrc = makeMockParticipant('part-orc', 'Orc', 'MONSTER', {
      hpMax: null, hpCurrent: null, hpTemp: null, healthStatus: 'INJURED', armourClass: 13,
    });
    await renderModal({ participants: [cleric, goblin, hiddenHpOrc] });

    expect(await screen.findByRole('button', { name: /Test Goblin/ })).toHaveTextContent('7/7 HP');
    expect(screen.getByRole('button', { name: /Orc/ })).toHaveTextContent('AC 13Injured');
    expect(screen.getByRole('button', { name: /Orc/ })).not.toHaveTextContent('HP');
  });

  it('stops adding targets once the spell limit is reached', async () => {
    targeting({ maxTargets: 1 });
    const { user } = await renderModal();

    await user.click(await screen.findByRole('button', { name: /Test Goblin/ }));

    expect(screen.getByText('— Select targets (1/1)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Orc/ })).toBeDisabled();
  });

  it('offers Manual Modifiers for the attack bonus and save DC when they are allowed (D164)', async () => {
    targeting({ canTargetEnemies: true });
    const ghoul = makeMockParticipant('part-ghoul', 'Ghoul Priest', 'MONSTER', {
      concentrationSpell: 'Hold Person', concentrationSlotLevel: 2, spellAttackBonus: 6, spellSaveDc: 14,
    });
    let repeatBody: unknown;
    server.use(
      http.post('/api/encounters/:id/combat/repeat-spell-effect', async ({ request }) => {
        repeatBody = await request.json();
        return HttpResponse.json(makeMockCastSpellResponse({ spellName: 'Hold Person', resultSummary: 'Legolas resists.' }));
      }),
    );
    const { user } = await renderModal({ caster: ghoul, participants: [ghoul, legolas], allowManualModifiers: true });

    await user.click(await screen.findByRole('button', { name: /Legolas/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(screen.getByLabelText('Spell Attack Bonus')).toHaveAttribute('placeholder', '6');
    expect(screen.getByLabelText('Spell Save DC')).toHaveAttribute('placeholder', '14');
    await user.type(screen.getByLabelText('Spell Save DC'), '16');
    await user.click(screen.getByRole('button', { name: 'Repeat Effect' }));

    expect(await screen.findByText('Legolas resists.')).toBeInTheDocument();
    expect(repeatBody).toEqual({ targetIds: ['part-legolas'], advantage: null, overrideSpellSaveDC: 16 });
  });

  it('offers no Manual Modifiers when they are not allowed', async () => {
    targeting({ canTargetEnemies: true });
    const { user } = await renderModal();

    await user.click(await screen.findByRole('button', { name: /Test Goblin/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(screen.getByRole('button', { name: 'Repeat Effect' })).toBeInTheDocument();
    expect(screen.queryByLabelText('Spell Save DC')).not.toBeInTheDocument();
  });

  it('goes straight to confirm when the spell takes no targets, as a cast does', async () => {
    targeting({ canTargetSelf: false, canTargetAllies: false, canTargetEnemies: false });
    await renderModal();

    expect(await screen.findByText('Targeting: None')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Repeat Effect' })).toBeEnabled();
    // Nothing comes before confirm here, so there is no going back
    expect(screen.queryByRole('button', { name: /Back/ })).not.toBeInTheDocument();
  });

  it('shows the result as a cast does, with the slot level and the Manual Modifiers note', async () => {
    targeting();
    server.use(
      http.post('/api/encounters/:id/combat/repeat-spell-effect', () =>
        HttpResponse.json(makeMockCastSpellResponse({ spellName: 'Hold Person', slotLevelUsed: 2, resultSummary: 'Orc fails.' })),
      ),
    );
    const { user } = await renderModal({ allowManualModifiers: true });

    await user.click(await screen.findByRole('button', { name: /Orc/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(screen.getByText(/noted in the combat log/)).toBeInTheDocument();
    expect(screen.queryByText(/Will drop concentration/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Repeat Effect' }));

    expect(await screen.findByText('Orc fails.')).toBeInTheDocument();
    expect(screen.getByText('at level 2')).toBeInTheDocument();
  });

  it('shows the generic message when the server fails unexpectedly', async () => {
    targeting();
    server.use(http.post('/api/encounters/:id/combat/repeat-spell-effect', () => new HttpResponse(null, { status: 500 })));
    const { user } = await renderModal();

    await user.click(await screen.findByRole('button', { name: /Test Goblin/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await user.click(screen.getByRole('button', { name: 'Repeat Effect' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong. Please try again.');
  });

  it('shows the server error when the repeat fails, and lets you try again', async () => {
    targeting();
    server.use(
      http.post('/api/encounters/:id/combat/repeat-spell-effect', () =>
        HttpResponse.json({ error: 'Hold Person has ended' }, { status: 409 }),
      ),
    );
    const { user, cachedEncounter } = await renderModal();
    const loaded = cachedEncounter();

    await user.click(await screen.findByRole('button', { name: /Test Goblin/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await user.click(screen.getByRole('button', { name: 'Repeat Effect' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Hold Person has ended');
    expect(screen.getByRole('button', { name: 'Repeat Effect' })).toBeEnabled();
    expect(cachedEncounter()).toBe(loaded);
  });
});
