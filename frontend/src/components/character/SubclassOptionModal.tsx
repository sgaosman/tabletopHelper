import { useState } from 'react';
import { Check } from 'lucide-react';
import { apiErrorMessage } from '../../api/errors';
import { useApplyChoices } from '../../queries/characters';
import { useSubclasses } from '../../queries/reference';
import type { PendingChoice, PlayerCharacter } from '../../types/character';
import { safeJsonParse } from '../../utils/dndRules';
import { choiceTitle } from '../../utils/pendingChoices';
import Button from '../ui/Button';
import Modal from '../ui/Modal';

interface Props {
  character: PlayerCharacter;
  /** A SUBCLASS_OPTION Pending Choice, whose options are the named lists the subclass offers (D231) */
  choice: PendingChoice;
  onComplete: (updated: PlayerCharacter) => void;
  /** Leaves the choice pending for later */
  onSkip: () => void;
  onClose: () => void;
}

/**
 * One option of a subclass's granted spells, as the class loader stores it: its spells by class level, and the spells
 * it adds to the class's list.
 */
interface GrantedOption {
  name: string;
  prepared?: Record<string, string[]>;
  known?: Record<string, string[]>;
  expanded?: string[];
}

// The option's spells, such as "Druid 3: hold person, spike growth", by class level, then the spells it alone adds to
// the class's list, such as a dao's "Adds to your spell list: sanctuary, spike growth" (TCE p.73)
function spellLines(option: GrantedOption | undefined, className: string, shared: Set<string>): string[] {
  if (!option) return [];
  const byLevel = new Map<number, string[]>();
  for (const part of [option.prepared, option.known]) {
    for (const [level, names] of Object.entries(part ?? {})) {
      byLevel.set(Number(level), [...(byLevel.get(Number(level)) ?? []), ...names]);
    }
  }
  const lines = [...byLevel.entries()].sort(([a], [b]) => a - b)
    .map(([level, names]) => `${className} ${level}: ${names.join(', ')}`);
  const own = (option.expanded ?? []).filter(name => !shared.has(name));
  if (own.length > 0) lines.push(`Adds to your spell list: ${own.join(', ')}`);
  return lines;
}

// The expanded spells every option adds, such as the genie spells of each Genie kind (TCE p.73)
function sharedExpanded(options: GrantedOption[]): string[] {
  if (options.length < 2) return [];
  return (options[0].expanded ?? []).filter(name => options.every(option => option.expanded?.includes(name)));
}

/**
 * One of the named lists a subclass's player picks when it is taken (D231): a Circle of the Land druid's terrain, whose
 * circle spells it then has always prepared at Druid 3, 5, 7 and 9 (PHB p.68-69), a Divine Soul sorcerer's affinity,
 * whose spell it learns (XGE p.50), or a Genie warlock's kind, whose spells join its spell list (TCE p.73, D239). Each
 * option shows its spells, from the subclass's data, and the spells every option adds are shown once. The server
 * records the pick on the subclass's level, so levelling down past it takes it away.
 */
export default function SubclassOptionModal({ character, choice, onComplete, onSkip, onClose }: Props) {
  const applyChoices = useApplyChoices();
  const [selected, setSelected] = useState<string | null>(null);
  const classId = choice.classId ?? '';
  const className = choice.className ?? '';
  const subclassesQuery = useSubclasses(classId);
  // Stays disabled once the choice is applied, until the page closes the dialog
  const submitting = applyChoices.isPending || applyChoices.isSuccess;

  const subclassId = character.multiclassEntries?.find(e => e.classId === classId)?.subclassId ?? null;
  const subclass = (subclassesQuery.data ?? []).find(s => s.id === subclassId);
  const granted = safeJsonParse<{ options?: GrantedOption[] }>(subclass?.grantedSpells, {});
  const optionsData = new Map((granted.options ?? []).map(option => [option.name, option]));
  const shared = sharedExpanded(granted.options ?? []);
  const sharedSet = new Set(shared);
  const title = choiceTitle(choice);

  function handleConfirm() {
    if (!selected) return;
    applyChoices.mutate(
      { characterId: character.id, choices: { choiceId: choice.id, subclassOption: selected } },
      { onSuccess: updated => onComplete(updated) },
    );
  }

  return (
    <Modal
      title={`Choose ${/^[aeiou]/i.test(title) ? 'an' : 'a'} ${title}`}
      subtitle={`${subclass?.name ?? className} (${className} ${choice.classLevel})`}
      onClose={onClose}
      footer={(
        <>
          <Button variant="secondary" onClick={onSkip}>Skip</Button>
          <Button onClick={handleConfirm} loading={submitting} disabled={!selected}>
            {submitting ? 'Choosing...' : 'Confirm'}
          </Button>
        </>
      )}
    >
      <div className="space-y-2">
        <p className="font-body text-[13px] font-semibold text-muted">
          Your subclass's spells follow your pick. You can't change it later without levelling down past this level.
        </p>
        {shared.length > 0 && (
          <p className="font-body text-[13px] font-semibold text-muted">
            Every choice adds to your spell list: {shared.join(', ')}
          </p>
        )}
        {applyChoices.error && (
          <div role="alert" className="bg-debuff/10 border border-debuff text-debuff p-3 font-body text-[15px] font-semibold">{apiErrorMessage(applyChoices.error)}</div>
        )}
        {(choice.options ?? []).map(option => {
          const isSelected = selected === option;
          const lines = spellLines(optionsData.get(option), className, sharedSet);
          return (
            <button
              key={option}
              type="button"
              onClick={() => setSelected(option)}
              aria-pressed={isSelected}
              className={`w-full flex items-start gap-3 px-4 py-3 border text-left transition-colors ${
                isSelected ? 'border-2 border-ink bg-page-alt' : 'border-rule bg-card hover:border-rule-light'
              }`}
            >
              <div className={`w-6 h-6 rounded flex items-center justify-center shrink-0 ${isSelected ? 'bg-ink' : 'bg-page-alt'}`}>
                {isSelected && <Check className="w-4 h-4 text-card" />}
              </div>
              <div className="min-w-0">
                <span className="font-heading text-[16px] font-semibold text-ink">{option}</span>
                {lines.map(line => (
                  <p key={line} className="font-body text-[13px] font-semibold text-muted mt-0.5">{line}</p>
                ))}
              </div>
            </button>
          );
        })}
      </div>
    </Modal>
  );
}
