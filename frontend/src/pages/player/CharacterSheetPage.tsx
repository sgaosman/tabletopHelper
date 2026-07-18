import { useState, useEffect, type FormEvent } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Save } from 'lucide-react';
import { characterApi } from '../../api/characterApi';
import { campaignApi } from '../../api/campaignApi';
import type { PlayerCharacter } from '../../types/character';
import type { Campaign } from '../../types/campaign';

const ABILITY_NAMES = ['strength', 'dexterity', 'constitution', 'intelligence', 'wisdom', 'charisma'] as const;
const ABILITY_ABBR: Record<string, string> = { strength: 'STR', dexterity: 'DEX', constitution: 'CON', intelligence: 'INT', wisdom: 'WIS', charisma: 'CHA' };

const TABS = ['Basic Info', 'Ability Scores', 'Combat', 'Personality & Notes'] as const;

function modStr(score: number) {
  const mod = Math.floor((score - 10) / 2);
  return mod >= 0 ? `+${mod}` : `${mod}`;
}

export default function CharacterSheetPage() {
  const { characterId } = useParams<{ characterId: string }>();
  const isNew = !characterId || characterId === 'new';
  const navigate = useNavigate();
  const [activeTab, setActiveTab] = useState<(typeof TABS)[number]>('Basic Info');
  const [campaigns, setCampaigns] = useState<Campaign[]>([]);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  const [form, setForm] = useState({
    name: '', race: '', characterClass: '', subclass: '', level: 1,
    experiencePoints: 0, background: '', alignment: '', campaignId: '',
    strength: 10, dexterity: 10, constitution: 10, intelligence: 10, wisdom: 10, charisma: 10,
    hpMax: 10, hpCurrent: 10, hpTemp: 0, armourClass: 10, initiativeBonus: 0,
    speed: 30, proficiencyBonus: 2, hitDiceTotal: '', hitDiceRemaining: '',
    personalityTraits: '', ideals: '', bonds: '', flaws: '', notes: '',
  });

  useEffect(() => {
    campaignApi.getAll().then(res => setCampaigns(res.data));
    if (!isNew && characterId) {
      characterApi.getById(characterId).then(res => {
        const c = res.data;
        setForm({
          name: c.name, race: c.race || '', characterClass: c.characterClass || '',
          subclass: c.subclass || '', level: c.level, experiencePoints: c.experiencePoints,
          background: c.background || '', alignment: c.alignment || '',
          campaignId: c.campaignId || '',
          strength: c.strength, dexterity: c.dexterity, constitution: c.constitution,
          intelligence: c.intelligence, wisdom: c.wisdom, charisma: c.charisma,
          hpMax: c.hpMax, hpCurrent: c.hpCurrent, hpTemp: c.hpTemp,
          armourClass: c.armourClass, initiativeBonus: c.initiativeBonus,
          speed: c.speed, proficiencyBonus: c.proficiencyBonus,
          hitDiceTotal: c.hitDiceTotal || '', hitDiceRemaining: c.hitDiceRemaining || '',
          personalityTraits: c.personalityTraits || '', ideals: c.ideals || '',
          bonds: c.bonds || '', flaws: c.flaws || '', notes: c.notes || '',
        });
      });
    }
  }, [characterId, isNew]);

  function update(field: string, value: string | number) {
    setForm(prev => ({ ...prev, [field]: value }));
  }

  async function handleSave(e: FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError('');
    setSuccess('');

    try {
      if (isNew) {
        const res = await characterApi.create({
          name: form.name, race: form.race || undefined,
          characterClass: form.characterClass || undefined,
          subclass: form.subclass || undefined, level: form.level,
          background: form.background || undefined, alignment: form.alignment || undefined,
          strength: form.strength, dexterity: form.dexterity, constitution: form.constitution,
          intelligence: form.intelligence, wisdom: form.wisdom, charisma: form.charisma,
          hpMax: form.hpMax, armourClass: form.armourClass, initiativeBonus: form.initiativeBonus,
          speed: form.speed, proficiencyBonus: form.proficiencyBonus,
          campaignId: form.campaignId || undefined,
        });
        navigate(`/player/characters/${res.data.id}`, { replace: true });
        setSuccess('Character created!');
      } else {
        await characterApi.update(characterId!, {
          name: form.name, race: form.race, characterClass: form.characterClass,
          subclass: form.subclass, level: form.level, experiencePoints: form.experiencePoints,
          background: form.background, alignment: form.alignment,
          strength: form.strength, dexterity: form.dexterity, constitution: form.constitution,
          intelligence: form.intelligence, wisdom: form.wisdom, charisma: form.charisma,
          hpMax: form.hpMax, hpCurrent: form.hpCurrent, hpTemp: form.hpTemp,
          armourClass: form.armourClass, initiativeBonus: form.initiativeBonus,
          speed: form.speed, proficiencyBonus: form.proficiencyBonus,
          hitDiceTotal: form.hitDiceTotal, hitDiceRemaining: form.hitDiceRemaining,
          personalityTraits: form.personalityTraits, ideals: form.ideals,
          bonds: form.bonds, flaws: form.flaws, notes: form.notes,
          campaignId: form.campaignId || undefined,
        });
        setSuccess('Character saved!');
      }
    } catch (err: any) {
      setError(err.response?.data?.error || 'Failed to save character');
    } finally {
      setSaving(false);
      setTimeout(() => setSuccess(''), 3000);
    }
  }

  const inputClass = "w-full px-3 py-2 bg-gray-800 border border-gray-700 rounded-lg text-white placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-indigo-500 text-sm";
  const labelClass = "block text-sm font-medium text-gray-300 mb-1";

  return (
    <div className="min-h-screen bg-gray-950">
      <header className="sticky top-0 z-10 bg-gray-950 border-b border-gray-800 px-6 py-4 flex items-center justify-between">
        <button onClick={() => navigate('/player')} className="flex items-center gap-2 text-gray-400 hover:text-white transition-colors">
          <ArrowLeft className="w-4 h-4" /> Back
        </button>
        <button
          onClick={handleSave}
          disabled={saving}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:bg-indigo-800 text-white rounded-lg text-sm transition-colors"
        >
          <Save className="w-4 h-4" /> {saving ? 'Saving...' : 'Save'}
        </button>
      </header>

      <main className="max-w-4xl mx-auto px-6 py-8">
        <h1 className="text-2xl font-bold text-white mb-6">{isNew ? 'Create Character' : form.name || 'Character Sheet'}</h1>

        {error && <div className="bg-red-900/50 border border-red-700 text-red-300 rounded-lg p-3 mb-4 text-sm">{error}</div>}
        {success && <div className="bg-green-900/50 border border-green-700 text-green-300 rounded-lg p-3 mb-4 text-sm">{success}</div>}

        {/* Tabs */}
        <div className="flex gap-1 mb-6 border-b border-gray-800">
          {TABS.map(tab => (
            <button
              key={tab}
              onClick={() => setActiveTab(tab)}
              className={`px-4 py-2 text-sm font-medium transition-colors ${activeTab === tab ? 'text-indigo-400 border-b-2 border-indigo-400' : 'text-gray-400 hover:text-white'}`}
            >
              {tab}
            </button>
          ))}
        </div>

        <form onSubmit={handleSave} className="space-y-6">
          {/* Basic Info Tab */}
          {activeTab === 'Basic Info' && (
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div className="sm:col-span-2">
                <label className={labelClass}>Character Name *</label>
                <input type="text" value={form.name} onChange={e => update('name', e.target.value)} required maxLength={200} className={inputClass} placeholder="e.g. Aragorn" />
              </div>
              <div>
                <label className={labelClass}>Race</label>
                <input type="text" value={form.race} onChange={e => update('race', e.target.value)} className={inputClass} placeholder="e.g. Human" />
              </div>
              <div>
                <label className={labelClass}>Class</label>
                <input type="text" value={form.characterClass} onChange={e => update('characterClass', e.target.value)} className={inputClass} placeholder="e.g. Fighter" />
              </div>
              <div>
                <label className={labelClass}>Subclass</label>
                <input type="text" value={form.subclass} onChange={e => update('subclass', e.target.value)} className={inputClass} placeholder="e.g. Champion" />
              </div>
              <div>
                <label className={labelClass}>Level</label>
                <input type="number" value={form.level} onChange={e => update('level', parseInt(e.target.value) || 1)} min={1} max={20} className={inputClass} />
              </div>
              <div>
                <label className={labelClass}>Background</label>
                <input type="text" value={form.background} onChange={e => update('background', e.target.value)} className={inputClass} placeholder="e.g. Noble" />
              </div>
              <div>
                <label className={labelClass}>Alignment</label>
                <select value={form.alignment} onChange={e => update('alignment', e.target.value)} className={inputClass}>
                  <option value="">Select...</option>
                  {['Lawful Good','Neutral Good','Chaotic Good','Lawful Neutral','True Neutral','Chaotic Neutral','Lawful Evil','Neutral Evil','Chaotic Evil'].map(a => (
                    <option key={a} value={a}>{a}</option>
                  ))}
                </select>
              </div>
              <div>
                <label className={labelClass}>Campaign</label>
                <select value={form.campaignId} onChange={e => update('campaignId', e.target.value)} className={inputClass}>
                  <option value="">None</option>
                  {campaigns.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
                </select>
              </div>
            </div>
          )}

          {/* Ability Scores Tab */}
          {activeTab === 'Ability Scores' && (
            <div className="grid grid-cols-2 sm:grid-cols-3 gap-4">
              {ABILITY_NAMES.map(ability => (
                <div key={ability} className="bg-gray-900 border border-gray-800 rounded-xl p-4 text-center">
                  <label className="block text-xs font-medium text-gray-400 mb-2 uppercase tracking-wider">{ABILITY_ABBR[ability]} *</label>
                  <input
                    type="number"
                    value={form[ability]}
                    onChange={e => update(ability, parseInt(e.target.value) || 1)}
                    min={1} max={30}
                    className="w-20 mx-auto text-center text-2xl font-bold bg-gray-800 border border-gray-700 rounded-lg text-white py-2 focus:outline-none focus:ring-2 focus:ring-indigo-500"
                  />
                  <p className="text-indigo-400 font-medium mt-2">{modStr(form[ability])}</p>
                </div>
              ))}
            </div>
          )}

          {/* Combat Tab */}
          {activeTab === 'Combat' && (
            <div className="grid grid-cols-2 sm:grid-cols-3 gap-4">
              <div>
                <label className={labelClass}>HP Max *</label>

                <input type="number" value={form.hpMax} onChange={e => update('hpMax', parseInt(e.target.value) || 1)} min={1} className={inputClass} />
              </div>
              {!isNew && (
                <>
                  <div>
                    <label className={labelClass}>HP Current</label>
                    <input type="number" value={form.hpCurrent} onChange={e => update('hpCurrent', parseInt(e.target.value) || 0)} className={inputClass} />
                  </div>
                  <div>
                    <label className={labelClass}>Temp HP</label>
                    <input type="number" value={form.hpTemp} onChange={e => update('hpTemp', parseInt(e.target.value) || 0)} min={0} className={inputClass} />
                  </div>
                </>
              )}
              <div>
                <label className={labelClass}>Armour Class</label>
                <input type="number" value={form.armourClass} onChange={e => update('armourClass', parseInt(e.target.value) || 10)} className={inputClass} />
              </div>
              <div>
                <label className={labelClass}>Initiative Bonus</label>
                <input type="number" value={form.initiativeBonus} onChange={e => update('initiativeBonus', parseInt(e.target.value) || 0)} className={inputClass} />
              </div>
              <div>
                <label className={labelClass}>Speed (ft)</label>
                <input type="number" value={form.speed} onChange={e => update('speed', parseInt(e.target.value) || 30)} className={inputClass} />
              </div>
              <div>
                <label className={labelClass}>Proficiency Bonus</label>
                <input type="number" value={form.proficiencyBonus} onChange={e => update('proficiencyBonus', parseInt(e.target.value) || 2)} className={inputClass} />
              </div>
              <div>
                <label className={labelClass}>Hit Dice Total</label>
                <input type="text" value={form.hitDiceTotal} onChange={e => update('hitDiceTotal', e.target.value)} className={inputClass} placeholder="e.g. 8d10" />
              </div>
              <div>
                <label className={labelClass}>Hit Dice Remaining</label>
                <input type="text" value={form.hitDiceRemaining} onChange={e => update('hitDiceRemaining', e.target.value)} className={inputClass} placeholder="e.g. 6d10" />
              </div>
            </div>
          )}

          {/* Personality & Notes Tab */}
          {activeTab === 'Personality & Notes' && (
            <div className="space-y-4">
              {(['personalityTraits', 'ideals', 'bonds', 'flaws', 'notes'] as const).map(field => (
                <div key={field}>
                  <label className={labelClass}>{field === 'personalityTraits' ? 'Personality Traits' : field.charAt(0).toUpperCase() + field.slice(1)}</label>
                  <textarea
                    value={form[field]}
                    onChange={e => update(field, e.target.value)}
                    rows={3}
                    className={inputClass + ' resize-none'}
                  />
                </div>
              ))}
            </div>
          )}
        </form>
      </main>
    </div>
  );
}
