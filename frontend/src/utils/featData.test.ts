import { describe, it, expect } from 'vitest';
import type { Feat } from '../types/reference';
import { abilityIncrease, abilityIncreasePreview, choicesNeeded, resistanceOptions, spellSlots } from './featData';

describe('featData', () => {
  it('reads fixed and chosen ability score increases as the server types them', () => {
    const durable: Feat = { id: 'f1', name: 'Durable', abilityScoreIncrease: [{ CON: 1 }] };
    const athlete: Feat = { id: 'f2', name: 'Athlete', abilityScoreIncrease: [{ choose: { from: ['STR', 'DEX'], amount: 1 } }] };
    expect(abilityIncrease(durable)).toEqual({ fixed: { CON: 1 }, choose: null });
    expect(abilityIncreasePreview(athlete)).toBe('+1 STR/DEX');
    expect(abilityIncrease({ id: 'f3', name: 'Alert' })).toBeNull();
  });

  it("counts the choices a feat's effects ask for", () => {
    const effects = {
      resistances: [{ choose: { from: ['fire', 'cold'] } }],
      skillProficiencies: [{ choose: { from: ['athletics'], count: 1 } }],
      expertise: [{ anyProficientSkill: 1 }],
      toolProficiencies: [{ anyArtisansTool: 1 }],
      optionalFeatureProgression: [{ name: 'Invocations', featureType: ['EI'], progression: { '*': 1 } }],
    };
    expect(choicesNeeded(effects, null)).toEqual({
      ability: false, resistance: true, skill: 1, savingThrow: false, expertise: 1, tool: 1, language: 0, weapon: 0,
      optionalFeatures: 1,
    });
    expect(resistanceOptions(effects)).toEqual(['fire', 'cold']);
  });

  it('turns an option of spells into those granted and one filter per spell chosen', () => {
    expect(spellSlots({
      known: { _: [{ choose: 'level=0|class=Wizard', count: 2 }] },
      innate: { _: { daily: { '1': ['misty step', { choose: 'level=1|school=D;E' }] } } },
    })).toEqual({
      fixed: ['Misty Step'],
      choices: [
        { level: 0, classes: ['Wizard'], schools: [] },
        { level: 0, classes: ['Wizard'], schools: [] },
        { level: 1, classes: [], schools: ['Divination', 'Enchantment'] },
      ],
    });
    expect(spellSlots({ innate: { _: { daily: { '1e': [{ choose: { from: ['hex|xge', 'bane'] } }] } } } }).choices)
      .toEqual([{ level: -1, classes: [], schools: [], fromList: ['Hex', 'Bane'] }]);
  });
});
