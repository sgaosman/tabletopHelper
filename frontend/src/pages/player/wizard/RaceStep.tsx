import { Search } from 'lucide-react';
import type { Race } from '../../../types/reference';
import { ABILITIES, safeJsonParse, ABILITY_ABBR as ABILITY_LABELS } from '../../../utils/dndRules';
import type { RaceChoiceReq } from './types';

export interface RaceStepProps {
  filteredRaces: Race[];
  raceSearch: string;
  setRaceSearch: (v: string) => void;
  selectedRace: Race | null;
  setSelectedRace: (r: Race | null) => void;
  raceChoiceReqs: RaceChoiceReq[];
  raceChoiceSelections: Record<string, string[]>;
  handleRaceChoice: (key: string, value: string, count: number) => void;
  raceChoicesComplete: boolean;
  bonusAssignments: Array<{ bonus: number; ability: string | null }>;
  handleBonusAssignment: (rowIndex: number, ability: string) => void;
  isVanillaHuman: boolean;
}

export default function RaceStep({
  filteredRaces, raceSearch, setRaceSearch,
  selectedRace, setSelectedRace,
  raceChoiceReqs, raceChoiceSelections, handleRaceChoice, raceChoicesComplete,
  bonusAssignments, handleBonusAssignment, isVanillaHuman,
}: RaceStepProps) {
  return (
    <div className="space-y-4">
      <h2 className="font-heading text-[15px] font-semibold text-ink">Choose a Race</h2>
      <div className="relative max-w-md">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-faint" />
        <input
          type="text"
          value={raceSearch}
          onChange={e => setRaceSearch(e.target.value)}
          placeholder="Search races..."
          className="w-full pl-9 pr-4 py-2 bg-card border border-rule font-body text-[14px] font-medium text-ink placeholder-faint focus:border-muted focus:outline-none"
        />
      </div>
      <div className="grid grid-cols-1 md:grid-cols-2 gap-3 max-h-[60vh] overflow-y-auto pr-1">
        {filteredRaces.map(race => {
          const bonuses = safeJsonParse<Array<{ ability: string; bonus: number; count?: number }>>(race.abilityScoreBonuses, []);
          const fixedParts = bonuses.filter(b => b.ability !== 'CHOOSE').map(b => `${b.ability} +${b.bonus}`);
          const chooseParts = bonuses.filter(b => b.ability === 'CHOOSE').map(b => `${b.count || 1}x +${b.bonus || 1}`);
          const chooseSummary = chooseParts.length > 0 ? `Choose ${chooseParts.join(', ')}` : '';
          const bonusSummary = [...fixedParts, chooseSummary].filter(Boolean).join(', ');
          const speed = safeJsonParse<Record<string, number | boolean>>(race.speed, { walk: 30 });
          const walkSpeed = typeof speed.walk === 'number' ? speed.walk : 30;
          const extraSpeeds = (['fly', 'swim', 'climb', 'burrow'] as const)
            .filter(k => speed[k] !== undefined)
            .map(k => `${k} ${speed[k] === true ? walkSpeed : speed[k]} ft`);
          const speedText = [`Speed ${walkSpeed} ft`, ...extraSpeeds].join(', ');
          return (
            <button
              key={race.id}
              onClick={() => setSelectedRace(selectedRace?.id === race.id ? null : race)}
              className={`p-4 border text-left transition-colors ${
                selectedRace?.id === race.id
                  ? 'bg-page-alt border-ink'
                  : 'bg-card border-rule hover:border-muted'
              }`}
            >
              <div className="flex items-start justify-between">
                <h3 className="font-heading text-[13px] font-semibold text-ink">{race.name}</h3>
                <span className="font-body text-[11px] text-faint">{race.source}</span>
              </div>
              <div className="mt-1 space-y-0.5">
                <p className="font-body text-[11px] font-medium text-muted">{speedText} &middot; {race.size}</p>
                {bonusSummary && <p className="font-body text-[11px] font-medium text-cls-druid">{bonusSummary}</p>}
                {race.darkvision && <p className="font-body text-[11px] text-faint">Darkvision {race.darkvision} ft</p>}
              </div>
            </button>
          );
        })}
      </div>
      {selectedRace && (
        <RaceDetail race={selectedRace} />
      )}
      {selectedRace && raceChoiceReqs.length > 0 && (
        <div className="bg-card border border-rule p-4 mt-4">
          <h3 className="font-heading text-[13px] font-semibold text-ink mb-1">Race Choices</h3>
          <p className="font-body text-[11px] text-faint mb-3">Make the following selections for your race</p>
          <div className="space-y-4">
            {raceChoiceReqs.map(req => (
              <div key={req.key}>
                <p className="font-body text-[11px] font-medium text-muted mb-2">
                  {req.label}: choose {req.count}
                </p>
                <div className="flex gap-1.5 flex-wrap">
                  {req.options.map(option => (
                    <button
                      key={option}
                      onClick={() => handleRaceChoice(req.key, option, req.count)}
                      className={`px-3 py-1.5 font-heading text-[9px] font-medium tracking-[0.02em] transition-colors border ${
                        (raceChoiceSelections[req.key] ?? []).includes(option)
                          ? 'bg-ink text-card border-ink'
                          : 'bg-page text-muted border-rule hover:border-muted'
                      }`}
                    >
                      {option}
                    </button>
                  ))}
                </div>
              </div>
            ))}
          </div>
          {!raceChoicesComplete && (
            <p className="font-body text-[11px] font-medium text-hp-wounded mt-3">Complete all selections to continue</p>
          )}
        </div>
      )}
      {selectedRace && !isVanillaHuman && bonusAssignments.length > 0 && (
        <div className="bg-card border border-rule p-4 mt-4">
          <h3 className="font-heading text-[13px] font-semibold text-ink mb-1">Assign Ability Score Bonuses</h3>
          <p className="font-body text-[11px] text-faint mb-3">Tasha's rules: reassign racial bonuses to any ability</p>
          <div className="space-y-3">
            {bonusAssignments.map((assignment, i) => (
              <div key={i} className="flex items-center gap-3 flex-wrap">
                <span className="font-body text-[13px] font-medium text-cls-druid w-24 shrink-0">
                  Apply +{assignment.bonus} to:
                </span>
                <div className="flex gap-1.5">
                  {ABILITIES.map(ability => (
                    <button
                      key={ability}
                      onClick={() => handleBonusAssignment(i, ability)}
                      className={`px-3 py-1.5 font-heading text-[9px] font-medium tracking-[0.02em] transition-colors border ${
                        assignment.ability === ability
                          ? 'bg-ink text-card border-ink'
                          : 'bg-page text-muted border-rule hover:border-muted'
                      }`}
                    >
                      {ABILITY_LABELS[ability]}
                    </button>
                  ))}
                </div>
              </div>
            ))}
          </div>
          {bonusAssignments.some(a => a.ability === null) && (
            <p className="font-body text-[11px] font-medium text-hp-wounded mt-3">Select all bonuses to continue</p>
          )}
        </div>
      )}
    </div>
  );
}

