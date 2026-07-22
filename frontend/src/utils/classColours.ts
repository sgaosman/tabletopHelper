const CLASS_COLOURS: Record<string, { colour: string; bg: string }> = {
  Barbarian:  { colour: '#DC2626', bg: '#FEF2F2' },
  Bard:       { colour: '#DB2777', bg: '#FDF2F8' },
  Cleric:     { colour: '#B8860B', bg: '#FBF6EB' },
  Druid:      { colour: '#059669', bg: '#ECFDF5' },
  Fighter:    { colour: '#92400E', bg: '#FFF7ED' },
  Monk:       { colour: '#CA8A04', bg: '#FFFBEB' },
  Paladin:    { colour: '#A16207', bg: '#FFFBEB' },
  Ranger:     { colour: '#166534', bg: '#F0FDF4' },
  Rogue:      { colour: '#374151', bg: '#F9FAFB' },
  Sorcerer:   { colour: '#9333EA', bg: '#FAF5FF' },
  Warlock:    { colour: '#7C3AED', bg: '#F5F3FF' },
  Wizard:     { colour: '#4F46E5', bg: '#EEF2FF' },
  Artificer:  { colour: '#B45309', bg: '#FFF7ED' },
};

const MONSTER_COLOUR = { colour: '#991B1B', bg: '#FEF2F2' };

export function getClassColour(className: string | undefined | null): string {
  if (!className) return '#78716C';
  const primary = className.split('/')[0].trim();
  return CLASS_COLOURS[primary]?.colour ?? '#78716C';
}

export function getClassBg(className: string | undefined | null): string {
  if (!className) return '#F5F5F0';
  const primary = className.split('/')[0].trim();
  return CLASS_COLOURS[primary]?.bg ?? '#F5F5F0';
}

export function getMonsterColour() {
  return MONSTER_COLOUR.colour;
}

export function getMonsterBg() {
  return MONSTER_COLOUR.bg;
}

export function getParticipantColour(isMonster: boolean, className?: string | null): string {
  return isMonster ? MONSTER_COLOUR.colour : getClassColour(className);
}

export function getParticipantBg(isMonster: boolean, className?: string | null): string {
  return isMonster ? MONSTER_COLOUR.bg : getClassBg(className);
}
