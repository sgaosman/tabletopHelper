import { describe, it, expect, beforeEach, vi } from 'vitest';
import { useState } from 'react';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render as renderWithProviders, setupAuthToken } from '../../../test/test-utils';
import { server } from '../../../test/mocks/server';
import {
  makeMockCastSpellResponse, makeMockParticipant, makeMockSpiritOptions, makeMockSummonOptions,
} from '../../../test/mocks/handlers';
import { EncounterProvider } from '../../../context/EncounterContext';
import { queryKeys } from '../../../queries/keys';
import type { SummonOption, SummonOptions } from '../../../types/encounter';
import { initialDraft, toSummonChoice, type SummonDraft } from '../../../utils/summons';
import SpellCastModal from '../SpellCastModal';
import SummonStep from './SummonStep';

/** SummonStep with its choices kept, as its pop-up keeps them; `onContinue` gets the choice as a request carries it. */
function Harness({ options, byDm = false, onContinue }: {
  options: SummonOptions;
  byDm?: boolean;
  onContinue?: (choice: ReturnType<typeof toSummonChoice>) => void;
}) {
  const [draft, setDraft] = useState<SummonDraft>(() => initialDraft(options));
  return (
    <SummonStep options={options} draft={draft} onChange={setDraft} byDm={byDm}
      onContinue={() => onContinue?.(toSummonChoice(options, draft, byDm))} />
  );
}

