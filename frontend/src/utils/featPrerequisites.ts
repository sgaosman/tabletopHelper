import type { PlayerCharacter } from '../types/character';
import type { Feat } from '../types/reference';

interface PrereqResult {
  eligible: boolean;
  reason: string | null;
}

const ABILITY_MAP: Record<string, keyof PlayerCharacter> = {
  strength: 'strength', str: 'strength',
  dexterity: 'dexterity', dex: 'dexterity',
  constitution: 'constitution', con: 'constitution',
  intelligence: 'intelligence', int: 'intelligence',
  wisdom: 'wisdom', wis: 'wisdom',
  charisma: 'charisma', cha: 'charisma',
};

export function checkFeatPrerequisites(feat: Feat, character: PlayerCharacter): PrereqResult {
  if (!feat.prerequisite) return { eligible: true, reason: null };

  let prereqs: any[];
  try {
    prereqs = JSON.parse(feat.prerequisite);
  } catch {
    return { eligible: true, reason: null };
  }
  if (!Array.isArray(prereqs) || prereqs.length === 0) return { eligible: true, reason: null };

  const reasons: string[] = [];

  for (const prereq of prereqs) {
    if (prereq.ability) {
      const abilityReqs = Array.isArray(prereq.ability) ? prereq.ability : [prereq.ability];
      for (const req of abilityReqs) {
        const entries = Object.entries(req) as [string, number][];
        for (const [ability, minimum] of entries) {
          const key = ABILITY_MAP[ability.toLowerCase()];
          if (key) {
            const score = character[key] as number;
            if (score < minimum) {
              reasons.push(`${ability.charAt(0).toUpperCase() + ability.slice(1)} ${minimum} (you have ${score})`);
            }
          }
        }
      }
    }

    if (prereq.race) {
      const raceNames = Array.isArray(prereq.race) ? prereq.race : [prereq.race];
      const charRace = (character.race || '').toLowerCase();
      const matches = raceNames.some((r: string) => {
        const raceName = typeof r === 'string' ? r : '';
        return charRace.includes(raceName.toLowerCase());
      });
      if (!matches) {
        const names = raceNames.map((r: string) => typeof r === 'string' ? r : '').join(', ');
        reasons.push(`Race: ${names}`);
      }
    }

    if (prereq.spellcasting) {
      if (!character.spellcastingAbility) {
        reasons.push('Spellcasting ability required');
      }
    }

    if (prereq.level) {
      const minLevel = typeof prereq.level === 'number' ? prereq.level : prereq.level;
      if (character.level < minLevel) {
        reasons.push(`Level ${minLevel} required`);
      }
    }

    if (prereq.proficiency) {
      const profReqs = Array.isArray(prereq.proficiency) ? prereq.proficiency : [prereq.proficiency];
      for (const req of profReqs) {
        if (typeof req === 'string' && !hasProficiency(character, req)) {
          reasons.push(`Proficiency: ${req}`);
        }
      }
    }

    if (prereq.feat) {
      const featReqs = Array.isArray(prereq.feat) ? prereq.feat : [prereq.feat];
      const charFeats = getCharacterFeats(character);
      for (const req of featReqs) {
        const reqName = typeof req === 'string' ? req : '';
        if (!charFeats.some(f => f.toLowerCase() === reqName.toLowerCase())) {
          reasons.push(`Feat: ${reqName}`);
        }
      }
    }
  }

  if (reasons.length > 0) {
    return { eligible: false, reason: reasons.join('; ') };
  }
  return { eligible: true, reason: null };
}

function hasProficiency(character: PlayerCharacter, prof: string): boolean {
  const lower = prof.toLowerCase();
  const checkList = (json: string | undefined) => {
    if (!json) return false;
    try {
      const list: string[] = JSON.parse(json);
      return list.some(p => p.toLowerCase().includes(lower));
    } catch { return false; }
  };
  return checkList(character.armorProficiencies)
      || checkList(character.weaponProficiencies)
      || checkList(character.toolProficiencies);
}

function getCharacterFeats(character: PlayerCharacter): string[] {
  if (!character.features) return [];
  try {
    const features: Array<{ name: string; source: string }> = JSON.parse(character.features);
    return features.filter(f => f.source === 'Feat').map(f => f.name);
  } catch { return []; }
}

export interface FeatEffects {
  resistances?: any[];
  expertise?: any[];
  armorProficiencies?: any[];
  weaponProficiencies?: any[];
  toolProficiencies?: any[];
  skillProficiencies?: any[];
  languageProficiencies?: any[];
  savingThrowProficiencies?: any[];
  skillToolLanguageProficiencies?: any[];
  optionalFeatureProgression?: any[];
  speedBonus?: number;
  initiativeBonus?: number;
  hpPerLevel?: number;
  passivePerceptionBonus?: number;
  passiveInvestigationBonus?: number;
  resource?: { name: string; maxUses: number; resetOn: string };
}

export function parseFeatEffects(feat: Feat): FeatEffects | null {
  if (!feat.effects) return null;
  try {
    return typeof feat.effects === 'string' ? JSON.parse(feat.effects) : feat.effects;
  } catch { return null; }
}

export interface AbilityScoreIncrease {
  fixed: Record<string, number>;
  choose: { from: string[]; amount: number } | null;
}

export function parseAbilityScoreIncrease(feat: Feat): AbilityScoreIncrease | null {
  if (!feat.abilityScoreIncrease) return null;
  try {
    const parsed = typeof feat.abilityScoreIncrease === 'string'
      ? JSON.parse(feat.abilityScoreIncrease) : feat.abilityScoreIncrease;
    if (!Array.isArray(parsed) || parsed.length === 0) return null;

    const result: AbilityScoreIncrease = { fixed: {}, choose: null };
    for (const entry of parsed) {
      if (entry.choose) {
        result.choose = { from: entry.choose.from || [], amount: entry.choose.amount || 1 };
      } else {
        for (const [key, val] of Object.entries(entry)) {
          result.fixed[key] = val as number;
        }
      }
    }
    return result;
  } catch { return null; }
}
