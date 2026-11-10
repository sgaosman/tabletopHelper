import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockCharacter, makeMockPendingChoice } from '../../test/mocks/handlers';
import type { ClassSpellcasting } from '../../types/character';
import type { PageResponse, Spell } from '../../types/reference';
import MysticArcanumModal from './MysticArcanumModal';

function spell(name: string, level: number, classes: string[]): Spell {
  return {
    id: `spell-${name.toLowerCase().replace(/\s+/g, '-')}`, name, level, school: 'Enchantment',
    castingTime: '1 action', rangeDistance: '60 feet', components: null, duration: 'Instantaneous',
    concentration: false, ritual: false, description: null, higherLevels: null, classes, damageType: null,
    damageDice: null, saveAbility: null, source: 'PHB',
  };
}

const SPELLS: Spell[] = [
  spell('Mass Suggestion', 6, ['Bard', 'Sorcerer', 'Warlock', 'Wizard']),
  spell('Circle of Death', 6, ['Sorcerer', 'Warlock', 'Wizard']),
  spell('Heal', 6, ['Cleric', 'Druid']),
  spell('Power Word Kill', 9, ['Bard', 'Sorcerer', 'Warlock', 'Wizard']),
  spell('Wish', 9, ['Sorcerer', 'Wizard', 'Warlock (The Genie)']),
];

function warlockCasting(subclassName?: string): ClassSpellcasting {
  return {
    className: 'Warlock', classLevel: 17, ability: 'CHA', spellSaveDc: 17, spellAttackBonus: 9,
    highestSpellLevel: 5, cantripsKnown: 4, spellsKnown: 14, preparedSpells: null, pactMagic: true,
    spellList: 'Warlock', subclassName,
  };
}

describe('MysticArcanumModal', () => {
  let searched: URLSearchParams[] = [];

  beforeEach(() => {
    setupAuthToken();
    searched = [];
    // The server searches the class's list and the subclass's additions together
    server.use(http.get('/api/reference/spells', ({ request }) => {
      const params = new URL(request.url).searchParams;
      searched.push(params);
      const lists = [params.get('className'), params.get('subclass')].filter(Boolean) as string[];
      const levels = (params.get('level') ?? '').split(',').map(Number);
      const content = SPELLS.filter(s => levels.includes(s.level) && s.classes?.some(c => lists.includes(c)));
      return HttpResponse.json({ content, totalElements: content.length, totalPages: 1, number: 0, size: 50 } satisfies PageResponse<Spell>);
    }));
  });

  it("offers warlock spells of exactly the arcanum's level and sends the one chosen (PHB p.108)", async () => {
    const warlock = { ...makeMockCharacter('char-011', 'Morrow', 'Warlock', 11), spellcasting: [warlockCasting()] };
    const sixth = makeMockPendingChoice({
      id: 'choice-arcanum', type: 'MYSTIC_ARCANUM', characterLevel: 11, classLevel: 11, className: 'Warlock', count: 1,
      maxSpellLevel: 6, spellList: 'Warlock',
    });
    let applied: unknown;
    server.use(http.post('/api/characters/:id/apply-choices', async ({ request }) => {
      applied = await request.json();
      return HttpResponse.json({ ...warlock, pendingChoices: [] });
    }));
    const onComplete = vi.fn();
    render(<MysticArcanumModal character={warlock} choice={sixth} onComplete={onComplete} onSkip={() => {}}
      onClose={() => {}} />);
    const user = userEvent.setup();

    expect(screen.getByRole('dialog', { name: 'Mystic Arcanum (6th)' })).toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: /Mass Suggestion/ }));
    expect(screen.queryByRole('button', { name: /Heal/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Power Word Kill/ })).not.toBeInTheDocument();
    expect(searched.at(-1)?.get('className')).toBe('Warlock');
    expect(searched.at(-1)?.get('level')).toBe('6');
    expect(searched.at(-1)?.get('subclass')).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Choose Arcanum' }));

    await waitFor(() => expect(onComplete).toHaveBeenCalled());
    expect(applied).toEqual({ choiceId: 'choice-arcanum', spells: ['Mass Suggestion'] });
  });

  it("offers a Genie warlock wish, its patron's expanded spell, as its 9th-level arcanum (TCE p.73)", async () => {
    const genie = { ...makeMockCharacter('char-012', 'Sahar', 'Warlock', 17), spellcasting: [warlockCasting('The Genie')] };
    const ninth = makeMockPendingChoice({
      id: 'choice-ninth', type: 'MYSTIC_ARCANUM', characterLevel: 17, classLevel: 17, className: 'Warlock', count: 1,
      maxSpellLevel: 9, spellList: 'Warlock',
    });
    render(<MysticArcanumModal character={genie} choice={ninth} onComplete={() => {}} onSkip={() => {}}
      onClose={() => {}} />);

    expect(await screen.findByRole('button', { name: /Wish/ })).toBeEnabled();
    expect(screen.getByRole('button', { name: /Power Word Kill/ })).toBeEnabled();
    expect(searched.at(-1)?.get('subclass')).toBe('Warlock (The Genie)');
  });

  it('can be skipped, leaving the choice pending', async () => {
    const warlock = { ...makeMockCharacter('char-013', 'Morrow', 'Warlock', 13), spellcasting: [warlockCasting()] };
    const seventh = makeMockPendingChoice({
      type: 'MYSTIC_ARCANUM', characterLevel: 13, classLevel: 13, className: 'Warlock', count: 1, maxSpellLevel: 7,
    });
    const onSkip = vi.fn();
    render(<MysticArcanumModal character={warlock} choice={seventh} onComplete={() => {}} onSkip={onSkip}
      onClose={() => {}} />);
    const user = userEvent.setup();

    expect(screen.getByRole('button', { name: 'Choose Arcanum' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Skip' }));
    expect(onSkip).toHaveBeenCalled();
  });
});
