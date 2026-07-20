export interface SpellEntry {
  name: string;
  level: number;
  source?: string;
  prepared?: boolean;
  alwaysPrepared?: boolean;
  atWill?: boolean;
  usesPerLongRest?: number;
  unlocksAtLevel?: number;
}
