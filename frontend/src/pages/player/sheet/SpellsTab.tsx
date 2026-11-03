import { useState, useEffect, useMemo } from 'react';
import { Lock, Search, X, Plus, Undo2 } from 'lucide-react';
import { searchSpells } from '../../../api/referenceApi';
import { useTypeAhead } from '../../../hooks/useTypeAhead';
import {
  useAddToSpellbook, useRemoveFromSpellbook, useRestoreSlot, useSetKnownSpells, useSetPreparedSpells, useSpendSlot,
} from '../../../queries/characters';
import SpellCard from '../../../components/reference/SpellCard';
import IconButton from '../../../components/ui/IconButton';
import type { PlayerCharacter, ClassSpellcasting } from '../../../types/character';
import type { SpellSlot } from '../../../types/encounter';
import type { Spell } from '../../../types/reference';
import { THIRD_CASTER_SUBCLASSES, THIRD_CASTER_SPELL_LIST } from '../../../utils/spellConstants';
import { formatMod } from '../../../utils/dndRules';
import type { RunCommand, SpellEntry } from './types';
import StatCard from './StatCard';

// The class a class's spells come from: the Wizard's list for an Eldritch Knight or Arcane Trickster
function spellListOf(casting: ClassSpellcasting | undefined, className: string) {
  return casting?.subclassName && THIRD_CASTER_SUBCLASSES.has(casting.subclassName)
    ? THIRD_CASTER_SPELL_LIST[casting.subclassName]
    : className;
}

type ManageType = 'prepared' | 'known' | 'cantrips' | 'spellbook' | 'remove-spellbook';

/**
 * The character's spells by where they come from. What each class casts with, its save DC, attack bonus, highest
 * spell level and cantrip, known and prepared limits, is the server's (char.spellcasting, D167); race and feat spells
 * use the main numbers. Every change is a command the server checks (D158): a slot spent or got back, the spells a
 * class prepares, a wizard's spellbook, and until Pending Choices the cantrips and spells a class knows. Spells from a
 * feat come with the feat, at a level's Ability Score Improvement or, from workstream E, as the DM's grant.
 */
