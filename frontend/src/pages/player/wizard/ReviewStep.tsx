import type { CharacterClassRef, Race, Spell } from '../../../types/reference';
import { ABILITIES, abilityMod, formatMod, safeJsonParse, ABILITY_ABBR as ABILITY_LABELS } from '../../../utils/dndRules';
import { THIRD_CASTER_SUBCLASSES, proficiencyBonusForLevel } from '../../../utils/spellConstants';
import type { ParsedFeatOption } from '../../../utils/featSpellParser';
import type { AbilityScores, ClassEntry } from './types';

export interface ReviewStepProps {
  name: string;
  alignment: string;
  selectedRace: Race | null;
  selectedClass: CharacterClassRef | null;
  classEntries: ClassEntry[];
  level: number;
  selectedBackground: { name: string; id: string } | null;
  finalScores: AbilityScores;
  selectedClassSkills: string[];
  resolvedRaceChoices: {
    languages: string[];
    skills: string[];
    tools: string[];
    weapons: string[];
    resistances: string[];
    spellAbility: string | null;
    feats: string[];
  };
  resolvedBgProfs: { skills: string[]; tools: string[]; languages: string[] };
  selectedBgFeat: string | null;
  selectedFeatOption: ParsedFeatOption | null;
  parsedFeatOptions: ParsedFeatOption[];
  selectedFeatAbility: string | null;
  selectedFeatAsiAbility: string | null;
  featAsi: { fixed: Record<string, number>; choose?: { from: string[]; amount: number } } | null;
  featCantrips: Spell[];
  featSpells: Spell[];
  selectedCantrips: Spell[];
  selectedSpells: Spell[];
  mcSpellSelections: Record<string, { cantrips: Spell[]; spells: Spell[] }>;
  error: string;
}

