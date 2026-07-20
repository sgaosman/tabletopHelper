import { useState, useMemo } from 'react';
import { X, Zap, Shield, Target, Sparkles, AlertTriangle } from 'lucide-react';
import { combatApi } from '../../api/combatApi';
import type { EncounterParticipant, SpellSlots } from '../../types/encounter';
import type { CastSpellResponse } from '../../types/combat';

interface SpellEntry {
  name: string;
  level: number;
  source?: string;
  prepared?: boolean;
  alwaysPrepared?: boolean;
  atWill?: boolean;
}

interface Props {
  encounterId: string;
  caster: EncounterParticipant;
  participants: EncounterParticipant[];
  onUpdate: (encounterState: any) => void;
  onClose: () => void;
  isMonster?: boolean;
}

type Step = 'spell' | 'slot' | 'target' | 'confirm';

export default function SpellCastModal({ encounterId, caster, participants, onUpdate, onClose, isMonster }: Props) {
  const [step, setStep] = useState<Step>('spell');
  const [search, setSearch] = useState('');
  const [selectedSpell, setSelectedSpell] = useState<SpellEntry | null>(null);
  const [selectedSlotLevel, setSelectedSlotLevel] = useState<number>(0);
  const [usePactSlot, setUsePactSlot] = useState(false);
  const [selectedTargets, setSelectedTargets] = useState<string[]>([]);
  const [advantage, setAdvantage] = useState<boolean | null>(null);
  const [casting, setCasting] = useState(false);
  const [result, setResult] = useState<CastSpellResponse | null>(null);
  const [error, setError] = useState('');

  const [overrideAttackBonus, setOverrideAttackBonus] = useState<string>('');
  const [overrideSaveDC, setOverrideSaveDC] = useState<string>('');

  const spells: SpellEntry[] = useMemo(() => {
    if (!caster.spellsKnown) return [];
    try {
      const parsed = JSON.parse(caster.spellsKnown);
      return Array.isArray(parsed) ? parsed : [];
    } catch { return []; }
  }, [caster.spellsKnown]);

  const slots: SpellSlots = useMemo(() => {
    if (!caster.spellSlotsCurrent) return {};
    try { return JSON.parse(caster.spellSlotsCurrent); }
    catch { return {}; }
  }, [caster.spellSlotsCurrent]);

  const filteredSpells = useMemo(() => {
    const q = search.toLowerCase();
    const filtered = spells.filter(s =>
      (s.prepared || s.alwaysPrepared || s.atWill || s.level === 0) &&
      s.name.toLowerCase().includes(q)
    );
    return filtered.sort((a, b) => a.level - b.level || a.name.localeCompare(b.name));
  }, [spells, search]);

  const cantrips = filteredSpells.filter(s => s.level === 0);
  const leveled = filteredSpells.filter(s => s.level > 0);

  const availableSlotLevels = useMemo(() => {
    if (!selectedSpell || selectedSpell.level === 0) return [];
    const levels: { level: number; remaining: number; max: number; isPact: boolean }[] = [];

    for (const [key, val] of Object.entries(slots)) {
      const isPact = key.startsWith('pact_');
      const lvl = isPact ? parseInt(key.replace('pact_', '')) : parseInt(key);
      if (isNaN(lvl) || lvl < selectedSpell.level) continue;
      levels.push({ level: lvl, remaining: val.remaining, max: val.max, isPact });
    }

    return levels.sort((a, b) => {
      if (a.isPact !== b.isPact) return a.isPact ? 1 : -1;
      return a.level - b.level;
    });
  }, [selectedSpell, slots]);

  function selectSpell(spell: SpellEntry) {
    setSelectedSpell(spell);
    setError('');

    if (spell.level === 0) {
      setSelectedSlotLevel(0);
      setStep('target');
    } else {
      setSelectedSlotLevel(spell.level);
      setStep('slot');
    }
  }

  function selectSlot(level: number, isPact: boolean) {
    setSelectedSlotLevel(level);
    setUsePactSlot(isPact);
    setStep('target');
  }

  function toggleTarget(id: string) {
    setSelectedTargets(prev =>
      prev.includes(id) ? prev.filter(t => t !== id) : [...prev, id]
    );
  }

  function proceedToConfirm() {
    setStep('confirm');
  }

  async function cast() {
    if (!selectedSpell) return;
    setCasting(true);
    setError('');

    try {
      const res = await combatApi.castSpell(encounterId, {
        spellName: selectedSpell.name,
        slotLevel: selectedSlotLevel,
        targetIds: selectedTargets,
        advantage,
        usePactSlot: usePactSlot || undefined,
        overrideSpellAttackBonus: overrideAttackBonus ? parseInt(overrideAttackBonus) : undefined,
        overrideSpellSaveDC: overrideSaveDC ? parseInt(overrideSaveDC) : undefined,
      }, caster.id);

      setResult(res.data);
      onUpdate(res.data.encounterState);
    } catch (err: any) {
      setError(err.response?.data?.error || err.response?.data?.message || 'Failed to cast spell');
      setCasting(false);
    }
  }

  function resetAndClose() {
    onClose();
  }

  const levelLabel = (lvl: number) => lvl === 0 ? 'Cantrip' : `Level ${lvl}`;

  const otherParticipants = participants.filter(p => p.id !== caster.id && p.isAlive);

  return (
    <div className="fixed inset-0 bg-black/60 z-50 flex items-center justify-center p-4" onClick={onClose}>
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="spell-cast-title"
        className="bg-gray-900 border border-gray-800 rounded-xl w-full max-w-lg max-h-[90vh] flex flex-col"
        onClick={e => e.stopPropagation()}
      >
        <div className="px-5 py-4 border-b border-gray-800 flex items-center justify-between shrink-0">
          <div>
            <h2 id="spell-cast-title" className="text-white font-bold text-lg flex items-center gap-2">
              <Sparkles className="w-5 h-5 text-indigo-400" />
              Cast Spell
            </h2>
            <p className="text-gray-400 text-xs mt-0.5">{caster.displayName}</p>
          </div>
          <button onClick={onClose} aria-label="Close" className="text-gray-400 hover:text-white">
            <X className="w-5 h-5" />
          </button>
        </div>

        <div className="px-5 py-4 space-y-3 overflow-y-auto flex-1 min-h-0">
          {error && (
            <div role="alert" className="bg-red-900/50 border border-red-700 text-red-300 rounded-lg p-3 text-sm">{error}</div>
          )}

          {result ? (
            <ResultView result={result} onClose={resetAndClose} />
          ) : step === 'spell' ? (
            <SpellSelectionStep
              cantrips={cantrips}
              leveled={leveled}
              search={search}
              onSearch={setSearch}
              onSelect={selectSpell}
              isMonster={isMonster}
            />
          ) : step === 'slot' ? (
            <SlotSelectionStep
              spell={selectedSpell!}
              levels={availableSlotLevels}
              onSelect={selectSlot}
              onBack={() => setStep('spell')}
            />
          ) : step === 'target' ? (
            <TargetSelectionStep
              spell={selectedSpell!}
              targets={otherParticipants}
              caster={caster}
              selectedTargets={selectedTargets}
              onToggle={toggleTarget}
              onBack={() => selectedSpell!.level === 0 ? setStep('spell') : setStep('slot')}
              onProceed={proceedToConfirm}
            />
          ) : (
            <ConfirmStep
              spell={selectedSpell!}
              slotLevel={selectedSlotLevel}
              usePactSlot={usePactSlot}
              targets={participants.filter(p => selectedTargets.includes(p.id))}
              caster={caster}
              advantage={advantage}
              onAdvantageChange={setAdvantage}
              casting={casting}
              onCast={cast}
              onBack={() => setStep('target')}
              isMonster={isMonster}
              overrideAttackBonus={overrideAttackBonus}
              overrideSaveDC={overrideSaveDC}
              onOverrideAttackBonus={setOverrideAttackBonus}
              onOverrideSaveDC={setOverrideSaveDC}
            />
          )}
        </div>
      </div>
    </div>
  );
}

