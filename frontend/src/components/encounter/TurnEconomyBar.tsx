import { Gauge } from 'lucide-react';
import type { EncounterParticipant, TurnEconomy } from '../../types/encounter';
import { speedText } from '../../utils/turnEconomy';

/** One pip of the bar: what it stands for, its short visible name, whether it is spent, and the economy after toggling it. */
interface Pip {
  key: string;
  name: string;
  short: string;
  used: boolean;
  colour: string;
  toggled: TurnEconomy;
}

function pips(economy: TurnEconomy): Pip[] {
  const actions: Pip[] = Array.from({ length: Math.max(economy.actions, 0) }, (_, i) => {
    const used = i < economy.actionsUsed;
    return {
      key: `action-${i}`,
      name: economy.actions > 1 ? `Action ${i + 1}` : 'Action',
      short: 'A',
      used,
      colour: 'bg-cls-fighter/20 border-cls-fighter',
      // Restoring a spent action gives one back; spending an available one takes one
      toggled: { ...economy, actionsUsed: Math.max(0, economy.actionsUsed + (used ? -1 : 1)) },
    };
  });
  return [
    ...actions,
    { key: 'bonus', name: 'Bonus action', short: 'BA', used: economy.bonusActionUsed, colour: 'bg-cls-monk/20 border-cls-monk',
      toggled: { ...economy, bonusActionUsed: !economy.bonusActionUsed } },
    { key: 'reaction', name: 'Reaction', short: 'R', used: economy.reactionUsed, colour: 'bg-cls-wizard/20 border-cls-wizard',
      toggled: { ...economy, reactionUsed: !economy.reactionUsed } },
    { key: 'object', name: 'Object interaction', short: 'Obj', used: economy.objectInteractionUsed, colour: 'bg-muted/20 border-muted',
      toggled: { ...economy, objectInteractionUsed: !economy.objectInteractionUsed } },
  ];
}

const BADGE = 'font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5 border';

/**
 * A participant's turn economy (D174): a pip each for its actions, bonus action, reaction and free object
 * interaction, labelled A, BA, R and Obj with a tooltip, dimmed and struck through once spent; the attacks left while an Attack action is open; its speeds, with the Dash
 * distance after the Dash action; and Dashed, Disengaged and Action Surge badges. `variant` is 'row' for a line in
 * an initiative row (the DM's) or 'card' for a card of its own (the player's character). With `onEdit`, the DM's,
 * each pip toggles and the whole economy is handed back, since the server's edit replaces it. A participant without
 * an economy, as a monster in the Player View (D157), shows nothing.
 */
export default function TurnEconomyBar({ participant, variant, onEdit }: {
  participant: Pick<EncounterParticipant, 'displayName' | 'turnEconomy' | 'attacksPerAction' | 'speed'>;
  variant: 'row' | 'card';
  onEdit?: (economy: TurnEconomy) => void;
}) {
  const economy = participant.turnEconomy;
  if (!economy) return null;

  const isCard = variant === 'card';
  const pipSize = isCard ? 'h-6 min-w-6 px-1.5 text-[12px]' : 'h-5 min-w-5 px-1 text-[11px]';
  const attacksPerAction = participant.attacksPerAction ?? 1;
  const attackOpen = economy.attackActionTaken && economy.attacksLeft > 0;
  const overspent = economy.actionsUsed - economy.actions;
  const { speeds, dash } = speedText(participant.speed, economy.dashed);

  const pipList = pips(economy).map(pip => {
    const status = pip.used ? 'used' : 'available';
    // The aria label stays "Action used"; the tooltip separates the name from its state
    const state = `${pip.name} ${status}`;
    const tooltip = `${pip.name} — ${status}`;
    const look = `${pipSize} inline-flex items-center justify-center rounded-full border font-heading font-semibold leading-none ${
      pip.used ? 'bg-transparent border-rule text-muted line-through opacity-50' : `${pip.colour} text-ink`}`;
    if (!onEdit) {
      return <span key={pip.key} role="img" aria-label={state} title={tooltip} className={look}>{pip.short}</span>;
    }
    // Not an IconButton: the pip shows its name, and its tooltip differs from its aria label
    return (
      <button key={pip.key} type="button" aria-label={state} title={`${tooltip} (click to ${pip.used ? 'restore' : 'mark used'})`}
        aria-pressed={pip.used} onClick={() => onEdit(pip.toggled)}
        className={`${look} transition-opacity hover:opacity-80 cursor-pointer`}>{pip.short}</button>
    );
  });

  const content = (
    <>
      <div className="flex items-center gap-1" role="group" aria-label={`${participant.displayName}'s turn economy`}>
        {pipList}
        {overspent > 0 && (
          <span className="font-body text-[11px] font-semibold text-debuff ml-1">+{overspent} over</span>
        )}
      </div>
      {attackOpen && (
        <span className={`${BADGE} border-cls-fighter/40 text-cls-fighter`}
          aria-label={`${economy.attacksLeft} of ${attacksPerAction} attacks left in the Attack action`}>
          Attack {economy.attacksLeft}/{attacksPerAction}
        </span>
      )}
      {speeds.length > 0 && (
        <span className="font-body text-[12.5px] font-semibold text-muted">
          Speed {speeds.join(', ')}{dash && <span className="text-ink">{` · Dash: ${dash}`}</span>}
        </span>
      )}
      {economy.dashed && <span className={`${BADGE} border-rule text-ink`}>Dashed</span>}
      {economy.disengaged && <span className={`${BADGE} border-rule text-ink`}>Disengaged</span>}
      {economy.actionSurgeUsed && <span className={`${BADGE} border-cls-fighter/40 text-cls-fighter`}>Action Surge</span>}
    </>
  );

  if (!isCard) {
    return <div className="flex items-center gap-2 mt-1 flex-wrap">{content}</div>;
  }
  return (
    <div className="bg-card border border-rule p-4 mb-4">
      <div className="flex items-center gap-2 mb-2">
        <Gauge className="w-4 h-4 text-ink" />
        <span className="font-heading text-[15px] font-semibold tracking-[0.01em] text-muted">This Turn</span>
      </div>
      <div className="flex items-center gap-3 flex-wrap">{content}</div>
    </div>
  );
}
