import type { Monster } from '../../types/monster';
import { parseMarkup } from '../../utils/parseMarkup';

function abilityMod(score: number): string {
  const mod = Math.floor((score - 10) / 2);
  return mod >= 0 ? `+${mod}` : `${mod}`;
}

export default function MonsterStatBlock({ monster }: { monster: Monster }) {
  const m = monster;

  const speedStr = m.speed
    ? Object.entries(m.speed).map(([k, v]) => k === 'walk' ? `${v} ft.` : `${k} ${v} ft.`).join(', ')
    : '30 ft.';

  const sensesStr = (() => {
    const parts: string[] = [];
    if (m.senses?.special) parts.push(...m.senses.special);
    if (m.senses?.passive_perception) parts.push(`passive Perception ${m.senses.passive_perception}`);
    return parts.join(', ') || '-';
  })();

  return (
    <div className="max-w-2xl bg-amber-50 text-stone-900 rounded-lg overflow-hidden shadow-xl">
      <div className="bg-red-900 px-6 py-4">
        <h2 className="text-2xl font-bold text-amber-50">{m.name}</h2>
        <p className="text-amber-200 italic text-sm">
          {m.size} {m.type}{m.subtype ? ` (${m.subtype})` : ''}, {m.alignment || 'unaligned'}
        </p>
      </div>

      <div className="px-6 py-4 space-y-3">
        <div className="border-b-2 border-red-900/20 pb-3 space-y-1 text-sm">
          <p><span className="font-bold">Armour Class</span> {m.armourClass}{m.acType ? ` (${m.acType})` : ''}</p>
          <p><span className="font-bold">Hit Points</span> {m.hitPoints}{m.hitDice ? ` (${m.hitDice})` : ''}</p>
          <p><span className="font-bold">Speed</span> {speedStr}</p>
        </div>

        <div className="border-b-2 border-red-900/20 pb-3">
          <div className="grid grid-cols-6 text-center text-sm">
            {(['strength', 'dexterity', 'constitution', 'intelligence', 'wisdom', 'charisma'] as const).map(ab => (
              <div key={ab}>
                <div className="font-bold text-xs uppercase">{ab.slice(0, 3)}</div>
                <div>{m[ab]} ({abilityMod(m[ab])})</div>
              </div>
            ))}
          </div>
        </div>

        <div className="border-b-2 border-red-900/20 pb-3 space-y-1 text-sm">
          {m.savingThrows && (
            <p><span className="font-bold">Saving Throws</span> {
              Object.entries(m.savingThrows as Record<string, string>).map(([k, v]) =>
                `${k.charAt(0).toUpperCase() + k.slice(1)} ${v}`
              ).join(', ')
            }</p>
          )}
          {m.skills && (
            <p><span className="font-bold">Skills</span> {
              Object.entries(m.skills as Record<string, string>).map(([k, v]) =>
                `${k.charAt(0).toUpperCase() + k.slice(1)} ${v}`
              ).join(', ')
            }</p>
          )}
          {m.damageResistances && m.damageResistances.length > 0 && (
            <p><span className="font-bold">Damage Resistances</span> {m.damageResistances.join(', ')}</p>
          )}
          {m.damageImmunities && m.damageImmunities.length > 0 && (
            <p><span className="font-bold">Damage Immunities</span> {m.damageImmunities.join(', ')}</p>
          )}
          {m.damageVulnerabilities && m.damageVulnerabilities.length > 0 && (
            <p><span className="font-bold">Damage Vulnerabilities</span> {m.damageVulnerabilities.join(', ')}</p>
          )}
          {m.conditionImmunities && m.conditionImmunities.length > 0 && (
            <p><span className="font-bold">Condition Immunities</span> {m.conditionImmunities.join(', ')}</p>
          )}
          <p><span className="font-bold">Senses</span> {sensesStr}</p>
          <p><span className="font-bold">Languages</span> {m.languages || '-'}</p>
          <p><span className="font-bold">Challenge</span> {m.challengeRating} ({m.experiencePoints.toLocaleString()} XP)</p>
        </div>

        {m.traits && m.traits.length > 0 && (
          <div className="space-y-2">
            {m.traits.map((t, i) => (
              <div key={i} className="text-sm">
                <span className="font-bold italic">{t.name}.</span>{' '}
                <span className="whitespace-pre-line" dangerouslySetInnerHTML={{ __html: parseMarkup(t.description) }} />
              </div>
            ))}
          </div>
        )}

        <EntrySection title="Actions" entries={m.actions} />
        <EntrySection title="Reactions" entries={m.reactions} />
        <EntrySection title="Legendary Actions" entries={m.legendaryActions} />
      </div>

      <div className="bg-red-900/10 px-6 py-2 text-xs text-stone-500 text-right">
        Source: {m.source}
      </div>
    </div>
  );
}

function EntrySection({ title, entries }: { title: string; entries: { name: string; description: string }[] | null }) {
  if (!entries || entries.length === 0) return null;
  return (
    <div className="border-t-2 border-red-900/20 pt-3 space-y-2">
      <h3 className="text-lg font-bold text-red-900">{title}</h3>
      {entries.map((e, i) => (
        <div key={i} className="text-sm">
          <span className="font-bold italic">{e.name}.</span>{' '}
          <span className="whitespace-pre-line" dangerouslySetInnerHTML={{ __html: parseMarkup(e.description) }} />
        </div>
      ))}
    </div>
  );
}
