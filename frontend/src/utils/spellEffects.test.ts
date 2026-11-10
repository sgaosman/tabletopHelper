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

  it('names a retaliation save, as Holy Aura\'s (D195)', () => {
    const effect = makeMockSpellEffect('Holy Aura', { retaliation: 'CON save or blinded (fiend, undead)' });
    expect(laterNotes(effect, () => undefined)).toEqual(['melee attackers: CON save or blinded (fiend, undead)']);
  });
});

describe('laterNotes (D189)', () => {
  it('names the bead\'s damage so far and the dead that rise', () => {
    const effect = makeMockSpellEffect('Delayed Blast Fireball', {
      detonation: { dice: '14d6', type: 'fire', ability: 'DEX', dc: 15, halfOnSave: true, grows: '1d6' },
      risingIds: ['bandit'],
    });
    expect(laterNotes(effect, id => (id === 'bandit' ? 'Bandit' : undefined))).toEqual([
      'explodes when it ends: 14d6 fire, DC 15 DEX save for half; +1d6 at the end of each of the caster\'s turns',
      'Bandit rises at the start of the caster\'s next turn',
    ]);
  });
});

describe('reachable (D189)', () => {
  it('leaves a plane-shifted creature out of reach (PHB p.266)', () => {
    const cleric = makeMockParticipant('cleric', 'Cleric', 'PLAYER');
    const gone = { ...makeMockParticipant('mage', 'Mage', 'MONSTER'), activeConditions: [makeMockCondition('plane shifted')] };
    expect(reachable(cleric, gone)).toBe(false);
  });
});

describe('laterNotes (D187)', () => {
  it('names the creatures it controls and each creature\'s count of saves', () => {
    const effect = makeMockSpellEffect('Contagion', {
      controlledIds: ['bandit'],
      tallyRule: 'CON 3/3',
      tallies: [
        { creature: 'ogre', successes: 1, failures: 2, done: false },
        { creature: 'troll', successes: 0, failures: 3, done: true },
      ],
    });
    const names: Record<string, string> = { bandit: 'Bandit', ogre: 'Ogre', troll: 'Troll' };
    expect(laterNotes(effect, id => names[id])).toEqual([
      'controls Bandit',
      'Ogre: CON saves 1/3 successes, 2/3 failures',
      'Troll: CON saves done (0 successes, 3 failures)',
    ]);
  });
});