export default function SpellsTab({ char, spellsKnown, spellSlots, runCommand, pendingSpellManage, onSpellManageHandled }: {
  char: PlayerCharacter;
  spellsKnown: SpellEntry[];
  spellSlots: Record<string, SpellSlot>;
  runCommand: RunCommand;
  pendingSpellManage?: { className: string; type: 'known' | 'prepared' } | null;
  onSpellManageHandled?: () => void;
}) {
  const spendSlotCommand = useSpendSlot();
  const restoreSlotCommand = useRestoreSlot();
  const setPrepared = useSetPreparedSpells();
  const [detailSpell, setDetailSpell] = useState<Spell | null>(null);
  const [loadingSpell, setLoadingSpell] = useState(false);
  const [manageModal, setManageModal] = useState<{ type: ManageType; className: string; spellListClass?: string; classLevel?: number } | null>(null);

  useEffect(() => {
    if (pendingSpellManage && !manageModal) {
      const mcEntries = char.multiclassEntries ?? [];
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
    const parsed = char.subclassAlwaysPreparedSpells;
    const mcEntries = char.multiclassEntries ?? [];
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

  const classCasting = char.spellcasting;

  const hasSpells = classCasting.length > 0 || char.spellcastingAbility || spellsKnown.length > 0
    || subclassAlwaysPreparedGroups.length > 0;
  if (!hasSpells) {
    return <p className="font-body text-[15px] text-faint">This character is not a spellcaster.</p>;
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

  const mcEntries = char.multiclassEntries ?? [];
  for (const casting of classCasting) {
    const source = `class:${casting.className}`;
    if (!classGroups.some(g => g.source === source)) {
      classGroups.push({ source, className: casting.className, spells: [] });
    }
  }

  const regularSlots = Object.entries(spellSlots)
    .filter(([k]) => !k.startsWith('pact_'))
    .sort((a, b) => parseInt(a[0]) - parseInt(b[0]));
  const pactSlots = Object.entries(spellSlots)
    .filter(([k]) => k.startsWith('pact_'))
    .sort((a, b) => parseInt(a[0].replace('pact_', '')) - parseInt(b[0].replace('pact_', '')));

  function spendSlot(level: string) {
    const slot = spellSlots[level];
    if (!slot || slot.used >= slot.max) return;
    void runCommand(spendSlotCommand.mutateAsync({ characterId: char.id, slot: level }));
  }

  function restoreSlot(level: string) {
    void runCommand(restoreSlotCommand.mutateAsync({ characterId: char.id, slot: level }));
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

  // Prepares or unprepares one of a class's spells, with the rest of the class's prepared spells as they are; the
  // server holds the class to its limit
  function togglePrepared(spell: SpellEntry) {
    if (spell.alwaysPrepared || spell.level === 0 || !spell.source?.startsWith('class:')) return;
    const prepared = taggedSpells
      .filter(s => s.source === spell.source && s.level > 0 && s.prepared && !s.alwaysPrepared && s.name !== spell.name)
      .map(s => s.name);
    void runCommand(setPrepared.mutateAsync({
      characterId: char.id,
      prepared: { className: spell.source.slice(6), spells: spell.prepared ? prepared : [...prepared, spell.name] },
    }));
  }

  // A class the server gives no spellcasting still lists the spells the sheet has from it, with no limits
  function getClassCasterInfo(className: string) {
    const casting = classCasting.find(c => c.className === className);
    const thirdCasterSubclass = casting?.subclassName && THIRD_CASTER_SUBCLASSES.has(casting.subclassName)
      ? casting.subclassName : null;
    return {
      casting,
      isPrepared: casting?.preparedSpells != null,
      isKnown: casting ? casting.spellsKnown != null : true,
      thirdCasterSubclass,
    };
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
            <IconButton
              label={spell.prepared ? `Unprepare ${spell.name}` : `Prepare ${spell.name}`}
              onClick={(e) => { e.stopPropagation(); togglePrepared(spell); }}
              className={`w-3 h-3 rounded-full flex-shrink-0 border ${
                spell.prepared ? 'bg-buff border-buff' : 'bg-transparent border-rule'
              }`}
            />
          )
        )}
        <span className="font-body text-[15px] font-semibold text-ink group-hover:text-muted transition-colors">{spell.name}</span>
        {spell.atWill && <span className="font-heading text-[11px] font-semibold text-buff ml-auto">At will</span>}
        {spell.usesPerLongRest && <span className="font-heading text-[11px] font-semibold text-cls-cleric ml-auto">{spell.usesPerLongRest}/long rest</span>}
        {spell.unlocksAtLevel && spell.unlocksAtLevel > char.level && (
          <span className="font-heading text-[11px] font-semibold text-faint ml-auto">Lv {spell.unlocksAtLevel}</span>
        )}
      </button>
    );
  }

  function renderSlotButtons(slots: Array<[string, SpellSlot]>, label: string) {
    if (slots.length === 0) return null;
    return (
      <div className="bg-card border border-rule p-4">
        <h3 className="font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-faint mb-3">{label}</h3>
        <div className="grid grid-cols-3 sm:grid-cols-5 gap-3">
          {slots.map(([level, slot]) => {
            const displayLevel = level.startsWith('pact_') ? level.replace('pact_', '') : level;
            return (
              <div key={level} className="relative">
                <button
                  onClick={() => spendSlot(level)}
                  className="w-full bg-page border border-rule p-3 text-center hover:bg-page-alt transition-colors"
                >
                  <p className="font-heading text-[11px] font-semibold text-faint">{label === 'Pact Slots' ? 'Pact' : ''} Lv {displayLevel}</p>
                  <p className="font-heading text-[17px] font-bold text-ink">{slot.max - slot.used}/{slot.max}</p>
                </button>
                {slot.used > 0 && (
                  <IconButton
                    label={`Get back a ${label === 'Pact Slots' ? 'pact' : `level ${displayLevel}`} slot`}
                    onClick={() => restoreSlot(level)}
                    className="absolute top-1 right-1 p-0.5 text-faint hover:text-ink"
                  >
                    <Undo2 className="w-3 h-3" />
                  </IconButton>
                )}
              </div>
            );
          })}
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Spellcasting stats: each class's own, from the server */}
      {classCasting.length > 0 ? (
        <div className="bg-card border border-rule p-4 space-y-3">
          <h3 className="font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-faint mb-1">
            {classCasting.length > 1 ? 'Spellcasting by Class' : 'Spellcasting'}
          </h3>
          {classCasting.map(c => (
            <div key={c.className} className="grid grid-cols-4 gap-2">
              <StatCard label={c.className} value={c.ability} />
              <StatCard label="Save DC" value={String(c.spellSaveDc)} />
              <StatCard label="Attack" value={formatMod(c.spellAttackBonus)} />
              <StatCard label="Spell Level" value={c.highestSpellLevel > 0 ? String(c.highestSpellLevel) : '-'} />
            </div>
          ))}
          {(raceGroups.length > 0 || featGroups.length > 0) && char.spellSaveDc != null && char.spellAttackBonus != null && (
            <div className="grid grid-cols-4 gap-2">
              <StatCard label="Race & Feat" value={char.spellcastingAbility || '-'} />
              <StatCard label="Save DC" value={String(char.spellSaveDc)} />
              <StatCard label="Attack" value={formatMod(char.spellAttackBonus)} />
              <div />
            </div>
          )}
        </div>
      ) : (
        <div className="bg-card border border-rule p-4">
          <h3 className="font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-faint mb-3">Spellcasting</h3>
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
        const classLevel = info.casting?.classLevel ?? (classEntry ? classEntry.level : char.level);
        const prepLimit = info.casting?.preparedSpells ?? 0;
        const knownLimit = info.casting?.spellsKnown ?? 0;
        const cantripLimit = info.casting?.cantripsKnown;
        const spellListClass = spellListOf(info.casting, className);
        const displayName = info.thirdCasterSubclass ? `${className} (${info.thirdCasterSubclass})` : className;

        const isWizard = className === 'Wizard';

        return (
          <div key={className} className="bg-card border border-rule p-4 space-y-4">
            <div className="flex items-center justify-between">
              <h3 className="font-heading text-[16px] font-semibold text-ink">{displayName} Spells</h3>
              <div className="flex items-center gap-2">
                {isWizard && (
                  <>
                    <button
                      onClick={() => setManageModal({ type: 'spellbook', className })}
                      className="font-body text-[14px] font-semibold text-buff hover:text-ink transition-colors"
                    >
                      + Spellbook
                    </button>
                    <button
                      onClick={() => setManageModal({ type: 'remove-spellbook', className })}
                      className="font-body text-[14px] font-semibold text-debuff hover:text-ink transition-colors"
                    >
                      - Spellbook
                    </button>
                  </>
                )}
                <span className={`font-heading text-[11px] font-semibold tracking-[0.02em] px-2 py-0.5 border ${
                  info.isPrepared ? 'text-cls-wizard bg-cls-wizard-bg border-abj-border' : 'text-cls-warlock bg-cls-warlock-bg border-illu-border'
                }`}>
                  {info.isPrepared ? 'Prepared' : 'Known'}
                </span>
              </div>
            </div>

            {(cantrips.length > 0 || (cantripLimit && cantripLimit > 0)) && (
              <div>
                <div className="flex items-center justify-between mb-1">
                  <h4 className="font-heading text-[11px] font-semibold tracking-[0.08em] uppercase text-faint">
                    Cantrips ({cantrips.length}{cantripLimit ? `/${cantripLimit}` : ''})
                  </h4>
                  {info.casting && cantripLimit ? (
                    <button
                      onClick={() => setManageModal({ type: 'cantrips', className, spellListClass, classLevel })}
                      className="font-body text-[14px] font-semibold text-muted hover:text-ink transition-colors"
                    >
                      Manage Cantrips
                    </button>
                  ) : null}
                </div>
                <div className="space-y-0.5">{cantrips.map(s => renderSpellRow(s, false))}</div>
              </div>
            )}

            {info.isPrepared && (
              <div>
                <div className="flex items-center justify-between mb-1">
                  <h4 className="font-heading text-[11px] font-semibold tracking-[0.08em] uppercase text-faint">
                    Prepared ({prepared.length}/{prepLimit})
                  </h4>
                  <button
                    onClick={() => setManageModal({ type: 'prepared', className })}
                    className="font-body text-[14px] font-semibold text-muted hover:text-ink transition-colors"
                  >
                    Change Prepared
                  </button>
                </div>
                {leveled.length > 0 ? (
                  <div className="space-y-0.5">{leveled.map(s => renderSpellRow(s, true))}</div>
                ) : (
                  <p className="font-body text-[13px] text-faint">No spells prepared.</p>
                )}
              </div>
            )}

            {info.isKnown && (
              <div>
                <div className="flex items-center justify-between mb-1">
                  <h4 className="font-heading text-[11px] font-semibold tracking-[0.08em] uppercase text-faint">
                    Known Spells ({leveled.length}{knownLimit ? `/${knownLimit}` : ''})
                  </h4>
                  <button
                    onClick={() => setManageModal({ type: 'known', className, spellListClass, classLevel })}
                    className="font-body text-[14px] font-semibold text-muted hover:text-ink transition-colors"
                  >
                    Manage Known
                  </button>
                </div>
                {leveled.length > 0 ? (
                  <div className="space-y-0.5">{leveled.map(s => renderSpellRow(s, false))}</div>
                ) : (
                  <p className="font-body text-[13px] text-faint">No spells known.</p>
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
            <h3 className="font-heading text-[16px] font-semibold text-ink">{group.subclassName} Spells</h3>
            <span className="font-heading text-[11px] font-semibold tracking-[0.02em] px-2 py-0.5 border text-cls-cleric bg-cls-cleric-bg border-cls-cleric-border">Always Prepared</span>
          </div>
          <div className="space-y-0.5">
            {group.spells.map(s => (
              <button
                key={s.name}
                onClick={() => viewSpellDetail(s.name)}
                className="flex items-center gap-2 py-1.5 px-2 hover:bg-page-alt transition-colors w-full text-left group"
              >
                <Lock className="w-3 h-3 text-cls-cleric flex-shrink-0" />
                <span className="font-body text-[15px] font-semibold text-ink group-hover:text-muted transition-colors">{s.name}</span>
                <span className="font-heading text-[11px] font-semibold text-faint ml-auto">Lv {s.classLevel}</span>
              </button>
            ))}
          </div>
        </div>
      ))}

      {/* Race spell boxes */}
      {raceGroups.map(({ raceName, spells }) => (
        <div key={raceName} className="bg-card border border-rule p-4 space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="font-heading text-[16px] font-semibold text-ink">{raceName} Spells</h3>
            <span className="font-heading text-[11px] font-semibold tracking-[0.02em] px-2 py-0.5 border text-cls-druid bg-cls-druid-bg border-conj-border">Innate</span>
          </div>
          <div className="space-y-0.5">{spells.map(s => renderSpellRow(s, false))}</div>
        </div>
      ))}

      {/* Feat spell boxes */}
      {featGroups.map(({ featName, spells }) => (
        <div key={featName} className="bg-card border border-rule p-4 space-y-3">
          <div className="flex items-center gap-2">
            <h3 className="font-heading text-[16px] font-semibold text-ink">{featName}</h3>
            <span className="font-heading text-[11px] font-semibold tracking-[0.02em] px-2 py-0.5 border text-cls-cleric bg-cls-cleric-bg border-cls-cleric-border">Feat</span>
          </div>
          <div className="space-y-0.5">{spells.map(s => renderSpellRow(s, false))}</div>
        </div>
      ))}

      {taggedSpells.length === 0 && classGroups.every(g => g.spells.length === 0) && (
        <p className="font-body text-[15px] text-faint">No spells yet. Use the Change Prepared or Manage Known button to add spells.</p>
      )}

      {/* Spell Detail Modal */}
      {(detailSpell || loadingSpell) && (
        <div className="fixed inset-0 z-50 bg-black/70 flex items-center justify-center p-4" onClick={() => { setDetailSpell(null); setLoadingSpell(false); }}>
          <div role="dialog" aria-modal="true" aria-label="Spell details" className="max-w-2xl w-full max-h-[80vh] overflow-y-auto" onClick={e => e.stopPropagation()}>
            {loadingSpell ? (
              <div className="bg-card border border-rule p-8 text-center">
                <p className="font-body text-[15px] text-muted">Loading spell...</p>
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
          casting={classCasting.find(c => c.className === manageModal.className)}
          spellListClass={manageModal.spellListClass ?? spellListOf(
            classCasting.find(c => c.className === manageModal.className), manageModal.className)}
          currentSpells={taggedSpells}
          runCommand={runCommand}
          onClose={() => setManageModal(null)}
        />
      )}
    </div>
  );
}

/**
 * Chooses a class's spells, which one command saves: the spells it prepares, its known spells or cantrips, or what a
 * wizard copies into or takes out of its spellbook. The server checks each against the class's list, level and limit.
 */
function ManageSpellsModal({ char, type, className, casting, spellListClass, currentSpells, runCommand, onClose }: {
  char: PlayerCharacter;
  type: ManageType;
  className: string;
  // What the class casts with, from the server; a class without spellcasting has no limits
  casting?: ClassSpellcasting;
  spellListClass?: string;
  currentSpells: SpellEntry[];
  runCommand: RunCommand;
  onClose: () => void;
}) {
  const setPrepared = useSetPreparedSpells();
  const setKnown = useSetKnownSpells();
  const addToSpellbook = useAddToSpellbook();
  const removeFromSpellbook = useRemoveFromSpellbook();
  const [searchQuery, setSearchQuery] = useState('');
  const [searchResults, setSearchResults] = useState<Spell[]>([]);
  const [selectedLevel, setSelectedLevel] = useState<number | ''>('');
  const [localSpells, setLocalSpells] = useState<SpellEntry[]>(currentSpells);
  const [saving, setSaving] = useState(false);

  const source = `class:${className}`;
  const classSpells = localSpells.filter(s => s.source === source);
  const leveled = classSpells.filter(s => s.level > 0);
  const cantrips = classSpells.filter(s => s.level === 0);
  const preparedCount = leveled.filter(s => s.prepared || s.alwaysPrepared).length;
  const prepLimit = casting?.preparedSpells ?? 0;
  const knownLimit = casting?.spellsKnown ?? 0;
  const cantripLimit = casting?.cantripsKnown ?? 0;
  const isCantrips = type === 'cantrips';

  const isWizardPrepared = type === 'prepared' && className === 'Wizard';
  const isSpellbookMode = type === 'spellbook';
  const isRemoveSpellbook = type === 'remove-spellbook';
  const needsApiSearch = !isWizardPrepared && !isRemoveSpellbook;

  // The highest level the class itself can learn or prepare, from its own table, whatever slots a multiclass
  // character has (PHB p.164)
  const slots = char.spellSlots ?? {};
  const maxSlotLevel = casting ? casting.highestSpellLevel : Math.max(0, ...Object.keys(slots).map(k => {
    if (k.startsWith('pact_')) return parseInt(k.replace('pact_', ''));
    const n = parseInt(k);
    return isNaN(n) ? 0 : n;
  }));
  // Cantrips are chosen on their own; a spellbook, and the spells a class prepares or knows, are of 1st level or higher
  const minSpellLevel = isCantrips ? 0 : 1;
  const maxSpellLevel = isCantrips ? 0 : maxSlotLevel;
  const validLevels = Array.from({ length: Math.max(0, maxSpellLevel - minSpellLevel + 1) }, (_, i) => i + minSpellLevel);

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

  // Results follow the search box as you type; Enter searches straight away.
  // The spellbook modes filter on the page instead (filteredSpellbook)
  const { searchNow: doSearch, searching } = useTypeAhead(searchQuery, () => {
    if (!needsApiSearch) return;
    const params: Record<string, unknown> = { className: spellListClass || className, size: 50 };
    if (searchQuery.trim()) params.name = searchQuery.trim();
    if (selectedLevel !== '') {
      params.level = selectedLevel;
    } else {
      params.level = validLevels.join(',');
    }
    return searchSpells(params as any)
      .then(res => res.content.filter(s => s.level >= minSpellLevel && s.level <= maxSpellLevel));
  }, setSearchResults);

  // Loads the list when the modal opens, and again whenever the level filter changes
  useEffect(() => {
    doSearch();
  }, [selectedLevel, doSearch]);

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
        if (isCantrips ? cantrips.length >= cantripLimit : knownLimit > 0 && leveled.length >= knownLimit) return;
        setLocalSpells([...localSpells, { name: spell.name, level: spell.level, source }]);
      }
    }
  }

  async function handleSave() {
    setSaving(true);
    const characterId = char.id;
    const classNames = (spells: SpellEntry[], levelled: boolean) => spells
      .filter(s => s.source === source && (levelled ? s.level > 0 : s.level === 0))
      .map(s => s.name);
    let saved: boolean;
    if (type === 'prepared') {
      const spells = localSpells
        .filter(s => s.source === source && s.level > 0 && s.prepared && !s.alwaysPrepared)
        .map(s => s.name);
      saved = await runCommand(setPrepared.mutateAsync({ characterId, prepared: { className, spells } }));
    } else if (type === 'known') {
      saved = await runCommand(setKnown.mutateAsync({
        characterId, known: { className, spells: classNames(localSpells, true) },
      }));
    } else if (isCantrips) {
      saved = await runCommand(setKnown.mutateAsync({
        characterId, known: { className, cantrips: classNames(localSpells, false) },
      }));
    } else {
      // What the spellbook gained and lost
      const before = classNames(currentSpells, true);
      const after = classNames(localSpells, true);
      const added = after.filter(name => !before.includes(name));
      const removed = before.filter(name => !after.includes(name));
      saved = true;
      if (added.length > 0) {
        saved = await runCommand(addToSpellbook.mutateAsync({ characterId, spells: added }));
      }
      if (saved && removed.length > 0) {
        saved = await runCommand(removeFromSpellbook.mutateAsync({ characterId, spells: removed }));
      }
    }
    setSaving(false);
    if (saved) onClose();
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
          : isCantrips
            ? 'Manage Cantrips'
            : 'Manage Known Spells';
  const modalSubtitle = isSpellbookMode
    ? `${leveled.length} spells in spellbook`
    : isRemoveSpellbook
      ? `${leveled.length} spells in spellbook`
      : type === 'prepared'
        ? `${preparedCount}/${prepLimit} prepared`
        : isCantrips
          ? `${cantrips.length}/${cantripLimit} known`
          : `${leveled.length}${knownLimit ? `/${knownLimit}` : ''} known`;

  return (
    <div className="fixed inset-0 z-50 bg-black/70 flex items-center justify-center p-4">
      <div role="dialog" aria-modal="true" aria-labelledby="manage-spells-title" className="bg-card border border-rule w-full max-w-lg max-h-[85vh] flex flex-col">
        {/* Header */}
        <div className="px-4 py-3 border-b border-rule flex items-center justify-between">
          <div>
            <h2 id="manage-spells-title" className="font-heading text-[17px] font-semibold text-ink">{modalTitle}</h2>
            <p className="font-body text-[13px] font-semibold text-faint mt-0.5">{modalSubtitle}</p>
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
                onKeyDown={e => e.key === 'Enter' && doSearch()}
                placeholder={needsApiSearch ? "Search spells..." : "Filter spellbook..."}
                className="w-full bg-page border border-rule pl-8 pr-3 py-1.5 font-body text-[15px] font-semibold text-ink placeholder-faint focus:outline-none focus:border-muted"
              />
            </div>
            {(isWizardPrepared || isRemoveSpellbook) && (
              <select
                value={selectedLevel}
                onChange={e => setSelectedLevel(e.target.value === '' ? '' : parseInt(e.target.value))}
                className="bg-page border border-rule px-2 py-1.5 font-body text-[15px] font-semibold text-ink focus:outline-none focus:border-muted"
              >
                <option value="">All Levels</option>
                {validLevels.filter(l => l > 0).map(l => (
                  <option key={l} value={l}>Level {l}</option>
                ))}
              </select>
            )}
            {needsApiSearch && (
              <select
                value={selectedLevel}
                onChange={e => setSelectedLevel(e.target.value === '' ? '' : parseInt(e.target.value))}
                className="bg-page border border-rule px-2 py-1.5 font-body text-[15px] font-semibold text-ink focus:outline-none focus:border-muted"
              >
                <option value="">All Levels</option>
                {validLevels.map(l => (
                  <option key={l} value={l}>{l === 0 ? 'Cantrips' : `Level ${l}`}</option>
                ))}
              </select>
            )}
          </div>
        </div>

        {/* Results. The last ones stay up while a new search runs, so typing doesn't flicker */}
        <div className="flex-1 overflow-y-auto px-4 py-2">
          {searching && displayResults.length === 0 ? (
            <p className="font-body text-[15px] text-muted py-4 text-center">Searching...</p>
          ) : displayResults.length === 0 ? (
            <p className="font-body text-[15px] text-faint py-4 text-center">
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
                } else if (isCantrips) {
                  disabled = cantrips.length >= cantripLimit && !added;
                } else {
                  disabled = isAlwaysPrep || (knownLimit > 0 && leveled.length >= knownLimit && !added);
                }

                return (
                  <button
                    key={spell.id}
                    onClick={() => !disabled && toggleSpell(spell)}
                    disabled={disabled}
                    className={`w-full flex items-center justify-between py-2 px-3 font-body text-[15px] font-semibold transition-colors ${
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
                      <span className="text-[13px] text-faint">
                        {spell.level === 0 ? 'Cantrip' : `Lv ${spell.level}`}
                      </span>
                    </div>
                    {isSpellbookMode ? (
                      added ? (
                        <span className="text-[13px] text-buff">In Spellbook</span>
                      ) : (
                        <Plus className="w-4 h-4 text-faint" />
                      )
                    ) : isRemoveSpellbook ? (
                      added ? (
                        <span className="text-[13px] text-debuff">Remove</span>
                      ) : (
                        <span className="text-[13px] text-faint">Removed</span>
                      )
                    ) : type === 'prepared' || isWizardPrepared ? (
                      isAlwaysPrep ? (
                        <span className="text-[13px] text-cls-cleric">Always</span>
                      ) : prepared ? (
                        <span className="text-[13px] text-buff">Prepared</span>
                      ) : added ? (
                        <span className="text-[13px] text-faint">In list</span>
                      ) : (
                        <Plus className="w-4 h-4 text-faint" />
                      )
                    ) : added ? (
                      <span className="text-[13px] text-debuff">Remove</span>
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
          <button onClick={onClose} className="px-3 py-1.5 font-body text-[15px] font-semibold text-muted hover:text-ink transition-colors">
            Cancel
          </button>
          <button
            onClick={handleSave}
            disabled={saving}
            className="px-4 py-1.5 bg-ink text-card font-body text-[15px] font-semibold hover:opacity-80 transition-colors disabled:opacity-50"
          >
            {saving ? 'Saving...' : 'Save Changes'}
          </button>
        </div>
      </div>
    </div>
  );
}
