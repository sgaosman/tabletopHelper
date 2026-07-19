export interface ParsedFeatOption {
  name: string;
  fixedCantrips: string[];
  cantripChoice: { count: number; classes: string[] } | null;
  fixedSpells: Array<{ name: string; usesPerDay: number }>;
  spellChoice: { count: number; classes: string[]; fromList: string[] | null; usesPerDay: number } | null;
  ability: string | null;
  abilityChoices: string[] | null;
}

function titleCase(s: string): string {
  return s.split(/\s+/).map(w => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase()).join(' ');
}

function stripSpellName(s: string): string {
  return titleCase(s.replace(/#c$/, '').replace(/#\d+$/, '').replace(/\|.*$/, ''));
}

function parseChooseFilter(filter: string): { level: number; classes: string[] } {
  const parts = filter.split('|');
  let level = 0;
  const classes: string[] = [];
  for (const part of parts) {
    const [key, val] = part.split('=');
    if (key === 'level') level = parseInt(val) || 0;
    if (key === 'class') classes.push(...val.split(';').map(c => titleCase(c.trim())));
  }
  return { level, classes };
}

export function parseFeatOptions(grantsFeatures: string | null): ParsedFeatOption[] {
  if (!grantsFeatures) return [];
  let raw: any[];
  try {
    raw = typeof grantsFeatures === 'string' ? JSON.parse(grantsFeatures) : grantsFeatures;
  } catch {
    return [];
  }
  if (!Array.isArray(raw)) return [];

  return raw.map((option, idx) => {
    const result: ParsedFeatOption = {
      name: option.name || `Option ${idx + 1}`,
      fixedCantrips: [],
      cantripChoice: null,
      fixedSpells: [],
      spellChoice: null,
      ability: null,
      abilityChoices: null,
    };

    if (typeof option.ability === 'string') {
      result.ability = option.ability.toUpperCase();
    } else if (option.ability?.choose) {
      result.abilityChoices = option.ability.choose.map((a: string) => a.toUpperCase());
    }

    const known = option.known?._;
    if (Array.isArray(known)) {
      for (const item of known) {
        if (typeof item === 'string') {
          result.fixedCantrips.push(stripSpellName(item));
        } else if (item?.choose) {
          if (typeof item.choose === 'string') {
            const { classes } = parseChooseFilter(item.choose);
            result.cantripChoice = { count: item.count || 1, classes };
          }
        }
      }
    }

    const daily = option.innate?._?.daily;
    if (daily) {
      for (const [usesKey, spells] of Object.entries(daily)) {
        const usesPerDay = parseInt(usesKey.replace('e', '')) || 1;
        if (Array.isArray(spells)) {
          for (const item of spells as any[]) {
            if (typeof item === 'string') {
              result.fixedSpells.push({ name: stripSpellName(item), usesPerDay });
            } else if (item?.choose) {
              if (typeof item.choose === 'string') {
                const { classes } = parseChooseFilter(item.choose);
                result.spellChoice = { count: 1, classes, fromList: null, usesPerDay };
              } else if (item.choose?.from) {
                const fromList = item.choose.from.map((s: string) => stripSpellName(s));
                const count = item.choose.count || 1;
                result.spellChoice = { count, classes: [], fromList, usesPerDay };
              }
            }
          }
        }
      }
    }

    return result;
  });
}
