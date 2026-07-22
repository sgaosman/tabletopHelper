import { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, ArrowRight, Check, ChevronLeft } from 'lucide-react';
import { getRaces, getClasses, getSubclasses, getBackgrounds, getFeats } from '../../api/referenceApi';
import { characterApi } from '../../api/characterApi';
import type { PlayerCharacter } from '../../types/character';
import type { Race, CharacterClassRef, Subclass, Background, Feat, Spell } from '../../types/reference';
import AsiModal from '../../components/character/AsiModal';
import { THIRD_CASTER_SUBCLASSES, THIRD_CASTER_ABILITY, proficiencyBonusForLevel } from '../../utils/spellConstants';
import { parseFeatOptions } from '../../utils/featSpellParser';
import type { ParsedFeatOption } from '../../utils/featSpellParser';
import { parseAbilityScoreIncrease } from '../../utils/featPrerequisites';
import { ABILITIES, ABILITY_FROM_ABBR, ALL_SKILLS, abilityMod, safeJsonParse, ABILITY_ABBR as ABILITY_LABELS } from '../../utils/dndRules';

import {
  ALL_STEPS, ALIGNMENTS, ALL_LANGUAGES, ALL_TOOLS, ARTISANS_TOOLS, MUSICAL_INSTRUMENTS,
  getToolAnyOptions, expandToolFrom, formatProfEntry, countAsiLevels,
} from './wizard/types';
import type { AbilityScores, ProfEntry, ClassEntry, RaceChoiceReq, ChoiceReq } from './wizard/types';

