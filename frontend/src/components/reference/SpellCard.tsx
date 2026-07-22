import type { Spell } from '../../types/reference';
import FormattedDescription from '../FormattedDescription';

const SCHOOL_COLOURS: Record<string, string> = {
  Evocation: '#DC2626', Abjuration: '#4F46E5', Conjuration: '#059669', Enchantment: '#DB2777',
  Necromancy: '#374151', Divination: '#CA8A04', Transmutation: '#B45309', Illusion: '#7C3AED',
};

export default function SpellCard({ spell }: { spell: Spell }) {
  const s = spell;
  const levelStr = s.level === 0 ? 'Cantrip' : `Level ${s.level}`;
  const schoolStr = s.school || '';
  const accent = SCHOOL_COLOURS[schoolStr] || '#292524';

  const componentsStr = (() => {
    if (!s.components) return '-';
    const parts: string[] = [];
    if (s.components.verbal) parts.push('V');
    if (s.components.somatic) parts.push('S');
    if (s.components.material) {
      const mat = typeof s.components.material === 'string' ? s.components.material : '';
      parts.push(mat ? `M (${mat})` : 'M');
    }
    return parts.join(', ');
  })();

  return (
    <div className="max-w-2xl bg-card border border-rule overflow-hidden" style={{ borderLeftWidth: '3px', borderLeftColor: accent }}>
      <div className="px-5 py-3 border-b border-rule">
        <h2 className="font-heading text-[17px] font-bold" style={{ color: accent }}>{s.name}</h2>
        <p className="font-body text-[12px] font-medium text-muted italic">
          {s.level === 0 ? `${schoolStr} cantrip` : `${levelStr} ${schoolStr.toLowerCase()}`}
          {s.ritual && ' (ritual)'}
        </p>
      </div>

      <div className="px-5 py-4 space-y-3">
        <div className="grid grid-cols-2 gap-1.5 border-b border-rule-light pb-3">
          <p className="font-body text-[12px]"><span className="font-heading text-[9px] font-semibold tracking-[0.06em] uppercase text-faint">Casting Time</span><br/><span className="text-ink font-medium">{s.castingTime}</span></p>
          <p className="font-body text-[12px]"><span className="font-heading text-[9px] font-semibold tracking-[0.06em] uppercase text-faint">Range</span><br/><span className="text-ink font-medium">{s.rangeDistance}</span></p>
          <p className="font-body text-[12px]"><span className="font-heading text-[9px] font-semibold tracking-[0.06em] uppercase text-faint">Components</span><br/><span className="text-ink font-medium">{componentsStr}</span></p>
          <p className="font-body text-[12px]"><span className="font-heading text-[9px] font-semibold tracking-[0.06em] uppercase text-faint">Duration</span><br/><span className="text-ink font-medium">{s.duration}</span></p>
        </div>

        {s.concentration && (
          <span className="inline-block font-heading text-[9px] font-medium tracking-[0.02em] px-1.5 py-0.5 text-muted border border-rule bg-page-alt">
            Requires Concentration
          </span>
        )}

        {s.description && (
          <FormattedDescription text={s.description} className="font-body text-[13px] [&_p]:text-muted [&_strong]:text-ink" />
        )}

        {s.higherLevels && (
          <div className="border-t border-rule-light pt-3">
            <FormattedDescription text={`At Higher Levels. ${s.higherLevels}`} className="font-body text-[13px] [&_p]:text-muted [&_strong]:text-ink" />
          </div>
        )}

        {(s.damageType || s.saveAbility) && (
          <div className="flex gap-4 font-body text-[11px] text-faint border-t border-rule-light pt-3">
            {s.damageType && <span>Damage: <span className="text-muted">{s.damageType}</span></span>}
            {s.saveAbility && <span>Save: <span className="text-muted">{s.saveAbility}</span></span>}
          </div>
        )}
      </div>

      {s.classes && s.classes.length > 0 && (
        <div className="px-5 py-2 border-t border-rule">
          <span className="font-heading text-[9px] font-semibold tracking-[0.06em] uppercase text-faint">Spell Lists </span>
          <span className="font-body text-[11px] font-medium text-muted">{s.classes.join(', ')}</span>
        </div>
      )}

      <div className="px-5 py-1.5 border-t border-rule-light text-right">
        <span className="font-body text-[11px] text-faint">Source: {s.source}</span>
      </div>
    </div>
  );
}
