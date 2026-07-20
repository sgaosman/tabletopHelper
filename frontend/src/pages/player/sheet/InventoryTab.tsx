import { useState, useEffect } from 'react';
import type { PlayerCharacter, CharacterUpdateRequest } from '../../../types/character';

export default function InventoryTab({ equipment, currency, char, saveField }: {
  equipment: Array<{ name: string; quantity?: number; description?: string }>;
  currency: { cp: number; sp: number; ep: number; gp: number; pp: number };
  char: PlayerCharacter;
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
      <div className="bg-gray-900 border border-gray-800 rounded-lg p-4">
        <div className="flex items-center justify-between mb-3">
          <h3 className="text-gray-400 text-xs font-medium uppercase tracking-wider">Currency</h3>
          <button onClick={() => editCurrency ? saveCurrency() : setEditCurrency(true)} className="text-indigo-400 text-xs hover:text-indigo-300">
            {editCurrency ? 'Save' : 'Edit'}
          </button>
        </div>
        <div className="grid grid-cols-5 gap-3">
          {(['cp', 'sp', 'ep', 'gp', 'pp'] as const).map(coin => (
            <div key={coin} className="text-center">
              <p className="text-gray-500 text-xs uppercase">{coin}</p>
              {editCurrency ? (
                <input
                  type="number"
                  min={0}
                  value={currencyForm[coin]}
                  onChange={e => setCurrencyForm({ ...currencyForm, [coin]: parseInt(e.target.value) || 0 })}
                  className="w-full text-center text-white font-bold bg-gray-800 border border-gray-700 rounded py-1 text-sm focus:outline-none focus:ring-1 focus:ring-indigo-500"
                />
              ) : (
                <p className="text-white font-bold">{currency[coin]}</p>
              )}
            </div>
          ))}
        </div>
      </div>

      {/* Equipment */}
      <div className="bg-gray-900 border border-gray-800 rounded-lg p-4">
        <h3 className="text-gray-400 text-xs font-medium uppercase tracking-wider mb-3">Equipment</h3>
        {equipment.length > 0 ? (
          <div className="space-y-2">
            {equipment.map((item, i) => (
              <div key={i} className="flex items-center gap-2 py-1 border-b border-gray-800 last:border-0">
                <span className="text-white text-sm flex-1">{item.name}</span>
                {item.quantity && item.quantity > 1 && <span className="text-gray-500 text-xs">x{item.quantity}</span>}
              </div>
            ))}
          </div>
        ) : (
          <p className="text-gray-500 text-sm">No equipment recorded.</p>
        )}
      </div>
    </div>
  );
}
