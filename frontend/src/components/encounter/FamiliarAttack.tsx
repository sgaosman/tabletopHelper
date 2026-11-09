import { useState } from 'react';
import { Swords } from 'lucide-react';
import { useEncounterAction, useMonsterActionTemplates } from '../../queries/encounters';
import { usePending } from '../../hooks/usePending';
import { useToast } from '../../hooks/useToast';
import type { MonsterAction } from '../../types/combat';
import type { EncounterParticipant } from '../../types/encounter';
import { chargeBlock } from '../../utils/turnEconomy';
import { FIELD_LABEL } from './formStyles';

// 44px tall, as every combat control is at phone width
const BUTTON = 'flex items-center gap-1.5 min-h-11 px-3 font-heading text-[12px] font-semibold tracking-[0.02em] '
  + 'bg-cls-warlock/10 hover:bg-cls-warlock/20 text-cls-warlock border border-cls-warlock/30 disabled:opacity-50';
const FIELD = 'w-full min-h-11 px-2 bg-card border border-rule font-body text-[15px] font-semibold text-ink';

/**
 * Pact of the Chain's familiar attack (PHB p.107): on the warlock's turn, the warlock forgoes one of its attacks of the
 * Attack action so its familiar makes one attack of its own with its reaction. Only the familiar's single attacks are
 * offered, at one creature; the warlock's attacks left and the familiar's reaction are checked here and by the server,
 * whose refusal shows as a toast, and the result as one too.
 */
export default function FamiliarAttack({ warlock, familiar, participants }: {
  warlock: EncounterParticipant;
  familiar: EncounterParticipant;
  participants: EncounterParticipant[];
}) {
  const actions = useEncounterAction();
  const templates = useMonsterActionTemplates(familiar.id);
  const { pending, run } = usePending();
  const toast = useToast();
  const [attackName, setAttackName] = useState('');
  const [targetId, setTargetId] = useState('');

  const attacks = ((templates.data?.actions ?? []) as MonsterAction[])
    .filter(a => !a.isMultiattack && a.deliveryMethod?.toUpperCase() === 'ATTACK_ROLL');
  const targets = participants.filter(p => p.id !== familiar.id && p.id !== warlock.id && p.lifeState !== 'DEAD');
  const attack = attacks.find(a => a.name === attackName) ?? attacks[0];
  const target = targets.find(p => p.id === targetId) ?? null;
  // The warlock forgoes an attack of its Attack action, and the familiar spends its reaction
  const block = chargeBlock(warlock, { cost: 'ACTION', attack: true })
    ?? (familiar.turnEconomy?.reactionUsed ? `${familiar.displayName} has already used its reaction` : null);

  if (templates.isPending || attacks.length === 0) return null;

  function send() {
    if (!attack || !target) return;
    run(() => actions.monsterAction({
      monsterParticipantId: familiar.id, actionName: attack.name, actionSource: 'ACTION',
      targetParticipantIds: [target.id], pactOfTheChain: true,
    })).then(response => {
      if (response) toast.success(response.description);
    }).catch(toast.errorFrom);
  }

  const base = `familiar-attack-${familiar.id}`;
  return (
    <section aria-label={`${familiar.displayName}'s attack (Pact of the Chain)`}
      className="mb-4 p-3 border border-cls-warlock/30 bg-card space-y-2">
      <p className="font-heading text-[15px] font-semibold tracking-[0.01em] text-cls-warlock">
        Pact of the Chain: {familiar.displayName} attacks
      </p>
      <p className="font-body text-[13px] text-muted">
        Forgo one of your attacks so {familiar.displayName} makes one attack with its reaction.
      </p>
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-2 items-end">
        {attacks.length > 1 && (
          <div>
            <label htmlFor={`${base}-action`} className={FIELD_LABEL}>Attack</label>
            <select id={`${base}-action`} value={attack?.name ?? ''} onChange={e => setAttackName(e.target.value)}
              className={FIELD}>
              {attacks.map(a => <option key={a.name} value={a.name}>{a.name}</option>)}
            </select>
          </div>
        )}
        <div>
          <label htmlFor={`${base}-target`} className={FIELD_LABEL}>Target</label>
          <select id={`${base}-target`} value={target?.id ?? ''} onChange={e => setTargetId(e.target.value)}
            className={FIELD}>
            <option value="">Choose a target</option>
            {targets.map(p => <option key={p.id} value={p.id}>{p.displayName}</option>)}
          </select>
        </div>
        <button type="button" disabled={pending || !target || block !== null} title={block ?? undefined}
          onClick={send} className={BUTTON}>
          <Swords className="w-3.5 h-3.5" /> {attack?.name ?? 'Attack'}
        </button>
      </div>
      {block && <p className="font-body text-[13px] font-semibold text-faint">{block}</p>}
    </section>
  );
}
