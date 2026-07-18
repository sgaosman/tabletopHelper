import type { Item } from '../../types/reference';

const RARITY_COLORS: Record<string, string> = {
  'common': 'bg-gray-800 border-gray-600',
  'uncommon': 'bg-green-950 border-green-800',
  'rare': 'bg-blue-950 border-blue-800',
  'very rare': 'bg-purple-950 border-purple-800',
  'legendary': 'bg-orange-950 border-orange-800',
  'artifact': 'bg-red-950 border-red-800',
};

const RARITY_HEADER: Record<string, string> = {
  'common': 'bg-gray-700',
  'uncommon': 'bg-green-900',
  'rare': 'bg-blue-900',
  'very rare': 'bg-purple-900',
  'legendary': 'bg-orange-900',
  'artifact': 'bg-red-900',
};

export default function ItemCard({ item }: { item: Item }) {
  const rarityKey = item.rarity?.toLowerCase() || '';
  const cardClass = RARITY_COLORS[rarityKey] || 'bg-gray-800 border-gray-600';
  const headerClass = RARITY_HEADER[rarityKey] || 'bg-gray-700';

  return (
    <div className={`max-w-2xl rounded-lg overflow-hidden shadow-xl border ${cardClass}`}>
      <div className={`px-6 py-4 ${headerClass}`}>
        <h2 className="text-2xl font-bold text-white">{item.name}</h2>
        <p className="text-gray-300 italic text-sm">
          {[item.type, item.subtype].filter(Boolean).join(' - ')}
          {item.rarity && `, ${item.rarity}`}
          {item.requiresAttunement && (
            <span> (requires attunement{item.attunementCondition ? ` ${item.attunementCondition}` : ''})</span>
          )}
        </p>
      </div>

      <div className="px-6 py-4 space-y-3 text-gray-200">
        <div className="grid grid-cols-2 gap-2 text-sm border-b border-gray-700 pb-3">
          {item.cost && <p><span className="font-bold text-gray-400">Cost:</span> {item.cost}</p>}
          {item.weight != null && <p><span className="font-bold text-gray-400">Weight:</span> {item.weight} lb.</p>}
          {item.damageDice && (
            <p><span className="font-bold text-gray-400">Damage:</span> {item.damageDice} {item.damageType}</p>
          )}
          {item.properties?.ac != null && (
            <p><span className="font-bold text-gray-400">AC:</span> {String(item.properties.ac)}</p>
          )}
        </div>

        {item.description && (
          <div className="text-sm whitespace-pre-line leading-relaxed">
            {item.description}
          </div>
        )}
      </div>

      <div className="bg-black/20 px-6 py-2 text-xs text-gray-500 text-right">
        Source: {item.source}
      </div>
    </div>
  );
}
