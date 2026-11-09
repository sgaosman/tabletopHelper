import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockCharacter, makeMockPendingChoice } from '../../test/mocks/handlers';
import type { PageResponse, Spell } from '../../types/reference';
import SubclassCantripModal from './SubclassCantripModal';

function cantrip(name: string, classes: string[]): Spell {
  return {
    id: `spell-${name.toLowerCase().replace(/\s+/g, '-')}`, name, level: 0, school: 'Transmutation',
    castingTime: '1 action', rangeDistance: '10 feet', components: null, duration: 'Instantaneous',
    concentration: false, ritual: false, description: null, higherLevels: null, classes, damageType: null,
    damageDice: null, saveAbility: null, source: 'PHB',
  };
}

const CANTRIPS: Spell[] = [
  cantrip('Druidcraft', ['Druid']), cantrip('Prestidigitation', ['Bard', 'Sorcerer', 'Warlock', 'Wizard']),
  cantrip('Shillelagh', ['Druid']), cantrip('Thaumaturgy', ['Cleric']),
];

describe('SubclassCantripModal', () => {
  const archer = { ...makeMockCharacter('char-007', 'Ilyra', 'Fighter', 3), spellsKnown: [] };
  // Arcane Archer Lore: prestidigitation or druidcraft (XGE p.28)
  const lore = makeMockPendingChoice({
    id: 'choice-lore', type: 'SUBCLASS_CANTRIP', characterLevel: 3, classLevel: 3, className: 'Fighter', count: 1,
    options: ['Prestidigitation', 'Druidcraft'], maxSpellLevel: 0,
  });
  let searched: URLSearchParams[] = [];

  beforeEach(() => {
    setupAuthToken();
    searched = [];
    server.use(http.get('/api/reference/spells', ({ request }) => {
      const params = new URL(request.url).searchParams;
      searched.push(params);
      const list = params.get('className');
      const content = CANTRIPS.filter(s => !list || s.classes?.includes(list));
      return HttpResponse.json({ content, totalElements: content.length, totalPages: 1, number: 0, size: 1000 } satisfies PageResponse<Spell>);
    }));
  });

  it('offers only the choice\'s cantrips and sends the one chosen', async () => {
    let applied: unknown;
    server.use(http.post('/api/characters/:id/apply-choices', async ({ request }) => {
      applied = await request.json();
      return HttpResponse.json({ ...archer, pendingChoices: [] });
    }));
    const onComplete = vi.fn();
    render(<SubclassCantripModal character={archer} choice={lore} onComplete={onComplete} onSkip={() => {}}
      onClose={() => {}} />);
    const user = userEvent.setup();

    expect(screen.getByRole('dialog', { name: 'Choose a Cantrip' })).toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: /Druidcraft/ }));
    expect(screen.queryByRole('button', { name: /Shillelagh/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Prestidigitation/ })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Confirm (1/1)' }));

    await waitFor(() => expect(onComplete).toHaveBeenCalled());
    expect(applied).toEqual({ choiceId: 'choice-lore', spells: ['Druidcraft'] });
  });

  it('marks a cantrip the character knows as known', async () => {
    const knowing = { ...archer, spellsKnown: [{ name: 'Druidcraft', level: 0, source: 'race:Firbolg' }] };
    render(<SubclassCantripModal character={knowing} choice={lore} onComplete={() => {}} onSkip={() => {}}
      onClose={() => {}} />);

    expect(await screen.findByRole('button', { name: /Druidcraft/ })).toBeDisabled();
    expect(screen.getByText('Known')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Prestidigitation/ })).toBeEnabled();
  });

  it("searches the choice's spell list for a Nature Domain cleric's druid cantrip (PHB p.61)", async () => {
    const cleric = { ...makeMockCharacter('char-008', 'Thorn', 'Cleric', 1), spellsKnown: [] };
    const acolyte = makeMockPendingChoice({
      id: 'choice-acolyte', type: 'SUBCLASS_CANTRIP', characterLevel: 1, classLevel: 1, className: 'Cleric',
      count: 1, spellList: 'Druid', maxSpellLevel: 0,
    });
    render(<SubclassCantripModal character={cleric} choice={acolyte} onComplete={() => {}} onSkip={() => {}}
      onClose={() => {}} />);

    expect(await screen.findByRole('button', { name: /Shillelagh/ })).toBeEnabled();
    expect(screen.queryByRole('button', { name: /Thaumaturgy/ })).not.toBeInTheDocument();
    expect(searched.at(-1)?.get('className')).toBe('Druid');
    expect(searched.at(-1)?.get('level')).toBe('0');
    expect(screen.getByText(/the Druid spell list/)).toBeInTheDocument();
  });
});
