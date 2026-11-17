import { describe, it, expect, beforeEach } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockEncounter, makeMockParticipant } from '../../test/mocks/handlers';
import type { MonsterActionResponse, MonsterActionTemplate, MultiattackOption } from '../../types/combat';
import { EncounterProvider } from '../../context/EncounterContext';
import { queryKeys } from '../../queries/keys';
import InlineMonsterActions from './InlineMonsterActions';
import { actionForms, formLabel, hasCharge, twoHandedDamage } from '../../utils/monsterChoices';

/**
 * The DM's choices for one use of a monster's attack (D241): a charge, off by default; a versatile weapon's grip,
 * one-handed by default, showing both damages; and the form an attack's damage depends on.
 */
const centaur = makeMockParticipant('part-centaur', 'Centaur', 'MONSTER');
const gimli = makeMockParticipant('part-gimli', 'Gimli', 'PLAYER');
const participants = [centaur, gimli];

const template = {
  actions: [
    { name: 'Multiattack', isMultiattack: true, multiattackComponents: ['Pike', 'Longsword'], automatable: true },
    { name: 'Pike', attackBonus: 6, deliveryMethod: 'ATTACK_ROLL', attackType: 'MELEE_WEAPON', automatable: true,
      effects: [
        { effectType: 'DAMAGE', damageDice: '1d10 + 4', damageType: 'piercing' },
        { effectType: 'DAMAGE', damageDice: '3d6', damageType: 'piercing', onlyIf: { attackerCharged: true } },
      ],
      charge: { feet: 30, trait: 'Charge', note: 'A target that fails the save is also pushed up to 10 feet away, by hand.' } },
    { name: 'Longsword', attackBonus: 3, deliveryMethod: 'ATTACK_ROLL', attackType: 'MELEE_WEAPON', automatable: true,
      effects: [{ effectType: 'DAMAGE', damageDice: '1d8 + 1', damageType: 'slashing', twoHandedDamageDice: '1d10 + 1' }] },
    { name: 'Spear', attackBonus: 3, deliveryMethod: 'ATTACK_ROLL', attackType: 'MELEE_WEAPON', automatable: true,
      effects: [{ effectType: 'DAMAGE', damageDice: '1d6 + 1', damageType: 'piercing' }],
      versatile: { twoHandedDamageDice: '1d8 + 1', twoHandedDamageType: 'piercing' } },
    { name: 'Beak', attackBonus: 4, deliveryMethod: 'ATTACK_ROLL', attackType: 'MELEE_WEAPON', automatable: true,
      effects: [
        { effectType: 'DAMAGE', damageDice: '1d4 + 2', damageType: 'piercing' },
        { effectType: 'DAMAGE', damageDice: '1', damageType: 'piercing', replacesDamage: true,
          onlyIf: { attackerForm: 'raven form', text: 'in raven form' } },
      ] },
  ],
} satisfies MonsterActionTemplate;

async function renderActions() {
  const { queryClient } = render(
    <EncounterProvider encounterId="enc-001" view="dm">
      <InlineMonsterActions participant={centaur} participants={participants} isLairTurn={false} inLair={false} />
    </EncounterProvider>,
  );
  await waitFor(() => expect(queryClient.getQueryData(queryKeys.encounters.detail('enc-001', 'dm'))).toBeDefined());
  return userEvent.setup();
}

/** Records each monster action request. */
function monsterActions() {
  const requests: unknown[] = [];
  server.use(http.post('/api/encounters/:id/combat/monster-action', async ({ request }) => {
    requests.push(await request.json());
    return HttpResponse.json({
      resolved: true, description: 'Done.', totalDamage: 0, totalHealing: 0, targetResults: [],
      requiresManualResolution: false, conditionsInflicted: [], legendaryResistanceAvailable: false,
      legendaryResistanceRemaining: 0, encounterState: makeMockEncounter({ participants }),
    } satisfies MonsterActionResponse);
  }));
  return requests;
}

