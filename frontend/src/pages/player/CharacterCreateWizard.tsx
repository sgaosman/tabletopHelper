import { useState, useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, ArrowRight, Check, ChevronLeft, Search } from 'lucide-react';
import { getRaces, getClasses, getSubclasses, getBackgrounds } from '../../api/referenceApi';
import { characterApi } from '../../api/characterApi';
import type { Race, CharacterClassRef, Subclass, Background } from '../../types/reference';

const STEPS = ['Basic Info', 'Race', 'Class', 'Ability Scores', 'Background', 'Review'] as const;

const ALIGNMENTS = [
  'Lawful Good', 'Neutral Good', 'Chaotic Good',
  'Lawful Neutral', 'True Neutral', 'Chaotic Neutral',
  'Lawful Evil', 'Neutral Evil', 'Chaotic Evil',
];

const STANDARD_ARRAY = [15, 14, 13, 12, 10, 8];
const ABILITIES = ['strength', 'dexterity', 'constitution', 'intelligence', 'wisdom', 'charisma'] as const;
const ABILITY_LABELS: Record<string, string> = {
  strength: 'STR', dexterity: 'DEX', constitution: 'CON',
  intelligence: 'INT', wisdom: 'WIS', charisma: 'CHA',
};

type AbilityScores = Record<typeof ABILITIES[number], number>;

function abilityMod(score: number): number {
  return Math.floor((score - 10) / 2);
}

function formatMod(mod: number): string {
  return mod >= 0 ? `+${mod}` : `${mod}`;
}

function safeJsonParse<T>(json: string | null | undefined, fallback: T): T {
  if (!json) return fallback;
  try { return JSON.parse(json); } catch { return fallback; }
}

