import { describe, it, expect } from 'vitest';
import { isTurnChange, logBorderColour, logTextColour } from './combatLogStyle';

describe('combatLogStyle', () => {
  it('colours harm, help and the M12 monster actions', () => {
    expect([logTextColour('DAMAGE'), logBorderColour('DAMAGE')]).toEqual(['text-debuff', 'border-debuff']);
    expect([logTextColour('HEAL'), logBorderColour('HEAL')]).toEqual(['text-buff', 'border-buff']);
    expect([logTextColour('MONSTER_ACTION'), logBorderColour('MONSTER_ACTION')]).toEqual(['text-monster', 'border-monster']);
    expect([logTextColour('REACTION_TAKEN'), logBorderColour('REACTION_TAKEN')]).toEqual(['text-monster', 'border-monster']);
    expect([logTextColour('LAIR_ACTION'),logBorderColour('LAIR_ACTION')]).toEqual(['text-cls-warlock', 'border-cls-warlock']);
    expect(logBorderColour('LEGENDARY_RESISTANCE_USED')).toBe('border-cls-monk');
  });

  it('falls back to muted for an action type it doesn\'t know', () => {
    expect([logTextColour('SOMETHING_NEW'), logBorderColour('SOMETHING_NEW')]).toEqual(['text-muted', 'border-muted']);
  });

  it('knows the turn changes', () => {
    expect(['TURN_ADVANCE', 'TURN_BACK', 'ATTACK'].map(isTurnChange)).toEqual([true, true, false]);
  });
});
