import type { Item } from '../../types/reference';

const RARITY_COLOURS: Record<string, { text: string; border: string }> = {
  'common':    { text: 'var(--color-faint)', border: 'var(--color-rule-light)' },
  'uncommon':  { text: 'var(--color-buff)', border: 'var(--color-buff-border)' },
  'rare':      { text: 'var(--color-abj)', border: 'var(--color-abj-border)' },
  'very rare': { text: 'var(--color-cls-sorcerer)', border: 'var(--color-illu-border)' },
  'legendary': { text: 'var(--color-trans)', border: 'var(--color-trans-border)' },
  'artifact':  { text: 'var(--color-debuff)', border: 'var(--color-debuff-border)' },
};

export default function ItemCard({ item }: { item: Item }) {
  const rarity = RARITY_COLOURS[item.rarity?.toLowerCase() || ''];
  const accent = rarity?.text || 'var(--color-ink)';

  return (
    <div className="max-w-2xl bg-card border border-rule overflow-hidden" style={{ borderLeftWidth: '3px', borderLeftColor: accent }}>
      <div className="px-5 py-3 border-b border-rule">
        <h2 className="font-heading text-[17px] font-bold" style={{ color: accent }}>{item.name}</h2>
        <p className="font-body text-[14px] font-semibold text-muted italic">
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
            <p className="font-body text-[14px]">
              <span className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint">Cost</span><br/>
              <span className="text-ink font-bold">{item.cost}</span>
            </p>
          )}
          {item.weight != null && (
            <p className="font-body text-[14px]">
              <span className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint">Weight</span><br/>
              <span className="text-ink font-bold">{item.weight} lb.</span>
            </p>
          )}
          {item.damageDice && (
            <p className="font-body text-[14px]">
              <span className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint">Damage</span><br/>
              <span className="text-ink font-bold">{item.damageDice} {item.damageType}</span>
            </p>
          )}
          {item.properties?.ac != null && (
            <p className="font-body text-[14px]">
              <span className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint">AC</span><br/>
              <span className="text-ink font-bold">{String(item.properties.ac)}</span>
            </p>
          )}
        </div>

        {item.description && (
          <div className="font-body text-[15px] font-semibold text-muted whitespace-pre-line leading-relaxed">
            {item.description}
          </div>
        )}
      </div>

      <div className="px-5 py-1.5 border-t border-rule-light text-right">
        <span className="font-body text-[13px] text-faint">Source: {item.source}</span>
      </div>
    </div>
  );
}
