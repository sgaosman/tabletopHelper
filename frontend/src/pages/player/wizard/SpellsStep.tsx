import type { Dispatch } from 'react';
import { X } from 'lucide-react';
import type { CharacterClassRef, Spell } from '../../../types/reference';
import SpellPicker from '../../../components/character/SpellPicker';
import IconButton from '../../../components/ui/IconButton';
import { useClassTables } from '../../../queries/reference';
import {
  type ClassTables, cantripsKnown, highestSpellLevel, spellbookSize, spellList, spellsKnown,
} from '../../../utils/classTables';
import type { WizardAction, WizardState } from './wizardReducer';

// The wizard chooses the 1st level's spells; every later level's are Pending Choices made on the sheet (D159)
const FIRST_LEVEL = 1;

export interface SpellsStepProps {
  state: WizardState;
  dispatch: Dispatch<WizardAction>;
}

/**
 * The cantrips and spells of the first class's 1st level, as many as that level gives whatever the character's level.
 * The cantrips and spells of every later level, of a second class, and an Eldritch Knight's or Arcane Trickster's are
 * Pending Choices, made on the character sheet after creation (D159), as are a feat's spells, which come with the
 * feat.
 */
export default function SpellsStep({ state, dispatch }: SpellsStepProps) {
  const { selectedClass, classEntries, level, selectedCantrips, selectedSpells } = state;
  const later = level > 1 || classEntries.length > 1;
  const { data: tables, isError } = useClassTables();
  if (!tables) {
    return (
      <p className="font-body text-[15px] font-semibold text-muted">
        {isError ? 'The class tables could not be loaded. Reload the page to try again.' : 'Loading the class tables…'}
      </p>
    );
  }
  return (
    <div className="space-y-8">
      {selectedClass?.isSpellcaster && (() => {
        const maxLvl = highestSpellLevel(tables, selectedClass.name, FIRST_LEVEL);
        const cantrips = cantripsKnown(tables, selectedClass.name, FIRST_LEVEL);
        if (maxLvl === 0 && cantrips === 0) {
          return (
            <div className="bg-card border border-rule p-4">
              <h2 className="font-heading text-[17px] font-semibold text-ink mb-2">
                {classEntries.length > 1 ? `${selectedClass.name} Spells` : 'Spells'}
              </h2>
              <p className="font-body text-[15px] font-semibold text-muted">
                {selectedClass.name} does not gain spellcasting at level 1, so no spell selection is needed now.
                {later && ' The spells of later levels are chosen on your character sheet after creation.'}
              </p>
            </div>
          );
        }
        return (
          <SpellSelectionSection
            // A new class starts a new search
            key={selectedClass.id}
            tables={tables}
            selectedClass={selectedClass}
            level={FIRST_LEVEL}
            selectedCantrips={selectedCantrips}
            selectedSpells={selectedSpells}
            dispatch={dispatch}
            title={classEntries.length > 1 ? `${selectedClass.name} Spells` : undefined}
          />
        );
      })()}
      {later && (
        <div className="bg-cls-monk-bg border border-div-border p-4">
          <p className="font-body text-[13px] font-semibold text-muted">
            The cantrips and spells of your levels above the 1st{classEntries.length > 1 ? ', and of your other classes,' : ''}
            {' '}are chosen on your character sheet after creation.
          </p>
        </div>
      )}
      {(() => {
        const warnings: string[] = [];
        if (selectedClass?.isSpellcaster) {
          const cantripsAllowed = cantripsKnown(tables, selectedClass.name, FIRST_LEVEL);
          const isWiz = selectedClass.name === 'Wizard';
          const spellsAllowed = isWiz ? spellbookSize(tables, selectedClass.name, FIRST_LEVEL)
            : selectedClass.isKnownCaster ? spellsKnown(tables, selectedClass.name, FIRST_LEVEL) : 0;
          if (cantripsAllowed > 0 && selectedCantrips.length < cantripsAllowed) {
            warnings.push(`${selectedClass.name}: ${selectedCantrips.length}/${cantripsAllowed} cantrips selected`);
          }
          if (spellsAllowed > 0 && selectedSpells.length < spellsAllowed) {
            warnings.push(`${selectedClass.name}: ${selectedSpells.length}/${spellsAllowed} spells selected`);
          }
        }
        if (warnings.length === 0) return null;
        return (
          <div className="bg-buff-bg border border-buff-border p-3">
            <p className="font-body text-[13px] font-semibold text-hp-wounded">
              You haven't selected all available spells. You can still proceed, but your character may be underpowered.
            </p>
            <ul className="font-body text-[13px] font-semibold text-buff mt-1 list-disc list-inside">
              {warnings.map(w => <li key={w}>{w}</li>)}
            </ul>
          </div>
        );
      })()}
    </div>
  );
}

