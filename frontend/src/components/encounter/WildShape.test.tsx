import { describe, it, expect, beforeEach, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import {
  makeMockCondition, makeMockParticipant, makeMockResourcePool, makeMockWildShapeOptions,
} from '../../test/mocks/handlers';
import type { EncounterParticipant, ResourcePoolEntry, WildShapeForm } from '../../types/encounter';
import { EncounterProvider } from '../../context/EncounterContext';
import { queryKeys } from '../../queries/keys';
import ResourcePoolDisplay from '../common/ResourcePoolDisplay';
import WildShapeStatus from './WildShapeStatus';
import ParticipantRow from './ParticipantRow';
import { featureMarker, poolEffect, WILD_SHAPE_POOL, wildShapePoolId } from '../../utils/classFeatures';

/** Rowan, a Druid 2 with 20 HP and AC 14, as a wolf: 11 HP and AC 13 (PHB p.66, MM). */
const wolfForm: WildShapeForm = {
  monsterId: 'monster-wolf', beastName: 'Wolf', challengeRating: '1/4', hpMax: 11, armourClass: 13, strength: 12,
  dexterity: 15, constitution: 12, saves: {}, skills: { perception: 3, stealth: 4 }, speed: { walk: 40 },
  senses: 'passive Perception 13', damageResistances: [], damageImmunities: [], damageVulnerabilities: [],
  conditionImmunities: [], normalHpCurrent: 20, normalHpMax: 20, normalArmourClass: 14, druidLevel: 2,
};

// The pool as resource-pools.json defines it, with its effect
const wildShapePool = makeMockResourcePool(WILD_SHAPE_POOL, 'Wild Shape', {
  sourceName: 'Druid', maxUses: 2, currentUses: 2, resetOn: 'shortRest', spendActionType: 'ACTION', icon: 'paw-print',
  metadata: { effect: 'wild-shape' } as unknown as ResourcePoolEntry['metadata'],
});

function rowan(overrides: Partial<EncounterParticipant> = {}): EncounterParticipant {
  return makeMockParticipant('part-rowan', 'Rowan', 'PLAYER', {
    isCurrentTurn: true, hpCurrent: 20, hpMax: 20, armourClass: 14, resourcePoolsCurrent: [wildShapePool],
    ...overrides,
  });
}

function shaped(overrides: Partial<EncounterParticipant> = {}): EncounterParticipant {
  return rowan({
    hpCurrent: 6, hpMax: 11, armourClass: 13, wildShape: wolfForm,
    activeConditions: [makeMockCondition('wild shape', { wildShape: wolfForm })], ...overrides,
  });
}

async function renderPools(participant: EncounterParticipant, extra: { onHeal?: () => Promise<unknown> } = {}) {
  const onUse = vi.fn().mockResolvedValue(undefined);
  const { queryClient } = render(
    <EncounterProvider encounterId="enc-001" view="player">
      <ResourcePoolDisplay pools={participant.resourcePoolsCurrent} participant={participant} onUse={onUse}
        onWildShapeHeal={extra.onHeal} />
    </EncounterProvider>,
  );
  await waitFor(() => expect(queryClient.getQueryData(queryKeys.encounters.detail('enc-001', 'player'))).toBeDefined());
  return { onUse, user: userEvent.setup() };
}

describe('Wild Shape (PHB p.66–67)', () => {
  beforeEach(() => {
    setupAuthToken();
  });

  describe('on the participant row', () => {
    it('shows nothing for a creature not in Wild Shape', () => {
      const { container } = render(<WildShapeStatus participant={rowan()} />);
      expect(container).toBeEmptyDOMElement();
    });

    it("shows the beast, its HP and AC and the druid's own, and ends it on request", async () => {
      const onEnd = vi.fn();
      render(<ParticipantRow participant={shaped()} current onEndWildShape={onEnd} />);

      const status = screen.getByRole('status', { name: "Rowan's Wild Shape" });
      expect(status).toHaveTextContent('Wild Shape: Wolf');
      expect(status).toHaveTextContent('Beast HP 6/11, AC 13');
      expect(status).toHaveTextContent('Own HP 20/20, AC 14');
      await userEvent.setup().click(screen.getByRole('button', { name: "End Rowan's Wild Shape" }));
      expect(onEnd).toHaveBeenCalledOnce();
    });

    it('offers no end button without the means to end it, as for another player', () => {
      render(<ParticipantRow participant={shaped()} current={false} />);
      expect(screen.getByRole('status', { name: "Rowan's Wild Shape" })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: "End Rowan's Wild Shape" })).not.toBeInTheDocument();
    });
  });

  describe('in the pool panel', () => {
    it('picks a beast from the options and transforms as an action', async () => {
      const { onUse, user } = await renderPools(rowan());

      expect(await screen.findByRole('option', { name: 'Wolf (CR 1/4, 11 HP, AC 13)' })).toBeInTheDocument();
      expect(screen.getByText(/up to CR 1\/4, no swimming speed, no flying speed, for up to 1 hour/)).toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Use Wild Shape' }));

      expect(onUse).toHaveBeenCalledWith(WILD_SHAPE_POOL, { beastId: 'monster-wolf' });
    });

    it("greys the transform for a player whose action is spent, with the turn's reason", async () => {
      const spent = rowan({
        turnEconomy: { ...makeMockParticipant('x', 'x', 'PLAYER').turnEconomy!, actions: 1, actionsUsed: 1 },
      });
      await renderPools(spent);

      expect(await screen.findByRole('button', { name: 'Use Wild Shape' })).toBeDisabled();
    });

    it('ends a Wild Shape in force with the end option, as a bonus action', async () => {
      const { onUse, user } = await renderPools(shaped());

      expect(screen.getAllByRole('status').some(s => s.textContent?.includes('Wild Shape: Wolf'))).toBe(true);
      expect(screen.queryByRole('button', { name: 'Use Wild Shape' })).not.toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'End Wild Shape' }));

      expect(onUse).toHaveBeenCalledWith(WILD_SHAPE_POOL, { option: 'end' });
    });

    it("offers a Circle of the Moon druid in beast form Combat Wild Shape's healing with a slot", async () => {
      server.use(http.get('/api/encounters/:id/combat/wild-shape-options', () => HttpResponse.json(
        makeMockWildShapeOptions({ circleOfTheMoon: true, maxChallengeRating: '1', cost: 'BONUS_ACTION' }))));
      const onHeal = vi.fn().mockResolvedValue(undefined);
      const { user } = await renderPools(shaped({ spellSlotsCurrent: { '1': { max: 3, used: 3, created: 0 },
        '2': { max: 2, used: 0, created: 0 } } }), { onHeal });

      const heal = await screen.findByRole('button', { name: 'Heal (bonus action)' });
      expect(screen.getByRole('option', { name: 'Level 2 slot: 2d8 HP' })).toBeInTheDocument();
      expect(screen.queryByRole('option', { name: 'Level 1 slot: 1d8 HP' })).not.toBeInTheDocument();
      await user.click(heal);

      expect(onHeal).toHaveBeenCalledWith(2, false);
    });
  });

  describe('the rules helpers', () => {
    it('reads the Wild Shape pool by its effect, or by its ID for a pool copied before the effect was named', () => {
      expect(poolEffect(wildShapePool)).toBe('wild-shape');
      expect(poolEffect({ poolId: WILD_SHAPE_POOL, metadata: undefined })).toBe('wild-shape');
      expect(wildShapePoolId(rowan())).toBe(WILD_SHAPE_POOL);
    });

    it("labels the Wild Shape entry among the conditions with the beast's name", () => {
      expect(featureMarker({ name: 'wild shape', wildShape: wolfForm })?.label).toBe('Wild Shape: Wolf');
    });
  });
});
