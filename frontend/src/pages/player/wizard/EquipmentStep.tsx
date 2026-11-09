import type { Dispatch, ReactNode } from 'react';
import { Coins, Dices, Shield } from 'lucide-react';
import { useArmourClassPreview } from '../../../queries/characters';
import type { StartingEquipment, StartingEquipmentEntry } from '../../../types/reference';
import { formatArmourClass } from '../../../utils/armourClass';
import Input, { Select } from '../../../components/ui/Input';
import type { WizardAction, WizardState } from './wizardReducer';
import { buildCreateRequest } from './selectors';
import {
  entryLabel, goldDice, inventoryLabel, goldRange, optionKey, optionLetter, pickKey, rollGold, startingInventory,
  type KitOwner, type WizardKits,
} from './startingEquipment';

export interface EquipmentStepProps {
  state: WizardState;
  dispatch: Dispatch<WizardAction>;
  kits: WizardKits;
  /** Whether a kit couldn't load, so the step goes on without it */
  failed: boolean;
}

/**
 * The starting equipment (PHB p.143): the class's lines and the background's, each a choice of (a), (b)... or given
 * whatever is chosen, with a pick for an entry such as "a martial weapon". The class's starting wealth may be taken
 * instead, in place of both. The armour class the choices give is the server's, worked out as creation works it out.
 */
export default function EquipmentStep({ state, dispatch, kits, failed }: EquipmentStepProps) {
  const gold = kits.cls?.goldAlternative ?? null;
  const takingGold = state.startingGold != null;
  const preview = useArmourClassPreview(buildCreateRequest(state, kits));
  const loading = (state.selectedClass && !kits.cls) || (state.selectedBackground && !kits.background);
  const inventory = startingInventory(state, kits);
  return (
    <div className="space-y-4">
      <h2 className="font-heading text-[17px] font-semibold text-ink">Starting Equipment</h2>
      <p className="font-body text-[14px] text-muted">
        Your class and background give your first equipment. Armour and a shield start worn, one of each.
      </p>
      {failed && (
        <p role="alert" className="font-body text-[14px] font-semibold text-debuff">
          Some starting equipment couldn&apos;t be loaded; add it on the sheet after creation.
        </p>
      )}
      {loading && <p className="font-body text-[14px] text-faint">Loading the equipment...</p>}

      {gold && (
        <div className="bg-card border border-rule p-4 space-y-3">
          <div className="flex flex-wrap gap-2" role="radiogroup" aria-label="Equipment or gold">
            <ChoiceButton selected={!takingGold} onClick={() => dispatch({ type: 'setStartingGold', gold: null })}>
              Take the equipment
            </ChoiceButton>
            <ChoiceButton selected={takingGold} onClick={() => dispatch({ type: 'setStartingGold', gold: goldRange(gold).min })}>
              <Coins className="w-4 h-4" /> Take {goldDice(gold)} instead
            </ChoiceButton>
          </div>
          {takingGold && (
            <div className="flex items-end gap-2 max-w-sm">
              <Input
                label={`Gold pieces (${goldRange(gold).min} to ${goldRange(gold).max})`}
                type="number" min={goldRange(gold).min} max={goldRange(gold).max}
                value={state.startingGold ?? ''}
                onChange={e => dispatch({ type: 'setStartingGold', gold: e.target.value === '' ? 0 : Number(e.target.value) })}
                className="flex-1"
              />
              <button
                type="button"
                onClick={() => dispatch({ type: 'setStartingGold', gold: rollGold(gold) })}
                className="flex items-center gap-1 px-3 py-2 border border-rule bg-page font-body text-[14px] font-semibold text-ink hover:border-muted"
              >
                <Dices className="w-4 h-4" /> Roll
              </button>
            </div>
          )}
          {takingGold && (
            <p className="font-body text-[13px] text-muted">The gold replaces the class&apos;s and background&apos;s equipment; buy gear on the sheet.</p>
          )}
        </div>
      )}

      {!takingGold && kits.cls && state.selectedClass && (
        <Kit title={state.selectedClass.name} owner="class" kit={kits.cls} state={state} dispatch={dispatch} />
      )}
      {!takingGold && kits.background && state.selectedBackground && (
        <Kit title={state.selectedBackground.name} owner="background" kit={kits.background} state={state} dispatch={dispatch} />
      )}

      <div className="bg-page-alt border border-rule p-4 space-y-1" aria-live="polite">
        <h3 className="flex items-center gap-2 font-heading text-[15px] font-semibold text-ink">
          <Shield className="w-4 h-4" /> Armour Class {preview.data ? preview.data.total : '…'}
        </h3>
        {preview.data && <p className="font-body text-[13px] text-muted">{formatArmourClass(preview.data)}</p>}
        {preview.data?.warnings.map(warning => (
          <p key={warning} className="font-body text-[13px] font-semibold text-debuff">{warning}</p>
        ))}
        {preview.isError && <p className="font-body text-[13px] text-debuff">The armour class couldn&apos;t be worked out yet.</p>}
        {!takingGold && inventory.equipment.length > 0 && (
          <p className="font-body text-[13px] text-faint">
            Carrying: {inventory.equipment.map(inventoryLabel).join(', ')}
          </p>
        )}
      </div>
    </div>
  );
}

