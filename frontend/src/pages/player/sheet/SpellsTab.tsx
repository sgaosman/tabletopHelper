import { useState, useMemo } from 'react';
import { Lock, Undo2 } from 'lucide-react';
import { useRestoreSlot, useSetPreparedSpells, useSpendSlot } from '../../../queries/characters';
import SpellCard from '../../../components/reference/SpellCard';
import IconButton from '../../../components/ui/IconButton';
import Modal from '../../../components/ui/Modal';
import type { PlayerCharacter } from '../../../types/character';
import type { SpellSlot } from '../../../types/encounter';
import { slotsLeft, slotTotal } from '../../../utils/metamagic';
import { useClassTables, useSpellByName } from '../../../queries/reference';
import { isThirdCasterSubclass } from '../../../utils/classTables';
import { formatMod } from '../../../utils/dndRules';
import type { RunCommand, SpellEntry } from './types';
import StatCard from './StatCard';
import ManageSpellsModal, { type ManageType } from '../../../components/character/ManageSpellsModal';

/**
 * The character's spells by where they come from. What each class casts with, its save DC, attack bonus, highest
 * spell level and cantrip, known and prepared limits, is the server's (char.spellcasting, D167); race and feat spells
 * use the main numbers. Every change is a command the server checks (D158): a slot spent or got back, the spells a
 * class prepares, and a wizard's spellbook. The cantrips and spells a class learns come with its levels' Pending
 * Choices, made from the sheet's banner (D159). Spells from a feat come with the feat, at a level's Ability Score
 * Improvement or a race's or background's FEAT choice, or as the DM's grant.
 */
export default function SpellsTab({ char, spellsKnown, spellSlots, runCommand }: {
  char: PlayerCharacter;
  spellsKnown: SpellEntry[];
  spellSlots: Record<string, SpellSlot>;
  runCommand: RunCommand;
}) {
  const spendSlotCommand = useSpendSlot();
  const restoreSlotCommand = useRestoreSlot();
  const setPrepared = useSetPreparedSpells();
  const { data: tables } = useClassTables();
  // The spell whose details are open, by name
  const [detailName, setDetailName] = useState<string | null>(null);
  const detail = useSpellByName(detailName);
  const [manageModal, setManageModal] = useState<{ type: ManageType; className: string } | null>(null);

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
    if (!slot || slotsLeft(slot) <= 0) return;
    void runCommand(spendSlotCommand.mutateAsync({ characterId: char.id, slot: level }));
  }

  function restoreSlot(level: string) {
    void runCommand(restoreSlotCommand.mutateAsync({ characterId: char.id, slot: level }));
  }

  function viewSpellDetail(spellName: string) {
    if (spellName) setDetailName(spellName);
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
    const thirdCasterSubclass = tables && casting?.subclassName
      && isThirdCasterSubclass(tables, className, casting.subclassName) ? casting.subclassName : null;
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
                  <p className="font-heading text-[17px] font-bold text-ink">{slotsLeft(slot)}/{slotTotal(slot)}</p>
                  {(slot.created ?? 0) > 0 && (
                    <p className="font-body text-[11px] text-faint">{slot.created} created, gone after a long rest</p>
                  )}
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
        const prepLimit = info.casting?.preparedSpells ?? 0;
        const knownLimit = info.casting?.spellsKnown ?? 0;
        const cantripLimit = info.casting?.cantripsKnown;
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
                <h4 className="font-heading text-[11px] font-semibold tracking-[0.08em] uppercase text-faint mb-1">
                  Cantrips ({cantrips.length}{cantripLimit ? `/${cantripLimit}` : ''})
                </h4>
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
                <h4 className="font-heading text-[11px] font-semibold tracking-[0.08em] uppercase text-faint mb-1">
                  Known Spells ({leveled.length}{knownLimit ? `/${knownLimit}` : ''})
                </h4>
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
        <p className="font-body text-[15px] text-faint">No spells yet. A class learns its cantrips and spells through the choices its levels bring, and a prepared caster picks its spells with Change Prepared.</p>
      )}

      {/* The spell's details, titled with its name: focus stays inside, and Escape closes it */}
      {detailName && (
        <Modal title={detailName} onClose={() => setDetailName(null)} size="xl" flush>
          {detail.data ? (
            <SpellCard spell={detail.data} titled />
          ) : (
            <p className="font-body text-[15px] text-muted p-8 text-center">
              {detail.isPending ? 'Loading spell...' : detail.isError ? 'The spell could not be loaded.' : 'There is no spell of that name.'}
            </p>
          )}
        </Modal>
      )}

      {/* Manage Spells Modal */}
      {manageModal && (
        <ManageSpellsModal
          char={char}
          type={manageModal.type}
          className={manageModal.className}
          casting={classCasting.find(c => c.className === manageModal.className)}
          currentSpells={taggedSpells}
          runCommand={runCommand}
          onClose={() => setManageModal(null)}
        />
      )}
    </div>
  );
}
