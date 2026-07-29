import { useState, useEffect } from 'react';
import { Swords, Sparkles, Crown, Castle, Info, Target, Zap } from 'lucide-react';
import { combatApi } from '../../api/combatApi';
import type {
  MonsterActionTemplate,
  MonsterAction,
  MonsterActionRequest,
  MonsterActionResponse,
  MonsterSpellcasting,
} from '../../types/combat';
import type { EncounterParticipant, Encounter } from '../../types/encounter';

interface Props {
  encounterId: string;
  participant: EncounterParticipant;
  participants: EncounterParticipant[];
  onUpdate: (encounterState: Encounter) => void;
  onClose: () => void;
}

type Section = 'actions' | 'spells' | 'legendary' | 'lair';

export default function MonsterActionPanel({
  encounterId,
  participant,
  participants,
  onUpdate,
  onClose,
}: Props) {
  const [templates, setTemplates] = useState<MonsterActionTemplate | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [activeSection, setActiveSection] = useState<Section>('actions');
  const [selectedAction, setSelectedAction] = useState<MonsterAction | null>(null);
  const [selectedTargets, setSelectedTargets] = useState<string[]>([]);
  const [overrideAttackBonus, setOverrideAttackBonus] = useState<number | undefined>();
  const [overrideSaveDC, setOverrideSaveDC] = useState<number | undefined>();
  const [advantage, setAdvantage] = useState<boolean | null>(null);
  const [result, setResult] = useState<MonsterActionResponse | null>(null);
  const [executing, setExecuting] = useState(false);
  const [selectedSpell, setSelectedSpell] = useState<string | null>(null);
  const [spellLevel, setSpellLevel] = useState<number>(0);

  useEffect(() => {
    combatApi.getMonsterActions(encounterId, participant.id)
      .then(res => { setTemplates(res.data); setLoading(false); })
      .catch(err => { setError(err.message); setLoading(false); });
  }, [encounterId, participant.id]);

  const executeAction = async (action: MonsterAction, source: 'ACTION' | 'LEGENDARY' | 'LAIR') => {
    if (selectedTargets.length === 0) return;
    setExecuting(true);
    try {
      const request: MonsterActionRequest = {
        monsterParticipantId: participant.id,
        actionName: action.name,
        actionSource: source,
        targetParticipantIds: selectedTargets,
        overrideAttackBonus,
        overrideSaveDC,
        advantage,
      };
      const res = await combatApi.monsterAction(encounterId, request);
      setResult(res.data);
      onUpdate(res.data.encounterState);
    } catch (err: any) {
      setError(err.response?.data?.message || err.message);
    } finally {
      setExecuting(false);
    }
  };

  const castMonsterSpell = async (spellName: string) => {
    if (selectedTargets.length === 0) return;
    setExecuting(true);
    try {
      const res = await combatApi.monsterSpell(encounterId, {
        monsterParticipantId: participant.id,
        spellName,
        slotLevel: spellLevel || 0,
        targetParticipantIds: selectedTargets,
        overrideAttackBonus,
        overrideSaveDC,
        advantage,
      });
      setResult(res.data as unknown as MonsterActionResponse);
      onUpdate(res.data.encounterState);
    } catch (err: any) {
      setError(err.response?.data?.message || err.message);
    } finally {
      setExecuting(false);
    }
  };

  const handleNonAutomatableClick = (action: MonsterAction, source: 'ACTION' | 'LEGENDARY' | 'LAIR') => {
    combatApi.monsterAction(encounterId, {
      monsterParticipantId: participant.id,
      actionName: action.name,
      actionSource: source,
      targetParticipantIds: [],
    }).then(res => {
      onUpdate(res.data.encounterState);
    }).catch(err => {
      setError(err.response?.data?.message || err.message);
    });
  };

  const toggleTarget = (id: string) => {
    setSelectedTargets(prev =>
      prev.includes(id) ? prev.filter(t => t !== id) : [...prev, id]
    );
  };

  const resetSelection = () => {
    setSelectedAction(null);
    setSelectedTargets([]);
    setResult(null);
    setOverrideAttackBonus(undefined);
    setOverrideSaveDC(undefined);
    setAdvantage(null);
    setSelectedSpell(null);
  };

  if (loading) return <div className="p-4 text-center text-[--text-muted]">Loading actions...</div>;
  if (error && !templates) return <div className="p-4 text-red-600">Error: {error}</div>;
  if (!templates) return <div className="p-4 text-[--text-muted]">No action data available</div>;

  const legendaryRemaining = templates.legendaryActionCount || 0;
  const legendaryActions = templates.legendaryActions || [];
  const lairActions = templates.lairActions || [];
  const actions = templates.actions || [];
  const spellcasting = templates.spellcasting;
  const hasLegendary = legendaryActions.length > 0;
  const hasLair = lairActions.length > 0;
  const hasSpells = !!spellcasting;

  // Get recharge status from resource pools
  const resourcePools = (templates as any).resourcePools || {};

  const isRecharged = (action: MonsterAction): boolean => {
    if (!action.recharge) return true;
    const kebab = action.name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/-+/g, '-').replace(/^-|-$/g, '');
    const poolId = `monster:recharge:${kebab}`;
    return !resourcePools[poolId] || resourcePools[poolId].currentUses > 0;
  };

  const getLairActionStatus = (action: MonsterAction): 'available' | 'used-last' | 'available' => {
    return 'available'; // The backend enforces this; frontend shows all
  };

  // Tab configuration
  const tabs: { key: Section; label: string; icon: React.ReactNode; count: number }[] = [
    { key: 'actions', label: 'Actions', icon: <Swords size={14} />, count: actions.length },
    ...(hasSpells ? [{ key: 'spells' as Section, label: 'Spells', icon: <Sparkles size={14} />, count: 0 }] : []),
    ...(hasLegendary ? [{ key: 'legendary' as Section, label: 'Legendary', icon: <Crown size={14} />, count: legendaryRemaining }] : []),
    ...(hasLair ? [{ key: 'lair' as Section, label: 'Lair', icon: <Castle size={14} />, count: lairActions.length }] : []),
  ];

  const renderActionRow = (action: MonsterAction, source: 'ACTION' | 'LEGENDARY' | 'LAIR') => {
    const isNonAutomatable = action.automatable === false || (!action.deliveryMethod && !action.effects);
    const recharged = isRecharged(action);
    const cost = action.cost || 0;

    return (
      <div
        key={action.name}
        className={`flex items-center gap-2 px-2 py-1.5 rounded border border-[--rule-light]
          ${!recharged ? 'opacity-40 cursor-not-allowed' : 'cursor-pointer hover:bg-[--monster-bg]'}
          ${selectedAction?.name === action.name ? 'bg-[--monster-bg] border-[--color-monster]' : 'bg-[--surface-card]'}`}
        onClick={() => {
          if (!recharged) return;
          if (isNonAutomatable) {
            handleNonAutomatableClick(action, source);
            return;
          }
          setSelectedAction(action);
          if (source === 'LEGENDARY') {
            // For legendary actions referencing spells, open spell tab
          }
        }}
      >
        <span className="text-[--text-ink] text-sm font-medium flex-1 font-[Cinzel]">
          {action.name}
          {action.isMultiattack && <Zap size={12} className="inline ml-1 text-amber-500" />}
        </span>
        {action.recharge && !recharged && <span className="text-xs text-[--text-muted]">(Recharging)</span>}
        {action.recharge && recharged && <span className="text-xs text-green-600">(Available)</span>}
        {cost > 0 && <span className="text-xs bg-[--monster-bg] text-[--color-monster] px-1 rounded">{cost} pts</span>}
        {isNonAutomatable && <Info size={12} className="text-[--text-muted]" />}
        {action.deliveryMethod === 'ATTACK_ROLL' && action.attackBonus != null && (
          <span className="text-xs text-[--text-muted]">+{action.attackBonus}</span>
        )}
        {action.deliveryMethod === 'SAVING_THROW' && action.saveDC && (
          <span className="text-xs text-[--text-muted]">DC {action.saveDC}</span>
        )}
        {action.effects?.map((e, i) =>
          e.effectType === 'DAMAGE' && e.damageDice ? (
            <span key={i} className="text-xs text-[--text-muted]">{e.damageDice} {e.damageType}</span>
          ) : null
        )}
      </div>
    );
  };

  // Render target selector when action selected
  const renderTargetSelector = () => {
    if (!selectedAction || result) return null;
    const targets = participants.filter(p => p.id !== participant.id && p.isAlive);

    return (
      <div className="mt-3 p-3 border border-[--rule] rounded bg-[--surface-page]">
        <h4 className="text-sm font-semibold text-[--text-ink] mb-2 font-[Cinzel]">
          Select targets for: {selectedAction.name}
        </h4>

        {/* Override fields */}
        {(selectedAction.deliveryMethod === 'ATTACK_ROLL' || selectedAction.deliveryMethod === 'SAVING_THROW') && (
          <div className="flex gap-2 mb-2">
            {selectedAction.deliveryMethod === 'ATTACK_ROLL' && (
              <label className="text-xs flex items-center gap-1">
                <span className="text-[--text-muted]">Atk Bonus:</span>
                <input
                  type="number"
                  className="w-16 px-1 border border-[--rule] rounded text-sm"
                  value={overrideAttackBonus ?? selectedAction.attackBonus ?? ''}
                  onChange={e => setOverrideAttackBonus(e.target.value ? parseInt(e.target.value) : undefined)}
                />
              </label>
            )}
            {selectedAction.deliveryMethod === 'SAVING_THROW' && (
              <label className="text-xs flex items-center gap-1">
                <span className="text-[--text-muted]">Save DC:</span>
                <input
                  type="number"
                  className="w-16 px-1 border border-[--rule] rounded text-sm"
                  value={overrideSaveDC ?? selectedAction.saveDC ?? ''}
                  onChange={e => setOverrideSaveDC(e.target.value ? parseInt(e.target.value) : undefined)}
                />
              </label>
            )}
            <label className="text-xs flex items-center gap-1">
              <span className="text-[--text-muted]">Adv:</span>
              <select
                className="border border-[--rule] rounded text-sm"
                value={advantage === null ? 'norm' : advantage ? 'adv' : 'dis'}
                onChange={e => setAdvantage(e.target.value === 'norm' ? null : e.target.value === 'adv')}
              >
                <option value="norm">Norm</option>
                <option value="adv">Adv</option>
                <option value="dis">Dis</option>
              </select>
            </label>
          </div>
        )}

        <div className="flex flex-wrap gap-1 mb-2">
          {targets.map(t => (
            <button
              key={t.id}
              className={`px-2 py-1 text-xs rounded border transition-colors
                ${selectedTargets.includes(t.id)
                  ? 'bg-[--color-monster] text-white border-[--color-monster]'
                  : 'bg-[--surface-card] border-[--rule-light] hover:bg-[--monster-bg]'}`}
              onClick={() => toggleTarget(t.id)}
            >
              {t.displayName}
            </button>
          ))}
        </div>

        <div className="flex gap-2">
          <button
            className="px-3 py-1 text-sm bg-[--color-monster] text-white rounded hover:opacity-90 disabled:opacity-50 font-[Cinzel]"
            disabled={selectedTargets.length === 0 || executing}
            onClick={() => executeAction(
              selectedAction,
              activeSection === 'legendary' ? 'LEGENDARY' : activeSection === 'lair' ? 'LAIR' : 'ACTION'
            )}
          >
            {executing ? 'Executing...' : `Execute${selectedAction.isMultiattack ? ' Multiattack' : ''}`}
          </button>
          <button
            className="px-2 py-1 text-sm border border-[--rule] rounded hover:bg-[--surface-page]"
            onClick={resetSelection}
          >
            Cancel
          </button>
        </div>
      </div>
    );
  };

  // Render spell section
  const renderSpellSection = () => {
    if (!spellcasting) return null;

    const slots = spellcasting.slots || {};
    const spellsByLevel = spellcasting.spellsByLevel || {};
    const dailySpells = spellcasting.dailySpells || {};
    const atWillSpells = spellcasting.atWillSpells || [];

    // Flatten spell list
    const allSpells: { name: string; level: number; isAtWill: boolean; dailyLimit: number }[] = [];

    Object.entries(spellsByLevel).forEach(([level, spells]) => {
      spells.forEach(name => allSpells.push({ name, level: parseInt(level), isAtWill: false, dailyLimit: 0 }));
    });
    Object.entries(dailySpells).forEach(([, spells]) => {
      spells.forEach(name => {
        const num = parseInt(Object.keys(dailySpells)[0].replace(/[^0-9]/g, '')) || 1;
        allSpells.push({ name, level: 0, isAtWill: false, dailyLimit: num });
      });
    });
    atWillSpells.forEach(name => allSpells.push({ name, level: 0, isAtWill: true, dailyLimit: 0 }));

    return (
      <div className="space-y-2">
        {/* Slot display */}
        {Object.keys(slots).length > 0 && (
          <div className="flex gap-2 mb-2 flex-wrap">
            {Object.entries(slots).map(([level, max]) => {
              const poolKey = `spellSlot:${level}`;
              const remaining = resourcePools[poolKey]?.currentUses ?? max;
              return (
                <span key={level} className="text-xs px-2 py-0.5 bg-[--surface-page] border border-[--rule-light] rounded">
                  Lv{level}: {remaining}/{max}
                </span>
              );
            })}
          </div>
        )}

        {/* Spell list */}
        {allSpells.map(spell => (
          <div
            key={spell.name}
            className={`flex items-center gap-2 px-2 py-1 rounded border border-[--rule-light]
              cursor-pointer hover:bg-[--monster-bg] bg-[--surface-card]`}
            onClick={() => {
              setSelectedSpell(spell.name);
              setSelectedAction(null);
            }}
          >
            <Sparkles size={12} className="text-[--color-monster]" />
            <span className="text-sm text-[--text-ink]">{spell.name}</span>
            {spell.level > 0 && <span className="text-xs text-[--text-muted]">Lv{spell.level}</span>}
            {spell.isAtWill && <span className="text-xs text-green-600">(At Will)</span>}
          </div>
        ))}

        {/* Spell cast flow when spell selected */}
        {selectedSpell && !result && renderTargetSelector()}

        {/* Override fields for spells */}
        {selectedSpell && spellcasting.saveDC != null && (
          <div className="flex gap-2 mt-2">
            <label className="text-xs flex items-center gap-1">
              <span className="text-[--text-muted]">Save DC:</span>
              <input type="number" className="w-16 px-1 border border-[--rule] rounded text-sm"
                value={overrideSaveDC ?? spellcasting.saveDC ?? ''}
                onChange={e => setOverrideSaveDC(e.target.value ? parseInt(e.target.value) : undefined)} />
            </label>
            <label className="text-xs flex items-center gap-1">
              <span className="text-[--text-muted]">Slot Lv:</span>
              <select className="border border-[--rule] rounded text-sm"
                value={spellLevel} onChange={e => setSpellLevel(parseInt(e.target.value))}>
                {Object.keys(slots).map(lv => <option key={lv} value={lv}>{lv}</option>)}
                <option value="0">Cantrip</option>
              </select>
            </label>
          </div>
        )}
        {selectedSpell && (
          <button
            className="px-3 py-1 text-sm bg-[--color-monster] text-white rounded hover:opacity-90 disabled:opacity-50 font-[Cinzel] mt-2"
            disabled={selectedTargets.length === 0 || executing}
            onClick={() => castMonsterSpell(selectedSpell!)}
          >
            {executing ? 'Casting...' : `Cast ${selectedSpell}`}
          </button>
        )}
      </div>
    );
  };

  // Render result
  const renderResult = () => {
    if (!result) return null;
    return (
      <div className="mt-3 p-3 border border-[--rule] rounded bg-[--surface-page]">
        <h4 className="text-sm font-semibold text-[--text-ink] mb-2 font-[Cinzel]">Result</h4>
        <p className="text-sm text-[--text-ink] mb-2">{result.description}</p>
        {result.targetResults.map(tr => (
          <div key={tr.targetId} className="text-xs text-[--text-muted] mb-1">
            <span className="font-medium">{tr.targetName}</span>: {tr.attackOutcome}
            {tr.damage > 0 && <> — {tr.damage} {tr.damageType} damage</>}
            {tr.conditionsApplied.length > 0 && <> — {tr.conditionsApplied.join(', ')}</>}
          </div>
        ))}
        {result.legendaryResistanceAvailable && (
          <div className="mt-2 p-2 bg-amber-50 border border-amber-300 rounded">
            <p className="text-xs text-amber-800 font-medium">
              Use Legendary Resistance? ({result.legendaryResistanceRemaining} remaining)
            </p>
            <div className="flex gap-2 mt-1">
              <button
                className="px-2 py-0.5 text-xs bg-amber-500 text-white rounded hover:bg-amber-600 font-[Cinzel]"
                onClick={() => {
                  combatApi.useLegendaryResistance(encounterId, participant.id)
                    .then(res => onUpdate(res.data));
                }}
              >
                Use Legendary Resistance
              </button>
              <button
                className="px-2 py-0.5 text-xs border border-[--rule] rounded hover:bg-[--surface-page]"
                onClick={() => {
                  combatApi.confirmFailedSave(encounterId, participant.id)
                    .then(res => onUpdate(res.data));
                }}
              >
                Accept Failure
              </button>
            </div>
          </div>
        )}
        {result.requiresManualResolution && (
          <p className="text-xs text-amber-600 mt-1">
            Manual resolution: {result.manualResolutionReason}
          </p>
        )}
        <button
          className="mt-2 px-2 py-1 text-xs border border-[--rule] rounded hover:bg-[--surface-page]"
          onClick={resetSelection}
        >
          Done
        </button>
      </div>
    );
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40"
      onClick={onClose}
      aria-modal="true"
      role="dialog"
    >
      <div
        className="bg-[--surface-card] rounded border border-[--rule] shadow-xl w-full max-w-lg max-h-[85vh] overflow-hidden flex flex-col"
        onClick={e => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-4 py-3 border-b border-[--rule]">
          <div className="flex items-center gap-2">
            <Target size={18} className="text-[--color-monster]" />
            <h3 className="font-[Cinzel] text-lg text-[--text-ink]">
              {participant.displayName}
            </h3>
          </div>
          <button
            className="text-[--text-muted] hover:text-[--text-ink] text-xl leading-none"
            onClick={onClose}
            aria-label="Close"
          >
            ×
          </button>
        </div>

        {/* Tabs */}
        {tabs.length > 1 && (
          <div className="flex border-b border-[--rule-light] px-2">
            {tabs.map(tab => (
              <button
                key={tab.key}
                className={`flex items-center gap-1 px-3 py-2 text-xs border-b-2 transition-colors font-[Cinzel]
                  ${activeSection === tab.key
                    ? 'border-[--color-monster] text-[--color-monster]'
                    : 'border-transparent text-[--text-muted] hover:text-[--text-ink]'}`}
                onClick={() => { setActiveSection(tab.key); resetSelection(); }}
              >
                {tab.icon}
                {tab.label}
                {tab.count > 0 && (
                  <span className="text-[10px] bg-[--monster-bg] text-[--color-monster] px-1 rounded-full">
                    {tab.count}
                  </span>
                )}
              </button>
            ))}
          </div>
        )}

        {/* Content */}
        <div className="flex-1 overflow-y-auto p-3 space-y-1">
          {error && <div className="p-2 text-xs text-red-600 bg-red-50 rounded mb-2">{error}</div>}

          {activeSection === 'actions' && (
            <>
              {actions.filter(a => a.isMultiattack).map(a => renderActionRow(a, 'ACTION'))}
              {actions.filter(a => !a.isMultiattack).map(a => renderActionRow(a, 'ACTION'))}
              {actions.length === 0 && (
                <p className="text-xs text-[--text-muted] text-center py-4">No actions available</p>
              )}
            </>
          )}

          {activeSection === 'spells' && renderSpellSection()}

          {activeSection === 'legendary' && (
            <>
              <div className="flex items-center gap-2 mb-2 px-2">
                <Crown size={14} className="text-amber-500" />
                <span className="text-xs text-[--text-muted]">
                  Legendary Actions: {legendaryRemaining} remaining
                </span>
              </div>
              {legendaryActions.map(a => renderActionRow(a, 'LEGENDARY'))}
            </>
          )}

          {activeSection === 'lair' && (
            <>
              {lairActions.map(a => renderActionRow(a, 'LAIR'))}
            </>
          )}

          {renderTargetSelector()}
          {renderResult()}
        </div>
      </div>
    </div>
  );
}
