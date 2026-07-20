import { useState, useMemo, useEffect, useCallback } from 'react';
import { X, Plus, Minus, Search, Check, ChevronDown, ChevronUp, Lock } from 'lucide-react';
import { characterApi } from '../../api/characterApi';
import { getFeats, getOptionalFeatures, searchSpells } from '../../api/referenceApi';
import type { PlayerCharacter, ApplyChoicesRequest, AsiChoice } from '../../types/character';
import type { Feat, OptionalFeature, Spell } from '../../types/reference';
import { checkFeatPrerequisites, parseFeatEffects, parseAbilityScoreIncrease } from '../../utils/featPrerequisites';
import FormattedDescription from '../FormattedDescription';

const ABILITIES = [
  { key: 'strength', label: 'STR', full: 'Strength' },
  { key: 'dexterity', label: 'DEX', full: 'Dexterity' },
  { key: 'constitution', label: 'CON', full: 'Constitution' },
  { key: 'intelligence', label: 'INT', full: 'Intelligence' },
  { key: 'wisdom', label: 'WIS', full: 'Wisdom' },
  { key: 'charisma', label: 'CHA', full: 'Charisma' },
] as const;

const ALL_SKILLS = [
  'Acrobatics', 'Animal Handling', 'Arcana', 'Athletics', 'Deception',
  'History', 'Insight', 'Intimidation', 'Investigation', 'Medicine',
  'Nature', 'Perception', 'Performance', 'Persuasion', 'Religion',
  'Sleight of Hand', 'Stealth', 'Survival',
];

const SCHOOL_ABBREV: Record<string, string> = {
  A: 'Abjuration', C: 'Conjuration', D: 'Divination', E: 'Enchantment',
  V: 'Evocation', I: 'Illusion', N: 'Necromancy', T: 'Transmutation',
};

interface Props {
  character: PlayerCharacter;
  onComplete: (updated: PlayerCharacter) => void;
  onClose: () => void;
}

