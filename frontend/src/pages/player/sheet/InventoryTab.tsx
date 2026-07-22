import { useState, useEffect } from 'react';
import type { PlayerCharacter, CharacterUpdateRequest } from '../../../types/character';

export default function InventoryTab({ equipment, currency, saveField }: {
  equipment: Array<{ name: string; quantity?: number; description?: string }>;
  currency: { cp: number; sp: number; ep: number; gp: number; pp: number };
  char?: PlayerCharacter;
  saveField: (u: CharacterUpdateRequest) => Promise<void>;
}) {
  const [editCurrency, setEditCurrency] = useState(false);
  const [currencyForm, setCurrencyForm] = useState(currency);

  useEffect(() => { setCurrencyForm(currency); }, [currency]);

  async function saveCurrency() {
    await saveField({ currency: JSON.stringify(currencyForm) });
    setEditCurrency(false);
  }

  return (
    <div className="space-y-6">
      {/* Currency */}
      <div className="bg-card border border-rule p-4">
        <div className="flex items-center justify-between mb-3">
          <h3 className="font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-faint">Currency</h3>
          <button onClick={() => editCurrency ? saveCurrency() : setEditCurrency(true)} className="font-body text-[12px] font-medium text-muted hover:text-ink transition-colors">
            {editCurrency ? 'Save' : 'Edit'}
          </button>
        </div>
        <div className="grid grid-cols-5 gap-3">
          {(['cp', 'sp', 'ep', 'gp', 'pp'] as const).map(coin => (
            <div key={coin} className="text-center">
              <p className="font-heading text-[9px] font-semibold tracking-[0.06em] uppercase text-faint">{coin}</p>
              {editCurrency ? (
                <input
                  type="number"
                  min={0}
                  value={currencyForm[coin]}
                  onChange={e => setCurrencyForm({ ...currencyForm, [coin]: parseInt(e.target.value) || 0 })}
                  className="w-full text-center font-heading text-[15px] font-bold text-ink bg-page border border-rule py-1 focus:outline-none focus:border-muted"
                />
              ) : (
                <p className="font-heading text-[15px] font-bold text-ink">{currency[coin]}</p>
              )}
            </div>
          ))}
        </div>
      </div>

      {/* Equipment */}
      <div className="bg-card border border-rule p-4">
        <h3 className="font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-faint mb-3">Equipment</h3>
        {equipment.length > 0 ? (
          <div className="space-y-0">
            {equipment.map((item, i) => (
              <div key={i} className="flex items-center gap-2 py-2 border-b border-rule-light last:border-0">
                <span className="font-body text-[13px] font-medium text-ink flex-1">{item.name}</span>
                {item.quantity && item.quantity > 1 && <span className="font-heading text-[9px] font-medium text-faint">x{item.quantity}</span>}
              </div>
            ))}
          </div>
        ) : (
          <p className="font-body text-[13px] text-faint">No equipment recorded.</p>
        )}
      </div>
    </div>
  );
}
