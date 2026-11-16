import { describe, it, expect, beforeEach, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockCastSpellResponse, makeMockParticipant } from '../../test/mocks/handlers';
import type { SpellOption } from '../../types/combat';
import type { SpellTargetingInfo } from '../../types/reference';
import { EncounterProvider } from '../../context/EncounterContext';
import { queryKeys } from '../../queries/keys';
import SpellCastModal from './SpellCastModal';

const wizard = makeMockParticipant('part-wizard', 'Elminster', 'PLAYER', { isCurrentTurn: true });
// The DM's creature with a trait that lets it choose whether a spell affects it
const elf = makeMockParticipant('part-elf', 'Sylvan Elf', 'MONSTER', {
  hpMax: 10, hpCurrent: 10, armourClass: 13, spellConsentTrait: 'Fey Ancestry', spellConsent: 'ASK',
});
const participants = [wizard, elf];

const CANTRIPS: SpellOption[] = [
  { name: 'Fire Bolt', level: 0, castingTime: 'ACTION', sources: [{ source: 'NONE', levels: [0] }] },
  { name: 'Ray of Frost', level: 0, castingTime: 'ACTION', sources: [{ source: 'NONE', levels: [0] }] },
];

const TARGET_ANYONE: SpellTargetingInfo = {
  maxTargets: -1, selfOnly: false, canTargetSelf: true, canTargetAllies: true, canTargetEnemies: true,
  targetType: 'SINGLE_TARGET', hasRepeatEffect: false,
};

describe('SpellCastModal: consent choices', () => {
  beforeEach(() => {
    setupAuthToken();
  });

  it('forgets the consent chosen for one spell when another spell is chosen', async () => {
    const requests: unknown[] = [];
    server.use(
      http.get('/api/encounters/:id/combat/spell-options', () => HttpResponse.json(CANTRIPS)),
      http.get('/api/reference/spells/targeting', () => HttpResponse.json(TARGET_ANYONE)),
      http.post('/api/encounters/:id/combat/cast-spell', async ({ request }) => {
        requests.push(await request.json());
        return HttpResponse.json(makeMockCastSpellResponse());
      }),
    );
    const { queryClient } = render(
      <EncounterProvider encounterId="enc-001" view="dm">
        <SpellCastModal caster={wizard} participants={participants} onClose={vi.fn()} asDm />
      </EncounterProvider>,
    );
    await waitFor(() => expect(queryClient.getQueryData(queryKeys.encounters.detail('enc-001', 'dm'))).toBeDefined());
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: /Fire Bolt/ }));
    await user.click(await screen.findByRole('button', { name: /^Sylvan Elf/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await user.click(screen.getByRole('radio', { name: 'No' }));
    expect(screen.getByRole('radio', { name: 'No' })).toBeChecked();

    // Back to the spells, and another spell at the same creature
    await user.click(screen.getByRole('button', { name: /Back/ }));
    await user.click(await screen.findByRole('button', { name: /Back/ }));
    await user.click(await screen.findByRole('button', { name: /Ray of Frost/ }));
    await user.click(await screen.findByRole('button', { name: /^Sylvan Elf/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(screen.getByRole('radio', { name: /Its setting/ })).toBeChecked();
    await user.click(screen.getByRole('button', { name: 'Cast Spell' }));
    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0]).toMatchObject({ spellName: 'Ray of Frost', targetIds: ['part-elf'] });
    expect(requests[0]).not.toHaveProperty('spellConsents');
  });
});
