import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render as renderWithProviders, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import {
  makeMockEncounter, makeMockParticipant, makeMockSpellEffect, makeMockSummonOptions,
} from '../../test/mocks/handlers';
import { EncounterProvider } from '../../context/EncounterContext';
import { queryKeys } from '../../queries/keys';
import ActiveSpells from './ActiveSpells';
import SummonGroupCard from './SummonGroupCard';
import SummonPlaceModal from './SummonPlaceModal';

const conjure = makeMockSpellEffect('Conjure Animals', {
  id: 'effect-conjure', slotLevel: 3, concentration: true, directedCost: null, directedResolves: false,
  summon: { optionId: '2xCR1', multiplier: 1, roll: null, status: 'PLACED' },
  summonedParticipantIds: ['wolf-1', 'wolf-2'],
});
const wolf = (id: string, name: string, hp: number) => makeMockParticipant(id, name, 'COMPANION', {
  monsterId: 'mon-wolf', hpMax: 11, hpCurrent: hp, summonedByParticipantId: 'part-druid',
  summon: { effectId: 'effect-conjure', spell: 'Conjure Animals', slotLevel: 3, kind: 'CONJURE_ANIMALS', initiativeMode: 'GROUP' },
});
const wolves = [wolf('wolf-1', 'Wolf 1', 11), wolf('wolf-2', 'Wolf 2', 4)];

describe('SummonGroupCard', () => {
  it('lists the creatures with their HP, and ends the spell', async () => {
    const onEnd = vi.fn();
    const user = userEvent.setup();
    render(<SummonGroupCard effect={conjure} creatures={wolves} onEnd={onEnd} />);

    const card = screen.getByRole('group', { name: 'Conjure Animals creatures' });
    expect(card).toHaveTextContent('Wolf 111/11');
    expect(card).toHaveTextContent('Wolf 24/11');
    // A player has no Replace or Dismiss
    expect(screen.queryByRole('button', { name: /Replace/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Dismiss/ })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'End Conjure Animals' }));
    expect(onEnd).toHaveBeenCalled();
  });

  it('gives the DM Replace creatures and a Dismiss for each', async () => {
    const onReplace = vi.fn();
    const onDismiss = vi.fn();
    const user = userEvent.setup();
    render(<SummonGroupCard effect={conjure} creatures={wolves} onReplace={onReplace} onDismiss={onDismiss} />);

    await user.click(screen.getByRole('button', { name: 'Replace creatures' }));
    expect(onReplace).toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: 'Dismiss Wolf 2' }));
    expect(onDismiss).toHaveBeenCalledWith(wolves[1]);
  });

  it('gives the DM Dismiss all, sending every creature', async () => {
    const onDismissAll = vi.fn();
    const user = userEvent.setup();
    render(<SummonGroupCard effect={conjure} creatures={wolves} onDismissAll={onDismissAll} />);

    await user.click(screen.getByRole('button', { name: 'Dismiss all' }));
    expect(onDismissAll).toHaveBeenCalledWith(wolves);
  });

  it('has no Dismiss all for a single creature, whose own Dismiss does', () => {
    render(<SummonGroupCard effect={conjure} creatures={[wolves[0]]} onDismiss={() => {}} onDismissAll={() => {}} />);

    expect(screen.queryByRole('button', { name: 'Dismiss all' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Dismiss Wolf 1' })).toBeInTheDocument();
  });

  describe('Command (PHB p.212, XGE p.168)', () => {
    const animate = makeMockSpellEffect('Animate Dead', {
      id: 'effect-animate', summon: { optionId: 'skeleton-or-zombie', multiplier: 1, roll: null, status: 'PLACED' },
      summonedParticipantIds: ['skeleton-1', 'skeleton-2', 'zombie-1'],
    });
    const undead = (id: string, name: string, extra: { commandedRound?: number; lifeState?: 'ALIVE' | 'DEAD' } = {}) =>
      makeMockParticipant(id, name, 'COMPANION', {
        hpMax: 13, hpCurrent: 13, lifeState: extra.lifeState ?? 'ALIVE', summonedByParticipantId: 'part-wizard',
        summon: {
          effectId: 'effect-animate', spell: 'Animate Dead', kind: 'ANIMATE_DEAD', initiativeMode: 'GROUP',
          needsCommand: true, commandedRound: extra.commandedRound,
        },
      });

    it('commands the uncommanded creatures that are still up, and marks the commanded', async () => {
      const onCommand = vi.fn();
      const user = userEvent.setup();
      const skeletons = [undead('skeleton-1', 'Skeleton 1'), undead('skeleton-2', 'Skeleton 2'),
        undead('zombie-1', 'Zombie', { commandedRound: 2 }), undead('skeleton-3', 'Skeleton 3', { lifeState: 'DEAD' })];
      render(<SummonGroupCard effect={animate} creatures={skeletons} onCommand={onCommand} />);

      expect(screen.getByText('Commanded')).toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Command 2 creatures' }));
      expect(onCommand).toHaveBeenCalledWith([skeletons[0], skeletons[1]]);
    });

    it('offers no command once every creature is commanded, nor to creatures that need none', () => {
      const { rerender } = render(<SummonGroupCard effect={animate}
        creatures={[undead('skeleton-1', 'Skeleton 1', { commandedRound: 1 })]} onCommand={() => {}} />);
      expect(screen.queryByRole('button', { name: /^Command/ })).not.toBeInTheDocument();

      rerender(<SummonGroupCard effect={conjure} creatures={wolves} onCommand={() => {}} />);
      expect(screen.queryByRole('button', { name: /^Command/ })).not.toBeInTheDocument();
    });

    it('gives a player no Command or Dismiss all here', () => {
      render(<SummonGroupCard effect={animate} creatures={[undead('skeleton-1', 'Skeleton 1'), undead('skeleton-2', 'Skeleton 2')]} />);

      expect(screen.queryByRole('button', { name: /^Command/ })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Dismiss all' })).not.toBeInTheDocument();
    });
  });

  it('shows a summoning waiting for the DM, with Summon Lesser Demons\' d6', () => {
    const demons = makeMockSpellEffect('Summon Lesser Demons', {
      summon: { optionId: '4xCR1/2', multiplier: 1, roll: 3, status: 'AWAITING_DM' }, summonedParticipantIds: [],
    });
    render(<SummonGroupCard effect={demons} creatures={[]} onReplace={() => {}} />);

    expect(screen.getByText(/Waiting for the DM to choose the creatures/)).toHaveTextContent('(rolled d6: 3)');
    expect(screen.getByRole('button', { name: 'Choose creatures' })).toBeInTheDocument();
  });

  it('sits under its Spell Effect in ActiveSpells, which moves the End button to it', async () => {
    const onEnd = vi.fn();
    const user = userEvent.setup();
    const druid = makeMockParticipant('part-druid', 'Radagast', 'PLAYER', { activeEffects: [conjure], concentrationSpell: 'Conjure Animals' });
    render(<ActiveSpells participant={druid} roundNumber={1} participants={[druid, ...wolves]} onEnd={onEnd} />);

    expect(screen.getByRole('group', { name: 'Conjure Animals effect' })).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'End Conjure Animals' })).toHaveLength(1);
    await user.click(screen.getByRole('button', { name: 'End Conjure Animals' }));
    expect(onEnd).toHaveBeenCalledWith(conjure);
  });
});

