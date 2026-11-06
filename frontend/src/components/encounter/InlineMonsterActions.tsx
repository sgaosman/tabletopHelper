import { useState } from 'react';
import { isAxiosError } from 'axios';
import { Castle } from 'lucide-react';
import { apiErrorMessage } from '../../api/errors';
import { useEncounterAction, useMonsterActionTemplates } from '../../queries/encounters';
import type { MonsterAction, MonsterActionRequest, MonsterActionResponse, MonsterActionTemplate } from '../../types/combat';
import type { EncounterParticipant } from '../../types/encounter';
import MultiattackPicker from './MultiattackPicker';

type ActionSource = MonsterActionRequest['actionSource'];
type ActionTargets = Pick<MonsterActionRequest, 'targetParticipantIds' | 'componentTargetIds'>;

/** What the last monster action came to: the server's result, or why it refused. */
type InlineResult = { response: MonsterActionResponse } | { error: string };

/**
 * What the templates endpoint answers: the template, with the monster's resource pools (legendary action uses and
 * recharges), or an error for a monster without templates. The generated template type leaves the last two out.
 */
type TemplatesAnswer = MonsterActionTemplate & {
  resourcePools?: Record<string, { currentUses: number }>;
  error?: string;
};

type Tab = 'actions' | 'legendary' | 'lair';

/** The server's own words for a refused monster action, where it gave any. */
function monsterActionError(err: unknown): string {
  if (!isAxiosError(err)) return err instanceof Error ? err.message : apiErrorMessage(err);
  const data: unknown = err.response?.data;
  if (typeof data === 'string' && data) return data;
  if (data && typeof data === 'object') {
    const { message, error } = data as { message?: unknown; error?: unknown };
    if (typeof message === 'string' && message) return message;
    if (typeof error === 'string' && error) return error;
  }
  return err.message;
}

function damageEffect(action: MonsterAction) {
  return action.effects?.find(e => e.effectType === 'DAMAGE' && e.damageDice);
}

function isAutomatable(action: MonsterAction) {
  return !(action.automatable === false || (!action.deliveryMethod && !action.effects));
}

function formatActionLabel(a: MonsterAction) {
  const parts = [a.name];
  if (a.attackBonus != null) parts.push(`+${a.attackBonus}`);
  if (a.saveDC) parts.push(`DC${a.saveDC}`);
  const dmg = damageEffect(a);
  if (dmg?.damageDice) parts.push(dmg.damageDice);
  return parts.join(' · ');
}

/**
 * A monster's actions, legendary actions and lair actions for the DM to take, with the targets they go at and the
 * result of the last one (M12). DM only: the Player View carries no action templates (D157). Legendary actions are
 * used on other creatures' turns, the Lair's included, never on the monster's own; lair actions only on the Lair's
 * turn, by a monster fighting in its lair (D163). The server refuses the rest.
 */
