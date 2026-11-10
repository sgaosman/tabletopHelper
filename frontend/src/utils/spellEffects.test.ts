import { describe, it, expect } from 'vitest';
import { makeMockCondition, makeMockParticipant, makeMockSpellEffect } from '../test/mocks/handlers';
import { laterNotes, reachable } from './spellEffects';

describe('reachable (D185)', () => {
  const cleric = makeMockParticipant('cleric', 'Cleric', 'PLAYER');
  const ogre = makeMockParticipant('ogre', 'Ogre', 'MONSTER');

  it('leaves a banished creature out of reach, even of itself (PHB p.217)', () => {
    const banished = { ...ogre, activeConditions: [makeMockCondition('banished')] };
    expect(reachable(cleric, ogre)).toBe(true);
    expect(reachable(cleric, banished)).toBe(false);
    expect(reachable(banished, banished)).toBe(false);
  });

  it('lets nothing cross Otiluke\'s Resilient Sphere, but its creature reaches itself (PHB p.264)', () => {
    const enclosed = { ...ogre, activeConditions: [makeMockCondition('enclosed')] };
    expect(reachable(cleric, enclosed)).toBe(false);
    expect(reachable(enclosed, cleric)).toBe(false);
    expect(reachable(enclosed, enclosed)).toBe(true);
  });
});

describe('laterNotes (D185)', () => {
  it('names the damage left, the retaliation and the damage still to come', () => {
    const effect = makeMockSpellEffect('Vitriolic Sphere', {
      damageLeft: 40, retaliation: '2d8 fire',
      delayed: [{ target: 'ogre', dice: '5d4', type: 'acid', armed: false }],
    });
    expect(laterNotes(effect, id => (id === 'ogre' ? 'Ogre' : undefined))).toEqual([
      '40 damage left', 'strikes back: 2d8 fire', '5d4 acid to Ogre at the end of its next turn',
    ]);
    expect(laterNotes(makeMockSpellEffect(), () => undefined)).toEqual([]);
  });
});
