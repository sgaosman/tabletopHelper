import { useState, useEffect, useCallback, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Save, Shield, Swords, BookOpen, Backpack, Star, ScrollText, Heart, Moon, Sun } from 'lucide-react';
import { characterApi } from '../../api/characterApi';
import type { PlayerCharacter, CharacterUpdateRequest } from '../../types/character';

const ABILITIES = ['strength', 'dexterity', 'constitution', 'intelligence', 'wisdom', 'charisma'] as const;
const ABILITY_ABBR: Record<string, string> = {
  strength: 'STR', dexterity: 'DEX', constitution: 'CON',
  intelligence: 'INT', wisdom: 'WIS', charisma: 'CHA',
};

const SKILLS: Array<{ name: string; ability: typeof ABILITIES[number] }> = [
  { name: 'Acrobatics', ability: 'dexterity' }, { name: 'Animal Handling', ability: 'wisdom' },
  { name: 'Arcana', ability: 'intelligence' }, { name: 'Athletics', ability: 'strength' },
  { name: 'Deception', ability: 'charisma' }, { name: 'History', ability: 'intelligence' },
  { name: 'Insight', ability: 'wisdom' }, { name: 'Intimidation', ability: 'charisma' },
  { name: 'Investigation', ability: 'intelligence' }, { name: 'Medicine', ability: 'wisdom' },
  { name: 'Nature', ability: 'intelligence' }, { name: 'Perception', ability: 'wisdom' },
  { name: 'Performance', ability: 'charisma' }, { name: 'Persuasion', ability: 'charisma' },
  { name: 'Religion', ability: 'intelligence' }, { name: 'Sleight of Hand', ability: 'dexterity' },
  { name: 'Stealth', ability: 'dexterity' }, { name: 'Survival', ability: 'wisdom' },
];

type Tab = 'Stats' | 'Actions' | 'Spells' | 'Inventory' | 'Features' | 'Journal';
const TABS: { key: Tab; label: string; icon: typeof Shield }[] = [
  { key: 'Stats', label: 'Stats', icon: Shield },
  { key: 'Actions', label: 'Actions', icon: Swords },
  { key: 'Spells', label: 'Spells', icon: BookOpen },
  { key: 'Inventory', label: 'Inventory', icon: Backpack },
  { key: 'Features', label: 'Features', icon: Star },
  { key: 'Journal', label: 'Journal', icon: ScrollText },
];

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

