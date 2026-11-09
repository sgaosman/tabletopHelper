const TAG_RE = /\{@(\w+)(?:\s+([^}]*?))?}/g;

/** A run of text from parseMarkupSegments, plain or in the style its tag gave it. */
export interface MarkupSegment {
  text: string;
  style?: 'bold' | 'italic';
}

/** What a 5etools tag shows: its text and, for {@b}, {@i} and {@note}, a style. */
function convertTag(tag: string, content: string): MarkupSegment {
  switch (tag) {
    case 'h': return { text: 'Hit: ' };
    case 'b':
    case 'bold': return { text: content, style: 'bold' };
    case 'i':
    case 'italic':
    case 'note': return { text: content, style: 'italic' };
    case 'dc': return { text: `DC ${content}` };
    case 'dice':
    case 'damage': return { text: content.includes('|') ? content.split('|')[0] : content };
    case 'hit': return { text: `+${content}` };
    case 'chance': return { text: `${content}%` };
    case 'recharge': return { text: `(Recharge ${content}-6)` };
    case 'scaledamage': {
      const parts = content.split('|');
      return { text: parts.length >= 3 ? parts[2] : content };
    }
    case '5etools': return { text: content.split('|')[0] };
    case 'quickref': {
      const parts = content.split('|');
      return { text: parts.length >= 5 ? parts[4] : parts[0] };
    }
    case 'book': return { text: content.split('|')[0] };
    case 'atk': {
      const map: Record<string, string> = {
        'mw': 'Melee Weapon Attack:',
        'rw': 'Ranged Weapon Attack:',
        'mw,rw': 'Melee or Ranged Weapon Attack:',
        'ms': 'Melee Spell Attack:',
        'rs': 'Ranged Spell Attack:',
        'ms,rs': 'Melee or Ranged Spell Attack:',
      };
      return { text: map[content] ?? 'Attack:' };
    }
    default: {
      if (content.includes('||')) {
        const afterPipes = content.substring(content.lastIndexOf('||') + 2);
        return { text: afterPipes || content.substring(0, content.indexOf('||')) };
      }
      return { text: content.includes('|') ? content.split('|')[0] : content };
    }
  }
}

/** The text with its 5etools tags as HTML, for dangerouslySetInnerHTML. */
export function parseMarkup(text: string): string {
  if (!text || typeof text !== 'string') return text ?? '';
  return text.replace(TAG_RE, (_match, tag: string, content: string = '') => {
    const { text: shown, style } = convertTag(tag, content);
    if (style === 'bold') return `<strong>${shown}</strong>`;
    if (style === 'italic') return `<em>${shown}</em>`;
    return shown;
  });
}

/**
 * The text with its 5etools tags converted as parseMarkup converts them, as segments to render as React elements
 * rather than HTML. A stat block's {@h} becomes an italic "Hit:", as the Monster Manual prints it. Neighbouring plain
 * runs are joined, and empty runs dropped.
 */
export function parseMarkupSegments(text: string): MarkupSegment[] {
  if (!text || typeof text !== 'string') return [];
  const segments: MarkupSegment[] = [];
  const push = (segment: MarkupSegment) => {
    if (!segment.text) return;
    const last = segments[segments.length - 1];
    if (last && !last.style && !segment.style) last.text += segment.text;
    else segments.push({ ...segment });
  };
  let cursor = 0;
  for (const match of text.matchAll(TAG_RE)) {
    push({ text: text.slice(cursor, match.index) });
    const [, tag, content = ''] = match;
    if (tag === 'h') {
      push({ text: 'Hit:', style: 'italic' });
      push({ text: ' ' });
    } else {
      push(convertTag(tag, content));
    }
    cursor = match.index + match[0].length;
  }
  push({ text: text.slice(cursor) });
  return segments;
}

/** The text with its 5etools tags converted to plain text, without styles, such as for a stat block entry's name. */
export function markupText(text: string): string {
  return parseMarkupSegments(text).map(segment => segment.text).join('');
}