export default function InlineMonsterActions({ participant, participants, isLairTurn, inLair }: {
  participant: EncounterParticipant;
  participants: EncounterParticipant[];
  isLairTurn: boolean;
  inLair: boolean;
}) {
  const encounterActions = useEncounterAction();
  const templatesQuery = useMonsterActionTemplates(participant.id);
  const [tab, setTab] = useState<Tab>('actions');
  const [selectedAction, setSelectedAction] = useState<MonsterAction | null>(null);
  // The multiattack whose target per attack the DM is choosing (D165)
  const [multiattackName, setMultiattackName] = useState<string | null>(null);
  const [chosenTargets, setChosenTargets] = useState<string[]>([]);
  const [result, setResult] = useState<InlineResult | null>(null);
  const [executing, setExecuting] = useState(false);
  // A participant removed from the encounter is no longer a target
  const targets = chosenTargets.filter(id => participants.some(p => p.id === id));

  function toggleTarget(targetId: string) {
    setChosenTargets(prev => prev.includes(targetId) ? prev.filter(t => t !== targetId) : [...prev, targetId]);
  }

  // The targets chosen in the selector, unless the action brings its own, as a multiattack does (D165)
  async function execute(actionName: string, source: ActionSource, own?: ActionTargets) {
    if (!own && targets.length === 0) return;
    setExecuting(true);
    try {
      const response = await encounterActions.monsterAction({
        monsterParticipantId: participant.id,
        actionName,
        actionSource: source,
        targetParticipantIds: own?.targetParticipantIds ?? targets,
        ...(own?.componentTargetIds ? { componentTargetIds: own.componentTargetIds } : {}),
      });
      setResult({ response });
    } catch (err: unknown) {
      setResult({ error: monsterActionError(err) });
    } finally {
      setExecuting(false);
    }
  }

  if (templatesQuery.isPending) return <div className="px-4 py-2 font-body text-[15px] font-semibold text-muted">Loading actions...</div>;
  if (templatesQuery.isError) return <div className="px-4 py-2 font-body text-[15px] font-semibold text-debuff">{apiErrorMessage(templatesQuery.error)}</div>;
  const templates = templatesQuery.data as TemplatesAnswer | undefined;
  if (!templates || templates.error) return <div className="px-4 py-2 font-body text-[15px] font-semibold text-debuff">{templates?.error || 'No data'}</div>;

  const actions = templates.actions || [];
  const legendary = templates.legendaryActions || [];
  const lair = templates.lairActions || [];
  const pools = templates.resourcePools || {};
  const hasLegendary = legendary.length > 0;
  const hasLair = lair.length > 0;
  const legendaryRemaining = pools['monster:legendary-actions']?.currentUses ?? templates.legendaryActionCount ?? 0;
  const ownTurn = participant.isCurrentTurn;
  const lairReady = isLairTurn && inLair;
  const lairReason = !inLair ? `${participant.displayName} isn't fighting in its lair` : 'Lair actions wait for the Lair\'s turn, on initiative 20';

  const currentActions = tab === 'legendary' ? legendary : tab === 'lair' ? lair : actions;

  const isRecharged = (action: MonsterAction) => {
    if (!action.recharge) return true;
    const kebab = action.name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/-+/g, '-').replace(/^-|-$/g, '');
    const pool = pools[`monster:recharge:${kebab}`];
    return !pool || pool.currentUses > 0;
  };

  // A multiattack's attack, labelled with the numbers of the action it makes
  const describeAction = (name: string) => {
    const action = actions.find(a => a.name?.toLowerCase() === name.toLowerCase());
    if (!action) return undefined;
    return formatActionLabel(action).split(' · ').slice(1).join(' · ') || undefined;
  };

  function switchTab(next: Tab) {
    setTab(next);
    setSelectedAction(null);
    setMultiattackName(null);
  }

  return (
    <div className="px-4 py-2 border-t border-rule-light bg-page">
      {(hasLegendary || hasLair) && (
        <div className="flex gap-2 mb-2">
          <TabBtn active={tab === 'actions'} onClick={() => switchTab('actions')} label={`Actions (${actions.length})`} />
          {hasLegendary && <TabBtn active={tab === 'legendary'} onClick={() => switchTab('legendary')} label={`Legendary (${legendaryRemaining})`} />}
          {hasLair && <TabBtn active={tab === 'lair'} onClick={() => switchTab('lair')} label={`Lair (${lair.length})`} />}
        </div>
      )}

      {tab === 'legendary' && ownTurn && (
        <p role="note" className="mb-2 font-body text-[13px] font-semibold text-faint">
          Legendary actions are used on other creatures' turns, not {participant.displayName}'s own.
        </p>
      )}
      {tab === 'lair' && !lairReady && (
        <p role="note" className="mb-2 font-body text-[13px] font-semibold text-faint">{lairReason}.</p>
      )}

      {/* Lair actions get full cards, the others compact buttons */}
      {tab === 'lair' ? (
        <div className="space-y-2 mb-2">
          {currentActions.map(a => {
            const manual = !isAutomatable(a);
            const dmg = damageEffect(a);
            return (
              <div
                key={a.name}
                aria-disabled={!lairReady}
                title={lairReady ? undefined : lairReason}
                className={`border p-3 transition-colors ${
                  !lairReady ? 'opacity-40 cursor-not-allowed border-rule-light bg-card' :
                  selectedAction?.name === a.name
                    ? 'border-ink bg-page-alt cursor-pointer'
                    : 'border-rule-light bg-card hover:bg-page-alt cursor-pointer'
                }`}
                onClick={() => {
                  if (!lairReady) return;
                  if (manual) {
                    void execute(a.name, 'LAIR');
                  } else {
                    setSelectedAction(selectedAction?.name === a.name ? null : a);
                  }
                }}
              >
                <div className="flex items-center gap-2 mb-1">
                  <Castle className="w-4 h-4 text-cls-warlock flex-shrink-0" />
                  <span className="font-heading text-[15px] font-semibold tracking-[0.01em] text-ink">{a.name}</span>
                  {a.saveDC && <span className="font-heading text-[11px] font-semibold tracking-[0.02em] text-muted">DC {a.saveDC} {a.saveAbility?.toUpperCase()}</span>}
                  {dmg && (
                    <span className="font-heading text-[11px] font-semibold tracking-[0.02em] text-muted">
                      {dmg.damageDice} {dmg.damageType}
                    </span>
                  )}
                  {manual && (
                    <span className="font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5 bg-cls-monk-bg text-cls-monk border border-cls-monk/30 ml-auto">Manual</span>
                  )}
                  {!manual && selectedAction?.name === a.name && (
                    <span className="font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5 bg-ink text-card ml-auto">Selected</span>
                  )}
                </div>
                <p className="font-body text-[15px] font-semibold text-muted leading-snug">{a.description}</p>
              </div>
            );
          })}
        </div>
      ) : (
        <div className="flex flex-wrap gap-1 mb-2">
          {currentActions.filter(a => !a.isMultiattack).map(a => {
            const recharged = isRecharged(a);
            const manual = !isAutomatable(a);
            const isLegendary = tab === 'legendary';
            const actionCost = a.cost || 1;
            const legendaryDepleted = isLegendary && legendaryRemaining < actionCost;
            const legendaryOnOwnTurn = isLegendary && ownTurn;
            return (
              <button
                key={a.name}
                disabled={!recharged || executing || legendaryDepleted || legendaryOnOwnTurn}
                onClick={() => {
                  if (legendaryDepleted || legendaryOnOwnTurn || !recharged) return;
                  setMultiattackName(null);
                  if (manual) {
                    void execute(a.name, isLegendary ? 'LEGENDARY' : 'ACTION');
                  } else {
                    setSelectedAction(selectedAction?.name === a.name ? null : a);
                  }
                }}
                className={`px-2 py-1 font-heading text-[12px] font-semibold tracking-[0.02em] border transition-colors
                  ${!recharged || legendaryDepleted || legendaryOnOwnTurn ? 'opacity-40 cursor-not-allowed border-rule-light bg-card text-muted' :
                    manual ? 'border-cls-monk bg-cls-monk-bg text-cls-monk hover:bg-cls-monk/20' :
                    selectedAction?.name === a.name ? 'border-ink bg-ink text-card' :
                    'border-monster bg-monster-bg text-monster hover:bg-monster/20'}`}
                title={legendaryOnOwnTurn ? `Not on ${participant.displayName}'s own turn`
                  : legendaryDepleted ? `Not enough legendary actions (need ${actionCost}, have ${legendaryRemaining})`
                  : (a.description || formatActionLabel(a))}
              >
                {a.name}
                {(a.cost ?? 0) > 0 && <span className="ml-1 text-[11px] opacity-70">({a.cost})</span>}
                {a.recharge && !recharged && <span className="ml-1 text-[11px]">↻</span>}
                {a.recharge && recharged && <span className="ml-1 text-[11px] text-buff">✓</span>}
                {legendaryDepleted && <span className="ml-1 text-[11px]">✗</span>}
              </button>
            );
          })}
        </div>
      )}

      {/* Multiattack buttons: each opens a target picker per attack (D165) */}
      {tab === 'actions' && actions.filter(a => a.isMultiattack).map(a => (
        <button
          key={a.name}
          disabled={executing}
          aria-pressed={multiattackName === a.name}
          onClick={() => { setSelectedAction(null); setMultiattackName(multiattackName === a.name ? null : a.name); }}
          className={`px-2 py-1 font-heading text-[12px] font-semibold tracking-[0.02em] border mr-1 mb-1 ${
            multiattackName === a.name ? 'border-ink bg-ink text-card' : 'border-cls-monk bg-cls-monk-bg text-cls-monk hover:bg-cls-monk/20'}`}
        >
          ⚔ {a.name}
        </button>
      ))}

      {tab === 'actions' && multiattackName && (
        <MultiattackPicker
          monster={participant}
          participants={participants}
          actionName={multiattackName}
          describe={describeAction}
          executing={executing}
          onExecute={componentTargetIds => {
            void execute(multiattackName, 'ACTION', componentTargetIds
              ? { targetParticipantIds: [], componentTargetIds }
              : { targetParticipantIds: [] });
            setMultiattackName(null);
          }}
        />
      )}

      {/* Target selector for automatable actions; a multiattack has its own per attack */}
      {!(tab === 'actions' && multiattackName) && <div className="flex flex-wrap gap-1 mt-2">
        {participants.filter(t => t.id !== participant.id && t.lifeState !== 'DEAD').map(t => (
          <button
            key={t.id}
            onClick={() => toggleTarget(t.id)}
            className={`px-2 py-0.5 font-heading text-[11px] font-semibold tracking-[0.02em] border transition-colors
              ${targets.includes(t.id)
                ? 'bg-monster text-card border-monster'
                : 'bg-card border-rule-light hover:bg-monster-bg'}`}
          >
            {t.displayName}
          </button>
        ))}
      </div>}

      {/* Execute button for the selected automatable action */}
      {selectedAction && (
        <div className="mt-2">
          <button
            disabled={targets.length === 0 || executing
              || (tab === 'legendary' && (ownTurn || legendaryRemaining < (selectedAction.cost || 1)))
              || (tab === 'lair' && !lairReady)}
            onClick={() => {
              if (tab === 'legendary' && (ownTurn || legendaryRemaining < (selectedAction.cost || 1))) return;
              if (tab === 'lair' && !lairReady) return;
              void execute(selectedAction.name, tab === 'legendary' ? 'LEGENDARY' : tab === 'lair' ? 'LAIR' : 'ACTION');
              setSelectedAction(null);
            }}
            className="px-3 py-1 bg-ink hover:bg-ink/90 disabled:bg-page-alt disabled:text-faint text-card font-heading text-[12px] font-semibold tracking-[0.02em]"
          >
            {executing ? 'Executing...' : `Execute ${selectedAction.name}${targets.length > 0 ? ` (${targets.length} target${targets.length !== 1 ? 's' : ''})` : ''}`}
          </button>
        </div>
      )}

      {result && (
        <div className={`mt-2 p-3 border font-body text-[15px] font-semibold ${'error' in result ? 'bg-debuff-bg border-debuff text-debuff' : 'bg-buff-bg border-buff text-buff'}`}>
          {'error' in result ? result.error : (
            <>
              {result.response.description}
              {result.response.targetResults?.map((tr, i) => (
                <div key={`${tr.targetId}-${i}`}>{tr.targetName}: {tr.attackOutcome}{tr.damage > 0 ? ` — ${tr.damage} ${tr.damageType}` : ''}{(tr.conditionsApplied?.length ?? 0) > 0 ? ` — ${tr.conditionsApplied.join(', ')}` : ''}</div>
              ))}
            </>
          )}
          <button className="mt-1 font-body text-[15px] font-semibold text-muted underline hover:text-ink" onClick={() => setResult(null)}>Clear</button>
        </div>
      )}
    </div>
  );
}

function TabBtn({ active, onClick, label }: { active: boolean; onClick: () => void; label: string }) {
  return (
    <button
      onClick={onClick}
      className={`px-2 py-0.5 font-heading text-[11px] font-semibold tracking-[0.02em] transition-colors
        ${active ? 'bg-monster text-card' : 'bg-card border border-rule-light text-muted hover:text-ink'}`}
    >
      {label}
    </button>
  );
}
