import { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Copy, Check, Users, ScrollText } from 'lucide-react';
import { campaignApi } from '../../api/campaignApi';
import { characterApi } from '../../api/characterApi';
import type { Campaign } from '../../types/campaign';
import type { PlayerCharacter } from '../../types/character';

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

  if (loading) return <div className="min-h-screen bg-gray-950 flex items-center justify-center"><p className="text-gray-400">Loading...</p></div>;
  if (!campaign) return <div className="min-h-screen bg-gray-950 flex items-center justify-center"><p className="text-red-400">Campaign not found</p></div>;

  return (
    <div className="min-h-screen bg-gray-950">
      <header className="sticky top-0 z-10 bg-gray-950 border-b border-gray-800 px-6 py-4">
        <button onClick={() => navigate('/dm/campaigns')} className="flex items-center gap-2 text-gray-400 hover:text-white transition-colors">
          <ArrowLeft className="w-4 h-4" /> Back to Campaigns
        </button>
      </header>

      <main className="max-w-4xl mx-auto px-6 py-8">
        <div className="mb-8">
          <h1 className="text-3xl font-bold text-white mb-2">{campaign.name}</h1>
          {campaign.description && <p className="text-gray-400">{campaign.description}</p>}

          <div className="flex items-center gap-2 mt-4 bg-gray-900 border border-gray-800 px-4 py-3 rounded-lg w-fit">
            <span className="text-gray-400 text-sm">Invite Code:</span>
            <span className="text-white font-mono font-bold tracking-wider text-lg">{campaign.inviteCode}</span>
            <button onClick={copyInviteCode} className="ml-2 text-gray-400 hover:text-white transition-colors">
              {copiedCode ? <Check className="w-5 h-5 text-green-400" /> : <Copy className="w-5 h-5" />}
            </button>
          </div>
        </div>

        <section className="mb-8">
          <h2 className="text-xl font-semibold text-white mb-4 flex items-center gap-2">
            <Users className="w-5 h-5 text-gray-400" /> Members ({campaign.members.length})
          </h2>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            {campaign.members.map((m) => (
              <div key={m.userId} className="bg-gray-900 border border-gray-800 rounded-lg p-4 flex items-center justify-between">
                <div>
                  <p className="text-white font-medium">{m.displayName}</p>
                  <p className="text-gray-500 text-sm">@{m.username}</p>
                </div>
                <span className={`px-2 py-0.5 rounded text-xs font-medium ${m.role === 'DM' ? 'bg-amber-900/50 text-amber-400' : 'bg-indigo-900/50 text-indigo-400'}`}>
                  {m.role}
                </span>
              </div>
            ))}
          </div>
        </section>

        <section>
          <h2 className="text-xl font-semibold text-white mb-4 flex items-center gap-2">
            <ScrollText className="w-5 h-5 text-gray-400" /> Characters ({characters.length})
          </h2>
          {characters.length === 0 ? (
            <p className="text-gray-500 text-sm">No characters assigned to this campaign yet.</p>
          ) : (
            <div className="space-y-3">
              {characters.map((c) => (
                <div key={c.id} className="bg-gray-900 border border-gray-800 rounded-lg p-4">
                  <div className="flex items-center justify-between mb-2">
                    <div>
                      <h3 className="text-white font-semibold">{c.name}</h3>
                      <p className="text-gray-400 text-sm">
                        Level {c.level} {c.race} {c.characterClass}{c.subclass ? ` (${c.subclass})` : ''}
                        <span className="text-gray-600"> — played by {c.ownerDisplayName}</span>
                      </p>
                    </div>
                    <div className="text-right">
                      <p className="text-white text-sm">HP {c.hpCurrent}/{c.hpMax}</p>
                      <p className="text-gray-400 text-xs">AC {c.armourClass}</p>
                    </div>
                  </div>
                  <div className="flex gap-4 text-xs text-gray-400 mt-2">
                    <span>STR {c.strength} ({modifierString(c.strength)})</span>
                    <span>DEX {c.dexterity} ({modifierString(c.dexterity)})</span>
                    <span>CON {c.constitution} ({modifierString(c.constitution)})</span>
                    <span>INT {c.intelligence} ({modifierString(c.intelligence)})</span>
                    <span>WIS {c.wisdom} ({modifierString(c.wisdom)})</span>
                    <span>CHA {c.charisma} ({modifierString(c.charisma)})</span>
                  </div>
                </div>
              ))}
            </div>
          )}
        </section>
      </main>
    </div>
  );
}