function SpellSelectionStep({ cantrips, leveled, search, onSearch, onSelect, isMonster }: {
  cantrips: SpellEntry[];
  leveled: SpellEntry[];
  search: string;
  onSearch: (s: string) => void;
  onSelect: (spell: SpellEntry) => void;
  isMonster?: boolean;
}) {
  return (
    <>
      <input
        type="text"
        value={search}
        onChange={e => onSearch(e.target.value)}
        placeholder="Search spells..."
        autoFocus
        className="w-full px-4 py-2 bg-gray-800 rounded-lg border border-gray-700 text-white text-sm placeholder-gray-500 focus:outline-none focus:ring-1 focus:ring-indigo-500"
      />

      {cantrips.length === 0 && leveled.length === 0 && (
        <p className="text-gray-500 text-sm text-center py-4">
          {isMonster ? 'Enter a spell name to cast' : 'No prepared spells found'}
        </p>
      )}

      {cantrips.length > 0 && (
        <div>
          <p className="text-gray-400 text-xs font-medium mb-1.5 uppercase tracking-wider">Cantrips</p>
          <div className="space-y-1">
            {cantrips.map(s => (
              <SpellRow key={s.name} spell={s} onClick={() => onSelect(s)} />
            ))}
          </div>
        </div>
      )}

      {leveled.length > 0 && (
        <div>
          <p className="text-gray-400 text-xs font-medium mb-1.5 uppercase tracking-wider">Spells</p>
          <div className="space-y-1">
            {leveled.map(s => (
              <SpellRow key={s.name + s.level} spell={s} onClick={() => onSelect(s)} />
            ))}
          </div>
        </div>
      )}
    </>
  );
}