export default function AsiModal({ character, onComplete, onClose }: Props) {
  const [mode, setMode] = useState<'ability' | 'feat'>('ability');
  const [increases, setIncreases] = useState<Record<string, number>>({});
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');

  // Feat state
  const [feats, setFeats] = useState<Feat[]>([]);
  const [loading, setLoading] = useState(false);
  const [search, setSearch] = useState('');
  const [selectedFeat, setSelectedFeat] = useState<Feat | null>(null);
  const [showDescription, setShowDescription] = useState(false);

  // Feat choices
  const [abilityChoice, setAbilityChoice] = useState('');
  const [resistanceChoice, setResistanceChoice] = useState('');
  const [skillChoices, setSkillChoices] = useState<string[]>([]);
  const [savingThrowChoice, setSavingThrowChoice] = useState('');
  const [expertiseChoices, setExpertiseChoices] = useState<string[]>([]);
  const [toolChoices, setToolChoices] = useState<string[]>([]);
  const [languageChoices, setLanguageChoices] = useState<string[]>([]);
  const [weaponChoices, setWeaponChoices] = useState<string[]>([]);
  const [optionalFeatures, setOptionalFeatures] = useState<OptionalFeature[]>([]);
  const [selectedOptFeatures, setSelectedOptFeatures] = useState<string[]>([]);

  // Spell picker state
  const [selectedSpellIds, setSelectedSpellIds] = useState<string[]>([]);
  const [fixedSpellIds, setFixedSpellIds] = useState<string[]>([]);
  const [spellSearchResults, setSpellSearchResults] = useState<Spell[]>([]);
  const [spellSearchQuery, setSpellSearchQuery] = useState('');
  const [spellSearchLoading, setSpellSearchLoading] = useState(false);
  const [activeSpellSlot, setActiveSpellSlot] = useState<{ type: 'cantrip' | 'spell'; index: number; filter: SpellFilter } | null>(null);

  useEffect(() => {
    if (mode === 'feat' && feats.length === 0) {
      setLoading(true);
      getFeats().then(setFeats).finally(() => setLoading(false));
    }
  }, [mode]);

  const pointsSpent = Object.values(increases).reduce((a, b) => a + b, 0);
  const pointsRemaining = 2 - pointsSpent;

  const featList = useMemo(() => {
    if (feats.length === 0) return [];
    return feats.map(feat => {
      const prereq = checkFeatPrerequisites(feat, character);
      const existingFeats = getCharacterFeatNames(character);
      const alreadyTaken = existingFeats.includes(feat.name);
      return { feat, eligible: prereq.eligible && !alreadyTaken, reason: alreadyTaken ? 'Already taken' : prereq.reason };
    }).sort((a, b) => {
      if (a.eligible !== b.eligible) return a.eligible ? -1 : 1;
      return a.feat.name.localeCompare(b.feat.name);
    });
  }, [feats, character]);

  const filteredFeats = useMemo(() => {
    if (!search.trim()) return featList;
    const q = search.toLowerCase();
    return featList.filter(f => f.feat.name.toLowerCase().includes(q));
  }, [featList, search]);

  const selectedEffects = selectedFeat ? parseFeatEffects(selectedFeat) : null;
  const selectedAsi = selectedFeat ? parseAbilityScoreIncrease(selectedFeat) : null;

  // Load optional features when a feat with optionalFeatureProgression is selected
  useEffect(() => {
    if (!selectedEffects?.optionalFeatureProgression) {
      setOptionalFeatures([]);
      return;
    }
    const prog = selectedEffects.optionalFeatureProgression;
    if (prog.length > 0) {
      const featureTypes = prog.flatMap((p: any) => p.featureType || []);
      const typeMap: Record<string, string> = {
        'EI': 'EldritchInvocation', 'MM': 'Metamagic',
        'MV:B': 'BattleManeuver',
        'FS:F': 'FightingStyle', 'FS:R': 'FightingStyle',
        'FS:P': 'FightingStyle', 'FS:B': 'FightingStyle',
      };
      const normalizedTypes = [...new Set(featureTypes.map((t: string) => typeMap[t] || t))];
      Promise.all(normalizedTypes.map((t: string) => getOptionalFeatures(t)))
        .then(results => setOptionalFeatures(results.flat()));
    }
  }, [selectedFeat?.id]);

  // Parse feat spell options from grantsFeatures
  const featSpellOptions = useMemo((): { name: string }[] | null => {
    if (!selectedFeat?.grantsFeatures) return null;
    const raw = typeof selectedFeat.grantsFeatures === 'string'
      ? JSON.parse(selectedFeat.grantsFeatures) : selectedFeat.grantsFeatures;
    if (!Array.isArray(raw) || raw.length === 0) return null;
    return raw.map((o: any, i: number) => ({ name: o.name || `Option ${i + 1}` }));
  }, [selectedFeat?.id]);

  // For feats with multiple options (Magic Initiate has Bard/Cleric/etc.), track which option
  const [selectedSpellOption, setSelectedSpellOption] = useState<number>(0);

  // Parse spell slots directly from raw grantsFeatures JSON
  const spellSlots = useMemo(() => {
    if (!selectedFeat?.grantsFeatures) return { fixed: [] as string[], choices: [] as SpellFilter[] };
    const raw = typeof selectedFeat.grantsFeatures === 'string'
      ? JSON.parse(selectedFeat.grantsFeatures) : selectedFeat.grantsFeatures;
    if (!Array.isArray(raw)) return { fixed: [] as string[], choices: [] as SpellFilter[] };
    const option = raw[selectedSpellOption] || raw[0];
    if (!option) return { fixed: [] as string[], choices: [] as SpellFilter[] };

    const fixed: string[] = [];
    const choices: SpellFilter[] = [];

    // Known cantrips
    const knownArr = option.known?._;
    if (Array.isArray(knownArr)) {
      for (const item of knownArr) {
        if (typeof item === 'string') {
          fixed.push(stripSpellRef(item));
        } else if (item?.choose) {
          const parsed = parseChooseString(item.choose);
          const count = item.count || 1;
          for (let i = 0; i < count; i++) {
            choices.push({ level: parsed.level ?? 0, classes: parsed.classes || [], schools: parsed.schools || [] });
          }
        }
      }
    }

    // Innate/daily spells
    const daily = option.innate?._?.daily || option.innate?._?.rest;
    if (daily) {
      for (const spells of Object.values(daily) as any[]) {
        if (!Array.isArray(spells)) continue;
        for (const item of spells) {
          if (typeof item === 'string') {
            fixed.push(stripSpellRef(item));
          } else if (item?.choose) {
            const parsed = parseChooseString(item.choose);
            const count = item.count || 1;
            for (let i = 0; i < count; i++) {
              if (item.choose?.from) {
                choices.push({ level: -1, classes: [], schools: [], fromList: item.choose.from.map((s: string) => stripSpellRef(s)) });
              } else {
                choices.push({ level: parsed.level ?? -1, classes: parsed.classes || [], schools: parsed.schools || [] });
              }
            }
          }
        }
      }
    }

    return { fixed, choices };
  }, [selectedFeat?.id, selectedSpellOption]);

  // Look up fixed spell IDs by name
  useEffect(() => {
    if (spellSlots.fixed.length === 0) { setFixedSpellIds([]); return; }
    const lookups = spellSlots.fixed.map(name =>
      searchSpells({ name, size: 1 }).then(r => r.content[0]?.id || null)
    );
    Promise.all(lookups).then(ids => setFixedSpellIds(ids.filter(Boolean) as string[]));
  }, [spellSlots.fixed.join(',')]);

  // Search spells for the active choice slot
  const searchFeatSpells = useCallback(async (query: string, filter: SpellFilter) => {
    setSpellSearchLoading(true);
    try {
      const params: Record<string, string | number> = { size: 50, sort: 'name' };
      if (query) params.name = query;
      if (filter.level >= 0) params.level = filter.level;
      if (filter.classes.length > 0) params.className = filter.classes[0];
      if (filter.schools.length > 0) params.school = filter.schools.join(',');
      const result = await searchSpells(params);
      let spells = result.content;
      if (filter.fromList) {
        const nameSet = new Set(filter.fromList.map(n => n.toLowerCase()));
        spells = spells.filter(s => nameSet.has(s.name.toLowerCase()));
      }
      setSpellSearchResults(spells);
    } finally {
      setSpellSearchLoading(false);
    }
  }, []);

  function resetFeatChoices() {
    setAbilityChoice('');
    setResistanceChoice('');
    setSkillChoices([]);
    setSavingThrowChoice('');
    setExpertiseChoices([]);
    setToolChoices([]);
    setLanguageChoices([]);
    setWeaponChoices([]);
    setSelectedOptFeatures([]);
    setSelectedSpellIds([]);
    setFixedSpellIds([]);
    setSpellSearchResults([]);
    setSpellSearchQuery('');
    setActiveSpellSlot(null);
    setSelectedSpellOption(0);
  }

  function selectFeat(feat: Feat) {
    setSelectedFeat(feat);
    resetFeatChoices();
    setShowDescription(false);
  }

  function adjustAbility(ability: string, delta: number) {
    const current = increases[ability] || 0;
    const newVal = current + delta;
    const currentScore = character[ability as keyof PlayerCharacter] as number;
    if (newVal < 0 || newVal > 2) return;
    if (currentScore + newVal > 20) return;
    if (delta > 0 && pointsRemaining <= 0) return;
    const updated = { ...increases };
    if (newVal === 0) delete updated[ability];
    else updated[ability] = newVal;
    setIncreases(updated);
  }

  function needsChoices(): { ability: boolean; resistance: boolean; skill: number; savingThrow: boolean;
    expertise: number; tool: number; language: number; weapon: number; optFeature: number; spellChoices: number } {
    const result = { ability: false, resistance: false, skill: 0, savingThrow: false,
      expertise: 0, tool: 0, language: 0, weapon: 0, optFeature: 0, spellChoices: 0 };
    if (selectedAsi?.choose) result.ability = true;
    result.spellChoices = spellSlots.choices.length;
    if (!selectedEffects) return result;
    const e = selectedEffects;
    if (e.resistances?.some((r: any) => typeof r === 'object' && r.choose)) result.resistance = true;
    if (e.skillProficiencies?.some((s: any) => s.choose)) {
      const chooseObj = e.skillProficiencies.find((s: any) => s.choose)?.choose;
      result.skill = chooseObj?.count || 1;
    }
    if (e.savingThrowProficiencies?.some((s: any) => s.choose)) result.savingThrow = true;
    if (e.expertise?.some((x: any) => x.anyProficientSkill)) {
      result.expertise = e.expertise.find((x: any) => x.anyProficientSkill)?.anyProficientSkill || 1;
    }
    if (e.toolProficiencies?.some((t: any) => t.any || t.anyArtisansTool)) {
      const entry = e.toolProficiencies.find((t: any) => t.any || t.anyArtisansTool);
      result.tool = entry?.any || entry?.anyArtisansTool || 1;
    }
    if (e.languageProficiencies?.some((l: any) => l.any)) {
      result.language = e.languageProficiencies.find((l: any) => l.any)?.any || 1;
    }
    if (e.weaponProficiencies?.some((w: any) => w.choose)) {
      result.weapon = e.weaponProficiencies.find((w: any) => w.choose)?.choose?.count || 4;
    }
    if (e.optionalFeatureProgression) {
      const total = e.optionalFeatureProgression.reduce((sum: number, p: any) => {
        const count = p.progression?.['*'] || 0;
        return sum + count;
      }, 0);
      result.optFeature = total;
    }
    if (e.skillToolLanguageProficiencies) {
      for (const entry of e.skillToolLanguageProficiencies) {
        if (entry.choose) {
          for (const c of entry.choose) {
            result.skill += c.count || 0;
          }
        }
      }
    }
    return result;
  }

  const choices = selectedFeat ? needsChoices() : null;

  const canSubmitFeat = useMemo(() => {
    if (!selectedFeat || !choices) return false;
    if (choices.ability && !abilityChoice) return false;
    if (choices.resistance && !resistanceChoice) return false;
    if (choices.skill > 0 && skillChoices.length < choices.skill) return false;
    if (choices.savingThrow && !savingThrowChoice) return false;
    if (choices.expertise > 0 && expertiseChoices.length < choices.expertise) return false;
    if (choices.tool > 0 && toolChoices.length < choices.tool) return false;
    if (choices.language > 0 && languageChoices.length < choices.language) return false;
    if (choices.weapon > 0 && weaponChoices.length < choices.weapon) return false;
    if (choices.optFeature > 0 && selectedOptFeatures.length < choices.optFeature) return false;
    if (choices.spellChoices > 0 && selectedSpellIds.length < choices.spellChoices) return false;
    if (featSpellOptions && featSpellOptions.length > 1 && selectedSpellOption < 0) return false;
    return true;
  }, [selectedFeat, choices, abilityChoice, resistanceChoice, skillChoices, savingThrowChoice,
      expertiseChoices, toolChoices, languageChoices, weaponChoices, selectedOptFeatures,
      selectedSpellIds, featSpellOptions, selectedSpellOption]);

  const canSubmit = mode === 'ability' ? pointsSpent === 2 : canSubmitFeat;

  async function handleSubmit() {
    setSubmitting(true);
    setError('');
    try {
      const request: ApplyChoicesRequest = {};
      if (mode === 'ability') {
        request.asi = {
          type: 'ability',
          increases: Object.entries(increases).map(([ability, bonus]) => ({ ability, bonus })),
        };
      } else if (selectedFeat) {
        const asi: AsiChoice = {
          type: 'feat',
          featId: selectedFeat.id,
          featAbility: abilityChoice || undefined,
        };
        if (resistanceChoice) asi.resistanceChoice = resistanceChoice;
        if (skillChoices.length) asi.skillProficiencyChoices = skillChoices;
        if (savingThrowChoice) asi.savingThrowChoice = savingThrowChoice;
        if (expertiseChoices.length) asi.expertiseSkillChoices = expertiseChoices;
        if (toolChoices.length) asi.toolProficiencyChoices = toolChoices;
        if (languageChoices.length) asi.languageChoices = languageChoices;
        if (weaponChoices.length) asi.weaponChoices = weaponChoices;
        if (selectedOptFeatures.length) asi.optionalFeatureIds = selectedOptFeatures;
        const allSpellIds = [...fixedSpellIds, ...selectedSpellIds];
        if (allSpellIds.length > 0) asi.spellIds = allSpellIds;
        request.asi = asi;
      }
      const res = await characterApi.applyChoices(character.id, request);
      onComplete(res.data);
    } catch (err: any) {
      setError(err.response?.data?.error || err.response?.data?.message || 'Failed to apply choices');
      setSubmitting(false);
    }
  }

  const proficientSkills = useMemo(() => {
    if (!character.skillProficiencies) return [];
    try { return JSON.parse(character.skillProficiencies) as string[]; } catch { return []; }
  }, [character.skillProficiencies]);

  const existingExpertise = useMemo(() => {
    if (!character.skillExpertises) return [];
    try { return JSON.parse(character.skillExpertises) as string[]; } catch { return []; }
  }, [character.skillExpertises]);

  return (
    <div className="fixed inset-0 bg-black/60 z-50 flex items-center justify-center p-4" onClick={onClose}>
      <div className="bg-gray-900 border border-gray-800 rounded-xl w-full max-w-lg max-h-[90vh] flex flex-col" onClick={e => e.stopPropagation()}>
        <div className="px-5 py-4 border-b border-gray-800 flex items-center justify-between shrink-0">
          <div>
            <h2 className="text-white font-bold text-lg">Ability Score Improvement</h2>
            <p className="text-gray-400 text-xs mt-0.5">Level {character.level}</p>
          </div>
          <button onClick={onClose} className="text-gray-400 hover:text-white"><X className="w-5 h-5" /></button>
        </div>

        <div className="px-5 py-4 space-y-4 overflow-y-auto flex-1 min-h-0">
          {error && (
            <div className="bg-red-900/50 border border-red-700 text-red-300 rounded-lg p-3 text-sm">{error}</div>
          )}

          <div className="flex gap-2">
            <button
              onClick={() => { setMode('ability'); setSelectedFeat(null); resetFeatChoices(); }}
              className={`flex-1 py-2 text-sm font-medium rounded-lg transition-colors ${
                mode === 'ability' ? 'bg-indigo-600 text-white' : 'bg-gray-800 text-gray-400 hover:text-white'
              }`}
            >Ability Scores</button>
            <button
              onClick={() => { setMode('feat'); setIncreases({}); }}
              className={`flex-1 py-2 text-sm font-medium rounded-lg transition-colors ${
                mode === 'feat' ? 'bg-indigo-600 text-white' : 'bg-gray-800 text-gray-400 hover:text-white'
              }`}
            >Feat</button>
          </div>

          {mode === 'ability' && (
            <>
              <p className="text-gray-400 text-xs">
                Distribute 2 points across your ability scores. No score can exceed 20.
              </p>
              <div className="space-y-2">
                {ABILITIES.map(({ key, label }) => {
                  const base = character[key as keyof PlayerCharacter] as number;
                  const bonus = increases[key] || 0;
                  const atMax = base + bonus >= 20;
                  return (
                    <div key={key} className="flex items-center justify-between bg-gray-800 rounded-lg px-4 py-2">
                      <div className="flex items-center gap-3">
                        <span className="text-gray-400 text-xs font-medium w-8">{label}</span>
                        <span className="text-white font-bold">{base}</span>
                        {bonus > 0 && <span className="text-green-400 text-sm font-medium">+{bonus}</span>}
                      </div>
                      <div className="flex items-center gap-1">
                        <button onClick={() => adjustAbility(key, -1)} disabled={bonus <= 0}
                          className="p-1 rounded hover:bg-gray-700 disabled:opacity-30 disabled:cursor-not-allowed text-gray-400">
                          <Minus className="w-4 h-4" />
                        </button>
                        <button onClick={() => adjustAbility(key, 1)} disabled={atMax || pointsRemaining <= 0}
                          className="p-1 rounded hover:bg-gray-700 disabled:opacity-30 disabled:cursor-not-allowed text-gray-400">
                          <Plus className="w-4 h-4" />
                        </button>
                      </div>
                    </div>
                  );
                })}
              </div>
              <p className="text-center text-sm">
                <span className={pointsRemaining === 0 ? 'text-green-400' : 'text-amber-400'}>
                  {pointsRemaining} point{pointsRemaining !== 1 ? 's' : ''} remaining
                </span>
              </p>
            </>
          )}

          {mode === 'feat' && !selectedFeat && (
            <>
              <div className="relative">
                <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-500" />
                <input
                  value={search} onChange={e => setSearch(e.target.value)}
                  placeholder="Search feats..."
                  className="w-full bg-gray-800 border border-gray-700 rounded-lg pl-9 pr-4 py-2 text-white text-sm placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-indigo-500"
                  autoFocus
                />
              </div>
              {loading ? (
                <p className="text-gray-500 text-sm text-center py-4">Loading feats...</p>
              ) : (
                <div className="space-y-1 max-h-[50vh] overflow-y-auto">
                  {filteredFeats.map(({ feat, eligible, reason }) => (
                    <button
                      key={feat.id}
                      onClick={() => eligible && selectFeat(feat)}
                      disabled={!eligible}
                      className={`w-full text-left px-3 py-2 rounded-lg transition-colors ${
                        eligible
                          ? 'hover:bg-gray-800 text-white cursor-pointer'
                          : 'text-gray-600 cursor-not-allowed'
                      }`}
                    >
                      <div className="flex items-center justify-between">
                        <div>
                          <span className="text-sm font-medium">{feat.name}</span>
                          <span className="text-xs text-gray-500 ml-2">{feat.source}</span>
                        </div>
                        {!eligible && <Lock className="w-3.5 h-3.5 text-gray-600" />}
                      </div>
                      {!eligible && reason && (
                        <p className="text-xs text-red-400/70 mt-0.5">{reason}</p>
                      )}
                      {eligible && feat.abilityScoreIncrease && (
                        <p className="text-xs text-green-400/70 mt-0.5">
                          {formatAsiPreview(feat)}
                        </p>
                      )}
                    </button>
                  ))}
                </div>
              )}
            </>
          )}

          {mode === 'feat' && selectedFeat && (
            <>
              <div className="flex items-center gap-2">
                <button onClick={() => { setSelectedFeat(null); resetFeatChoices(); }}
                  className="text-gray-400 hover:text-white text-sm">&larr; Back</button>
                <h3 className="text-white font-bold">{selectedFeat.name}</h3>
                <span className="text-xs text-gray-500">{selectedFeat.source}</span>
              </div>

              {/* Description toggle */}
              <button onClick={() => setShowDescription(!showDescription)}
                className="flex items-center gap-1 text-xs text-gray-400 hover:text-gray-300">
                {showDescription ? <ChevronUp className="w-3 h-3" /> : <ChevronDown className="w-3 h-3" />}
                {showDescription ? 'Hide' : 'Show'} description
              </button>
              {showDescription && selectedFeat.description && (
                <div className="bg-gray-800/50 rounded-lg p-3 max-h-40 overflow-y-auto">
                  <FormattedDescription text={selectedFeat.description} className="text-xs [&_p]:text-gray-300" />
                </div>
              )}

              {/* Effects summary */}
              <EffectsSummary feat={selectedFeat} effects={selectedEffects} asi={selectedAsi} />

              {/* Choice forms */}
              <div className="space-y-3">
                {choices?.ability && selectedAsi?.choose && (
                  <ChoiceSection title={`Choose ability score +${selectedAsi.choose.amount}`}>
                    <div className="flex flex-wrap gap-2">
                      {selectedAsi.choose.from.map(ab => (
                        <button key={ab} onClick={() => setAbilityChoice(ab)}
                          className={`px-3 py-1.5 rounded-lg text-sm transition-colors ${
                            abilityChoice === ab ? 'bg-indigo-600 text-white' : 'bg-gray-800 text-gray-300 hover:bg-gray-700'
                          }`}>
                          {ab} ({(character[ab.toLowerCase() as keyof PlayerCharacter] as number) || '?'})
                        </button>
                      ))}
                    </div>
                  </ChoiceSection>
                )}

                {choices?.resistance && (
                  <ChoiceSection title="Choose damage resistance">
                    <div className="flex flex-wrap gap-2">
                      {getResistanceOptions(selectedEffects!).map(r => (
                        <button key={r} onClick={() => setResistanceChoice(r)}
                          className={`px-3 py-1.5 rounded-lg text-sm capitalize transition-colors ${
                            resistanceChoice === r ? 'bg-indigo-600 text-white' : 'bg-gray-800 text-gray-300 hover:bg-gray-700'
                          }`}>{r}</button>
                      ))}
                    </div>
                  </ChoiceSection>
                )}

                {choices && choices.skill > 0 && (
                  <ChoiceSection title={`Choose ${choices.skill} skill proficiency${choices.skill > 1 ? 'ies' : ''}`}>
                    <div className="flex flex-wrap gap-1.5">
                      {getSkillOptions(selectedEffects!, proficientSkills).map(skill => (
                        <button key={skill} onClick={() => toggleChoice(skill, skillChoices, setSkillChoices, choices.skill)}
                          className={`px-2 py-1 rounded text-xs transition-colors ${
                            skillChoices.includes(skill) ? 'bg-indigo-600 text-white' : 'bg-gray-800 text-gray-300 hover:bg-gray-700'
                          }`}>{skill}</button>
                      ))}
                    </div>
                  </ChoiceSection>
                )}

                {choices?.savingThrow && (
                  <ChoiceSection title="Choose saving throw proficiency">
                    <div className="flex flex-wrap gap-2">
                      {ABILITIES.map(({ key, label }) => (
                        <button key={key} onClick={() => setSavingThrowChoice(key)}
                          className={`px-3 py-1.5 rounded-lg text-sm transition-colors ${
                            savingThrowChoice === key ? 'bg-indigo-600 text-white' : 'bg-gray-800 text-gray-300 hover:bg-gray-700'
                          }`}>{label}</button>
                      ))}
                    </div>
                  </ChoiceSection>
                )}

                {choices && choices.expertise > 0 && (
                  <ChoiceSection title={`Choose ${choices.expertise} skill${choices.expertise > 1 ? 's' : ''} for expertise`}>
                    <div className="flex flex-wrap gap-1.5">
                      {proficientSkills.filter(s => !existingExpertise.includes(s)).map(skill => (
                        <button key={skill} onClick={() => toggleChoice(skill, expertiseChoices, setExpertiseChoices, choices.expertise)}
                          className={`px-2 py-1 rounded text-xs transition-colors ${
                            expertiseChoices.includes(skill) ? 'bg-indigo-600 text-white' : 'bg-gray-800 text-gray-300 hover:bg-gray-700'
                          }`}>{skill}</button>
                      ))}
                    </div>
                  </ChoiceSection>
                )}

                {choices && choices.tool > 0 && (
                  <ChoiceSection title={`Enter ${choices.tool} tool proficiency${choices.tool > 1 ? 'ies' : ''}`}>
                    {Array.from({ length: choices.tool }).map((_, i) => (
                      <input key={i} value={toolChoices[i] || ''} onChange={e => {
                        const updated = [...toolChoices];
                        updated[i] = e.target.value;
                        setToolChoices(updated.filter(Boolean));
                      }}
                      placeholder={`Tool ${i + 1}...`}
                      className="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-1.5 text-white text-sm placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-indigo-500 mb-1" />
                    ))}
                  </ChoiceSection>
                )}

                {choices && choices.language > 0 && (
                  <ChoiceSection title={`Enter ${choices.language} language${choices.language > 1 ? 's' : ''}`}>
                    {Array.from({ length: choices.language }).map((_, i) => (
                      <input key={i} value={languageChoices[i] || ''} onChange={e => {
                        const updated = [...languageChoices];
                        updated[i] = e.target.value;
                        setLanguageChoices(updated.filter(Boolean));
                      }}
                      placeholder={`Language ${i + 1}...`}
                      className="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-1.5 text-white text-sm placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-indigo-500 mb-1" />
                    ))}
                  </ChoiceSection>
                )}

                {choices && choices.weapon > 0 && (
                  <ChoiceSection title={`Enter ${choices.weapon} weapon proficiency${choices.weapon > 1 ? 'ies' : ''}`}>
                    {Array.from({ length: choices.weapon }).map((_, i) => (
                      <input key={i} value={weaponChoices[i] || ''} onChange={e => {
                        const updated = [...weaponChoices];
                        updated[i] = e.target.value;
                        setWeaponChoices(updated.filter(Boolean));
                      }}
                      placeholder={`Weapon ${i + 1}...`}
                      className="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-1.5 text-white text-sm placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-indigo-500 mb-1" />
                    ))}
                  </ChoiceSection>
                )}

                {choices && choices.optFeature > 0 && optionalFeatures.length > 0 && (
                  <ChoiceSection title={`Choose ${choices.optFeature} option${choices.optFeature > 1 ? 's' : ''}`}>
                    <div className="space-y-1 max-h-40 overflow-y-auto">
                      {optionalFeatures.map(of => (
                        <button key={of.id}
                          onClick={() => toggleChoice(of.id, selectedOptFeatures, setSelectedOptFeatures, choices.optFeature)}
                          className={`w-full text-left px-3 py-2 rounded-lg text-sm transition-colors ${
                            selectedOptFeatures.includes(of.id) ? 'bg-indigo-600 text-white' : 'bg-gray-800 text-gray-300 hover:bg-gray-700'
                          }`}>
                          <div className="flex items-center justify-between">
                            <span className="font-medium">{of.name}</span>
                            {selectedOptFeatures.includes(of.id) && <Check className="w-4 h-4" />}
                          </div>
                          {of.description && (
                            <p className="text-xs text-gray-400 mt-0.5 line-clamp-2">{of.description}</p>
                          )}
                        </button>
                      ))}
                    </div>
                  </ChoiceSection>
                )}
                {/* Spell picker for spell-granting feats */}
                {featSpellOptions && (
                  <ChoiceSection title="Feat Spells">
                    {/* Option selector for multi-option feats (e.g. Magic Initiate) */}
                    {featSpellOptions.length > 1 && (
                      <div className="mb-3">
                        <p className="text-xs text-gray-500 mb-1.5">Choose a spell list:</p>
                        <div className="flex flex-wrap gap-1.5">
                          {featSpellOptions.map((opt, i) => (
                            <button key={i} onClick={() => { setSelectedSpellOption(i); setSelectedSpellIds([]); setActiveSpellSlot(null); }}
                              className={`px-3 py-1.5 rounded-lg text-sm transition-colors ${
                                selectedSpellOption === i ? 'bg-indigo-600 text-white' : 'bg-gray-800 text-gray-300 hover:bg-gray-700'
                              }`}>{opt.name}</button>
                          ))}
                        </div>
                      </div>
                    )}

                    {/* Fixed spells (auto-included) */}
                    {spellSlots.fixed.length > 0 && (
                      <div className="mb-2">
                        <p className="text-xs text-gray-500 mb-1">Granted automatically:</p>
                        {spellSlots.fixed.map((s, i) => (
                          <div key={i} className="flex items-center gap-2 px-3 py-1.5 bg-gray-800/60 rounded text-sm text-green-300">
                            <Check className="w-3.5 h-3.5" /> {s}
                          </div>
                        ))}
                      </div>
                    )}

                    {/* Choosable spell slots */}
                    {spellSlots.choices.map((filter, i) => {
                      const selectedSpell = selectedSpellIds[i];
                      const selectedSpellData = spellSearchResults.find(s => s.id === selectedSpell);
                      const isActive = activeSpellSlot?.index === i;
                      const levelLabel = filter.level === 0 ? 'cantrip' : filter.level > 0 ? `level ${filter.level} spell` : 'spell';
                      const filterDesc = filter.schools.length > 0
                        ? `${filter.schools.join(' or ')} ${levelLabel}`
                        : filter.classes.length > 0
                          ? `${filter.classes.join('/')} ${levelLabel}`
                          : filter.fromList ? `${levelLabel} from list` : levelLabel;

                      return (
                        <div key={i} className="mb-2">
                          <button
                            onClick={() => {
                              if (isActive) { setActiveSpellSlot(null); return; }
                              setActiveSpellSlot({ type: filter.level === 0 ? 'cantrip' : 'spell', index: i, filter });
                              setSpellSearchQuery('');
                              searchFeatSpells('', filter);
                            }}
                            className={`w-full text-left px-3 py-2 rounded-lg text-sm transition-colors border ${
                              selectedSpell
                                ? 'border-indigo-600 bg-indigo-900/30 text-white'
                                : 'border-gray-700 bg-gray-800 text-gray-300 hover:bg-gray-700'
                            }`}
                          >
                            {selectedSpell && selectedSpellData
                              ? <span className="flex items-center gap-2"><Check className="w-3.5 h-3.5 text-indigo-400" /> {selectedSpellData.name}</span>
                              : <span>Choose a {filterDesc}...</span>
                            }
                          </button>

                          {isActive && (
                            <div className="mt-1 border border-gray-700 rounded-lg bg-gray-850 overflow-hidden">
                              <div className="p-2">
                                <div className="relative">
                                  <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-gray-500" />
                                  <input
                                    value={spellSearchQuery}
                                    onChange={e => {
                                      setSpellSearchQuery(e.target.value);
                                      searchFeatSpells(e.target.value, filter);
                                    }}
                                    placeholder={`Search ${filterDesc}...`}
                                    className="w-full bg-gray-800 border border-gray-700 rounded pl-8 pr-3 py-1.5 text-white text-xs placeholder-gray-500 focus:outline-none focus:ring-1 focus:ring-indigo-500"
                                    autoFocus
                                  />
                                </div>
                              </div>
                              <div className="max-h-40 overflow-y-auto">
                                {spellSearchLoading ? (
                                  <p className="text-gray-500 text-xs text-center py-3">Searching...</p>
                                ) : spellSearchResults.length === 0 ? (
                                  <p className="text-gray-500 text-xs text-center py-3">No spells found</p>
                                ) : (
                                  spellSearchResults.map(spell => (
                                    <button key={spell.id}
                                      onClick={() => {
                                        const updated = [...selectedSpellIds];
                                        updated[i] = spell.id;
                                        setSelectedSpellIds(updated);
                                        setActiveSpellSlot(null);
                                      }}
                                      className={`w-full text-left px-3 py-1.5 text-xs hover:bg-gray-800 transition-colors ${
                                        selectedSpell === spell.id ? 'bg-indigo-900/30 text-indigo-300' : 'text-gray-300'
                                      }`}
                                    >
                                      <span className="font-medium">{spell.name}</span>
                                      <span className="text-gray-500 ml-2">
                                        {spell.level === 0 ? 'Cantrip' : `Level ${spell.level}`}
                                        {spell.school ? ` · ${spell.school}` : ''}
                                      </span>
                                    </button>
                                  ))
                                )}
                              </div>
                            </div>
                          )}
                        </div>
                      );
                    })}
                  </ChoiceSection>
                )}
              </div>
            </>
          )}
        </div>

        <div className="px-5 py-4 border-t border-gray-800 flex justify-end gap-3 shrink-0">
          <button onClick={onClose} className="px-4 py-2 text-sm text-gray-400 hover:text-white transition-colors">
            Skip
          </button>
          <button
            onClick={handleSubmit}
            disabled={!canSubmit || submitting}
            className="px-5 py-2 bg-indigo-600 hover:bg-indigo-500 text-white text-sm font-medium rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {submitting ? 'Applying...' : 'Apply'}
          </button>
        </div>
      </div>
    </div>
  );
}

