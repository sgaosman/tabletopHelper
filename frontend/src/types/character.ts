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
  campaignId?: string;
  raceId?: string;
  raceName?: string;
  classId?: string;
  className?: string;
  subclassId?: string;
  subclassName?: string;
  backgroundId?: string;
  backgroundName?: string;
  name: string;
  race?: string;
  characterClass?: string;
  subclass?: string;
  level: number;
  experiencePoints: number;
  background?: string;
  alignment?: string;
  strength: number;
  dexterity: number;
  constitution: number;
  intelligence: number;
  wisdom: number;
  charisma: number;
  hpMax: number;
  hpCurrent: number;
  hpTemp: number;
  hitDiceTotal?: string;
  hitDiceRemaining?: string;
  armourClass: number;
  initiativeBonus: number;
  speed: number;
  proficiencyBonus: number;
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
  subclassAlwaysPreparedSpells?: string;
  equipment?: string;
  currency?: string;
  personalityTraits?: string;
  ideals?: string;
  bonds?: string;
  flaws?: string;
  notes?: string;
  deathSaveSuccesses: number;
  deathSaveFailures: number;
  portraitUrl?: string;
  abilityScoreMethod?: string;
  racialAbilityBonuses?: string;
  multiclassEntries?: string;
  attunedItems?: string;
  equippedItems?: string;
  hitDiceMap?: string;
  levelHistory?: string;
  featResources?: string;
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
