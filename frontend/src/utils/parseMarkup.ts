export function parseMarkup(text: string): string {
  if (!text || typeof text !== 'string') return text ?? '';
  return text.replace(/\{@(\w+)(?:\s+([^}]*?))?}/g, (_match, tag: string, content: string = '') => {
    switch (tag) {
      case 'h': return 'Hit: ';
      case 'b': return `<strong>${content}</strong>`;
      case 'i': return `<em>${content}</em>`;
      case 'bold': return `<strong>${content}</strong>`;
      case 'italic': return `<em>${content}</em>`;
      case 'dc': return `DC ${content}`;
      case 'dice':
      case 'damage': return content.includes('|') ? content.split('|')[0] : content;
      case 'hit': return `+${content}`;
      case 'chance': return `${content}%`;
      case 'recharge': return `(Recharge ${content}-6)`;
      case 'scaledamage': {
        const parts = content.split('|');
        return parts.length >= 3 ? parts[2] : content;
      }
      case 'note': return `<em>${content}</em>`;
      case '5etools': return content.split('|')[0];
      case 'quickref': {
        const parts = content.split('|');
        return parts.length >= 5 ? parts[4] : parts[0];
      }
      case 'book': return content.split('|')[0];
      case 'atk': {
        const map: Record<string, string> = {
          'mw': 'Melee Weapon Attack:',
          'rw': 'Ranged Weapon Attack:',
          'mw,rw': 'Melee or Ranged Weapon Attack:',
          'ms': 'Melee Spell Attack:',
          'rs': 'Ranged Spell Attack:',
          'ms,rs': 'Melee or Ranged Spell Attack:',
        };
        return map[content] ?? 'Attack:';
      }
      default: {
        if (content.includes('||')) {
          const afterPipes = content.substring(content.lastIndexOf('||') + 2);
          return afterPipes || content.substring(0, content.indexOf('||'));
        }
        return content.includes('|') ? content.split('|')[0] : content;
      }
    }
  });
}