function ChoiceSection({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="bg-gray-800/30 rounded-lg p-3">
      <p className="text-xs text-gray-400 font-medium mb-2">{title}</p>
      {children}
    </div>
  );
}

function EffectsSummary({ feat, effects, asi }: { feat: Feat; effects: ReturnType<typeof parseFeatEffects>; asi: ReturnType<typeof parseAbilityScoreIncrease> }) {
  const tags: string[] = [];
  if (asi) {
    if (Object.keys(asi.fixed).length > 0) {
      tags.push(...Object.entries(asi.fixed).map(([k, v]) => `+${v} ${k}`));
    }
    if (asi.choose) tags.push(`+${asi.choose.amount} to choice`);
  }
  if (effects) {
    if (effects.resistances) tags.push('Resistance');
    if (effects.armorProficiencies) tags.push('Armor Prof.');
    if (effects.weaponProficiencies) tags.push('Weapon Prof.');
    if (effects.toolProficiencies) tags.push('Tool Prof.');
    if (effects.skillProficiencies) tags.push('Skill Prof.');
    if (effects.languageProficiencies) tags.push('Language');
    if (effects.savingThrowProficiencies) tags.push('Save Prof.');
    if (effects.expertise) tags.push('Expertise');
    if (effects.speedBonus) tags.push(`+${effects.speedBonus} Speed`);
    if (effects.initiativeBonus) tags.push(`+${effects.initiativeBonus} Initiative`);
    if (effects.hpPerLevel) tags.push(`+${effects.hpPerLevel} HP/level`);
    if (effects.passivePerceptionBonus) tags.push(`+${effects.passivePerceptionBonus} Passive Perc.`);
    if (effects.resource) tags.push(effects.resource.name);
    if (effects.optionalFeatureProgression) tags.push('Feature Choice');
    if (effects.skillToolLanguageProficiencies) tags.push('Skill/Tool Choice');
  }
  if (feat.grantsFeatures) tags.push('Spells');

  if (tags.length === 0) return null;

  return (
    <div className="flex flex-wrap gap-1.5">
      {tags.map(tag => (
        <span key={tag} className="px-2 py-0.5 bg-indigo-900/40 text-indigo-300 text-xs rounded-full">{tag}</span>
      ))}
    </div>
  );
}

