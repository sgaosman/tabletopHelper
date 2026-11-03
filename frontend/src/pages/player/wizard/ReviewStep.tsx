import type { CharacterClassRef, Race, Spell } from '../../../types/reference';
import { ABILITIES, abilityMod, formatMod, safeJsonParse, ABILITY_ABBR as ABILITY_LABELS } from '../../../utils/dndRules';
import { proficiencyBonusForLevel } from '../../../utils/spellConstants';
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
  };
  resolvedBgProfs: { skills: string[]; tools: string[]; languages: string[] };
  /** The feats the race and background grant, which the character takes on its sheet after creation (D159) */
  raceFeats: number;
  bgFeatNames: string[];
  selectedCantrips: Spell[];
  selectedSpells: Spell[];
  error: string;
}

export default function ReviewStep({
  name, alignment, selectedRace, selectedClass, classEntries, level,
  selectedBackground, finalScores,
  selectedClassSkills,
  resolvedRaceChoices, resolvedBgProfs,
  raceFeats, bgFeatNames,
  selectedCantrips, selectedSpells,
  error,
}: ReviewStepProps) {
  return (
    <div className="space-y-6">
      <h2 className="font-heading text-[17px] font-semibold text-ink">Review Your Character</h2>

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
          <ReviewField label="Hit Points" value="Worked out when the character is created" />
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
            <h3 className="font-heading text-[13px] font-semibold tracking-[0.06em] uppercase text-faint mb-3">Class Skills</h3>
            <div className="grid grid-cols-2 gap-4">
              <ReviewField label="Skill Proficiencies" value={selectedClassSkills.join(', ')} />
            </div>
          </div>
        )}

        {(resolvedRaceChoices.languages.length > 0 || resolvedRaceChoices.skills.length > 0 || resolvedRaceChoices.tools.length > 0 || resolvedRaceChoices.weapons.length > 0 || resolvedRaceChoices.resistances.length > 0 || resolvedRaceChoices.spellAbility) && (
          <div className="border-t border-rule pt-4">
            <h3 className="font-heading text-[13px] font-semibold tracking-[0.06em] uppercase text-faint mb-3">Race Choices</h3>
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
            </div>
          </div>
        )}

        {(resolvedBgProfs.skills.length > 0 || resolvedBgProfs.tools.length > 0 || resolvedBgProfs.languages.length > 0) && (
          <div className="border-t border-rule pt-4">
            <h3 className="font-heading text-[13px] font-semibold tracking-[0.06em] uppercase text-faint mb-3">Background Proficiencies</h3>
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

        {/* Feats and the choices of later levels are Pending Choices, made on the sheet so they apply (D159) */}
        {(raceFeats > 0 || bgFeatNames.length > 0 || level > 1) && (
          <div className="border-t border-rule pt-4">
            <h3 className="font-heading text-[13px] font-semibold tracking-[0.06em] uppercase text-faint mb-3">To Choose on Your Sheet</h3>
            <ul className="font-body text-[15px] font-semibold text-ink list-disc list-inside space-y-1">
              {raceFeats > 0 && <li>{raceFeats === 1 ? 'A feat' : `${raceFeats} feats`} from your race</li>}
              {bgFeatNames.length > 0 && (
                <li>{bgFeatNames.length > 1 ? `One of ${bgFeatNames.join(', ')}` : bgFeatNames[0]}, from your background</li>
              )}
              {level > 1 && <li>The choices of your levels above the 1st, such as Ability Score Improvements and spells</li>}
            </ul>
            <p className="font-body text-[13px] text-faint mt-2">You'll choose these on your character sheet after creation.</p>
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
          if (spellGroups.length === 0) return null;
          return (
            <div className="border-t border-rule pt-4">
              <h3 className="font-heading text-[13px] font-semibold tracking-[0.06em] uppercase text-faint mb-3">Selected Spells</h3>
              <div className="space-y-2">
                {spellGroups.map(g => (
                  <div key={g.label}>
                    <p className="font-body text-[13px] text-faint">{g.label}</p>
                    <p className="font-body text-[15px] font-semibold text-ink">{g.spells.join(', ')}</p>
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
              <h3 className="font-heading text-[13px] font-semibold tracking-[0.06em] uppercase text-faint mb-3">Always Prepared (Subclass)</h3>
              <div className="space-y-2">
                {groups.map(g => (
                  <div key={g.scName}>
                    <p className="font-body text-[13px] text-faint">{g.scName}</p>
                    <p className="font-body text-[15px] font-semibold text-buff">{g.spells.join(', ')}</p>
                  </div>
                ))}
              </div>
            </div>
          );
        })()}

        <div className="border-t border-rule pt-4">
          <h3 className="font-heading text-[13px] font-semibold tracking-[0.06em] uppercase text-faint mb-3">Ability Scores</h3>
          <div className="grid grid-cols-6 gap-3">
            {ABILITIES.map(a => (
              <div key={a} className="text-center bg-page py-2">
                <p className="font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-faint">{ABILITY_LABELS[a]}</p>
                <p className="font-heading text-[17px] font-bold text-ink">{finalScores[a]}</p>
                <p className="font-body text-[13px] font-semibold text-muted">{formatMod(abilityMod(finalScores[a]))}</p>
              </div>
            ))}
          </div>
        </div>
      </div>

      {error && <p className="font-body text-[15px] font-semibold text-debuff">{error}</p>}
    </div>
  );
}

function ReviewField({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <p className="font-heading text-[11px] font-semibold tracking-[0.06em] uppercase text-faint">{label}</p>
      <p className="font-body text-[15px] font-semibold text-ink">{value}</p>
    </div>
  );
}
