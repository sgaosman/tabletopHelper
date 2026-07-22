import type { Item } from '../../types/reference';

const RARITY_COLOURS: Record<string, { text: string; border: string }> = {
  'common':    { text: '#78716C', border: '#E7E5E4' },
  'uncommon':  { text: '#166534', border: '#BBF7D0' },
  'rare':      { text: '#4F46E5', border: '#C7D2FE' },
  'very rare': { text: '#9333EA', border: '#D8B4FE' },
  'legendary': { text: '#B45309', border: '#FDE68A' },
  'artifact':  { text: '#991B1B', border: '#FECACA' },
};

export default function ItemCard({ item }: { item: Item }) {
  const rarity = RARITY_COLOURS[item.rarity?.toLowerCase() || ''];
  const accent = rarity?.text || '#292524';

  return (
    <div className="max-w-2xl bg-card border border-rule overflow-hidden" style={{ borderLeftWidth: '3px', borderLeftColor: accent }}>
      <div className="px-5 py-3 border-b border-rule">
        <h2 className="font-heading text-[17px] font-bold" style={{ color: accent }}>{item.name}</h2>
        <p className="font-body text-[12px] font-medium text-muted italic">
          {[item.type, item.subtype].filter(Boolean).join(' — ')}
          {item.rarity && `, ${item.rarity}`}
          {item.requiresAttunement && (
            <span> (requires attunement{item.attunementCondition ? ` ${item.attunementCondition}` : ''})</span>
          )}
        </p>
      </div>

      <div className="px-5 py-4 space-y-3">
        <div className="grid grid-cols-2 gap-1.5 border-b border-rule-light pb-3">
          {item.cost && (
            <p className="font-body text-[12px]">
              <span className="font-heading text-[9px] font-semibold tracking-[0.06em] uppercase text-faint">Cost</span><br/>
              <span className="text-ink font-medium">{item.cost}</span>
            </p>
          )}
          {item.weight != null && (
            <p className="font-body text-[12px]">
              <span className="font-heading text-[9px] font-semibold tracking-[0.06em] uppercase text-faint">Weight</span><br/>
              <span className="text-ink font-medium">{item.weight} lb.</span>
            </p>
          )}
          {item.damageDice && (
            <p className="font-body text-[12px]">
              <span className="font-heading text-[9px] font-semibold tracking-[0.06em] uppercase text-faint">Damage</span><br/>
              <span className="text-ink font-medium">{item.damageDice} {item.damageType}</span>
            </p>
          )}
          {item.properties?.ac != null && (
            <p className="font-body text-[12px]">
              <span className="font-heading text-[9px] font-semibold tracking-[0.06em] uppercase text-faint">AC</span><br/>
              <span className="text-ink font-medium">{String(item.properties.ac)}</span>
            </p>
          )}
        </div>

        {item.description && (
          <div className="font-body text-[13px] font-medium text-muted whitespace-pre-line leading-relaxed">
            {item.description}
          </div>
        )}
      </div>

      <div className="px-5 py-1.5 border-t border-rule-light text-right">
        <span className="font-body text-[11px] text-faint">Source: {item.source}</span>
      </div>
    </div>
  );
}
