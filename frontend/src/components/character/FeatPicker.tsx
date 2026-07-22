import { useState, useMemo, useEffect, useCallback } from 'react';
import { Search, Check, ChevronDown, ChevronUp, Lock } from 'lucide-react';
import { getFeats, getOptionalFeatures, searchSpells } from '../../api/referenceApi';
import type { PlayerCharacter, AsiChoice } from '../../types/character';
import type { Feat, OptionalFeature, Spell } from '../../types/reference';
import { checkFeatPrerequisites, parseFeatEffects, parseAbilityScoreIncrease } from '../../utils/featPrerequisites';
import FormattedDescription from '../FormattedDescription';
import { ALL_SKILLS } from '../../utils/dndRules';

const ABILITIES = [
  { key: 'strength', label: 'STR', full: 'Strength' },
  { key: 'dexterity', label: 'DEX', full: 'Dexterity' },
  { key: 'constitution', label: 'CON', full: 'Constitution' },
  { key: 'intelligence', label: 'INT', full: 'Intelligence' },
  { key: 'wisdom', label: 'WIS', full: 'Wisdom' },
  { key: 'charisma', label: 'CHA', full: 'Charisma' },
] as const;

const SCHOOL_ABBREV: Record<string, string> = {
  A: 'Abjuration', C: 'Conjuration', D: 'Divination', E: 'Enchantment',
  V: 'Evocation', I: 'Illusion', N: 'Necromancy', T: 'Transmutation',
};

interface SpellFilter {
  level: number;
  classes: string[];
  schools: string[];
  fromList?: string[];
}

interface FeatPickerProps {
  character: PlayerCharacter;
  onSelectionChange: (selection: AsiChoice | null) => void;
}