import BasicInfoStep from './wizard/BasicInfoStep';
import RaceStep from './wizard/RaceStep';
import AbilityScoresStep from './wizard/AbilityScoresStep';
import ClassStep from './wizard/ClassStep';
import BackgroundStep from './wizard/BackgroundStep';
import SpellsStep from './wizard/SpellsStep';
import ReviewStep from './wizard/ReviewStep';

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

  // ── Draft persistence ──

  useEffect(() => {
    if (draftRestoredRef.current) return;
    try {
      const saved = localStorage.getItem(DRAFT_KEY);
      if (saved) {
        const draft = JSON.parse(saved);
        if (draft.name || draft.raceId || draft.classId) setShowDraftBanner(true);
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
      name, alignment, step, abilityMethod, scores, standardAssignments, level,
      raceId: selectedRace?.id ?? null,
      classId: selectedClass?.id ?? null,
      backgroundId: selectedBackground?.id ?? null,
    };
    try { localStorage.setItem(DRAFT_KEY, JSON.stringify(draft)); } catch { /* quota */ }
  }, [name, alignment, step, abilityMethod, scores, standardAssignments, level, selectedRace, selectedClass, selectedBackground]);

  useEffect(() => {
    if (!hasMeaningfulData) return;
    function onBeforeUnload(e: BeforeUnloadEvent) { e.preventDefault(); }
    window.addEventListener('beforeunload', onBeforeUnload);
    return () => window.removeEventListener('beforeunload', onBeforeUnload);
  }, [hasMeaningfulData]);

  // ── Feat configuration ──

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
    if (bgFeatNames.length === 1) setSelectedBgFeat(bgFeatNames[0]);
    else setSelectedBgFeat(null);
    setSelectedFeatOptionIdx(null);
    setSelectedFeatAbility(null);
    setSelectedFeatAsiAbility(null);
    setFeatCantrips([]);
    setFeatSpells([]);
  }, [selectedBackground]);

  useEffect(() => {
    if (parsedFeatOptions.length === 1) setSelectedFeatOptionIdx(0);
    else if (parsedFeatOptions.length === 0) setSelectedFeatOptionIdx(null);
    setFeatCantrips([]);
    setFeatSpells([]);
  }, [parsedFeatOptions.length, selectedBgFeat]);

  useEffect(() => {
    if (selectedFeatOption?.ability) setSelectedFeatAbility(selectedFeatOption.ability);
    else if (selectedFeatOption?.abilityChoices) setSelectedFeatAbility(null);
    setFeatCantrips([]);
    setFeatSpells([]);
  }, [selectedFeatOptionIdx]);

  useEffect(() => {
    if (featAsi?.choose) setSelectedFeatAsiAbility(null);
    else if (featAsi?.fixed) setSelectedFeatAsiAbility(null);
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

  // ── Class / skill / expertise ──

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
      if (mcProfs.skills && mcProfs.skills.from.length > 0 && mcProfs.skills.count > 0) map[entry.cls.id] = mcProfs.skills;
    }
    return map;
  }, [classEntries]);

  const expertiseCount = useMemo(() => {
    let count = 0;
    for (const entry of classEntries) {
      const n = entry.cls.name, lvl = entry.level;
      if (n === 'Rogue') { if (lvl >= 1) count += 2; if (lvl >= 6) count += 2; }
      else if (n === 'Bard') { if (lvl >= 3) count += 2; if (lvl >= 10) count += 2; }
    }
    return count;
  }, [classEntries]);

  // ── Spellcaster / step visibility ──

  const hasThirdCasterSubclass = classEntries.some(e => e.subclass && THIRD_CASTER_SUBCLASSES.has(e.subclass.name) && e.level >= 3);
  const isSpellcaster = classEntries.some(e => e.cls.isSpellcaster) || (selectedClass?.isSpellcaster ?? false) || hasThirdCasterSubclass;
  const showSpellsStep = isSpellcaster || hasFeatSpellChoices;
  const steps = useMemo(() => showSpellsStep ? ALL_STEPS : ALL_STEPS.filter(s => s !== 'Spells'), [showSpellsStep]);

  // ── Data loading ──

  useEffect(() => {
    getRaces().then(res => setRaces(res));
    getClasses().then(res => setClasses(res));
    getBackgrounds().then(res => setBackgrounds(res));
    getFeats().then(res => setFeats(res));
  }, []);

  useEffect(() => {
    if (selectedClass) getSubclasses(selectedClass.id).then(res => setSubclasses(res));
    else { setSubclasses([]); setSelectedSubclass(null); }
    setSelectedCantrips([]); setSelectedSpells([]); setCantripResults([]); setSpellResults([]);
    setSpellSearch(''); setCantripSearch('');
  }, [selectedClass]);

  // ── Class entry management ──

  useEffect(() => {
    if (classEntries.length === 0) return;
    const currentTotal = classEntries.reduce((s, e) => s + e.level, 0);
    if (currentTotal === level) return;
    setClassEntries(prev => {
      const newPrimaryLevel = prev[0].level + (level - currentTotal);
      if (newPrimaryLevel >= 1) return prev.map((e, i) => i === 0 ? { ...e, level: newPrimaryLevel } : e);
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
      return prev.filter(e => e.cls.id !== clsId).map((e, i) => i === 0 ? { ...e, level: e.level + freedLevels } : e);
    });
    setMcSpellSelections(prev => { const next = { ...prev }; delete next[clsId]; return next; });
  }, []);

  const handleClassLevelChange = useCallback((clsId: string, newLevel: number) => {
    setClassEntries(prev => {
      const idx = prev.findIndex(e => e.cls.id === clsId);
      if (idx < 0) return prev;
      const otherTotal = prev.reduce((s, e, i) => i === idx ? s : s + e.level, 0);
      const clamped = Math.max(1, Math.min(newLevel, level - otherTotal));
      return prev.map((e, i) => i === idx ? { ...e, level: clamped } : e);
    });
  }, [level]);

  const handleEntrySubclass = useCallback((clsId: string, sc: Subclass | null) => {
    setClassEntries(prev => prev.map(e => e.cls.id === clsId ? { ...e, subclass: sc } : e));
    if (classEntries.length > 0 && classEntries[0].cls.id === clsId) setSelectedSubclass(sc);
  }, [classEntries]);

  const handleSelectPrimaryClass = useCallback((cls: CharacterClassRef | null) => {
    if (!cls) {
      setSelectedClass(null); setSelectedSubclass(null); setClassEntries([]);
      setMcSpellSelections({}); setSelectedClassSkills([]); setMcSkillSelections({}); setSelectedExpertise([]);
    } else {
      setSelectedClass(cls); setSelectedSubclass(null);
      setSelectedCantrips([]); setSelectedSpells([]); setCantripResults([]); setSpellResults([]);
      setMcSpellSelections({}); setSelectedClassSkills([]); setMcSkillSelections({}); setSelectedExpertise([]);
      getSubclasses(cls.id).then(scs => {
        setSubclasses(scs);
        setClassEntries([{ cls, level, subclass: null, subclasses: scs }]);
      });
    }
  }, [level]);

  // ── Race bonuses & choices ──

  useEffect(() => {
    if (!selectedRace) { setBonusAssignments([]); return; }
    const bonuses = safeJsonParse<Array<{ ability: string; bonus: number; count?: number }>>(selectedRace.abilityScoreBonuses, []);
    const rows: Array<{ bonus: number; ability: string | null }> = [];
    for (const b of bonuses) {
      if (b.ability === 'CHOOSE') { for (let i = 0; i < (b.count || 1); i++) rows.push({ bonus: b.bonus, ability: null }); }
      else { const key = b.ability.toLowerCase().slice(0, 3); rows.push({ bonus: b.bonus, ability: ABILITIES.find(a => a.startsWith(key)) || null }); }
    }
    setBonusAssignments(rows);
  }, [selectedRace]);

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
        if (lc.anyStandard) { reqs.push({ key: `lang_${reqs.length}`, label: 'Language', options: ALL_LANGUAGES.filter(l => !existingLangs.has(l.toLowerCase())), count: lc.anyStandard as number }); }
        if (lc.choose) { const ch = lc.choose as { from?: string[]; count?: number }; if (ch.from?.length) reqs.push({ key: `lang_${reqs.length}`, label: 'Language', options: ch.from, count: ch.count ?? 1 }); }
      }
    }
    if (choices.skills) {
      const existingSkills = new Set((profs.skills ?? []).map((s: string) => s.toLowerCase()));
      for (const sc of choices.skills as Array<Record<string, unknown>>) {
        if (sc.any) reqs.push({ key: `skill_${reqs.length}`, label: 'Skill Proficiency', options: ALL_SKILLS.filter(s => !existingSkills.has(s.toLowerCase())), count: sc.any as number });
        if (sc.choose) { const ch = sc.choose as { from?: string[]; count?: number }; if (ch.from?.length) reqs.push({ key: `skill_${reqs.length}`, label: 'Skill Proficiency', options: ch.from, count: ch.count ?? 1 }); }
      }
    }
    if (choices.tools) {
      for (const tc of choices.tools as Array<Record<string, unknown>>) {
        if (tc.any) reqs.push({ key: `tool_${reqs.length}`, label: 'Tool Proficiency', options: ALL_TOOLS, count: tc.any as number });
        if (tc.anyArtisansTool) reqs.push({ key: `tool_${reqs.length}`, label: "Artisan's Tool", options: ARTISANS_TOOLS, count: tc.anyArtisansTool as number });
        if (tc.anyMusicalInstrument) reqs.push({ key: `tool_${reqs.length}`, label: 'Musical Instrument', options: MUSICAL_INSTRUMENTS, count: tc.anyMusicalInstrument as number });
        if (tc.choose) { const ch = tc.choose as { from?: string[]; count?: number }; if (ch.from?.length) reqs.push({ key: `tool_${reqs.length}`, label: 'Tool Proficiency', options: ch.from, count: ch.count ?? 1 }); }
      }
    }
    if (choices.weapons) { for (const wc of choices.weapons as Array<{ from?: string[]; count?: number }>) reqs.push({ key: `weapon_${reqs.length}`, label: 'Martial Weapon Proficiency', options: wc.from ?? [], count: wc.count ?? 2 }); }
    if (choices.resistances) { for (const rc of choices.resistances as Array<{ from: string[] }>) reqs.push({ key: `resist_${reqs.length}`, label: 'Damage Resistance', options: rc.from, count: 1 }); }
    if (choices.spellAbility) reqs.push({ key: 'spellAbility', label: 'Spellcasting Ability', options: choices.spellAbility as string[], count: 1 });
    if (choices.feats) { const featNames = feats.map(f => f.name).sort(); if (featNames.length > 0) reqs.push({ key: `feat_${reqs.length}`, label: 'Feat', options: featNames, count: choices.feats as number }); }
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

  const raceChoicesComplete = useMemo(() => raceChoiceReqs.every(req => (raceChoiceSelections[req.key] ?? []).length === req.count), [raceChoiceReqs, raceChoiceSelections]);

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

  // ── Background choices ──

  const bgChoiceReqs = useMemo((): ChoiceReq[] => {
    if (!selectedBackground) return [];
    const reqs: ChoiceReq[] = [];
    const fields: Array<[string, string, string]> = [['skillProficiencies', 'Skill', 'skill'], ['toolProficiencies', 'Tool', 'tool'], ['languageProficiencies', 'Language', 'lang']];
    for (const [field, label, prefix] of fields) {
      const raw = (selectedBackground as unknown as Record<string, string | null>)[field];
      if (!raw) continue;
      const parsed = safeJsonParse<ProfEntry[]>(raw, []);
      let chooseIdx = 0;
      for (const p of parsed) {
        if (typeof p === 'string') {
          const anyOpts = prefix === 'tool' ? getToolAnyOptions(p) : prefix === 'skill' && p === 'Any' ? ALL_SKILLS : null;
          if (anyOpts) { reqs.push({ key: `${prefix}_choose_${chooseIdx}`, label: `${label} Proficiency`, type: 'choose', from: [...anyOpts], count: 1 }); chooseIdx++; }
        } else if (typeof p === 'object' && p !== null && 'any' in p) {
          const n = (p as { any: number }).any;
          const pool = prefix === 'skill' ? ALL_SKILLS : prefix === 'tool' ? ALL_TOOLS : ALL_LANGUAGES;
          reqs.push({ key: `${prefix}_choose_${chooseIdx}`, label: `${label} Proficiency`, type: 'choose', from: [...pool], count: n }); chooseIdx++;
        } else if (typeof p === 'object' && p !== null && 'chooseSet' in p) {
          const sets = (p as { chooseSet: ProfEntry[][] }).chooseSet;
          reqs.push({ key: `${prefix}_set`, label: `${label} Proficiencies`, type: 'chooseSet', sets: sets.map(s => s.map(formatProfEntry).filter(Boolean)) });
        } else if (typeof p === 'object' && p !== null && 'anyStandard' in p) {
          const n = (p as { anyStandard: number }).anyStandard;
          reqs.push({ key: `${prefix}_choose_${chooseIdx}`, label: `${label}`, type: 'choose', from: ALL_LANGUAGES, count: n }); chooseIdx++;
        } else if (typeof p === 'object' && p !== null && 'choose' in p) {
          const c = (p as { choose: { from?: string[]; count?: number } }).choose;
          if (c.from && c.from.length > 0) { const expanded = prefix === 'tool' ? expandToolFrom(c.from) : c.from; reqs.push({ key: `${prefix}_choose_${chooseIdx}`, label: `${label} Proficiency`, type: 'choose', from: expanded, count: c.count ?? 1 }); chooseIdx++; }
        }
      }
    }
    return reqs;
  }, [selectedBackground]);

  useEffect(() => { setBgProfChoices({}); setBgSetChoices({}); }, [selectedBackground]);

  function handleBgProfChoice(key: string, value: string, count: number) {
    setBgProfChoices(prev => {
      const current = prev[key] ?? [];
      if (current.includes(value)) return { ...prev, [key]: current.filter(v => v !== value) };
      if (current.length >= count) return { ...prev, [key]: [...current.slice(1), value] };
      return { ...prev, [key]: [...current, value] };
    });
  }

  function handleBgSetChoice(key: string, index: number) {
    setBgSetChoices(prev => ({ ...prev, [key]: prev[key] === index ? null : index }));
  }

  const bgChoicesComplete = useMemo(() => bgChoiceReqs.every(req => {
    if (req.type === 'choose') return (bgProfChoices[req.key] ?? []).length === req.count;
    return bgSetChoices[req.key] != null;
  }), [bgChoiceReqs, bgProfChoices, bgSetChoices]);

  const resolvedBgProfs = useMemo(() => {
    if (!selectedBackground) return { skills: [] as string[], tools: [] as string[], languages: [] as string[] };
    const result: { skills: string[]; tools: string[]; languages: string[]; [key: string]: string[] } = { skills: [], tools: [], languages: [] };
    const fields: Array<[string, string, string]> = [['skillProficiencies', 'skill', 'skills'], ['toolProficiencies', 'tool', 'tools'], ['languageProficiencies', 'lang', 'languages']];
    for (const [field, prefix, outKey] of fields) {
      const raw = (selectedBackground as unknown as Record<string, string | null>)[field];
      if (!raw) continue;
      const parsed = safeJsonParse<ProfEntry[]>(raw, []);
      let chooseIdx = 0;
      for (const p of parsed) {
        if (typeof p === 'string') {
          const isAny = prefix === 'tool' ? !!getToolAnyOptions(p) : prefix === 'skill' && p === 'Any';
          if (isAny) { result[outKey].push(...(bgProfChoices[`${prefix}_choose_${chooseIdx}`] ?? [])); chooseIdx++; }
          else result[outKey].push(p);
        } else if (typeof p === 'object' && p !== null && 'any' in p) { result[outKey].push(...(bgProfChoices[`${prefix}_choose_${chooseIdx}`] ?? [])); chooseIdx++; }
        else if (typeof p === 'object' && p !== null && 'chooseSet' in p) {
          const idx = bgSetChoices[`${prefix}_set`];
          if (idx != null) { const sets = (p as { chooseSet: ProfEntry[][] }).chooseSet; result[outKey].push(...sets[idx].map(formatProfEntry).filter(Boolean)); }
        } else if (typeof p === 'object' && p !== null && ('anyStandard' in p || 'choose' in p)) { result[outKey].push(...(bgProfChoices[`${prefix}_choose_${chooseIdx}`] ?? [])); chooseIdx++; }
      }
    }
    return result;
  }, [selectedBackground, bgProfChoices, bgSetChoices]);

  // ── Skill conflicts & score computations ──

  const raceSkills = useMemo(() => {
    if (!selectedRace) return new Set<string>();
    const profs = safeJsonParse<{ skills?: string[] }>(selectedRace.proficiencies, {});
    return new Set([...(profs.skills ?? []).map(s => s.toLowerCase()), ...resolvedRaceChoices.skills.map(s => s.toLowerCase())]);
  }, [selectedRace, resolvedRaceChoices.skills]);

  const bgSkillConflicts = useMemo(() => {
    const allTaken = new Set([...raceSkills, ...selectedClassSkills.map(s => s.toLowerCase())]);
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
    bonusAssignments.forEach(a => { if (a.ability) map[a.ability] = (map[a.ability] || 0) + a.bonus; });
    return map;
  }, [bonusAssignments]);

  const finalScores = useMemo(() => {
    const base = abilityMethod === 'standard'
      ? Object.fromEntries(ABILITIES.map(a => [a, standardAssignments[a] ?? 10])) as AbilityScores
      : scores;
    const result: AbilityScores = { ...base };
    for (const [ability, bonus] of Object.entries(racialBonuses)) {
      if (ability in result) result[ability as keyof AbilityScores] += bonus;
    }
    if (featAsi) {
      for (const [ability, bonus] of Object.entries(featAsi.fixed)) {
        const key = ABILITY_FROM_ABBR[ability] || ability.toLowerCase();
        if (key in result) result[key as keyof AbilityScores] = Math.min(20, result[key as keyof AbilityScores] + bonus);
      }
      if (featAsi.choose && selectedFeatAsiAbility) {
        const key = ABILITY_FROM_ABBR[selectedFeatAsiAbility] || selectedFeatAsiAbility.toLowerCase();
        if (key in result) result[key as keyof AbilityScores] = Math.min(20, result[key as keyof AbilityScores] + featAsi.choose.amount);
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

  // ── Step navigation ──

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
        for (const entry of classEntries) { if (entry.level >= (entry.cls.subclassLevel || 3) && entry.subclasses.length > 0 && !entry.subclass) return false; }
        if (classSkillChoices.count > 0 && selectedClassSkills.length < classSkillChoices.count) return false;
        return true;
      }
      case 'Ability Scores':
        if (abilityMethod === 'standard') return Object.values(standardAssignments).every(v => v !== null);
        if (abilityMethod === 'pointbuy') return pointBuyTotal <= 27;
        return true;
      case 'Background': return selectedBackground !== null && bgChoicesComplete && featConfigComplete;
      case 'Spells': return true;
      default: return true;
    }
  }

  // ── Character creation ──

  function buildSpellsKnown(): string | undefined {
    const entries: Array<Record<string, unknown>> = [];
    if (selectedClass?.isSpellcaster) {
      const source = `class:${selectedClass.name}`;
      for (const s of selectedCantrips) entries.push({ name: s.name, level: 0, source });
      for (const s of selectedSpells) {
        const isWiz = selectedClass.name === 'Wizard';
        entries.push({ name: s.name, level: s.level, source, ...(selectedClass.isPreparedCaster && !isWiz ? { prepared: true } : {}) });
      }
    }
    for (const entry of classEntries.slice(1)) {
      if (!entry.cls.isSpellcaster) continue;
      const sel = mcSpellSelections[entry.cls.id]; if (!sel) continue;
      const source = `class:${entry.cls.name}`;
      for (const s of sel.cantrips) entries.push({ name: s.name, level: 0, source });
      for (const s of sel.spells) {
        const isWiz = entry.cls.name === 'Wizard';
        entries.push({ name: s.name, level: s.level, source, ...(entry.cls.isPreparedCaster && !isWiz ? { prepared: true } : {}) });
      }
    }
    for (const entry of classEntries) {
      if (!entry.subclass || !THIRD_CASTER_SUBCLASSES.has(entry.subclass.name)) continue;
      const sel = mcSpellSelections[`third:${entry.cls.id}`]; if (!sel) continue;
      const source = `class:${entry.cls.name}`;
      for (const s of sel.cantrips) entries.push({ name: s.name, level: 0, source });
      for (const s of sel.spells) entries.push({ name: s.name, level: s.level, source });
    }
    if (selectedRace?.additionalSpells) {
      const raceSpells = safeJsonParse<{ fixedSpells?: Array<{ name: string; level: number; atWill?: boolean; usesPerLongRest?: number; unlocksAtLevel?: number }> }>(selectedRace.additionalSpells, {});
      const raceSource = `race:${selectedRace.name}`;
      for (const s of raceSpells.fixedSpells ?? []) {
        if (!s.unlocksAtLevel || s.unlocksAtLevel <= level) {
          entries.push({ name: s.name, level: s.level ?? 0, source: raceSource, ...(s.atWill ? { atWill: true } : {}), ...(s.usesPerLongRest ? { usesPerLongRest: s.usesPerLongRest } : {}), ...(s.unlocksAtLevel ? { unlocksAtLevel: s.unlocksAtLevel } : {}) });
        }
      }
    }
    if (selectedFeatObj && selectedFeatOption) {
      const featSource = `feat:${selectedFeatObj.name}`;
      for (const n of selectedFeatOption.fixedCantrips) entries.push({ name: n, level: 0, source: featSource, atWill: true });
      for (const s of featCantrips) entries.push({ name: s.name, level: 0, source: featSource, atWill: true });
      for (const { name, usesPerDay } of selectedFeatOption.fixedSpells) entries.push({ name, level: 1, source: featSource, usesPerLongRest: usesPerDay });
      for (const s of featSpells) entries.push({ name: s.name, level: s.level, source: featSource, usesPerLongRest: selectedFeatOption.spellChoice?.usesPerDay ?? 1 });
    }
    return entries.length > 0 ? JSON.stringify(entries) : undefined;
  }

  async function handleCreate() {
    if (!selectedRace || !selectedClass || !selectedBackground) return;
    setSubmitting(true); setError('');
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
    const allSkills = [...(raceProfs.skills ?? []), ...resolvedRaceChoices.skills, ...selectedClassSkills, ...resolvedBgProfs.skills, ...Object.values(mcSkillSelections).flat()];
    const allWeapons = [...(raceProfs.weapons ?? []), ...resolvedRaceChoices.weapons, ...classWeapons];
    const allTools = [...(raceProfs.tools ?? []), ...resolvedRaceChoices.tools, ...resolvedBgProfs.tools, ...classTools];
    const allLanguages = [...(raceProfs.languages ?? []), ...resolvedRaceChoices.languages, ...resolvedBgProfs.languages];
    const featFeatures: Array<{ name: string; description: string; source: string }> = [];
    for (const featName of resolvedRaceChoices.feats) featFeatures.push({ name: featName, description: 'Racial feat', source: selectedRace.name });
    if (selectedFeatObj && selectedFeatOption) {
      const optionDesc = parsedFeatOptions.length > 1 ? ` (${selectedFeatOption.name})` : '';
      const abilityDesc = selectedFeatAbility ? ` Spellcasting ability: ${selectedFeatAbility}.` : '';
      featFeatures.push({ name: selectedFeatObj.name, description: `Granted by ${selectedBackground.name} background.${optionDesc}${abilityDesc}`, source: selectedBackground.name });
    } else if (selectedBgFeat) {
      const feat = feats.find(f => f.name.toLowerCase() === selectedBgFeat.toLowerCase());
      if (feat) featFeatures.push({ name: feat.name, description: `Granted by ${selectedBackground.name} background.`, source: selectedBackground.name });
    }
    const featSpellAbility = selectedFeatAbility ?? selectedFeatOption?.ability;
    let resolvedSpellAbility: string | undefined = selectedClass.spellcastingAbility ?? undefined;
    if (!resolvedSpellAbility) { const thirdCasterEntry = classEntries.find(e => e.subclass && THIRD_CASTER_SUBCLASSES.has(e.subclass.name)); if (thirdCasterEntry) resolvedSpellAbility = THIRD_CASTER_ABILITY[thirdCasterEntry.subclass!.name]; }
    if (!resolvedSpellAbility && featSpellAbility) resolvedSpellAbility = featSpellAbility;
    let spellSaveDc: number | undefined;
    let spellAttackBonus: number | undefined;
    if (resolvedSpellAbility && !selectedClass.isSpellcaster) {
      const profBonus = proficiencyBonusForLevel(level);
      const abilityKey = ABILITY_FROM_ABBR[resolvedSpellAbility] as keyof typeof finalScores | undefined;
      const mod = abilityMod(abilityKey ? finalScores[abilityKey] : 10);
      spellSaveDc = 8 + profBonus + mod;
      spellAttackBonus = profBonus + mod;
    }
    const hitDiceMap: Record<string, { total: number; remaining: number; faces: number }> = {};
    for (const entry of classEntries) hitDiceMap[entry.cls.name] = { total: entry.level, remaining: entry.level, faces: entry.cls.hitDice };
    if (classEntries.length === 0) hitDiceMap[selectedClass.name] = { total: level, remaining: level, faces: selectedClass.hitDice };
    const multiclassClassEntries = isMulticlass ? JSON.stringify(classEntries.map(e => ({ classId: e.cls.id, subclassId: e.subclass?.id ?? null, level: e.level }))) : undefined;
    try {
      const res = await characterApi.create({
        name: name.trim(), level, raceId: selectedRace.id, classId: selectedClass.id,
        subclassId: classEntries[0]?.subclass?.id ?? selectedSubclass?.id,
        backgroundId: selectedBackground.id, alignment: alignment || undefined,
        abilityScoreMethod: abilityMethod,
        racialAbilityBonuses: JSON.stringify(bonusAssignments.map(a => ({ ability: a.ability ? ABILITY_LABELS[a.ability] : null, bonus: a.bonus }))),
        strength: finalScores.strength, dexterity: finalScores.dexterity, constitution: finalScores.constitution,
        intelligence: finalScores.intelligence, wisdom: finalScores.wisdom, charisma: finalScores.charisma,
        ...(level === 1 && !isMulticlass ? { hpMax: hpMax as number } : {}),
        speed: speed.walk ?? 30,
        savingThrowProficiencies: JSON.stringify(savingThrows),
        skillProficiencies: allSkills.length > 0 ? JSON.stringify([...new Set(allSkills)]) : undefined,
        skillExpertises: selectedExpertise.length > 0 ? JSON.stringify(selectedExpertise) : undefined,
        armorProficiencies: classArmor.length > 0 ? JSON.stringify([...new Set(classArmor)]) : undefined,
        weaponProficiencies: allWeapons.length > 0 ? JSON.stringify([...new Set(allWeapons)]) : undefined,
        toolProficiencies: allTools.length > 0 ? JSON.stringify([...new Set(allTools)]) : undefined,
        languageProficiencies: allLanguages.length > 0 ? JSON.stringify([...new Set(allLanguages)]) : undefined,
        damageResistances: resistances.length > 0 ? JSON.stringify(resistances) : undefined,
        spellcastingAbility: resolvedSpellAbility, spellSaveDc, spellAttackBonus,
        spellsKnown: buildSpellsKnown(),
        features: featFeatures.length > 0 ? JSON.stringify(featFeatures) : undefined,
        hitDiceMap: JSON.stringify(hitDiceMap), multiclassClassEntries,
      });
      localStorage.removeItem(DRAFT_KEY);
      const asiCount = countAsiLevels(classEntries);
      if (asiCount > 0) { setCreatedCharacter(res.data); setPendingAsiCount(asiCount); }
      else navigate(`/player/characters/${res.data.id}`);
    } catch (err: any) {
      setError(err.response?.data?.error || err.response?.data?.message || 'Failed to create character');
    } finally { setSubmitting(false); }
  }

  function handleAsiComplete(updated: PlayerCharacter) {
    if (pendingAsiCount <= 1) navigate(`/player/characters/${updated.id}`);
    else { setCreatedCharacter(updated); setPendingAsiCount(prev => prev - 1); }
  }

  // ── Render ──

  return (
    <div className="min-h-screen bg-page">
      <header className="sticky top-0 z-10 bg-page border-b border-rule px-6 py-3">
        <div className="max-w-3xl mx-auto flex items-center justify-between">
          <button onClick={() => navigate('/player')} className="flex items-center gap-2 font-body text-[13px] font-medium text-muted hover:text-ink transition-colors">
            <ChevronLeft className="w-4 h-4" /> Back
          </button>
          <h1 className="font-heading text-[17px] font-semibold tracking-[0.02em] text-ink">Create Character</h1>
          <div className="w-16" />
        </div>
      </header>

      {showDraftBanner && (
        <div className="max-w-3xl mx-auto px-6 pt-4">
          <div className="bg-cls-wizard-bg border border-abj-border px-4 py-3 flex items-center justify-between">
            <span className="font-body text-[13px] font-medium text-cls-wizard">You have an unsaved character draft. Restore it?</span>
            <div className="flex gap-2">
              <button onClick={dismissDraft} className="px-3 py-1 font-body text-[12px] text-muted hover:text-ink transition-colors">Discard</button>
              <button onClick={restoreDraft} className="px-3 py-1 font-body text-[12px] bg-ink text-card hover:bg-muted transition-colors">Restore</button>
            </div>
          </div>
        </div>
      )}

      <div className="max-w-3xl mx-auto px-6 pt-6">
        <div className="flex items-center gap-1">
          {steps.map((label, i) => (
            <div key={label} className="flex-1 flex flex-col items-center">
              <div className={`w-7 h-7 flex items-center justify-center font-heading text-[11px] font-bold border-2 transition-colors ${
                i < step ? 'bg-buff border-buff text-white' : i === step ? 'bg-ink border-ink text-card' : 'bg-page border-rule text-faint'
              }`}>
                {i < step ? <Check className="w-3.5 h-3.5" /> : i + 1}
              </div>
              <span className={`font-heading text-[9px] font-medium tracking-[0.04em] mt-1 hidden sm:block ${i === step ? 'text-ink' : 'text-faint'}`}>{label}</span>
            </div>
          ))}
        </div>
      </div>

      <main className="max-w-3xl mx-auto px-6 py-8">
        {currentStepName === 'Basic Info' && (
          <BasicInfoStep name={name} setName={setName} alignment={alignment} setAlignment={setAlignment} level={level} setLevel={setLevel} />
        )}
        {currentStepName === 'Race' && (
          <RaceStep
            filteredRaces={filteredRaces} raceSearch={raceSearch} setRaceSearch={setRaceSearch}
            selectedRace={selectedRace} setSelectedRace={setSelectedRace}
            raceChoiceReqs={raceChoiceReqs} raceChoiceSelections={raceChoiceSelections}
            handleRaceChoice={handleRaceChoice} raceChoicesComplete={raceChoicesComplete}
            bonusAssignments={bonusAssignments} handleBonusAssignment={handleBonusAssignment}
            isVanillaHuman={isVanillaHuman}
          />
        )}
        {currentStepName === 'Ability Scores' && (
          <AbilityScoresStep
            abilityMethod={abilityMethod} setAbilityMethod={setAbilityMethod}
            scores={scores} setScores={setScores}
            standardAssignments={standardAssignments} setStandardAssignments={setStandardAssignments}
            racialBonuses={racialBonuses} pointBuyTotal={pointBuyTotal}
          />
        )}
        {currentStepName === 'Class' && (
          <ClassStep
            classes={classes} selectedClass={selectedClass} onSelectClass={handleSelectPrimaryClass}
            classEntries={classEntries} level={level}
            multiclassExpanded={multiclassExpanded} setMulticlassExpanded={setMulticlassExpanded}
            addMulticlass={addMulticlass} removeMulticlass={removeMulticlass}
            handleClassLevelChange={handleClassLevelChange} handleEntrySubclass={handleEntrySubclass}
            finalScores={finalScores} classSkillChoices={classSkillChoices}
            selectedClassSkills={selectedClassSkills} setSelectedClassSkills={setSelectedClassSkills}
            raceSkills={raceSkills} mcSkillChoicesMap={mcSkillChoicesMap}
            mcSkillSelections={mcSkillSelections} setMcSkillSelections={setMcSkillSelections}
            resolvedBgProfs={resolvedBgProfs} resolvedRaceChoices={resolvedRaceChoices}
            selectedExpertise={selectedExpertise} setSelectedExpertise={setSelectedExpertise}
            expertiseCount={expertiseCount} selectedRace={selectedRace}
          />
        )}
        {currentStepName === 'Background' && (
          <BackgroundStep
            filteredBackgrounds={filteredBackgrounds} bgSearch={bgSearch} setBgSearch={setBgSearch}
            selectedBackground={selectedBackground} setSelectedBackground={setSelectedBackground}
            bgChoiceReqs={bgChoiceReqs} bgProfChoices={bgProfChoices} handleBgProfChoice={handleBgProfChoice}
            bgSetChoices={bgSetChoices} handleBgSetChoice={handleBgSetChoice}
            bgChoicesComplete={bgChoicesComplete} bgSkillConflicts={bgSkillConflicts}
            bgFeatNames={bgFeatNames} selectedBgFeat={selectedBgFeat} setSelectedBgFeat={setSelectedBgFeat}
            selectedFeatObj={selectedFeatObj} parsedFeatOptions={parsedFeatOptions}
            selectedFeatOptionIdx={selectedFeatOptionIdx} setSelectedFeatOptionIdx={setSelectedFeatOptionIdx}
            selectedFeatOption={selectedFeatOption} selectedFeatAbility={selectedFeatAbility}
            setSelectedFeatAbility={setSelectedFeatAbility}
            selectedFeatAsiAbility={selectedFeatAsiAbility} setSelectedFeatAsiAbility={setSelectedFeatAsiAbility}
            featAsi={featAsi} featConfigComplete={featConfigComplete}
            setFeatCantrips={setFeatCantrips} setFeatSpells={setFeatSpells}
          />
        )}
        {currentStepName === 'Spells' && (
          <SpellsStep
            selectedClass={selectedClass} classEntries={classEntries} level={level}
            selectedCantrips={selectedCantrips} setSelectedCantrips={setSelectedCantrips}
            selectedSpells={selectedSpells} setSelectedSpells={setSelectedSpells}
            cantripResults={cantripResults} setCantripResults={setCantripResults}
            spellResults={spellResults} setSpellResults={setSpellResults}
            cantripSearch={cantripSearch} setCantripSearch={setCantripSearch}
            spellSearch={spellSearch} setSpellSearch={setSpellSearch}
            mcSpellSelections={mcSpellSelections} setMcSpellSelections={setMcSpellSelections}
            selectedFeatObj={selectedFeatObj} selectedFeatOption={selectedFeatOption}
            hasFeatSpellChoices={hasFeatSpellChoices}
            featCantrips={featCantrips} setFeatCantrips={setFeatCantrips}
            featSpells={featSpells} setFeatSpells={setFeatSpells}
          />
        )}
        {currentStepName === 'Review' && (
          <ReviewStep
            name={name} alignment={alignment} selectedRace={selectedRace}
            selectedClass={selectedClass} classEntries={classEntries} level={level}
            selectedBackground={selectedBackground} finalScores={finalScores}
            selectedClassSkills={selectedClassSkills}
            resolvedRaceChoices={resolvedRaceChoices} resolvedBgProfs={resolvedBgProfs}
            selectedBgFeat={selectedBgFeat} selectedFeatOption={selectedFeatOption}
            parsedFeatOptions={parsedFeatOptions} selectedFeatAbility={selectedFeatAbility}
            selectedFeatAsiAbility={selectedFeatAsiAbility} featAsi={featAsi}
            featCantrips={featCantrips} featSpells={featSpells}
            selectedCantrips={selectedCantrips} selectedSpells={selectedSpells}
            mcSpellSelections={mcSpellSelections} error={error}
          />
        )}

        <div className="flex items-center justify-between mt-8 pt-6 border-t border-rule">
          <button onClick={() => step > 0 ? setStep(step - 1) : navigate('/player')} className="flex items-center gap-2 px-4 py-2 font-body text-[14px] font-medium text-muted hover:text-ink transition-colors">
            <ArrowLeft className="w-4 h-4" /> {step > 0 ? 'Previous' : 'Cancel'}
          </button>
          {step < steps.length - 1 ? (
            <button onClick={() => setStep(step + 1)} disabled={!canAdvance()} className="flex items-center gap-2 px-5 py-2 bg-ink text-card font-body text-[14px] font-medium border border-ink hover:bg-muted hover:border-muted disabled:opacity-40 disabled:cursor-not-allowed transition-colors">
              Next <ArrowRight className="w-4 h-4" />
            </button>
          ) : (
            <button onClick={handleCreate} disabled={submitting} className="flex items-center gap-2 px-5 py-2 bg-buff text-white font-body text-[14px] font-medium border border-buff hover:opacity-80 disabled:opacity-40 disabled:cursor-not-allowed transition-colors">
              {submitting ? 'Creating...' : 'Create Character'} <Check className="w-4 h-4" />
            </button>
          )}
        </div>
      </main>

      {createdCharacter && pendingAsiCount > 0 && (
        <AsiModal character={createdCharacter} onComplete={handleAsiComplete} onClose={() => navigate(`/player/characters/${createdCharacter.id}`)} />
      )}
    </div>
  );
}
