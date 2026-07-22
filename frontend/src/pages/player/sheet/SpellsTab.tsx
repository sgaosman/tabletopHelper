import { useState, useEffect, useMemo } from 'react';
import { ArrowLeft, Lock, Search, X, Plus, Check } from 'lucide-react';
import { searchSpells, getFeats } from '../../../api/referenceApi';
import SpellCard from '../../../components/reference/SpellCard';
import type { PlayerCharacter, CharacterUpdateRequest } from '../../../types/character';
import type { Spell, Feat } from '../../../types/reference';
import { CANTRIPS_KNOWN, SPELLS_KNOWN, THIRD_CASTER_CANTRIPS, THIRD_CASTER_SPELLS, THIRD_CASTER_SUBCLASSES, THIRD_CASTER_SPELL_LIST, getPreparedCount } from '../../../utils/spellConstants';
import { parseFeatOptions } from '../../../utils/featSpellParser';
import type { ParsedFeatOption } from '../../../utils/featSpellParser';
import { ABILITY_FROM_ABBR, abilityMod, formatMod, safeJsonParse } from '../../../utils/dndRules';
import type { SpellEntry } from './types';
import StatCard from './StatCard';

export default function SpellsTab({ char, spellsKnown, spellSlots, saveField, pendingSpellManage, onSpellManageHandled }: {
  char: PlayerCharacter;
  spellsKnown: SpellEntry[];
  spellSlots: Record<string, { total: number; used: number }>;
  saveField: (u: CharacterUpdateRequest) => Promise<void>;
  pendingSpellManage?: { className: string; type: 'known' | 'prepared' } | null;
  onSpellManageHandled?: () => void;
}) {
  const [detailSpell, setDetailSpell] = useState<Spell | null>(null);
  const [loadingSpell, setLoadingSpell] = useState(false);
  const [manageModal, setManageModal] = useState<{ type: 'prepared' | 'known' | 'spellbook' | 'remove-spellbook'; className: string; spellListClass?: string; classLevel?: number } | null>(null);
  const [showAddFeat, setShowAddFeat] = useState(false);
  const [confirmRemoveFeat, setConfirmRemoveFeat] = useState<string | null>(null);

  useEffect(() => {
    if (pendingSpellManage && !manageModal) {
      const mcEntries = safeJsonParse<Array<{ className: string; subclassName?: string; level: number }>>(char.multiclassEntries, []);
      const entry = mcEntries.find(e => e.className === pendingSpellManage.className);
      const classLevel = entry ? entry.level : char.level;
      const isThirdCaster = entry?.subclassName && THIRD_CASTER_SUBCLASSES.has(entry.subclassName);
      const spellListClass = isThirdCaster ? THIRD_CASTER_SPELL_LIST[entry!.subclassName!] : pendingSpellManage.className;
      setManageModal({ type: pendingSpellManage.type, className: pendingSpellManage.className, spellListClass, classLevel });
      onSpellManageHandled?.();
    }
  }, [pendingSpellManage]);

  const subclassAlwaysPreparedGroups = useMemo(() => {
    if (!char.subclassAlwaysPreparedSpells) return [];
    const parsed = safeJsonParse<Record<string, Record<string, string[]>>>(char.subclassAlwaysPreparedSpells, {});
    const mcEntries = safeJsonParse<Array<{ className: string; subclassName?: string; level: number }>>(char.multiclassEntries, []);
    const groups: Array<{ subclassName: string; spells: Array<{ name: string; classLevel: number }> }> = [];
    for (const [scName, levels] of Object.entries(parsed)) {
      const mcEntry = mcEntries.find(e => e.subclassName === scName);
      const classLevel = mcEntry ? mcEntry.level : char.level;
      const unlocked: Array<{ name: string; classLevel: number }> = [];
      for (const [lvlKey, spells] of Object.entries(levels)) {
        const lvl = parseInt(lvlKey);
        if (isNaN(lvl) || lvl > classLevel) continue;
        for (const name of spells) unlocked.push({ name, classLevel: lvl });
      }
      if (unlocked.length > 0) groups.push({ subclassName: scName, spells: unlocked });
    }
    return groups;
  }, [char.subclassAlwaysPreparedSpells, char.multiclassEntries, char.level]);

  const hasSpells = char.spellcastingAbility || spellsKnown.length > 0 || subclassAlwaysPreparedGroups.length > 0;
  if (!hasSpells) {
    return <p className="font-body text-[13px] text-faint">This character is not a spellcaster.</p>;
  }

  const defaultSource = `class:${char.characterClass || 'Unknown'}`;
  const taggedSpells: SpellEntry[] = spellsKnown
    .filter(s => s.name)
    .map(s => ({
      ...s,
      source: s.source || defaultSource,
    }));

  const sourceGroups = new Map<string, SpellEntry[]>();
  for (const s of taggedSpells) {
    const key = s.source!;
    const list = sourceGroups.get(key) || [];
    list.push(s);
    sourceGroups.set(key, list);
  }

  const classGroups: Array<{ source: string; className: string; spells: SpellEntry[] }> = [];
  const raceGroups: Array<{ source: string; raceName: string; spells: SpellEntry[] }> = [];
  const featGroups: Array<{ source: string; featName: string; spells: SpellEntry[] }> = [];

  for (const [source, spells] of sourceGroups) {
    if (source.startsWith('class:')) {
      classGroups.push({ source, className: source.slice(6), spells });
    } else if (source.startsWith('race:')) {
      raceGroups.push({ source, raceName: source.slice(5), spells });
    } else if (source.startsWith('feat:')) {
      featGroups.push({ source, featName: source.slice(5), spells });
    }
  }

  const SPELLCASTER_CLASSES = ['Bard', 'Cleric', 'Druid', 'Paladin', 'Ranger', 'Sorcerer', 'Warlock', 'Wizard', 'Artificer'];

  const mcEntries = safeJsonParse<Array<{ className: string; subclassName?: string; level: number }>>(char.multiclassEntries, []);
  for (const entry of mcEntries) {
    const source = `class:${entry.className}`;
    if (classGroups.some(g => g.source === source)) continue;
    const isCasterClass = SPELLCASTER_CLASSES.includes(entry.className);
    const isThirdCaster = entry.subclassName && THIRD_CASTER_SUBCLASSES.has(entry.subclassName) && entry.level >= 3;
    if (isCasterClass || isThirdCaster) {
      classGroups.push({ source, className: entry.className, spells: [] });
    }
  }

  const regularSlots = Object.entries(spellSlots)
    .filter(([k]) => !k.startsWith('pact_'))
    .sort((a, b) => parseInt(a[0]) - parseInt(b[0]));
  const pactSlots = Object.entries(spellSlots)
    .filter(([k]) => k.startsWith('pact_'))
    .sort((a, b) => parseInt(a[0].replace('pact_', '')) - parseInt(b[0].replace('pact_', '')));

  async function useSlot(level: string) {
    const slot = spellSlots[level];
    if (!slot || slot.used >= slot.total) return;
    const updated = { ...spellSlots, [level]: { ...slot, used: slot.used + 1 } };
    await saveField({ spellSlots: JSON.stringify(updated) });
  }

  async function viewSpellDetail(spellName: string) {
    if (!spellName) return;
    setLoadingSpell(true);
    try {
      const res = await searchSpells({ name: spellName, size: 1 });
      if (res.content.length > 0) setDetailSpell(res.content[0]);
    } catch { /* ignore */ }
    setLoadingSpell(false);
  }

  async function togglePrepared(spell: SpellEntry) {
    if (spell.alwaysPrepared || spell.level === 0) return;
    const updated = taggedSpells.map(s =>
      s.name === spell.name && s.source === spell.source
        ? { ...s, prepared: !s.prepared }
        : s
    );
    await saveField({ spellsKnown: JSON.stringify(updated) });
  }

  async function removeFeatSpells(source: string) {
    const updated = taggedSpells.filter(s => s.source !== source);
    await saveField({ spellsKnown: JSON.stringify(updated) });
  }

  function getClassCasterInfo(className: string) {
    const isPrepared = ['Cleric', 'Druid', 'Paladin', 'Wizard', 'Artificer'].includes(className);
    const mcEntry = mcEntries.find(e => e.className === className);
    const isThirdCaster = mcEntry?.subclassName ? THIRD_CASTER_SUBCLASSES.has(mcEntry.subclassName) : false;
    const isKnown = ['Bard', 'Ranger', 'Sorcerer', 'Warlock'].includes(className) || isThirdCaster;
    const isPact = className === 'Warlock';
    const thirdCasterSubclass = isThirdCaster ? mcEntry!.subclassName! : null;
    return { isPrepared, isKnown, isPact, isThirdCaster, thirdCasterSubclass };
  }

  function renderSpellRow(spell: SpellEntry, showPrepared: boolean) {
    return (
      <button
        key={`${spell.source}-${spell.name}`}
        onClick={() => viewSpellDetail(spell.name)}
        className="flex items-center gap-2 py-1.5 px-2 hover:bg-page-alt transition-colors w-full text-left group"
      >
        {showPrepared && spell.level > 0 && (
          spell.alwaysPrepared ? (
            <Lock className="w-3 h-3 text-cls-cleric flex-shrink-0" />
          ) : (
            <button
              onClick={(e) => { e.stopPropagation(); togglePrepared(spell); }}
              className={`w-3 h-3 rounded-full flex-shrink-0 border ${
                spell.prepared ? 'bg-buff border-buff' : 'bg-transparent border-rule'
              }`}
            />
          )
        )}
        <span className="font-body text-[13px] font-medium text-ink group-hover:text-muted transition-colors">{spell.name}</span>
        {spell.atWill && <span className="font-heading text-[9px] font-medium text-buff ml-auto">At will</span>}
        {spell.usesPerLongRest && <span className="font-heading text-[9px] font-medium text-cls-cleric ml-auto">{spell.usesPerLongRest}/long rest</span>}
        {spell.unlocksAtLevel && spell.unlocksAtLevel > char.level && (
          <span className="font-heading text-[9px] font-medium text-faint ml-auto">Lv {spell.unlocksAtLevel}</span>
        )}
      </button>
    );
  }

  function renderSlotButtons(slots: Array<[string, { total: number; used: number }]>, label: string) {
    if (slots.length === 0) return null;
    return (
      <div className="bg-card border border-rule p-4">
        <h3 className="font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-faint mb-3">{label}</h3>
        <div className="grid grid-cols-3 sm:grid-cols-5 gap-3">
          {slots.map(([level, slot]) => {
            const displayLevel = level.startsWith('pact_') ? level.replace('pact_', '') : level;
            return (
              <button
                key={level}
                onClick={() => useSlot(level)}
                className="bg-page border border-rule p-3 text-center hover:bg-page-alt transition-colors"
              >
                <p className="font-heading text-[9px] font-medium text-faint">{label === 'Pact Slots' ? 'Pact' : ''} Lv {displayLevel}</p>
                <p className="font-heading text-[15px] font-bold text-ink">{slot.total - slot.used}/{slot.total}</p>
              </button>
            );
          })}
        </div>
      </div>
    );
  }

  const CASTER_ABILITY: Record<string, string> = {
    Bard: 'CHA', Cleric: 'WIS', Druid: 'WIS', Paladin: 'CHA', Ranger: 'WIS',
    Sorcerer: 'CHA', Warlock: 'CHA', Wizard: 'INT', Artificer: 'INT',
  };
  const THIRD_CASTER_ABILITY_MAP: Record<string, string> = {
    'Eldritch Knight': 'INT', 'Arcane Trickster': 'INT',
  };

  const multiclassSpellStats = useMemo(() => {
    const entries = safeJsonParse<Array<{ className: string; subclassName?: string; level: number }>>(char.multiclassEntries, []);
    if (entries.length <= 1) return [];
    const stats: Array<{ className: string; ability: string; saveDc: number; attackBonus: number }> = [];
    const seen = new Set<string>();
    for (const e of entries) {
      let ability = CASTER_ABILITY[e.className];
      if (!ability && e.subclassName) ability = THIRD_CASTER_ABILITY_MAP[e.subclassName];
      if (!ability || seen.has(ability)) continue;
      seen.add(ability);
      const abilityKey = ABILITY_FROM_ABBR[ability] as keyof PlayerCharacter;
      const score = (char[abilityKey] as number) || 10;
      const mod = abilityMod(score);
      stats.push({
        className: e.className,
        ability,
        saveDc: 8 + char.proficiencyBonus + mod,
        attackBonus: char.proficiencyBonus + mod,
      });
    }
    return stats.length > 1 ? stats : [];
  }, [char.multiclassEntries, char.proficiencyBonus, char.strength, char.dexterity, char.constitution, char.intelligence, char.wisdom, char.charisma]);

  return (
    <div className="space-y-6">
      {/* Spellcasting stats */}
      {multiclassSpellStats.length > 0 ? (
        <div className="bg-card border border-rule p-4 space-y-3">
          <h3 className="font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-faint mb-1">Spellcasting by Class</h3>
          {multiclassSpellStats.map(s => (
            <div key={s.className} className="grid grid-cols-4 gap-2">
              <StatCard label={s.className} value={s.ability} />
              <StatCard label="Save DC" value={String(s.saveDc)} />
              <StatCard label="Attack" value={formatMod(s.attackBonus)} />
              <div />
            </div>
          ))}
        </div>
      ) : (
        <div className="bg-card border border-rule p-4">
          <h3 className="font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-faint mb-3">Spellcasting</h3>
          <div className="grid grid-cols-3 gap-3">
            <StatCard label="Ability" value={char.spellcastingAbility || '-'} />
            {char.spellSaveDc && <StatCard label="Save DC" value={String(char.spellSaveDc)} />}
            {char.spellAttackBonus != null && <StatCard label="Attack Bonus" value={formatMod(char.spellAttackBonus)} />}
          </div>
        </div>
      )}

      {/* Spell slots */}
      {renderSlotButtons(regularSlots, 'Spell Slots')}
      {renderSlotButtons(pactSlots, 'Pact Slots')}

      {/* Class spell boxes */}
      {classGroups.map(({ className, spells }) => {
        const info = getClassCasterInfo(className);
        const cantrips = spells.filter(s => s.level === 0);
        const leveled = spells.filter(s => s.level > 0);
        const prepared = leveled.filter(s => s.prepared || s.alwaysPrepared);
        const classEntry = mcEntries.find(e => e.className === className);
        const classLevel = classEntry ? classEntry.level : char.level;
        const abilityMod_ = char.spellcastingAbility
          ? abilityMod(char[ABILITY_FROM_ABBR[char.spellcastingAbility] as keyof PlayerCharacter] as number || 10)
          : 0;
        const prepLimit = info.isPrepared ? getPreparedCount(className, classLevel, abilityMod_) : 0;
        const knownLimit = info.isThirdCaster && info.thirdCasterSubclass
          ? (THIRD_CASTER_SPELLS[info.thirdCasterSubclass]?.[classLevel] ?? 0)
          : (SPELLS_KNOWN[className]?.[classLevel] ?? 0);
        const cantripLimit = info.isThirdCaster && info.thirdCasterSubclass
          ? (THIRD_CASTER_CANTRIPS[info.thirdCasterSubclass]?.[classLevel] ?? 0)
          : (CANTRIPS_KNOWN[className]?.[classLevel]);
        const spellListClass = info.thirdCasterSubclass ? THIRD_CASTER_SPELL_LIST[info.thirdCasterSubclass] : className;
        const displayName = info.thirdCasterSubclass ? `${className} (${info.thirdCasterSubclass})` : className;

        const isWizard = className === 'Wizard';

        return (
          <div key={className} className="bg-card border border-rule p-4 space-y-4">
            <div className="flex items-center justify-between">
              <h3 className="font-heading text-[14px] font-semibold text-ink">{displayName} Spells</h3>
              <div className="flex items-center gap-2">
                {isWizard && (
                  <>
                    <button
                      onClick={() => setManageModal({ type: 'spellbook', className })}
                      className="font-body text-[12px] font-medium text-buff hover:text-ink transition-colors"
                    >
                      + Spellbook
                    </button>
                    <button
                      onClick={() => setManageModal({ type: 'remove-spellbook', className })}
                      className="font-body text-[12px] font-medium text-debuff hover:text-ink transition-colors"
                    >
                      - Spellbook
                    </button>
                  </>
                )}
                <span className={`font-heading text-[9px] font-medium tracking-[0.02em] px-2 py-0.5 border ${
                  info.isPrepared ? 'text-cls-wizard bg-cls-wizard-bg border-[#C7D2FE]' : 'text-cls-warlock bg-[#F5F3FF] border-[#DDD6FE]'
                }`}>
                  {info.isPrepared ? 'Prepared' : 'Known'}
                </span>
              </div>
            </div>

            {(cantrips.length > 0 || (cantripLimit && cantripLimit > 0)) && (
              <div>
                <h4 className="font-heading text-[9px] font-semibold tracking-[0.08em] uppercase text-faint mb-1">
                  Cantrips ({cantrips.length}{cantripLimit ? `/${cantripLimit}` : ''})
                </h4>
                <div className="space-y-0.5">{cantrips.map(s => renderSpellRow(s, false))}</div>
              </div>
            )}

            {info.isPrepared && (
              <div>
                <div className="flex items-center justify-between mb-1">
                  <h4 className="font-heading text-[9px] font-semibold tracking-[0.08em] uppercase text-faint">
                    Prepared ({prepared.length}/{prepLimit})
                  </h4>
                  <button
                    onClick={() => setManageModal({ type: 'prepared', className })}
                    className="font-body text-[12px] font-medium text-muted hover:text-ink transition-colors"
                  >
                    Change Prepared
                  </button>
                </div>
                {leveled.length > 0 ? (
                  <div className="space-y-0.5">{leveled.map(s => renderSpellRow(s, true))}</div>
                ) : (
                  <p className="font-body text-[11px] text-faint">No spells prepared.</p>
                )}
              </div>
            )}

            {info.isKnown && (
              <div>
                <div className="flex items-center justify-between mb-1">
                  <h4 className="font-heading text-[9px] font-semibold tracking-[0.08em] uppercase text-faint">
                    Known Spells ({leveled.length}{knownLimit ? `/${knownLimit}` : ''})
                  </h4>
                  <button
                    onClick={() => setManageModal({ type: 'known', className, spellListClass, classLevel })}
                    className="font-body text-[12px] font-medium text-muted hover:text-ink transition-colors"
                  >
                    Manage Known
                  </button>
                </div>
                {leveled.length > 0 ? (
                  <div className="space-y-0.5">{leveled.map(s => renderSpellRow(s, false))}</div>
                ) : (
                  <p className="font-body text-[11px] text-faint">No spells known.</p>
                )}
              </div>
            )}
          </div>
        );
      })}

      {/* Subclass always-prepared spells */}
      {subclassAlwaysPreparedGroups.map(group => (
        <div key={group.subclassName} className="bg-card border border-rule p-4 space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="font-heading text-[14px] font-semibold text-ink">{group.subclassName} Spells</h3>
            <span className="font-heading text-[9px] font-medium tracking-[0.02em] px-2 py-0.5 border text-cls-cleric bg-cls-cleric-bg border-[#E8DCC4]">Always Prepared</span>
          </div>
          <div className="space-y-0.5">
            {group.spells.map(s => (
              <button
                key={s.name}
                onClick={() => viewSpellDetail(s.name)}
                className="flex items-center gap-2 py-1.5 px-2 hover:bg-page-alt transition-colors w-full text-left group"
              >
                <Lock className="w-3 h-3 text-cls-cleric flex-shrink-0" />
                <span className="font-body text-[13px] font-medium text-ink group-hover:text-muted transition-colors">{s.name}</span>
                <span className="font-heading text-[9px] font-medium text-faint ml-auto">Lv {s.classLevel}</span>
              </button>
            ))}
          </div>
        </div>
      ))}

      {/* Race spell boxes */}
      {raceGroups.map(({ raceName, spells }) => (
        <div key={raceName} className="bg-card border border-rule p-4 space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="font-heading text-[14px] font-semibold text-ink">{raceName} Spells</h3>
            <span className="font-heading text-[9px] font-medium tracking-[0.02em] px-2 py-0.5 border text-cls-druid bg-cls-druid-bg border-[#A7F3D0]">Innate</span>
          </div>
          <div className="space-y-0.5">{spells.map(s => renderSpellRow(s, false))}</div>
        </div>
      ))}

      {/* Feat spell boxes */}
      {featGroups.map(({ featName, source, spells }) => (
        <div key={featName} className="bg-card border border-rule p-4 space-y-3">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <h3 className="font-heading text-[14px] font-semibold text-ink">{featName}</h3>
              <span className="font-heading text-[9px] font-medium tracking-[0.02em] px-2 py-0.5 border text-cls-cleric bg-cls-cleric-bg border-[#E8DCC4]">Feat</span>
            </div>
            <button
              onClick={() => setConfirmRemoveFeat(confirmRemoveFeat === source ? null : source)}
              className="text-faint hover:text-debuff transition-colors"
              title="Remove feat spells"
            >
              <X className="w-4 h-4" />
            </button>
          </div>
          {confirmRemoveFeat === source && (
            <div className="flex items-center gap-2 bg-debuff-bg border border-debuff/30 px-3 py-2">
              <p className="font-body text-[12px] font-medium text-debuff flex-1">Remove all {featName} spells?</p>
              <button
                onClick={() => { removeFeatSpells(source); setConfirmRemoveFeat(null); }}
                className="px-2 py-1 bg-debuff text-card font-body text-[12px] font-medium transition-colors hover:opacity-80"
              >
                Remove
              </button>
              <button
                onClick={() => setConfirmRemoveFeat(null)}
                className="px-2 py-1 font-body text-[12px] font-medium text-muted hover:text-ink transition-colors"
              >
                Cancel
              </button>
            </div>
          )}
          <div className="space-y-0.5">{spells.map(s => renderSpellRow(s, false))}</div>
        </div>
      ))}

      {/* Add Feat button */}
      <button
        onClick={() => setShowAddFeat(true)}
        className="w-full py-2 border border-dashed border-cls-cleric/30 font-body text-[13px] font-medium text-cls-cleric hover:bg-cls-cleric-bg transition-colors flex items-center justify-center gap-2"
      >
        <Plus className="w-4 h-4" /> Add Feat Spells
      </button>

      {taggedSpells.length === 0 && classGroups.every(g => g.spells.length === 0) && (
        <p className="font-body text-[13px] text-faint">No spells yet. Use the Change Prepared or Manage Known button to add spells.</p>
      )}

      {/* Spell Detail Modal */}
      {(detailSpell || loadingSpell) && (
        <div className="fixed inset-0 z-50 bg-black/70 flex items-center justify-center p-4" onClick={() => { setDetailSpell(null); setLoadingSpell(false); }}>
          <div role="dialog" aria-modal="true" aria-label="Spell details" className="max-w-2xl w-full max-h-[80vh] overflow-y-auto" onClick={e => e.stopPropagation()}>
            {loadingSpell ? (
              <div className="bg-card border border-rule p-8 text-center">
                <p className="font-body text-[13px] text-muted">Loading spell...</p>
              </div>
            ) : detailSpell ? (
              <div className="relative">
                <button onClick={() => setDetailSpell(null)} className="absolute top-2 right-2 z-10 text-faint hover:text-ink">
                  <X className="w-5 h-5" />
                </button>
                <SpellCard spell={detailSpell} />
              </div>
            ) : null}
          </div>
        </div>
      )}

      {/* Manage Spells Modal */}
      {manageModal && (
        <ManageSpellsModal
          char={char}
          type={manageModal.type}
          className={manageModal.className}
          spellListClass={manageModal.spellListClass}
          classLevel={manageModal.classLevel}
          currentSpells={taggedSpells}
          saveField={saveField}
          onClose={() => setManageModal(null)}
        />
      )}

      {/* Add Feat Modal */}
      {showAddFeat && (
        <AddFeatModal
          currentSpells={taggedSpells}
          saveField={saveField}
          onClose={() => setShowAddFeat(false)}
        />
      )}
    </div>
  );
}

