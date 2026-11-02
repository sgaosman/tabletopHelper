import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { Copy, Check, Users, ScrollText, Settings } from 'lucide-react';
import { apiErrorMessage } from '../../api/errors';
import { useCampaign, useUpdateCampaignSettings } from '../../queries/campaigns';
import { useCampaignCharacters } from '../../queries/characters';
import NavBar from '../../components/common/NavBar';
import { getClassColour } from '../../utils/classColours';

// Players who join, and their characters, appear without a reload. Like every refetch interval, this one
// pauses while the tab is hidden.
const REFRESH_INTERVAL_MS = 10_000;

export default function CampaignDetailPage() {
  const { campaignId } = useParams<{ campaignId: string }>();
  const campaignQuery = useCampaign(campaignId, { refetchInterval: REFRESH_INTERVAL_MS });
  const charactersQuery = useCampaignCharacters(campaignId, { refetchInterval: REFRESH_INTERVAL_MS });
  const updateSettings = useUpdateCampaignSettings();
  const [copiedCode, setCopiedCode] = useState(false);
  const campaign = campaignQuery.data;
  const characters = charactersQuery.data;

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
            <button onClick={copyInviteCode} className="ml-2 text-faint hover:text-ink transition-colors">
              {copiedCode ? <Check className="w-4 h-4 text-buff" /> : <Copy className="w-4 h-4" />}
            </button>
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
                      </div>
                      <div className="text-right">
                        <p className="font-heading text-[13px] font-semibold text-ink">HP {c.hpCurrent}/{c.hpMax}</p>
                        <p className="font-body text-[13px] text-faint">AC {c.armourClass}</p>
                      </div>
                    </div>
                    <div className="flex gap-4 mt-2">
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
                  </div>
                );
              })}
            </div>
          )}
        </section>
      </main>
    </div>
  );
}
