import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { Copy, Check, Users, ScrollText, Settings, SlidersHorizontal, HeartPulse } from 'lucide-react';
import { apiErrorMessage } from '../../api/errors';
import { useCampaign, useUpdateCampaignSettings } from '../../queries/campaigns';
import { useCampaignCharacters, useReviveCharacter } from '../../queries/characters';
import NavBar from '../../components/common/NavBar';
import DmAdjustmentsModal from '../../components/character/DmAdjustmentsModal';
import Button from '../../components/ui/Button';
import IconButton from '../../components/ui/IconButton';
import { getClassColour } from '../../utils/classColours';
import { activeAdjustments } from '../../utils/dmAdjustments';
import { choicesPending } from '../../utils/pendingChoices';

// Players who join, and their characters, appear without a reload. Like every refetch interval, this one
// pauses while the tab is hidden.
const REFRESH_INTERVAL_MS = 10_000;

export default function CampaignDetailPage() {
  const { campaignId } = useParams<{ campaignId: string }>();
  const campaignQuery = useCampaign(campaignId, { refetchInterval: REFRESH_INTERVAL_MS });
  const charactersQuery = useCampaignCharacters(campaignId, { refetchInterval: REFRESH_INTERVAL_MS });
  const updateSettings = useUpdateCampaignSettings();
  const revive = useReviveCharacter();
  const [copiedCode, setCopiedCode] = useState(false);
  // The character whose DM Adjustments are open, read from the list so the dialog shows each change (D158)
  const [adjustingId, setAdjustingId] = useState<string | null>(null);
  const campaign = campaignQuery.data;
  const characters = charactersQuery.data;
  const adjusting = characters?.find(c => c.id === adjustingId);

  function copyInviteCode() {
    if (!campaign) return;
    navigator.clipboard.writeText(campaign.inviteCode);
    setCopiedCode(true);
    setTimeout(() => setCopiedCode(false), 2000);
  }

  function handleShowExactMonsterHp(showExactMonsterHp: boolean) {
    if (!campaign) return;
    updateSettings.mutate({ campaignId: campaign.id, settings: { showExactMonsterHp } });
  }

  function handlePromptPlayerRepeatSaves(promptPlayerRepeatSaves: boolean) {
    if (!campaign) return;
    updateSettings.mutate({ campaignId: campaign.id, settings: { promptPlayerRepeatSaves } });
  }

  function handleAllowPlayerManualModifiers(allowPlayerManualModifiers: boolean) {
    if (!campaign) return;
    updateSettings.mutate({ campaignId: campaign.id, settings: { allowPlayerManualModifiers } });
  }

  function handleRevive(characterId: string) {
    if (!campaign) return;
    revive.mutate({ characterId, campaignId: campaign.id });
  }

  function modifierString(score: number) {
    const mod = Math.floor((score - 10) / 2);
    return mod >= 0 ? `+${mod}` : `${mod}`;
  }

  if (campaignQuery.isPending) return <div className="min-h-screen bg-page"><NavBar /><div className="flex items-center justify-center py-20"><p className="font-body text-[16px] text-muted">Loading...</p></div></div>;
  if (!campaign) return <div className="min-h-screen bg-page"><NavBar /><div className="flex items-center justify-center py-20"><p role="alert" className="font-body text-[16px] text-debuff">{apiErrorMessage(campaignQuery.error)}</p></div></div>;

  return (
    <div className="min-h-screen bg-page">
      <NavBar />

      <main className="max-w-4xl mx-auto px-6 py-8">
        <div className="mb-8">
          <h1 className="font-heading text-[19px] font-semibold tracking-[0.02em] text-ink mb-1">{campaign.name}</h1>
          {campaign.description && <p className="font-body text-[15px] font-semibold text-muted">{campaign.description}</p>}

          <div className="flex items-center gap-2 mt-4 bg-card border border-rule px-4 py-2.5 w-fit">
            <span className="font-heading text-[11px] font-semibold tracking-[0.08em] uppercase text-faint">Invite Code</span>
            <span className="font-heading text-[17px] font-bold tracking-wider text-ink">{campaign.inviteCode}</span>
            <IconButton label={copiedCode ? 'Invite code copied' : 'Copy invite code'} onClick={copyInviteCode} className="ml-2 text-faint hover:text-ink transition-colors">
              {copiedCode ? <Check className="w-4 h-4 text-buff" /> : <Copy className="w-4 h-4" />}
            </IconButton>
          </div>
        </div>

        <section className="mb-8">
          <h2 className="font-heading text-[13px] font-semibold tracking-[0.1em] uppercase text-faint mb-3 flex items-center gap-2">
            <Settings className="w-3.5 h-3.5" /> Encounter Settings
          </h2>
          <label className="bg-card border border-rule p-3 flex items-start gap-3 cursor-pointer w-fit">
            <input
              type="checkbox"
              checked={campaign.showExactMonsterHp}
              onChange={e => handleShowExactMonsterHp(e.target.checked)}
              disabled={updateSettings.isPending}
              className="mt-1 accent-ink"
            />
            <span>
              <span className="block font-heading text-[15px] font-bold text-ink">Show exact monster HP</span>
              <span className="block font-body text-[13px] font-semibold text-faint">
                Players see monster HP as numbers. When off, they see Unharmed, Injured, Bloodied or Down.
              </span>
            </span>
          </label>
          <label className="bg-card border border-rule p-3 flex items-start gap-3 cursor-pointer w-fit mt-2">
            <input
              type="checkbox"
              checked={campaign.promptPlayerRepeatSaves}
              onChange={e => handlePromptPlayerRepeatSaves(e.target.checked)}
              disabled={updateSettings.isPending}
              className="mt-1 accent-ink"
            />
            <span>
              <span className="block font-heading text-[15px] font-bold text-ink">Players roll their own Repeat Saves</span>
              <span className="block font-body text-[13px] font-semibold text-faint">
                At the end of their character's turn, players are prompted to roll the save that ends a condition. When off, the app rolls it for them.
              </span>
            </span>
          </label>
          {/* D164: the DM can always enter Manual Modifiers; this decides whether players can */}
          <label className="bg-card border border-rule p-3 flex items-start gap-3 cursor-pointer w-fit mt-2">
            <input
              type="checkbox"
              checked={campaign.allowPlayerManualModifiers}
              onChange={e => handleAllowPlayerManualModifiers(e.target.checked)}
              disabled={updateSettings.isPending}
              className="mt-1 accent-ink"
            />
            <span>
              <span className="block font-heading text-[15px] font-bold text-ink">Allow players Manual Modifiers</span>
              <span className="block font-body text-[13px] font-semibold text-faint">
                Players can type their own spell attack bonus and save DC, and force a critical hit. Each use shows in the combat log.
              </span>
            </span>
          </label>
          {updateSettings.error && (
            <p role="alert" className="font-body text-[13px] font-semibold text-debuff mt-2">{apiErrorMessage(updateSettings.error)}</p>
          )}
        </section>

        <section className="mb-8">
          <h2 className="font-heading text-[13px] font-semibold tracking-[0.1em] uppercase text-faint mb-3 flex items-center gap-2">
            <Users className="w-3.5 h-3.5" /> Members ({campaign.members.length})
          </h2>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            {campaign.members.map((m) => (
              <div key={m.userId} className="bg-card border border-rule p-3 flex items-center justify-between">
                <div>
                  <p className="font-heading text-[15px] font-bold text-ink">{m.displayName}</p>
                  <p className="font-body text-[13px] font-semibold text-faint">@{m.username}</p>
                </div>
                <span className={`font-heading text-[11px] font-semibold tracking-[0.04em] px-2 py-0.5 border ${m.role === 'DM' ? 'text-cls-cleric bg-cls-cleric-bg border-cls-cleric-border' : 'text-cls-wizard bg-cls-wizard-bg border-abj-border'}`}>
                  {m.role}
                </span>
              </div>
            ))}
          </div>
        </section>

        <section>
          <h2 className="font-heading text-[13px] font-semibold tracking-[0.1em] uppercase text-faint mb-3 flex items-center gap-2">
            <ScrollText className="w-3.5 h-3.5" /> Characters{characters && ` (${characters.length})`}
          </h2>
          {charactersQuery.isPending ? (
            <p className="font-body text-[15px] text-muted">Loading...</p>
          ) : !characters ? (
            <p role="alert" className="font-body text-[15px] text-debuff">{apiErrorMessage(charactersQuery.error)}</p>
          ) : characters.length === 0 ? (
            <p className="font-body text-[15px] text-faint">No characters assigned to this campaign yet.</p>
          ) : (
            <div className="space-y-2">
              {characters.map((c) => {
                const clsColour = getClassColour(c.characterClass);
                const adjustments = activeAdjustments(c.dmAdjustments).length;
                const adjustmentsLabel = `Adjustments${adjustments > 0 ? ` (${adjustments})` : ''}`;
                return (
                  <div key={c.id} className="bg-card border border-rule p-4"
                    style={{ borderLeftWidth: '3px', borderLeftColor: clsColour }}>
                    <div className="flex items-center justify-between mb-2">
                      <div>
                        <h3 className="font-heading text-[16px] font-bold" style={{ color: clsColour }}>{c.name}</h3>
                        <p className="font-body text-[14px] font-semibold text-muted">
                          Level {c.level} {c.race} {c.characterClass}{c.subclass ? ` (${c.subclass})` : ''}
                          <span className="text-faint"> — played by {c.ownerDisplayName}</span>
                        </p>
                        {/* The choices its levels owe, which only its player makes (D159) */}
                        {c.pendingChoices.length > 0 && (
                          <span className="inline-block mt-1 font-heading text-[11px] font-semibold tracking-[0.04em] px-2 py-0.5 border text-cls-monk bg-cls-monk-bg border-div-border">
                            {choicesPending(c.pendingChoices.length)}
                          </span>
                        )}
                        {/* Died in an encounter, until the DM revives them (D166) */}
                        {c.dead && (
                          <span className="inline-block mt-1 ml-1 font-heading text-[11px] font-semibold tracking-[0.04em] px-2 py-0.5 bg-debuff text-card">
                            Dead
                          </span>
                        )}
                      </div>
                      <div className="text-right">
                        <p className="font-heading text-[13px] font-semibold text-ink">HP {c.hpCurrent}/{c.hpMax}</p>
                        <p className="font-body text-[13px] text-faint">AC {c.armourClass}</p>
                      </div>
                    </div>
                    <div className="flex items-end justify-between gap-4 mt-2 flex-wrap">
                      <div className="flex gap-4 flex-wrap">
                        {(['strength','dexterity','constitution','intelligence','wisdom','charisma'] as const).map(stat => {
                          const val = c[stat];
                          const label = stat.slice(0, 3).toUpperCase();
                          return (
                            <span key={stat} className="font-body text-[13px] text-muted">
                              <span className="font-heading text-[11px] font-semibold tracking-[0.04em] text-faint">{label}</span>{' '}
                              <span className="font-heading text-[13px] font-bold text-ink">{val}</span>{' '}
                              <span className="text-faint">({modifierString(val)})</span>
                            </span>
                          );
                        })}
                      </div>
                      <div className="flex gap-2">
                        {/* Brings a character who died back at 1 hit point (D166) */}
                        {c.dead && (
                          <Button size="sm" variant="primary" onClick={() => handleRevive(c.id)}
                            loading={revive.isPending && revive.variables?.characterId === c.id}
                            aria-label={`Revive ${c.name}`}>
                            <HeartPulse className="w-3.5 h-3.5" />
                            Revive
                          </Button>
                        )}
                        {/* The DM's adjustments to the character (D158); the name says whose, as every card has one */}
                        <Button size="sm" variant="secondary" onClick={() => setAdjustingId(c.id)}
                          aria-label={`${adjustmentsLabel} for ${c.name}`}>
                          <SlidersHorizontal className="w-3.5 h-3.5" />
                          {adjustmentsLabel}
                        </Button>
                      </div>
                    </div>
                    {revive.isError && revive.variables?.characterId === c.id && (
                      <p role="alert" className="font-body text-[13px] font-semibold text-debuff mt-2">
                        {apiErrorMessage(revive.error)}
                      </p>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </section>
      </main>

      {adjusting && <DmAdjustmentsModal character={adjusting} onClose={() => setAdjustingId(null)} />}
    </div>
  );
}
