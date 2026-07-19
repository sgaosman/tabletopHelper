import { useState, useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, ArrowRight, Check, ChevronLeft, Search, X } from 'lucide-react';
import { getRaces, getClasses, getSubclasses, getBackgrounds, getFeats, searchSpells } from '../../api/referenceApi';
import { characterApi } from '../../api/characterApi';
import type { Race, CharacterClassRef, Subclass, Background, Feat, Spell } from '../../types/reference';
import { CANTRIPS_KNOWN, SPELLS_KNOWN } from '../../utils/spellConstants';

const ALL_STEPS = ['Basic Info', 'Race', 'Class', 'Ability Scores', 'Background', 'Spells', 'Review'] as const;

const ALIGNMENTS = [
  'Lawful Good', 'Neutral Good', 'Chaotic Good',
  'Lawful Neutral', 'True Neutral', 'Chaotic Neutral',
  'Lawful Evil', 'Neutral Evil', 'Chaotic Evil',
];

const STANDARD_ARRAY = [15, 14, 13, 12, 10, 8];
const ABILITIES = ['strength', 'dexterity', 'constitution', 'intelligence', 'wisdom', 'charisma'] as const;
const ABILITY_LABELS: Record<string, string> = {
  strength: 'STR', dexterity: 'DEX', constitution: 'CON',
  intelligence: 'INT', wisdom: 'WIS', charisma: 'CHA',
};

const STANDARD_LANGUAGES = [
  'Common', 'Dwarvish', 'Elvish', 'Giant', 'Gnomish', 'Goblin', 'Halfling', 'Orc',
];

const EXOTIC_LANGUAGES = [
  'Abyssal', 'Celestial', 'Deep Speech', 'Draconic', 'Infernal', 'Primordial', 'Sylvan', 'Undercommon',
];

const ALL_LANGUAGES = [...STANDARD_LANGUAGES, ...EXOTIC_LANGUAGES];

const ALL_SKILLS = [
  'Acrobatics', 'Animal Handling', 'Arcana', 'Athletics', 'Deception', 'History',
  'Insight', 'Intimidation', 'Investigation', 'Medicine', 'Nature', 'Perception',
  'Performance', 'Persuasion', 'Religion', 'Sleight of Hand', 'Stealth', 'Survival',
];

const ARTISANS_TOOLS = [
  "Alchemist's Supplies", "Brewer's Supplies", "Calligrapher's Supplies",
  "Carpenter's Tools", "Cartographer's Tools", "Cobbler's Tools",
  "Cook's Utensils", "Glassblower's Tools", "Jeweler's Tools",
  "Leatherworker's Tools", "Mason's Tools", "Painter's Supplies",
  "Potter's Tools", "Smith's Tools", "Tinker's Tools",
  "Weaver's Tools", "Woodcarver's Tools",
];

const MUSICAL_INSTRUMENTS = [
  'Bagpipes', 'Drum', 'Dulcimer', 'Flute', 'Lute', 'Lyre',
  'Horn', 'Pan Flute', 'Shawm', 'Viol',
];

const ALL_TOOLS = [
  ...ARTISANS_TOOLS,
  'Disguise Kit', 'Forgery Kit', 'Herbalism Kit',
  "Navigator's Tools", "Poisoner's Kit", "Thieves' Tools",
  ...MUSICAL_INSTRUMENTS,
  'Dice Set', 'Dragonchess Set', 'Playing Card Set', "Three-Dragon Ante Set",
];

const GAMING_SETS = [
  'Dice Set', 'Dragonchess Set', 'Playing Card Set', "Three-Dragon Ante Set",
];

function getToolAnyOptions(entry: string): string[] | null {
  if (entry === 'Any Gaming Set') return GAMING_SETS;
  if (entry === "Any Artisan's Tool") return ARTISANS_TOOLS;
  if (entry === 'Any Musical Instrument') return MUSICAL_INSTRUMENTS;
  return null;
}

function expandToolFrom(from: string[]): string[] {
  const result: string[] = [];
  for (const item of from) {
    if (item === 'AnyArtisansTool') result.push(...ARTISANS_TOOLS);
    else if (item === 'Musical instrument') result.push(...MUSICAL_INSTRUMENTS);
    else if (item === 'Gaming set') result.push(...GAMING_SETS);
    else result.push(item);
  }
  return result;
}

type AbilityScores = Record<typeof ABILITIES[number], number>;

type ProfEntry = string | { choose: { from?: string[]; count?: number } } | { anyStandard: number } | { any: number } | { chooseSet: ProfEntry[][] };

function formatProfEntry(p: ProfEntry): string {
  if (typeof p === 'string') return p;
  if ('any' in p && !('anyStandard' in p) && !('chooseSet' in p)) {
    const n = (p as { any: number }).any;
    return `Any ${n}`;
  }
  if ('choose' in p && !('chooseSet' in p)) {
    const c = (p as { choose: { from?: string[]; count?: number } }).choose;
    const count = c.count ?? 1;
    if (c.from && c.from.length > 0) return `Choose ${count} from ${c.from.join(', ')}`;
    return `Choose ${count}`;
  }
  if ('anyStandard' in p) {
    const n = (p as { anyStandard: number }).anyStandard;
    return `Any ${n} language${n > 1 ? 's' : ''}`;
  }
  return '';
}

function formatProficiencies(raw: string | null): string[] {
  if (!raw) return [];
  const parsed = safeJsonParse<ProfEntry[]>(raw, []);
  return parsed.flatMap(p => {
    if (typeof p === 'object' && p !== null && 'chooseSet' in p) {
      return ['Choose one set from below'];
    }
    const text = formatProfEntry(p);
    return text ? [text] : [];
  });
}

function hasChooseSet(raw: string | null): ProfEntry[][] | null {
  if (!raw) return null;
  const parsed = safeJsonParse<ProfEntry[]>(raw, []);
  for (const p of parsed) {
    if (typeof p === 'object' && p !== null && 'chooseSet' in p) {
      return (p as { chooseSet: ProfEntry[][] }).chooseSet;
    }
  }
  return null;
}

function abilityMod(score: number): number {
  return Math.floor((score - 10) / 2);
}

function formatMod(mod: number): string {
  return mod >= 0 ? `+${mod}` : `${mod}`;
}

function safeJsonParse<T>(json: unknown, fallback: T): T {
  if (json == null) return fallback;
  if (typeof json !== 'string') return json as T;
  try { return JSON.parse(json); } catch { return fallback; }
}

