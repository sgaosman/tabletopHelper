import { Search } from 'lucide-react';
import type { Background } from '../../../types/reference';
import { safeJsonParse } from '../../../utils/dndRules';
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
  /** The feats the background grants, one of which the character takes on its sheet after creation (D159) */
  bgFeatNames: string[];
}

export default function BackgroundStep({
  filteredBackgrounds, bgSearch, setBgSearch,
  selectedBackground, setSelectedBackground,
  bgChoiceReqs, bgProfChoices, handleBgProfChoice,
  bgSetChoices, handleBgSetChoice, bgChoicesComplete,
  bgSkillConflicts, bgFeatNames,
}: BackgroundStepProps) {
  return (
    <div className="space-y-4">
      <h2 className="font-heading text-[17px] font-semibold text-ink">Choose a Background</h2>
      <div className="relative max-w-md">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-faint" />
        <input
          type="text"
          value={bgSearch}
          onChange={e => setBgSearch(e.target.value)}
          placeholder="Search backgrounds..."
          className="w-full pl-9 pr-4 py-2 bg-card border border-rule font-body text-[16px] font-semibold text-ink placeholder-faint focus:border-muted focus:outline-none"
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
              className={`p-4 border text-left transition-colors ${
                selectedBackground?.id === bg.id
                  ? 'bg-page-alt border-ink'
                  : 'bg-card border-rule hover:border-muted'
              }`}
            >
              <div className="flex items-start justify-between">
                <h3 className="font-heading text-[15px] font-semibold text-ink">{bg.name}</h3>
                <span className="font-body text-[13px] text-faint">{bg.source}</span>
              </div>
              <div className="mt-1 space-y-0.5">
                {skills.length > 0 && <p className="font-body text-[13px] font-semibold text-cls-druid">Skills: {skills.join(', ')}</p>}
                {tools.length > 0 && <p className="font-body text-[13px] font-semibold text-muted">Tools: {tools.join(', ')}</p>}
                {langs.length > 0 && <p className="font-body text-[13px] font-semibold text-muted">Languages: {langs.join(', ')}</p>}
                {feats.length > 0 && <p className="font-body text-[13px] font-semibold text-buff">Feat: {feats.join(', ')}</p>}
              </div>
            </button>
          );
        })}
      </div>
      {selectedBackground && (
        <BackgroundDetail bg={selectedBackground} />
      )}
      {selectedBackground && bgChoiceReqs.length > 0 && (
        <div className="bg-card border border-rule p-4 mt-4 space-y-4">
          <h3 className="font-heading text-[15px] font-semibold text-ink">Choose Your Proficiencies</h3>
          {bgChoiceReqs.map(req => (
            <div key={req.key}>
              {req.type === 'choose' && (
                <div>
                  <p className="font-body text-[13px] font-semibold text-muted mb-2">
                    {req.label}: choose {req.count} from the options below
                  </p>
                  <div className="flex gap-1.5 flex-wrap">
                    {req.from.map(option => (
                      <button
                        key={option}
                        onClick={() => handleBgProfChoice(req.key, option, req.count)}
                        className={`px-3 py-1.5 font-heading text-[11px] font-semibold tracking-[0.02em] transition-colors border ${
                          (bgProfChoices[req.key] ?? []).includes(option)
                            ? 'bg-ink text-card border-ink'
                            : 'bg-page text-muted border-rule hover:border-muted'
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
                  <p className="font-body text-[13px] font-semibold text-muted mb-2">
                    {req.label}: choose one set
                  </p>
                  <div className="grid grid-cols-2 md:grid-cols-3 gap-2">
                    {req.sets.map((set, i) => (
                      <button
                        key={i}
                        onClick={() => handleBgSetChoice(req.key, i)}
                        className={`px-3 py-2 border font-body text-[13px] font-semibold text-left transition-colors ${
                          bgSetChoices[req.key] === i
                            ? 'bg-page-alt border-ink text-ink'
                            : 'bg-page border-rule text-muted hover:border-muted'
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
            <p className="font-body text-[13px] font-semibold text-hp-wounded">Complete all selections to continue</p>
          )}
        </div>
      )}
      {selectedBackground && bgSkillConflicts.length > 0 && (
        <div className="bg-buff-bg border border-buff-border p-4 mt-4">
          <h3 className="font-heading text-[15px] font-semibold text-hp-wounded">Skill Proficiency Overlap</h3>
          <p className="font-body text-[13px] font-semibold text-muted mt-1">
            The following background skill{bgSkillConflicts.length > 1 ? 's are' : ' is'} already granted by your race or class: <span className="font-semibold text-buff">{bgSkillConflicts.join(', ')}</span>.
            Per PHB rules, you may choose a different skill proficiency instead. You can adjust this from the character sheet after creation.
          </p>
        </div>
      )}
      {/* The feat is a Pending Choice, made on the sheet so its effects apply (D159) */}
      {selectedBackground && bgFeatNames.length > 0 && (
        <div className="bg-card border border-buff-border p-4 mt-4">
          <h3 className="font-heading text-[15px] font-semibold text-ink">Background Feat</h3>
          <p className="font-body text-[13px] font-semibold text-muted mt-1">
            {bgFeatNames.length > 1
              ? `This background grants one of these feats: ${bgFeatNames.join(', ')}.`
              : `This background grants ${bgFeatNames[0]}.`}
            {' '}You'll choose this feat on your character sheet after creation.
          </p>
        </div>
      )}
    </div>
  );
}

function ChooseSetPicker({ label, sets, colorClass }: { label: string; sets: ProfEntry[][]; colorClass: string }) {
  return (
    <div className="md:col-span-2">
      <p className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint mb-1">{label} (choose one set)</p>
      <div className="grid grid-cols-2 md:grid-cols-3 gap-2">
        {sets.map((set, i) => (
          <div key={i} className="bg-page border border-rule px-3 py-1.5">
            <p className={`${colorClass} font-body text-[13px] font-semibold`}>{set.map(formatProfEntry).filter(Boolean).join(', ')}</p>
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
    <div className="bg-card border border-rule p-4 mt-4">
      <h3 className="font-heading text-[15px] font-semibold text-ink mb-3">{bg.name} — Summary</h3>
      <div className="grid grid-cols-1 md:grid-cols-2 gap-x-6 gap-y-2">
        {!skillSets && skills.length > 0 && (
          <div>
            <p className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint">Skill Proficiencies</p>
            <p className="font-body text-[15px] font-semibold text-cls-druid">{skills.join(', ')}</p>
          </div>
        )}
        {skillSets && <ChooseSetPicker label="Skill Proficiencies" sets={skillSets} colorClass="text-cls-druid" />}
        {!toolSets && tools.length > 0 && (
          <div>
            <p className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint">Tool Proficiencies</p>
            <p className="font-body text-[15px] font-semibold text-muted">{tools.join(', ')}</p>
          </div>
        )}
        {toolSets && <ChooseSetPicker label="Tool Proficiencies" sets={toolSets} colorClass="text-muted" />}
        {!langSets && langs.length > 0 && (
          <div>
            <p className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint">Languages</p>
            <p className="font-body text-[15px] font-semibold text-muted">{langs.join(', ')}</p>
          </div>
        )}
        {langSets && <ChooseSetPicker label="Languages" sets={langSets} colorClass="text-muted" />}
        {feats.length > 0 && (
          <div>
            <p className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint">Feat</p>
            <p className="font-body text-[15px] font-semibold text-buff">{feats.join(', ')}</p>
          </div>
        )}
        {allEquip.length > 0 && (
          <div className="md:col-span-2">
            <p className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint">Equipment</p>
            <p className="font-body text-[15px] font-semibold text-muted">{allEquip.join('; ')}</p>
          </div>
        )}
      </div>
      {spells.length > 0 && (
        <div className="mt-3">
          <p className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint mb-1">Expanded Spell List</p>
          <div className="grid grid-cols-2 md:grid-cols-5 gap-2">
            {spells.map((levelMap, i) =>
              Object.entries(levelMap).map(([level, names]) => (
                <div key={`${i}-${level}`}>
                  <p className="font-body text-[13px] text-faint">{LEVEL_LABELS[level] ?? level} level</p>
                  <p className="font-body text-[13px] font-semibold text-cls-sorcerer">{(names as string[]).join(', ')}</p>
                </div>
              ))
            )}
          </div>
        </div>
      )}
      {feature && (
        <div className="mt-3 pt-3 border-t border-rule">
          <p className="font-body text-[15px] font-semibold text-ink">Feature: {feature.name}</p>
          <p className="font-body text-[13px] font-semibold text-faint mt-1 line-clamp-4">{feature.description}</p>
        </div>
      )}
    </div>
  );
}
