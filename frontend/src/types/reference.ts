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