function toggleChoice(value: string, current: string[], setter: (v: string[]) => void, max: number) {
  if (current.includes(value)) {
    setter(current.filter(v => v !== value));
  } else if (current.length < max) {
    setter([...current, value]);
  }
}

function getCharacterFeatNames(character: PlayerCharacter): string[] {
  if (!character.features) return [];
  try {
    const features: Array<{ name: string; source: string }> = JSON.parse(character.features);
    return features.filter(f => f.source === 'Feat').map(f => f.name);
  } catch { return []; }
}

function formatAsiPreview(feat: Feat): string {
  const asi = parseAbilityScoreIncrease(feat);
  if (!asi) return '';
  const parts: string[] = [];
  for (const [k, v] of Object.entries(asi.fixed)) {
    parts.push(`+${v} ${k}`);
  }
  if (asi.choose) {
    parts.push(`+${asi.choose.amount} ${asi.choose.from.join('/')}`);
  }
  return parts.join(', ');
}

function getResistanceOptions(effects: ReturnType<typeof parseFeatEffects>): string[] {
  if (!effects?.resistances) return [];
  for (const r of effects.resistances) {
    if (typeof r === 'object' && r.choose?.from) {
      return r.choose.from;
    }
  }
  return [];
}

function stripSpellRef(s: string): string {
  return s.replace(/#c$/, '').replace(/#\d+$/, '').replace(/\|.*$/, '')
    .split(/\s+/).map(w => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase()).join(' ');
}

interface SpellFilter {
  level: number; // 0=cantrip, -1=any level (use classes/schools to filter)
  classes: string[];
  schools: string[];
  fromList?: string[];
}

function parseChooseString(choose: string): Partial<SpellFilter> {
  const parts = choose.split('|');
  const result: Partial<SpellFilter> = {};
  for (const part of parts) {
    const [key, val] = part.split('=');
    if (key === 'level') result.level = parseInt(val) || 0;
    if (key === 'class') result.classes = val.split(';').map(c => c.trim().charAt(0).toUpperCase() + c.trim().slice(1).toLowerCase());
    if (key === 'school') {
      result.schools = val.split(';').map(s => SCHOOL_ABBREV[s.trim()] || s.trim()).filter(Boolean);
    }
  }
  return result;
}

function getSkillOptions(effects: ReturnType<typeof parseFeatEffects>, proficientSkills: string[]): string[] {
  if (!effects) return ALL_SKILLS;
  const skillProfs = effects.skillProficiencies || effects.skillToolLanguageProficiencies;
  if (!skillProfs) return ALL_SKILLS;
  for (const entry of skillProfs) {
    if (entry?.choose?.from) {
      return entry.choose.from.map((s: string) => s.charAt(0).toUpperCase() + s.slice(1));
    }
    if (entry?.choose) {
      for (const c of Array.isArray(entry.choose) ? entry.choose : [entry.choose]) {
        if (c.from?.includes('anySkill')) {
          return ALL_SKILLS.filter(s => !proficientSkills.includes(s));
        }
      }
    }
  }
  return ALL_SKILLS.filter(s => !proficientSkills.includes(s));
}
