import { useState } from 'react';
import { Shield, ShieldAlert, Sparkles, Swords } from 'lucide-react';
import { apiErrorMessage } from '../../api/errors';
import { useEncounterAction } from '../../queries/encounters';
import type { EncounterParticipant } from '../../types/encounter';
import { chargeBlock, poolCost, type CastingTime } from '../../utils/turnEconomy';
import ResourcePoolDisplay from '../common/ResourcePoolDisplay';
import Modal from '../ui/Modal';
import SpellCastModal from './SpellCastModal';
import WeaponAttackForm from './WeaponAttackForm';
import { FIELD_INPUT, FIELD_LABEL } from './formStyles';

const REACTION_SPELLS: CastingTime[] = ['REACTION'];

/**
 * The participant's reactions (PHB p.190, D174), on any creature's turn, its own included: an opportunity attack,
 * which opens the weapon attack with the reaction's cost; a spell whose casting time is a reaction, which opens the
 * cast flow showing only those; and pools that cost a reaction. A monster's stat-block reactions stay in the DM's
 * monster panel, which `onOpenMonsterReactions` opens. Once the reaction is used, a player's choices are greyed with
 * the reason, while the DM's are only warned about; the server's refusal shows too.
 */
export default function ReactionMenu({
  participant, participants, asDm = false, allowManualModifiers, onClose, onOpenMonsterReactions,
}: {
  participant: EncounterParticipant;
  participants: EncounterParticipant[];
  asDm?: boolean;
  allowManualModifiers: boolean;
  onClose: () => void;
  onOpenMonsterReactions?: () => void;
}) {
  const actions = useEncounterAction();
  const [targetId, setTargetId] = useState('');
  const [castingSpell, setCastingSpell] = useState(false);

  const blocked = chargeBlock(participant, { cost: 'REACTION' });
  const refused = blocked !== null && !asDm;
  const target = participants.find(p => p.id === targetId) ?? null;
  const targets = participants.filter(p => p.id !== participant.id && p.lifeState !== 'DEAD');
  const reactionPools = (participant.resourcePoolsCurrent ?? []).filter(pool => poolCost(pool) === 'REACTION');
  const isMonster = participant.participantType === 'MONSTER';
  const casts = (participant.spellsKnown?.length ?? 0) > 0;
  // The Protection fighting style's reaction, for a creature within 5 feet of the defender (PHB p.72, M17)
  const protects = (participant.fightingStyles ?? []).includes('Protection');
  const [protectedId, setProtectedId] = useState('');
  const [protectionError, setProtectionError] = useState('');
  const [protecting, setProtecting] = useState(false);

  async function protect() {
    if (!protectedId || protecting) return;
    setProtectionError('');
    setProtecting(true);
    try {
      await actions.protection({ participantId: participant.id, protectedId });
      onClose();
    } catch (err: unknown) {
      setProtectionError(apiErrorMessage(err));
    } finally {
      setProtecting(false);
    }
  }

  if (castingSpell) {
    return (
      <SpellCastModal caster={participant} participants={participants} onClose={onClose}
        allowManualModifiers={allowManualModifiers} asDm={asDm} castingTimes={REACTION_SPELLS} />
    );
  }

  return (
    <Modal title={<><ShieldAlert className="w-5 h-5 text-ink" />Reactions</>} subtitle={participant.displayName}
      size="lg" onClose={onClose}>
      <div className="space-y-4">
        <p className="font-body text-[13px] font-semibold text-muted">
          One reaction a round, on any creature&apos;s turn; it comes back at the start of {participant.displayName}&apos;s turn.
        </p>
        {blocked && (
          <p role="status" className={`font-body text-[13px] font-semibold ${asDm ? 'text-cls-fighter' : 'text-debuff'}`}>
            {asDm ? `Over the action economy: ${blocked}` : blocked}
          </p>
        )}

        <section aria-label="Opportunity attack" className="space-y-2">
          <h3 className="font-heading text-[15px] font-semibold text-ink flex items-center gap-1.5">
            <Swords className="w-4 h-4 text-cls-fighter" /> Opportunity attack
          </h3>
          <div>
            <label htmlFor="reaction-target" className={FIELD_LABEL}>Against</label>
            <select id="reaction-target" value={targetId} disabled={refused}
              onChange={e => setTargetId(e.target.value)} className={FIELD_INPUT}>
              <option value="">Choose the creature leaving your reach...</option>
              {targets.map(p => <option key={p.id} value={p.id}>{p.displayName}</option>)}
            </select>
          </div>
          {/* The weapon form charges the reaction; the server works out the numbers (D164) */}
          {target && !refused && (
            <WeaponAttackForm key={target.id} attacker={participant} target={target}
              allowManualModifiers={allowManualModifiers} onDone={onClose} defaultCost="REACTION" />
          )}
        </section>

        {protects && (
          <section aria-label="Protection" className="space-y-2">
            <h3 className="font-heading text-[15px] font-semibold text-ink flex items-center gap-1.5">
              <Shield className="w-4 h-4 text-cls-fighter" /> Protection
            </h3>
            <p className="font-body text-[13px] font-semibold text-muted">
              With a shield, give disadvantage to an attack against a creature within 5 ft of you; the DM rolls it so.
            </p>
            <div className="flex flex-wrap items-end gap-2">
              <div className="flex-1 min-w-40">
                <label htmlFor="protection-target" className={FIELD_LABEL}>Protect</label>
                <select id="protection-target" value={protectedId} disabled={refused}
                  onChange={e => setProtectedId(e.target.value)} className={FIELD_INPUT}>
                  <option value="">Choose the creature attacked...</option>
                  {targets.map(p => <option key={p.id} value={p.id}>{p.displayName}</option>)}
                </select>
              </div>
              <button type="button" onClick={() => { void protect(); }} disabled={refused || !protectedId || protecting}
                aria-busy={protecting || undefined}
                className="min-h-11 px-3 py-2 bg-cls-fighter/10 hover:bg-cls-fighter/20 text-ink font-heading text-[12px] font-semibold tracking-[0.02em] border border-cls-fighter/30 disabled:opacity-50">
                Use Protection
              </button>
            </div>
            {protectionError && <p role="alert" className="font-body text-[13px] font-semibold text-debuff">{protectionError}</p>}
          </section>
        )}

        {casts && (
          <section aria-label="Reaction spells">
            <button type="button" onClick={() => setCastingSpell(true)} disabled={refused}
              className="flex items-center gap-1.5 px-3 py-1.5 bg-ink/10 hover:bg-ink/20 text-ink font-heading text-[12px] font-semibold tracking-[0.02em] border border-ink/30 disabled:opacity-50">
              <Sparkles className="w-3.5 h-3.5" /> Cast a reaction spell
            </button>
          </section>
        )}

        {reactionPools.length > 0 && (
          <section aria-label="Reaction pools" className="space-y-1">
            <h3 className="font-heading text-[15px] font-semibold text-ink">Pools used as a reaction</h3>
            <ResourcePoolDisplay pools={reactionPools} participant={participant} asDm={asDm}
              onUse={poolId => actions.usePool({ participantId: participant.id, poolId })} />
          </section>
        )}

        {isMonster && onOpenMonsterReactions && (
          <section aria-label="Stat block reactions">
            <button type="button" onClick={() => { onOpenMonsterReactions(); onClose(); }}
              className="px-3 py-1.5 bg-monster/10 hover:bg-monster/20 text-monster font-heading text-[12px] font-semibold tracking-[0.02em] border border-monster/30">
              Stat block reactions (monster panel)
            </button>
          </section>
        )}
      </div>
    </Modal>
  );
}