export default function FeatPicker({ character, onSelectionChange }: FeatPickerProps) {
  const [feats, setFeats] = useState<Feat[]>([]);
  const [loading, setLoading] = useState(false);
  const [search, setSearch] = useState('');
  const [selectedFeat, setSelectedFeat] = useState<Feat | null>(null);
  const [showDescription, setShowDescription] = useState(false);

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

  const [selectedSpellIds, setSelectedSpellIds] = useState<string[]>([]);
  const [fixedSpellIds, setFixedSpellIds] = useState<string[]>([]);
  const [spellSearchResults, setSpellSearchResults] = useState<Spell[]>([]);
  const [spellSearchQuery, setSpellSearchQuery] = useState('');
  const [spellSearchLoading, setSpellSearchLoading] = useState(false);
  const [activeSpellSlot, setActiveSpellSlot] = useState<{ type: 'cantrip' | 'spell'; index: number; filter: SpellFilter } | null>(null);
  const [selectedSpellOption, setSelectedSpellOption] = useState<number>(0);

  useEffect(() => {
    if (feats.length === 0) {
      setLoading(true);
      getFeats().then(setFeats).finally(() => setLoading(false));
    }
  }, []);

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

  const featSpellOptions = useMemo((): { name: string }[] | null => {
    if (!selectedFeat?.grantsFeatures) return null;
    const raw = typeof selectedFeat.grantsFeatures === 'string'
      ? JSON.parse(selectedFeat.grantsFeatures) : selectedFeat.grantsFeatures;
    if (!Array.isArray(raw) || raw.length === 0) return null;
    return raw.map((o: any, i: number) => ({ name: o.name || `Option ${i + 1}` }));
  }, [selectedFeat?.id]);

  const spellSlots = useMemo(() => {
    if (!selectedFeat?.grantsFeatures) return { fixed: [] as string[], choices: [] as SpellFilter[] };
    const raw = typeof selectedFeat.grantsFeatures === 'string'
      ? JSON.parse(selectedFeat.grantsFeatures) : selectedFeat.grantsFeatures;
    if (!Array.isArray(raw)) return { fixed: [] as string[], choices: [] as SpellFilter[] };
    const option = raw[selectedSpellOption] || raw[0];
    if (!option) return { fixed: [] as string[], choices: [] as SpellFilter[] };

    const fixed: string[] = [];
    const choices: SpellFilter[] = [];

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

  useEffect(() => {
    if (spellSlots.fixed.length === 0) { setFixedSpellIds([]); return; }
    const lookups = spellSlots.fixed.map(name =>
      searchSpells({ name, size: 1 }).then(r => r.content[0]?.id || null)
    );
    Promise.all(lookups).then(ids => setFixedSpellIds(ids.filter(Boolean) as string[]));
  }, [spellSlots.fixed.join(',')]);

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

  useEffect(() => {
    if (!canSubmitFeat || !selectedFeat) {
      onSelectionChange(null);
      return;
    }
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
    onSelectionChange(asi);
  }, [canSubmitFeat, selectedFeat, abilityChoice, resistanceChoice, skillChoices,
      savingThrowChoice, expertiseChoices, toolChoices, languageChoices, weaponChoices,
      selectedOptFeatures, fixedSpellIds, selectedSpellIds]);

  const proficientSkills = useMemo(() => {
    if (!character.skillProficiencies) return [];
    try { return JSON.parse(character.skillProficiencies) as string[]; } catch { return []; }
  }, [character.skillProficiencies]);

  const existingExpertise = useMemo(() => {
    if (!character.skillExpertises) return [];
    try { return JSON.parse(character.skillExpertises) as string[]; } catch { return []; }
  }, [character.skillExpertises]);

  if (!selectedFeat) {
    return (
      <>
        <div className="relative">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-faint" />
          <input
            value={search} onChange={e => setSearch(e.target.value)}
            placeholder="Search feats..."
            className="w-full bg-page-alt border border-rule pl-9 pr-4 py-2 font-body text-[14px] font-medium text-ink placeholder-faint focus:outline-none focus:border-muted"
            autoFocus
          />
        </div>
        {loading ? (
          <p className="font-body text-[13px] text-faint text-center py-4">Loading feats...</p>
        ) : (
          <div className="space-y-1 max-h-[50vh] overflow-y-auto">
            {filteredFeats.map(({ feat, eligible, reason }) => (
              <button
                key={feat.id}
                onClick={() => eligible && selectFeat(feat)}
                disabled={!eligible}
                className={`w-full text-left px-3 py-2 transition-colors ${
                  eligible
                    ? 'hover:bg-page-alt text-ink cursor-pointer'
                    : 'text-faint cursor-not-allowed'
                }`}
              >
                <div className="flex items-center justify-between">
                  <div>
                    <span className="font-heading text-[14px] font-semibold">{feat.name}</span>
                    <span className="font-body text-[11px] text-faint ml-2">{feat.source}</span>
                  </div>
                  {!eligible && <Lock className="w-3.5 h-3.5 text-faint" />}
                </div>
                {!eligible && reason && (
                  <p className="font-body text-[11px] text-debuff/70 mt-0.5">{reason}</p>
                )}
                {eligible && feat.abilityScoreIncrease && (
                  <p className="font-body text-[11px] text-buff/70 mt-0.5">
                    {formatAsiPreview(feat)}
                  </p>
                )}
              </button>
            ))}
          </div>
        )}
      </>
    );
  }

  return (
    <>
      <div className="flex items-center gap-2">
        <button onClick={() => { setSelectedFeat(null); resetFeatChoices(); onSelectionChange(null); }}
          className="font-body text-[13px] font-medium text-muted hover:text-ink">&larr; Back</button>
        <h3 className="font-heading text-[15px] font-bold text-ink">{selectedFeat.name}</h3>
        <span className="font-body text-[11px] text-faint">{selectedFeat.source}</span>
      </div>

      <button onClick={() => setShowDescription(!showDescription)}
        className="flex items-center gap-1 font-body text-[11px] font-medium text-muted hover:text-ink">
        {showDescription ? <ChevronUp className="w-3 h-3" /> : <ChevronDown className="w-3 h-3" />}
        {showDescription ? 'Hide' : 'Show'} description
      </button>
      {showDescription && selectedFeat.description && (
        <div className="bg-page-alt border border-rule p-3 max-h-40 overflow-y-auto">
          <FormattedDescription text={selectedFeat.description} className="text-xs [&_p]:text-muted" />
        </div>
      )}

      <EffectsSummary feat={selectedFeat} effects={selectedEffects} asi={selectedAsi} />

      <div className="space-y-3">
        {choices?.ability && selectedAsi?.choose && (
          <ChoiceSection title={`Choose ability score +${selectedAsi.choose.amount}`}>
            <div className="flex flex-wrap gap-2">
              {selectedAsi.choose.from.map(ab => (
                <button key={ab} onClick={() => setAbilityChoice(ab)}
                  className={`px-3 py-1.5 font-body text-[13px] font-medium border transition-colors ${
                    abilityChoice === ab ? 'bg-ink text-card border-ink' : 'bg-page border-rule text-muted hover:border-muted'
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
                  className={`px-3 py-1.5 font-body text-[13px] font-medium capitalize border transition-colors ${
                    resistanceChoice === r ? 'bg-ink text-card' : 'bg-page-alt border border-rule text-muted hover:border-muted'
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
                  className={`px-2 py-1 rounded font-heading text-[9px] font-medium tracking-[0.02em] transition-colors ${
                    skillChoices.includes(skill) ? 'bg-ink text-card' : 'bg-page-alt text-muted hover:bg-rule'
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
                  className={`px-3 py-1.5 font-body text-[13px] font-medium transition-colors ${
                    savingThrowChoice === key ? 'bg-ink text-card' : 'bg-page-alt border border-rule text-muted hover:border-muted'
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
                  className={`px-2 py-1 rounded font-heading text-[9px] font-medium tracking-[0.02em] transition-colors ${
                    expertiseChoices.includes(skill) ? 'bg-ink text-card' : 'bg-page-alt text-muted hover:bg-rule'
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
              className="w-full bg-page-alt border border-rule px-3 py-1.5 font-body text-[14px] font-medium text-ink placeholder-faint focus:outline-none focus:border-muted mb-1" />
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
              className="w-full bg-page-alt border border-rule px-3 py-1.5 font-body text-[14px] font-medium text-ink placeholder-faint focus:outline-none focus:border-muted mb-1" />
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
              className="w-full bg-page-alt border border-rule px-3 py-1.5 font-body text-[14px] font-medium text-ink placeholder-faint focus:outline-none focus:border-muted mb-1" />
            ))}
          </ChoiceSection>
        )}

        {choices && choices.optFeature > 0 && optionalFeatures.length > 0 && (
          <ChoiceSection title={`Choose ${choices.optFeature} option${choices.optFeature > 1 ? 's' : ''}`}>
            <div className="space-y-1 max-h-40 overflow-y-auto">
              {optionalFeatures.map(of => (
                <button key={of.id}
                  onClick={() => toggleChoice(of.id, selectedOptFeatures, setSelectedOptFeatures, choices.optFeature)}
                  className={`w-full text-left px-3 py-2 font-body text-[13px] font-medium transition-colors ${
                    selectedOptFeatures.includes(of.id) ? 'bg-ink text-card' : 'bg-page-alt border border-rule text-muted hover:border-muted'
                  }`}>
                  <div className="flex items-center justify-between">
                    <span className="font-medium">{of.name}</span>
                    {selectedOptFeatures.includes(of.id) && <Check className="w-4 h-4" />}
                  </div>
                  {of.description && (
                    <p className="font-body text-[11px] text-muted mt-0.5 line-clamp-2">{of.description}</p>
                  )}
                </button>
              ))}
            </div>
          </ChoiceSection>
        )}

        {featSpellOptions && (
          <ChoiceSection title="Feat Spells">
            {featSpellOptions.length > 1 && (
              <div className="mb-3">
                <p className="font-body text-[11px] text-faint mb-1.5">Choose a spell list:</p>
                <div className="flex flex-wrap gap-1.5">
                  {featSpellOptions.map((opt, i) => (
                    <button key={i} onClick={() => { setSelectedSpellOption(i); setSelectedSpellIds([]); setActiveSpellSlot(null); }}
                      className={`px-3 py-1.5 font-body text-[13px] font-medium transition-colors ${
                        selectedSpellOption === i ? 'bg-ink text-card' : 'bg-page-alt border border-rule text-muted hover:border-muted'
                      }`}>{opt.name}</button>
                  ))}
                </div>
              </div>
            )}

            {spellSlots.fixed.length > 0 && (
              <div className="mb-2">
                <p className="font-body text-[11px] font-medium text-faint mb-1">Granted automatically:</p>
                {spellSlots.fixed.map((s, i) => (
                  <div key={i} className="flex items-center gap-2 px-3 py-1.5 bg-page-alt rounded font-body text-[13px] font-medium text-buff">
                    <Check className="w-3.5 h-3.5" /> {s}
                  </div>
                ))}
              </div>
            )}

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
                    className={`w-full text-left px-3 py-2 font-body text-[13px] font-medium transition-colors border ${
                      selectedSpell
                        ? 'border-2 border-ink bg-page-alt text-ink'
                        : 'border-rule bg-page-alt text-ink hover:bg-rule'
                    }`}
                  >
                    {selectedSpell && selectedSpellData
                      ? <span className="flex items-center gap-2"><Check className="w-3.5 h-3.5 text-ink" /> {selectedSpellData.name}</span>
                      : <span>Choose a {filterDesc}...</span>
                    }
                  </button>

                  {isActive && (
                    <div className="mt-1 border border-rule bg-card overflow-hidden">
                      <div className="p-2">
                        <div className="relative">
                          <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-faint" />
                          <input
                            value={spellSearchQuery}
                            onChange={e => {
                              setSpellSearchQuery(e.target.value);
                              searchFeatSpells(e.target.value, filter);
                            }}
                            placeholder={`Search ${filterDesc}...`}
                            className="w-full bg-page-alt border border-rule pl-8 pr-3 py-1.5 font-body text-[12px] font-medium text-ink placeholder-faint focus:outline-none focus:border-muted"
                            autoFocus
                          />
                        </div>
                      </div>
                      <div className="max-h-40 overflow-y-auto">
                        {spellSearchLoading ? (
                          <p className="font-body text-[11px] font-medium text-faint text-center py-3">Searching...</p>
                        ) : spellSearchResults.length === 0 ? (
                          <p className="font-body text-[11px] font-medium text-faint text-center py-3">No spells found</p>
                        ) : (
                          spellSearchResults.map(spell => (
                            <button key={spell.id}
                              onClick={() => {
                                const updated = [...selectedSpellIds];
                                updated[i] = spell.id;
                                setSelectedSpellIds(updated);
                                setActiveSpellSlot(null);
                              }}
                              className={`w-full text-left px-3 py-1.5 font-body text-[11px] font-medium hover:bg-page-alt transition-colors ${
                                selectedSpell === spell.id ? 'bg-page-alt text-ink' : 'text-muted'
                              }`}
                            >
                              <span className="font-heading text-[11px] font-semibold">{spell.name}</span>
                              <span className="text-faint ml-2">
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
  );
}

function ChoiceSection({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="bg-page-alt p-3">
      <p className="font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-muted mb-2">{title}</p>
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
        <span key={tag} className="px-2 py-0.5 bg-page-alt border border-rule font-heading text-[9px] font-medium tracking-[0.02em] text-ink">{tag}</span>
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
  if (!effects) return [...ALL_SKILLS];
  const skillProfs = effects.skillProficiencies || effects.skillToolLanguageProficiencies;
  if (!skillProfs) return [...ALL_SKILLS];
  for (const entry of skillProfs) {
    if (entry?.choose?.from) {
      return entry.choose.from.map((s: string) => s.charAt(0).toUpperCase() + s.slice(1));
    }
    if (entry?.choose) {
      for (const c of Array.isArray(entry.choose) ? entry.choose : [entry.choose]) {
        if (c.from?.includes('anySkill')) {
          return [...ALL_SKILLS].filter(s => !proficientSkills.includes(s));
        }
      }
    }
  }
  return [...ALL_SKILLS].filter(s => !proficientSkills.includes(s));
}
