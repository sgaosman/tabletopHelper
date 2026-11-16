import { useState } from 'react';
import { Backpack, FlaskConical, ScrollText, WandSparkles } from 'lucide-react';
import { apiErrorMessage } from '../../api/errors';
import { useEncounterAction, useUsableItems } from '../../queries/encounters';
import type { EncounterParticipant } from '../../types/encounter';
import type { SpellOption, UsableChargedItem, UsablePotion, UsableScroll } from '../../types/combat';
import { chargeBlock } from '../../utils/turnEconomy';
import Modal from '../ui/Modal';
import SpellCastModal from './SpellCastModal';
import { FIELD_INPUT, FIELD_LABEL, SUBMIT_BUTTON } from './formStyles';

/** The item spell or scroll chosen to cast, which opens the cast flow with it as the only spell. */
interface ItemCast {
  itemName: string;
  spells: SpellOption[];
  numbers?: string;
}

/**
 * Use an Item (D210): what a player character carries that the encounter can use, from GET /combat/usable-items. A
 * potion is drunk, or given to another creature (no map, so any is in reach), as an action: a potion of healing heals
 * by its dice, any other is logged for the DM with its description. A magic item's spells and spell scrolls open the
 * cast flow with the item as the only way to pay, showing the item's own save DC where it has one, or a scroll's from
 * DMG p.200. What the turn can't pay for is greyed for a player with the reason, as in StandardActionMenu; the DM is
 * only warned, and the server's refusal shows too.
 */
export default function UseItemModal({ participant, participants, asDm = false, allowManualModifiers = false, onClose }: {
  participant: EncounterParticipant;
  participants: EncounterParticipant[];
  asDm?: boolean;
  allowManualModifiers?: boolean;
  onClose: () => void;
}) {
  const usable = useUsableItems(participant.id);
  const [casting, setCasting] = useState<ItemCast | null>(null);
  // What the last potion used was, kept here because the last of a potion takes its row with it
  const [used, setUsed] = useState('');

  if (casting) {
    return (
      <SpellCastModal caster={participant} participants={participants} asDm={asDm}
        allowManualModifiers={allowManualModifiers} spells={casting.spells} itemName={casting.itemName}
        itemNumbers={casting.numbers} onClose={onClose} />
    );
  }

  const data = usable.data;
  const empty = data && data.potions.length === 0 && data.chargedItems.length === 0 && data.scrolls.length === 0;
  const actionBlock = chargeBlock(participant, { cost: 'ACTION' });

  return (
    <Modal title={<><Backpack className="w-5 h-5 text-ink" />Use an Item</>} subtitle={participant.displayName}
      size="lg" onClose={onClose}>
      <div className="space-y-4">
        {used && <p role="status" className="font-body text-[15px] font-semibold text-buff">{used}</p>}
        {usable.isPending && <p className="font-body text-[15px] font-semibold text-faint">Loading items...</p>}
        {usable.isError && <p role="alert" className="font-body text-[15px] font-semibold text-debuff">{apiErrorMessage(usable.error)}</p>}
        {empty && (
          <p className="font-body text-[15px] font-semibold text-faint">
            Nothing to use: carry potions, a magic item with charges linked to its item, or a spell scroll with its spell
          </p>
        )}

        {data && data.potions.length > 0 && (
          <section aria-label="Potions" className="space-y-2">
            <h3 className={FIELD_LABEL}>Potions</h3>
            {data.potions.map(potion => (
              <PotionRow key={potion.name} potion={potion} participant={participant} participants={participants}
                asDm={asDm} actionBlock={actionBlock} onUsing={() => setUsed('')}
                onUsed={() => { setUsed(`Used ${potion.name}`); void usable.refetch(); }} />
            ))}
          </section>
        )}

        {data && data.chargedItems.length > 0 && (
          <section aria-label="Magic items" className="space-y-2">
            <h3 className={FIELD_LABEL}>Magic items</h3>
            {data.chargedItems.map(item => (
              <ChargedItemRow key={item.name} item={item} onCast={spell => setCasting({
                itemName: item.name, spells: [spell], numbers: itemNumbers(item),
              })} />
            ))}
          </section>
        )}

        {data && data.scrolls.length > 0 && (
          <section aria-label="Spell scrolls" className="space-y-2">
            <h3 className={FIELD_LABEL}>Spell scrolls</h3>
            {data.scrolls.map(scroll => (
              <ScrollRow key={`${scroll.name}-${scroll.spell.name}`} scroll={scroll} onCast={() => setCasting({
                itemName: scroll.name, spells: [scroll.spell], numbers: scrollNumbers(scroll),
              })} />
            ))}
          </section>
        )}
      </div>
    </Modal>
  );
}

// The numbers a magic item's spells use: its own where its text gives them, the wielder's otherwise
function itemNumbers(item: UsableChargedItem): string {
  const parts = [
    item.saveDc != null ? `Save DC ${item.saveDc}` : null,
    item.attackBonus != null ? `spell attack +${item.attackBonus}` : null,
  ].filter(Boolean);
  return parts.length > 0 ? `${parts.join(', ')} (${item.name}'s own)` : `Your own spell save DC and attack bonus (${item.name})`;
}

// A scroll's numbers from DMG p.200, and the check a spell above the reader's level takes first
function scrollNumbers(scroll: UsableScroll): string {
  const numbers = `Save DC ${scroll.saveDc}, spell attack +${scroll.attackBonus} (spell scroll)`;
  return scroll.checkDc != null
    ? `${numbers}. First a DC ${scroll.checkDc} ${scroll.checkAbility ?? 'spellcasting'} check: on a failure the spell is lost with the scroll`
    : numbers;
}

