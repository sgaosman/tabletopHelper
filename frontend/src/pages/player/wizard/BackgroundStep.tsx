import { Search } from 'lucide-react';
import type { Background, Feat, Spell } from '../../../types/reference';
import { safeJsonParse } from '../../../utils/dndRules';
import type { ParsedFeatOption } from '../../../utils/featSpellParser';
import type { ChoiceReq, ProfEntry } from './types';
import { formatProfEntry, formatProficiencies, hasChooseSet } from './types';

export interface BackgroundStepProps {
  filteredBackgrounds: Background[];
  bgSearch: string;
  setBgSearch: (v: string) => void;
  selectedBackground: Background | null;
  setSelectedBackground: (bg: Background | null) => void;
  bgChoiceReqs: ChoiceReq[];
  bgProfChoices: Record<string, string[]>;
  handleBgProfChoice: (key: string, value: string, count: number) => void;
  bgSetChoices: Record<string, number | null>;
  handleBgSetChoice: (key: string, index: number) => void;
  bgChoicesComplete: boolean;
  bgSkillConflicts: string[];
  bgFeatNames: string[];
  selectedBgFeat: string | null;
  setSelectedBgFeat: (v: string | null) => void;
  selectedFeatObj: Feat | null;
  parsedFeatOptions: ParsedFeatOption[];
  selectedFeatOptionIdx: number | null;
  setSelectedFeatOptionIdx: (v: number | null) => void;
  selectedFeatOption: ParsedFeatOption | null;
  selectedFeatAbility: string | null;
  setSelectedFeatAbility: (v: string | null) => void;
  selectedFeatAsiAbility: string | null;
  setSelectedFeatAsiAbility: (v: string | null) => void;
  featAsi: { fixed: Record<string, number>; choose?: { from: string[]; amount: number } } | null;
  featConfigComplete: boolean;
  setFeatCantrips: (s: Spell[]) => void;
  setFeatSpells: (s: Spell[]) => void;
}

export default function BackgroundStep({
  filteredBackgrounds, bgSearch, setBgSearch,
  selectedBackground, setSelectedBackground,
  bgChoiceReqs, bgProfChoices, handleBgProfChoice,
  bgSetChoices, handleBgSetChoice, bgChoicesComplete,
  bgSkillConflicts,
  bgFeatNames, selectedBgFeat, setSelectedBgFeat,
  selectedFeatObj, parsedFeatOptions,
  selectedFeatOptionIdx, setSelectedFeatOptionIdx,
  selectedFeatOption,
  selectedFeatAbility, setSelectedFeatAbility,
  selectedFeatAsiAbility, setSelectedFeatAsiAbility,
  featAsi, featConfigComplete,
  setFeatCantrips, setFeatSpells,
}: BackgroundStepProps) {
  return (
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
