export interface CharacterCreateRequest {
  name: string;
  raceId?: string;
  classId?: string;
  subclassId?: string;
  backgroundId?: string;
  race?: string;
  characterClass?: string;
  subclass?: string;
  level?: number;
  background?: string;
  alignment?: string;
  abilityScoreMethod?: string;
  racialAbilityBonuses?: string;
  strength: number;
  dexterity: number;
  constitution: number;
  intelligence: number;
  wisdom: number;
  charisma: number;
  hpMax?: number;
  armourClass?: number;
  initiativeBonus?: number;
  speed?: number;
  proficiencyBonus?: number;
  savingThrowProficiencies?: string;
  skillProficiencies?: string;
  skillExpertises?: string;
  armorProficiencies?: string;
  weaponProficiencies?: string;
  toolProficiencies?: string;
  languageProficiencies?: string;
  spellsKnown?: string;
  spellSlots?: string;
  spellSaveDc?: number;
  spellAttackBonus?: number;
  spellcastingAbility?: string;
  features?: string;
  damageResistances?: string;
  equipment?: string;
  currency?: string;
  hitDiceMap?: string;
  multiclassClassEntries?: string;
  campaignId?: string;
}

export interface CharacterUpdateRequest {
  [key: string]: unknown;
  name?: string;
  race?: string;
  characterClass?: string;
  subclass?: string;
  level?: number;
  experiencePoints?: number;
  background?: string;
  alignment?: string;
  raceId?: string;
  classId?: string;
  subclassId?: string;
  backgroundId?: string;
  abilityScoreMethod?: string;
  racialAbilityBonuses?: string;
  strength?: number;
  dexterity?: number;
  constitution?: number;
  intelligence?: number;
  wisdom?: number;
  charisma?: number;
  hpMax?: number;
  hpCurrent?: number;
  hpTemp?: number;
  hitDiceTotal?: string;
  hitDiceRemaining?: string;
  armourClass?: number;
  initiativeBonus?: number;
  speed?: number;
  proficiencyBonus?: number;
  savingThrowProficiencies?: string;
  skillProficiencies?: string;
  skillExpertises?: string;
  armorProficiencies?: string;
  weaponProficiencies?: string;
  toolProficiencies?: string;
  languageProficiencies?: string;
  damageResistances?: string;
  damageImmunities?: string;
  conditionImmunities?: string;
  features?: string;
  spellsKnown?: string;
  spellSlots?: string;
  spellSaveDc?: number;
  spellAttackBonus?: number;
  spellcastingAbility?: string;
  equipment?: string;
  currency?: string;
  personalityTraits?: string;
  ideals?: string;
  bonds?: string;
  flaws?: string;
  notes?: string;
  portraitUrl?: string;
  campaignId?: string;
  clearCampaign?: boolean;
  multiclassEntries?: string;
  attunedItems?: string;
  equippedItems?: string;
  hitDiceMap?: string;
}

export interface PlayerCharacter {
  id: string;
  userId: string;
  ownerDisplayName: string;
  campaignId?: string | null;
  raceId?: string | null;
  raceName?: string | null;
  classId?: string | null;
  className?: string | null;
  subclassId?: string | null;
  subclassName?: string | null;
  backgroundId?: string | null;
  backgroundName?: string | null;
  name: string;
  race?: string | null;
  characterClass?: string | null;
  subclass?: string | null;
  level: number;
  experiencePoints: number;
  background?: string | null;
  alignment?: string | null;
  strength: number;
  dexterity: number;
  constitution: number;
  intelligence: number;
  wisdom: number;
  charisma: number;
  hpMax: number;
  hpCurrent: number;
  hpTemp: number;
  hitDiceTotal?: string | null;
  hitDiceRemaining?: string | null;
  armourClass: number;
  initiativeBonus: number;
  speed: number;
  proficiencyBonus: number;
  savingThrowProficiencies?: string | null;
  skillProficiencies?: string | null;
  skillExpertises?: string | null;
  armorProficiencies?: string | null;
  weaponProficiencies?: string | null;
  toolProficiencies?: string | null;
  languageProficiencies?: string | null;
  damageResistances?: string | null;
  damageImmunities?: string | null;
  conditionImmunities?: string | null;
  features?: string | null;
  spellsKnown?: string | null;
  spellSlots?: string | null;
  spellSaveDc?: number | null;
  spellAttackBonus?: number | null;
  spellcastingAbility?: string | null;
  subclassAlwaysPreparedSpells?: string | null;
  equipment?: string | null;
  currency?: string | null;
  personalityTraits?: string | null;
  ideals?: string | null;
  bonds?: string | null;
  flaws?: string | null;
  notes?: string | null;
  deathSaveSuccesses: number;
  deathSaveFailures: number;
  portraitUrl?: string | null;
  abilityScoreMethod?: string | null;
  racialAbilityBonuses?: string | null;
  multiclassEntries?: string | null;
  attunedItems?: string | null;
  equippedItems?: string | null;
  hitDiceMap?: string | null;
  levelHistory?: string | null;
  featResources?: string | null;
  resourcePools?: string | null;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface LevelUpResponse {
  character: PlayerCharacter;
  pendingChoices: PendingChoices;
}

export interface PendingChoices {
  asiAvailable: boolean;
  subclassRequired: boolean;
  expertiseAvailable: boolean;
  expertiseCount: number;
  spellSelectionNeeded: boolean;
  spellSelectionType?: string;
  newFeatures: string[];
  maxSpellLevel: number;
}

export interface ApplyChoicesRequest {
  asi?: AsiChoice;
  subclassId?: string;
  classId?: string;
  expertiseSkills?: string[];
}

export interface AsiChoice {
  type: 'ability' | 'feat';
  increases?: AbilityIncrease[];
  featName?: string;
  featId?: string;
  featAbility?: string;
  resistanceChoice?: string;
  skillProficiencyChoices?: string[];
  savingThrowChoice?: string;
  expertiseSkillChoices?: string[];
  toolProficiencyChoices?: string[];
  languageChoices?: string[];
  weaponChoices?: string[];
  spellIds?: string[];
  optionalFeatureIds?: string[];
}

export interface FeatResource {
  featName: string;
  name: string;
  maxUses: number;
  currentUses: number;
  resetOn: string;
}

export interface AbilityIncrease {
  ability: string;
  bonus: number;
}

export interface EligibleClassResponse {
  classId: string;
  className: string;
  currentClassLevel: number;
  currentClass: boolean;
  meetsPrerequisites: boolean;
  prerequisiteDescription: string;
}