function PotionRow({ potion, participant, participants, asDm, actionBlock, onUsing, onUsed }: {
  potion: UsablePotion;
  participant: EncounterParticipant;
  participants: EncounterParticipant[];
  asDm: boolean;
  actionBlock: string | null;
  onUsing: () => void;
  onUsed: () => void;
}) {
  const actions = useEncounterAction();
  const [targetId, setTargetId] = useState(participant.id);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState('');
  const fieldId = `potion-target-${potion.name.replace(/[^A-Za-z0-9]+/g, '-')}`;
  const drinkers = participants.filter(p => p.lifeState !== 'DEAD');
  const refused = actionBlock !== null && !asDm;

  async function use() {
    if (sending || refused) return;
    setSending(true);
    setError('');
    onUsing();
    try {
      await actions.useItem({
        participantId: participant.id,
        itemName: potion.name,
        targetId: targetId !== participant.id ? targetId : undefined,
      });
      onUsed();
    } catch (err: unknown) {
      setError(apiErrorMessage(err));
    } finally {
      setSending(false);
    }
  }

  return (
    <div className="border border-rule bg-page-alt p-3 space-y-2">
      <div className="flex items-center gap-2">
        <FlaskConical className="w-4 h-4 text-ink shrink-0" />
        <span className="font-body text-[15px] font-semibold text-ink">{potion.name}</span>
        <span className="font-body text-[13px] font-semibold text-faint">× {potion.left}</span>
        {potion.healing && <span className="font-body text-[13px] font-semibold text-buff ml-auto">heals {potion.healing}</span>}
      </div>
      {!potion.healing && potion.description && (
        <p className="font-body text-[13px] font-semibold text-muted line-clamp-3">{potion.description}</p>
      )}
      <div className="flex gap-2 items-end">
        <div className="flex-1">
          <label htmlFor={fieldId} className={FIELD_LABEL}>Who drinks it</label>
          <select id={fieldId} value={targetId} onChange={e => setTargetId(e.target.value)} className={FIELD_INPUT}>
            {drinkers.map(p => (
              <option key={p.id} value={p.id}>{p.id === participant.id ? `${p.displayName} (drink it)` : p.displayName}</option>
            ))}
          </select>
        </div>
        <button type="button" onClick={() => void use()} disabled={sending || refused} className={SUBMIT_BUTTON}>
          {sending ? '...' : targetId === participant.id ? 'Drink (action)' : 'Give (action)'}
        </button>
      </div>
      {actionBlock && (
        <p className={`font-body text-[13px] font-semibold ${asDm ? 'text-cls-fighter' : 'text-debuff'}`}>
          {asDm ? `Over the action economy: ${actionBlock}` : actionBlock}
        </p>
      )}
      {error && <p role="alert" className="font-body text-[13px] font-semibold text-debuff">{error}</p>}
    </div>
  );
}

function ChargedItemRow({ item, onCast }: { item: UsableChargedItem; onCast: (spell: SpellOption) => void }) {
  return (
    <div className="border border-rule bg-page-alt p-3 space-y-2">
      <div className="flex items-center gap-2">
        <WandSparkles className="w-4 h-4 text-ink shrink-0" />
        <span className="font-body text-[15px] font-semibold text-ink">{item.name}</span>
        <span className="font-body text-[13px] font-semibold text-faint ml-auto">
          {item.chargesLeft}/{item.chargesMax} charges{item.saveDc != null ? ` · save DC ${item.saveDc}` : ''}
        </span>
      </div>
      {item.unavailableReason && (
        <p className="font-body text-[13px] font-semibold text-debuff">{item.unavailableReason}</p>
      )}
      <div className="flex flex-wrap gap-2">
        {item.spells.map(spell => {
          const castable = !spell.unavailableReason && spell.sources.some(source => source.levels.length > 0);
          return (
            <button key={spell.name} type="button" onClick={() => onCast(spell)} disabled={!castable}
              title={spell.unavailableReason ?? undefined}
              className="px-3 py-1.5 border border-rule bg-card hover:bg-rule disabled:opacity-50 font-body text-[14px] font-semibold text-ink">
              Cast {spell.name}
            </button>
          );
        })}
      </div>
      {item.spells.filter(spell => spell.unavailableReason && !item.unavailableReason).map(spell => (
        <p key={spell.name} className="font-body text-[12.5px] font-semibold text-muted">{spell.unavailableReason}</p>
      ))}
    </div>
  );
}

function ScrollRow({ scroll, onCast }: { scroll: UsableScroll; onCast: () => void }) {
  const left = scroll.spell.sources[0]?.usesLeft ?? 1;
  const reason = scroll.spell.unavailableReason;
  return (
    <div className="border border-rule bg-page-alt p-3 space-y-2">
      <div className="flex items-center gap-2">
        <ScrollText className="w-4 h-4 text-ink shrink-0" />
        <span className="font-body text-[15px] font-semibold text-ink">{scroll.name}: {scroll.spell.name}</span>
        <span className="font-body text-[13px] font-semibold text-faint">× {left}</span>
        <span className="font-body text-[13px] font-semibold text-faint ml-auto">save DC {scroll.saveDc}</span>
      </div>
      {scroll.checkDc != null && !reason && (
        <p className="font-body text-[12.5px] font-semibold text-cls-fighter">
          Above your level: a DC {scroll.checkDc} {scroll.checkAbility} check first
        </p>
      )}
      {reason && <p className="font-body text-[13px] font-semibold text-debuff">{reason}</p>}
      <button type="button" onClick={onCast} disabled={!!reason}
        className="px-3 py-1.5 border border-rule bg-card hover:bg-rule disabled:opacity-50 font-body text-[14px] font-semibold text-ink">
        Read {scroll.spell.name}
      </button>
    </div>
  );
}
