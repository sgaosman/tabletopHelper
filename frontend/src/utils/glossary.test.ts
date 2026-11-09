import { describe, it, expect } from 'vitest';
import { GLOSSARY } from '../test/mocks/glossary';
import { splitAtMatches } from './formatText';
import { buildTermIndex, findTerm, glossaryPatterns, termKey } from './glossary';

const index = buildTermIndex(GLOSSARY);
const patterns = glossaryPatterns(GLOSSARY);
const matches = (text: string) =>
  splitAtMatches(text, patterns).filter(p => typeof p !== 'string').map(p => (p as { bold: string }).bold);

describe('termKey', () => {
  it('lower-cases, joins spaces and drops a leading article', () => {
    expect(termKey('  An   Action ')).toBe('action');
    expect(termKey('your Spell Save DC')).toBe('spell save dc');
  });

  it('turns any DC number into the Difficulty Class', () => {
    expect(termKey('DC 15')).toBe('dc');
  });
});

describe('findTerm', () => {
  it('finds an entry by name or alias in any case', () => {
    expect(findTerm(index, 'Prone')?.id).toBe('prone');
    expect(findTerm(index, 'disadvantage')?.id).toBe('advantage');
    expect(findTerm(index, 'DC 13')?.id).toBe('difficulty-class');
  });

  it('finds a plural through its singular', () => {
    expect(findTerm(index, 'saving throws')?.id).toBe('saving-throw');
    expect(findTerm(index, 'actions')?.id).toBe('action');
  });

  it('finds nothing for words the Glossary does not define', () => {
    expect(findTerm(index, '2d6')).toBeUndefined();
    expect(findTerm(index, 'At 3rd level')).toBeUndefined();
  });
});

describe('glossaryPatterns', () => {
  it('finds conditions and senses in any case', () => {
    expect(matches('The target is knocked Prone and frightened.')).toEqual(['Prone', 'frightened']);
    expect(matches('It has darkvision out to 60 feet.')).toEqual(['darkvision']);
  });

  it('finds a damage type only before "damage", so "cold" alone stays plain', () => {
    expect(matches('Takes 2d6 fire damage in the cold night.')).toEqual(['fire']);
  });

  it('finds an action by its capitalised name before "action"', () => {
    expect(matches('You can take the Dash action. You dash away.')).toEqual(['Dash']);
  });

  it('finds a skill only by its capitalised name', () => {
    expect(matches('A Wisdom (Perception) check, by perception alone.')).toEqual(['Perception']);
  });

  it('makes no pattern for an empty Glossary', () => {
    expect(glossaryPatterns([])).toEqual([]);
  });
});