describe('SummonStep', () => {
  it('lets the caster pick Conjure Animals\' option, then creatures up to its count, with a running total', async () => {
    const onContinue = vi.fn();
    const user = userEvent.setup();
    render(<Harness options={makeMockSummonOptions()} onContinue={onContinue} />);

    expect(screen.getByText('Choose how many first.')).toBeInTheDocument();
    await user.click(screen.getByRole('radio', { name: /2 beasts of challenge rating 1/ }));

    // A CR 1 option lists the brown bear too
    const list = screen.getByRole('list', { name: 'Creatures to summon' });
    expect(within(list).getByText('Brown Bear')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Chosen 0 of 2');
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: 'One more Wolf' }));
    await user.click(screen.getByRole('button', { name: 'One more Brown Bear' }));
    expect(screen.getByRole('status')).toHaveTextContent('Chosen 2 of 2');
    // Full: no more can be added
    expect(screen.getByRole('button', { name: 'One more Elk' })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(onContinue).toHaveBeenCalledWith({
      optionId: '2xCR1', creatures: [{ monsterId: 'mon-wolf', count: 1 }, { monsterId: 'mon-bear', count: 1 }],
      variant: undefined, initiativeMode: 'GROUP', creatureType: undefined,
    });
  });

  it('leaves out creatures above the option\'s challenge rating', async () => {
    const user = userEvent.setup();
    render(<Harness options={makeMockSummonOptions()} />);

    await user.click(screen.getByRole('radio', { name: /8 beasts/ }));
    const list = screen.getByRole('list', { name: 'Creatures to summon' });
    expect(within(list).getByText('Wolf')).toBeInTheDocument();
    expect(within(list).queryByText('Brown Bear')).not.toBeInTheDocument();
  });

  it('labels an initiative mode the rules don\'t give as a house rule, and says the beasts count as fey', async () => {
    const onContinue = vi.fn();
    const user = userEvent.setup();
    render(<Harness options={makeMockSummonOptions()} onContinue={onContinue} />);

    expect(screen.getByRole('radio', { name: /One initiative roll for the group/ })).toBeChecked();
    const follows = screen.getByRole('radio', { name: /Acts right after the caster/ });
    expect(follows.closest('label')).toHaveTextContent('house rule');
    expect(screen.getByRole('radio', { name: /One initiative roll/ }).closest('label')).not.toHaveTextContent('house rule');
    expect(screen.getByText('They count as fey creatures.')).toBeInTheDocument();

    // The house rule goes with the choice
    await user.click(follows);
    await user.click(screen.getByRole('radio', { name: /1 beast of/ }));
    await user.click(screen.getByRole('button', { name: 'One more Brown Bear' }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(onContinue).toHaveBeenCalledWith(expect.objectContaining({ initiativeMode: 'FOLLOWS_CASTER', optionId: '1xCR2' }));
  });

  it('previews a Tasha\'s spirit: AC, the chosen form\'s HP, attacks, to-hit and save DC', async () => {
    const onContinue = vi.fn();
    const user = userEvent.setup();
    render(<Harness options={makeMockSpiritOptions()} onContinue={onContinue} />);

    const preview = screen.getByLabelText('Bestial Spirit at level 4');
    // Air is the first form, with 30 HP at 4th level
    expect(preview).toHaveTextContent('AC15HP30Attacks2To hit+7Save DC15');

    await user.click(screen.getByRole('radio', { name: 'Land' }));
    expect(preview).toHaveTextContent('HP40');
    expect(screen.queryByRole('list', { name: 'Creatures to summon' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(onContinue).toHaveBeenCalledWith(expect.objectContaining({ variant: 'Land', creatures: undefined }));
  });

  it('needs a familiar\'s type before it can go on (PHB p.240)', async () => {
    const user = userEvent.setup();
    const familiar = makeMockSummonOptions({
      spellName: 'Find Familiar', kind: 'FAMILIAR', slotLevel: 1, spellLevel: 1, concentration: false,
      options: [{ id: '1', count: 1, maxCr: null, label: '1 familiar', forms: [] }], initiativeModes: ['INDIVIDUAL'],
      defaultInitiativeMode: 'INDIVIDUAL', houseRuleModes: [], creatureTypes: ['celestial', 'fey', 'fiend'],
      creatureType: null, cannotAttack: true,
      candidates: [{ monsterId: 'mon-owl', name: 'Owl', challengeRating: '0', challengeRatingValue: 0, hitPoints: 1, armourClass: 11, type: 'beast', size: 'T', source: 'MM' }],
    });
    render(<Harness options={familiar} />);

    // The lone form is already counted
    expect(screen.getByRole('status')).toHaveTextContent('Chosen 1 of 1');
    expect(screen.getByText('Choose the creature type')).toBeInTheDocument();
    expect(screen.getByText("A familiar can't attack (PHB p.240).")).toBeInTheDocument();
    await user.click(screen.getByRole('radio', { name: 'fey' }));
    expect(screen.getByRole('button', { name: 'Continue' })).toBeEnabled();
  });

  it('says the DM chooses Summon Lesser Demons\' creatures, and sends none', async () => {
    const onContinue = vi.fn();
    const user = userEvent.setup();
    const demons = makeMockSummonOptions({
      spellName: 'Summon Lesser Demons', kind: 'LESSER_DEMONS', dmChooses: true, controlledBy: 'DM',
      initiativeModes: ['GROUP'], houseRuleModes: [], creatureType: null,
      options: [{ id: '2xCR1', count: 2, maxCr: 1, label: '2 demons of challenge rating 1 or lower', forms: [] }],
    });
    render(<Harness options={demons} onContinue={onContinue} />);

    expect(screen.getByRole('note')).toHaveTextContent('A d6 decides how many demons come, and the DM chooses them');
    expect(screen.queryByRole('list', { name: 'Creatures to summon' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(onContinue).toHaveBeenCalledWith({});
  });

  it('lets the DM pick a DM-chooses spell\'s creatures', () => {
    render(<Harness options={makeMockSummonOptions({ dmChooses: true })} byDm />);
    expect(screen.queryByRole('note')).not.toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /8 beasts/ })).toBeInTheDocument();
  });
});

// ── The 4th-level summonings (D185) ───────────────────────────────────────

// The generated types learn the newer kinds and an option's forms when the API types are regenerated
const kindOf = (kind: string) => kind as SummonOptions['kind'];

describe('SummonStep for the 4th-level summonings', () => {
  it('Giant Insect: each option lists only its own insect, and fewer than its count will do (PHB p.245)', async () => {
    const onContinue = vi.fn();
    const user = userEvent.setup();
    const insectOptions: (SummonOption & { forms: string[] })[] = [
      { id: '10xGiant Centipede', count: 10, maxCr: null, label: 'Up to 10 giant centipedes', forms: ['Giant Centipede'] },
      { id: '3xGiant Spider', count: 3, maxCr: null, label: 'Up to 3 giant spiders', forms: ['Giant Spider'] },
      { id: '5xGiant Wasp', count: 5, maxCr: null, label: 'Up to 5 giant wasps', forms: ['Giant Wasp'] },
      { id: '1xGiant Scorpion', count: 1, maxCr: null, label: 'Up to 1 giant scorpion', forms: ['Giant Scorpion'] },
    ];
    const insects = makeMockSummonOptions({
      spellName: 'Giant Insect', kind: kindOf('GIANT_INSECT'), slotLevel: 4, spellLevel: 4, controlledBy: 'DM',
      options: insectOptions, initiativeModes: ['FOLLOWS_CASTER'], defaultInitiativeMode: 'FOLLOWS_CASTER',
      houseRuleModes: [], creatureType: null,
      candidates: [
        { monsterId: 'mon-centipede', name: 'Giant Centipede', challengeRating: '1/4', challengeRatingValue: 0.25, hitPoints: 4, armourClass: 13, type: 'beast', size: 'S', source: 'MM' },
        { monsterId: 'mon-spider', name: 'Giant Spider', challengeRating: '1', challengeRatingValue: 1, hitPoints: 26, armourClass: 14, type: 'beast', size: 'L', source: 'MM' },
      ],
    });
    render(<Harness options={insects} onContinue={onContinue} />);

    expect(screen.getByText(/the DM resolves their actions and movement/)).toBeInTheDocument();
    expect(screen.queryByText(/they are hostile/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('radio', { name: 'Up to 3 giant spiders' }));
    const list = screen.getByRole('list', { name: 'Creatures to summon' });
    expect(within(list).getByText('Giant Spider')).toBeInTheDocument();
    expect(within(list).queryByText('Giant Centipede')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'One more Giant Spider' }));
    await user.click(screen.getByRole('button', { name: 'One more Giant Spider' }));
    expect(screen.getByRole('status')).toHaveTextContent('Chosen 2 of up to 3');
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(onContinue).toHaveBeenCalledWith(expect.objectContaining({
      optionId: '3xGiant Spider', creatures: [{ monsterId: 'mon-spider', count: 2 }],
    }));
  });

  it('Spirit of Death: a spirit with one form takes it without asking (BMT p.50)', async () => {
    const onContinue = vi.fn();
    const user = userEvent.setup();
    render(<Harness onContinue={onContinue} options={makeMockSpiritOptions({
      spellName: 'Spirit of Death', spellLevel: 4, variants: ['Reaper'],
      spirit: { monsterId: 'mon-reaper', name: 'Reaper Spirit', armourClass: 15, armourClasses: { Reaper: 15 }, hitPoints: { Reaper: 40 }, attacks: 2, attackBonus: 7, saveDc: 15 },
    })} />);

    expect(screen.queryByRole('radio', { name: 'Reaper' })).not.toBeInTheDocument();
    expect(screen.getByLabelText('Reaper Spirit at level 4')).toHaveTextContent('HP40');
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(onContinue).toHaveBeenCalledWith(expect.objectContaining({ variant: 'Reaper' }));
  });

  it('Conjure Woodland Beings: when the DM chooses, the caster still picks how many come (PHB p.226)', async () => {
    const onContinue = vi.fn();
    const user = userEvent.setup();
    render(<Harness onContinue={onContinue} options={makeMockSummonOptions({
      spellName: 'Conjure Woodland Beings', kind: kindOf('CONJURE'), slotLevel: 4, spellLevel: 4, dmChooses: true,
      creatureType: null,
    })} />);

    expect(screen.getByRole('note')).toHaveTextContent('The DM chooses Conjure Woodland Beings\'s creatures; choose how many come.');
    await user.click(screen.getByRole('radio', { name: /8 beasts/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(onContinue).toHaveBeenCalledWith({ optionId: '8xCR1/4' });
  });

  it('Summon Greater Demon: one demon, and a note that it may break free (XGE p.166)', () => {
    render(<Harness options={makeMockSummonOptions({
      spellName: 'Summon Greater Demon', kind: kindOf('GREATER_DEMON'), slotLevel: 4, spellLevel: 4,
      options: [{ id: '1xCR5', count: 1, maxCr: 5, label: '1 demon of challenge rating 5 or lower', forms: [] }],
      initiativeModes: ['INDIVIDUAL'], defaultInitiativeMode: 'INDIVIDUAL', houseRuleModes: [], creatureType: null,
    })} />);

    expect(screen.getByText('1 demon of challenge rating 5 or lower')).toBeInTheDocument();
    expect(screen.getByText(/on a success your control ends \(XGE p\.166\)/)).toBeInTheDocument();
    // Every candidate here is CR 1 or lower, so all fit the CR 5 cap
    expect(within(screen.getByRole('list', { name: 'Creatures to summon' })).getByText('Brown Bear')).toBeInTheDocument();
  });
});

describe('SummonStep for the 5th-level summonings (D187)', () => {
  it('Summon Celestial: the Defender shows its AC 2 higher than the Avenger\'s (TCE p.110)', async () => {
    const user = userEvent.setup();
    render(<Harness options={makeMockSpiritOptions({
      spellName: 'Summon Celestial', spellLevel: 5, slotLevel: 5, variants: ['Avenger', 'Defender'],
      spirit: { monsterId: 'mon-celestial', name: 'Celestial Spirit', armourClass: 16, armourClasses: { Avenger: 16, Defender: 18 }, hitPoints: { Avenger: 40, Defender: 40 }, attacks: 2, attackBonus: 7, saveDc: 15 },
    })} />);

    const stats = screen.getByLabelText('Celestial Spirit at level 5');
    expect(stats).toHaveTextContent('AC16');
    await user.click(screen.getByRole('radio', { name: 'Defender' }));
    expect(stats).toHaveTextContent('AC18');
  });

  it('Animate Objects: objects count by size against ten, so a Huge one and a Large one are too many (PHB p.213)', async () => {
    const onContinue = vi.fn();
    const user = userEvent.setup();
    render(<Harness onContinue={onContinue} options={makeMockSummonOptions({
      spellName: 'Animate Objects', kind: kindOf('ANIMATE_OBJECTS'), slotLevel: 5, spellLevel: 5, needsCommand: true,
      options: [{ id: '10', count: 10, maxCr: null, label: 'Up to 10 objects (Medium counts as 2, Large as 4, Huge as 8)', forms: [] }],
      initiativeModes: ['GROUP'], defaultInitiativeMode: 'GROUP', houseRuleModes: [], creatureType: null,
      candidates: [
        { monsterId: 'mon-tiny', name: 'Animated Object (Tiny)', challengeRating: null, challengeRatingValue: null, hitPoints: 20, armourClass: 18, type: 'construct', size: 'Tiny', source: 'PHB' },
        { monsterId: 'mon-large', name: 'Animated Object (Large)', challengeRating: null, challengeRatingValue: null, hitPoints: 50, armourClass: 10, type: 'construct', size: 'Large', source: 'PHB' },
        { monsterId: 'mon-huge', name: 'Animated Object (Huge)', challengeRating: null, challengeRatingValue: null, hitPoints: 80, armourClass: 10, type: 'construct', size: 'Huge', source: 'PHB' },
      ],
    })} />);

    expect(screen.getByText(/a Large as 4 and a Huge as 8 \(PHB p\.213\)/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'One more Animated Object (Huge)' }));
    expect(screen.getByRole('status')).toHaveTextContent('Chosen 8 of up to 10');
    // A Large one would make twelve
    expect(screen.getByRole('button', { name: 'One more Animated Object (Large)' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'One more Animated Object (Tiny)' }));
    await user.click(screen.getByRole('button', { name: 'One more Animated Object (Tiny)' }));
    expect(screen.getByRole('status')).toHaveTextContent('Chosen 10 of up to 10');
    expect(screen.getByRole('button', { name: 'One more Animated Object (Tiny)' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(onContinue).toHaveBeenCalledWith(expect.objectContaining({
      optionId: '10', creatures: [{ monsterId: 'mon-huge', count: 1 }, { monsterId: 'mon-tiny', count: 2 }],
    }));
  });

  it('Conjure Elemental: a note that it turns hostile when concentration breaks (PHB p.225)', () => {
    render(<Harness options={makeMockSummonOptions({
      spellName: 'Conjure Elemental', kind: kindOf('CONJURE_ELEMENTAL'), slotLevel: 5, spellLevel: 5,
      options: [{ id: '1xCR5', count: 1, maxCr: 5, label: '1 elemental of challenge rating 5 or lower', forms: [] }],
      initiativeModes: ['INDIVIDUAL'], defaultInitiativeMode: 'INDIVIDUAL', houseRuleModes: [], creatureType: null,
    })} />);

    expect(screen.getByText(/it turns hostile, you can't dismiss it/)).toBeInTheDocument();
  });
});

// ── The summon step in the cast flow (D182) ───────────────────────────────

const druid = makeMockParticipant('part-druid', 'Radagast', 'PLAYER', {
  isCurrentTurn: true, controlledByUserId: 'user-001',
  spellSlotsCurrent: { 3: { max: 3, used: 0 } },
});

describe('SpellCastModal with a summoning spell', () => {
  beforeEach(() => {
    setupAuthToken();
  });

  it('chooses Conjure Animals\' creatures after the slot, and the cast carries them', async () => {
    const asked: (string | null)[][] = [];
    const casts: unknown[] = [];
    server.use(
      http.get('/api/encounters/:id/combat/spell-options', () => HttpResponse.json([
        { name: 'Conjure Animals', level: 3, castingTime: 'ACTION', sources: [{ source: 'SLOT', levels: [3] }] },
      ])),
      http.get('/api/encounters/:id/combat/summon-options', ({ request }) => {
        const params = new URL(request.url).searchParams;
        asked.push([params.get('participantId'), params.get('spellName'), params.get('slotLevel')]);
        return HttpResponse.json(makeMockSummonOptions());
      }),
      http.get('/api/reference/spells/targeting', () => HttpResponse.json({
        maxTargets: 0, selfOnly: false, canTargetSelf: false, canTargetAllies: false, canTargetEnemies: false,
        targetType: 'SELF', hasRepeatEffect: false,
      })),
      http.post('/api/encounters/:id/combat/cast-spell', async ({ request }) => {
        casts.push(await request.json());
        return HttpResponse.json(makeMockCastSpellResponse({ spellName: 'Conjure Animals', resultSummary: 'Two wolves appear.' }));
      }),
    );
    const user = userEvent.setup();
    const { queryClient } = renderWithProviders(
      <EncounterProvider encounterId="enc-001">
        <SpellCastModal caster={druid} participants={[druid]} onClose={() => {}} />
      </EncounterProvider>,
    );
    await waitFor(() => expect(queryClient.getQueryData(queryKeys.encounters.detail('enc-001', 'player'))).toBeDefined());

    await user.click(await screen.findByRole('button', { name: /Conjure Animals/ }));
    await user.click(screen.getByRole('button', { name: /^Level 3/ }));
    await user.click(await screen.findByRole('radio', { name: /2 beasts/ }));
    await user.click(screen.getByRole('button', { name: 'One more Wolf' }));
    await user.click(screen.getByRole('button', { name: 'One more Wolf' }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(await screen.findByText('Summons: 2 × Wolf')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cast Spell' }));

    await waitFor(() => expect(casts).toHaveLength(1));
    expect(asked).toEqual([['part-druid', 'Conjure Animals', '3']]);
    expect(casts[0]).toMatchObject({
      spellName: 'Conjure Animals', slotLevel: 3,
      summon: { optionId: '2xCR1', creatures: [{ monsterId: 'mon-wolf', count: 2 }], initiativeMode: 'GROUP' },
    });
    expect(await screen.findByText('Two wolves appear.')).toBeInTheDocument();
  });

  it('asks for no summon options for any other spell', async () => {
    let asked = 0;
    server.use(
      http.get('/api/encounters/:id/combat/spell-options', () => HttpResponse.json([
        { name: 'Fireball', level: 3, castingTime: 'ACTION', sources: [{ source: 'SLOT', levels: [3] }] },
      ])),
      http.get('/api/encounters/:id/combat/summon-options', () => { asked++; return HttpResponse.json({}); }),
      http.get('/api/reference/spells/targeting', () => HttpResponse.json({
        maxTargets: -1, selfOnly: false, canTargetSelf: true, canTargetAllies: true, canTargetEnemies: true,
        targetType: 'AREA', hasRepeatEffect: false,
      })),
    );
    const user = userEvent.setup();
    renderWithProviders(
      <EncounterProvider encounterId="enc-001">
        <SpellCastModal caster={druid} participants={[druid]} onClose={() => {}} />
      </EncounterProvider>,
    );

    await user.click(await screen.findByRole('button', { name: /Fireball/ }));
    await user.click(screen.getByRole('button', { name: /^Level 3/ }));
    expect(await screen.findByRole('button', { name: 'Continue' })).toBeInTheDocument();
    expect(screen.queryByText('Loading creatures...')).not.toBeInTheDocument();
    expect(asked).toBe(0);
  });
});