function SpellRow({ spell, onClick }: { spell: SpellEntry; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      className="w-full flex items-center justify-between px-3 py-2 bg-gray-800 hover:bg-gray-750 rounded-lg text-left transition-colors group"
    >
      <div className="flex items-center gap-2">
        <Zap className="w-3.5 h-3.5 text-indigo-400 shrink-0" />
        <span className="text-white text-sm font-medium">{spell.name}</span>
      </div>
      <div className="flex items-center gap-2">
        {spell.alwaysPrepared && <span className="text-[10px] text-amber-400">always</span>}
        {spell.atWill && <span className="text-[10px] text-emerald-400">at will</span>}
        <span className="text-gray-500 text-xs">
          {spell.level === 0 ? 'Cantrip' : `Lvl ${spell.level}`}
        </span>
      </div>
    </button>
  );
}

function SlotSelectionStep({ spell, levels, onSelect, onBack }: {
  spell: SpellEntry;
  levels: { level: number; remaining: number; max: number; isPact: boolean }[];
  onSelect: (level: number, isPact: boolean) => void;
  onBack: () => void;
}) {
  return (
    <>
      <div className="flex items-center gap-2 mb-2">
        <button onClick={onBack} className="text-gray-400 hover:text-white text-sm">&larr; Back</button>
        <span className="text-white font-medium">{spell.name}</span>
        <span className="text-gray-500 text-xs">— Select slot level</span>
      </div>

      {levels.length === 0 && (
        <p className="text-red-400 text-sm text-center py-4">No spell slots available at level {spell.level} or above</p>
      )}

      <div className="space-y-1.5">
        {levels.map(l => (
          <button
            key={l.isPact ? `pact_${l.level}` : String(l.level)}
            onClick={() => l.remaining > 0 && onSelect(l.level, l.isPact)}
            disabled={l.remaining <= 0}
            className={`w-full flex items-center justify-between px-4 py-3 rounded-lg text-left transition-colors
              ${l.remaining > 0
                ? 'bg-gray-800 hover:bg-gray-750 border border-gray-700'
                : 'bg-gray-800/50 opacity-50 cursor-not-allowed border border-gray-800'
              }
              ${l.level === spell.level && !l.isPact ? 'ring-1 ring-indigo-500/50' : ''}
            `}
          >
            <div>
              <span className="text-white text-sm font-medium">
                Level {l.level}{l.isPact ? ' (Pact)' : ''}
              </span>
              {l.level > spell.level && (
                <span className="text-indigo-400 text-xs ml-2">Upcast +{l.level - spell.level}</span>
              )}
            </div>
            <div className="flex items-center gap-1.5">
              {Array.from({ length: l.max }, (_, i) => (
                <div key={i} className={`w-2 h-2 rounded-full ${i < l.remaining ? 'bg-indigo-400' : 'bg-gray-600'}`} />
              ))}
              <span className="text-gray-500 text-xs ml-1">{l.remaining}/{l.max}</span>
            </div>
          </button>
        ))}
      </div>
    </>
  );
}

