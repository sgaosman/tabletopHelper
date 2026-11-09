// The text helpers FormattedDescription and GlossaryText share (M19)

/** The game words a description shows in bold, such as dice, DCs and rest names. */
export const BOLD_PATTERNS: RegExp[] = [
  /\b(At \d+\w{0,2} level)\b/gi,
  /\b(Starting at \d+\w{0,2} level)\b/gi,
  /\b(Beginning at \d+\w{0,2} level)\b/gi,
  /\b(When you reach \d+\w{0,2} level)\b/gi,
  /\b(\d+d\d+(?:\s*\+\s*\d+)?)\b/g,
  /\b(Strength|Dexterity|Constitution|Intelligence|Wisdom|Charisma)(?= (?:modifier|saving throw|check|score|ability))/g,
  /\b(spell (?:save DC|attack bonus|slots?|level))\b/gi,
  /\b(hit points?|hit dice?|bonus action|short rest|long rest|saving throws?|ability checks?|attack rolls?|damage rolls?|ability score improvements?)\b/gi,
  /\b(advantage|disadvantage|proficiency bonus|proficient)\b/gi,
  /\b((?:an? |your |one |as (?:a |an ))?(?:action|reaction))\b/gi,
  /\b(cantrips? known|spells? known|prepared spells?|spellbook|spellcasting ability|ritual)\b/gi,
  /\b(once per (?:short|long) rest|once per day|times? per (?:short|long) rest)\b/gi,
  /\b((?:melee|ranged) (?:weapon |spell )?attacks?)\b/gi,
  /\b(DC \d+)\b/g,
  /\b(armor class|AC)\b/g,
];

const MARKUP_RE = /\{@(\w+)\s+([^}]*?)}/g;

/** The text with any 5etools tag left in it replaced by its display text. */
export function cleanMarkup(text: string): string {
  return text.replace(MARKUP_RE, (_match, tag: string, content: string) => {
    switch (tag) {
      case 'b': case 'bold': return content;
      case 'i': case 'italic': case 'note': return content;
      case 'dc': return `DC ${content}`;
      case 'dice': case 'damage': return content.includes('|') ? content.split('|')[0] : content;
      case 'hit': return `+${content}`;
      case 'chance': return `${content}%`;
      case 'recharge': return `(Recharge ${content}-6)`;
      case 'scaledamage': {
        const parts = content.split('|');
        return parts.length >= 3 ? parts[2] : content;
      }
      case 'filter': return content.split('|')[0];
      case 'quickref': {
        const parts = content.split('|');
        return parts.length >= 5 ? parts[4] : parts[0];
      }
      case 'book': return content.split('|')[0];
      case 'atk': {
        const map: Record<string, string> = {
          'mw': 'Melee Weapon Attack:', 'rw': 'Ranged Weapon Attack:',
          'mw,rw': 'Melee or Ranged Weapon Attack:',
          'ms': 'Melee Spell Attack:', 'rs': 'Ranged Spell Attack:',
        };
        return map[content] ?? 'Attack:';
      }
      default:
        if (content.includes('||')) return content.substring(content.lastIndexOf('||') + 2) || content.substring(0, content.indexOf('||'));
        return content.includes('|') ? content.split('|')[0] : content;
    }
  });
}

/** A run of plain text, or a match of one of the patterns. */
export type TextPart = string | { bold: string };

/**
 * The text split at the matches of the patterns. Earlier patterns win: a match that overlaps one already found is
 * left out. The patterns must have the g flag.
 */
export function splitAtMatches(text: string, patterns: readonly RegExp[]): TextPart[] {
  const allMatches: Array<{ start: number; end: number; text: string }> = [];

  for (const pattern of patterns) {
    pattern.lastIndex = 0;
    let m;
    while ((m = pattern.exec(text)) !== null) {
      const start = m.index;
      const end = start + m[0].length;
      const overlaps = allMatches.some(
        existing => start < existing.end && end > existing.start
      );
      if (!overlaps) {
        allMatches.push({ start, end, text: m[0] });
      }
    }
  }

  if (allMatches.length === 0) return [text];

  allMatches.sort((a, b) => a.start - b.start);

  const parts: TextPart[] = [];
  let cursor = 0;
  for (const match of allMatches) {
    if (match.start > cursor) {
      parts.push(text.slice(cursor, match.start));
    }
    parts.push({ bold: match.text });
    cursor = match.end;
  }
  if (cursor < text.length) {
    parts.push(text.slice(cursor));
  }
  return parts;
}
