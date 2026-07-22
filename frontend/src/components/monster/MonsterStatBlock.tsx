import type { Monster } from '../../types/monster';
import { parseMarkup } from '../../utils/parseMarkup';
import { formatAbilityMod } from '../../utils/dndRules';

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
    <div className="max-w-2xl bg-card border border-rule overflow-hidden" style={{ borderLeftWidth: '3px', borderLeftColor: '#991B1B' }}>
      <div className="px-5 py-3 border-b border-rule bg-debuff-bg">
        <h2 className="font-heading text-[17px] font-bold text-monster">{m.name}</h2>
        <p className="font-body text-[12px] font-medium text-muted italic">
          {m.size} {m.type}{m.subtype ? ` (${m.subtype})` : ''}, {m.alignment || 'unaligned'}
        </p>
      </div>

      <div className="px-5 py-4 space-y-3">
        <div className="border-b border-rule-light pb-3 space-y-1">
          <p className="font-body text-[12px] font-medium text-muted"><span className="font-semibold text-ink">Armour Class</span> {m.armourClass}{m.acType ? ` (${m.acType})` : ''}</p>
          <p className="font-body text-[12px] font-medium text-muted"><span className="font-semibold text-ink">Hit Points</span> {m.hitPoints}{m.hitDice ? ` (${m.hitDice})` : ''}</p>
          <p className="font-body text-[12px] font-medium text-muted"><span className="font-semibold text-ink">Speed</span> {speedStr}</p>
        </div>

        <div className="border-b border-rule-light pb-3">
          <div className="grid grid-cols-6 text-center gap-1">
            {(['strength', 'dexterity', 'constitution', 'intelligence', 'wisdom', 'charisma'] as const).map(ab => (
              <div key={ab} className="bg-page py-1.5 px-1">
                <div className="font-heading text-[8px] font-semibold tracking-[0.1em] uppercase text-faint">{ab.slice(0, 3)}</div>
                <div className="font-heading text-[16px] font-bold text-ink">{m[ab]}</div>
                <div className="font-body text-[11px] font-medium text-muted">{formatAbilityMod(m[ab])}</div>
              </div>
            ))}
          </div>
        </div>

        <div className="border-b border-rule-light pb-3 space-y-1">
          {m.savingThrows && (
            <p className="font-body text-[12px] font-medium text-muted"><span className="font-semibold text-ink">Saving Throws</span> {
              Object.entries(m.savingThrows as Record<string, string>).map(([k, v]) =>
                `${k.charAt(0).toUpperCase() + k.slice(1)} ${v}`
              ).join(', ')
            }</p>
          )}
          {m.skills && (
            <p className="font-body text-[12px] font-medium text-muted"><span className="font-semibold text-ink">Skills</span> {
              Object.entries(m.skills as Record<string, string>).map(([k, v]) =>
                `${k.charAt(0).toUpperCase() + k.slice(1)} ${v}`
              ).join(', ')
            }</p>
          )}
          {m.damageResistances && m.damageResistances.length > 0 && (
            <p className="font-body text-[12px] font-medium text-muted"><span className="font-semibold text-ink">Damage Resistances</span> {m.damageResistances.join(', ')}</p>
          )}
          {m.damageImmunities && m.damageImmunities.length > 0 && (
            <p className="font-body text-[12px] font-medium text-muted"><span className="font-semibold text-ink">Damage Immunities</span> {m.damageImmunities.join(', ')}</p>
          )}
          {m.damageVulnerabilities && m.damageVulnerabilities.length > 0 && (
            <p className="font-body text-[12px] font-medium text-muted"><span className="font-semibold text-ink">Damage Vulnerabilities</span> {m.damageVulnerabilities.join(', ')}</p>
          )}
          {m.conditionImmunities && m.conditionImmunities.length > 0 && (
            <p className="font-body text-[12px] font-medium text-muted"><span className="font-semibold text-ink">Condition Immunities</span> {m.conditionImmunities.join(', ')}</p>
          )}
          <p className="font-body text-[12px] font-medium text-muted"><span className="font-semibold text-ink">Senses</span> {sensesStr}</p>
          <p className="font-body text-[12px] font-medium text-muted"><span className="font-semibold text-ink">Languages</span> {m.languages || '-'}</p>
          <p className="font-body text-[12px] font-medium text-muted"><span className="font-semibold text-ink">Challenge</span> {m.challengeRating} ({m.experiencePoints.toLocaleString()} XP)</p>
        </div>

        {m.traits && m.traits.length > 0 && (
          <div className="space-y-2">
            {m.traits.map((t, i) => (
              <div key={i} className="font-body text-[12px] font-medium text-muted">
                <span className="font-semibold italic text-ink">{t.name}.</span>{' '}
                <span className="whitespace-pre-line" dangerouslySetInnerHTML={{ __html: parseMarkup(t.description) }} />
              </div>
            ))}
          </div>
        )}

        <EntrySection title="Actions" entries={m.actions} />
        <EntrySection title="Reactions" entries={m.reactions} />
        <EntrySection title="Legendary Actions" entries={m.legendaryActions} />
      </div>

      <div className="px-5 py-1.5 border-t border-rule-light text-right">
        <span className="font-body text-[11px] text-faint">Source: {m.source}</span>
      </div>
    </div>
  );
}

function EntrySection({ title, entries }: { title: string; entries: { name: string; description: string }[] | null }) {
  if (!entries || entries.length === 0) return null;
  return (
    <div className="border-t border-rule-light pt-3 space-y-2">
      <h3 className="font-heading text-[13px] font-bold text-monster">{title}</h3>
      {entries.map((e, i) => (
        <div key={i} className="font-body text-[12px] font-medium text-muted">
          <span className="font-semibold italic text-ink">{e.name}.</span>{' '}
          <span className="whitespace-pre-line" dangerouslySetInnerHTML={{ __html: parseMarkup(e.description) }} />
        </div>
      ))}
    </div>
  );
}
