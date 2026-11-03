import { describe, it, expect } from 'vitest';
import { makeMockPendingChoice } from '../test/mocks/handlers';
import { choiceLabel, choicesPending, choicesToMake, choiceTitle, isSpellbookChoice } from './pendingChoices';

describe('pendingChoices', () => {
  it('names a choice with the class level that owes it (D159)', () => {
    expect(choiceLabel(makeMockPendingChoice())).toBe('Ability Score Improvement (Fighter 4)');
    expect(choiceLabel(makeMockPendingChoice({ type: 'SUBCLASS', classLevel: 3 }))).toBe('Subclass (Fighter 3)');
    expect(choiceLabel(makeMockPendingChoice({ type: 'EXPERTISE', className: 'Rogue', classLevel: 1 })))
      .toBe('Expertise (Rogue 1)');
    expect(choiceLabel(makeMockPendingChoice({ type: 'SPELLS', className: 'Sorcerer', classLevel: 3 })))
      .toBe('Spells (Sorcerer 3)');
  });

  it("calls a wizard's spells its spellbook, and a race's or background's feat by where it comes from", () => {
    const spellbook = makeMockPendingChoice({ type: 'SPELLS', className: 'Wizard', classLevel: 2, count: 2 });
    expect(isSpellbookChoice(spellbook)).toBe(true);
    expect(choiceTitle(spellbook)).toBe('Spellbook');
    expect(choiceLabel(spellbook)).toBe('Spellbook (Wizard 2)');
    const feat = { type: 'FEAT', classId: null, className: null, classLevel: null, characterLevel: 1 } as const;
    expect(choiceLabel(makeMockPendingChoice({ ...feat, source: 'RACE' }))).toBe('Feat (race)');
    expect(choiceLabel(makeMockPendingChoice({ ...feat, source: 'BACKGROUND' }))).toBe('Feat (background)');
  });

  it('counts the choices for the player and the DM', () => {
    expect(choicesToMake(1)).toBe('1 choice to make');
    expect(choicesToMake(3)).toBe('3 choices to make');
    expect(choicesPending(1)).toBe('1 choice pending');
    expect(choicesPending(2)).toBe('2 choices pending');
  });
});
