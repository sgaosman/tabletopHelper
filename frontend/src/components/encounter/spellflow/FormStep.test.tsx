import { describe, it, expect, beforeEach } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render as renderWithProviders, setupAuthToken } from '../../../test/test-utils';
import { server } from '../../../test/mocks/server';
import { makeMockCastSpellResponse, makeMockFormOptions, makeMockParticipant } from '../../../test/mocks/handlers';
import { EncounterProvider } from '../../../context/EncounterContext';
import { queryKeys } from '../../../queries/keys';
import SpellCastModal from '../SpellCastModal';
import { formLimitText } from '../../../utils/formSpells';

// ── The form step in the cast flow (D192) ─────────────────────────────

const wizard = makeMockParticipant('part-wiz', 'Elminster', 'PLAYER', {
  isCurrentTurn: true, controlledByUserId: 'user-001',
  spellSlotsCurrent: { 4: { max: 3, used: 0 }, 9: { max: 1, used: 0 } },
});
const gimli = makeMockParticipant('part-002', 'Gimli', 'PLAYER', { controlledByUserId: 'user-002' });

const ANY_TARGET = {
  maxTargets: 1, selfOnly: false, canTargetSelf: true, canTargetAllies: true, canTargetEnemies: true,
  targetType: 'SINGLE', hasRepeatEffect: false,
};

function castHandlers(spell: { name: string; level: number }, targeting: object, asked: (string | null)[][],
  casts: unknown[]) {
  server.use(
    http.get('/api/encounters/:id/combat/spell-options', () => HttpResponse.json([
      { name: spell.name, level: spell.level, castingTime: 'ACTION', sources: [{ source: 'SLOT', levels: [spell.level] }] },
    ])),
    http.get('/api/reference/spells/targeting', () => HttpResponse.json(targeting)),
    http.get('/api/encounters/:id/combat/form-options', ({ request }) => {
      const params = new URL(request.url).searchParams;
      asked.push([params.get('participantId'), params.get('spell'), params.get('targetId')]);
      return HttpResponse.json(makeMockFormOptions({ spell: spell.name, targetId: params.get('targetId') ?? 'part-wiz' }));
    }),
    http.post('/api/encounters/:id/combat/cast-spell', async ({ request }) => {
      casts.push(await request.json());
      return HttpResponse.json(makeMockCastSpellResponse({ spellName: spell.name, resultSummary: 'The spell takes hold.' }));
    }),
  );
}

async function renderModal() {
  const { queryClient } = renderWithProviders(
    <EncounterProvider encounterId="enc-001">
      <SpellCastModal caster={wizard} participants={[wizard, gimli]} onClose={() => {}} />
    </EncounterProvider>,
  );
  await waitFor(() => expect(queryClient.getQueryData(queryKeys.encounters.detail('enc-001', 'player'))).toBeDefined());
}

describe('SpellCastModal with a form spell', () => {
  beforeEach(() => {
    setupAuthToken();
  });

  it('chooses Polymorph\'s form for its target from the listed creatures, filtered, and the cast carries it', async () => {
    const asked: (string | null)[][] = [];
    const casts: unknown[] = [];
    castHandlers({ name: 'Polymorph', level: 4 }, ANY_TARGET, asked, casts);
    const user = userEvent.setup();
    await renderModal();

    await user.click(await screen.findByRole('button', { name: /Polymorph/ }));
    await user.click(screen.getByRole('button', { name: /^Level 4/ }));
    await user.click(await screen.findByRole('button', { name: /Gimli/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    // Each creature the server lists, with its CR, HP, AC and size, under the spell's limit
    const list = await screen.findByRole('list', { name: 'Form for Gimli' });
    expect(within(list).getByText('Wolf')).toBeInTheDocument();
    expect(within(list).getByText(/CR 7 · 157 HP · AC 12 · Huge/)).toBeInTheDocument();
    expect(screen.getByText('A beast of CR 7 or lower')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled();

    // The filter narrows the list
    await user.type(screen.getByRole('textbox', { name: 'Filter Form for Gimli' }), 'ape');
    expect(within(list).queryByText('Wolf')).not.toBeInTheDocument();
    await user.click(within(list).getByRole('button', { name: /Giant Ape/ }));

    expect(screen.getByText('becomes Giant Ape')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(await screen.findByText('Forms: Gimli as Giant Ape')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cast Spell' }));

    await waitFor(() => expect(casts).toHaveLength(1));
    expect(asked).toEqual([['part-wiz', 'Polymorph', 'part-002']]);
    expect(casts[0]).toMatchObject({
      spellName: 'Polymorph', slotLevel: 4, targetIds: ['part-002'], forms: { 'part-002': 'monster-giant-ape' },
    });
    expect(await screen.findByText('The spell takes hold.')).toBeInTheDocument();
  });

  it('gives Shapechange\'s form to the caster, asking with no target', async () => {
    const asked: (string | null)[][] = [];
    const casts: unknown[] = [];
    castHandlers({ name: 'Shapechange', level: 9 },
      { ...ANY_TARGET, selfOnly: true, targetType: 'SELF' }, asked, casts);
    const user = userEvent.setup();
    await renderModal();

    await user.click(await screen.findByRole('button', { name: /Shapechange/ }));
    await user.click(screen.getByRole('button', { name: /^Level 9/ }));
    await user.click(await screen.findByRole('button', { name: /Elminster/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    const list = await screen.findByRole('list', { name: 'Form for Elminster' });
    await user.click(within(list).getByRole('button', { name: /Brown Bear/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await user.click(await screen.findByRole('button', { name: 'Cast Spell' }));

    await waitFor(() => expect(casts).toHaveLength(1));
    expect(asked).toEqual([['part-wiz', 'Shapechange', null]]);
    expect(casts[0]).toMatchObject({ spellName: 'Shapechange', forms: { 'part-wiz': 'monster-brown-bear' } });
  });

  it('sends no forms for any other spell', async () => {
    const asked: (string | null)[][] = [];
    const casts: unknown[] = [];
    castHandlers({ name: 'Hold Person', level: 4 }, ANY_TARGET, asked, casts);
    const user = userEvent.setup();
    await renderModal();

    await user.click(await screen.findByRole('button', { name: /Hold Person/ }));
    await user.click(screen.getByRole('button', { name: /^Level 4/ }));
    await user.click(await screen.findByRole('button', { name: /Gimli/ }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await user.click(await screen.findByRole('button', { name: 'Cast Spell' }));

    await waitFor(() => expect(casts).toHaveLength(1));
    expect(asked).toEqual([]);
    expect(casts[0]).not.toHaveProperty('forms');
  });
});

describe('formLimitText', () => {
  it('names the creature kind, the challenge rating and the largest size', () => {
    expect(formLimitText({ beastsOnly: false, maxChallengeRating: '9', largestSize: 'Large' }))
      .toBe('A creature of CR 9 or lower, Large or smaller');
    expect(formLimitText({ beastsOnly: true, maxChallengeRating: '1/2', largestSize: null }))
      .toBe('A beast of CR 1/2 or lower');
  });
});
