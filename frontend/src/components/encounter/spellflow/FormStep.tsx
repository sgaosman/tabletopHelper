import { useFormOptions } from '../../../queries/encounters';
import type { EncounterParticipant } from '../../../types/encounter';
import type { WildShapeBeast } from '../../../types/combat';
import { SHAPECHANGE } from '../../../utils/formSpells';
import Button from '../../ui/Button';
import FormPicker from '../FormPicker';

/** A creature's chosen form: its monster ID, which the cast request carries, and its name to show. */
export interface FormChoice {
  monsterId: string;
  name: string;
}

interface Props {
  spellName: string;
  caster: EncounterParticipant;
  /** The creatures the cast transforms: each target, or the caster for Shapechange. */
  subjects: EncounterParticipant[];
  /** The forms chosen so far, by the creature's participant ID. */
  forms: Record<string, FormChoice>;
  onChoose: (subjectId: string, choice: FormChoice | null) => void;
  onContinue: () => void;
  onBack?: () => void;
}

/**
 * Chooses the form each creature takes with a form spell (D192): Polymorph, Mass Polymorph, True Polymorph,
 * Shapechange or Animal Shapes. Each creature picks from the options the server lists for it within the spell's limits;
 * a creature whose form is chosen folds to a line with Change. Continue waits until every creature has one.
 */
export default function FormStep({ spellName, caster, subjects, forms, onChoose, onContinue, onBack }: Props) {
  const ready = subjects.length > 0 && subjects.every(s => forms[s.id]);
  return (
    <>
      <div className="flex flex-wrap items-center gap-2 mb-2">
        {onBack && <Button variant="ghost" size="sm" onClick={onBack}>&larr; Back</Button>}
        <span className="font-heading text-[16px] font-semibold text-ink">{spellName}</span>
        <span className="font-body text-[13px] font-semibold text-faint">— Choose each new form</span>
      </div>

      <div className="space-y-3">
        {subjects.map(subject => (
          <SubjectForm key={subject.id} spellName={spellName} caster={caster} subject={subject}
            choice={forms[subject.id] ?? null} onChoose={choice => onChoose(subject.id, choice)} />
        ))}
      </div>

      <Button size="lg" className="w-full mt-2" onClick={onContinue} disabled={!ready}>
        Continue
      </Button>
    </>
  );
}

function SubjectForm({ spellName, caster, subject, choice, onChoose }: {
  spellName: string;
  caster: EncounterParticipant;
  subject: EncounterParticipant;
  choice: FormChoice | null;
  onChoose: (choice: FormChoice | null) => void;
}) {
  // Shapechange's caster is its own target, so the server needs no target for it
  const options = useFormOptions(caster.id, spellName, spellName === SHAPECHANGE ? undefined : subject.id);
  const label = `Form for ${subject.displayName}`;
  return (
    <section aria-label={label} className="border border-rule p-2 sm:p-3 space-y-1.5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="font-heading text-[15px] font-semibold text-ink">
          {subject.displayName}
          {subject.id === caster.id && <span className="ml-1 font-body text-[13px] text-faint">(self)</span>}
        </span>
        {choice && (
          <span className="flex flex-wrap items-center gap-2">
            <span className="font-body text-[14px] font-semibold text-ink">becomes {choice.name}</span>
            <Button variant="ghost" size="sm" onClick={() => onChoose(null)} aria-label={`Change ${subject.displayName}'s form`}>
              Change
            </Button>
          </span>
        )}
      </div>
      {!choice && (
        <FormPicker label={label} options={options.data} loading={options.isPending} error={options.error}
          selected={null}
          onSelect={(creature: WildShapeBeast) => onChoose({ monsterId: creature.monsterId, name: creature.name })} />
      )}
    </section>
  );
}
