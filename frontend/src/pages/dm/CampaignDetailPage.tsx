import { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Copy, Check, Users, ScrollText } from 'lucide-react';
import { campaignApi } from '../../api/campaignApi';
import { characterApi } from '../../api/characterApi';
import type { Campaign } from '../../types/campaign';
import type { PlayerCharacter } from '../../types/character';
import NavBar from '../../components/common/NavBar';
import { getClassColour } from '../../utils/classColours';

export default function CampaignDetailPage() {
  const { campaignId } = useParams<{ campaignId: string }>();
  const [campaign, setCampaign] = useState<Campaign | null>(null);
  const [characters, setCharacters] = useState<PlayerCharacter[]>([]);
  const [loading, setLoading] = useState(true);
  const [copiedCode, setCopiedCode] = useState(false);
  const navigate = useNavigate();

  const fetchData = useCallback(() => {
    if (!campaignId) return;
    Promise.all([
      campaignApi.getById(campaignId),
      characterApi.getByCampaign(campaignId),
    ]).then(([campRes, charRes]) => {
      setCampaign(campRes.data);
      setCharacters(charRes.data);
    }).finally(() => setLoading(false));
  }, [campaignId]);

  useEffect(() => {
    fetchData();
    const interval = setInterval(fetchData, 10000);
    return () => clearInterval(interval);
  }, [fetchData]);

  function copyInviteCode() {
    if (!campaign) return;
    navigator.clipboard.writeText(campaign.inviteCode);
    setCopiedCode(true);
    setTimeout(() => setCopiedCode(false), 2000);
  }

  function modifierString(score: number) {
    const mod = Math.floor((score - 10) / 2);
    return mod >= 0 ? `+${mod}` : `${mod}`;
  }

  if (loading) return <div className="min-h-screen bg-page"><NavBar /><div className="flex items-center justify-center py-20"><p className="font-body text-[14px] text-muted">Loading...</p></div></div>;
  if (!campaign) return <div className="min-h-screen bg-page"><NavBar /><div className="flex items-center justify-center py-20"><p className="font-body text-[14px] text-debuff">Campaign not found</p></div></div>;

  return (
    <div className="min-h-screen bg-page">
      <NavBar />

      <main className="max-w-4xl mx-auto px-6 py-8">
        <div className="mb-8">
          <h1 className="font-heading text-[19px] font-semibold tracking-[0.02em] text-ink mb-1">{campaign.name}</h1>
          {campaign.description && <p className="font-body text-[13px] font-medium text-muted">{campaign.description}</p>}

          <div className="flex items-center gap-2 mt-4 bg-card border border-rule px-4 py-2.5 w-fit">
            <span className="font-heading text-[9px] font-semibold tracking-[0.08em] uppercase text-faint">Invite Code</span>
            <span className="font-heading text-[15px] font-bold tracking-wider text-ink">{campaign.inviteCode}</span>
            <button onClick={copyInviteCode} className="ml-2 text-faint hover:text-ink transition-colors">
              {copiedCode ? <Check className="w-4 h-4 text-buff" /> : <Copy className="w-4 h-4" />}
            </button>
          </div>
        </div>

        <section className="mb-8">
          <h2 className="font-heading text-[11px] font-semibold tracking-[0.1em] uppercase text-faint mb-3 flex items-center gap-2">
            <Users className="w-3.5 h-3.5" /> Members ({campaign.members.length})
          </h2>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            {campaign.members.map((m) => (
              <div key={m.userId} className="bg-card border border-rule p-3 flex items-center justify-between">
                <div>
                  <p className="font-heading text-[13px] font-semibold text-ink">{m.displayName}</p>
                  <p className="font-body text-[11px] font-medium text-faint">@{m.username}</p>
                </div>
                <span className={`font-heading text-[9px] font-medium tracking-[0.04em] px-2 py-0.5 border ${m.role === 'DM' ? 'text-cls-cleric bg-cls-cleric-bg border-[#E8DCC4]' : 'text-cls-wizard bg-cls-wizard-bg border-abj-border'}`}>
                  {m.role}
                </span>
              </div>
            ))}
          </div>
        </section>

        <section>
          <h2 className="font-heading text-[11px] font-semibold tracking-[0.1em] uppercase text-faint mb-3 flex items-center gap-2">
            <ScrollText className="w-3.5 h-3.5" /> Characters ({characters.length})
          </h2>
          {characters.length === 0 ? (
            <p className="font-body text-[13px] text-faint">No characters assigned to this campaign yet.</p>
          ) : (
            <div className="space-y-2">
              {characters.map((c) => {
                const clsColour = getClassColour(c.characterClass);
                return (
                  <div key={c.id} className="bg-card border border-rule p-4"
                    style={{ borderLeftWidth: '3px', borderLeftColor: clsColour }}>
                    <div className="flex items-center justify-between mb-2">
                      <div>
                        <h3 className="font-heading text-[14px] font-bold" style={{ color: clsColour }}>{c.name}</h3>
                        <p className="font-body text-[12px] font-medium text-muted">
                          Level {c.level} {c.race} {c.characterClass}{c.subclass ? ` (${c.subclass})` : ''}
                          <span className="text-faint"> — played by {c.ownerDisplayName}</span>
                        </p>
                      </div>
                      <div className="text-right">
                        <p className="font-heading text-[11px] font-semibold text-ink">HP {c.hpCurrent}/{c.hpMax}</p>
                        <p className="font-body text-[11px] text-faint">AC {c.armourClass}</p>
                      </div>
                    </div>
                    <div className="flex gap-4 mt-2">
                      {(['strength','dexterity','constitution','intelligence','wisdom','charisma'] as const).map(stat => {
                        const val = c[stat];
                        const label = stat.slice(0, 3).toUpperCase();
                        return (
                          <span key={stat} className="font-body text-[11px] text-muted">
                            <span className="font-heading text-[9px] font-semibold tracking-[0.04em] text-faint">{label}</span>{' '}
                            <span className="font-heading text-[11px] font-bold text-ink">{val}</span>{' '}
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