describe('SummonPlaceModal', () => {
  beforeEach(() => {
    setupAuthToken();
  });

  it('posts the DM\'s creatures for a summoning, on the option its casting chose', async () => {
    const druid = makeMockParticipant('part-druid', 'Radagast', 'PLAYER', {
      activeEffects: [{ ...conjure, summon: { optionId: '2xCR1', multiplier: 1, roll: null, status: 'AWAITING_DM' }, summonedParticipantIds: [] }],
    });
    const placed: unknown[] = [];
    server.use(
      http.get('/api/encounters/:id', () => HttpResponse.json(makeMockEncounter({ participants: [druid] }))),
      http.get('/api/encounters/:id/combat/summon-options', () => HttpResponse.json(makeMockSummonOptions({ dmChooses: true }))),
      http.post('/api/encounters/:id/combat/summons', async ({ request }) => {
        placed.push(await request.json());
        return HttpResponse.json(makeMockEncounter({ participants: [druid, ...wolves] }));
      }),
    );
    const onClose = vi.fn();
    const user = userEvent.setup();
    const { queryClient } = renderWithProviders(
      <EncounterProvider encounterId="enc-001" view="dm">
        <SummonPlaceModal caster={druid} effect={druid.activeEffects[0]} onClose={onClose} />
      </EncounterProvider>,
    );
    await waitFor(() => expect(queryClient.getQueryData(queryKeys.encounters.detail('enc-001', 'dm'))).toBeDefined());

    expect(await screen.findByRole('radio', { name: /2 beasts/ })).toBeChecked();
    expect(screen.getByRole('button', { name: 'Place creatures' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'One more Brown Bear' }));
    await user.click(screen.getByRole('button', { name: 'One more Brown Bear' }));
    await user.click(screen.getByRole('button', { name: 'Place creatures' }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(placed).toEqual([{
      effectId: 'effect-conjure', optionId: '2xCR1', creatures: [{ monsterId: 'mon-bear', count: 2 }], initiativeMode: 'GROUP',
    }]);
  });
});
