import type { Spell } from '../../types/reference';

export default function SpellCard({ spell }: { spell: Spell }) {
  const s = spell;
  const levelStr = s.level === 0 ? 'Cantrip' : `Level ${s.level}`;
  const schoolStr = s.school || '';

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
    <div className="max-w-2xl bg-indigo-950 border border-indigo-800 rounded-lg overflow-hidden shadow-xl">
      <div className="bg-indigo-900 px-6 py-4">
        <h2 className="text-2xl font-bold text-indigo-100">{s.name}</h2>
        <p className="text-indigo-300 italic text-sm">
          {s.level === 0 ? `${schoolStr} cantrip` : `${levelStr} ${schoolStr.toLowerCase()}`}
          {s.ritual && ' (ritual)'}
        </p>
      </div>

      <div className="px-6 py-4 space-y-3 text-indigo-100">
        <div className="grid grid-cols-2 gap-2 text-sm border-b border-indigo-800 pb-3">
          <p><span className="font-bold text-indigo-300">Casting Time:</span> {s.castingTime}</p>
          <p><span className="font-bold text-indigo-300">Range:</span> {s.rangeDistance}</p>
          <p><span className="font-bold text-indigo-300">Components:</span> {componentsStr}</p>
          <p><span className="font-bold text-indigo-300">Duration:</span> {s.duration}</p>
        </div>

        {s.concentration && (
          <div className="text-xs bg-yellow-900/30 text-yellow-300 px-3 py-1 rounded-md inline-block">
            Requires Concentration
          </div>
        )}

        {s.description && (
          <div className="text-sm whitespace-pre-line leading-relaxed">
            {s.description}
          </div>
        )}

        {s.higherLevels && (
          <div className="text-sm border-t border-indigo-800 pt-3">
            <span className="font-bold text-indigo-300">At Higher Levels.</span>{' '}
            <span className="whitespace-pre-line">{s.higherLevels}</span>
          </div>
        )}

        {(s.damageType || s.saveAbility) && (
          <div className="flex gap-4 text-xs text-indigo-400 border-t border-indigo-800 pt-3">
            {s.damageType && <span>Damage: {s.damageType}</span>}
            {s.saveAbility && <span>Save: {s.saveAbility}</span>}
          </div>
        )}
      </div>

      {s.classes && s.classes.length > 0 && (
        <div className="px-6 py-3 border-t border-indigo-800">
          <span className="text-xs font-bold text-indigo-300">Spell Lists: </span>
          <span className="text-xs text-indigo-200">
            {s.classes.join(', ')}
          </span>
        </div>
      )}

      <div className="bg-indigo-900/30 px-6 py-2 text-xs text-indigo-500 text-right">
        Source: {s.source}
      </div>
    </div>
  );
}