function ManageSpellsModal({ char, type, className, spellListClass, classLevel, currentSpells, saveField, onClose }: {
  char: PlayerCharacter;
  type: 'prepared' | 'known' | 'spellbook' | 'remove-spellbook';
  className: string;
  spellListClass?: string;
  classLevel?: number;
  currentSpells: SpellEntry[];
  saveField: (u: CharacterUpdateRequest) => Promise<void>;
  onClose: () => void;
}) {
  const [searchQuery, setSearchQuery] = useState('');
  const [searchResults, setSearchResults] = useState<Spell[]>([]);
  const [searching, setSearching] = useState(false);
  const [selectedLevel, setSelectedLevel] = useState<number | ''>('');
  const [localSpells, setLocalSpells] = useState<SpellEntry[]>(currentSpells);
  const [saving, setSaving] = useState(false);

  const source = `class:${className}`;
  const classSpells = localSpells.filter(s => s.source === source);
  const leveled = classSpells.filter(s => s.level > 0);
  const preparedCount = leveled.filter(s => s.prepared || s.alwaysPrepared).length;
  const abilityMod_ = char.spellcastingAbility
    ? abilityMod(char[ABILITY_FROM_ABBR[char.spellcastingAbility] as keyof PlayerCharacter] as number || 10)
    : 0;
  const prepLimit = getPreparedCount(className, char.level, abilityMod_);
  const effectiveClassLevel = classLevel ?? char.level;
  const thirdCasterSub = spellListClass && spellListClass !== className
    ? Object.entries(THIRD_CASTER_SPELL_LIST).find(([, v]) => v === spellListClass)?.[0] ?? null
    : null;
  const knownLimit = thirdCasterSub
    ? (THIRD_CASTER_SPELLS[thirdCasterSub]?.[effectiveClassLevel] ?? 0)
    : (SPELLS_KNOWN[className]?.[effectiveClassLevel] ?? 0);

  const isWizardPrepared = type === 'prepared' && className === 'Wizard';
  const isSpellbookMode = type === 'spellbook';
  const isRemoveSpellbook = type === 'remove-spellbook';
  const needsApiSearch = !isWizardPrepared && !isRemoveSpellbook;

  const slots = safeJsonParse<Record<string, { total: number; used: number }>>(char.spellSlots, {});
  const maxSlotLevel = Math.max(0, ...Object.keys(slots).map(k => {
    if (k.startsWith('pact_')) return parseInt(k.replace('pact_', ''));
    const n = parseInt(k);
    return isNaN(n) ? 0 : n;
  }));
  const canSwapCantrips = className === 'Wizard' && char.level >= 3;
  const minSpellLevel = canSwapCantrips ? 0 : 1;
  const validLevels = Array.from({ length: maxSlotLevel - minSpellLevel + 1 }, (_, i) => i + minSpellLevel);

  const spellbookSpells = useMemo(() => {
    if (!isWizardPrepared && !isRemoveSpellbook) return [];
    return classSpells.filter(s => s.level > 0).map(s => ({
      id: `spellbook-${s.name}`,
      name: s.name,
      level: s.level,
      school: null, castingTime: null, rangeDistance: null, components: null,
      duration: null, concentration: false, ritual: false, description: null,
      higherLevels: null, classes: null, damageType: null, damageDice: null,
      saveAbility: null, source: null,
    } as Spell));
  }, [isWizardPrepared, isRemoveSpellbook, classSpells]);

  const filteredSpellbook = useMemo(() => {
    let list = spellbookSpells;
    if (searchQuery.trim()) {
      const q = searchQuery.trim().toLowerCase();
      list = list.filter(s => s.name.toLowerCase().includes(q));
    }
    if (selectedLevel !== '') {
      list = list.filter(s => s.level === selectedLevel);
    }
    return list;
  }, [spellbookSpells, searchQuery, selectedLevel]);

  useEffect(() => {
    if (needsApiSearch) doSearch();
  }, [selectedLevel]);

  async function doSearch() {
    if (!needsApiSearch) return;
    setSearching(true);
    try {
      const params: Record<string, unknown> = { className: spellListClass || className, size: 50 };
      if (searchQuery.trim()) params.name = searchQuery.trim();
      if (selectedLevel !== '') {
        params.level = selectedLevel;
      } else {
        params.level = validLevels.join(',');
      }
      const res = await searchSpells(params as any);
      setSearchResults(res.content.filter(s => s.level >= minSpellLevel && s.level <= maxSlotLevel));
    } catch { /* ignore */ }
    setSearching(false);
  }

  function isAdded(spell: Spell): boolean {
    return classSpells.some(s => s.name === spell.name);
  }

  function isPreparedSpell(spell: Spell): boolean {
    return classSpells.some(s => s.name === spell.name && (s.prepared || s.alwaysPrepared));
  }

  function toggleSpell(spell: Spell) {
    if (isSpellbookMode) {
      const exists = localSpells.some(s => s.name === spell.name && s.source === source);
      if (exists) {
        setLocalSpells(localSpells.filter(s => !(s.name === spell.name && s.source === source)));
      } else {
        setLocalSpells([...localSpells, { name: spell.name, level: spell.level, source, prepared: false }]);
      }
    } else if (isRemoveSpellbook) {
      setLocalSpells(localSpells.filter(s => !(s.name === spell.name && s.source === source)));
    } else if (type === 'prepared') {
      const existing = localSpells.find(s => s.name === spell.name && s.source === source);
      if (existing) {
        if (existing.alwaysPrepared) return;
        if (existing.prepared) {
          setLocalSpells(localSpells.map(s =>
            s.name === spell.name && s.source === source ? { ...s, prepared: false } : s
          ));
        } else {
          if (preparedCount >= prepLimit) return;
          setLocalSpells(localSpells.map(s =>
            s.name === spell.name && s.source === source ? { ...s, prepared: true } : s
          ));
        }
      } else {
        if (preparedCount >= prepLimit) return;
        setLocalSpells([...localSpells, { name: spell.name, level: spell.level, source, prepared: true }]);
      }
    } else {
      const exists = localSpells.some(s => s.name === spell.name && s.source === source);
      if (exists) {
        setLocalSpells(localSpells.filter(s => !(s.name === spell.name && s.source === source)));
      } else {
        if (knownLimit > 0 && leveled.length >= knownLimit) return;
        setLocalSpells([...localSpells, { name: spell.name, level: spell.level, source }]);
      }
    }
  }

  async function handleSave() {
    setSaving(true);
    await saveField({ spellsKnown: JSON.stringify(localSpells) });
    setSaving(false);
    onClose();
  }

  const displayResults = needsApiSearch ? searchResults : filteredSpellbook;
  const modalTitle = isSpellbookMode
    ? 'Add to Spellbook'
    : isRemoveSpellbook
      ? 'Remove from Spellbook'
      : isWizardPrepared
        ? 'Prepare from Spellbook'
        : type === 'prepared'
          ? 'Change Prepared Spells'
          : 'Manage Known Spells';
  const modalSubtitle = isSpellbookMode
    ? `${leveled.length} spells in spellbook`
    : isRemoveSpellbook
      ? `${leveled.length} spells in spellbook`
      : type === 'prepared'
        ? `${preparedCount}/${prepLimit} prepared`
        : `${leveled.length}${knownLimit ? `/${knownLimit}` : ''} known`;

  return (
    <div className="fixed inset-0 z-50 bg-black/70 flex items-center justify-center p-4">
      <div role="dialog" aria-modal="true" aria-labelledby="manage-spells-title" className="bg-card border border-rule w-full max-w-lg max-h-[85vh] flex flex-col">
        {/* Header */}
        <div className="px-4 py-3 border-b border-rule flex items-center justify-between">
          <div>
            <h2 id="manage-spells-title" className="font-heading text-[15px] font-semibold text-ink">{modalTitle}</h2>
            <p className="font-body text-[11px] font-medium text-faint mt-0.5">{modalSubtitle}</p>
          </div>
          <button onClick={onClose} className="text-faint hover:text-ink"><X className="w-5 h-5" /></button>
        </div>

        {/* Search bar */}
        <div className="px-4 py-3 border-b border-rule space-y-2">
          <div className="flex gap-2">
            <div className="relative flex-1">
              <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-4 h-4 text-faint" />
              <input
                value={searchQuery}
                onChange={e => setSearchQuery(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && needsApiSearch && doSearch()}
                placeholder={needsApiSearch ? "Search spells..." : "Filter spellbook..."}
                className="w-full bg-page border border-rule pl-8 pr-3 py-1.5 font-body text-[13px] font-medium text-ink placeholder-faint focus:outline-none focus:border-muted"
              />
            </div>
            {(isWizardPrepared || isRemoveSpellbook) && (
              <select
                value={selectedLevel}
                onChange={e => setSelectedLevel(e.target.value === '' ? '' : parseInt(e.target.value))}
                className="bg-page border border-rule px-2 py-1.5 font-body text-[13px] font-medium text-ink focus:outline-none focus:border-muted"
              >
                <option value="">All Levels</option>
                {validLevels.filter(l => l > 0).map(l => (
                  <option key={l} value={l}>Level {l}</option>
                ))}
              </select>
            )}
            {needsApiSearch && (
              <>
                <select
                  value={selectedLevel}
                  onChange={e => setSelectedLevel(e.target.value === '' ? '' : parseInt(e.target.value))}
                  className="bg-page border border-rule px-2 py-1.5 font-body text-[13px] font-medium text-ink focus:outline-none focus:border-muted"
                >
                  <option value="">All Levels</option>
                  {validLevels.map(l => (
                    <option key={l} value={l}>{l === 0 ? 'Cantrips' : `Level ${l}`}</option>
                  ))}
                </select>
                <button onClick={doSearch} className="bg-ink text-card font-body text-[13px] font-medium px-3 hover:opacity-80 transition-colors">
                  Search
                </button>
              </>
            )}
          </div>
        </div>

        {/* Results */}
        <div className="flex-1 overflow-y-auto px-4 py-2">
          {searching ? (
            <p className="font-body text-[13px] text-muted py-4 text-center">Searching...</p>
          ) : displayResults.length === 0 ? (
            <p className="font-body text-[13px] text-faint py-4 text-center">
              {needsApiSearch
                ? `Search for ${className} spells to add them.`
                : isRemoveSpellbook
                  ? 'No spells in spellbook.'
                  : 'No spells in spellbook to prepare.'}
            </p>
          ) : (
            <div className="space-y-1">
              {displayResults.map(spell => {
                const added = isAdded(spell);
                const prepared = isPreparedSpell(spell);
                const isAlwaysPrep = classSpells.some(s => s.name === spell.name && s.alwaysPrepared);

                let disabled = false;
                if (isSpellbookMode) {
                  disabled = false;
                } else if (isRemoveSpellbook) {
                  disabled = !added;
                } else if (type === 'prepared') {
                  disabled = isAlwaysPrep || (preparedCount >= prepLimit && !prepared);
                } else {
                  disabled = isAlwaysPrep || (knownLimit > 0 && leveled.length >= knownLimit && !added);
                }

                return (
                  <button
                    key={spell.id}
                    onClick={() => !disabled && toggleSpell(spell)}
                    disabled={disabled}
                    className={`w-full flex items-center justify-between py-2 px-3 font-body text-[13px] font-medium transition-colors ${
                      isAlwaysPrep && (type === 'prepared' || isWizardPrepared)
                        ? 'bg-cls-cleric-bg text-cls-cleric cursor-not-allowed'
                        : disabled
                          ? 'text-faint cursor-not-allowed'
                          : isRemoveSpellbook
                            ? 'text-muted hover:bg-debuff-bg'
                            : isSpellbookMode
                              ? added
                                ? 'bg-buff-bg text-buff hover:opacity-80'
                                : 'text-muted hover:bg-page-alt'
                              : (type === 'prepared' || isWizardPrepared ? prepared : added)
                                ? 'bg-cls-wizard-bg text-cls-wizard hover:opacity-80'
                                : 'text-muted hover:bg-page-alt'
                    }`}
                  >
                    <div className="flex items-center gap-2">
                      {isAlwaysPrep && (type === 'prepared' || isWizardPrepared) && <Lock className="w-3 h-3 text-cls-cleric" />}
                      <span>{spell.name}</span>
                      <span className="text-[11px] text-faint">
                        {spell.level === 0 ? 'Cantrip' : `Lv ${spell.level}`}
                      </span>
                    </div>
                    {isSpellbookMode ? (
                      added ? (
                        <span className="text-[11px] text-buff">In Spellbook</span>
                      ) : (
                        <Plus className="w-4 h-4 text-faint" />
                      )
                    ) : isRemoveSpellbook ? (
                      added ? (
                        <span className="text-[11px] text-debuff">Remove</span>
                      ) : (
                        <span className="text-[11px] text-faint">Removed</span>
                      )
                    ) : type === 'prepared' || isWizardPrepared ? (
                      isAlwaysPrep ? (
                        <span className="text-[11px] text-cls-cleric">Always</span>
                      ) : prepared ? (
                        <span className="text-[11px] text-buff">Prepared</span>
                      ) : added ? (
                        <span className="text-[11px] text-faint">In list</span>
                      ) : (
                        <Plus className="w-4 h-4 text-faint" />
                      )
                    ) : added ? (
                      <span className="text-[11px] text-debuff">Remove</span>
                    ) : (
                      <Plus className="w-4 h-4 text-faint" />
                    )}
                  </button>
                );
              })}
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="px-4 py-3 border-t border-rule flex justify-end gap-2">
          <button onClick={onClose} className="px-3 py-1.5 font-body text-[13px] font-medium text-muted hover:text-ink transition-colors">
            Cancel
          </button>
          <button
            onClick={handleSave}
            disabled={saving}
            className="px-4 py-1.5 bg-ink text-card font-body text-[13px] font-medium hover:opacity-80 transition-colors disabled:opacity-50"
          >
            {saving ? 'Saving...' : 'Save Changes'}
          </button>
        </div>
      </div>
    </div>
  );
}

function AddFeatModal({ currentSpells, saveField, onClose }: {
  currentSpells: SpellEntry[];
  saveField: (u: CharacterUpdateRequest) => Promise<void>;
  onClose: () => void;
}) {
  const [allFeats, setAllFeats] = useState<Feat[]>([]);
  const [featSearch, setFeatSearch] = useState('');
  const [selectedFeat, setSelectedFeat] = useState<Feat | null>(null);
  const [selectedOptionIdx, setSelectedOptionIdx] = useState<number | null>(null);
  const [selectedAbility, setSelectedAbility] = useState<string | null>(null);
  const [chosenCantrips, setChosenCantrips] = useState<Spell[]>([]);
  const [chosenSpells, setChosenSpells] = useState<Spell[]>([]);
  const [cantripResults, setCantripResults] = useState<Spell[]>([]);
  const [spellResults, setSpellResults] = useState<Spell[]>([]);
  const [saving, setSaving] = useState(false);
  const [step, setStep] = useState<'pick' | 'configure'>('pick');

  useEffect(() => {
    getFeats().then(f => setAllFeats(f)).catch(() => {});
  }, []);

  const spellGrantingFeats = useMemo(() => {
    return allFeats.filter(f => f.grantsFeatures && f.grantsFeatures !== 'null');
  }, [allFeats]);

  const filteredFeats = useMemo(() => {
    if (!featSearch.trim()) return spellGrantingFeats;
    const q = featSearch.trim().toLowerCase();
    return spellGrantingFeats.filter(f => f.name.toLowerCase().includes(q));
  }, [spellGrantingFeats, featSearch]);

  const parsedOptions = useMemo(() => {
    if (!selectedFeat) return [];
    return parseFeatOptions(selectedFeat.grantsFeatures);
  }, [selectedFeat]);

  const selectedOption: ParsedFeatOption | null = selectedOptionIdx != null ? parsedOptions[selectedOptionIdx] ?? null : null;

  useEffect(() => {
    if (!selectedOption) return;
    if (selectedOption.cantripChoice && selectedOption.cantripChoice.classes.length > 0) {
      Promise.all(selectedOption.cantripChoice.classes.map(cls =>
        searchSpells({ className: cls, level: 0, size: 50 }).then(r => r.content)
      )).then(results => {
        const all = results.flat();
        const seen = new Set<string>();
        setCantripResults(all.filter(s => { if (seen.has(s.name)) return false; seen.add(s.name); return true; }));
      }).catch(() => {});
    }
    if (selectedOption.spellChoice) {
      if (selectedOption.spellChoice.fromList) {
        const fakeSpells: Spell[] = selectedOption.spellChoice.fromList.map((name, i) => ({
          id: `feat-list-${i}`, name, level: 1, school: null, castingTime: null,
          rangeDistance: null, components: null, duration: null, concentration: false,
          ritual: false, description: null, higherLevels: null, classes: null,
          damageType: null, damageDice: null, saveAbility: null, source: null,
        }));
        setSpellResults(fakeSpells);
      } else if (selectedOption.spellChoice.classes.length > 0) {
        Promise.all(selectedOption.spellChoice.classes.map(cls =>
          searchSpells({ className: cls, level: 1, size: 50 }).then(r => r.content)
        )).then(results => {
          const all = results.flat();
          const seen = new Set<string>();
          setSpellResults(all.filter(s => { if (seen.has(s.name)) return false; seen.add(s.name); return true; }));
        }).catch(() => {});
      }
    }
    if (selectedOption.ability) setSelectedAbility(selectedOption.ability);
  }, [selectedOptionIdx]);

  function selectFeat(feat: Feat) {
    setSelectedFeat(feat);
    setSelectedOptionIdx(null);
    setSelectedAbility(null);
    setChosenCantrips([]);
    setChosenSpells([]);
    setCantripResults([]);
    setSpellResults([]);
    const opts = parseFeatOptions(feat.grantsFeatures);
    if (opts.length === 1) {
      setSelectedOptionIdx(0);
    }
    setStep('configure');
  }

  function toggleCantrip(spell: Spell) {
    if (!selectedOption?.cantripChoice) return;
    const exists = chosenCantrips.some(s => s.name === spell.name);
    if (exists) setChosenCantrips(chosenCantrips.filter(s => s.name !== spell.name));
    else if (chosenCantrips.length < selectedOption.cantripChoice.count) setChosenCantrips([...chosenCantrips, spell]);
  }

  function toggleSpell(spell: Spell) {
    if (!selectedOption?.spellChoice) return;
    const exists = chosenSpells.some(s => s.name === spell.name);
    if (exists) setChosenSpells(chosenSpells.filter(s => s.name !== spell.name));
    else if (chosenSpells.length < selectedOption.spellChoice.count) setChosenSpells([...chosenSpells, spell]);
  }

  const canSave = useMemo(() => {
    if (!selectedFeat || !selectedOption) return false;
    if (selectedOption.abilityChoices && !selectedAbility) return false;
    if (selectedOption.cantripChoice && chosenCantrips.length < selectedOption.cantripChoice.count) return false;
    if (selectedOption.spellChoice && chosenSpells.length < selectedOption.spellChoice.count) return false;
    return true;
  }, [selectedFeat, selectedOption, selectedAbility, chosenCantrips, chosenSpells]);

  async function handleSave() {
    if (!selectedFeat || !selectedOption) return;
    setSaving(true);
    const featSource = `feat:${selectedFeat.name}`;
    const newEntries: SpellEntry[] = [];
    for (const name of selectedOption.fixedCantrips) {
      newEntries.push({ name, level: 0, source: featSource, atWill: true });
    }
    for (const s of chosenCantrips) {
      newEntries.push({ name: s.name, level: 0, source: featSource, atWill: true });
    }
    for (const { name, usesPerDay } of selectedOption.fixedSpells) {
      newEntries.push({ name, level: 1, source: featSource, usesPerLongRest: usesPerDay });
    }
    for (const s of chosenSpells) {
      newEntries.push({
        name: s.name, level: s.level, source: featSource,
        usesPerLongRest: selectedOption.spellChoice?.usesPerDay ?? 1,
      });
    }
    await saveField({ spellsKnown: JSON.stringify([...currentSpells, ...newEntries]) });
    setSaving(false);
    onClose();
  }

  return (
    <div className="fixed inset-0 z-50 bg-black/70 flex items-center justify-center p-4">
      <div role="dialog" aria-modal="true" aria-labelledby="add-feat-title" className="bg-card border border-rule w-full max-w-lg max-h-[85vh] flex flex-col">
        <div className="px-4 py-3 border-b border-rule flex items-center justify-between">
          <div className="flex items-center gap-2">
            {step === 'configure' && (
              <button onClick={() => { setStep('pick'); setSelectedFeat(null); }} className="text-faint hover:text-ink" aria-label="Back to feat list">
                <ArrowLeft className="w-4 h-4" />
              </button>
            )}
            <h2 id="add-feat-title" className="font-heading text-[15px] font-semibold text-ink">
              {step === 'pick' ? 'Add Feat Spells' : selectedFeat?.name ?? ''}
            </h2>
          </div>
          <button onClick={onClose} className="text-faint hover:text-ink"><X className="w-5 h-5" /></button>
        </div>

        <div className="flex-1 overflow-y-auto px-4 py-3 space-y-4">
          {step === 'pick' && (
            <>
              <div className="relative">
                <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-4 h-4 text-faint" />
                <input
                  value={featSearch}
                  onChange={e => setFeatSearch(e.target.value)}
                  placeholder="Search spell-granting feats..."
                  className="w-full bg-page border border-rule pl-8 pr-3 py-1.5 font-body text-[13px] font-medium text-ink placeholder-faint focus:outline-none focus:border-muted"
                />
              </div>
              <div className="space-y-1">
                {filteredFeats.map(feat => (
                  <button
                    key={feat.id}
                    onClick={() => selectFeat(feat)}
                    className="w-full flex items-center justify-between py-2 px-3 font-body text-[13px] font-medium text-muted hover:bg-page-alt transition-colors"
                  >
                    <span>{feat.name}</span>
                    <span className="text-[11px] text-faint">{feat.source}</span>
                  </button>
                ))}
                {filteredFeats.length === 0 && (
                  <p className="font-body text-[13px] text-faint py-4 text-center">No spell-granting feats found.</p>
                )}
              </div>
            </>
          )}

          {step === 'configure' && selectedFeat && (
            <>
              {parsedOptions.length > 1 && (
                <div>
                  <p className="font-body text-[12px] text-muted mb-2">Choose an option:</p>
                  <div className="grid grid-cols-2 gap-2 max-h-40 overflow-y-auto">
                    {parsedOptions.map((opt, i) => (
                      <button
                        key={i}
                        onClick={() => {
                          setSelectedOptionIdx(selectedOptionIdx === i ? null : i);
                          setSelectedAbility(null);
                          setChosenCantrips([]);
                          setChosenSpells([]);
                        }}
                        className={`px-3 py-2 border font-body text-[12px] text-left transition-colors ${
                          selectedOptionIdx === i
                            ? 'bg-cls-cleric-bg border-cls-cleric text-ink'
                            : 'bg-page border-rule text-muted hover:border-muted'
                        }`}
                      >
                        <span className="font-medium">{opt.name}</span>
                        {opt.fixedCantrips.length > 0 && (
                          <span className="block text-faint mt-0.5">{opt.fixedCantrips.join(', ')}</span>
                        )}
                      </button>
                    ))}
                  </div>
                </div>
              )}

              {selectedOption?.abilityChoices && (
                <div>
                  <p className="font-body text-[12px] text-muted mb-2">Spellcasting Ability:</p>
                  <div className="flex gap-1.5">
                    {selectedOption.abilityChoices.map(a => (
                      <button
                        key={a}
                        onClick={() => setSelectedAbility(selectedAbility === a ? null : a)}
                        className={`px-3 py-1.5 font-heading text-[11px] font-semibold transition-colors ${
                          selectedAbility === a ? 'bg-ink text-card' : 'bg-page border border-rule text-muted hover:bg-page-alt'
                        }`}
                      >
                        {a}
                      </button>
                    ))}
                  </div>
                </div>
              )}

              {selectedOption?.fixedCantrips.length ? (
                <div>
                  <p className="font-body text-[12px] text-muted mb-1">Granted cantrips:</p>
                  <div className="flex flex-wrap gap-1">
                    {selectedOption.fixedCantrips.map(name => (
                      <span key={name} className="bg-cls-cleric-bg border border-[#E8DCC4] text-cls-cleric px-2 py-1 font-body text-[11px] font-medium">{name}</span>
                    ))}
                  </div>
                </div>
              ) : null}

              {selectedOption?.cantripChoice && (
                <div className="space-y-2">
                  <div className="flex items-center justify-between">
                    <p className="font-body text-[12px] text-muted">Choose {selectedOption.cantripChoice.count} cantrip{selectedOption.cantripChoice.count > 1 ? 's' : ''}:</p>
                    <span className="font-body text-[11px] text-faint">{chosenCantrips.length}/{selectedOption.cantripChoice.count}</span>
                  </div>
                  {chosenCantrips.length > 0 && (
                    <div className="flex flex-wrap gap-1">
                      {chosenCantrips.map(s => (
                        <span key={s.name} className="flex items-center gap-1 bg-cls-cleric-bg border border-[#E8DCC4] text-cls-cleric px-2 py-1 font-body text-[11px] font-medium">
                          {s.name}
                          <button onClick={() => toggleCantrip(s)} className="text-cls-cleric hover:text-ink"><X className="w-3 h-3" /></button>
                        </span>
                      ))}
                    </div>
                  )}
                  <div className="max-h-32 overflow-y-auto space-y-1">
                    {cantripResults.map(spell => {
                      const sel = chosenCantrips.some(s => s.name === spell.name);
                      const dis = !sel && chosenCantrips.length >= selectedOption!.cantripChoice!.count;
                      return (
                        <button
                          key={spell.id}
                          onClick={() => !dis && toggleCantrip(spell)}
                          disabled={dis}
                          className={`w-full flex items-center justify-between py-1 px-2 rounded text-xs transition-colors ${
                            sel ? 'bg-cls-cleric-bg text-cls-cleric' : dis ? 'text-faint cursor-not-allowed' : 'text-muted hover:bg-page-alt'
                          }`}
                        >
                          <span>{spell.name}</span>
                          {sel && <Check className="w-3 h-3 text-cls-cleric" />}
                        </button>
                      );
                    })}
                  </div>
                </div>
              )}

              {selectedOption?.fixedSpells.length ? (
                <div>
                  <p className="font-body text-[12px] text-muted mb-1">Granted spells:</p>
                  <div className="flex flex-wrap gap-1">
                    {selectedOption.fixedSpells.map(s => (
                      <span key={s.name} className="bg-cls-cleric-bg border border-[#E8DCC4] text-cls-cleric px-2 py-1 font-body text-[11px] font-medium">{s.name} ({s.usesPerDay}/day)</span>
                    ))}
                  </div>
                </div>
              ) : null}

              {selectedOption?.spellChoice && (
                <div className="space-y-2">
                  <div className="flex items-center justify-between">
                    <p className="font-body text-[12px] text-muted">
                      Choose {selectedOption.spellChoice.count} spell{selectedOption.spellChoice.count > 1 ? 's' : ''} ({selectedOption.spellChoice.usesPerDay}/day):
                    </p>
                    <span className="font-body text-[11px] text-faint">{chosenSpells.length}/{selectedOption.spellChoice.count}</span>
                  </div>
                  {chosenSpells.length > 0 && (
                    <div className="flex flex-wrap gap-1">
                      {chosenSpells.map(s => (
                        <span key={s.name} className="flex items-center gap-1 bg-cls-cleric-bg border border-[#E8DCC4] text-cls-cleric px-2 py-1 font-body text-[11px] font-medium">
                          {s.name}
                          <button onClick={() => toggleSpell(s)} className="text-cls-cleric hover:text-ink"><X className="w-3 h-3" /></button>
                        </span>
                      ))}
                    </div>
                  )}
                  <div className="max-h-32 overflow-y-auto space-y-1">
                    {spellResults.map(spell => {
                      const sel = chosenSpells.some(s => s.name === spell.name);
                      const dis = !sel && chosenSpells.length >= selectedOption!.spellChoice!.count;
                      return (
                        <button
                          key={spell.id}
                          onClick={() => !dis && toggleSpell(spell)}
                          disabled={dis}
                          className={`w-full flex items-center justify-between py-1 px-2 rounded text-xs transition-colors ${
                            sel ? 'bg-cls-cleric-bg text-cls-cleric' : dis ? 'text-faint cursor-not-allowed' : 'text-muted hover:bg-page-alt'
                          }`}
                        >
                          <div className="flex items-center gap-2">
                            <span>{spell.name}</span>
                            {spell.school && <span className="text-faint">{spell.school}</span>}
                          </div>
                          {sel && <Check className="w-3 h-3 text-cls-cleric" />}
                        </button>
                      );
                    })}
                  </div>
                </div>
              )}
            </>
          )}
        </div>

        {step === 'configure' && (
          <div className="px-4 py-3 border-t border-rule flex justify-end gap-2">
            <button onClick={onClose} className="px-3 py-1.5 font-body text-[13px] font-medium text-muted hover:text-ink transition-colors">Cancel</button>
            <button
              onClick={handleSave}
              disabled={!canSave || saving}
              className="px-4 py-1.5 bg-ink text-card font-body text-[13px] font-medium hover:opacity-80 transition-colors disabled:opacity-50"
            >
              {saving ? 'Saving...' : 'Add Feat Spells'}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
