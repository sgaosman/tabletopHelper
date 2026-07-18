export interface Monster {
  id: string;
  name: string;
  size: string | null;
  type: string | null;
  subtype: string | null;
  alignment: string | null;
  armourClass: number;
  acType: string | null;
  hitPoints: number;
  hitDice: string | null;
  speed: Record<string, number> | null;
  strength: number;
  dexterity: number;
  constitution: number;
  intelligence: number;
  wisdom: number;
  charisma: number;
  savingThrows: Record<string, string> | null;
  skills: Record<string, string> | null;
  damageResistances: string[] | null;
  damageImmunities: string[] | null;
  damageVulnerabilities: string[] | null;
  conditionImmunities: string[] | null;
  senses: { special?: string[]; passive_perception?: number } | null;
  languages: string | null;
  challengeRating: string | null;
  experiencePoints: number;
  traits: MonsterEntry[] | null;
  actions: MonsterEntry[] | null;
  reactions: MonsterEntry[] | null;
  legendaryActions: MonsterEntry[] | null;
  lairActions: MonsterEntry[] | null;
  source: string | null;
}

export interface MonsterEntry {
  name: string;
  description: string;
}

export interface MonsterSearchParams {
  name?: string;
  type?: string;
  cr?: string;
  source?: string;
  page?: number;
  size?: number;
  sort?: string;
}