function TargetSelectionStep({ spell, targets, caster, selectedTargets, onToggle, onBack, onProceed }: {
  spell: SpellEntry;
  targets: EncounterParticipant[];
  caster: EncounterParticipant;
  selectedTargets: string[];
  onToggle: (id: string) => void;
  onBack: () => void;
  onProceed: () => void;
}) {
  const allTargetable = [caster, ...targets];

  return (
    <>
      <div className="flex items-center gap-2 mb-2">
        <button onClick={onBack} className="text-gray-400 hover:text-white text-sm">&larr; Back</button>
        <span className="text-white font-medium">{spell.name}</span>
        <span className="text-gray-500 text-xs">— Select target{selectedTargets.length > 0 ? `s (${selectedTargets.length})` : '(s)'}</span>
      </div>

      <div className="space-y-1">
        {allTargetable.map(p => {
          const isSelected = selectedTargets.includes(p.id);
          const isSelf = p.id === caster.id;
          return (
            <button
              key={p.id}
              onClick={() => onToggle(p.id)}
              className={`w-full flex items-center justify-between px-3 py-2 rounded-lg text-left transition-colors
                ${isSelected ? 'bg-indigo-900/40 border border-indigo-500/50' : 'bg-gray-800 border border-gray-700 hover:bg-gray-750'}
              `}
            >
              <div className="flex items-center gap-2">
                <Target className={`w-3.5 h-3.5 ${isSelected ? 'text-indigo-400' : 'text-gray-500'}`} />
                <span className={isSelected ? 'text-white' : 'text-gray-300'}>{p.displayName}</span>
                {isSelf && <span className="text-gray-500 text-[10px]">(self)</span>}
              </div>
              <div className="flex items-center gap-2 text-xs text-gray-500">
                <span>AC {p.armourClass}</span>
                <span>{p.hpCurrent}/{p.hpMax} HP</span>
              </div>
            </button>
          );
        })}
      </div>

      <button
        onClick={onProceed}
        disabled={selectedTargets.length === 0}
        className="w-full mt-2 px-4 py-2.5 bg-indigo-600 hover:bg-indigo-500 text-white text-sm font-medium rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
      >
        Continue
      </button>
    </>
  );
}

function ConfirmStep({ spell, slotLevel, usePactSlot, targets, caster, advantage, onAdvantageChange, casting, onCast, onBack, isMonster, overrideAttackBonus, overrideSaveDC, onOverrideAttackBonus, onOverrideSaveDC }: {
  spell: SpellEntry;
  slotLevel: number;
  usePactSlot: boolean;
  targets: EncounterParticipant[];
  caster: EncounterParticipant;
  advantage: boolean | null;
  onAdvantageChange: (v: boolean | null) => void;
  casting: boolean;
  onCast: () => void;
  onBack: () => void;
  isMonster?: boolean;
  overrideAttackBonus: string;
  overrideSaveDC: string;
  onOverrideAttackBonus: (v: string) => void;
  onOverrideSaveDC: (v: string) => void;
}) {
  const hasConcentration = !!caster.concentrationSpell;

  return (
    <>
      <div className="flex items-center gap-2 mb-3">
        <button onClick={onBack} className="text-gray-400 hover:text-white text-sm">&larr; Back</button>
        <span className="text-white font-medium">Confirm</span>
      </div>

      <div className="bg-gray-800 rounded-lg p-4 space-y-2">
        <div className="flex items-center gap-2">
          <Sparkles className="w-4 h-4 text-indigo-400" />
          <span className="text-white font-bold">{spell.name}</span>
          <span className="text-gray-500 text-xs">{spell.level === 0 ? 'Cantrip' : `Level ${spell.level}`}</span>
        </div>

        {slotLevel > 0 && (
          <p className="text-gray-400 text-sm">
            Using level {slotLevel}{usePactSlot ? ' pact' : ''} slot
            {slotLevel > spell.level && <span className="text-indigo-400 ml-1">(upcast +{slotLevel - spell.level})</span>}
          </p>
        )}

        <p className="text-gray-400 text-sm">
          <Shield className="w-3.5 h-3.5 inline mr-1" />
          Targeting: {targets.map(t => t.displayName).join(', ') || 'None'}
        </p>

        {hasConcentration && (
          <div className="flex items-start gap-2 bg-amber-900/20 border border-amber-700/30 rounded p-2">
            <AlertTriangle className="w-4 h-4 text-amber-400 shrink-0 mt-0.5" />
            <p className="text-amber-300 text-xs">
              Will drop concentration on <strong>{caster.concentrationSpell}</strong>
            </p>
          </div>
        )}
      </div>

      <div className="flex gap-2">
        {(['normal', 'advantage', 'disadvantage'] as const).map(mode => {
          const val = mode === 'normal' ? null : mode === 'advantage';
          return (
            <button
              key={mode}
              onClick={() => onAdvantageChange(val)}
              className={`flex-1 py-1.5 text-xs font-medium rounded-lg transition-colors ${
                advantage === val
                  ? 'bg-indigo-600 text-white'
                  : 'bg-gray-800 text-gray-400 hover:text-white'
              }`}
            >
              {mode.charAt(0).toUpperCase() + mode.slice(1)}
            </button>
          );
        })}
      </div>

      {isMonster && (
        <div className="grid grid-cols-2 gap-2">
          <div>
            <label className="text-gray-400 text-xs">Spell Attack Bonus</label>
            <input
              type="number"
              value={overrideAttackBonus}
              onChange={e => onOverrideAttackBonus(e.target.value)}
              placeholder={String(caster.spellAttackBonus ?? 0)}
              className="w-full mt-1 px-3 py-1.5 bg-gray-800 rounded border border-gray-700 text-white text-sm focus:outline-none focus:ring-1 focus:ring-indigo-500"
            />
          </div>
          <div>
            <label className="text-gray-400 text-xs">Spell Save DC</label>
            <input
              type="number"
              value={overrideSaveDC}
              onChange={e => onOverrideSaveDC(e.target.value)}
              placeholder={String(caster.spellSaveDc ?? 10)}
              className="w-full mt-1 px-3 py-1.5 bg-gray-800 rounded border border-gray-700 text-white text-sm focus:outline-none focus:ring-1 focus:ring-indigo-500"
            />
          </div>
        </div>
      )}

      <button
        onClick={onCast}
        disabled={casting}
        className="w-full px-4 py-2.5 bg-indigo-600 hover:bg-indigo-500 text-white text-sm font-bold rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2"
      >
        <Sparkles className="w-4 h-4" />
        {casting ? 'Casting...' : 'Cast Spell'}
      </button>
    </>
  );
}

