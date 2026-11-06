import { describe, it, expect } from 'vitest';
import { spellListClass } from './spellConstants';

// The class tables these tests covered are the server's since R7 (classTables.test.ts)
describe('spellListClass', () => {
  it("is the wizard's list for an Eldritch Knight or Arcane Trickster", () => {
    expect(spellListClass('Fighter', 'Eldritch Knight')).toBe('Wizard');
    expect(spellListClass('Rogue', 'Arcane Trickster')).toBe('Wizard');
  });

  it("is the class's own list otherwise", () => {
    expect(spellListClass('Fighter', 'Champion')).toBe('Fighter');
    expect(spellListClass('Cleric', null)).toBe('Cleric');
    expect(spellListClass('Wizard')).toBe('Wizard');
    expect(spellListClass('Bard', 'constructor')).toBe('Bard');
  });
});
