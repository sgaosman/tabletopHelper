import { useState, useEffect, useMemo } from 'react';
import { Lock, Search, Plus } from 'lucide-react';
import { apiErrorMessage } from '../../api/errors';
import {
  useAddToSpellbook, useApplyChoices, useRemoveFromSpellbook, useSetPreparedSpells,
} from '../../queries/characters';
import { useSpellSearch } from '../../queries/reference';
import type { ClassSpellcasting, PendingChoice, PlayerCharacter } from '../../types/character';
import type { KnownSpell as SpellEntry } from '../../types/encounter';
import type { Spell, SpellSearchParams } from '../../types/reference';
import { isSpellbookChoice as copiesToSpellbook } from '../../utils/pendingChoices';
import Button from '../ui/Button';
import Modal from '../ui/Modal';

export type ManageType = 'prepared' | 'spellbook' | 'remove-spellbook' | 'choice';

interface Props {
  char: PlayerCharacter;
  type: ManageType;
  className: string;
  // What the class casts with, from the server; a class without spellcasting has no limits
  casting?: ClassSpellcasting;
  spellListClass?: string;
  currentSpells: SpellEntry[];
  /** How the prepared and spellbook modes send their command and show how it went: the sheet's RunCommand */
  runCommand?: (command: Promise<unknown>) => Promise<boolean>;
  /** For 'choice': the CANTRIPS or SPELLS Pending Choice the spells answer (D159) */
  choice?: PendingChoice;
  /** For 'choice': the character the server answered with */
  onComplete?: (updated: PlayerCharacter) => void;
  /** For 'choice': leaves the choice pending for later */
  onSkip?: () => void;
  onClose: () => void;
}

/**
 * Chooses a class's spells, which one command saves: the spells it prepares, what a wizard copies into or takes out
 * of its spellbook, or, in the 'choice' mode, the cantrips or spells one of its levels' Pending Choices owes (D159),
 * exactly as many as the choice counts and of a level the class could learn then, with a known caster's optional swap
 * of one spell it knows. The server checks each against the class's list, level and limit. Until R8's spell picker.
 */
