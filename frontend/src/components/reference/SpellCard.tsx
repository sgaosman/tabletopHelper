import type { Spell } from '../../types/reference';
import FormattedDescription from '../FormattedDescription';

const SCHOOL_COLOURS: Record<string, string> = {
  Evocation: 'var(--color-evo)', Abjuration: 'var(--color-abj)', Conjuration: 'var(--color-conj)', Enchantment: 'var(--color-ench)',
  Necromancy: 'var(--color-necro)', Divination: 'var(--color-div)', Transmutation: 'var(--color-trans)', Illusion: 'var(--color-illu)',
};

/**
 * A spell's card: its level and school, casting numbers, description and lists. Titled, it sits under a heading that
 * already names the spell, such as a dialog's, so it leaves out its own name and outer border.
 */
export default function SpellCard({ spell, titled = false }: { spell: Spell; titled?: boolean }) {
  const s = spell;
  const levelStr = s.level === 0 ? 'Cantrip' : `Level ${s.level}`;
  const schoolStr = s.school || '';
  const accent = SCHOOL_COLOURS[schoolStr] || 'var(--color-ink)';

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
    <div
      className={titled ? 'bg-card' : 'max-w-2xl bg-card border border-rule overflow-hidden'}
      style={titled ? undefined : { borderLeftWidth: '3px', borderLeftColor: accent }}
    >
      <div className="px-5 py-3 border-b border-rule">
        {!titled && <h2 className="font-heading text-[17px] font-bold" style={{ color: accent }}>{s.name}</h2>}
        <p className="font-body text-[14px] font-semibold text-muted italic">
          {s.level === 0 ? `${schoolStr} cantrip` : `${levelStr} ${schoolStr.toLowerCase()}`}
          {s.ritual && ' (ritual)'}
        </p>
      </div>

      <div className="px-5 py-4 space-y-3">
        <div className="grid grid-cols-2 gap-1.5 border-b border-rule-light pb-3">
          <p className="font-body text-[14px]"><span className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint">Casting Time</span><br/><span className="text-ink font-bold">{s.castingTime}</span></p>
          <p className="font-body text-[14px]"><span className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint">Range</span><br/><span className="text-ink font-bold">{s.rangeDistance}</span></p>
          <p className="font-body text-[14px]"><span className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint">Components</span><br/><span className="text-ink font-bold">{componentsStr}</span></p>
          <p className="font-body text-[14px]"><span className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint">Duration</span><br/><span className="text-ink font-bold">{s.duration}</span></p>
        </div>

        {s.concentration && (
          <span className="inline-block font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5 text-muted border border-rule bg-page-alt">
            Requires Concentration
          </span>
        )}

        {s.description && (
          <FormattedDescription text={s.description} className="font-body text-[15px] [&_p]:text-muted [&_strong]:text-ink" />
        )}

        {s.higherLevels && (
          <div className="border-t border-rule-light pt-3">
            <FormattedDescription text={`At Higher Levels. ${s.higherLevels}`} className="font-body text-[15px] [&_p]:text-muted [&_strong]:text-ink" />
          </div>
        )}

        {(s.damageType || s.saveAbility) && (
          <div className="flex gap-4 font-body text-[13px] text-faint border-t border-rule-light pt-3">
            {s.damageType && <span>Damage: <span className="text-muted">{s.damageType}</span></span>}
            {s.saveAbility && <span>Save: <span className="text-muted">{s.saveAbility}</span></span>}
          </div>
        )}
      </div>

      {s.classes && s.classes.length > 0 && (
        <div className="px-5 py-2 border-t border-rule">
          <span className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint">Spell Lists </span>
          <span className="font-body text-[13px] font-semibold text-muted">{s.classes.join(', ')}</span>
        </div>
      )}

      <div className="px-5 py-1.5 border-t border-rule-light text-right">
        <span className="font-body text-[13px] text-faint">Source: {s.source}</span>
      </div>
    </div>
  );
}
