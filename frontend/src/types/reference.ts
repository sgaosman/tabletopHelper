import type { Schemas } from '../api/schema';

// The reference entities write their JSON columns as raw JSON (@JsonRawValue), but the spec describes those
// fields as strings. Where a page reads one of them, the type below gives the shape it really has; R5-R6
// type them in the spec (D171).

/** A spell's components, which arrive as a JSON object. */
export interface SpellComponents {
  verbal?: boolean;
  somatic?: boolean;
  material?: string | boolean;
}

export type Spell = Omit<Schemas['Spell'], 'components' | 'classes'> & {
  components?: SpellComponents | null;
  /** The classes and subclasses whose spell lists have the spell, such as "Cleric (Life)". */
  classes?: string[] | null;
};

export type Condition = Omit<Schemas['Condition'], 'effects'> & {
  effects?: string[] | null;
};

export type Item = Omit<Schemas['Item'], 'properties'> & {
  properties?: Record<string, unknown> | null;
};

/** Who a spell can target, read from its effect template; a spell without one has no targeting. */
export type SpellTargetingInfo = Schemas['SpellTargetingResponse'];

/** The search and filters of the spell list; a filter takes several values separated by commas. */
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

/** The search and filters of the item list; a filter takes several values separated by commas. */
export interface ItemSearchParams {
  name?: string;
  type?: string;
  rarity?: string;
  source?: string;
  page?: number;
  size?: number;
  sort?: string;
}

/** One page of a paged list. */
export interface PageResponse<T> {
  content: T[];
  totalPages: number;
  totalElements: number;
  number: number;
  size: number;
}

export type Race = Schemas['Race'];

export type CharacterClassRef = Schemas['CharacterClass'];

/** A subclass, with the class it belongs to. */
export type Subclass = Schemas['Subclass'];

// The wizard's helpers take the proficiency lists as string | null until R8 rewrites them; the server always
// sends the three fields, null when a background has none
export type Background = Omit<Schemas['Background'], 'skillProficiencies' | 'toolProficiencies' | 'languageProficiencies'> & {
  skillProficiencies: string | null;
  toolProficiencies: string | null;
  languageProficiencies: string | null;
};

export type Feat = Schemas['Feat'];

export type OptionalFeature = Schemas['OptionalFeature'];