export default function CharacterSheetPage() {
  const { characterId } = useParams<{ characterId: string }>();
  const navigate = useNavigate();
  const [char, setChar] = useState<PlayerCharacter | null>(null);
  const [activeTab, setActiveTab] = useState<Tab>('Stats');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [restModal, setRestModal] = useState<'short' | 'long' | null>(null);

  useEffect(() => {
    if (characterId && characterId !== 'new') {
      characterApi.getById(characterId).then(res => setChar(res.data));
    }
  }, [characterId]);

  const saveField = useCallback(async (updates: CharacterUpdateRequest) => {
    if (!characterId) return;
    setSaving(true);
    setError('');
    try {
      const res = await characterApi.update(characterId, updates);
      setChar(res.data);
      setSuccess('Saved');
      setTimeout(() => setSuccess(''), 2000);
    } catch (err: any) {
      setError(err.response?.data?.error || 'Save failed');
    } finally {
      setSaving(false);
    }
  }, [characterId]);

  const savingThrows = useMemo(() => safeJsonParse<string[]>(char?.savingThrowProficiencies, []), [char?.savingThrowProficiencies]);
  const skillProfs = useMemo(() => safeJsonParse<string[]>(char?.skillProficiencies, []), [char?.skillProficiencies]);
  const skillExpertises = useMemo(() => safeJsonParse<string[]>(char?.skillExpertises, []), [char?.skillExpertises]);
  const features = useMemo(() => safeJsonParse<Array<{ name: string; description: string; source?: string }>>(char?.features, []), [char?.features]);
  const equipment = useMemo(() => safeJsonParse<Array<{ name: string; quantity?: number; description?: string }>>(char?.equipment, []), [char?.equipment]);
  const currency = useMemo(() => safeJsonParse<{ cp: number; sp: number; ep: number; gp: number; pp: number }>(char?.currency, { cp: 0, sp: 0, ep: 0, gp: 0, pp: 0 }), [char?.currency]);
  const spellsKnown = useMemo(() => safeJsonParse<Array<{ name: string; level: number; prepared?: boolean }>>(char?.spellsKnown, []), [char?.spellsKnown]);
  const spellSlots = useMemo(() => safeJsonParse<Record<string, { total: number; used: number }>>(char?.spellSlots, {}), [char?.spellSlots]);
  const resistances = useMemo(() => safeJsonParse<string[]>(char?.damageResistances, []), [char?.damageResistances]);
  const hitDiceMap = useMemo(() => safeJsonParse<Record<string, { total: number; remaining: number; faces: number }>>(char?.hitDiceMap, {}), [char?.hitDiceMap]);

  if (!char) {
    return (
      <div className="min-h-screen bg-gray-950 flex items-center justify-center">
        <p className="text-gray-400">Loading character...</p>
      </div>
    );
  }

  async function handleShortRest() {
    if (!char) return;
    const hdEntries = Object.entries(hitDiceMap);
    if (hdEntries.length === 0) { setRestModal(null); return; }

    const updated = { ...hitDiceMap };
    let hpGain = 0;
    for (const [cls, hd] of hdEntries) {
      if (hd.remaining > 0) {
        const roll = Math.ceil(hd.faces / 2) + 1;
        hpGain += roll + abilityMod(char.constitution);
        updated[cls] = { ...hd, remaining: hd.remaining - 1 };
      }
    }
    const newHp = Math.min(char.hpMax, char.hpCurrent + hpGain);
    await saveField({ hpCurrent: newHp, hitDiceMap: JSON.stringify(updated) });
    setRestModal(null);
  }

  async function handleLongRest() {
    if (!char) return;
    const updated = { ...hitDiceMap };
    for (const [cls, hd] of Object.entries(updated)) {
      const regain = Math.max(1, Math.floor(hd.total / 2));
      updated[cls] = { ...hd, remaining: Math.min(hd.total, hd.remaining + regain) };
    }
    const resetSlots: Record<string, { total: number; used: number }> = {};
    for (const [lvl, slot] of Object.entries(spellSlots)) {
      resetSlots[lvl] = { ...slot, used: 0 };
    }
    await saveField({
      hpCurrent: char.hpMax,
      hpTemp: 0,
      hitDiceMap: JSON.stringify(updated),
      spellSlots: Object.keys(resetSlots).length > 0 ? JSON.stringify(resetSlots) : undefined,
    });
    setRestModal(null);
  }

  return (
    <div className="min-h-screen bg-gray-950">
      {/* Header */}
      <header className="sticky top-0 z-10 bg-gray-950 border-b border-gray-800 px-4 py-3">
        <div className="max-w-5xl mx-auto flex items-center justify-between">
          <button onClick={() => navigate('/player')} className="flex items-center gap-1 text-gray-400 hover:text-white text-sm transition-colors">
            <ArrowLeft className="w-4 h-4" />
          </button>
          <div className="text-center">
            <h1 className="text-white font-bold">{char.name}</h1>
            <p className="text-gray-400 text-xs">
              Level {char.level} {char.race} {char.characterClass}
              {char.subclass ? ` (${char.subclass})` : ''}
            </p>
          </div>
          <div className="flex items-center gap-2">
            <button onClick={() => setRestModal('short')} className="p-2 text-yellow-400 hover:bg-gray-800 rounded-lg transition-colors" title="Short Rest">
              <Moon className="w-4 h-4" />
            </button>
            <button onClick={() => setRestModal('long')} className="p-2 text-blue-400 hover:bg-gray-800 rounded-lg transition-colors" title="Long Rest">
              <Sun className="w-4 h-4" />
            </button>
          </div>
        </div>
      </header>

      {/* HP Bar */}
      <div className="max-w-5xl mx-auto px-4 py-3">
        <div className="flex items-center gap-4">
          <div className="flex items-center gap-2">
            <Heart className="w-4 h-4 text-red-400" />
            <span className="text-white font-bold">{char.hpCurrent}/{char.hpMax}</span>
            {char.hpTemp > 0 && <span className="text-cyan-400 text-sm">+{char.hpTemp} temp</span>}
          </div>
          <div className="flex-1 h-2 bg-gray-800 rounded-full overflow-hidden">
            <div
              className={`h-full rounded-full transition-all ${
                char.hpCurrent / char.hpMax > 0.5 ? 'bg-green-500' :
                char.hpCurrent / char.hpMax > 0.25 ? 'bg-yellow-500' : 'bg-red-500'
              }`}
              style={{ width: `${Math.min(100, (char.hpCurrent / char.hpMax) * 100)}%` }}
            />
          </div>
          <div className="flex gap-3 text-sm">
            <span className="text-gray-400">AC <span className="text-white font-medium">{char.armourClass}</span></span>
            <span className="text-gray-400">Init <span className="text-white font-medium">{formatMod(char.initiativeBonus)}</span></span>
            <span className="text-gray-400">Speed <span className="text-white font-medium">{char.speed}ft</span></span>
          </div>
        </div>
      </div>

      {/* Tabs */}
      <div className="max-w-5xl mx-auto px-4">
        <div className="flex gap-1 border-b border-gray-800 overflow-x-auto">
          {TABS.map(({ key, label, icon: Icon }) => (
            <button
              key={key}
              onClick={() => setActiveTab(key)}
              className={`flex items-center gap-1.5 px-3 py-2 text-sm font-medium transition-colors whitespace-nowrap ${
                activeTab === key ? 'text-indigo-400 border-b-2 border-indigo-400' : 'text-gray-500 hover:text-gray-300'
              }`}
            >
              <Icon className="w-3.5 h-3.5" /> {label}
            </button>
          ))}
        </div>
      </div>

      {/* Error / Success */}
      <div className="max-w-5xl mx-auto px-4">
        {error && <div className="bg-red-900/50 border border-red-700 text-red-300 rounded-lg p-3 mt-4 text-sm">{error}</div>}
        {success && <div className="bg-green-900/50 border border-green-700 text-green-300 rounded-lg p-3 mt-4 text-sm">{success}</div>}
      </div>

      {/* Tab Content */}
      <main className="max-w-5xl mx-auto px-4 py-6">
        {activeTab === 'Stats' && (
          <StatsTab char={char} savingThrows={savingThrows} skillProfs={skillProfs} skillExpertises={skillExpertises} resistances={resistances} />
        )}
        {activeTab === 'Actions' && (
          <ActionsTab char={char} features={features} />
        )}
        {activeTab === 'Spells' && (
          <SpellsTab char={char} spellsKnown={spellsKnown} spellSlots={spellSlots} saveField={saveField} />
        )}
        {activeTab === 'Inventory' && (
          <InventoryTab equipment={equipment} currency={currency} char={char} saveField={saveField} />
        )}
        {activeTab === 'Features' && (
          <FeaturesTab features={features} char={char} />
        )}
        {activeTab === 'Journal' && (
          <JournalTab char={char} saveField={saveField} />
        )}
      </main>

      {/* Rest Modal */}
      {restModal && (
        <div className="fixed inset-0 bg-black/60 z-50 flex items-center justify-center p-4" onClick={() => setRestModal(null)}>
          <div className="bg-gray-900 border border-gray-800 rounded-xl p-6 max-w-sm w-full" onClick={e => e.stopPropagation()}>
            <h3 className="text-white font-bold text-lg mb-2">
              {restModal === 'short' ? 'Short Rest' : 'Long Rest'}
            </h3>
            <p className="text-gray-400 text-sm mb-4">
              {restModal === 'short'
                ? 'Spend hit dice to regain hit points. You can spend one hit die per short rest.'
                : 'Regain all hit points, reset spell slots, and regain half your total hit dice (minimum 1).'}
            </p>
            <div className="flex gap-3">
              <button onClick={() => setRestModal(null)} className="flex-1 px-4 py-2 bg-gray-800 text-gray-300 rounded-lg text-sm hover:bg-gray-700 transition-colors">Cancel</button>
              <button
                onClick={restModal === 'short' ? handleShortRest : handleLongRest}
                className="flex-1 px-4 py-2 bg-indigo-600 text-white rounded-lg text-sm hover:bg-indigo-500 transition-colors"
              >
                {restModal === 'short' ? 'Short Rest' : 'Long Rest'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function StatsTab({ char, savingThrows, skillProfs, skillExpertises, resistances }: {
  char: PlayerCharacter;
  savingThrows: string[];
  skillProfs: string[];
  skillExpertises: string[];
  resistances: string[];
}) {
  return (
    <div className="space-y-6">
      {/* Ability Scores */}
      <div className="grid grid-cols-6 gap-3">
        {ABILITIES.map(ability => {
          const score = char[ability];
          const mod = abilityMod(score);
          return (
            <div key={ability} className="bg-gray-900 border border-gray-800 rounded-xl p-3 text-center">
              <p className="text-gray-500 text-xs font-medium uppercase tracking-wider">{ABILITY_ABBR[ability]}</p>
              <p className="text-white text-2xl font-bold mt-1">{score}</p>
              <p className="text-indigo-400 text-sm font-medium">{formatMod(mod)}</p>
            </div>
          );
        })}
      </div>

      {/* Saving Throws */}
      <div className="bg-gray-900 border border-gray-800 rounded-lg p-4">
        <h3 className="text-gray-400 text-xs font-medium uppercase tracking-wider mb-3">Saving Throws</h3>
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
          {ABILITIES.map(ability => {
            const mod = abilityMod(char[ability]);
            const isProficient = savingThrows.some(s => s.toLowerCase() === ABILITY_ABBR[ability].toLowerCase() || s.toLowerCase() === ability);
            const total = mod + (isProficient ? char.proficiencyBonus : 0);
            return (
              <div key={ability} className="flex items-center gap-2">
                <div className={`w-2 h-2 rounded-full ${isProficient ? 'bg-green-400' : 'bg-gray-700'}`} />
                <span className="text-gray-400 text-sm">{ABILITY_ABBR[ability]}</span>
                <span className="text-white text-sm ml-auto font-medium">{formatMod(total)}</span>
              </div>
            );
          })}
        </div>
      </div>

      {/* Skills */}
      <div className="bg-gray-900 border border-gray-800 rounded-lg p-4">
        <h3 className="text-gray-400 text-xs font-medium uppercase tracking-wider mb-3">Skills</h3>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-1.5">
          {SKILLS.map(skill => {
            const mod = abilityMod(char[skill.ability]);
            const isProficient = skillProfs.some(s => s.toLowerCase() === skill.name.toLowerCase());
            const isExpert = skillExpertises.some(s => s.toLowerCase() === skill.name.toLowerCase());
            const bonus = mod + (isExpert ? char.proficiencyBonus * 2 : isProficient ? char.proficiencyBonus : 0);
            return (
              <div key={skill.name} className="flex items-center gap-2 py-0.5">
                <div className={`w-2 h-2 rounded-full ${isExpert ? 'bg-yellow-400' : isProficient ? 'bg-green-400' : 'bg-gray-700'}`} />
                <span className="text-gray-400 text-sm flex-1">{skill.name} <span className="text-gray-600 text-xs">({ABILITY_ABBR[skill.ability]})</span></span>
                <span className="text-white text-sm font-medium">{formatMod(bonus)}</span>
              </div>
            );
          })}
        </div>
      </div>

      {/* Proficiency & Other */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        <StatCard label="Proficiency" value={formatMod(char.proficiencyBonus)} />
        <StatCard label="Passive Perception" value={String(10 + abilityMod(char.wisdom) + (skillProfs.some(s => s.toLowerCase() === 'perception') ? char.proficiencyBonus : 0))} />
        <StatCard label="Hit Dice" value={char.hitDiceTotal || `${char.level}d?`} />
        <StatCard label="XP" value={String(char.experiencePoints)} />
      </div>

      {resistances.length > 0 && (
        <div className="bg-gray-900 border border-gray-800 rounded-lg p-4">
          <h3 className="text-gray-400 text-xs font-medium uppercase tracking-wider mb-2">Resistances & Immunities</h3>
          <div className="flex flex-wrap gap-2">
            {resistances.map((r, i) => (
              <span key={i} className="px-2 py-1 bg-gray-800 text-gray-300 rounded text-xs">{r}</span>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

function ActionsTab({ char, features }: {
  char: PlayerCharacter;
  features: Array<{ name: string; description: string; source?: string }>;
}) {
  const actionFeatures = features.filter(f =>
    f.description?.toLowerCase().includes('attack') ||
    f.description?.toLowerCase().includes('action') ||
    f.description?.toLowerCase().includes('damage') ||
    f.source?.toLowerCase().includes('class')
  );

  return (
    <div className="space-y-4">
      <div className="bg-gray-900 border border-gray-800 rounded-lg p-4">
        <h3 className="text-gray-400 text-xs font-medium uppercase tracking-wider mb-3">Combat Stats</h3>
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
          <StatCard label="Attack Bonus" value={formatMod(abilityMod(char.strength) + char.proficiencyBonus)} sub="STR melee" />
          <StatCard label="Attack Bonus" value={formatMod(abilityMod(char.dexterity) + char.proficiencyBonus)} sub="DEX ranged/finesse" />
          {char.spellSaveDc && <StatCard label="Spell Save DC" value={String(char.spellSaveDc)} />}
          {char.spellAttackBonus != null && <StatCard label="Spell Attack" value={formatMod(char.spellAttackBonus)} />}
        </div>
      </div>

      {actionFeatures.length > 0 ? (
        <div className="space-y-3">
          {actionFeatures.map((f, i) => (
            <div key={i} className="bg-gray-900 border border-gray-800 rounded-lg p-4">
              <h4 className="text-white font-medium text-sm">{f.name}</h4>
              <p className="text-gray-400 text-xs mt-1 whitespace-pre-line">{f.description}</p>
            </div>
          ))}
        </div>
      ) : (
        <p className="text-gray-500 text-sm">No combat actions configured. Add features in the Features tab.</p>
      )}
    </div>
  );
}

function SpellsTab({ char, spellsKnown, spellSlots, saveField }: {
  char: PlayerCharacter;
  spellsKnown: Array<{ name: string; level: number; prepared?: boolean }>;
  spellSlots: Record<string, { total: number; used: number }>;
  saveField: (u: CharacterUpdateRequest) => Promise<void>;
}) {
  if (!char.spellcastingAbility) {
    return <p className="text-gray-500 text-sm">This character is not a spellcaster.</p>;
  }

  const slotLevels = Object.entries(spellSlots).sort((a, b) => parseInt(a[0]) - parseInt(b[0]));
  const spellsByLevel = new Map<number, typeof spellsKnown>();
  for (const spell of spellsKnown) {
    const list = spellsByLevel.get(spell.level) || [];
    list.push(spell);
    spellsByLevel.set(spell.level, list);
  }

  async function useSlot(level: string) {
    const slot = spellSlots[level];
    if (!slot || slot.used >= slot.total) return;
    const updated = { ...spellSlots, [level]: { ...slot, used: slot.used + 1 } };
    await saveField({ spellSlots: JSON.stringify(updated) });
  }

  return (
    <div className="space-y-6">
      <div className="bg-gray-900 border border-gray-800 rounded-lg p-4">
        <h3 className="text-gray-400 text-xs font-medium uppercase tracking-wider mb-3">Spellcasting</h3>
        <div className="grid grid-cols-3 gap-3">
          <StatCard label="Ability" value={char.spellcastingAbility} />
          {char.spellSaveDc && <StatCard label="Save DC" value={String(char.spellSaveDc)} />}
          {char.spellAttackBonus != null && <StatCard label="Attack Bonus" value={formatMod(char.spellAttackBonus)} />}
        </div>
      </div>

      {slotLevels.length > 0 && (
        <div className="bg-gray-900 border border-gray-800 rounded-lg p-4">
          <h3 className="text-gray-400 text-xs font-medium uppercase tracking-wider mb-3">Spell Slots</h3>
          <div className="grid grid-cols-3 sm:grid-cols-5 gap-3">
            {slotLevels.map(([level, slot]) => (
              <button
                key={level}
                onClick={() => useSlot(level)}
                className="bg-gray-800 rounded-lg p-3 text-center hover:bg-gray-700 transition-colors"
              >
                <p className="text-gray-500 text-xs">Level {level}</p>
                <p className="text-white font-bold">{slot.total - slot.used}/{slot.total}</p>
              </button>
            ))}
          </div>
        </div>
      )}

      {[0, 1, 2, 3, 4, 5, 6, 7, 8, 9].map(level => {
        const spells = spellsByLevel.get(level);
        if (!spells || spells.length === 0) return null;
        return (
          <div key={level} className="bg-gray-900 border border-gray-800 rounded-lg p-4">
            <h3 className="text-gray-400 text-xs font-medium uppercase tracking-wider mb-2">
              {level === 0 ? 'Cantrips' : `Level ${level} Spells`}
            </h3>
            <div className="space-y-1">
              {spells.map((s, i) => (
                <div key={i} className="flex items-center gap-2 py-1">
                  {s.prepared !== undefined && (
                    <div className={`w-2 h-2 rounded-full ${s.prepared ? 'bg-green-400' : 'bg-gray-700'}`} />
                  )}
                  <span className="text-white text-sm">{s.name}</span>
                </div>
              ))}
            </div>
          </div>
        );
      })}

      {spellsKnown.length === 0 && (
        <p className="text-gray-500 text-sm">No spells known yet.</p>
      )}
    </div>
  );
}

function InventoryTab({ equipment, currency, char, saveField }: {
  equipment: Array<{ name: string; quantity?: number; description?: string }>;
  currency: { cp: number; sp: number; ep: number; gp: number; pp: number };
  char: PlayerCharacter;
  saveField: (u: CharacterUpdateRequest) => Promise<void>;
}) {
  const [editCurrency, setEditCurrency] = useState(false);
  const [currencyForm, setCurrencyForm] = useState(currency);

  useEffect(() => { setCurrencyForm(currency); }, [currency]);

  async function saveCurrency() {
    await saveField({ currency: JSON.stringify(currencyForm) });
    setEditCurrency(false);
  }

  return (
    <div className="space-y-6">
      {/* Currency */}
      <div className="bg-gray-900 border border-gray-800 rounded-lg p-4">
        <div className="flex items-center justify-between mb-3">
          <h3 className="text-gray-400 text-xs font-medium uppercase tracking-wider">Currency</h3>
          <button onClick={() => editCurrency ? saveCurrency() : setEditCurrency(true)} className="text-indigo-400 text-xs hover:text-indigo-300">
            {editCurrency ? 'Save' : 'Edit'}
          </button>
        </div>
        <div className="grid grid-cols-5 gap-3">
          {(['cp', 'sp', 'ep', 'gp', 'pp'] as const).map(coin => (
            <div key={coin} className="text-center">
              <p className="text-gray-500 text-xs uppercase">{coin}</p>
              {editCurrency ? (
                <input
                  type="number"
                  min={0}
                  value={currencyForm[coin]}
                  onChange={e => setCurrencyForm({ ...currencyForm, [coin]: parseInt(e.target.value) || 0 })}
                  className="w-full text-center text-white font-bold bg-gray-800 border border-gray-700 rounded py-1 text-sm focus:outline-none focus:ring-1 focus:ring-indigo-500"
                />
              ) : (
                <p className="text-white font-bold">{currency[coin]}</p>
              )}
            </div>
          ))}
        </div>
      </div>

      {/* Equipment */}
      <div className="bg-gray-900 border border-gray-800 rounded-lg p-4">
        <h3 className="text-gray-400 text-xs font-medium uppercase tracking-wider mb-3">Equipment</h3>
        {equipment.length > 0 ? (
          <div className="space-y-2">
            {equipment.map((item, i) => (
              <div key={i} className="flex items-center gap-2 py-1 border-b border-gray-800 last:border-0">
                <span className="text-white text-sm flex-1">{item.name}</span>
                {item.quantity && item.quantity > 1 && <span className="text-gray-500 text-xs">x{item.quantity}</span>}
              </div>
            ))}
          </div>
        ) : (
          <p className="text-gray-500 text-sm">No equipment recorded.</p>
        )}
      </div>
    </div>
  );
}

function FeaturesTab({ features, char }: {
  features: Array<{ name: string; description: string; source?: string }>;
  char: PlayerCharacter;
}) {
  return (
    <div className="space-y-3">
      {features.length > 0 ? (
        features.map((f, i) => (
          <details key={i} className="bg-gray-900 border border-gray-800 rounded-lg group">
            <summary className="px-4 py-3 cursor-pointer list-none flex items-center justify-between">
              <div>
                <h4 className="text-white font-medium text-sm">{f.name}</h4>
                {f.source && <span className="text-gray-500 text-xs">{f.source}</span>}
              </div>
              <svg className="w-4 h-4 text-gray-500 group-open:rotate-180 transition-transform" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
              </svg>
            </summary>
            <div className="px-4 pb-3">
              <p className="text-gray-400 text-sm whitespace-pre-line">{f.description}</p>
            </div>
          </details>
        ))
      ) : (
        <p className="text-gray-500 text-sm">No features recorded. Features will be populated based on your race, class, and background choices.</p>
      )}
    </div>
  );
}

function JournalTab({ char, saveField }: {
  char: PlayerCharacter;
  saveField: (u: CharacterUpdateRequest) => Promise<void>;
}) {
  const [editing, setEditing] = useState<string | null>(null);
  const [editValue, setEditValue] = useState('');

  const fields: Array<{ key: keyof PlayerCharacter; label: string }> = [
    { key: 'personalityTraits', label: 'Personality Traits' },
    { key: 'ideals', label: 'Ideals' },
    { key: 'bonds', label: 'Bonds' },
    { key: 'flaws', label: 'Flaws' },
    { key: 'notes', label: 'Notes' },
  ];

  async function save(key: string) {
    await saveField({ [key]: editValue });
    setEditing(null);
  }

  return (
    <div className="space-y-4">
      {fields.map(({ key, label }) => (
        <div key={key} className="bg-gray-900 border border-gray-800 rounded-lg p-4">
          <div className="flex items-center justify-between mb-2">
            <h3 className="text-gray-400 text-xs font-medium uppercase tracking-wider">{label}</h3>
            <button
              onClick={() => {
                if (editing === key) { save(key); }
                else { setEditing(key); setEditValue((char[key] as string) || ''); }
              }}
              className="text-indigo-400 text-xs hover:text-indigo-300"
            >
              {editing === key ? 'Save' : 'Edit'}
            </button>
          </div>
          {editing === key ? (
            <textarea
              value={editValue}
              onChange={e => setEditValue(e.target.value)}
              rows={4}
              className="w-full px-3 py-2 bg-gray-800 border border-gray-700 rounded-lg text-white text-sm resize-none focus:outline-none focus:ring-2 focus:ring-indigo-500"
              autoFocus
            />
          ) : (
            <p className="text-gray-300 text-sm whitespace-pre-line">
              {(char[key] as string) || <span className="text-gray-600 italic">Not set</span>}
            </p>
          )}
        </div>
      ))}
    </div>
  );
}

function StatCard({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="bg-gray-900 border border-gray-800 rounded-lg p-3 text-center">
      <p className="text-gray-500 text-xs">{label}</p>
      <p className="text-white font-bold text-lg">{value}</p>
      {sub && <p className="text-gray-600 text-xs">{sub}</p>}
    </div>
  );
}
