export interface Spell {
  id: string;
  name: string;
  level: number;
  school: string | null;
  castingTime: string | null;
  rangeDistance: string | null;
  components: { verbal?: boolean; somatic?: boolean; material?: string | boolean } | null;
  duration: string | null;
  concentration: boolean;
  ritual: boolean;
  description: string | null;
  higherLevels: string | null;
  classes: string[] | null;
  damageType: string | null;
  damageDice: string | null;
  saveAbility: string | null;
  source: string | null;
}

export interface Condition {
  id: string;
  name: string;
  description: string;
  effects: string[] | null;
  source: string | null;
}

export interface Item {
  id: string;
  name: string;
  type: string | null;
  subtype: string | null;
  rarity: string | null;
  description: string | null;
  properties: Record<string, unknown> | null;
  requiresAttunement: boolean;
  attunementCondition: string | null;
  weight: number | null;
  cost: string | null;
  damageDice: string | null;
  damageType: string | null;
  source: string | null;
}

export interface SpellSearchParams {
  name?: string;
  level?: number | string;
  school?: string;
  source?: string;
  className?: string;
  subclass?: string;
  concentration?: string;
  ritual?: string;
  page?: number;
  size?: number;
  sort?: string;
}

export interface ItemSearchParams {
  name?: string;
  type?: string;
  rarity?: string;
  source?: string;
  page?: number;
  size?: number;
  sort?: string;
}

export interface PageResponse<T> {
  content: T[];
  totalPages: number;
  totalElements: number;
  number: number;
  size: number;
}

export interface Race {
  id: string;
  name: string;
  source: string;
  size: string;
  speed: string;
  abilityScoreBonuses: string;
  creatureType: string;
  darkvision: number | null;
  traits: string;
  proficiencies: string;
  resistances: string;
  raceChoices: string | null;
  baseRaceName: string | null;
  description: string | null;
}

export interface CharacterClassRef {
  id: string;
  name: string;
  source: string;
  hitDice: number;
  primaryAbility: string;
  savingThrowProficiencies: string;
  armorProficiencies: string;
  weaponProficiencies: string;
  toolProficiencies: string;
  skillChoices: string;
  spellcastingAbility: string | null;
  isSpellcaster: boolean;
  isPreparedCaster: boolean;
  isKnownCaster: boolean;
  isPactMagic: boolean;
  spellSlotProgression: string;
  features: string;
  startingEquipment: string;
  subclassLevel: number;
}

export interface Subclass {
  id: string;
  name: string;
  source: string;
  characterClass: { id: string; name: string };
  features: string;
  alwaysPreparedSpells: string;
  additionalProficiencies: string;
}

export interface Background {
  id: string;
  name: string;
  source: string;
  skillProficiencies: string;
  toolProficiencies: string;
  languageProficiencies: string;
  startingEquipment: string;
  feature: string;
  feats: string | null;
  additionalSpells: string | null;
  description: string | null;
}

export interface Feat {
  id: string;
  name: string;
  source: string;
  prerequisite: string;
  description: string;
  abilityScoreIncrease: string;
  grantsFeatures: string;
}