export default function ManageSpellsModal({
  char, type, className, casting, spellListClass, currentSpells, runCommand, choice, onComplete, onSkip, onClose,
}: Props) {
  const setPrepared = useSetPreparedSpells();
  const addToSpellbook = useAddToSpellbook();
  const removeFromSpellbook = useRemoveFromSpellbook();
  const applyChoices = useApplyChoices();
  const [searchQuery, setSearchQuery] = useState('');
  // The name the spell search sends: the search box's text, shortly after typing stops, or at once on Enter
  const [searchName, setSearchName] = useState('');
  const [selectedLevel, setSelectedLevel] = useState<number | ''>('');
  const [localSpells, setLocalSpells] = useState<SpellEntry[]>(currentSpells);
  const [saving, setSaving] = useState(false);
  // The choice's answer: the spells picked, and a swap of one known spell for a replacement
  const [picked, setPicked] = useState<Spell[]>([]);
  const [replace, setReplace] = useState('');
  const [replacement, setReplacement] = useState<Spell | null>(null);

  const source = `class:${className}`;
  const classSpells = localSpells.filter(s => s.source === source);
  const leveled = classSpells.filter(s => s.level > 0);
  const preparedCount = leveled.filter(s => s.prepared || s.alwaysPrepared).length;
  const prepLimit = casting?.preparedSpells ?? 0;

  const isChoice = type === 'choice' && !!choice;
  const isCantripChoice = isChoice && choice?.type === 'CANTRIPS';
  const isSpellbookChoice = isChoice && !!choice && copiesToSpellbook(choice);
  const choiceCount = choice?.count ?? 0;
  const isWizardPrepared = type === 'prepared' && className === 'Wizard';
  const isSpellbookMode = type === 'spellbook';
  const isRemoveSpellbook = type === 'remove-spellbook';
  const needsApiSearch = !isWizardPrepared && !isRemoveSpellbook;

  // The highest level the class itself can learn or prepare, from its own table, whatever slots a multiclass
  // character has (PHB p.164); a choice's spells go up to the highest level the class could learn at its level
  const slots = char.spellSlots ?? {};
  const maxSlotLevel = casting ? casting.highestSpellLevel : Math.max(0, ...Object.keys(slots).map(k => {
    if (k.startsWith('pact_')) return parseInt(k.replace('pact_', ''));
    const n = parseInt(k);
    return isNaN(n) ? 0 : n;
  }));
  // Cantrips are chosen on their own; a spellbook, and the spells a class prepares or learns, are of 1st level or higher
  const minSpellLevel = isCantripChoice ? 0 : 1;
  const maxSpellLevel = isCantripChoice ? 0 : isChoice ? (choice?.maxSpellLevel ?? maxSlotLevel) : maxSlotLevel;
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

  // Results follow the search box as you type; Enter searches straight away. The list loads when the modal opens,
  // and again whenever the level filter changes. The spellbook modes filter on the page instead (filteredSpellbook)
  useEffect(() => {
    const timer = setTimeout(() => setSearchName(searchQuery.trim()), 250);
    return () => clearTimeout(timer);
  }, [searchQuery]);
  const searchParams: SpellSearchParams = {
    className: spellListClass || className,
    size: 50,
    level: selectedLevel !== '' ? selectedLevel : validLevels.join(','),
    ...(searchName ? { name: searchName } : {}),
  };
  const spellsQuery = useSpellSearch(searchParams, { enabled: needsApiSearch });
  const searching = spellsQuery.isFetching;
  const searchResults = useMemo(
    () => (spellsQuery.data?.content ?? []).filter(s => s.level >= minSpellLevel && s.level <= maxSpellLevel),
    [spellsQuery.data, minSpellLevel, maxSpellLevel],
  );

  function isAdded(spell: Spell): boolean {
    return classSpells.some(s => s.name === spell.name);
  }

  function isPreparedSpell(spell: Spell): boolean {
    return classSpells.some(s => s.name === spell.name && (s.prepared || s.alwaysPrepared));
  }

  function isPicked(spell: Spell): boolean {
    return picked.some(s => s.name === spell.name);
  }

  // The spells a known caster may replace: those of the class it knows, not always prepared
  const replaceable = leveled.filter(s => !s.alwaysPrepared);
  const replacementOptions = (() => {
    const options = searchResults.filter(s => s.level > 0 && !isAdded(s) && !isPicked(s));
    return replacement && !options.some(s => s.name === replacement.name) ? [replacement, ...options] : options;
  })();

  function toggleSpell(spell: Spell) {
    if (isChoice) {
      if (isPicked(spell)) {
        setPicked(picked.filter(s => s.name !== spell.name));
      } else if (!isAdded(spell) && picked.length < choiceCount) {
        setPicked([...picked, spell]);
        if (replacement?.name === spell.name) setReplacement(null);
      }
    } else if (isSpellbookMode) {
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
    }
  }

  function handleChoice() {
    if (!choice) return;
    const swap = replace && replacement ? { replace, with: replacement.name } : undefined;
    applyChoices.mutate(
      { characterId: char.id, choices: { choiceId: choice.id, spells: picked.map(s => s.name), ...(swap ? { swap } : {}) } },
      { onSuccess: updated => onComplete?.(updated) },
    );
  }

  async function handleSave() {
    if (isChoice) {
      handleChoice();
      return;
    }
    if (!runCommand) return;
    setSaving(true);
    const characterId = char.id;
    const classNames = (spells: SpellEntry[]) => spells
      .filter(s => s.source === source && s.level > 0)
      .map(s => s.name);
    let saved: boolean;
    if (type === 'prepared') {
      const spells = localSpells
        .filter(s => s.source === source && s.level > 0 && s.prepared && !s.alwaysPrepared)
        .map(s => s.name);
      saved = await runCommand(setPrepared.mutateAsync({ characterId, prepared: { className, spells } }));
    } else {
      // What the spellbook gained and lost
      const before = classNames(currentSpells);
      const after = classNames(localSpells);
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
  // A choice's answer is complete with exactly its count, and a swap with both of its spells or neither
  const choiceComplete = picked.length === choiceCount && (!replace || !!replacement);
  const choiceSaving = applyChoices.isPending || applyChoices.isSuccess;
  const levelText = choice?.className ? `${choice.className} ${choice.classLevel}` : className;
  const modalTitle = isChoice
    ? isCantripChoice ? 'Learn Cantrips' : isSpellbookChoice ? 'Add to Spellbook' : 'Learn Spells'
    : isSpellbookMode
      ? 'Add to Spellbook'
      : isRemoveSpellbook
        ? 'Remove from Spellbook'
        : isWizardPrepared
          ? 'Prepare from Spellbook'
          : 'Change Prepared Spells';
  const modalSubtitle = isChoice
    ? `${levelText}: choose ${choiceCount} (${picked.length}/${choiceCount})${
      isCantripChoice ? '' : `, up to level ${maxSpellLevel}`}`
    : isSpellbookMode || isRemoveSpellbook
      ? `${leveled.length} spells in spellbook`
      : `${preparedCount}/${prepLimit} prepared`;

  return (
    <Modal
      title={modalTitle}
      subtitle={modalSubtitle}
      onClose={onClose}
      size="lg"
      footer={(
        <>
          <Button variant="secondary" onClick={isChoice ? onSkip ?? onClose : onClose}>
            {isChoice ? 'Skip' : 'Cancel'}
          </Button>
          <Button onClick={handleSave} disabled={isChoice ? !choiceComplete || choiceSaving : saving}>
            {isChoice
              ? choiceSaving ? 'Saving...' : isCantripChoice ? 'Learn Cantrips' : isSpellbookChoice ? 'Add to Spellbook' : 'Learn Spells'
              : saving ? 'Saving...' : 'Save Changes'}
          </Button>
        </>
      )}
    >
      {isChoice && applyChoices.error && (
        <div role="alert" className="mb-3 bg-debuff/10 border border-debuff text-debuff p-3 font-body text-[15px] font-semibold">
          {apiErrorMessage(applyChoices.error)}
        </div>
      )}

      {/* Search bar */}
      <div className="pb-3 border-b border-rule space-y-2">
        <div className="flex gap-2">
          <div className="relative flex-1">
            <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-4 h-4 text-faint" />
            <input
              value={searchQuery}
              onChange={e => setSearchQuery(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && setSearchName(searchQuery.trim())}
              placeholder={needsApiSearch ? "Search spells..." : "Filter spellbook..."}
              aria-label={needsApiSearch ? 'Search spells' : 'Filter spellbook'}
              className="w-full bg-page border border-rule pl-8 pr-3 py-1.5 font-body text-[15px] font-semibold text-ink placeholder-faint focus:outline-none focus:border-muted"
            />
          </div>
          {(isWizardPrepared || isRemoveSpellbook) && (
            <select
              value={selectedLevel}
              onChange={e => setSelectedLevel(e.target.value === '' ? '' : parseInt(e.target.value))}
              aria-label="Spell level"
              className="bg-page border border-rule px-2 py-1.5 font-body text-[15px] font-semibold text-ink focus:outline-none focus:border-muted"
            >
              <option value="">All Levels</option>
              {validLevels.filter(l => l > 0).map(l => (
                <option key={l} value={l}>Level {l}</option>
              ))}
            </select>
          )}
          {needsApiSearch && !isCantripChoice && (
            <select
              value={selectedLevel}
              onChange={e => setSelectedLevel(e.target.value === '' ? '' : parseInt(e.target.value))}
              aria-label="Spell level"
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
      <div className="py-2">
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
              const chosen = isPicked(spell);

              let disabled = false;
              if (isChoice) {
                disabled = added || (!chosen && picked.length >= choiceCount);
              } else if (isSpellbookMode) {
                disabled = false;
              } else if (isRemoveSpellbook) {
                disabled = !added;
              } else if (type === 'prepared') {
                disabled = isAlwaysPrep || (preparedCount >= prepLimit && !prepared);
              }

              return (
                <button
                  key={spell.id}
                  onClick={() => !disabled && toggleSpell(spell)}
                  disabled={disabled}
                  aria-pressed={isChoice ? chosen : undefined}
                  className={`w-full flex items-center justify-between py-2 px-3 font-body text-[15px] font-semibold transition-colors ${
                    isAlwaysPrep && (type === 'prepared' || isWizardPrepared)
                      ? 'bg-cls-cleric-bg text-cls-cleric cursor-not-allowed'
                      : isChoice
                        ? chosen
                          ? 'bg-cls-wizard-bg text-cls-wizard hover:opacity-80'
                          : disabled ? 'text-faint cursor-not-allowed' : 'text-muted hover:bg-page-alt'
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
                  {isChoice ? (
                    added ? (
                      <span className="text-[13px] text-faint">{isSpellbookChoice ? 'In Spellbook' : 'Known'}</span>
                    ) : chosen ? (
                      <span className="text-[13px] text-buff">Chosen</span>
                    ) : (
                      <Plus className="w-4 h-4 text-faint" />
                    )
                  ) : isSpellbookMode ? (
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
                  ) : isAlwaysPrep ? (
                    <span className="text-[13px] text-cls-cleric">Always</span>
                  ) : prepared ? (
                    <span className="text-[13px] text-buff">Prepared</span>
                  ) : added ? (
                    <span className="text-[13px] text-faint">In list</span>
                  ) : (
                    <Plus className="w-4 h-4 text-faint" />
                  )}
                </button>
              );
            })}
          </div>
        )}
      </div>

      {/* A known caster may also replace one spell it knows as it gains a level */}
      {isChoice && choice?.swap && (
        <div className="pt-3 border-t border-rule space-y-2">
          <h3 className="font-heading text-[11px] font-semibold tracking-[0.08em] uppercase text-faint">
            Replace a known spell (optional)
          </h3>
          <div className="flex gap-2 flex-wrap">
            <select
              value={replace}
              onChange={e => { setReplace(e.target.value); if (!e.target.value) setReplacement(null); }}
              aria-label="Spell to replace"
              className="flex-1 min-w-40 bg-page border border-rule px-2 py-1.5 font-body text-[15px] font-semibold text-ink focus:outline-none focus:border-muted"
            >
              <option value="">Keep every spell</option>
              {replaceable.map(s => <option key={s.name} value={s.name}>{s.name}</option>)}
            </select>
            <select
              value={replacement?.name ?? ''}
              onChange={e => setReplacement(replacementOptions.find(s => s.name === e.target.value) ?? null)}
              disabled={!replace}
              aria-label="Replacement spell"
              className="flex-1 min-w-40 bg-page border border-rule px-2 py-1.5 font-body text-[15px] font-semibold text-ink focus:outline-none focus:border-muted disabled:opacity-50"
            >
              <option value="">Choose its replacement</option>
              {replacementOptions.map(s => <option key={s.name} value={s.name}>{s.name} (Lv {s.level})</option>)}
            </select>
          </div>
          <p className="font-body text-[13px] text-faint">Search above to list more replacements.</p>
        </div>
      )}

    </Modal>
  );
}