export default function CharacterCreateWizard() {
  const navigate = useNavigate();
  const [step, setStep] = useState(0);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');

  const [name, setName] = useState('');
  const [alignment, setAlignment] = useState('');

  const [races, setRaces] = useState<Race[]>([]);
  const [raceSearch, setRaceSearch] = useState('');
  const [selectedRace, setSelectedRace] = useState<Race | null>(null);

  const [classes, setClasses] = useState<CharacterClassRef[]>([]);
  const [selectedClass, setSelectedClass] = useState<CharacterClassRef | null>(null);
  const [subclasses, setSubclasses] = useState<Subclass[]>([]);
  const [selectedSubclass, setSelectedSubclass] = useState<Subclass | null>(null);

  const [abilityMethod, setAbilityMethod] = useState<'standard' | 'pointbuy' | 'manual'>('standard');
  const [scores, setScores] = useState<AbilityScores>({ strength: 10, dexterity: 10, constitution: 10, intelligence: 10, wisdom: 10, charisma: 10 });
  const [standardAssignments, setStandardAssignments] = useState<Record<string, number | null>>({
    strength: null, dexterity: null, constitution: null,
    intelligence: null, wisdom: null, charisma: null,
  });

  const [backgrounds, setBackgrounds] = useState<Background[]>([]);
  const [bgSearch, setBgSearch] = useState('');
  const [selectedBackground, setSelectedBackground] = useState<Background | null>(null);

  useEffect(() => {
    getRaces().then(res => setRaces(res));
    getClasses().then(res => setClasses(res));
    getBackgrounds().then(res => setBackgrounds(res));
  }, []);

  useEffect(() => {
    if (selectedClass) {
      getSubclasses(selectedClass.id).then(res => setSubclasses(res));
    } else {
      setSubclasses([]);
      setSelectedSubclass(null);
    }
  }, [selectedClass]);

  const filteredRaces = useMemo(() => {
    if (!raceSearch) return races;
    const q = raceSearch.toLowerCase();
    return races.filter(r => r.name.toLowerCase().includes(q));
  }, [races, raceSearch]);

  const filteredBackgrounds = useMemo(() => {
    if (!bgSearch) return backgrounds;
    const q = bgSearch.toLowerCase();
    return backgrounds.filter(b => b.name.toLowerCase().includes(q));
  }, [backgrounds, bgSearch]);

  const racialBonuses = useMemo(() => {
    if (!selectedRace) return {};
    const bonuses = safeJsonParse<Array<{ ability: string; bonus: number }>>(selectedRace.abilityScoreBonuses, []);
    const map: Record<string, number> = {};
    bonuses.forEach(b => {
      if (b.ability && b.ability !== 'CHOOSE') {
        const key = b.ability.toLowerCase().slice(0, 3);
        const ability = ABILITIES.find(a => a.startsWith(key));
        if (ability) map[ability] = (map[ability] || 0) + (b.bonus || 0);
      }
    });
    return map;
  }, [selectedRace]);

  const finalScores = useMemo(() => {
    const base = abilityMethod === 'standard'
      ? Object.fromEntries(ABILITIES.map(a => [a, standardAssignments[a] ?? 10])) as AbilityScores
      : scores;
    const result: AbilityScores = { ...base };
    for (const [ability, bonus] of Object.entries(racialBonuses)) {
      if (ability in result) {
        result[ability as keyof AbilityScores] += bonus;
      }
    }
    return result;
  }, [scores, standardAssignments, abilityMethod, racialBonuses]);

  const pointBuyTotal = useMemo(() => {
    let total = 0;
    for (const a of ABILITIES) {
      const s = scores[a];
      if (s >= 8 && s <= 13) total += s - 8;
      else if (s === 14) total += 7;
      else if (s === 15) total += 9;
    }
    return total;
  }, [scores]);

  function canAdvance(): boolean {
    switch (step) {
      case 0: return name.trim().length > 0;
      case 1: return selectedRace !== null;
      case 2: return selectedClass !== null;
      case 3:
        if (abilityMethod === 'standard') {
          return Object.values(standardAssignments).every(v => v !== null);
        }
        if (abilityMethod === 'pointbuy') return pointBuyTotal <= 27;
        return true;
      case 4: return selectedBackground !== null;
      default: return true;
    }
  }

  async function handleCreate() {
    if (!selectedRace || !selectedClass || !selectedBackground) return;
    setSubmitting(true);
    setError('');

    const conMod = abilityMod(finalScores.constitution);
    const hpMax = selectedClass.hitDice + conMod;

    const savingThrows = safeJsonParse<string[]>(selectedClass.savingThrowProficiencies, []);
    const speed = safeJsonParse<{ walk?: number }>(selectedRace.speed, { walk: 30 });
    const resistances = safeJsonParse<string[]>(selectedRace.resistances, []);

    try {
      const res = await characterApi.create({
        name: name.trim(),
        raceId: selectedRace.id,
        classId: selectedClass.id,
        subclassId: selectedSubclass?.id,
        backgroundId: selectedBackground.id,
        alignment: alignment || undefined,
        abilityScoreMethod: abilityMethod,
        racialAbilityBonuses: selectedRace.abilityScoreBonuses,
        strength: finalScores.strength,
        dexterity: finalScores.dexterity,
        constitution: finalScores.constitution,
        intelligence: finalScores.intelligence,
        wisdom: finalScores.wisdom,
        charisma: finalScores.charisma,
        hpMax,
        speed: speed.walk ?? 30,
        savingThrowProficiencies: JSON.stringify(savingThrows),
        damageResistances: resistances.length > 0 ? JSON.stringify(resistances) : undefined,
        spellcastingAbility: selectedClass.spellcastingAbility ?? undefined,
        hitDiceMap: JSON.stringify({ [selectedClass.name]: { total: 1, remaining: 1, faces: selectedClass.hitDice } }),
      });
      navigate(`/player/characters/${res.data.id}`);
    } catch (err: any) {
      setError(err.response?.data?.error || err.response?.data?.message || 'Failed to create character');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="min-h-screen bg-gray-950">
      <header className="sticky top-0 z-10 bg-gray-950 border-b border-gray-800 px-6 py-4">
        <div className="max-w-3xl mx-auto flex items-center justify-between">
          <button onClick={() => navigate('/player')} className="flex items-center gap-2 text-gray-400 hover:text-white text-sm transition-colors">
            <ChevronLeft className="w-4 h-4" /> Back
          </button>
          <h1 className="text-lg font-bold text-white">Create Character</h1>
          <div className="w-16" />
        </div>
      </header>

      {/* Step indicator */}
      <div className="max-w-3xl mx-auto px-6 pt-6">
        <div className="flex items-center gap-1">
          {STEPS.map((label, i) => (
            <div key={label} className="flex-1 flex flex-col items-center">
              <div className={`w-8 h-8 rounded-full flex items-center justify-center text-xs font-bold border-2 transition-colors ${
                i < step ? 'bg-green-600 border-green-600 text-white' :
                i === step ? 'bg-indigo-600 border-indigo-600 text-white' :
                'bg-gray-800 border-gray-700 text-gray-500'
              }`}>
                {i < step ? <Check className="w-4 h-4" /> : i + 1}
              </div>
              <span className={`text-xs mt-1 hidden sm:block ${i === step ? 'text-indigo-400' : 'text-gray-500'}`}>{label}</span>
            </div>
          ))}
        </div>
      </div>

      <main className="max-w-3xl mx-auto px-6 py-8">
        {/* Step 0: Basic Info */}
        {step === 0 && (
          <div className="space-y-6">
            <h2 className="text-xl font-semibold text-white">Basic Information</h2>
            <div>
              <label className="block text-sm font-medium text-gray-300 mb-1">Character Name *</label>
              <input
                type="text"
                value={name}
                onChange={e => setName(e.target.value)}
                maxLength={200}
                autoFocus
                className="w-full max-w-md px-4 py-2.5 bg-gray-800 border border-gray-700 rounded-lg text-white placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-indigo-500"
                placeholder="Enter character name"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-300 mb-1">Alignment</label>
              <div className="grid grid-cols-3 gap-2 max-w-md">
                {ALIGNMENTS.map(a => (
                  <button
                    key={a}
                    onClick={() => setAlignment(alignment === a ? '' : a)}
                    className={`px-3 py-2 rounded-lg text-xs font-medium transition-colors ${
                      alignment === a ? 'bg-indigo-600 text-white' : 'bg-gray-800 text-gray-400 hover:bg-gray-700'
                    }`}
                  >{a}</button>
                ))}
              </div>
            </div>
          </div>
        )}

        {/* Step 1: Race */}
        {step === 1 && (
          <div className="space-y-4">
            <h2 className="text-xl font-semibold text-white">Choose a Race</h2>
            <div className="relative max-w-md">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-500" />
              <input
                type="text"
                value={raceSearch}
                onChange={e => setRaceSearch(e.target.value)}
                placeholder="Search races..."
                className="w-full pl-10 pr-4 py-2.5 bg-gray-800 border border-gray-700 rounded-lg text-white placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-indigo-500"
              />
            </div>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3 max-h-[60vh] overflow-y-auto pr-1">
              {filteredRaces.map(race => {
                const bonuses = safeJsonParse<Array<{ ability: string; bonus: number }>>(race.abilityScoreBonuses, []);
                const bonusSummary = bonuses
                  .filter(b => b.ability !== 'CHOOSE')
                  .map(b => `${b.ability} +${b.bonus}`)
                  .join(', ');
                const speed = safeJsonParse<{ walk?: number }>(race.speed, { walk: 30 });
                return (
                  <button
                    key={race.id}
                    onClick={() => setSelectedRace(selectedRace?.id === race.id ? null : race)}
                    className={`p-4 rounded-lg border text-left transition-colors ${
                      selectedRace?.id === race.id
                        ? 'bg-indigo-900/30 border-indigo-500'
                        : 'bg-gray-900 border-gray-800 hover:border-gray-600'
                    }`}
                  >
                    <div className="flex items-start justify-between">
                      <h3 className="text-white font-medium text-sm">{race.name}</h3>
                      <span className="text-gray-500 text-xs">{race.source}</span>
                    </div>
                    <div className="mt-1 space-y-0.5">
                      <p className="text-gray-400 text-xs">Speed {speed.walk ?? 30} ft &middot; {race.size}</p>
                      {bonusSummary && <p className="text-cyan-400 text-xs">{bonusSummary}</p>}
                      {race.darkvision && <p className="text-gray-500 text-xs">Darkvision {race.darkvision} ft</p>}
                    </div>
                  </button>
                );
              })}
            </div>
            {selectedRace && (
              <RaceDetail race={selectedRace} />
            )}
          </div>
        )}

        {/* Step 2: Class */}
        {step === 2 && (
          <div className="space-y-4">
            <h2 className="text-xl font-semibold text-white">Choose a Class</h2>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              {classes.map(cls => {
                const saves = safeJsonParse<string[]>(cls.savingThrowProficiencies, []);
                return (
                  <button
                    key={cls.id}
                    onClick={() => {
                      if (selectedClass?.id === cls.id) {
                        setSelectedClass(null);
                        setSelectedSubclass(null);
                      } else {
                        setSelectedClass(cls);
                        setSelectedSubclass(null);
                      }
                    }}
                    className={`p-4 rounded-lg border text-left transition-colors ${
                      selectedClass?.id === cls.id
                        ? 'bg-indigo-900/30 border-indigo-500'
                        : 'bg-gray-900 border-gray-800 hover:border-gray-600'
                    }`}
                  >
                    <div className="flex items-start justify-between">
                      <h3 className="text-white font-medium text-sm">{cls.name}</h3>
                      <span className="text-gray-500 text-xs">d{cls.hitDice}</span>
                    </div>
                    <p className="text-gray-400 text-xs mt-1">Primary: {cls.primaryAbility}</p>
                    {saves.length > 0 && <p className="text-cyan-400 text-xs">Saves: {saves.join(', ')}</p>}
                    {cls.isSpellcaster && <p className="text-purple-400 text-xs mt-0.5">Spellcaster ({cls.spellcastingAbility})</p>}
                  </button>
                );
              })}
            </div>

            {selectedClass && subclasses.length > 0 && selectedClass.subclassLevel <= 1 && (
              <div className="mt-6">
                <h3 className="text-lg font-semibold text-white mb-3">Choose a Subclass (Optional)</h3>
                <div className="grid grid-cols-1 md:grid-cols-2 gap-3 max-h-60 overflow-y-auto">
                  {subclasses.map(sc => (
                    <button
                      key={sc.id}
                      onClick={() => setSelectedSubclass(selectedSubclass?.id === sc.id ? null : sc)}
                      className={`p-3 rounded-lg border text-left transition-colors ${
                        selectedSubclass?.id === sc.id
                          ? 'bg-purple-900/30 border-purple-500'
                          : 'bg-gray-900 border-gray-800 hover:border-gray-600'
                      }`}
                    >
                      <h4 className="text-white text-sm font-medium">{sc.name}</h4>
                      <p className="text-gray-500 text-xs">{sc.source}</p>
                    </button>
                  ))}
                </div>
              </div>
            )}

            {selectedClass && subclasses.length > 0 && selectedClass.subclassLevel > 1 && (
              <p className="text-gray-500 text-sm">Subclass available at level {selectedClass.subclassLevel}</p>
            )}
          </div>
        )}

        {/* Step 3: Ability Scores */}
        {step === 3 && (
          <div className="space-y-6">
            <h2 className="text-xl font-semibold text-white">Ability Scores</h2>

            <div className="flex gap-2">
              {(['standard', 'pointbuy', 'manual'] as const).map(m => (
                <button
                  key={m}
                  onClick={() => setAbilityMethod(m)}
                  className={`px-4 py-2 rounded-lg text-sm font-medium transition-colors ${
                    abilityMethod === m ? 'bg-indigo-600 text-white' : 'bg-gray-800 text-gray-400 hover:bg-gray-700'
                  }`}
                >
                  {m === 'standard' ? 'Standard Array' : m === 'pointbuy' ? 'Point Buy' : 'Manual'}
                </button>
              ))}
            </div>

            {abilityMethod === 'standard' && (
              <div>
                <p className="text-gray-400 text-sm mb-4">Assign each value to an ability: {STANDARD_ARRAY.join(', ')}</p>
                <StandardArrayAssigner assignments={standardAssignments} onChange={setStandardAssignments} racialBonuses={racialBonuses} />
              </div>
            )}

            {abilityMethod === 'pointbuy' && (
              <div>
                <p className="text-gray-400 text-sm mb-2">Points spent: <span className={pointBuyTotal > 27 ? 'text-red-400' : 'text-cyan-400'}>{pointBuyTotal}/27</span></p>
                <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
                  {ABILITIES.map(a => (
                    <div key={a} className="bg-gray-900 border border-gray-800 rounded-lg p-4 text-center">
                      <label className="text-gray-400 text-xs font-medium block mb-2">{ABILITY_LABELS[a]}</label>
                      <div className="flex items-center justify-center gap-3">
                        <button
                          onClick={() => setScores(s => ({ ...s, [a]: Math.max(8, s[a] - 1) }))}
                          className="w-8 h-8 rounded bg-gray-800 text-gray-400 hover:bg-gray-700 flex items-center justify-center"
                        >-</button>
                        <span className="text-white text-xl font-bold w-8 text-center">{scores[a]}</span>
                        <button
                          onClick={() => setScores(s => ({ ...s, [a]: Math.min(15, s[a] + 1) }))}
                          className="w-8 h-8 rounded bg-gray-800 text-gray-400 hover:bg-gray-700 flex items-center justify-center"
                        >+</button>
                      </div>
                      {racialBonuses[a] && (
                        <p className="text-green-400 text-xs mt-1">+{racialBonuses[a]} racial</p>
                      )}
                      <p className="text-gray-500 text-xs mt-1">
                        Final: {scores[a] + (racialBonuses[a] || 0)} ({formatMod(abilityMod(scores[a] + (racialBonuses[a] || 0)))})
                      </p>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {abilityMethod === 'manual' && (
              <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
                {ABILITIES.map(a => (
                  <div key={a} className="bg-gray-900 border border-gray-800 rounded-lg p-4 text-center">
                    <label className="text-gray-400 text-xs font-medium block mb-2">{ABILITY_LABELS[a]}</label>
                    <input
                      type="number"
                      min={1}
                      max={30}
                      value={scores[a]}
                      onChange={e => setScores(s => ({ ...s, [a]: Math.min(30, Math.max(1, parseInt(e.target.value) || 1)) }))}
                      className="w-full text-center text-xl font-bold bg-gray-800 border border-gray-700 rounded-lg py-2 text-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
                    />
                    {racialBonuses[a] && (
                      <p className="text-green-400 text-xs mt-1">+{racialBonuses[a]} racial</p>
                    )}
                    <p className="text-gray-500 text-xs mt-1">
                      Final: {scores[a] + (racialBonuses[a] || 0)} ({formatMod(abilityMod(scores[a] + (racialBonuses[a] || 0)))})
                    </p>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {/* Step 4: Background */}
        {step === 4 && (
          <div className="space-y-4">
            <h2 className="text-xl font-semibold text-white">Choose a Background</h2>
            <div className="relative max-w-md">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-500" />
              <input
                type="text"
                value={bgSearch}
                onChange={e => setBgSearch(e.target.value)}
                placeholder="Search backgrounds..."
                className="w-full pl-10 pr-4 py-2.5 bg-gray-800 border border-gray-700 rounded-lg text-white placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-indigo-500"
              />
            </div>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3 max-h-[60vh] overflow-y-auto pr-1">
              {filteredBackgrounds.map(bg => {
                const skills = safeJsonParse<(string | { choose: unknown })[]>(bg.skillProficiencies, []);
                const skillNames = skills.filter((s): s is string => typeof s === 'string');
                return (
                  <button
                    key={bg.id}
                    onClick={() => setSelectedBackground(selectedBackground?.id === bg.id ? null : bg)}
                    className={`p-4 rounded-lg border text-left transition-colors ${
                      selectedBackground?.id === bg.id
                        ? 'bg-indigo-900/30 border-indigo-500'
                        : 'bg-gray-900 border-gray-800 hover:border-gray-600'
                    }`}
                  >
                    <div className="flex items-start justify-between">
                      <h3 className="text-white font-medium text-sm">{bg.name}</h3>
                      <span className="text-gray-500 text-xs">{bg.source}</span>
                    </div>
                    {skillNames.length > 0 && (
                      <p className="text-cyan-400 text-xs mt-1">Skills: {skillNames.join(', ')}</p>
                    )}
                  </button>
                );
              })}
            </div>
            {selectedBackground && (
              <BackgroundDetail bg={selectedBackground} />
            )}
          </div>
        )}

        {/* Step 5: Review */}
        {step === 5 && (
          <div className="space-y-6">
            <h2 className="text-xl font-semibold text-white">Review Your Character</h2>

            <div className="bg-gray-900 border border-gray-800 rounded-lg p-6 space-y-4">
              <div className="grid grid-cols-2 gap-4">
                <ReviewField label="Name" value={name} />
                <ReviewField label="Alignment" value={alignment || 'None'} />
                <ReviewField label="Race" value={selectedRace?.name || '—'} />
                <ReviewField label="Class" value={selectedClass?.name || '—'} />
                {selectedSubclass && <ReviewField label="Subclass" value={selectedSubclass.name} />}
                <ReviewField label="Background" value={selectedBackground?.name || '—'} />
                <ReviewField label="Hit Points" value={String((selectedClass?.hitDice || 0) + abilityMod(finalScores.constitution))} />
                <ReviewField label="Speed" value={`${safeJsonParse<{ walk?: number }>(selectedRace?.speed ?? null, { walk: 30 }).walk ?? 30} ft`} />
              </div>

              <div className="border-t border-gray-800 pt-4">
                <h3 className="text-sm font-medium text-gray-400 mb-3">Ability Scores</h3>
                <div className="grid grid-cols-6 gap-3">
                  {ABILITIES.map(a => (
                    <div key={a} className="text-center">
                      <p className="text-gray-500 text-xs">{ABILITY_LABELS[a]}</p>
                      <p className="text-white text-lg font-bold">{finalScores[a]}</p>
                      <p className="text-gray-400 text-xs">{formatMod(abilityMod(finalScores[a]))}</p>
                    </div>
                  ))}
                </div>
              </div>
            </div>

            {error && <p className="text-red-400 text-sm">{error}</p>}
          </div>
        )}

        {/* Navigation buttons */}
        <div className="flex items-center justify-between mt-8 pt-6 border-t border-gray-800">
          <button
            onClick={() => step > 0 ? setStep(step - 1) : navigate('/player')}
            className="flex items-center gap-2 px-4 py-2.5 text-gray-400 hover:text-white text-sm transition-colors"
          >
            <ArrowLeft className="w-4 h-4" /> {step > 0 ? 'Previous' : 'Cancel'}
          </button>

          {step < STEPS.length - 1 ? (
            <button
              onClick={() => setStep(step + 1)}
              disabled={!canAdvance()}
              className="flex items-center gap-2 px-6 py-2.5 bg-indigo-600 hover:bg-indigo-500 disabled:bg-gray-700 disabled:text-gray-500 text-white rounded-lg text-sm transition-colors"
            >
              Next <ArrowRight className="w-4 h-4" />
            </button>
          ) : (
            <button
              onClick={handleCreate}
              disabled={submitting}
              className="flex items-center gap-2 px-6 py-2.5 bg-green-600 hover:bg-green-500 disabled:bg-gray-700 disabled:text-gray-500 text-white rounded-lg text-sm font-medium transition-colors"
            >
              {submitting ? 'Creating...' : 'Create Character'} <Check className="w-4 h-4" />
            </button>
          )}
        </div>
      </main>
    </div>
  );
}

function StandardArrayAssigner({
  assignments,
  onChange,
  racialBonuses,
}: {
  assignments: Record<string, number | null>;
  onChange: (a: Record<string, number | null>) => void;
  racialBonuses: Record<string, number>;
}) {
  const usedValues = Object.values(assignments).filter((v): v is number => v !== null);
  const available = STANDARD_ARRAY.filter(v => {
    const usedCount = usedValues.filter(u => u === v).length;
    const totalCount = STANDARD_ARRAY.filter(a => a === v).length;
    return usedCount < totalCount;
  });

  return (
    <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
      {ABILITIES.map(a => (
        <div key={a} className="bg-gray-900 border border-gray-800 rounded-lg p-4 text-center">
          <label className="text-gray-400 text-xs font-medium block mb-2">{ABILITY_LABELS[a]}</label>
          <select
            value={assignments[a] ?? ''}
            onChange={e => {
              const val = e.target.value ? parseInt(e.target.value) : null;
              onChange({ ...assignments, [a]: val });
            }}
            className="w-full text-center text-lg font-bold bg-gray-800 border border-gray-700 rounded-lg py-2 text-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
          >
            <option value="">—</option>
            {STANDARD_ARRAY.map(v => {
              const isCurrentValue = assignments[a] === v;
              const isAvailable = available.includes(v) || isCurrentValue;
              return (
                <option key={`${a}-${v}`} value={v} disabled={!isAvailable}>{v}</option>
              );
            })}
          </select>
          {racialBonuses[a] && (
            <p className="text-green-400 text-xs mt-1">+{racialBonuses[a]} racial</p>
          )}
          {assignments[a] !== null && (
            <p className="text-gray-500 text-xs mt-1">
              Final: {(assignments[a] ?? 0) + (racialBonuses[a] || 0)} ({formatMod(abilityMod((assignments[a] ?? 0) + (racialBonuses[a] || 0)))})
            </p>
          )}
        </div>
      ))}
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
    <div className="bg-gray-900 border border-gray-800 rounded-lg p-4 mt-4">
      <h3 className="text-white font-medium mb-2">{race.name} — Traits</h3>
      {profItems.length > 0 && (
        <p className="text-cyan-400 text-xs mb-2">{profItems.join(' | ')}</p>
      )}
      <div className="space-y-2">
        {traits.slice(0, 8).map((t, i) => (
          <div key={i}>
            <p className="text-gray-300 text-sm font-medium">{t.name}</p>
            <p className="text-gray-500 text-xs line-clamp-2">{t.description}</p>
          </div>
        ))}
      </div>
    </div>
  );
}

function BackgroundDetail({ bg }: { bg: Background }) {
  const feature = safeJsonParse<{ name: string; description: string } | null>(bg.feature, null);

  return (
    <div className="bg-gray-900 border border-gray-800 rounded-lg p-4 mt-4">
      <h3 className="text-white font-medium mb-2">{bg.name}</h3>
      {feature && (
        <div>
          <p className="text-gray-300 text-sm font-medium">Feature: {feature.name}</p>
          <p className="text-gray-500 text-xs mt-1 line-clamp-4">{feature.description}</p>
        </div>
      )}
      {bg.description && !feature && (
        <p className="text-gray-500 text-xs line-clamp-4">{bg.description}</p>
      )}
    </div>
  );
}

function ReviewField({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <p className="text-gray-500 text-xs">{label}</p>
      <p className="text-white text-sm font-medium">{value}</p>
    </div>
  );
}