function RaceDetail({ race }: { race: Race }) {
  const traits = safeJsonParse<Array<{ name: string; description: string }>>(race.traits, []);
  const profs = safeJsonParse<{ skills?: string[]; languages?: string[]; weapons?: string[]; armor?: string[]; tools?: string[] }>(race.proficiencies, {});

  const profItems: string[] = [];
  if (profs.skills?.length) profItems.push(`Skills: ${profs.skills.join(', ')}`);
  if (profs.weapons?.length) profItems.push(`Weapons: ${profs.weapons.join(', ')}`);
  if (profs.armor?.length) profItems.push(`Armor: ${profs.armor.join(', ')}`);
  if (profs.tools?.length) profItems.push(`Tools: ${profs.tools.join(', ')}`);
  if (profs.languages?.length) profItems.push(`Languages: ${profs.languages.join(', ')}`);

  return (
    <div className="bg-card border border-rule p-4 mt-4">
      <h3 className="font-heading text-[13px] font-semibold text-ink mb-2">{race.name} — Traits</h3>
      {profItems.length > 0 && (
        <p className="font-body text-[11px] font-medium text-cls-druid mb-2">{profItems.join(' | ')}</p>
      )}
      <div className="space-y-2">
        {traits.slice(0, 8).map((t, i) => (
          <div key={i}>
            <p className="font-body text-[13px] font-semibold text-ink">{t.name}</p>
            <p className="font-body text-[11px] font-medium text-faint line-clamp-2">{t.description}</p>
          </div>
        ))}
      </div>
    </div>
  );
}