/**
 * One class's cantrip and spell pickers. The chosen spells are the wizard's; each picker's search and results are its
 * own, one request for all the levels it offers, cached for when the step is opened again.
 */
function SpellSelectionSection({
  tables, selectedClass, level, selectedCantrips, selectedSpells, dispatch, title,
}: {
  tables: ClassTables;
  selectedClass: CharacterClassRef;
  level: number;
  selectedCantrips: Spell[];
  selectedSpells: Spell[];
  dispatch: Dispatch<WizardAction>;
  title?: string;
}) {
  const cantripsAllowed = cantripsKnown(tables, selectedClass.name, level);
  const isWizard = selectedClass.name === 'Wizard';
  const spellbook = spellbookSize(tables, selectedClass.name, level);
  const spellsAllowed = isWizard
    ? spellbook
    : selectedClass.isKnownCaster
    ? spellsKnown(tables, selectedClass.name, level)
    : 0;
  const isPrepared = selectedClass.isPreparedCaster && !isWizard;
  const maxLevel = highestSpellLevel(tables, selectedClass.name, level);
  // The list the server names for the class
  const list = spellList(tables, selectedClass.name);
  const spellLevels = Array.from({ length: maxLevel }, (_, i) => i + 1);

  function toggleCantrip(spell: Spell) {
    dispatch({ type: 'toggleCantrip', spell, max: cantripsAllowed });
  }

  function toggleSpell(spell: Spell) {
    dispatch({ type: 'toggleSpell', spell, max: spellsAllowed === 0 ? Infinity : spellsAllowed });
  }

  return (
    <div className="space-y-6">
      <h2 className="font-heading text-[17px] font-semibold text-ink">{title ?? 'Choose Spells'}</h2>
      <p className="font-body text-[15px] font-semibold text-muted">
        {isWizard
          ? `Select spells for your starting spellbook. You begin with ${spellbook} spells.`
          : `Select your starting ${selectedClass.name} spells.`}
      </p>

      {cantripsAllowed > 0 && (
        <div className="bg-card border border-rule p-4 space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="font-heading text-[15px] font-semibold text-ink">Cantrips</h3>
            <span className="font-heading text-[11px] font-semibold text-faint">{selectedCantrips.length}/{cantripsAllowed} selected</span>
          </div>
          <ChosenSpells spells={selectedCantrips} onRemove={toggleCantrip} />
          <SpellPicker
            value={selectedCantrips.map(s => s.name)}
            onChange={(_, spell) => toggleCantrip(spell)}
            max={cantripsAllowed}
            filter={{ className: list, levels: [0] }}
            placeholder="Search cantrips..."
          />
        </div>
      )}

      {spellsAllowed > 0 && (
        <div className="bg-card border border-rule p-4 space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="font-heading text-[15px] font-semibold text-ink">
              {isWizard
                ? (maxLevel > 1 ? `Spellbook (Level 1-${maxLevel})` : 'Spellbook (Level 1)')
                : (maxLevel > 1 ? `Spells (Level 1-${maxLevel})` : 'Level 1 Spells')}
            </h3>
            <span className="font-heading text-[11px] font-semibold text-faint">{selectedSpells.length}/{spellsAllowed} selected</span>
          </div>
          <ChosenSpells spells={selectedSpells} onRemove={toggleSpell} />
          <SpellPicker
            value={selectedSpells.map(s => s.name)}
            onChange={(_, spell) => toggleSpell(spell)}
            max={spellsAllowed}
            filter={{ className: list, levels: spellLevels }}
            placeholder={maxLevel > 1 ? `Search level 1-${maxLevel} spells...` : 'Search level 1 spells...'}
          />
        </div>
      )}

      {isPrepared && (
        <div className="bg-card border border-rule p-4">
          <h3 className="font-heading text-[15px] font-semibold text-ink mb-2">Prepared Spells</h3>
          <p className="font-body text-[15px] font-semibold text-muted">
            As a {selectedClass.name}, you prepare spells each day from your class spell list.
            You can change your prepared spells from the character sheet after creation.
          </p>
        </div>
      )}
    </div>
  );
}

/** The spells chosen so far, each with a button that takes it off, whatever the search shows. */
function ChosenSpells({ spells, onRemove }: { spells: Spell[]; onRemove: (spell: Spell) => void }) {
  if (spells.length === 0) return null;
  return (
    <div className="flex flex-wrap gap-2">
      {spells.map(s => (
        <span key={s.id} className="flex items-center gap-1 bg-page-alt text-ink px-2 py-1 font-body text-[13px] font-semibold border border-rule">
          {s.name}
          <IconButton label={`Remove ${s.name}`} onClick={() => onRemove(s)} className="text-muted hover:text-ink">
            <X className="w-3 h-3" />
          </IconButton>
        </span>
      ))}
    </div>
  );
}