export default function CharacterCreateWizard() {
  const navigate = useNavigate();
  const [step, setStep] = useState(0);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');

  const [name, setName] = useState('');
  const [alignment, setAlignment] = useState('');

  const [races, setRaces] = useState<Race[]>([]);
  const [raceSearch, setRaceSearch] = useState('');
  const [selectedRace, setSelectedRace] = useState<Race | null>(null);

  const [classes, setClasses] = useState<CharacterClassRef[]>([]);
  const [selectedClass, setSelectedClass] = useState<CharacterClassRef | null>(null);
  const [subclasses, setSubclasses] = useState<Subclass[]>([]);
  const [selectedSubclass, setSelectedSubclass] = useState<Subclass | null>(null);

  const [abilityMethod, setAbilityMethod] = useState<'standard' | 'pointbuy' | 'manual'>('standard');
  const [scores, setScores] = useState<AbilityScores>({ strength: 10, dexterity: 10, constitution: 10, intelligence: 10, wisdom: 10, charisma: 10 });
  const [standardAssignments, setStandardAssignments] = useState<Record<string, number | null>>({
    strength: null, dexterity: null, constitution: null,
    intelligence: null, wisdom: null, charisma: null,
  });

  const [backgrounds, setBackgrounds] = useState<Background[]>([]);
  const [bgSearch, setBgSearch] = useState('');
  const [selectedBackground, setSelectedBackground] = useState<Background | null>(null);
  const [bgProfChoices, setBgProfChoices] = useState<Record<string, string[]>>({});
  const [bgSetChoices, setBgSetChoices] = useState<Record<string, number | null>>({});

  const [feats, setFeats] = useState<Feat[]>([]);
  const [raceChoiceSelections, setRaceChoiceSelections] = useState<Record<string, string[]>>({});

  const [bonusAssignments, setBonusAssignments] = useState<Array<{ bonus: number; ability: string | null }>>([]);
  const [selectedCantrips, setSelectedCantrips] = useState<Spell[]>([]);
  const [selectedSpells, setSelectedSpells] = useState<Spell[]>([]);
  const [cantripResults, setCantripResults] = useState<Spell[]>([]);
  const [spellResults, setSpellResults] = useState<Spell[]>([]);
  const [spellSearch, setSpellSearch] = useState('');
  const [cantripSearch, setCantripSearch] = useState('');

  const isSpellcaster = selectedClass?.isSpellcaster ?? false;
  const steps = useMemo(() =>
    isSpellcaster ? ALL_STEPS : ALL_STEPS.filter(s => s !== 'Spells'),
    [isSpellcaster]
  );

  useEffect(() => {
    getRaces().then(res => setRaces(res));
    getClasses().then(res => setClasses(res));
    getBackgrounds().then(res => setBackgrounds(res));
    getFeats().then(res => setFeats(res));
  }, []);

  useEffect(() => {
    if (selectedClass) {
      getSubclasses(selectedClass.id).then(res => setSubclasses(res));
    } else {
      setSubclasses([]);
      setSelectedSubclass(null);
    }
  }, [selectedClass]);

  useEffect(() => {
    if (!selectedRace) { setBonusAssignments([]); return; }
    const bonuses = safeJsonParse<Array<{ ability: string; bonus: number; count?: number }>>(selectedRace.abilityScoreBonuses, []);
    const rows: Array<{ bonus: number; ability: string | null }> = [];
    for (const b of bonuses) {
      if (b.ability === 'CHOOSE') {
        for (let i = 0; i < (b.count || 1); i++) rows.push({ bonus: b.bonus, ability: null });
      } else {
        const key = b.ability.toLowerCase().slice(0, 3);
        const ability = ABILITIES.find(a => a.startsWith(key)) || null;
        rows.push({ bonus: b.bonus, ability });
      }
    }
    setBonusAssignments(rows);
  }, [selectedRace]);

  // Race choice requirements
  type RaceChoiceReq = { key: string; label: string; options: string[]; count: number };

  useEffect(() => { setRaceChoiceSelections({}); }, [selectedRace]);

  const raceChoiceReqs = useMemo((): RaceChoiceReq[] => {
    if (!selectedRace) return [];
    const choices = safeJsonParse<Record<string, unknown>>(selectedRace.raceChoices, null);
    if (!choices) return [];
    const reqs: RaceChoiceReq[] = [];
    const profs = safeJsonParse<{ skills?: string[]; languages?: string[]; tools?: string[] }>(selectedRace.proficiencies, {});

    if (choices.languages) {
      const existingLangs = new Set((profs.languages ?? []).map((l: string) => l.toLowerCase()));
      for (const lc of choices.languages as Array<Record<string, unknown>>) {
        if (lc.anyStandard) {
          const options = ALL_LANGUAGES.filter(l => !existingLangs.has(l.toLowerCase()));
          reqs.push({ key: `lang_${reqs.length}`, label: 'Language', options, count: lc.anyStandard as number });
        }
        if (lc.choose) {
          const ch = lc.choose as { from?: string[]; count?: number };
          if (ch.from?.length) reqs.push({ key: `lang_${reqs.length}`, label: 'Language', options: ch.from, count: ch.count ?? 1 });
        }
      }
    }

    if (choices.skills) {
      const existingSkills = new Set((profs.skills ?? []).map((s: string) => s.toLowerCase()));
      for (const sc of choices.skills as Array<Record<string, unknown>>) {
        if (sc.any) {
          const options = ALL_SKILLS.filter(s => !existingSkills.has(s.toLowerCase()));
          reqs.push({ key: `skill_${reqs.length}`, label: 'Skill Proficiency', options, count: sc.any as number });
        }
        if (sc.choose) {
          const ch = sc.choose as { from?: string[]; count?: number };
          if (ch.from?.length) reqs.push({ key: `skill_${reqs.length}`, label: 'Skill Proficiency', options: ch.from, count: ch.count ?? 1 });
        }
      }
    }

    if (choices.tools) {
      for (const tc of choices.tools as Array<Record<string, unknown>>) {
        if (tc.any) reqs.push({ key: `tool_${reqs.length}`, label: 'Tool Proficiency', options: ALL_TOOLS, count: tc.any as number });
        if (tc.anyArtisansTool) reqs.push({ key: `tool_${reqs.length}`, label: "Artisan's Tool", options: ARTISANS_TOOLS, count: tc.anyArtisansTool as number });
        if (tc.anyMusicalInstrument) reqs.push({ key: `tool_${reqs.length}`, label: 'Musical Instrument', options: MUSICAL_INSTRUMENTS, count: tc.anyMusicalInstrument as number });
        if (tc.choose) {
          const ch = tc.choose as { from?: string[]; count?: number };
          if (ch.from?.length) reqs.push({ key: `tool_${reqs.length}`, label: 'Tool Proficiency', options: ch.from, count: ch.count ?? 1 });
        }
      }
    }

    if (choices.weapons) {
      for (const wc of choices.weapons as Array<{ from?: string[]; count?: number }>) {
        reqs.push({ key: `weapon_${reqs.length}`, label: 'Martial Weapon Proficiency', options: wc.from ?? [], count: wc.count ?? 2 });
      }
    }

    if (choices.resistances) {
      for (const rc of choices.resistances as Array<{ from: string[] }>) {
        reqs.push({ key: `resist_${reqs.length}`, label: 'Damage Resistance', options: rc.from, count: 1 });
      }
    }

    if (choices.spellAbility) {
      reqs.push({ key: 'spellAbility', label: 'Spellcasting Ability', options: choices.spellAbility as string[], count: 1 });
    }

    if (choices.feats) {
      const featNames = feats.map(f => f.name).sort();
      if (featNames.length > 0) reqs.push({ key: `feat_${reqs.length}`, label: 'Feat', options: featNames, count: choices.feats as number });
    }

    return reqs;
  }, [selectedRace, feats]);

  function handleRaceChoice(key: string, value: string, count: number) {
    setRaceChoiceSelections(prev => {
      const current = prev[key] ?? [];
      if (current.includes(value)) return { ...prev, [key]: current.filter(v => v !== value) };
      if (current.length >= count) return { ...prev, [key]: [...current.slice(1), value] };
      return { ...prev, [key]: [...current, value] };
    });
  }

  const raceChoicesComplete = useMemo(() => {
    return raceChoiceReqs.every(req => (raceChoiceSelections[req.key] ?? []).length === req.count);
  }, [raceChoiceReqs, raceChoiceSelections]);

  const resolvedRaceChoices = useMemo(() => {
    const result = { languages: [] as string[], skills: [] as string[], tools: [] as string[], weapons: [] as string[], resistances: [] as string[], spellAbility: null as string | null, feats: [] as string[] };
    for (const req of raceChoiceReqs) {
      const selected = raceChoiceSelections[req.key] ?? [];
      if (req.key.startsWith('lang_')) result.languages.push(...selected);
      else if (req.key.startsWith('skill_')) result.skills.push(...selected);
      else if (req.key.startsWith('tool_')) result.tools.push(...selected);
      else if (req.key.startsWith('weapon_')) result.weapons.push(...selected);
      else if (req.key.startsWith('resist_')) result.resistances.push(...selected);
      else if (req.key === 'spellAbility') result.spellAbility = selected[0] ?? null;
      else if (req.key.startsWith('feat_')) result.feats.push(...selected);
    }
    return result;
  }, [raceChoiceReqs, raceChoiceSelections]);

  // Extract all choose/chooseSet requirements from the selected background
  type ChoiceReq = { key: string; label: string; type: 'choose'; from: string[]; count: number }
    | { key: string; label: string; type: 'chooseSet'; sets: string[][] };

  const bgChoiceReqs = useMemo((): ChoiceReq[] => {
    if (!selectedBackground) return [];
    const reqs: ChoiceReq[] = [];
    const fields: Array<[string, string, string]> = [
      ['skillProficiencies', 'Skill', 'skill'],
      ['toolProficiencies', 'Tool', 'tool'],
      ['languageProficiencies', 'Language', 'lang'],
    ];
    for (const [field, label, prefix] of fields) {
      const raw = (selectedBackground as Record<string, string | null>)[field];
      if (!raw) continue;
      const parsed = safeJsonParse<ProfEntry[]>(raw, []);
      let chooseIdx = 0;
      for (const p of parsed) {
        if (typeof p === 'string') {
          const anyOpts = prefix === 'tool' ? getToolAnyOptions(p)
            : prefix === 'skill' && p === 'Any' ? ALL_SKILLS
            : null;
          if (anyOpts) {
            reqs.push({
              key: `${prefix}_choose_${chooseIdx}`,
              label: `${label} Proficiency`,
              type: 'choose',
              from: anyOpts,
              count: 1,
            });
            chooseIdx++;
          }
        } else if (typeof p === 'object' && p !== null && 'any' in p) {
          const n = (p as { any: number }).any;
          const pool = prefix === 'skill' ? ALL_SKILLS : prefix === 'tool' ? ALL_TOOLS : ALL_LANGUAGES;
          reqs.push({
            key: `${prefix}_choose_${chooseIdx}`,
            label: `${label} Proficiency`,
            type: 'choose',
            from: pool,
            count: n,
          });
          chooseIdx++;
        } else if (typeof p === 'object' && p !== null && 'chooseSet' in p) {
          const sets = (p as { chooseSet: ProfEntry[][] }).chooseSet;
          reqs.push({
            key: `${prefix}_set`,
            label: `${label} Proficiencies`,
            type: 'chooseSet',
            sets: sets.map(s => s.map(formatProfEntry).filter(Boolean)),
          });
        } else if (typeof p === 'object' && p !== null && 'anyStandard' in p) {
          const n = (p as { anyStandard: number }).anyStandard;
          reqs.push({
            key: `${prefix}_choose_${chooseIdx}`,
            label: `${label}`,
            type: 'choose',
            from: ALL_LANGUAGES,
            count: n,
          });
          chooseIdx++;
        } else if (typeof p === 'object' && p !== null && 'choose' in p) {
          const c = (p as { choose: { from?: string[]; count?: number } }).choose;
          if (c.from && c.from.length > 0) {
            const expanded = prefix === 'tool' ? expandToolFrom(c.from) : c.from;
            reqs.push({
              key: `${prefix}_choose_${chooseIdx}`,
              label: `${label} Proficiency`,
              type: 'choose',
              from: expanded,
              count: c.count ?? 1,
            });
            chooseIdx++;
          }
        }
      }
    }
    return reqs;
  }, [selectedBackground]);

  useEffect(() => {
    setBgProfChoices({});
    setBgSetChoices({});
  }, [selectedBackground]);

  function handleBgProfChoice(key: string, value: string, count: number) {
    setBgProfChoices(prev => {
      const current = prev[key] ?? [];
      if (current.includes(value)) {
        return { ...prev, [key]: current.filter(v => v !== value) };
      }
      if (current.length >= count) {
        return { ...prev, [key]: [...current.slice(1), value] };
      }
      return { ...prev, [key]: [...current, value] };
    });
  }

  function handleBgSetChoice(key: string, index: number) {
    setBgSetChoices(prev => ({ ...prev, [key]: prev[key] === index ? null : index }));
  }

  const bgChoicesComplete = useMemo(() => {
    return bgChoiceReqs.every(req => {
      if (req.type === 'choose') {
        return (bgProfChoices[req.key] ?? []).length === req.count;
      }
      return bgSetChoices[req.key] != null;
    });
  }, [bgChoiceReqs, bgProfChoices, bgSetChoices]);

  const resolvedBgProfs = useMemo(() => {
    if (!selectedBackground) return { skills: [] as string[], tools: [] as string[], languages: [] as string[] };
    const result: Record<string, string[]> = { skills: [], tools: [], languages: [] };
    const fields: Array<[string, string, string]> = [
      ['skillProficiencies', 'skill', 'skills'],
      ['toolProficiencies', 'tool', 'tools'],
      ['languageProficiencies', 'lang', 'languages'],
    ];
    for (const [field, prefix, outKey] of fields) {
      const raw = (selectedBackground as Record<string, string | null>)[field];
      if (!raw) continue;
      const parsed = safeJsonParse<ProfEntry[]>(raw, []);
      let chooseIdx = 0;
      for (const p of parsed) {
        if (typeof p === 'string') {
          const isAny = prefix === 'tool' ? !!getToolAnyOptions(p)
            : prefix === 'skill' && p === 'Any';
          if (isAny) {
            const chosen = bgProfChoices[`${prefix}_choose_${chooseIdx}`] ?? [];
            result[outKey].push(...chosen);
            chooseIdx++;
          } else {
            result[outKey].push(p);
          }
        } else if (typeof p === 'object' && p !== null && 'any' in p) {
          const chosen = bgProfChoices[`${prefix}_choose_${chooseIdx}`] ?? [];
          result[outKey].push(...chosen);
          chooseIdx++;
        } else if (typeof p === 'object' && p !== null && 'chooseSet' in p) {
          const idx = bgSetChoices[`${prefix}_set`];
          if (idx != null) {
            const sets = (p as { chooseSet: ProfEntry[][] }).chooseSet;
            result[outKey].push(...sets[idx].map(formatProfEntry).filter(Boolean));
          }
        } else if (typeof p === 'object' && p !== null && ('anyStandard' in p || 'choose' in p)) {
          const chosen = bgProfChoices[`${prefix}_choose_${chooseIdx}`] ?? [];
          result[outKey].push(...chosen);
          chooseIdx++;
        }
      }
    }
    return result;
  }, [selectedBackground, bgProfChoices, bgSetChoices]);

  const isVanillaHuman = useMemo(() => {
    if (!selectedRace) return false;
    const bonuses = safeJsonParse<Array<{ ability: string; bonus: number }>>(selectedRace.abilityScoreBonuses, []);
    return bonuses.length === 6 && bonuses.every(b => b.ability !== 'CHOOSE' && b.bonus === 1);
  }, [selectedRace]);

  function handleBonusAssignment(rowIndex: number, ability: string) {
    setBonusAssignments(prev => prev.map((a, i) => {
      if (i === rowIndex) return { ...a, ability };
      if (a.ability === ability) return { ...a, ability: null };
      return a;
    }));
  }

  const filteredRaces = useMemo(() => {
    if (!raceSearch) return races;
    const q = raceSearch.toLowerCase();
    return races.filter(r => r.name.toLowerCase().includes(q));
  }, [races, raceSearch]);

  const filteredBackgrounds = useMemo(() => {
    if (!bgSearch) return backgrounds;
    const q = bgSearch.toLowerCase();
    return backgrounds.filter(b => b.name.toLowerCase().includes(q));
  }, [backgrounds, bgSearch]);

  const racialBonuses = useMemo(() => {
    const map: Record<string, number> = {};
    bonusAssignments.forEach(a => {
      if (a.ability) map[a.ability] = (map[a.ability] || 0) + a.bonus;
    });
    return map;
  }, [bonusAssignments]);

  const finalScores = useMemo(() => {
    const base = abilityMethod === 'standard'
      ? Object.fromEntries(ABILITIES.map(a => [a, standardAssignments[a] ?? 10])) as AbilityScores
      : scores;
    const result: AbilityScores = { ...base };
    for (const [ability, bonus] of Object.entries(racialBonuses)) {
      if (ability in result) {
        result[ability as keyof AbilityScores] += bonus;
      }
    }
    return result;
  }, [scores, standardAssignments, abilityMethod, racialBonuses]);

  const pointBuyTotal = useMemo(() => {
    let total = 0;
    for (const a of ABILITIES) {
      const s = scores[a];
      if (s >= 8 && s <= 13) total += s - 8;
      else if (s === 14) total += 7;
      else if (s === 15) total += 9;
    }
    return total;
  }, [scores]);

  const currentStepName = steps[step];

  function canAdvance(): boolean {
    switch (currentStepName) {
      case 'Basic Info': return name.trim().length > 0;
      case 'Race': {
        if (!selectedRace) return false;
        const bonusesOk = isVanillaHuman || bonusAssignments.length === 0 || bonusAssignments.every(a => a.ability !== null);
        return bonusesOk && raceChoicesComplete;
      }
      case 'Class': return selectedClass !== null;
      case 'Ability Scores':
        if (abilityMethod === 'standard') {
          return Object.values(standardAssignments).every(v => v !== null);
        }
        if (abilityMethod === 'pointbuy') return pointBuyTotal <= 27;
        return true;
      case 'Background': return selectedBackground !== null && bgChoicesComplete;
      case 'Spells': return true;
      default: return true;
    }
  }

  function buildSpellsKnown(): string | undefined {
    if (!selectedClass?.isSpellcaster) return undefined;
    const entries: Array<{ name: string; level: number; source: string; prepared?: boolean }> = [];
    const source = `class:${selectedClass.name}`;
    for (const s of selectedCantrips) {
      entries.push({ name: s.name, level: 0, source });
    }
    for (const s of selectedSpells) {
      entries.push({
        name: s.name, level: s.level, source,
        prepared: selectedClass.isPreparedCaster ? true : undefined,
      });
    }
    // Add race innate spells
    if (selectedRace?.additionalSpells) {
      const raceSpells = safeJsonParse<{
        fixedSpells?: Array<{ name: string; level: number; atWill?: boolean; usesPerLongRest?: number; unlocksAtLevel?: number }>;
      }>(selectedRace.additionalSpells, {});
      const raceSource = `race:${selectedRace.name}`;
      for (const s of raceSpells.fixedSpells ?? []) {
        if (!s.unlocksAtLevel || s.unlocksAtLevel <= 1) {
          entries.push({
            name: s.name, level: s.level ?? 0, source: raceSource,
            ...(s.atWill ? { atWill: true } as any : {}),
            ...(s.usesPerLongRest ? { usesPerLongRest: s.usesPerLongRest } as any : {}),
          });
        }
      }
    }
    return entries.length > 0 ? JSON.stringify(entries) : undefined;
  }

  async function handleCreate() {
    if (!selectedRace || !selectedClass || !selectedBackground) return;
    setSubmitting(true);
    setError('');

    const conMod = abilityMod(finalScores.constitution);
    const hpMax = selectedClass.hitDice + conMod;

    const savingThrows = safeJsonParse<string[]>(selectedClass.savingThrowProficiencies, []);
    const speed = safeJsonParse<{ walk?: number }>(selectedRace.speed, { walk: 30 });
    const resistances = safeJsonParse<string[]>(selectedRace.resistances, []);

    const raceProfs = safeJsonParse<{ skills?: string[]; languages?: string[]; tools?: string[]; weapons?: string[] }>(selectedRace.proficiencies, {});
    const classArmor = safeJsonParse<string[]>(selectedClass.armorProficiencies, []);
    const classWeapons = safeJsonParse<string[]>(selectedClass.weaponProficiencies, []);
    const classTools = safeJsonParse<string[]>(selectedClass.toolProficiencies, []);

    const allSkills = [
      ...(raceProfs.skills ?? []),
      ...resolvedRaceChoices.skills,
      ...resolvedBgProfs.skills,
    ];
    const allWeapons = [
      ...(raceProfs.weapons ?? []),
      ...resolvedRaceChoices.weapons,
      ...classWeapons,
    ];
    const allTools = [
      ...(raceProfs.tools ?? []),
      ...resolvedRaceChoices.tools,
      ...resolvedBgProfs.tools,
      ...classTools,
    ];
    const allLanguages = [
      ...(raceProfs.languages ?? []),
      ...resolvedRaceChoices.languages,
      ...resolvedBgProfs.languages,
    ];

    try {
      const res = await characterApi.create({
        name: name.trim(),
        raceId: selectedRace.id,
        classId: selectedClass.id,
        subclassId: selectedSubclass?.id,
        backgroundId: selectedBackground.id,
        alignment: alignment || undefined,
        abilityScoreMethod: abilityMethod,
        racialAbilityBonuses: JSON.stringify(bonusAssignments.map(a => ({
          ability: a.ability ? ABILITY_LABELS[a.ability] : null,
          bonus: a.bonus,
        }))),
        strength: finalScores.strength,
        dexterity: finalScores.dexterity,
        constitution: finalScores.constitution,
        intelligence: finalScores.intelligence,
        wisdom: finalScores.wisdom,
        charisma: finalScores.charisma,
        hpMax,
        speed: speed.walk ?? 30,
        savingThrowProficiencies: JSON.stringify(savingThrows),
        skillProficiencies: allSkills.length > 0 ? JSON.stringify([...new Set(allSkills)]) : undefined,
        armorProficiencies: classArmor.length > 0 ? JSON.stringify(classArmor) : undefined,
        weaponProficiencies: allWeapons.length > 0 ? JSON.stringify([...new Set(allWeapons)]) : undefined,
        toolProficiencies: allTools.length > 0 ? JSON.stringify([...new Set(allTools)]) : undefined,
        languageProficiencies: allLanguages.length > 0 ? JSON.stringify([...new Set(allLanguages)]) : undefined,
        damageResistances: resistances.length > 0 ? JSON.stringify(resistances) : undefined,
        spellcastingAbility: selectedClass.spellcastingAbility ?? undefined,
        spellsKnown: buildSpellsKnown(),
        hitDiceMap: JSON.stringify({ [selectedClass.name]: { total: 1, remaining: 1, faces: selectedClass.hitDice } }),
      });
      navigate(`/player/characters/${res.data.id}`);
    } catch (err: any) {
      setError(err.response?.data?.error || err.response?.data?.message || 'Failed to create character');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="min-h-screen bg-gray-950">
      <header className="sticky top-0 z-10 bg-gray-950 border-b border-gray-800 px-6 py-4">
        <div className="max-w-3xl mx-auto flex items-center justify-between">
          <button onClick={() => navigate('/player')} className="flex items-center gap-2 text-gray-400 hover:text-white text-sm transition-colors">
            <ChevronLeft className="w-4 h-4" /> Back
          </button>
          <h1 className="text-lg font-bold text-white">Create Character</h1>
          <div className="w-16" />
        </div>
      </header>

      {/* Step indicator */}
      <div className="max-w-3xl mx-auto px-6 pt-6">
        <div className="flex items-center gap-1">
          {steps.map((label, i) => (
            <div key={label} className="flex-1 flex flex-col items-center">
              <div className={`w-8 h-8 rounded-full flex items-center justify-center text-xs font-bold border-2 transition-colors ${
                i < step ? 'bg-green-600 border-green-600 text-white' :
                i === step ? 'bg-indigo-600 border-indigo-600 text-white' :
                'bg-gray-800 border-gray-700 text-gray-500'
              }`}>
                {i < step ? <Check className="w-4 h-4" /> : i + 1}
              </div>
              <span className={`text-xs mt-1 hidden sm:block ${i === step ? 'text-indigo-400' : 'text-gray-500'}`}>{label}</span>
            </div>
          ))}
        </div>
      </div>

      <main className="max-w-3xl mx-auto px-6 py-8">
        {/* Basic Info */}
        {currentStepName === 'Basic Info' && (
          <div className="space-y-6">
            <h2 className="text-xl font-semibold text-white">Basic Information</h2>
            <div>
              <label className="block text-sm font-medium text-gray-300 mb-1">Character Name *</label>
              <input
                type="text"
                value={name}
                onChange={e => setName(e.target.value)}
                maxLength={200}
                autoFocus
                className="w-full max-w-md px-4 py-2.5 bg-gray-800 border border-gray-700 rounded-lg text-white placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-indigo-500"
                placeholder="Enter character name"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-300 mb-1">Alignment</label>
              <div className="grid grid-cols-3 gap-2 max-w-md">
                {ALIGNMENTS.map(a => (
                  <button
                    key={a}
                    onClick={() => setAlignment(alignment === a ? '' : a)}
                    className={`px-3 py-2 rounded-lg text-xs font-medium transition-colors ${
                      alignment === a ? 'bg-indigo-600 text-white' : 'bg-gray-800 text-gray-400 hover:bg-gray-700'
                    }`}
                  >{a}</button>
                ))}
              </div>
            </div>
          </div>
        )}

        {/* Race */}
        {currentStepName === 'Race' && (
          <div className="space-y-4">
            <h2 className="text-xl font-semibold text-white">Choose a Race</h2>
            <div className="relative max-w-md">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-500" />
              <input
                type="text"
                value={raceSearch}
                onChange={e => setRaceSearch(e.target.value)}
                placeholder="Search races..."
                className="w-full pl-10 pr-4 py-2.5 bg-gray-800 border border-gray-700 rounded-lg text-white placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-indigo-500"
              />
            </div>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3 max-h-[60vh] overflow-y-auto pr-1">
              {filteredRaces.map(race => {
                const bonuses = safeJsonParse<Array<{ ability: string; bonus: number; count?: number }>>(race.abilityScoreBonuses, []);
                const fixedParts = bonuses.filter(b => b.ability !== 'CHOOSE').map(b => `${b.ability} +${b.bonus}`);
                const chooseParts = bonuses.filter(b => b.ability === 'CHOOSE').map(b => `${b.count || 1}x +${b.bonus || 1}`);
                const chooseSummary = chooseParts.length > 0 ? `Choose ${chooseParts.join(', ')}` : '';
                const bonusSummary = [...fixedParts, chooseSummary].filter(Boolean).join(', ');
                const speed = safeJsonParse<Record<string, number | boolean>>(race.speed, { walk: 30 });
                const walkSpeed = typeof speed.walk === 'number' ? speed.walk : 30;
                const extraSpeeds = (['fly', 'swim', 'climb', 'burrow'] as const)
                  .filter(k => speed[k] !== undefined)
                  .map(k => `${k} ${speed[k] === true ? walkSpeed : speed[k]} ft`);
                const speedText = [`Speed ${walkSpeed} ft`, ...extraSpeeds].join(', ');
                return (
                  <button
                    key={race.id}
                    onClick={() => setSelectedRace(selectedRace?.id === race.id ? null : race)}
                    className={`p-4 rounded-lg border text-left transition-colors ${
                      selectedRace?.id === race.id
                        ? 'bg-indigo-900/30 border-indigo-500'
                        : 'bg-gray-900 border-gray-800 hover:border-gray-600'
                    }`}
                  >
                    <div className="flex items-start justify-between">
                      <h3 className="text-white font-medium text-sm">{race.name}</h3>
                      <span className="text-gray-500 text-xs">{race.source}</span>
                    </div>
                    <div className="mt-1 space-y-0.5">
                      <p className="text-gray-400 text-xs">{speedText} &middot; {race.size}</p>
                      {bonusSummary && <p className="text-cyan-400 text-xs">{bonusSummary}</p>}
                      {race.darkvision && <p className="text-gray-500 text-xs">Darkvision {race.darkvision} ft</p>}
                    </div>
                  </button>
                );
              })}
            </div>
            {selectedRace && (
              <RaceDetail race={selectedRace} />
            )}
            {selectedRace && raceChoiceReqs.length > 0 && (
              <div className="bg-gray-900 border border-gray-800 rounded-lg p-4 mt-4">
                <h3 className="text-white font-medium mb-1">Race Choices</h3>
                <p className="text-gray-500 text-xs mb-3">Make the following selections for your race</p>
                <div className="space-y-4">
                  {raceChoiceReqs.map(req => (
                    <div key={req.key}>
                      <p className="text-gray-400 text-xs mb-2">
                        {req.label}: choose {req.count}
                      </p>
                      <div className="flex gap-1.5 flex-wrap">
                        {req.options.map(option => (
                          <button
                            key={option}
                            onClick={() => handleRaceChoice(req.key, option, req.count)}
                            className={`px-3 py-1.5 rounded text-xs font-medium transition-colors ${
                              (raceChoiceSelections[req.key] ?? []).includes(option)
                                ? 'bg-indigo-600 text-white'
                                : 'bg-gray-800 text-gray-400 hover:bg-gray-700'
                            }`}
                          >
                            {option}
                          </button>
                        ))}
                      </div>
                    </div>
                  ))}
                </div>
                {!raceChoicesComplete && (
                  <p className="text-amber-400 text-xs mt-3">Complete all selections to continue</p>
                )}
              </div>
            )}
            {selectedRace && !isVanillaHuman && bonusAssignments.length > 0 && (
              <div className="bg-gray-900 border border-gray-800 rounded-lg p-4 mt-4">
                <h3 className="text-white font-medium mb-1">Assign Ability Score Bonuses</h3>
                <p className="text-gray-500 text-xs mb-3">Tasha's rules: reassign racial bonuses to any ability</p>
                <div className="space-y-3">
                  {bonusAssignments.map((assignment, i) => (
                    <div key={i} className="flex items-center gap-3 flex-wrap">
                      <span className="text-cyan-400 text-sm font-medium w-24 shrink-0">
                        Apply +{assignment.bonus} to:
                      </span>
                      <div className="flex gap-1.5">
                        {ABILITIES.map(ability => (
                          <button
                            key={ability}
                            onClick={() => handleBonusAssignment(i, ability)}
                            className={`px-3 py-1.5 rounded text-xs font-medium transition-colors ${
                              assignment.ability === ability
                                ? 'bg-indigo-600 text-white'
                                : 'bg-gray-800 text-gray-400 hover:bg-gray-700'
                            }`}
                          >
                            {ABILITY_LABELS[ability]}
                          </button>
                        ))}
                      </div>
                    </div>
                  ))}
                </div>
                {bonusAssignments.some(a => a.ability === null) && (
                  <p className="text-amber-400 text-xs mt-3">Select all bonuses to continue</p>
                )}
              </div>
            )}
          </div>
        )}

        {/* Step 2: Class */}
        {currentStepName === 'Class' && (
          <div className="space-y-4">
            <h2 className="text-xl font-semibold text-white">Choose a Class</h2>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              {classes.map(cls => {
                const saves = safeJsonParse<string[]>(cls.savingThrowProficiencies, []);
                return (
                  <button
                    key={cls.id}
                    onClick={() => {
                      if (selectedClass?.id === cls.id) {
                        setSelectedClass(null);
                        setSelectedSubclass(null);
                      } else {
                        setSelectedClass(cls);
                        setSelectedSubclass(null);
                        setSelectedCantrips([]);
                        setSelectedSpells([]);
                        setCantripResults([]);
                        setSpellResults([]);
                      }
                    }}
                    className={`p-4 rounded-lg border text-left transition-colors ${
                      selectedClass?.id === cls.id
                        ? 'bg-indigo-900/30 border-indigo-500'
                        : 'bg-gray-900 border-gray-800 hover:border-gray-600'
                    }`}
                  >
                    <div className="flex items-start justify-between">
                      <h3 className="text-white font-medium text-sm">{cls.name}</h3>
                      <span className="text-gray-500 text-xs">d{cls.hitDice}</span>
                    </div>
                    <p className="text-gray-400 text-xs mt-1">Primary: {cls.primaryAbility}</p>
                    {saves.length > 0 && <p className="text-cyan-400 text-xs">Saves: {saves.join(', ')}</p>}
                    {cls.isSpellcaster && <p className="text-purple-400 text-xs mt-0.5">Spellcaster ({cls.spellcastingAbility})</p>}
                  </button>
                );
              })}
            </div>

            {selectedClass && subclasses.length > 0 && selectedClass.subclassLevel <= 1 && (
              <div className="mt-6">
                <h3 className="text-lg font-semibold text-white mb-3">Choose a Subclass (Optional)</h3>
                <div className="grid grid-cols-1 md:grid-cols-2 gap-3 max-h-60 overflow-y-auto">
                  {subclasses.map(sc => (
                    <button
                      key={sc.id}
                      onClick={() => setSelectedSubclass(selectedSubclass?.id === sc.id ? null : sc)}
                      className={`p-3 rounded-lg border text-left transition-colors ${
                        selectedSubclass?.id === sc.id
                          ? 'bg-purple-900/30 border-purple-500'
                          : 'bg-gray-900 border-gray-800 hover:border-gray-600'
                      }`}
                    >
                      <h4 className="text-white text-sm font-medium">{sc.name}</h4>
                      <p className="text-gray-500 text-xs">{sc.source}</p>
                    </button>
                  ))}
                </div>
              </div>
            )}

            {selectedClass && subclasses.length > 0 && selectedClass.subclassLevel > 1 && (
              <p className="text-gray-500 text-sm">Subclass available at level {selectedClass.subclassLevel}</p>
            )}
          </div>
        )}

        {/* Step 3: Ability Scores */}
        {currentStepName === 'Ability Scores' && (
          <div className="space-y-6">
            <h2 className="text-xl font-semibold text-white">Ability Scores</h2>

            <div className="flex gap-2">
              {(['standard', 'pointbuy', 'manual'] as const).map(m => (
                <button
                  key={m}
                  onClick={() => setAbilityMethod(m)}
                  className={`px-4 py-2 rounded-lg text-sm font-medium transition-colors ${
                    abilityMethod === m ? 'bg-indigo-600 text-white' : 'bg-gray-800 text-gray-400 hover:bg-gray-700'
                  }`}
                >
                  {m === 'standard' ? 'Standard Array' : m === 'pointbuy' ? 'Point Buy' : 'Manual'}
                </button>
              ))}
            </div>

            {abilityMethod === 'standard' && (
              <div>
                <p className="text-gray-400 text-sm mb-4">Assign each value to an ability: {STANDARD_ARRAY.join(', ')}</p>
                <StandardArrayAssigner assignments={standardAssignments} onChange={setStandardAssignments} racialBonuses={racialBonuses} />
              </div>
            )}

            {abilityMethod === 'pointbuy' && (
              <div>
                <p className="text-gray-400 text-sm mb-2">Points spent: <span className={pointBuyTotal > 27 ? 'text-red-400' : 'text-cyan-400'}>{pointBuyTotal}/27</span></p>
                <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
                  {ABILITIES.map(a => (
                    <div key={a} className="bg-gray-900 border border-gray-800 rounded-lg p-4 text-center">
                      <label className="text-gray-400 text-xs font-medium block mb-2">{ABILITY_LABELS[a]}</label>
                      <div className="flex items-center justify-center gap-3">
                        <button
                          onClick={() => setScores(s => ({ ...s, [a]: Math.max(8, s[a] - 1) }))}
                          className="w-8 h-8 rounded bg-gray-800 text-gray-400 hover:bg-gray-700 flex items-center justify-center"
                        >-</button>
                        <span className="text-white text-xl font-bold w-8 text-center">{scores[a]}</span>
                        <button
                          onClick={() => setScores(s => ({ ...s, [a]: Math.min(15, s[a] + 1) }))}
                          className="w-8 h-8 rounded bg-gray-800 text-gray-400 hover:bg-gray-700 flex items-center justify-center"
                        >+</button>
                      </div>
                      {racialBonuses[a] && (
                        <p className="text-green-400 text-xs mt-1">+{racialBonuses[a]} racial</p>
                      )}
                      <p className="text-gray-500 text-xs mt-1">
                        Final: {scores[a] + (racialBonuses[a] || 0)} ({formatMod(abilityMod(scores[a] + (racialBonuses[a] || 0)))})
                      </p>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {abilityMethod === 'manual' && (
              <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
                {ABILITIES.map(a => (
                  <div key={a} className="bg-gray-900 border border-gray-800 rounded-lg p-4 text-center">
                    <label className="text-gray-400 text-xs font-medium block mb-2">{ABILITY_LABELS[a]}</label>
                    <input
                      type="number"
                      min={1}
                      max={30}
                      value={scores[a]}
                      onChange={e => setScores(s => ({ ...s, [a]: Math.min(30, Math.max(1, parseInt(e.target.value) || 1)) }))}
                      className="w-full text-center text-xl font-bold bg-gray-800 border border-gray-700 rounded-lg py-2 text-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
                    />
                    {racialBonuses[a] && (
                      <p className="text-green-400 text-xs mt-1">+{racialBonuses[a]} racial</p>
                    )}
                    <p className="text-gray-500 text-xs mt-1">
                      Final: {scores[a] + (racialBonuses[a] || 0)} ({formatMod(abilityMod(scores[a] + (racialBonuses[a] || 0)))})
                    </p>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {/* Step 4: Background */}
        {currentStepName === 'Background' && (
          <div className="space-y-4">
            <h2 className="text-xl font-semibold text-white">Choose a Background</h2>
            <div className="relative max-w-md">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-500" />
              <input
                type="text"
                value={bgSearch}
                onChange={e => setBgSearch(e.target.value)}
                placeholder="Search backgrounds..."
                className="w-full pl-10 pr-4 py-2.5 bg-gray-800 border border-gray-700 rounded-lg text-white placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-indigo-500"
              />
            </div>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3 max-h-[60vh] overflow-y-auto pr-1">
              {filteredBackgrounds.map(bg => {
                const skills = formatProficiencies(bg.skillProficiencies);
                const tools = formatProficiencies(bg.toolProficiencies);
                const langs = formatProficiencies(bg.languageProficiencies);
                const feats = safeJsonParse<string[]>(bg.feats, []);
                return (
                  <button
                    key={bg.id}
                    onClick={() => setSelectedBackground(selectedBackground?.id === bg.id ? null : bg)}
                    className={`p-4 rounded-lg border text-left transition-colors ${
                      selectedBackground?.id === bg.id
                        ? 'bg-indigo-900/30 border-indigo-500'
                        : 'bg-gray-900 border-gray-800 hover:border-gray-600'
                    }`}
                  >
                    <div className="flex items-start justify-between">
                      <h3 className="text-white font-medium text-sm">{bg.name}</h3>
                      <span className="text-gray-500 text-xs">{bg.source}</span>
                    </div>
                    <div className="mt-1 space-y-0.5">
                      {skills.length > 0 && <p className="text-cyan-400 text-xs">Skills: {skills.join(', ')}</p>}
                      {tools.length > 0 && <p className="text-gray-400 text-xs">Tools: {tools.join(', ')}</p>}
                      {langs.length > 0 && <p className="text-gray-400 text-xs">Languages: {langs.join(', ')}</p>}
                      {feats.length > 0 && <p className="text-amber-400 text-xs">Feat: {feats.join(', ')}</p>}
                    </div>
                  </button>
                );
              })}
            </div>
            {selectedBackground && (
              <BackgroundDetail bg={selectedBackground} />
            )}
            {selectedBackground && bgChoiceReqs.length > 0 && (
              <div className="bg-gray-900 border border-gray-800 rounded-lg p-4 mt-4 space-y-4">
                <h3 className="text-white font-medium">Choose Your Proficiencies</h3>
                {bgChoiceReqs.map(req => (
                  <div key={req.key}>
                    {req.type === 'choose' && (
                      <div>
                        <p className="text-gray-400 text-xs mb-2">
                          {req.label}: choose {req.count} from the options below
                        </p>
                        <div className="flex gap-1.5 flex-wrap">
                          {req.from.map(option => (
                            <button
                              key={option}
                              onClick={() => handleBgProfChoice(req.key, option, req.count)}
                              className={`px-3 py-1.5 rounded text-xs font-medium transition-colors ${
                                (bgProfChoices[req.key] ?? []).includes(option)
                                  ? 'bg-indigo-600 text-white'
                                  : 'bg-gray-800 text-gray-400 hover:bg-gray-700'
                              }`}
                            >
                              {option}
                            </button>
                          ))}
                        </div>
                      </div>
                    )}
                    {req.type === 'chooseSet' && (
                      <div>
                        <p className="text-gray-400 text-xs mb-2">
                          {req.label}: choose one set
                        </p>
                        <div className="grid grid-cols-2 md:grid-cols-3 gap-2">
                          {req.sets.map((set, i) => (
                            <button
                              key={i}
                              onClick={() => handleBgSetChoice(req.key, i)}
                              className={`px-3 py-2 rounded border text-xs text-left transition-colors ${
                                bgSetChoices[req.key] === i
                                  ? 'bg-indigo-900/30 border-indigo-500 text-white'
                                  : 'bg-gray-800 border-gray-700 text-gray-400 hover:border-gray-500'
                              }`}
                            >
                              {set.join(', ')}
                            </button>
                          ))}
                        </div>
                      </div>
                    )}
                  </div>
                ))}
                {!bgChoicesComplete && (
                  <p className="text-amber-400 text-xs">Complete all selections to continue</p>
                )}
              </div>
            )}
          </div>
        )}

        {/* Spells */}
        {currentStepName === 'Spells' && selectedClass && (
          <SpellSelectionStep
            selectedClass={selectedClass}
            level={1}
            selectedCantrips={selectedCantrips}
            setSelectedCantrips={setSelectedCantrips}
            selectedSpells={selectedSpells}
            setSelectedSpells={setSelectedSpells}
            cantripResults={cantripResults}
            setCantripResults={setCantripResults}
            spellResults={spellResults}
            setSpellResults={setSpellResults}
            cantripSearch={cantripSearch}
            setCantripSearch={setCantripSearch}
            spellSearch={spellSearch}
            setSpellSearch={setSpellSearch}
          />
        )}

        {/* Review */}
        {currentStepName === 'Review' && (
          <div className="space-y-6">
            <h2 className="text-xl font-semibold text-white">Review Your Character</h2>

            <div className="bg-gray-900 border border-gray-800 rounded-lg p-6 space-y-4">
              <div className="grid grid-cols-2 gap-4">
                <ReviewField label="Name" value={name} />
                <ReviewField label="Alignment" value={alignment || 'None'} />
                <ReviewField label="Race" value={selectedRace?.name || '—'} />
                <ReviewField label="Class" value={selectedClass?.name || '—'} />
                {selectedSubclass && <ReviewField label="Subclass" value={selectedSubclass.name} />}
                <ReviewField label="Background" value={selectedBackground?.name || '—'} />
                <ReviewField label="Hit Points" value={String((selectedClass?.hitDice || 0) + abilityMod(finalScores.constitution))} />
                <ReviewField label="Speed" value={(() => {
                  const sp = safeJsonParse<Record<string, number | boolean>>(selectedRace?.speed ?? null, { walk: 30 });
                  const w = typeof sp.walk === 'number' ? sp.walk : 30;
                  const extras = (['fly', 'swim', 'climb', 'burrow'] as const)
                    .filter(k => sp[k] !== undefined)
                    .map(k => `${k} ${sp[k] === true ? w : sp[k]} ft`);
                  return [`${w} ft`, ...extras].join(', ');
                })()} />
              </div>

              {(resolvedRaceChoices.languages.length > 0 || resolvedRaceChoices.skills.length > 0 || resolvedRaceChoices.tools.length > 0 || resolvedRaceChoices.weapons.length > 0 || resolvedRaceChoices.resistances.length > 0 || resolvedRaceChoices.spellAbility || resolvedRaceChoices.feats.length > 0) && (
                <div className="border-t border-gray-800 pt-4">
                  <h3 className="text-sm font-medium text-gray-400 mb-3">Race Choices</h3>
                  <div className="grid grid-cols-2 gap-4">
                    {resolvedRaceChoices.languages.length > 0 && (
                      <ReviewField label="Languages" value={resolvedRaceChoices.languages.join(', ')} />
                    )}
                    {resolvedRaceChoices.skills.length > 0 && (
                      <ReviewField label="Skills" value={resolvedRaceChoices.skills.join(', ')} />
                    )}
                    {resolvedRaceChoices.tools.length > 0 && (
                      <ReviewField label="Tools" value={resolvedRaceChoices.tools.join(', ')} />
                    )}
                    {resolvedRaceChoices.weapons.length > 0 && (
                      <ReviewField label="Weapons" value={resolvedRaceChoices.weapons.join(', ')} />
                    )}
                    {resolvedRaceChoices.resistances.length > 0 && (
                      <ReviewField label="Damage Resistance" value={resolvedRaceChoices.resistances.join(', ')} />
                    )}
                    {resolvedRaceChoices.spellAbility && (
                      <ReviewField label="Spellcasting Ability" value={resolvedRaceChoices.spellAbility} />
                    )}
                    {resolvedRaceChoices.feats.length > 0 && (
                      <ReviewField label="Feat" value={resolvedRaceChoices.feats.join(', ')} />
                    )}
                  </div>
                </div>
              )}

              {(resolvedBgProfs.skills.length > 0 || resolvedBgProfs.tools.length > 0 || resolvedBgProfs.languages.length > 0) && (
                <div className="border-t border-gray-800 pt-4">
                  <h3 className="text-sm font-medium text-gray-400 mb-3">Background Proficiencies</h3>
                  <div className="grid grid-cols-2 gap-4">
                    {resolvedBgProfs.skills.length > 0 && (
                      <ReviewField label="Skills" value={resolvedBgProfs.skills.join(', ')} />
                    )}
                    {resolvedBgProfs.tools.length > 0 && (
                      <ReviewField label="Tools" value={resolvedBgProfs.tools.join(', ')} />
                    )}
                    {resolvedBgProfs.languages.length > 0 && (
                      <ReviewField label="Languages" value={resolvedBgProfs.languages.join(', ')} />
                    )}
                  </div>
                </div>
              )}

              <div className="border-t border-gray-800 pt-4">
                <h3 className="text-sm font-medium text-gray-400 mb-3">Ability Scores</h3>
                <div className="grid grid-cols-6 gap-3">
                  {ABILITIES.map(a => (
                    <div key={a} className="text-center">
                      <p className="text-gray-500 text-xs">{ABILITY_LABELS[a]}</p>
                      <p className="text-white text-lg font-bold">{finalScores[a]}</p>
                      <p className="text-gray-400 text-xs">{formatMod(abilityMod(finalScores[a]))}</p>
                    </div>
                  ))}
                </div>
              </div>
            </div>

            {error && <p className="text-red-400 text-sm">{error}</p>}
          </div>
        )}

        {/* Navigation buttons */}
        <div className="flex items-center justify-between mt-8 pt-6 border-t border-gray-800">
          <button
            onClick={() => step > 0 ? setStep(step - 1) : navigate('/player')}
            className="flex items-center gap-2 px-4 py-2.5 text-gray-400 hover:text-white text-sm transition-colors"
          >
            <ArrowLeft className="w-4 h-4" /> {step > 0 ? 'Previous' : 'Cancel'}
          </button>

          {step < steps.length - 1 ? (
            <button
              onClick={() => setStep(step + 1)}
              disabled={!canAdvance()}
              className="flex items-center gap-2 px-6 py-2.5 bg-indigo-600 hover:bg-indigo-500 disabled:bg-gray-700 disabled:text-gray-500 text-white rounded-lg text-sm transition-colors"
            >
              Next <ArrowRight className="w-4 h-4" />
            </button>
          ) : (
            <button
              onClick={handleCreate}
              disabled={submitting}
              className="flex items-center gap-2 px-6 py-2.5 bg-green-600 hover:bg-green-500 disabled:bg-gray-700 disabled:text-gray-500 text-white rounded-lg text-sm font-medium transition-colors"
            >
              {submitting ? 'Creating...' : 'Create Character'} <Check className="w-4 h-4" />
            </button>
          )}
        </div>
      </main>
    </div>
  );
}

function StandardArrayAssigner({
  assignments,
  onChange,
  racialBonuses,
}: {
  assignments: Record<string, number | null>;
  onChange: (a: Record<string, number | null>) => void;
  racialBonuses: Record<string, number>;
}) {
  const usedValues = Object.values(assignments).filter((v): v is number => v !== null);
  const available = STANDARD_ARRAY.filter(v => {
    const usedCount = usedValues.filter(u => u === v).length;
    const totalCount = STANDARD_ARRAY.filter(a => a === v).length;
    return usedCount < totalCount;
  });

  return (
    <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
      {ABILITIES.map(a => (
        <div key={a} className="bg-gray-900 border border-gray-800 rounded-lg p-4 text-center">
          <label className="text-gray-400 text-xs font-medium block mb-2">{ABILITY_LABELS[a]}</label>
          <select
            value={assignments[a] ?? ''}
            onChange={e => {
              const val = e.target.value ? parseInt(e.target.value) : null;
              onChange({ ...assignments, [a]: val });
            }}
            className="w-full text-center text-lg font-bold bg-gray-800 border border-gray-700 rounded-lg py-2 text-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
          >
            <option value="">—</option>
            {STANDARD_ARRAY.map(v => {
              const isCurrentValue = assignments[a] === v;
              const isAvailable = available.includes(v) || isCurrentValue;
              return (
                <option key={`${a}-${v}`} value={v} disabled={!isAvailable}>{v}</option>
              );
            })}
          </select>
          {racialBonuses[a] && (
            <p className="text-green-400 text-xs mt-1">+{racialBonuses[a]} racial</p>
          )}
          {assignments[a] !== null && (
            <p className="text-gray-500 text-xs mt-1">
              Final: {(assignments[a] ?? 0) + (racialBonuses[a] || 0)} ({formatMod(abilityMod((assignments[a] ?? 0) + (racialBonuses[a] || 0)))})
            </p>
          )}
        </div>
      ))}
    </div>
  );
}

function RaceDetail({ race }: { race: Race }) {
  const traits = safeJsonParse<Array<{ name: string; description: string }>>(race.traits, []);
  const profs = safeJsonParse<{ skills?: string[]; languages?: string[]; weapons?: string[]; armor?: string[]; tools?: string[] }>(race.proficiencies, {});

  const profItems: string[] = [];
  if (profs.skills?.length) profItems.push(`Skills: ${profs.skills.join(', ')}`);
  if (profs.weapons?.length) profItems.push(`Weapons: ${profs.weapons.join(', ')}`);
  if (profs.armor?.length) profItems.push(`Armor: ${profs.armor.join(', ')}`);
  if (profs.tools?.length) profItems.push(`Tools: ${profs.tools.join(', ')}`);
  if (profs.languages?.length) profItems.push(`Languages: ${profs.languages.join(', ')}`);

  return (
    <div className="bg-gray-900 border border-gray-800 rounded-lg p-4 mt-4">
      <h3 className="text-white font-medium mb-2">{race.name} — Traits</h3>
      {profItems.length > 0 && (
        <p className="text-cyan-400 text-xs mb-2">{profItems.join(' | ')}</p>
      )}
      <div className="space-y-2">
        {traits.slice(0, 8).map((t, i) => (
          <div key={i}>
            <p className="text-gray-300 text-sm font-medium">{t.name}</p>
            <p className="text-gray-500 text-xs line-clamp-2">{t.description}</p>
          </div>
        ))}
      </div>
    </div>
  );
}

function ChooseSetPicker({ label, sets, colorClass }: { label: string; sets: ProfEntry[][]; colorClass: string }) {
  return (
    <div className="md:col-span-2">
      <p className="text-gray-500 text-xs font-medium mb-1">{label} (choose one set)</p>
      <div className="grid grid-cols-2 md:grid-cols-3 gap-2">
        {sets.map((set, i) => (
          <div key={i} className="bg-gray-800 rounded px-3 py-1.5 border border-gray-700">
            <p className={`${colorClass} text-xs`}>{set.map(formatProfEntry).filter(Boolean).join(', ')}</p>
          </div>
        ))}
      </div>
    </div>
  );
}

function BackgroundDetail({ bg }: { bg: Background }) {
  const feature = safeJsonParse<{ name: string; description: string } | null>(bg.feature, null);
  const skills = formatProficiencies(bg.skillProficiencies);
  const tools = formatProficiencies(bg.toolProficiencies);
  const langs = formatProficiencies(bg.languageProficiencies);
  const feats = safeJsonParse<string[]>(bg.feats, []);
  const spells = safeJsonParse<Record<string, string[]>[]>(bg.additionalSpells, []);
  const equipment = safeJsonParse<Array<Record<string, unknown>>>(bg.startingEquipment, []);

  function fmtCurrency(cp: number): string {
    if (cp >= 100 && cp % 100 === 0) return `${cp / 100} gp`;
    if (cp >= 10 && cp % 10 === 0) return `${cp / 10} sp`;
    return `${cp} cp`;
  }

  function strip5eMarkup(s: string): string {
    return s.replace(/\{@\w+ ([^|}]+)[^}]*\}/g, '$1');
  }

  function fmtEquipItem(item: unknown): string | null {
    if (typeof item === 'string') {
      const name = item.includes('|') ? item.substring(0, item.indexOf('|')) : item;
      return name.charAt(0).toUpperCase() + name.slice(1);
    }
    if (typeof item !== 'object' || item === null) return null;
    const obj = item as Record<string, unknown>;
    const qty = typeof obj.quantity === 'number' && obj.quantity > 1 ? obj.quantity : null;
    let label = '';
    if (typeof obj.displayName === 'string') {
      label = strip5eMarkup(obj.displayName);
    } else if (typeof obj.item === 'string') {
      const raw = obj.item as string;
      label = raw.includes('|') ? raw.substring(0, raw.indexOf('|')) : raw;
    } else if (typeof obj.equipmentType === 'string') {
      const et: Record<string, string> = { instrumentMusical: 'Musical instrument', setGaming: 'Gaming set', toolArtisan: "Artisan's tools", holy: 'Holy symbol' };
      label = et[obj.equipmentType as string] ?? (obj.equipmentType as string);
    } else if (typeof obj.special === 'string') {
      label = obj.special as string;
    } else if (typeof obj.value === 'number') {
      return fmtCurrency(obj.value as number);
    } else {
      return null;
    }
    label = label.charAt(0).toUpperCase() + label.slice(1);
    if (qty) label = `${qty} ${label}`;
    if (typeof obj.containsValue === 'number') {
      label += ` containing ${fmtCurrency(obj.containsValue as number)}`;
    }
    if (typeof obj.worthValue === 'number') {
      label += ` (worth ${fmtCurrency(obj.worthValue as number)})`;
    }
    return label;
  }

  const equipItems: string[] = [];
  const equipChoices: string[] = [];
  for (const entry of equipment) {
    if (entry._ && Array.isArray(entry._)) {
      for (const item of entry._) equipItems.push(fmtEquipItem(item) ?? '');
    }
    const choiceKeys = Object.keys(entry).filter(k => k !== '_').sort();
    if (choiceKeys.length > 0) {
      const options = choiceKeys.map(k => {
        const arr = entry[k] as unknown[];
        return arr.map(i => fmtEquipItem(i)).filter(Boolean).join(', ');
      });
      equipChoices.push(options.join(' -or- '));
    }
  }
  const allEquip = [...equipItems.filter(Boolean), ...equipChoices].filter(Boolean);

  const skillSets = hasChooseSet(bg.skillProficiencies);
  const toolSets = hasChooseSet(bg.toolProficiencies);
  const langSets = hasChooseSet(bg.languageProficiencies);

  const LEVEL_LABELS: Record<string, string> = { s1: '1st', s2: '2nd', s3: '3rd', s4: '4th', s5: '5th' };

  return (
    <div className="bg-gray-900 border border-gray-800 rounded-lg p-4 mt-4">
      <h3 className="text-white font-medium mb-3">{bg.name} — Summary</h3>
      <div className="grid grid-cols-1 md:grid-cols-2 gap-x-6 gap-y-2">
        {!skillSets && skills.length > 0 && (
          <div>
            <p className="text-gray-500 text-xs font-medium">Skill Proficiencies</p>
            <p className="text-cyan-400 text-sm">{skills.join(', ')}</p>
          </div>
        )}
        {skillSets && <ChooseSetPicker label="Skill Proficiencies" sets={skillSets} colorClass="text-cyan-400" />}
        {!toolSets && tools.length > 0 && (
          <div>
            <p className="text-gray-500 text-xs font-medium">Tool Proficiencies</p>
            <p className="text-gray-300 text-sm">{tools.join(', ')}</p>
          </div>
        )}
        {toolSets && <ChooseSetPicker label="Tool Proficiencies" sets={toolSets} colorClass="text-gray-300" />}
        {!langSets && langs.length > 0 && (
          <div>
            <p className="text-gray-500 text-xs font-medium">Languages</p>
            <p className="text-gray-300 text-sm">{langs.join(', ')}</p>
          </div>
        )}
        {langSets && <ChooseSetPicker label="Languages" sets={langSets} colorClass="text-gray-300" />}
        {feats.length > 0 && (
          <div>
            <p className="text-gray-500 text-xs font-medium">Feat</p>
            <p className="text-amber-400 text-sm">{feats.join(', ')}</p>
          </div>
        )}
        {allEquip.length > 0 && (
          <div className="md:col-span-2">
            <p className="text-gray-500 text-xs font-medium">Equipment</p>
            <p className="text-gray-300 text-sm">{allEquip.join('; ')}</p>
          </div>
        )}
      </div>
      {spells.length > 0 && (
        <div className="mt-3">
          <p className="text-gray-500 text-xs font-medium mb-1">Expanded Spell List</p>
          <div className="grid grid-cols-2 md:grid-cols-5 gap-2">
            {spells.map((levelMap, i) =>
              Object.entries(levelMap).map(([level, names]) => (
                <div key={`${i}-${level}`}>
                  <p className="text-gray-500 text-xs">{LEVEL_LABELS[level] ?? level} level</p>
                  <p className="text-purple-400 text-xs">{(names as string[]).join(', ')}</p>
                </div>
              ))
            )}
          </div>
        </div>
      )}
      {feature && (
        <div className="mt-3 pt-3 border-t border-gray-800">
          <p className="text-gray-300 text-sm font-medium">Feature: {feature.name}</p>
          <p className="text-gray-500 text-xs mt-1 line-clamp-4">{feature.description}</p>
        </div>
      )}
    </div>
  );
}

function ReviewField({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <p className="text-gray-500 text-xs">{label}</p>
      <p className="text-white text-sm font-medium">{value}</p>
    </div>
  );
}

function SpellSelectionStep({
  selectedClass, level,
  selectedCantrips, setSelectedCantrips,
  selectedSpells, setSelectedSpells,
  cantripResults, setCantripResults,
  spellResults, setSpellResults,
  cantripSearch, setCantripSearch,
  spellSearch, setSpellSearch,
}: {
  selectedClass: CharacterClassRef;
  level: number;
  selectedCantrips: Spell[];
  setSelectedCantrips: (s: Spell[]) => void;
  selectedSpells: Spell[];
  setSelectedSpells: (s: Spell[]) => void;
  cantripResults: Spell[];
  setCantripResults: (s: Spell[]) => void;
  spellResults: Spell[];
  setSpellResults: (s: Spell[]) => void;
  cantripSearch: string;
  setCantripSearch: (s: string) => void;
  spellSearch: string;
  setSpellSearch: (s: string) => void;
}) {
  const cantripsAllowed = CANTRIPS_KNOWN[selectedClass.name]?.[level] ?? 0;
  const spellsAllowed = selectedClass.isKnownCaster
    ? (SPELLS_KNOWN[selectedClass.name]?.[level] ?? 0)
    : 0;
  const isPrepared = selectedClass.isPreparedCaster;

  useEffect(() => {
    if (cantripsAllowed > 0 && cantripResults.length === 0) {
      searchSpells({ className: selectedClass.name, level: 0, size: 50 })
        .then(res => setCantripResults(res.content))
        .catch(() => {});
    }
    if ((spellsAllowed > 0 || isPrepared) && spellResults.length === 0) {
      searchSpells({ className: selectedClass.name, level: 1, size: 50 })
        .then(res => setSpellResults(res.content))
        .catch(() => {});
    }
  }, [selectedClass.name]);

  function searchCantrips() {
    const params: Record<string, unknown> = { className: selectedClass.name, level: 0, size: 50 };
    if (cantripSearch.trim()) params.name = cantripSearch.trim();
    searchSpells(params as any).then(res => setCantripResults(res.content)).catch(() => {});
  }

  function searchSpellsList() {
    const params: Record<string, unknown> = { className: selectedClass.name, level: 1, size: 50 };
    if (spellSearch.trim()) params.name = spellSearch.trim();
    searchSpells(params as any).then(res => setSpellResults(res.content)).catch(() => {});
  }

  function toggleCantrip(spell: Spell) {
    const exists = selectedCantrips.some(s => s.id === spell.id);
    if (exists) {
      setSelectedCantrips(selectedCantrips.filter(s => s.id !== spell.id));
    } else if (selectedCantrips.length < cantripsAllowed) {
      setSelectedCantrips([...selectedCantrips, spell]);
    }
  }

  function toggleSpell(spell: Spell) {
    const exists = selectedSpells.some(s => s.id === spell.id);
    if (exists) {
      setSelectedSpells(selectedSpells.filter(s => s.id !== spell.id));
    } else if (spellsAllowed === 0 || selectedSpells.length < spellsAllowed) {
      setSelectedSpells([...selectedSpells, spell]);
    }
  }

  return (
    <div className="space-y-6">
      <h2 className="text-xl font-semibold text-white">Choose Spells</h2>
      <p className="text-gray-400 text-sm">
        Select your starting {selectedClass.name} spells.
      </p>

      {/* Cantrip selection */}
      {cantripsAllowed > 0 && (
        <div className="bg-gray-900 border border-gray-800 rounded-lg p-4 space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="text-white font-semibold text-sm">Cantrips</h3>
            <span className="text-xs text-gray-400">{selectedCantrips.length}/{cantripsAllowed} selected</span>
          </div>

          <div className="flex gap-2">
            <div className="relative flex-1">
              <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-500" />
              <input
                value={cantripSearch}
                onChange={e => setCantripSearch(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && searchCantrips()}
                placeholder="Search cantrips..."
                className="w-full bg-gray-800 border border-gray-700 rounded-md pl-8 pr-3 py-1.5 text-sm text-white placeholder-gray-500 focus:outline-none focus:border-indigo-500"
              />
            </div>
            <button onClick={searchCantrips} className="bg-indigo-600 hover:bg-indigo-500 text-white text-xs px-3 rounded-md">Search</button>
          </div>

          {selectedCantrips.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {selectedCantrips.map(s => (
                <span key={s.id} className="flex items-center gap-1 bg-indigo-900/50 text-indigo-200 px-2 py-1 rounded text-xs">
                  {s.name}
                  <button onClick={() => toggleCantrip(s)} className="text-indigo-400 hover:text-white"><X className="w-3 h-3" /></button>
                </span>
              ))}
            </div>
          )}

          <div className="max-h-48 overflow-y-auto space-y-1">
            {cantripResults.map(spell => {
              const selected = selectedCantrips.some(s => s.id === spell.id);
              const disabled = !selected && selectedCantrips.length >= cantripsAllowed;
              return (
                <button
                  key={spell.id}
                  onClick={() => !disabled && toggleCantrip(spell)}
                  disabled={disabled}
                  className={`w-full flex items-center justify-between py-1.5 px-3 rounded-md text-sm transition-colors ${
                    selected ? 'bg-indigo-900/30 text-indigo-200' : disabled ? 'text-gray-600 cursor-not-allowed' : 'text-gray-300 hover:bg-gray-800'
                  }`}
                >
                  <span>{spell.name}</span>
                  {selected && <Check className="w-4 h-4 text-indigo-400" />}
                </button>
              );
            })}
          </div>
        </div>
      )}

      {/* Known spell selection (known casters only at creation) */}
      {spellsAllowed > 0 && (
        <div className="bg-gray-900 border border-gray-800 rounded-lg p-4 space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="text-white font-semibold text-sm">Level 1 Spells</h3>
            <span className="text-xs text-gray-400">{selectedSpells.length}/{spellsAllowed} selected</span>
          </div>

          <div className="flex gap-2">
            <div className="relative flex-1">
              <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-500" />
              <input
                value={spellSearch}
                onChange={e => setSpellSearch(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && searchSpellsList()}
                placeholder="Search level 1 spells..."
                className="w-full bg-gray-800 border border-gray-700 rounded-md pl-8 pr-3 py-1.5 text-sm text-white placeholder-gray-500 focus:outline-none focus:border-indigo-500"
              />
            </div>
            <button onClick={searchSpellsList} className="bg-indigo-600 hover:bg-indigo-500 text-white text-xs px-3 rounded-md">Search</button>
          </div>

          {selectedSpells.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {selectedSpells.map(s => (
                <span key={s.id} className="flex items-center gap-1 bg-indigo-900/50 text-indigo-200 px-2 py-1 rounded text-xs">
                  {s.name}
                  <button onClick={() => toggleSpell(s)} className="text-indigo-400 hover:text-white"><X className="w-3 h-3" /></button>
                </span>
              ))}
            </div>
          )}

          <div className="max-h-48 overflow-y-auto space-y-1">
            {spellResults.map(spell => {
              const selected = selectedSpells.some(s => s.id === spell.id);
              const disabled = !selected && selectedSpells.length >= spellsAllowed;
              return (
                <button
                  key={spell.id}
                  onClick={() => !disabled && toggleSpell(spell)}
                  disabled={disabled}
                  className={`w-full flex items-center justify-between py-1.5 px-3 rounded-md text-sm transition-colors ${
                    selected ? 'bg-indigo-900/30 text-indigo-200' : disabled ? 'text-gray-600 cursor-not-allowed' : 'text-gray-300 hover:bg-gray-800'
                  }`}
                >
                  <div className="flex items-center gap-2">
                    <span>{spell.name}</span>
                    <span className="text-xs text-gray-500">{spell.school}</span>
                  </div>
                  {selected && <Check className="w-4 h-4 text-indigo-400" />}
                </button>
              );
            })}
          </div>
        </div>
      )}

      {/* Prepared caster info */}
      {isPrepared && (
        <div className="bg-gray-900 border border-gray-800 rounded-lg p-4">
          <h3 className="text-white font-semibold text-sm mb-2">Prepared Spells</h3>
          <p className="text-gray-400 text-sm">
            As a {selectedClass.name}, you prepare spells each day from your class spell list.
            You can change your prepared spells from the character sheet after creation.
          </p>
        </div>
      )}
    </div>
  );
}
