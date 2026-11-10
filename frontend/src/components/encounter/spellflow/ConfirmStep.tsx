import type { ReactNode } from 'react';
import { Shield, Sparkles, AlertTriangle, type LucideIcon } from 'lucide-react';
import type { EncounterParticipant } from '../../../types/encounter';
import Button from '../../ui/Button';
import Input, { Select } from '../../ui/Input';
import type { CastingTime } from '../../../utils/turnEconomy';
import CastingTimeBadge from './CastingTimeBadge';

interface Props {
  spellName: string;
  /** Shown beside the name, such as "Cantrip" or "Level 2". */
  levelLabel?: string;
  /** What the spell is paid with, as a line under the name. */
  cost?: ReactNode;
  /** The spell's casting time, as a badge beside the name (D174). */
  castingTime?: CastingTime;
  /** What a reaction spell is cast in response to, as its casting time says. */
  reactionTrigger?: string | null;
  /** Why the caster's turn can't pay for this, as far as the page can tell: shown before the server is asked (D174). */
  economyWarning?: string | null;
  caster: EncounterParticipant;
  targets: EncounterParticipant[];
  /** Warns that the cast ends the caster's current concentration. */
  warnConcentration?: boolean;
  advantage: boolean | null;
  onAdvantageChange: (value: boolean | null) => void;
  /** Offers a spell attack bonus and save DC typed by hand (D164). */
  allowManualModifiers: boolean;
  overrideAttackBonus: string;
  overrideSaveDC: string;
  onOverrideAttackBonus: (value: string) => void;
  onOverrideSaveDC: (value: string) => void;
  submitLabel: string;
  busyLabel: string;
  submitIcon: LucideIcon;
  submitting: boolean;
  onSubmit: () => void;
  /** Back to the step before; without it there is no Back button. */
  onBack?: () => void;
  /** The options a cast of the spell chooses between, such as Enlarge or Reduce (D184); none for most spells. */
  castOptions?: string[] | null;
  option?: string | null;
  onOptionChange?: (value: string) => void;
  /**
   * The highest level of spell a cast may duplicate, as Wish's 8th (D190): the option is then the spell's name, typed,
   * with its own option after a colon. Left empty, the wish is the DM's.
   */
  duplicatesUpTo?: number | null;
}

const ADVANTAGE_MODES = [
  { label: 'Normal', value: null },
  { label: 'Advantage', value: true },
  { label: 'Disadvantage', value: false },
] as const;

/** Sums up a spell before it is resolved, and takes advantage and any Manual Modifiers. */
export default function ConfirmStep({
  spellName, levelLabel, cost, castingTime, reactionTrigger, economyWarning, caster, targets, warnConcentration = false, advantage, onAdvantageChange,
  allowManualModifiers, overrideAttackBonus, overrideSaveDC, onOverrideAttackBonus, onOverrideSaveDC,
  submitLabel, busyLabel, submitIcon: SubmitIcon, submitting, onSubmit, onBack, castOptions, option, onOptionChange,
  duplicatesUpTo,
}: Props) {
  return (
    <>
      <div className="flex items-center gap-2 mb-3">
        {onBack && <Button variant="ghost" size="sm" onClick={onBack}>&larr; Back</Button>}
        <span className="font-heading text-[16px] font-semibold text-ink">Confirm</span>
      </div>

      <div className="bg-page-alt p-4 space-y-2">
        <div className="flex items-center gap-2">
          <Sparkles className="w-4 h-4 text-ink" />
          <span className="font-heading text-[16px] font-semibold text-ink">{spellName}</span>
          {levelLabel && <span className="font-body text-[13px] font-semibold text-faint">{levelLabel}</span>}
          {castingTime && <CastingTimeBadge castingTime={castingTime} />}
        </div>

        {castingTime === 'REACTION' && reactionTrigger && (
          <p className="font-body text-[13px] font-semibold text-faint">Cast as a reaction, {reactionTrigger}</p>
        )}

        {cost && <p className="font-body text-[15px] font-semibold text-muted">{cost}</p>}

        {economyWarning && (
          <div className="flex items-start gap-2 bg-debuff/10 border border-debuff/30 p-2">
            <AlertTriangle className="w-4 h-4 text-debuff shrink-0 mt-0.5" />
            <p className="font-body text-[13px] font-semibold text-debuff">{economyWarning}</p>
          </div>
        )}

        <p className="font-body text-[15px] font-semibold text-muted">
          <Shield className="w-3.5 h-3.5 inline mr-1" />
          Targeting: {targets.map(t => t.displayName).join(', ') || 'None'}
        </p>

        {warnConcentration && caster.concentrationSpell && (
          <div className="flex items-start gap-2 bg-cls-monk/10 border border-cls-monk/30 p-2">
            <AlertTriangle className="w-4 h-4 text-cls-monk shrink-0 mt-0.5" />
            <p className="font-body text-[13px] font-semibold text-cls-monk">
              Will drop concentration on <strong>{caster.concentrationSpell}</strong>
            </p>
          </div>
        )}
      </div>

      {castOptions && castOptions.length > 0 && onOptionChange && (
        <Select label="Option" value={option ?? ''} onChange={e => onOptionChange(e.target.value)}>
          {castOptions.map(choice => <option key={choice} value={choice}>{choice}</option>)}
        </Select>
      )}

      {duplicatesUpTo != null && onOptionChange && (
        <Input
          label={`Spell to duplicate (level ${duplicatesUpTo} or lower)`}
          placeholder="Fireball, or Protection from Energy: fire"
          value={option ?? ''}
          onChange={e => onOptionChange(e.target.value)}
        />
      )}

      <div className="flex gap-2">
        {ADVANTAGE_MODES.map(({ label, value }) => (
          <button
            key={label}
            type="button"
            onClick={() => onAdvantageChange(value)}
            aria-pressed={advantage === value}
            className={`flex-1 py-1.5 font-body text-[15px] font-semibold transition-colors ${
              advantage === value ? 'bg-ink text-card' : 'bg-page-alt text-muted hover:text-ink'
            }`}
          >
            {label}
          </button>
        ))}
      </div>

      {allowManualModifiers && (
        <div className="space-y-1">
          <div className="grid grid-cols-2 gap-2">
            <Input
              label="Spell Attack Bonus"
              type="number"
              value={overrideAttackBonus}
              onChange={e => onOverrideAttackBonus(e.target.value)}
              placeholder={String(caster.spellAttackBonus ?? 0)}
            />
            <Input
              label="Spell Save DC"
              type="number"
              value={overrideSaveDC}
              onChange={e => onOverrideSaveDC(e.target.value)}
              placeholder={String(caster.spellSaveDc ?? 10)}
            />
          </div>
          <p className="font-body text-[13px] font-semibold text-faint">
            Manual Modifiers: leave blank to use the caster&apos;s own. Any you enter is noted in the combat log.
          </p>
        </div>
      )}

      <Button size="lg" className="w-full" onClick={onSubmit} loading={submitting}>
        <SubmitIcon className="w-4 h-4" />
        {submitting ? busyLabel : submitLabel}
      </Button>
    </>
  );
}
