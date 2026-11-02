import type { Schemas } from '../api/schema';

/** A trait, action, reaction, legendary action or lair action in a stat block. */
export interface MonsterEntry {
  name: string;
  description: string;
}

/**
 * A bestiary monster. Its JSON columns arrive as raw JSON (@JsonRawValue), but the spec describes them as
 * strings, so the fields the stat block reads keep the shapes they really have; R5-R6 type them in the
 * spec (D171).
 */
export type Monster = Omit<Schemas['Monster'],
  | 'speed' | 'savingThrows' | 'skills' | 'damageResistances' | 'damageImmunities' | 'damageVulnerabilities'
  | 'conditionImmunities' | 'senses' | 'traits' | 'actions' | 'reactions' | 'legendaryActions' | 'lairActions'
> & {
  speed?: Record<string, number> | null;
  savingThrows?: Record<string, string> | null;
  skills?: Record<string, string> | null;
  damageResistances?: string[] | null;
  damageImmunities?: string[] | null;
  damageVulnerabilities?: string[] | null;
  conditionImmunities?: string[] | null;
  senses?: { special?: string[]; passive_perception?: number } | null;
  traits?: MonsterEntry[] | null;
  actions?: MonsterEntry[] | null;
  reactions?: MonsterEntry[] | null;
  legendaryActions?: MonsterEntry[] | null;
  lairActions?: MonsterEntry[] | null;
};

/** The search and filters of the bestiary; a filter takes several values separated by commas. */
export interface MonsterSearchParams {
  name?: string;
  type?: string;
  cr?: string;
  source?: string;
  page?: number;
  size?: number;
  sort?: string;
}