export default function ReviewStep({
  name, alignment, selectedRace, selectedClass, classEntries, level,
  selectedBackground, finalScores,
  selectedClassSkills,
  resolvedRaceChoices, resolvedBgProfs,
  selectedBgFeat, selectedFeatOption, parsedFeatOptions,
  selectedFeatAbility, selectedFeatAsiAbility, featAsi,
  featCantrips, featSpells,
  selectedCantrips, selectedSpells, mcSpellSelections,
  error,
}: ReviewStepProps) {
  return (
    <div className="space-y-6">
      <h2 className="font-heading text-[15px] font-semibold text-ink">Review Your Character</h2>

      <div className="bg-card border border-rule p-6 space-y-4">
        <div className="grid grid-cols-2 gap-4">
          <ReviewField label="Name" value={name} />
          <ReviewField label="Alignment" value={alignment || 'None'} />
          <ReviewField label="Race" value={selectedRace?.name || '—'} />
          <ReviewField label="Class" value={
            classEntries.length > 1
              ? classEntries.map(e => `${e.cls.name} ${e.level}`).join(' / ')
              : selectedClass?.name || '—'
          } />
          <ReviewField label="Level" value={String(level)} />
          {classEntries.filter(e => e.subclass).map(e => (
            <ReviewField key={e.cls.id} label={classEntries.length > 1 ? `${e.cls.name} Subclass` : 'Subclass'} value={e.subclass!.name} />
          ))}
          <ReviewField label="Background" value={selectedBackground?.name || '—'} />
          <ReviewField label="Proficiency Bonus" value={`+${proficiencyBonusForLevel(level)}`} />
          <ReviewField label="Hit Points" value={level === 1 && classEntries.length <= 1 ? String((selectedClass?.hitDice || 0) + abilityMod(finalScores.constitution)) : `Calculated by server for level ${level}`} />
          <ReviewField label="Speed" value={(() => {
            const sp = safeJsonParse<Record<string, number | boolean>>(selectedRace?.speed ?? null, { walk: 30 });
            const w = typeof sp.walk === 'number' ? sp.walk : 30;
            const extras = (['fly', 'swim', 'climb', 'burrow'] as const)
              .filter(k => sp[k] !== undefined)
              .map(k => `${k} ${sp[k] === true ? w : sp[k]} ft`);
            return [`${w} ft`, ...extras].join(', ');
          })()} />
        </div>

        {selectedClassSkills.length > 0 && (
          <div className="border-t border-rule pt-4">
            <h3 className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint mb-3">Class Skills</h3>
            <div className="grid grid-cols-2 gap-4">
              <ReviewField label="Skill Proficiencies" value={selectedClassSkills.join(', ')} />
            </div>
          </div>
        )}

        {(resolvedRaceChoices.languages.length > 0 || resolvedRaceChoices.skills.length > 0 || resolvedRaceChoices.tools.length > 0 || resolvedRaceChoices.weapons.length > 0 || resolvedRaceChoices.resistances.length > 0 || resolvedRaceChoices.spellAbility || resolvedRaceChoices.feats.length > 0) && (
          <div className="border-t border-rule pt-4">
            <h3 className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint mb-3">Race Choices</h3>
            <div className="grid grid-cols-2 gap-4">
              {resolvedRaceChoices.languages.length > 0 && (
                <ReviewField label="Languages" value={resolvedRaceChoices.languages.join(', ')} />
              )}
              {resolvedRaceChoices.skills.length > 0 && (
                <ReviewField label="Skills" value={resolvedRaceChoices.skills.join(', ')} />
              )}
              {resolvedRaceChoices.tools.length > 0 && (
                <ReviewField label="Tools" value={resolvedRaceChoices.tools.join(', ')} />
              )}
              {resolvedRaceChoices.weapons.length > 0 && (
                <ReviewField label="Weapons" value={resolvedRaceChoices.weapons.join(', ')} />
              )}
              {resolvedRaceChoices.resistances.length > 0 && (
                <ReviewField label="Damage Resistance" value={resolvedRaceChoices.resistances.join(', ')} />
              )}
              {resolvedRaceChoices.spellAbility && (
                <ReviewField label="Spellcasting Ability" value={resolvedRaceChoices.spellAbility} />
              )}
              {resolvedRaceChoices.feats.length > 0 && (
                <ReviewField label="Feat" value={resolvedRaceChoices.feats.join(', ')} />
              )}
            </div>
          </div>
        )}

        {(resolvedBgProfs.skills.length > 0 || resolvedBgProfs.tools.length > 0 || resolvedBgProfs.languages.length > 0) && (
          <div className="border-t border-rule pt-4">
            <h3 className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint mb-3">Background Proficiencies</h3>
            <div className="grid grid-cols-2 gap-4">
              {resolvedBgProfs.skills.length > 0 && (
                <ReviewField label="Skills" value={resolvedBgProfs.skills.join(', ')} />
              )}
              {resolvedBgProfs.tools.length > 0 && (
                <ReviewField label="Tools" value={resolvedBgProfs.tools.join(', ')} />
              )}
              {resolvedBgProfs.languages.length > 0 && (
                <ReviewField label="Languages" value={resolvedBgProfs.languages.join(', ')} />
              )}
            </div>
          </div>
        )}

        {selectedBgFeat && (
          <div className="border-t border-rule pt-4">
            <h3 className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint mb-3">Feat</h3>
            <div className="grid grid-cols-2 gap-4">
              <ReviewField label="Feat" value={selectedBgFeat} />
              {selectedFeatOption && parsedFeatOptions.length > 1 && (
                <ReviewField label="Option" value={selectedFeatOption.name} />
              )}
              {selectedFeatAbility && (
                <ReviewField label="Spellcasting Ability" value={selectedFeatAbility} />
              )}
              {selectedFeatAsiAbility && featAsi?.choose && (
                <ReviewField label="Ability Increase" value={`+${featAsi.choose.amount} ${selectedFeatAsiAbility}`} />
              )}
              {featAsi && Object.keys(featAsi.fixed).length > 0 && (
                <ReviewField label="Ability Increase" value={Object.entries(featAsi.fixed).map(([k, v]) => `+${v} ${k}`).join(', ')} />
              )}
              {(selectedFeatOption?.fixedCantrips.length ?? 0) > 0 && (
                <ReviewField label="Feat Cantrips" value={selectedFeatOption!.fixedCantrips.join(', ')} />
              )}
              {featCantrips.length > 0 && (
                <ReviewField label="Chosen Cantrips" value={featCantrips.map(s => s.name).join(', ')} />
              )}
              {featSpells.length > 0 && (
                <ReviewField label="Feat Spells" value={featSpells.map(s => s.name).join(', ')} />
              )}
              {(selectedFeatOption?.fixedSpells.length ?? 0) > 0 && (
                <ReviewField label="Granted Spells" value={selectedFeatOption!.fixedSpells.map(s => s.name).join(', ')} />
              )}
            </div>
          </div>
        )}

        {(() => {
          const spellGroups: Array<{ label: string; spells: string[] }> = [];
          if (selectedCantrips.length > 0) {
            spellGroups.push({ label: `${selectedClass?.name ?? 'Class'} Cantrips`, spells: selectedCantrips.map(s => s.name) });
          }
          if (selectedSpells.length > 0) {
            spellGroups.push({ label: `${selectedClass?.name ?? 'Class'} Spells`, spells: selectedSpells.map(s => s.name) });
          }
          for (const entry of classEntries) {
            if (entry.cls.id === selectedClass?.id) continue;
            const sel = mcSpellSelections[entry.cls.id];
            if (sel?.cantrips?.length) spellGroups.push({ label: `${entry.cls.name} Cantrips`, spells: sel.cantrips.map(s => s.name) });
            if (sel?.spells?.length) spellGroups.push({ label: `${entry.cls.name} Spells`, spells: sel.spells.map(s => s.name) });
          }
          for (const entry of classEntries) {
            if (!entry.subclass || !THIRD_CASTER_SUBCLASSES.has(entry.subclass.name)) continue;
            const sel = mcSpellSelections[`third:${entry.cls.id}`];
            if (sel?.cantrips?.length) spellGroups.push({ label: `${entry.subclass.name} Cantrips`, spells: sel.cantrips.map(s => s.name) });
            if (sel?.spells?.length) spellGroups.push({ label: `${entry.subclass.name} Spells`, spells: sel.spells.map(s => s.name) });
          }
          if (spellGroups.length === 0) return null;
          return (
            <div className="border-t border-rule pt-4">
              <h3 className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint mb-3">Selected Spells</h3>
              <div className="space-y-2">
                {spellGroups.map(g => (
                  <div key={g.label}>
                    <p className="font-body text-[11px] text-faint">{g.label}</p>
                    <p className="font-body text-[13px] font-medium text-ink">{g.spells.join(', ')}</p>
                  </div>
                ))}
              </div>
            </div>
          );
        })()}

        {(() => {
          const groups: Array<{ scName: string; spells: string[] }> = [];
          for (const entry of classEntries) {
            if (!entry.subclass?.alwaysPreparedSpells) continue;
            try {
              const parsed = JSON.parse(entry.subclass.alwaysPreparedSpells) as Record<string, string[]>;
              const unlocked: string[] = [];
              for (const [lvlKey, spells] of Object.entries(parsed)) {
                if (parseInt(lvlKey) <= entry.level) unlocked.push(...spells);
              }
              if (unlocked.length > 0) groups.push({ scName: entry.subclass.name, spells: unlocked });
            } catch { /* ignore parse errors */ }
          }
          if (groups.length === 0) return null;
          return (
            <div className="border-t border-rule pt-4">
              <h3 className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint mb-3">Always Prepared (Subclass)</h3>
              <div className="space-y-2">
                {groups.map(g => (
                  <div key={g.scName}>
                    <p className="font-body text-[11px] text-faint">{g.scName}</p>
                    <p className="font-body text-[13px] font-medium text-buff">{g.spells.join(', ')}</p>
                  </div>
                ))}
              </div>
            </div>
          );
        })()}

        <div className="border-t border-rule pt-4">
          <h3 className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint mb-3">Ability Scores</h3>
          <div className="grid grid-cols-6 gap-3">
            {ABILITIES.map(a => (
              <div key={a} className="text-center bg-page py-2">
                <p className="font-heading text-[8px] font-semibold tracking-[0.1em] uppercase text-faint">{ABILITY_LABELS[a]}</p>
                <p className="font-heading text-[17px] font-bold text-ink">{finalScores[a]}</p>
                <p className="font-body text-[11px] font-medium text-muted">{formatMod(abilityMod(finalScores[a]))}</p>
              </div>
            ))}
          </div>
        </div>
      </div>

      {error && <p className="font-body text-[13px] font-medium text-debuff">{error}</p>}
    </div>
  );
}

function ReviewField({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <p className="font-heading text-[9px] font-semibold tracking-[0.06em] uppercase text-faint">{label}</p>
      <p className="font-body text-[13px] font-medium text-ink">{value}</p>
    </div>
  );
}
