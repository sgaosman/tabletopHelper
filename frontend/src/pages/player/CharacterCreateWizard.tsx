import { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, ArrowRight, Check, ChevronDown, ChevronLeft, Search, X } from 'lucide-react';
import { getRaces, getClasses, getSubclasses, getBackgrounds, getFeats, searchSpells } from '../../api/referenceApi';
import { characterApi } from '../../api/characterApi';
import type { PlayerCharacter } from '../../types/character';
import type { Race, CharacterClassRef, Subclass, Background, Feat, Spell } from '../../types/reference';
import AsiModal from '../../components/character/AsiModal';
import { CANTRIPS_KNOWN, SPELLS_KNOWN, THIRD_CASTER_CANTRIPS, THIRD_CASTER_SPELLS, THIRD_CASTER_SUBCLASSES, THIRD_CASTER_SPELL_LIST, THIRD_CASTER_ABILITY, maxSpellLevel, proficiencyBonusForLevel, wizardSpellbookCount } from '../../utils/spellConstants';
import { parseFeatOptions } from '../../utils/featSpellParser';
import type { ParsedFeatOption } from '../../utils/featSpellParser';
import { parseAbilityScoreIncrease } from '../../utils/featPrerequisites';
import { ABILITIES, ABILITY_FROM_ABBR, ALL_SKILLS, abilityMod, formatMod, safeJsonParse, ABILITY_ABBR as ABILITY_LABELS } from '../../utils/dndRules';
import {
  ALL_STEPS, ALIGNMENTS, STANDARD_ARRAY, ALL_LANGUAGES, ALL_TOOLS,
  getToolAnyOptions, expandToolFrom, formatProfEntry, formatProficiencies,
  isAsiLevel, countAsiLevels, checkMulticlassEligibility,
} from './wizard/constants';
import type { AbilityScores, ProfEntry, ClassEntry } from './wizard/constants';
import { StandardArrayAssigner, RaceDetail, BackgroundDetail, ReviewField } from './wizard/StepComponents';
import { FeatSpellSelectionStep, MulticlassSpellSelectionStep, ThirdCasterSpellSelectionStep, SpellSelectionStep } from './wizard/SpellSteps';

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
  const [level, setLevel] = useState(1);

  const [classEntries, setClassEntries] = useState<ClassEntry[]>([]);

  const [createdCharacter, setCreatedCharacter] = useState<PlayerCharacter | null>(null);
  const [pendingAsiCount, setPendingAsiCount] = useState(0);
  const [multiclassExpanded, setMulticlassExpanded] = useState(false);

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
  const [mcSpellSelections, setMcSpellSelections] = useState<Record<string, { cantrips: Spell[]; spells: Spell[] }>>({});
  const [selectedClassSkills, setSelectedClassSkills] = useState<string[]>([]);
  const [mcSkillSelections, setMcSkillSelections] = useState<Record<string, string[]>>({});
  const [selectedExpertise, setSelectedExpertise] = useState<string[]>([]);

  const [selectedBgFeat, setSelectedBgFeat] = useState<string | null>(null);
  const [selectedFeatOptionIdx, setSelectedFeatOptionIdx] = useState<number | null>(null);
  const [selectedFeatAbility, setSelectedFeatAbility] = useState<string | null>(null);
  const [selectedFeatAsiAbility, setSelectedFeatAsiAbility] = useState<string | null>(null);
  const [featCantrips, setFeatCantrips] = useState<Spell[]>([]);
  const [featSpells, setFeatSpells] = useState<Spell[]>([]);
  const [showDraftBanner, setShowDraftBanner] = useState(false);
  const draftRestoredRef = useRef(false);

  const DRAFT_KEY = 'characterWizardDraft';

  const hasMeaningfulData = name.trim().length > 0 || selectedRace !== null || selectedClass !== null;

  useEffect(() => {
    if (draftRestoredRef.current) return;
    try {
      const saved = localStorage.getItem(DRAFT_KEY);
      if (saved) {
        const draft = JSON.parse(saved);
        if (draft.name || draft.raceId || draft.classId) {
          setShowDraftBanner(true);
        }
      }
    } catch { /* ignore corrupt drafts */ }
  }, []);

  function restoreDraft() {
    try {
      const saved = localStorage.getItem(DRAFT_KEY);
      if (!saved) return;
      const d = JSON.parse(saved);
      draftRestoredRef.current = true;
      if (d.name) setName(d.name);
      if (d.alignment) setAlignment(d.alignment);
      if (d.step != null) setStep(d.step);
      if (d.abilityMethod) setAbilityMethod(d.abilityMethod);
      if (d.scores) setScores(d.scores);
      if (d.standardAssignments) setStandardAssignments(d.standardAssignments);
      if (d.level) setLevel(d.level);
      if (d.raceId && races.length > 0) {
        const r = races.find(r => r.id === d.raceId);
        if (r) setSelectedRace(r);
      }
      if (d.classId && classes.length > 0) {
        const c = classes.find(c => c.id === d.classId);
        if (c) setSelectedClass(c);
      }
      if (d.backgroundId && backgrounds.length > 0) {
        const bg = backgrounds.find(b => b.id === d.backgroundId);
        if (bg) setSelectedBackground(bg);
      }
    } catch { /* ignore */ }
    setShowDraftBanner(false);
  }

  function dismissDraft() {
    localStorage.removeItem(DRAFT_KEY);
    setShowDraftBanner(false);
  }

  useEffect(() => {
    if (!hasMeaningfulData) return;
    const draft = {
      name, alignment, step,
      abilityMethod, scores, standardAssignments, level,
      raceId: selectedRace?.id ?? null,
      classId: selectedClass?.id ?? null,
      backgroundId: selectedBackground?.id ?? null,
    };
    try { localStorage.setItem(DRAFT_KEY, JSON.stringify(draft)); } catch { /* quota */ }
  }, [name, alignment, step, abilityMethod, scores, standardAssignments, level, selectedRace, selectedClass, selectedBackground]);

  useEffect(() => {
    if (!hasMeaningfulData) return;
    function onBeforeUnload(e: BeforeUnloadEvent) {
      e.preventDefault();
    }
    window.addEventListener('beforeunload', onBeforeUnload);
    return () => window.removeEventListener('beforeunload', onBeforeUnload);
  }, [hasMeaningfulData]);

  const bgFeatNames = useMemo(() => {
    if (!selectedBackground?.feats) return [];
    return safeJsonParse<string[]>(selectedBackground.feats, []);
  }, [selectedBackground]);

  const selectedFeatObj = useMemo(() => {
    if (!selectedBgFeat) return null;
    return feats.find(f => f.name.toLowerCase() === selectedBgFeat.toLowerCase()) || null;
  }, [selectedBgFeat, feats]);

  const parsedFeatOptions = useMemo(() => {
    if (!selectedFeatObj) return [];
    return parseFeatOptions(selectedFeatObj.grantsFeatures);
  }, [selectedFeatObj]);

  const selectedFeatOption: ParsedFeatOption | null = selectedFeatOptionIdx != null ? parsedFeatOptions[selectedFeatOptionIdx] ?? null : null;

  const featAsi = useMemo(() => {
    if (!selectedFeatObj) return null;
    return parseAbilityScoreIncrease(selectedFeatObj);
  }, [selectedFeatObj]);

  useEffect(() => {
    if (bgFeatNames.length === 1) {
      setSelectedBgFeat(bgFeatNames[0]);
    } else {
      setSelectedBgFeat(null);
    }
    setSelectedFeatOptionIdx(null);
    setSelectedFeatAbility(null);
    setSelectedFeatAsiAbility(null);
    setFeatCantrips([]);
    setFeatSpells([]);
  }, [selectedBackground]);

  useEffect(() => {
    if (parsedFeatOptions.length === 1) {
      setSelectedFeatOptionIdx(0);
    } else if (parsedFeatOptions.length === 0) {
      setSelectedFeatOptionIdx(null);
    }
    setFeatCantrips([]);
    setFeatSpells([]);
  }, [parsedFeatOptions.length, selectedBgFeat]);

  useEffect(() => {
    if (selectedFeatOption?.ability) {
      setSelectedFeatAbility(selectedFeatOption.ability);
    } else if (selectedFeatOption?.abilityChoices) {
      setSelectedFeatAbility(null);
    }
    setFeatCantrips([]);
    setFeatSpells([]);
  }, [selectedFeatOptionIdx]);

  useEffect(() => {
    if (featAsi?.choose) {
      setSelectedFeatAsiAbility(null);
    } else if (featAsi?.fixed) {
      setSelectedFeatAsiAbility(null);
    }
  }, [selectedBgFeat]);

  const hasFeatSpellChoices = useMemo(() => {
    if (!selectedFeatOption) return false;
    return selectedFeatOption.cantripChoice != null || selectedFeatOption.spellChoice != null;
  }, [selectedFeatOption]);

  const featConfigComplete = useMemo(() => {
    if (bgFeatNames.length === 0) return true;
    if (!selectedBgFeat) return false;
    if (featAsi?.choose && !selectedFeatAsiAbility) return false;
    if (!selectedFeatObj?.grantsFeatures) return true;
    if (parsedFeatOptions.length > 0 && selectedFeatOptionIdx == null) return false;
    if (selectedFeatOption?.abilityChoices && !selectedFeatAbility) return false;
    return true;
  }, [bgFeatNames, selectedBgFeat, selectedFeatObj, parsedFeatOptions, selectedFeatOptionIdx, selectedFeatOption, selectedFeatAbility, featAsi, selectedFeatAsiAbility]);

  const classSkillChoices = useMemo(() => {
    if (!selectedClass?.skillChoices) return { from: [] as string[], count: 0 };
    return safeJsonParse<{ from: string[]; count: number }>(selectedClass.skillChoices, { from: [], count: 0 });
  }, [selectedClass]);

  const mcSkillChoicesMap = useMemo(() => {
    const map: Record<string, { from: string[]; count: number }> = {};
    if (classEntries.length <= 1) return map;
    for (let i = 1; i < classEntries.length; i++) {
      const entry = classEntries[i];
      if (!entry.cls.multiclassProficiencies) continue;
      const mcProfs = safeJsonParse<{ skills?: { from: string[]; count: number } }>(entry.cls.multiclassProficiencies, {});
      if (mcProfs.skills && mcProfs.skills.from.length > 0 && mcProfs.skills.count > 0) {
        map[entry.cls.id] = mcProfs.skills;
      }
    }
    return map;
  }, [classEntries]);

  const expertiseCount = useMemo(() => {
    let count = 0;
    for (const entry of classEntries) {
      const name = entry.cls.name;
      const lvl = entry.level;
      if (name === 'Rogue') {
        if (lvl >= 1) count += 2;
        if (lvl >= 6) count += 2;
      } else if (name === 'Bard') {
        if (lvl >= 3) count += 2;
        if (lvl >= 10) count += 2;
      }
    }
    return count;
  }, [classEntries]);

  const hasThirdCasterSubclass = classEntries.some(e =>
    e.subclass && THIRD_CASTER_SUBCLASSES.has(e.subclass.name) && e.level >= 3
  );
  const isSpellcaster = classEntries.some(e => e.cls.isSpellcaster) || (selectedClass?.isSpellcaster ?? false) || hasThirdCasterSubclass;
  const showSpellsStep = isSpellcaster || hasFeatSpellChoices;
  const steps = useMemo(() =>
    showSpellsStep ? ALL_STEPS : ALL_STEPS.filter(s => s !== 'Spells'),
    [showSpellsStep]
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
    setSelectedCantrips([]);
    setSelectedSpells([]);
    setCantripResults([]);
    setSpellResults([]);
    setSpellSearch('');
    setCantripSearch('');
  }, [selectedClass]);

  useEffect(() => {
    if (classEntries.length === 0) return;
    const currentTotal = classEntries.reduce((s, e) => s + e.level, 0);
    if (currentTotal === level) return;
    setClassEntries(prev => {
      const newPrimaryLevel = prev[0].level + (level - currentTotal);
      if (newPrimaryLevel >= 1) {
        return prev.map((e, i) => i === 0 ? { ...e, level: newPrimaryLevel } : e);
      }
      let remaining = level;
      const result: ClassEntry[] = [];
      for (const entry of prev) {
        if (remaining <= 0) break;
        const entryLevel = Math.min(entry.level, remaining);
        result.push({ ...entry, level: Math.max(1, entryLevel) });
        remaining -= Math.max(1, entryLevel);
      }
      if (result.length > 0) {
        const total = result.reduce((s, e) => s + e.level, 0);
        if (total < level) result[0] = { ...result[0], level: result[0].level + (level - total) };
      }
      return result;
    });
  }, [level]);

  const addMulticlass = useCallback(async (cls: CharacterClassRef) => {
    if (classEntries.some(e => e.cls.id === cls.id)) return;
    const scs = await getSubclasses(cls.id);
    setClassEntries(prev => {
      if (prev.some(e => e.cls.id === cls.id)) return prev;
      const updated = prev.map((e, i) => i === 0 ? { ...e, level: Math.max(1, e.level - 1) } : e);
      return [...updated, { cls, level: 1, subclass: null, subclasses: scs }];
    });
  }, [classEntries]);

  const removeMulticlass = useCallback((clsId: string) => {
    setClassEntries(prev => {
      const entry = prev.find(e => e.cls.id === clsId);
      if (!entry || prev[0].cls.id === clsId) return prev;
      const freedLevels = entry.level;
      return prev
        .filter(e => e.cls.id !== clsId)
        .map((e, i) => i === 0 ? { ...e, level: e.level + freedLevels } : e);
    });
    setMcSpellSelections(prev => {
      const next = { ...prev };
      delete next[clsId];
      return next;
    });
  }, []);

  const handleClassLevelChange = useCallback((clsId: string, newLevel: number) => {
    setClassEntries(prev => {
      const idx = prev.findIndex(e => e.cls.id === clsId);
      if (idx < 0) return prev;
      const otherTotal = prev.reduce((s, e, i) => i === idx ? s : s + e.level, 0);
      const maxForThis = level - otherTotal;
      const clamped = Math.max(1, Math.min(newLevel, maxForThis));
      return prev.map((e, i) => i === idx ? { ...e, level: clamped } : e);
    });
  }, [level]);

  const handleEntrySubclass = useCallback((clsId: string, sc: Subclass | null) => {
    setClassEntries(prev =>
      prev.map(e => e.cls.id === clsId ? { ...e, subclass: sc } : e)
    );
    if (classEntries.length > 0 && classEntries[0].cls.id === clsId) {
      setSelectedSubclass(sc);
    }
  }, [classEntries]);

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

  const raceSkills = useMemo(() => {
    if (!selectedRace) return new Set<string>();
    const profs = safeJsonParse<{ skills?: string[] }>(selectedRace.proficiencies, {});
    return new Set([
      ...(profs.skills ?? []).map(s => s.toLowerCase()),
      ...resolvedRaceChoices.skills.map(s => s.toLowerCase()),
    ]);
  }, [selectedRace, resolvedRaceChoices.skills]);

  const bgSkillConflicts = useMemo(() => {
    const allTaken = new Set([
      ...raceSkills,
      ...selectedClassSkills.map(s => s.toLowerCase()),
    ]);
    return resolvedBgProfs.skills.filter(s => allTaken.has(s.toLowerCase()));
  }, [raceSkills, selectedClassSkills, resolvedBgProfs.skills]);

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
    if (featAsi) {
      for (const [ability, bonus] of Object.entries(featAsi.fixed)) {
        const key = ABILITY_FROM_ABBR[ability] || ability.toLowerCase();
        if (key in result) {
          result[key as keyof AbilityScores] = Math.min(20, result[key as keyof AbilityScores] + bonus);
        }
      }
      if (featAsi.choose && selectedFeatAsiAbility) {
        const key = ABILITY_FROM_ABBR[selectedFeatAsiAbility] || selectedFeatAsiAbility.toLowerCase();
        if (key in result) {
          result[key as keyof AbilityScores] = Math.min(20, result[key as keyof AbilityScores] + featAsi.choose.amount);
        }
      }
    }
    return result;
  }, [scores, standardAssignments, abilityMethod, racialBonuses, featAsi, selectedFeatAsiAbility]);

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
      case 'Class': {
        if (!selectedClass) return false;
        const totalClassLevels = classEntries.reduce((s, e) => s + e.level, 0);
        if (totalClassLevels !== level) return false;
        for (const entry of classEntries) {
          if (entry.level >= (entry.cls.subclassLevel || 3) && entry.subclasses.length > 0 && !entry.subclass) {
            return false;
          }
        }
        if (classSkillChoices.count > 0 && selectedClassSkills.length < classSkillChoices.count) return false;
        return true;
      }
      case 'Ability Scores':
        if (abilityMethod === 'standard') {
          return Object.values(standardAssignments).every(v => v !== null);
        }
        if (abilityMethod === 'pointbuy') return pointBuyTotal <= 27;
        return true;
      case 'Background': return selectedBackground !== null && bgChoicesComplete && featConfigComplete;
      case 'Spells': return true;
      default: return true;
    }
  }

  function buildSpellsKnown(): string | undefined {
    const entries: Array<Record<string, unknown>> = [];

    if (selectedClass?.isSpellcaster) {
      const source = `class:${selectedClass.name}`;
      for (const s of selectedCantrips) {
        entries.push({ name: s.name, level: 0, source });
      }
      for (const s of selectedSpells) {
        const isWiz = selectedClass.name === 'Wizard';
        entries.push({
          name: s.name, level: s.level, source,
          ...(selectedClass.isPreparedCaster && !isWiz ? { prepared: true } : {}),
        });
      }
    }

    for (const entry of classEntries.slice(1)) {
      if (!entry.cls.isSpellcaster) continue;
      const sel = mcSpellSelections[entry.cls.id];
      if (!sel) continue;
      const source = `class:${entry.cls.name}`;
      for (const s of sel.cantrips) {
        entries.push({ name: s.name, level: 0, source });
      }
      for (const s of sel.spells) {
        const isWiz = entry.cls.name === 'Wizard';
        entries.push({
          name: s.name, level: s.level, source,
          ...(entry.cls.isPreparedCaster && !isWiz ? { prepared: true } : {}),
        });
      }
    }

    // 1/3 caster subclass spells (Eldritch Knight, Arcane Trickster)
    for (const entry of classEntries) {
      if (!entry.subclass || !THIRD_CASTER_SUBCLASSES.has(entry.subclass.name)) continue;
      const sel = mcSpellSelections[`third:${entry.cls.id}`];
      if (!sel) continue;
      const source = `class:${entry.cls.name}`;
      for (const s of sel.cantrips) {
        entries.push({ name: s.name, level: 0, source });
      }
      for (const s of sel.spells) {
        entries.push({ name: s.name, level: s.level, source });
      }
    }

    if (selectedRace?.additionalSpells) {
      const raceSpells = safeJsonParse<{
        fixedSpells?: Array<{ name: string; level: number; atWill?: boolean; usesPerLongRest?: number; unlocksAtLevel?: number }>;
      }>(selectedRace.additionalSpells, {});
      const raceSource = `race:${selectedRace.name}`;
      for (const s of raceSpells.fixedSpells ?? []) {
        if (!s.unlocksAtLevel || s.unlocksAtLevel <= level) {
          entries.push({
            name: s.name, level: s.level ?? 0, source: raceSource,
            ...(s.atWill ? { atWill: true } : {}),
            ...(s.usesPerLongRest ? { usesPerLongRest: s.usesPerLongRest } : {}),
            ...(s.unlocksAtLevel ? { unlocksAtLevel: s.unlocksAtLevel } : {}),
          });
        }
      }
    }

    if (selectedFeatObj && selectedFeatOption) {
      const featSource = `feat:${selectedFeatObj.name}`;
      for (const name of selectedFeatOption.fixedCantrips) {
        entries.push({ name, level: 0, source: featSource, atWill: true });
      }
      for (const s of featCantrips) {
        entries.push({ name: s.name, level: 0, source: featSource, atWill: true });
      }
      for (const { name, usesPerDay } of selectedFeatOption.fixedSpells) {
        entries.push({ name, level: 1, source: featSource, usesPerLongRest: usesPerDay });
      }
      for (const s of featSpells) {
        entries.push({
          name: s.name, level: s.level, source: featSource,
          usesPerLongRest: selectedFeatOption.spellChoice?.usesPerDay ?? 1,
        });
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
    const resistances = [...safeJsonParse<string[]>(selectedRace.resistances, []), ...resolvedRaceChoices.resistances];

    const raceProfs = safeJsonParse<{ skills?: string[]; languages?: string[]; tools?: string[]; weapons?: string[] }>(selectedRace.proficiencies, {});
    const isMulticlass = classEntries.length > 1;
    const classArmor = [...safeJsonParse<string[]>(selectedClass.armorProficiencies, [])];
    const classWeapons = [...safeJsonParse<string[]>(selectedClass.weaponProficiencies, [])];
    const classTools = [...safeJsonParse<string[]>(selectedClass.toolProficiencies, [])];

    for (let i = 1; i < classEntries.length; i++) {
      const mc = classEntries[i].cls;
      if (!mc.multiclassProficiencies) continue;
      const mcProfs = safeJsonParse<{ armor?: string[]; weapons?: string[]; tools?: string[]; skills?: unknown }>(mc.multiclassProficiencies, {});
      if (mcProfs.armor) classArmor.push(...mcProfs.armor);
      if (mcProfs.weapons) classWeapons.push(...mcProfs.weapons);
      if (mcProfs.tools) classTools.push(...mcProfs.tools);
    }

    const allSkills = [
      ...(raceProfs.skills ?? []),
      ...resolvedRaceChoices.skills,
      ...selectedClassSkills,
      ...resolvedBgProfs.skills,
      ...Object.values(mcSkillSelections).flat(),
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

    const featFeatures: Array<{ name: string; description: string; source: string }> = [];
    for (const featName of resolvedRaceChoices.feats) {
      featFeatures.push({ name: featName, description: 'Racial feat', source: selectedRace.name });
    }
    if (selectedFeatObj && selectedFeatOption) {
      const optionDesc = parsedFeatOptions.length > 1 ? ` (${selectedFeatOption.name})` : '';
      const abilityDesc = selectedFeatAbility ? ` Spellcasting ability: ${selectedFeatAbility}.` : '';
      featFeatures.push({
        name: selectedFeatObj.name,
        description: `Granted by ${selectedBackground.name} background.${optionDesc}${abilityDesc}`,
        source: selectedBackground.name,
      });
    } else if (selectedBgFeat) {
      const feat = feats.find(f => f.name.toLowerCase() === selectedBgFeat.toLowerCase());
      if (feat) {
        featFeatures.push({
          name: feat.name,
          description: `Granted by ${selectedBackground.name} background.`,
          source: selectedBackground.name,
        });
      }
    }

    const featSpellAbility = selectedFeatAbility ?? selectedFeatOption?.ability;
    let resolvedSpellAbility: string | undefined = selectedClass.spellcastingAbility ?? undefined;
    if (!resolvedSpellAbility) {
      const thirdCasterEntry = classEntries.find(e => e.subclass && THIRD_CASTER_SUBCLASSES.has(e.subclass.name));
      if (thirdCasterEntry) resolvedSpellAbility = THIRD_CASTER_ABILITY[thirdCasterEntry.subclass!.name];
    }
    if (!resolvedSpellAbility && featSpellAbility) resolvedSpellAbility = featSpellAbility;

    let spellSaveDc: number | undefined;
    let spellAttackBonus: number | undefined;
    if (resolvedSpellAbility && !selectedClass.isSpellcaster) {
      const profBonus = proficiencyBonusForLevel(level);
      const abilityKey = ABILITY_FROM_ABBR[resolvedSpellAbility] as keyof typeof finalScores | undefined;
      const abilityScore = abilityKey ? finalScores[abilityKey] : 10;
      const mod = abilityMod(abilityScore);
      spellSaveDc = 8 + profBonus + mod;
      spellAttackBonus = profBonus + mod;
    }

    const hitDiceMap: Record<string, { total: number; remaining: number; faces: number }> = {};
    for (const entry of classEntries) {
      hitDiceMap[entry.cls.name] = { total: entry.level, remaining: entry.level, faces: entry.cls.hitDice };
    }
    if (classEntries.length === 0) {
      hitDiceMap[selectedClass.name] = { total: level, remaining: level, faces: selectedClass.hitDice };
    }

    const multiclassClassEntries = isMulticlass
      ? JSON.stringify(classEntries.map(e => ({
          classId: e.cls.id,
          subclassId: e.subclass?.id ?? null,
          level: e.level,
        })))
      : undefined;

    try {
      const res = await characterApi.create({
        name: name.trim(),
        level,
        raceId: selectedRace.id,
        classId: selectedClass.id,
        subclassId: classEntries[0]?.subclass?.id ?? selectedSubclass?.id,
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
        ...(level === 1 && !isMulticlass ? { hpMax } : {}),
        speed: speed.walk ?? 30,
        savingThrowProficiencies: JSON.stringify(savingThrows),
        skillProficiencies: allSkills.length > 0 ? JSON.stringify([...new Set(allSkills)]) : undefined,
        skillExpertises: selectedExpertise.length > 0 ? JSON.stringify(selectedExpertise) : undefined,
        armorProficiencies: classArmor.length > 0 ? JSON.stringify([...new Set(classArmor)]) : undefined,
        weaponProficiencies: allWeapons.length > 0 ? JSON.stringify([...new Set(allWeapons)]) : undefined,
        toolProficiencies: allTools.length > 0 ? JSON.stringify([...new Set(allTools)]) : undefined,
        languageProficiencies: allLanguages.length > 0 ? JSON.stringify([...new Set(allLanguages)]) : undefined,
        damageResistances: resistances.length > 0 ? JSON.stringify(resistances) : undefined,
        spellcastingAbility: resolvedSpellAbility,
        spellSaveDc,
        spellAttackBonus,
        spellsKnown: buildSpellsKnown(),
        features: featFeatures.length > 0 ? JSON.stringify(featFeatures) : undefined,
        hitDiceMap: JSON.stringify(hitDiceMap),
        multiclassClassEntries,
      });
      localStorage.removeItem(DRAFT_KEY);
      const asiCount = countAsiLevels(classEntries);
      if (asiCount > 0) {
        setCreatedCharacter(res.data);
        setPendingAsiCount(asiCount);
      } else {
        navigate(`/player/characters/${res.data.id}`);
      }
    } catch (err: any) {
      setError(err.response?.data?.error || err.response?.data?.message || 'Failed to create character');
    } finally {
      setSubmitting(false);
    }
  }

  function handleAsiComplete(updated: PlayerCharacter) {
    if (pendingAsiCount <= 1) {
      navigate(`/player/characters/${updated.id}`);
    } else {
      setCreatedCharacter(updated);
      setPendingAsiCount(prev => prev - 1);
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

      {showDraftBanner && (
        <div className="max-w-3xl mx-auto px-6 pt-4">
          <div className="bg-indigo-900/40 border border-indigo-700 rounded-lg px-4 py-3 flex items-center justify-between">
            <span className="text-indigo-300 text-sm">You have an unsaved character draft. Restore it?</span>
            <div className="flex gap-2">
              <button onClick={dismissDraft} className="px-3 py-1 text-xs text-gray-400 hover:text-white transition-colors">Discard</button>
              <button onClick={restoreDraft} className="px-3 py-1 text-xs bg-indigo-600 text-white rounded hover:bg-indigo-500 transition-colors">Restore</button>
            </div>
          </div>
        </div>
      )}

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
            <div>
              <label className="block text-sm font-medium text-gray-300 mb-2">Starting Level</label>
              <div className="flex items-center gap-4 max-w-md">
                <input
                  type="range"
                  min={1}
                  max={20}
                  value={level}
                  onChange={e => setLevel(Number(e.target.value))}
                  className="flex-1 accent-indigo-500"
                />
                <span className="text-white font-bold text-lg w-8 text-center">{level}</span>
              </div>
              <p className="text-xs text-gray-500 mt-1">
                Proficiency bonus: +{proficiencyBonusForLevel(level)}
                {level > 1 && ` · HP, features, and spell slots auto-calculated`}
              </p>
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
            <p className="text-gray-400 text-sm">Select your primary class{level >= 2 ? '. You can optionally multiclass below.' : '.'}</p>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              {classes.map(cls => {
                const saves = safeJsonParse<string[]>(cls.savingThrowProficiencies, []);
                const isSelected = selectedClass?.id === cls.id;
                return (
                  <button
                    key={cls.id}
                    onClick={() => {
                      if (isSelected) {
                        setSelectedClass(null);
                        setSelectedSubclass(null);
                        setClassEntries([]);
                        setMcSpellSelections({});
                        setSelectedClassSkills([]);
                        setMcSkillSelections({});
                        setSelectedExpertise([]);
                      } else {
                        setSelectedClass(cls);
                        setSelectedSubclass(null);
                        setSelectedCantrips([]);
                        setSelectedSpells([]);
                        setCantripResults([]);
                        setSpellResults([]);
                        setMcSpellSelections({});
                        setSelectedClassSkills([]);
                        setMcSkillSelections({});
                        setSelectedExpertise([]);
                        getSubclasses(cls.id).then(scs => {
                          setSubclasses(scs);
                          setClassEntries([{ cls, level, subclass: null, subclasses: scs }]);
                        });
                      }
                    }}
                    className={`p-4 rounded-lg border text-left transition-colors ${
                      isSelected
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

            {/* Multiclass section */}
            {selectedClass && level >= 2 && (
              <div className="mt-6 bg-gray-900 border border-gray-800 rounded-lg overflow-hidden">
                <button
                  onClick={() => setMulticlassExpanded(prev => !prev)}
                  className="w-full flex items-center justify-between px-4 py-3 hover:bg-gray-800/50 transition-colors"
                >
                  <div className="flex items-center gap-2">
                    <h3 className="text-sm font-semibold text-gray-300">Multiclassing Options</h3>
                    {classEntries.length > 1 && (
                      <span className="text-xs text-emerald-400">({classEntries.length - 1} added)</span>
                    )}
                  </div>
                  <ChevronDown className={`w-4 h-4 text-gray-400 transition-transform ${multiclassExpanded ? 'rotate-180' : ''}`} />
                </button>
                {multiclassExpanded && (
                  <div className="px-4 pb-4 border-t border-gray-800">
                    <p className="text-gray-500 text-xs my-3">
                      PHB rule: you must meet the ability score prerequisites for both your current class and the new class.
                    </p>
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
                      {classes.filter(c => c.id !== selectedClass.id).map(cls => {
                        const entryEligibility = checkMulticlassEligibility(cls, finalScores);
                        const exitEligibility = checkMulticlassEligibility(selectedClass, finalScores);
                        const canMulticlass = entryEligibility.eligible && exitEligibility.eligible;
                        const isAdded = classEntries.some(e => e.cls.id === cls.id);
                        const primaryCanGive = classEntries.length > 0 && classEntries[0].level > 1;
                        const canAddMore = primaryCanGive || isAdded;
                        const saves = safeJsonParse<string[]>(cls.savingThrowProficiencies, []);

                        return (
                          <button
                            key={cls.id}
                            onClick={() => {
                              if (isAdded) {
                                removeMulticlass(cls.id);
                              } else if (canMulticlass && canAddMore) {
                                addMulticlass(cls);
                              }
                            }}
                            disabled={!canMulticlass && !isAdded}
                            className={`p-3 rounded-lg border text-left transition-colors ${
                              isAdded
                                ? 'bg-emerald-900/30 border-emerald-500'
                                : !canMulticlass
                                ? 'bg-gray-900/50 border-gray-800 opacity-50 cursor-not-allowed'
                                : 'bg-gray-900 border-gray-800 hover:border-gray-600 cursor-pointer'
                            }`}
                          >
                            <div className="flex items-start justify-between">
                              <h4 className="text-white font-medium text-sm">{cls.name}</h4>
                              <span className="text-gray-500 text-xs">d{cls.hitDice}</span>
                            </div>
                            {saves.length > 0 && <p className="text-cyan-400 text-xs">Saves: {saves.join(', ')}</p>}
                            {cls.isSpellcaster && <p className="text-purple-400 text-xs">Spellcaster ({cls.spellcastingAbility})</p>}
                            {!canMulticlass && (
                              <p className="text-red-400 text-xs mt-1">
                                {!exitEligibility.eligible ? `Exit: ${exitEligibility.reason}` : entryEligibility.reason}
                              </p>
                            )}
                            {isAdded && <p className="text-emerald-400 text-xs mt-1">Added to multiclass</p>}
                          </button>
                        );
                      })}
                    </div>
                  </div>
                )}
              </div>
            )}

            {/* Level allocation sliders */}
            {classEntries.length > 1 && (
              <div className="mt-4 bg-gray-900 border border-gray-800 rounded-lg p-4">
                <h3 className="text-lg font-semibold text-white mb-1">Level Allocation</h3>
                <p className="text-gray-500 text-xs mb-3">
                  Distribute your {level} character levels across classes.
                </p>
                <div className="space-y-3">
                  {classEntries.map(entry => {
                    const otherTotal = classEntries.reduce((s, e) => e.cls.id === entry.cls.id ? s : s + e.level, 0);
                    const maxForThis = level - otherTotal;
                    return (
                      <div key={entry.cls.id} className="flex items-center gap-3">
                        <span className="text-white text-sm font-medium w-24 shrink-0">{entry.cls.name}</span>
                        <input
                          type="range"
                          min={1}
                          max={Math.max(1, maxForThis)}
                          value={entry.level}
                          onChange={e => handleClassLevelChange(entry.cls.id, Number(e.target.value))}
                          className="flex-1 accent-indigo-500"
                        />
                        <span className="text-white font-bold text-sm w-6 text-center">{entry.level}</span>
                      </div>
                    );
                  })}
                </div>
                {(() => {
                  const total = classEntries.reduce((s, e) => s + e.level, 0);
                  return total !== level && (
                    <p className="text-red-400 text-xs mt-2">Total levels: {total}/{level} — must equal character level</p>
                  );
                })()}
              </div>
            )}

            {/* Per-class subclass selection */}
            {classEntries.map(entry => {
              const subclassLvl = entry.cls.subclassLevel || 3;
              const needsSubclass = entry.level >= subclassLvl && entry.subclasses.length > 0;
              const belowSubclass = entry.level < subclassLvl && entry.subclasses.length > 0;
              return (
                <div key={`sc-${entry.cls.id}`}>
                  {needsSubclass && (
                    <div className="mt-4">
                      <h3 className="text-lg font-semibold text-white mb-3">
                        {classEntries.length > 1 ? `${entry.cls.name} Subclass` : 'Choose a Subclass'}
                      </h3>
                      <div className="grid grid-cols-1 md:grid-cols-2 gap-3 max-h-60 overflow-y-auto">
                        {entry.subclasses.map(sc => (
                          <button
                            key={sc.id}
                            onClick={() => handleEntrySubclass(entry.cls.id, entry.subclass?.id === sc.id ? null : sc)}
                            className={`p-3 rounded-lg border text-left transition-colors ${
                              entry.subclass?.id === sc.id
                                ? 'bg-purple-900/30 border-purple-500'
                                : 'bg-gray-900 border-gray-800 hover:border-gray-600'
                            }`}
                          >
                            <h4 className="text-white text-sm font-medium">{sc.name}</h4>
                            <p className="text-gray-500 text-xs">{sc.source}</p>
                          </button>
                        ))}
                      </div>
                      {!entry.subclass && (
                        <p className="text-amber-400 text-sm mt-2">Subclass selection required at {entry.cls.name} level {subclassLvl}+</p>
                      )}
                    </div>
                  )}
                  {belowSubclass && classEntries.length <= 1 && (
                    <p className="text-gray-500 text-sm mt-2">Subclass available at level {subclassLvl}</p>
                  )}
                </div>
              );
            })}

            {/* ASI level preview */}
            {classEntries.length > 0 && (() => {
              const asiCount = countAsiLevels(classEntries);
              if (asiCount === 0) return null;
              const asiDetails = classEntries.flatMap(entry => {
                const levels: string[] = [];
                for (let lvl = 1; lvl <= entry.level; lvl++) {
                  if (isAsiLevel(entry.cls.name, lvl)) {
                    levels.push(classEntries.length > 1 ? `${entry.cls.name} ${lvl}` : `Level ${lvl}`);
                  }
                }
                return levels;
              });
              return (
                <div className="mt-4 bg-amber-900/20 border border-amber-800/50 rounded-lg p-4">
                  <h3 className="text-amber-400 font-medium text-sm">
                    {asiCount} Ability Score Improvement{asiCount > 1 ? 's' : ''}
                  </h3>
                  <p className="text-gray-400 text-xs mt-1">
                    ASI at: {asiDetails.join(', ')}. You'll choose ability increases or feats after creation.
                  </p>
                </div>
              );
            })()}

            {/* Class skill proficiency selection */}
            {selectedClass && classSkillChoices.count > 0 && (
              <div className="mt-4 bg-gray-900 border border-gray-800 rounded-lg p-4">
                <div className="flex items-center justify-between mb-1">
                  <h3 className="text-white font-medium text-sm">Class Skill Proficiencies</h3>
                  <span className="text-xs text-gray-400">{selectedClassSkills.length}/{classSkillChoices.count} selected</span>
                </div>
                <p className="text-gray-500 text-xs mb-3">
                  Choose {classSkillChoices.count} skill{classSkillChoices.count > 1 ? 's' : ''} from the {selectedClass.name} class list.
                </p>
                <div className="flex gap-1.5 flex-wrap">
                  {classSkillChoices.from.map(skill => {
                    const isSelected = selectedClassSkills.includes(skill);
                    const alreadyFromRace = raceSkills.has(skill.toLowerCase());
                    const disabled = alreadyFromRace || (!isSelected && selectedClassSkills.length >= classSkillChoices.count);
                    return (
                      <button
                        key={skill}
                        onClick={() => {
                          if (alreadyFromRace) return;
                          if (isSelected) {
                            setSelectedClassSkills(prev => prev.filter(s => s !== skill));
                          } else if (selectedClassSkills.length < classSkillChoices.count) {
                            setSelectedClassSkills(prev => [...prev, skill]);
                          }
                        }}
                        disabled={disabled && !isSelected}
                        className={`px-3 py-1.5 rounded text-xs font-medium transition-colors ${
                          alreadyFromRace
                            ? 'bg-gray-800/50 text-gray-600 cursor-not-allowed line-through'
                            : isSelected
                            ? 'bg-indigo-600 text-white'
                            : disabled
                            ? 'bg-gray-800 text-gray-600 cursor-not-allowed'
                            : 'bg-gray-800 text-gray-400 hover:bg-gray-700'
                        }`}
                        title={alreadyFromRace ? 'Already granted by race' : undefined}
                      >
                        {skill}
                        {alreadyFromRace && ' (race)'}
                      </button>
                    );
                  })}
                </div>
                {selectedClassSkills.length < classSkillChoices.count && (
                  <p className="text-amber-400 text-xs mt-3">Select {classSkillChoices.count - selectedClassSkills.length} more skill{classSkillChoices.count - selectedClassSkills.length > 1 ? 's' : ''} to continue</p>
                )}
              </div>
            )}

            {/* Multiclass skill choices */}
            {Object.entries(mcSkillChoicesMap).map(([clsId, choices]) => {
              const entry = classEntries.find(e => e.cls.id === clsId);
              if (!entry) return null;
              const selected = mcSkillSelections[clsId] ?? [];
              const allTaken = new Set([
                ...raceSkills,
                ...selectedClassSkills.map(s => s.toLowerCase()),
                ...Object.entries(mcSkillSelections).filter(([k]) => k !== clsId).flatMap(([, v]) => v.map(s => s.toLowerCase())),
              ]);
              return (
                <div key={clsId} className="mt-4 bg-gray-900 border border-gray-800 rounded-lg p-4">
                  <div className="flex items-center justify-between mb-1">
                    <h3 className="text-white font-medium text-sm">{entry.cls.name} Multiclass Skills</h3>
                    <span className="text-xs text-gray-400">{selected.length}/{choices.count} selected</span>
                  </div>
                  <p className="text-gray-500 text-xs mb-3">
                    Choose {choices.count} skill{choices.count > 1 ? 's' : ''} from the {entry.cls.name} multiclass list.
                  </p>
                  <div className="flex gap-1.5 flex-wrap">
                    {choices.from.map(skill => {
                      const isSelected = selected.includes(skill);
                      const alreadyTaken = allTaken.has(skill.toLowerCase());
                      const disabled = alreadyTaken || (!isSelected && selected.length >= choices.count);
                      return (
                        <button
                          key={skill}
                          onClick={() => {
                            if (alreadyTaken) return;
                            if (isSelected) {
                              setMcSkillSelections(prev => ({ ...prev, [clsId]: (prev[clsId] ?? []).filter(s => s !== skill) }));
                            } else if (selected.length < choices.count) {
                              setMcSkillSelections(prev => ({ ...prev, [clsId]: [...(prev[clsId] ?? []), skill] }));
                            }
                          }}
                          disabled={disabled && !isSelected}
                          className={`px-3 py-1.5 rounded text-xs font-medium transition-colors ${
                            alreadyTaken ? 'bg-gray-800/50 text-gray-600 cursor-not-allowed line-through'
                              : isSelected ? 'bg-indigo-600 text-white'
                              : disabled ? 'bg-gray-800 text-gray-600 cursor-not-allowed'
                              : 'bg-gray-800 text-gray-400 hover:bg-gray-700'
                          }`}
                          title={alreadyTaken ? 'Already proficient' : undefined}
                        >
                          {skill}
                          {alreadyTaken && ' (taken)'}
                        </button>
                      );
                    })}
                  </div>
                </div>
              );
            })}

            {/* Expertise picker */}
            {expertiseCount > 0 && (
              <div className="mt-4 bg-gray-900 border border-gray-800 rounded-lg p-4">
                <div className="flex items-center justify-between mb-1">
                  <h3 className="text-white font-medium text-sm">Expertise</h3>
                  <span className="text-xs text-gray-400">{selectedExpertise.length}/{expertiseCount} selected</span>
                </div>
                <p className="text-gray-500 text-xs mb-3">
                  Choose {expertiseCount} skill{expertiseCount > 1 ? 's' : ''} to gain expertise in (double proficiency bonus).
                </p>
                <div className="flex gap-1.5 flex-wrap">
                  {[...selectedClassSkills, ...resolvedBgProfs.skills, ...(safeJsonParse<{ skills?: string[] }>(selectedRace?.proficiencies, {}).skills ?? []), ...resolvedRaceChoices.skills, ...Object.values(mcSkillSelections).flat()]
                    .filter((s, i, a) => a.indexOf(s) === i)
                    .map(skill => {
                      const isSelected = selectedExpertise.includes(skill);
                      const disabled = !isSelected && selectedExpertise.length >= expertiseCount;
                      return (
                        <button
                          key={skill}
                          onClick={() => {
                            if (isSelected) {
                              setSelectedExpertise(prev => prev.filter(s => s !== skill));
                            } else if (selectedExpertise.length < expertiseCount) {
                              setSelectedExpertise(prev => [...prev, skill]);
                            }
                          }}
                          disabled={disabled}
                          className={`px-3 py-1.5 rounded text-xs font-medium transition-colors ${
                            isSelected ? 'bg-yellow-600 text-white'
                              : disabled ? 'bg-gray-800 text-gray-600 cursor-not-allowed'
                              : 'bg-gray-800 text-gray-400 hover:bg-gray-700'
                          }`}
                        >
                          {skill}
                        </button>
                      );
                    })}
                </div>
                {selectedExpertise.length < expertiseCount && (
                  <p className="text-amber-400 text-xs mt-3">Select {expertiseCount - selectedExpertise.length} more skill{expertiseCount - selectedExpertise.length > 1 ? 's' : ''}</p>
                )}
              </div>
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
                  onClick={() => {
                    setAbilityMethod(m);
                    if (m === 'pointbuy') {
                      setScores(s => {
                        const clamped = { ...s };
                        for (const a of ABILITIES) clamped[a] = Math.max(8, Math.min(15, s[a]));
                        return clamped;
                      });
                    }
                  }}
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
                {pointBuyTotal < 27 && pointBuyTotal > 0 && (
                  <p className="text-amber-400 text-xs mb-2">You have {27 - pointBuyTotal} unspent point{27 - pointBuyTotal !== 1 ? 's' : ''}. You can still proceed.</p>
                )}
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
            {selectedBackground && bgSkillConflicts.length > 0 && (
              <div className="bg-amber-900/20 border border-amber-800/50 rounded-lg p-4 mt-4">
                <h3 className="text-amber-400 font-medium text-sm">Skill Proficiency Overlap</h3>
                <p className="text-gray-400 text-xs mt-1">
                  The following background skill{bgSkillConflicts.length > 1 ? 's are' : ' is'} already granted by your race or class: <span className="text-amber-300 font-medium">{bgSkillConflicts.join(', ')}</span>.
                  Per PHB rules, you may choose a different skill proficiency instead. You can adjust this from the character sheet after creation.
                </p>
              </div>
            )}
            {selectedBackground && bgFeatNames.length > 0 && (
              <div className="bg-gray-900 border border-amber-800/50 rounded-lg p-4 mt-4 space-y-4">
                <h3 className="text-white font-medium">Background Feat</h3>

                {bgFeatNames.length > 1 ? (
                  <div>
                    <p className="text-gray-400 text-xs mb-2">Choose one feat granted by this background:</p>
                    <div className="flex gap-1.5 flex-wrap">
                      {bgFeatNames.map(fn => (
                        <button
                          key={fn}
                          onClick={() => {
                            setSelectedBgFeat(selectedBgFeat === fn ? null : fn);
                            setSelectedFeatOptionIdx(null);
                            setSelectedFeatAbility(null);
                            setSelectedFeatAsiAbility(null);
                            setFeatCantrips([]);
                            setFeatSpells([]);
                          }}
                          className={`px-3 py-1.5 rounded text-xs font-medium transition-colors ${
                            selectedBgFeat?.toLowerCase() === fn.toLowerCase()
                              ? 'bg-amber-600 text-white'
                              : 'bg-gray-800 text-gray-400 hover:bg-gray-700'
                          }`}
                        >
                          {fn}
                        </button>
                      ))}
                    </div>
                  </div>
                ) : (
                  <p className="text-amber-400 text-sm">
                    This background grants: <span className="font-semibold">{bgFeatNames[0]}</span>
                  </p>
                )}

                {selectedFeatObj && parsedFeatOptions.length > 1 && (
                  <div>
                    <p className="text-gray-400 text-xs mb-2">Choose an option for {selectedFeatObj.name}:</p>
                    <div className="grid grid-cols-2 md:grid-cols-3 gap-2 max-h-48 overflow-y-auto">
                      {parsedFeatOptions.map((opt, i) => (
                        <button
                          key={i}
                          onClick={() => setSelectedFeatOptionIdx(selectedFeatOptionIdx === i ? null : i)}
                          className={`px-3 py-2 rounded border text-xs text-left transition-colors ${
                            selectedFeatOptionIdx === i
                              ? 'bg-amber-900/30 border-amber-500 text-white'
                              : 'bg-gray-800 border-gray-700 text-gray-400 hover:border-gray-500'
                          }`}
                        >
                          <span className="font-medium">{opt.name}</span>
                          {opt.fixedCantrips.length > 0 && (
                            <span className="block text-gray-500 mt-0.5">{opt.fixedCantrips.join(', ')}</span>
                          )}
                        </button>
                      ))}
                    </div>
                  </div>
                )}

                {selectedFeatOption?.abilityChoices && (
                  <div>
                    <p className="text-gray-400 text-xs mb-2">Spellcasting Ability:</p>
                    <div className="flex gap-1.5">
                      {selectedFeatOption.abilityChoices.map(a => (
                        <button
                          key={a}
                          onClick={() => setSelectedFeatAbility(selectedFeatAbility === a ? null : a)}
                          className={`px-3 py-1.5 rounded text-xs font-medium transition-colors ${
                            selectedFeatAbility === a
                              ? 'bg-indigo-600 text-white'
                              : 'bg-gray-800 text-gray-400 hover:bg-gray-700'
                          }`}
                        >
                          {a}
                        </button>
                      ))}
                    </div>
                  </div>
                )}

                {selectedFeatObj && featAsi?.choose && (
                  <div>
                    <p className="text-gray-400 text-xs mb-2">
                      Ability Score Increase (+{featAsi.choose.amount}):
                    </p>
                    <div className="flex gap-1.5">
                      {featAsi.choose.from.map(a => (
                        <button
                          key={a}
                          onClick={() => setSelectedFeatAsiAbility(selectedFeatAsiAbility === a ? null : a)}
                          className={`px-3 py-1.5 rounded text-xs font-medium transition-colors ${
                            selectedFeatAsiAbility === a
                              ? 'bg-green-600 text-white'
                              : 'bg-gray-800 text-gray-400 hover:bg-gray-700'
                          }`}
                        >
                          {a}
                        </button>
                      ))}
                    </div>
                  </div>
                )}

                {selectedFeatObj && featAsi && Object.keys(featAsi.fixed).length > 0 && (
                  <div className="text-xs text-green-400">
                    {Object.entries(featAsi.fixed).map(([k, v]) => `+${v} ${k}`).join(', ')}
                  </div>
                )}

                {selectedFeatOption && (
                  <div className="text-xs text-gray-500 space-y-0.5">
                    {selectedFeatOption.fixedCantrips.length > 0 && (
                      <p>Cantrips: {selectedFeatOption.fixedCantrips.join(', ')}</p>
                    )}
                    {selectedFeatOption.cantripChoice && (
                      <p>Choose {selectedFeatOption.cantripChoice.count} cantrip{selectedFeatOption.cantripChoice.count > 1 ? 's' : ''} from {selectedFeatOption.cantripChoice.classes.join('/')}</p>
                    )}
                    {selectedFeatOption.fixedSpells.length > 0 && (
                      <p>Spells: {selectedFeatOption.fixedSpells.map(s => `${s.name} (${s.usesPerDay}/day)`).join(', ')}</p>
                    )}
                    {selectedFeatOption.spellChoice && (
                      <p>
                        Choose {selectedFeatOption.spellChoice.count} spell{selectedFeatOption.spellChoice.count > 1 ? 's' : ''}
                        {selectedFeatOption.spellChoice.fromList
                          ? ' from a list'
                          : ` from ${selectedFeatOption.spellChoice.classes.join('/')}`}
                        {' '}({selectedFeatOption.spellChoice.usesPerDay}/day)
                      </p>
                    )}
                    {selectedFeatOption.ability && !selectedFeatOption.abilityChoices && (
                      <p>Spellcasting Ability: {selectedFeatOption.ability}</p>
                    )}
                  </div>
                )}

                {!featConfigComplete && (
                  <p className="text-amber-400 text-xs">Complete all feat selections to continue</p>
                )}
              </div>
            )}
          </div>
        )}

        {/* Spells */}
        {currentStepName === 'Spells' && (
          <div className="space-y-8">
            {selectedClass?.isSpellcaster && (() => {
              const classLevel = classEntries.length > 0 ? classEntries[0].level : level;
              const maxLvl = maxSpellLevel(selectedClass.name, classLevel);
              const cantrips = CANTRIPS_KNOWN[selectedClass.name]?.[classLevel] ?? 0;
              if (maxLvl === 0 && cantrips === 0) {
                return (
                  <div className="bg-gray-900 border border-gray-800 rounded-lg p-4">
                    <h2 className="text-xl font-semibold text-white mb-2">
                      {classEntries.length > 1 ? `${selectedClass.name} Spells` : 'Spells'}
                    </h2>
                    <p className="text-gray-400 text-sm">
                      {selectedClass.name} does not gain spellcasting until a higher level. No spell selection is needed at level {classLevel}.
                    </p>
                  </div>
                );
              }
              return (
                <SpellSelectionStep
                  selectedClass={selectedClass}
                  level={classLevel}
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
                  title={classEntries.length > 1 ? `${selectedClass.name} Spells` : undefined}
                />
              );
            })()}
            {classEntries.slice(1).filter(e => e.cls.isSpellcaster).map(entry => {
              const maxLvl = maxSpellLevel(entry.cls.name, entry.level);
              const cantrips = CANTRIPS_KNOWN[entry.cls.name]?.[entry.level] ?? 0;
              if (maxLvl === 0 && cantrips === 0) {
                return (
                  <div key={entry.cls.id} className="bg-gray-900 border border-gray-800 rounded-lg p-4">
                    <h3 className="text-white font-semibold text-sm mb-2">{entry.cls.name} Spells</h3>
                    <p className="text-gray-400 text-sm">
                      {entry.cls.name} does not gain spellcasting until a higher level. No spell selection is needed at level {entry.level}.
                    </p>
                  </div>
                );
              }
              return (
                <MulticlassSpellSelectionStep
                  key={entry.cls.id}
                  classEntry={entry}
                  selectedCantrips={mcSpellSelections[entry.cls.id]?.cantrips ?? []}
                  onCantripsChange={cantrips => setMcSpellSelections(prev => ({
                    ...prev,
                    [entry.cls.id]: { cantrips, spells: prev[entry.cls.id]?.spells ?? [] },
                  }))}
                  selectedSpells={mcSpellSelections[entry.cls.id]?.spells ?? []}
                  onSpellsChange={spells => setMcSpellSelections(prev => ({
                    ...prev,
                    [entry.cls.id]: { cantrips: prev[entry.cls.id]?.cantrips ?? [], spells },
                  }))}
                />
              );
            })}
            {/* 1/3 caster subclass spell selection (Eldritch Knight, Arcane Trickster) */}
            {classEntries.filter(e => e.subclass && THIRD_CASTER_SUBCLASSES.has(e.subclass.name) && e.level >= 3).map(entry => (
              <ThirdCasterSpellSelectionStep
                key={`third-${entry.cls.id}`}
                classEntry={entry}
                selectedCantrips={mcSpellSelections[`third:${entry.cls.id}`]?.cantrips ?? []}
                onCantripsChange={cantrips => setMcSpellSelections(prev => ({
                  ...prev,
                  [`third:${entry.cls.id}`]: { cantrips, spells: prev[`third:${entry.cls.id}`]?.spells ?? [] },
                }))}
                selectedSpells={mcSpellSelections[`third:${entry.cls.id}`]?.spells ?? []}
                onSpellsChange={spells => setMcSpellSelections(prev => ({
                  ...prev,
                  [`third:${entry.cls.id}`]: { cantrips: prev[`third:${entry.cls.id}`]?.cantrips ?? [], spells },
                }))}
              />
            ))}
            {selectedFeatObj && selectedFeatOption && hasFeatSpellChoices && (
              <FeatSpellSelectionStep
                featName={selectedFeatObj.name}
                option={selectedFeatOption}
                featCantrips={featCantrips}
                setFeatCantrips={setFeatCantrips}
                featSpells={featSpells}
                setFeatSpells={setFeatSpells}
              />
            )}
            {/* Spell selection warning */}
            {(() => {
              const warnings: string[] = [];
              if (selectedClass?.isSpellcaster) {
                const classLevel = classEntries.length > 0 ? classEntries[0].level : level;
                const cantripsAllowed = CANTRIPS_KNOWN[selectedClass.name]?.[classLevel] ?? 0;
                const isWiz = selectedClass.name === 'Wizard';
                const spellsAllowed = isWiz ? wizardSpellbookCount(classLevel)
                  : selectedClass.isKnownCaster ? (SPELLS_KNOWN[selectedClass.name]?.[classLevel] ?? 0) : 0;
                if (cantripsAllowed > 0 && selectedCantrips.length < cantripsAllowed) {
                  warnings.push(`${selectedClass.name}: ${selectedCantrips.length}/${cantripsAllowed} cantrips selected`);
                }
                if (spellsAllowed > 0 && selectedSpells.length < spellsAllowed) {
                  warnings.push(`${selectedClass.name}: ${selectedSpells.length}/${spellsAllowed} spells selected`);
                }
              }
              for (const entry of classEntries) {
                if (entry.cls.id === selectedClass?.id) continue;
                if (!entry.cls.isSpellcaster) {
                  if (entry.subclass && THIRD_CASTER_SUBCLASSES.has(entry.subclass.name) && entry.level >= 3) {
                    const sel = mcSpellSelections[`third:${entry.cls.id}`];
                    const cantripsAllowed = THIRD_CASTER_CANTRIPS[entry.subclass.name]?.[entry.level] ?? 0;
                    const spellsAllowed = THIRD_CASTER_SPELLS[entry.subclass.name]?.[entry.level] ?? 0;
                    const cantripCount = sel?.cantrips?.length ?? 0;
                    const spellCount = sel?.spells?.length ?? 0;
                    if (cantripsAllowed > 0 && cantripCount < cantripsAllowed) warnings.push(`${entry.subclass.name}: ${cantripCount}/${cantripsAllowed} cantrips`);
                    if (spellsAllowed > 0 && spellCount < spellsAllowed) warnings.push(`${entry.subclass.name}: ${spellCount}/${spellsAllowed} spells`);
                  }
                  continue;
                }
                const sel = mcSpellSelections[entry.cls.id];
                const cantripsAllowed = CANTRIPS_KNOWN[entry.cls.name]?.[entry.level] ?? 0;
                const isWiz = entry.cls.name === 'Wizard';
                const spellsAllowed = isWiz ? wizardSpellbookCount(entry.level)
                  : entry.cls.isKnownCaster ? (SPELLS_KNOWN[entry.cls.name]?.[entry.level] ?? 0) : 0;
                const cantripCount = sel?.cantrips?.length ?? 0;
                const spellCount = sel?.spells?.length ?? 0;
                if (cantripsAllowed > 0 && cantripCount < cantripsAllowed) warnings.push(`${entry.cls.name}: ${cantripCount}/${cantripsAllowed} cantrips`);
                if (spellsAllowed > 0 && spellCount < spellsAllowed) warnings.push(`${entry.cls.name}: ${spellCount}/${spellsAllowed} spells`);
              }
              if (classEntries.length > 0 && classEntries[0].subclass && THIRD_CASTER_SUBCLASSES.has(classEntries[0].subclass.name) && classEntries[0].level >= 3) {
                const sel = mcSpellSelections[`third:${classEntries[0].cls.id}`];
                const scName = classEntries[0].subclass.name;
                const cantripsAllowed = THIRD_CASTER_CANTRIPS[scName]?.[classEntries[0].level] ?? 0;
                const spellsAllowed = THIRD_CASTER_SPELLS[scName]?.[classEntries[0].level] ?? 0;
                const cantripCount = sel?.cantrips?.length ?? 0;
                const spellCount = sel?.spells?.length ?? 0;
                if (cantripsAllowed > 0 && cantripCount < cantripsAllowed) warnings.push(`${scName}: ${cantripCount}/${cantripsAllowed} cantrips`);
                if (spellsAllowed > 0 && spellCount < spellsAllowed) warnings.push(`${scName}: ${spellCount}/${spellsAllowed} spells`);
              }
              if (warnings.length === 0) return null;
              return (
                <div className="bg-amber-900/20 border border-amber-800/50 rounded-lg p-3">
                  <p className="text-amber-400 text-xs">
                    You haven't selected all available spells. You can still proceed, but your character may be underpowered.
                  </p>
                  <ul className="text-amber-300 text-xs mt-1 list-disc list-inside">
                    {warnings.map(w => <li key={w}>{w}</li>)}
                  </ul>
                </div>
              );
            })()}
          </div>
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
                <ReviewField label="Class" value={
                  classEntries.length > 1
                    ? classEntries.map(e => `${e.cls.name} ${e.level}`).join(' / ')
                    : selectedClass?.name || '—'
                } />
                <ReviewField label="Level" value={String(level)} />
                {classEntries.filter(e => e.subclass).map(e => (
                  <ReviewField key={e.cls.id} label={classEntries.length > 1 ? `${e.cls.name} Subclass` : 'Subclass'} value={e.subclass!.name} />
                ))}
                <ReviewField label="Background" value={selectedBackground?.name || '—'} />
                <ReviewField label="Proficiency Bonus" value={`+${proficiencyBonusForLevel(level)}`} />
                <ReviewField label="Hit Points" value={level === 1 && classEntries.length <= 1 ? String((selectedClass?.hitDice || 0) + abilityMod(finalScores.constitution)) : `Calculated by server for level ${level}`} />
                <ReviewField label="Speed" value={(() => {
                  const sp = safeJsonParse<Record<string, number | boolean>>(selectedRace?.speed ?? null, { walk: 30 });
                  const w = typeof sp.walk === 'number' ? sp.walk : 30;
                  const extras = (['fly', 'swim', 'climb', 'burrow'] as const)
                    .filter(k => sp[k] !== undefined)
                    .map(k => `${k} ${sp[k] === true ? w : sp[k]} ft`);
                  return [`${w} ft`, ...extras].join(', ');
                })()} />
              </div>

              {selectedClassSkills.length > 0 && (
                <div className="border-t border-gray-800 pt-4">
                  <h3 className="text-sm font-medium text-gray-400 mb-3">Class Skills</h3>
                  <div className="grid grid-cols-2 gap-4">
                    <ReviewField label="Skill Proficiencies" value={selectedClassSkills.join(', ')} />
                  </div>
                </div>
              )}

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

              {selectedBgFeat && (
                <div className="border-t border-gray-800 pt-4">
                  <h3 className="text-sm font-medium text-gray-400 mb-3">Feat</h3>
                  <div className="grid grid-cols-2 gap-4">
                    <ReviewField label="Feat" value={selectedBgFeat} />
                    {selectedFeatOption && parsedFeatOptions.length > 1 && (
                      <ReviewField label="Option" value={selectedFeatOption.name} />
                    )}
                    {selectedFeatAbility && (
                      <ReviewField label="Spellcasting Ability" value={selectedFeatAbility} />
                    )}
                    {selectedFeatAsiAbility && featAsi?.choose && (
                      <ReviewField label="Ability Increase" value={`+${featAsi.choose.amount} ${selectedFeatAsiAbility}`} />
                    )}
                    {featAsi && Object.keys(featAsi.fixed).length > 0 && (
                      <ReviewField label="Ability Increase" value={Object.entries(featAsi.fixed).map(([k, v]) => `+${v} ${k}`).join(', ')} />
                    )}
                    {(selectedFeatOption?.fixedCantrips.length ?? 0) > 0 && (
                      <ReviewField label="Feat Cantrips" value={selectedFeatOption!.fixedCantrips.join(', ')} />
                    )}
                    {featCantrips.length > 0 && (
                      <ReviewField label="Chosen Cantrips" value={featCantrips.map(s => s.name).join(', ')} />
                    )}
                    {featSpells.length > 0 && (
                      <ReviewField label="Feat Spells" value={featSpells.map(s => s.name).join(', ')} />
                    )}
                    {(selectedFeatOption?.fixedSpells.length ?? 0) > 0 && (
                      <ReviewField label="Granted Spells" value={selectedFeatOption!.fixedSpells.map(s => s.name).join(', ')} />
                    )}
                  </div>
                </div>
              )}

              {/* Selected Spells */}
              {(() => {
                const spellGroups: Array<{ label: string; spells: string[] }> = [];
                if (selectedCantrips.length > 0) {
                  spellGroups.push({ label: `${selectedClass?.name ?? 'Class'} Cantrips`, spells: selectedCantrips.map(s => s.name) });
                }
                if (selectedSpells.length > 0) {
                  spellGroups.push({ label: `${selectedClass?.name ?? 'Class'} Spells`, spells: selectedSpells.map(s => s.name) });
                }
                for (const entry of classEntries) {
                  if (entry.cls.id === selectedClass?.id) continue;
                  const sel = mcSpellSelections[entry.cls.id];
                  if (sel?.cantrips?.length) spellGroups.push({ label: `${entry.cls.name} Cantrips`, spells: sel.cantrips.map(s => s.name) });
                  if (sel?.spells?.length) spellGroups.push({ label: `${entry.cls.name} Spells`, spells: sel.spells.map(s => s.name) });
                }
                for (const entry of classEntries) {
                  if (!entry.subclass || !THIRD_CASTER_SUBCLASSES.has(entry.subclass.name)) continue;
                  const sel = mcSpellSelections[`third:${entry.cls.id}`];
                  if (sel?.cantrips?.length) spellGroups.push({ label: `${entry.subclass.name} Cantrips`, spells: sel.cantrips.map(s => s.name) });
                  if (sel?.spells?.length) spellGroups.push({ label: `${entry.subclass.name} Spells`, spells: sel.spells.map(s => s.name) });
                }
                if (spellGroups.length === 0) return null;
                return (
                  <div className="border-t border-gray-800 pt-4">
                    <h3 className="text-sm font-medium text-gray-400 mb-3">Selected Spells</h3>
                    <div className="space-y-2">
                      {spellGroups.map(g => (
                        <div key={g.label}>
                          <p className="text-gray-500 text-xs">{g.label}</p>
                          <p className="text-white text-sm">{g.spells.join(', ')}</p>
                        </div>
                      ))}
                    </div>
                  </div>
                );
              })()}

              {/* Always-prepared subclass spells */}
              {(() => {
                const groups: Array<{ scName: string; spells: string[] }> = [];
                for (const entry of classEntries) {
                  if (!entry.subclass?.alwaysPreparedSpells) continue;
                  try {
                    const parsed = JSON.parse(entry.subclass.alwaysPreparedSpells) as Record<string, string[]>;
                    const unlocked: string[] = [];
                    for (const [lvlKey, spells] of Object.entries(parsed)) {
                      if (parseInt(lvlKey) <= entry.level) unlocked.push(...spells);
                    }
                    if (unlocked.length > 0) groups.push({ scName: entry.subclass.name, spells: unlocked });
                  } catch { /* ignore parse errors */ }
                }
                if (groups.length === 0) return null;
                return (
                  <div className="border-t border-gray-800 pt-4">
                    <h3 className="text-sm font-medium text-gray-400 mb-3">Always Prepared (Subclass)</h3>
                    <div className="space-y-2">
                      {groups.map(g => (
                        <div key={g.scName}>
                          <p className="text-gray-500 text-xs">{g.scName}</p>
                          <p className="text-amber-300 text-sm">{g.spells.join(', ')}</p>
                        </div>
                      ))}
                    </div>
                  </div>
                );
              })()}

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

      {createdCharacter && pendingAsiCount > 0 && (
        <AsiModal
          character={createdCharacter}
          onComplete={handleAsiComplete}
          onClose={() => navigate(`/player/characters/${createdCharacter.id}`)}
        />
      )}
    </div>
  );
}
