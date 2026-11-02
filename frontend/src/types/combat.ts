import type { Schemas } from '../api/schema';

export type CastSpellRequest = Schemas['CastSpellRequest'];

export type TargetOutcome = Schemas['TargetOutcome'];

/** The result of a spell, with the encounter state it left: the caller's view (D157). */
export type CastSpellResponse = Schemas['CastSpellResponse'];

export type RepeatSpellEffectRequest = Schemas['RepeatSpellEffectRequest'];

/**
 * A combat log entry. The players' log leaves out entries about participants the DM hides, and its
 * entries have no Legendary Resistance fields (D157).
 */
export type CombatLogEntry = Schemas['CombatLogResponse'];

// ── M12 Monster Action Types ──────────────────────────────────────

export type MonsterActionRequest = Schemas['MonsterActionRequest'];

export type MonsterActionTargetResult = Schemas['TargetResult'];

export type MonsterActionResponse = Schemas['MonsterActionResponse'];

export type MonsterSpellRequest = Schemas['MonsterSpellRequest'];

export interface MonsterActionTemplate {
  actions: MonsterAction[];
  legendaryActions?: MonsterAction[];
  lairActions?: MonsterAction[];
  spellcasting?: MonsterSpellcasting;
  bonusActions?: MonsterAction[];
  reactions?: MonsterAction[];
  traits?: MonsterTrait[];
  legendaryActionCount?: number;
  legendaryResistanceCount?: number;
  hasLairActions?: boolean;
}

export interface MonsterAction {
  name: string;
  actionType?: string;
  deliveryMethod?: string;
  attackType?: string;
  attackBonus?: number;
  reach?: number;
  range?: number;
  targetCount?: number;
  targetType?: string;
  aoeSize?: number;
  saveDC?: number;
  saveAbility?: string;
  halfOnSave?: boolean;
  effects?: MonsterEffect[];
  isMultiattack?: boolean;
  multiattackDescription?: string;
  multiattackComponents?: string[];
  automatable?: boolean;
  recharge?: string;
  description?: string;
  cost?: number;
  referencesAction?: string;
}

export interface MonsterEffect {
  effectType: string;
  damageDice?: string;
  damageType?: string;
  condition?: string;
  durationRounds?: number;
  durationText?: string;
  saveToEndEachTurn?: boolean;
  saveToEndDC?: number;
  saveToEndAbility?: string;
}

export interface MonsterSpellcasting {
  ability?: string;
  saveDC?: number;
  attackBonus?: number;
  innateSpells?: boolean;
  name?: string;
  slots?: Record<string, number>;
  spellsByLevel?: Record<string, string[]>;
  dailySpells?: Record<string, string[]>;
  atWillSpells?: string[];
}

export interface MonsterTrait {
  name: string;
  description: string;
  combatRelevant?: boolean;
  mechanicType?: string;
}