function ResultView({ result, onClose }: { result: CastSpellResponse; onClose: () => void }) {
  return (
    <div className="space-y-3">
      <div className="bg-indigo-900/20 border border-indigo-700/30 rounded-lg p-4">
        <p className="text-white font-bold mb-1 flex items-center gap-2">
          <Sparkles className="w-4 h-4 text-indigo-400" />
          {result.spellName}
          {result.slotLevelUsed > 0 && (
            <span className="text-gray-400 text-xs font-normal">at level {result.slotLevelUsed}</span>
          )}
        </p>
        <p className="text-gray-300 text-sm">{result.resultSummary}</p>
      </div>

      {!result.autoResolved && result.manualResolutionReason && (
        <div className="bg-amber-900/20 border border-amber-700/30 rounded-lg p-3">
          <p className="text-amber-300 text-sm flex items-center gap-2">
            <AlertTriangle className="w-4 h-4" />
            Requires DM adjudication: {result.manualResolutionReason}
          </p>
        </div>
      )}

      {result.targets.length > 0 && result.autoResolved && (
        <div className="space-y-1.5">
          {result.targets.map(t => (
            <div key={t.targetId} className="bg-gray-800 rounded-lg px-3 py-2 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span className={`w-2 h-2 rounded-full ${
                  t.outcome === 'miss' || t.outcome === 'saved' ? 'bg-gray-500' :
                  t.outcome === 'critical' ? 'bg-red-400' : 'bg-green-400'
                }`} />
                <span className="text-white text-sm">{t.targetName}</span>
              </div>
              <div className="flex items-center gap-3 text-xs">
                {t.attackRoll != null && (
                  <span className={t.outcome === 'miss' ? 'text-gray-500' : 'text-green-400'}>
                    Roll: {t.attackRoll}
                  </span>
                )}
                {t.saveRoll != null && (
                  <span className={t.outcome === 'saved' ? 'text-green-400' : 'text-red-400'}>
                    Save: {t.saveRoll}
                  </span>
                )}
                {t.damage != null && t.damage > 0 && (
                  <span className="text-red-400">{t.damage} dmg</span>
                )}
                {t.healing != null && t.healing > 0 && (
                  <span className="text-green-400">{t.healing} heal</span>
                )}
                {t.conditionsApplied.length > 0 && (
                  <span className="text-amber-400">{t.conditionsApplied.join(', ')}</span>
                )}
              </div>
            </div>
          ))}
        </div>
      )}

      <button
        onClick={onClose}
        className="w-full px-4 py-2 bg-gray-800 hover:bg-gray-700 text-white text-sm font-medium rounded-lg transition-colors"
      >
        Done
      </button>
    </div>
  );
}
