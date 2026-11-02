// Class and monster colours for inline styles. They are var() references to the theme tokens
// in index.css rather than hex values, so they follow the chosen theme (D173).
const CLASS_TOKENS: Record<string, string> = {
  Barbarian: 'barbarian',
  Bard: 'bard',
  Cleric: 'cleric',
  Druid: 'druid',
  Fighter: 'fighter',
  Monk: 'monk',
  Paladin: 'paladin',
  Ranger: 'ranger',
  Rogue: 'rogue',
  Sorcerer: 'sorcerer',
  Warlock: 'warlock',
  Wizard: 'wizard',
  Artificer: 'artificer',
};

const FALLBACK_COLOUR = 'var(--color-faint)';
const FALLBACK_BG = 'var(--color-page-alt)';
const MONSTER_COLOUR = 'var(--color-monster)';
const MONSTER_BG = 'var(--color-monster-bg)';

function classToken(className: string | undefined | null): string | undefined {
  if (!className) return undefined;
  return CLASS_TOKENS[className.split('/')[0].trim()];
}

export function getClassColour(className: string | undefined | null): string {
  const token = classToken(className);
  return token ? `var(--color-cls-${token})` : FALLBACK_COLOUR;
}

export function getClassBg(className: string | undefined | null): string {
  const token = classToken(className);
  return token ? `var(--color-cls-${token}-bg)` : FALLBACK_BG;
}

export function getMonsterColour() {
  return MONSTER_COLOUR;
}

export function getMonsterBg() {
  return MONSTER_BG;
}

export function getParticipantColour(isMonster: boolean, className?: string | null): string {
  return isMonster ? MONSTER_COLOUR : getClassColour(className);
}

export function getParticipantBg(isMonster: boolean, className?: string | null): string {
  return isMonster ? MONSTER_BG : getClassBg(className);
}