describe('monsterChoices', () => {
  it('reads the two-handed damage either way the data keeps it, the charge and the forms', () => {
    const [, pike, longsword, spear, beak] = template.actions;
    expect(twoHandedDamage(longsword)).toBe('1d10 + 1 slashing');
    expect(twoHandedDamage(spear)).toBe('1d8 + 1 piercing');
    expect(twoHandedDamage(pike)).toBeUndefined();
    expect(hasCharge(pike)).toBe(true);
    expect(hasCharge(longsword)).toBe(false);
    expect(actionForms(beak)).toEqual(['raven form']);
    expect(formLabel(beak, 'raven form')).toBe('Raven form (1 piercing)');
    expect(formLabel({ ...beak, effects: [{ effectType: 'DAMAGE', damageDice: '1d8', damageType: 'piercing', onlyIf: { attackerForm: 'enlarged', attackerCharged: true } }] }, 'enlarged')).toBe('Enlarged (1d8 piercing if charging)');
    expect(actionForms(pike)).toEqual([]);
  });
});

describe('InlineMonsterActions: charges, grips and forms (D241)', () => {
  beforeEach(() => {
    setupAuthToken();
    server.use(
      http.get('/api/encounters/:id/participants/:participantId/actions', () => HttpResponse.json(template)),
      http.get('/api/encounters/:id/combat/multiattack-options', () => HttpResponse.json([{
        name: 'Multiattack', description: 'One with its pike and one with its longsword.', manualReason: null,
        components: [
          { name: 'Pike', automated: true, manualReason: null, maxTargets: 1, damageOptions: [], charge: true,
            chargeFeet: 30, forms: [] },
          { name: 'Longsword', automated: true, manualReason: null, maxTargets: 1, damageOptions: [], charge: false,
            twoHandedDamage: '1d10 + 1 slashing', forms: [] },
        ],
      }] satisfies MultiattackOption[])),
    );
  });

  it('offers a charge, off by default, with its note, and sends it once ticked', async () => {
    const requests = monsterActions();
    const user = await renderActions();

    await user.click(await screen.findByRole('button', { name: /^Pike/ }));
    const charge = screen.getByRole('checkbox', { name: /Charged \(moved 30\+ ft straight toward the target first\)/ });
    expect(charge).not.toBeChecked();
    expect(screen.getByText(/Charge: A target that fails the save is also pushed/)).toBeInTheDocument();
    expect(screen.queryByLabelText('Grip')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Gimli' }));
    await user.click(screen.getByRole('button', { name: 'Execute Pike (1 target)' }));
    await user.click(screen.getByRole('button', { name: /^Pike/ }));
    await user.click(screen.getByRole('checkbox', { name: /Charged/ }));
    await user.click(screen.getByRole('button', { name: 'Execute Pike (1 target)' }));

    await waitFor(() => expect(requests).toEqual([
      { monsterParticipantId: 'part-centaur', actionName: 'Pike', actionSource: 'ACTION', targetParticipantIds: ['part-gimli'] },
      { monsterParticipantId: 'part-centaur', actionName: 'Pike', actionSource: 'ACTION', targetParticipantIds: ['part-gimli'],
        charged: true },
    ]));
  });

  it('shows both damages of a versatile weapon, one-handed by default, and sends two hands when chosen', async () => {
    const requests = monsterActions();
    const user = await renderActions();

    await user.click(await screen.findByRole('button', { name: /^Longsword/ }));
    const grip = screen.getByLabelText<HTMLSelectElement>('Grip');
    expect([...grip.options].map(o => o.text)).toEqual(['One-handed (1d8 + 1 slashing)', 'Two-handed (1d10 + 1 slashing)']);
    expect(grip).toHaveValue('one');
    expect(screen.queryByRole('checkbox', { name: /Charged/ })).not.toBeInTheDocument();
    await user.selectOptions(grip, 'two');
    await user.click(screen.getByRole('button', { name: 'Gimli' }));
    await user.click(screen.getByRole('button', { name: 'Execute Longsword (1 target)' }));

    await waitFor(() => expect(requests).toEqual([{
      monsterParticipantId: 'part-centaur', actionName: 'Longsword', actionSource: 'ACTION',
      targetParticipantIds: ['part-gimli'], twoHanded: true,
    }]));
  });

  it('offers the forms an attack depends on, and sends the one chosen', async () => {
    const requests = monsterActions();
    const user = await renderActions();

    await user.click(await screen.findByRole('button', { name: /^Beak/ }));
    const form = screen.getByLabelText<HTMLSelectElement>('Form');
    expect([...form.options].map(o => o.value)).toEqual(['', 'usual', 'raven form']);
    await user.selectOptions(form, 'raven form');
    await user.click(screen.getByRole('button', { name: 'Gimli' }));
    await user.click(screen.getByRole('button', { name: 'Execute Beak (1 target)' }));

    await waitFor(() => expect(requests).toEqual([{
      monsterParticipantId: 'part-centaur', actionName: 'Beak', actionSource: 'ACTION',
      targetParticipantIds: ['part-gimli'], attackerForm: 'raven form',
    }]));
  });

  it('takes a charge and a grip for each attack of a multiattack, and sends them in order', async () => {
    const requests = monsterActions();
    const user = await renderActions();

    await user.click(await screen.findByRole('button', { name: '⚔ Multiattack' }));
    await user.click(within(await screen.findByRole('group', { name: 'Same target for every attack' }))
      .getByRole('button', { name: 'Gimli' }));
    expect(screen.queryByRole('checkbox', { name: 'Charged for 2. Longsword' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('checkbox', { name: 'Charged for 1. Pike' }));
    await user.click(screen.getByRole('checkbox', { name: 'Two-handed for 2. Longsword' }));
    await user.click(screen.getByRole('button', { name: 'Execute Multiattack (2 attacks)' }));

    await waitFor(() => expect(requests).toEqual([{
      monsterParticipantId: 'part-centaur', actionName: 'Multiattack', actionSource: 'ACTION', targetParticipantIds: [],
      componentTargetIds: [['part-gimli'], ['part-gimli']],
      componentCharged: [true, false], componentTwoHanded: [false, true],
    }]));
  });
});

/**
 * A charge that hangs on no attack, as the giff's Headfirst Charge (MPMM), is a trait the DM uses from the Traits tab
 * (D243); a trait without a delivery method isn't offered.
 */
describe('InlineMonsterActions: traits the DM uses (D243)', () => {
  const giffTemplate = {
    actions: [
      { name: 'Longsword', attackBonus: 6, deliveryMethod: 'ATTACK_ROLL', attackType: 'MELEE_WEAPON', automatable: true,
        effects: [{ effectType: 'DAMAGE', damageDice: '1d8 + 4', damageType: 'slashing' }] },
    ],
    traits: [
      { name: 'Firearms Knowledge', description: 'The giff ignores the loading property of muskets and pistols.' },
      { name: 'Headfirst Charge', description: 'If the giff moves at least 20 feet in a straight line...',
        deliveryMethod: 'SAVING_THROW', saveDC: 14, saveAbility: 'STR', halfOnSave: false, targetCount: 1,
        maxTargetSize: 'Large',
        dmNote: 'Used when the giff moves at least 20 feet in a straight line and ends within 5 feet of the target; it costs no action.',
        effects: [
          { effectType: 'DAMAGE', damageDice: '2d6', damageType: 'bludgeoning' },
          { effectType: 'CONDITION', condition: 'prone' },
        ] },
    ],
  } satisfies MonsterActionTemplate;

  beforeEach(() => {
    setupAuthToken();
    server.use(http.get('/api/encounters/:id/participants/:participantId/actions', () => HttpResponse.json(giffTemplate)));
  });

  it('offers only the usable traits, at no cost, and sends them as a trait', async () => {
    const requests = monsterActions();
    const user = await renderActions();

    await user.click(await screen.findByRole('button', { name: 'Traits (1)' }));
    expect(screen.getByRole('note')).toHaveTextContent('cost no action');
    expect(screen.queryByRole('button', { name: /Firearms Knowledge/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Headfirst Charge' }));
    expect(screen.getByText(/DM: Used when the giff moves at least 20 feet/)).toBeInTheDocument();
    expect(screen.queryByText(/Over the action economy/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Gimli' }));
    await user.click(screen.getByRole('button', { name: 'Execute Headfirst Charge (1 target)' }));

    await waitFor(() => expect(requests).toEqual([{
      monsterParticipantId: 'part-centaur', actionName: 'Headfirst Charge', actionSource: 'TRAIT',
      targetParticipantIds: ['part-gimli'],
    }]));
  });
});