function ChoiceButton({ selected, onClick, children }: { selected: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={selected}
      onClick={onClick}
      className={`flex items-center gap-1.5 px-3 py-2 border text-left font-body text-[14px] font-semibold transition-colors ${
        selected ? 'bg-page-alt border-ink text-ink' : 'bg-card border-rule text-muted hover:border-muted'
      }`}
    >
      {children}
    </button>
  );
}

interface KitProps {
  title: string;
  owner: KitOwner;
  kit: StartingEquipment;
  state: WizardState;
  dispatch: Dispatch<WizardAction>;
}

function Kit({ title, owner, kit, state, dispatch }: KitProps) {
  if (kit.groups.length === 0) {
    return (
      <div className="bg-card border border-rule p-4">
        <h3 className="font-heading text-[15px] font-semibold text-ink">{title}</h3>
        <p className="font-body text-[13px] text-faint">No starting equipment listed.</p>
      </div>
    );
  }
  return (
    <div className="bg-card border border-rule p-4 space-y-3">
      <h3 className="font-heading text-[15px] font-semibold text-ink">{title}</h3>
      {kit.groups.map((group, line) => {
        const chosen = group.choice ? state.equipmentOptions[optionKey(owner, line)] : group.options[0]?.key;
        return (
          <div key={line} className="space-y-2">
            {group.choice ? (
              <div className="flex flex-wrap gap-2" role="radiogroup" aria-label={`${title} equipment, line ${line + 1}`}>
                {group.options.map(option => (
                  <ChoiceButton
                    key={option.key}
                    selected={chosen === option.key}
                    onClick={() => dispatch({ type: 'chooseEquipmentOption', key: optionKey(owner, line), option: option.key })}
                  >
                    {optionLetter(option.key)} {option.entries.map(entryLabel).join(', ')}
                  </ChoiceButton>
                ))}
              </div>
            ) : (
              <p className="font-body text-[14px] font-semibold text-ink">{group.options[0]?.entries.map(entryLabel).join(', ')}</p>
            )}
            {group.options.filter(o => o.key === chosen).map(option => option.entries.map((entry, i) => entry.kind === 'PICK' && (
              <Picks
                key={`${option.key}-${i}`}
                entry={entry}
                picksKey={pickKey(owner, line, option.key, i)}
                state={state}
                dispatch={dispatch}
              />
            )))}
          </div>
        );
      })}
    </div>
  );
}

function Picks({ entry, picksKey, state, dispatch }: {
  entry: StartingEquipmentEntry;
  picksKey: string;
  state: WizardState;
  dispatch: Dispatch<WizardAction>;
}) {
  const picked = state.equipmentPicks[picksKey] ?? [];
  return (
    <div className="flex flex-wrap gap-2 pl-2">
      {Array.from({ length: entry.quantity }, (_, index) => (
        <Select
          key={index}
          label={entry.quantity > 1 ? `${entry.name} ${index + 1}` : entry.name}
          value={picked[index] ?? ''}
          onChange={e => dispatch({ type: 'pickEquipment', key: picksKey, index, count: entry.quantity, itemId: e.target.value })}
          className="min-w-[12rem]"
        >
          <option value="">Choose...</option>
          {entry.candidates.map(candidate => (
            <option key={candidate.itemId} value={candidate.itemId}>{candidate.name}</option>
          ))}
        </Select>
      ))}
    </div>
  );
}
