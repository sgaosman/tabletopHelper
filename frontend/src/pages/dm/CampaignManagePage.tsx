import { useState, useEffect, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { Plus, Copy, Users, Check } from 'lucide-react';
import { campaignApi } from '../../api/campaignApi';
import type { Campaign } from '../../types/campaign';
import NavBar from '../../components/common/NavBar';

export default function CampaignManagePage() {
  const [campaigns, setCampaigns] = useState<Campaign[]>([]);
  const [showCreate, setShowCreate] = useState(false);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [copiedCode, setCopiedCode] = useState<string | null>(null);
  const navigate = useNavigate();

  useEffect(() => { loadCampaigns(); }, []);

  async function loadCampaigns() {
    try {
      const res = await campaignApi.getAll();
      setCampaigns(res.data);
    } catch {
      setError('Failed to load campaigns');
    } finally {
      setLoading(false);
    }
  }

  async function handleCreate(e: FormEvent) {
    e.preventDefault();
    setError('');
    try {
      await campaignApi.create({ name, description: description || undefined });
      setName('');
      setDescription('');
      setShowCreate(false);
      loadCampaigns();
    } catch (err: any) {
      setError(err.response?.data?.error || 'Failed to create campaign');
    }
  }

  function copyInviteCode(code: string) {
    navigator.clipboard.writeText(code);
    setCopiedCode(code);
    setTimeout(() => setCopiedCode(null), 2000);
  }

  return (
    <div className="min-h-screen bg-page">
      <NavBar />

      <main className="max-w-4xl mx-auto px-6 py-8">
        <div className="flex items-center justify-between mb-6">
          <h1 className="font-heading text-[19px] font-semibold tracking-[0.02em] text-ink">Campaigns</h1>
          <button
            onClick={() => setShowCreate(true)}
            className="flex items-center gap-1.5 px-3 py-1.5 bg-ink text-card font-body text-[13px] font-medium border border-ink hover:bg-muted hover:border-muted transition-colors"
          >
            <Plus className="w-3.5 h-3.5" /> New Campaign
          </button>
        </div>

        {error && (
          <div className="bg-debuff-bg border border-debuff-border text-debuff font-body text-[13px] p-3 mb-4">{error}</div>
        )}

        {showCreate && (
          <form onSubmit={handleCreate} className="bg-card border border-rule p-6 mb-6 space-y-4">
            <h2 className="font-heading text-[15px] font-semibold text-ink">Create Campaign</h2>
            <div>
              <label className="block font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-faint mb-1.5">Campaign Name</label>
              <input
                type="text" value={name} onChange={(e) => setName(e.target.value)} required maxLength={200}
                className="w-full px-3 py-2 bg-card border border-rule font-body text-[14px] font-medium text-ink placeholder-faint focus:border-muted focus:outline-none"
                placeholder="e.g. Curse of Strahd"
              />
            </div>
            <div>
              <label className="block font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-faint mb-1.5">
                Description <span className="text-faint normal-case tracking-normal font-body">(optional)</span>
              </label>
              <textarea
                value={description} onChange={(e) => setDescription(e.target.value)} rows={3}
                className="w-full px-3 py-2 bg-card border border-rule font-body text-[14px] font-medium text-ink placeholder-faint focus:border-muted focus:outline-none resize-none"
                placeholder="A brief description of your campaign"
              />
            </div>
            <div className="flex gap-3">
              <button type="submit" className="px-4 py-2 bg-ink text-card font-body text-[13px] font-medium border border-ink hover:bg-muted hover:border-muted transition-colors">Create</button>
              <button type="button" onClick={() => setShowCreate(false)} className="px-4 py-2 bg-page border border-rule font-body text-[13px] font-medium text-muted hover:border-muted transition-colors">Cancel</button>
            </div>
          </form>
        )}

        {loading ? (
          <p className="font-body text-[14px] text-muted">Loading campaigns...</p>
        ) : campaigns.length === 0 ? (
          <div className="text-center py-16">
            <Users className="w-10 h-10 text-faint mx-auto mb-4" />
            <p className="font-body text-[14px] text-muted mb-1">No campaigns yet</p>
            <p className="font-body text-[12px] text-faint">Create your first campaign to get started</p>
          </div>
        ) : (
          <div className="space-y-3">
            {campaigns.map((campaign) => (
              <div key={campaign.id} className="bg-card border border-rule p-5">
                <div className="flex items-start justify-between mb-3">
                  <div>
                    <h3 className="font-heading text-[15px] font-semibold text-ink">{campaign.name}</h3>
                    {campaign.description && <p className="font-body text-[13px] font-medium text-muted mt-1">{campaign.description}</p>}
                  </div>
                  <button
                    onClick={() => navigate(`/dm/campaigns/${campaign.id}`)}
                    className="font-body text-[13px] font-semibold text-ink hover:text-muted transition-colors"
                  >
                    View Details
                  </button>
                </div>

                <div className="flex items-center gap-4 mt-4">
                  <div className="flex items-center gap-2 bg-page border border-rule px-3 py-1.5">
                    <span className="font-heading text-[9px] font-semibold tracking-[0.08em] uppercase text-faint">Invite Code</span>
                    <span className="font-heading text-[13px] font-bold tracking-wider text-ink">{campaign.inviteCode}</span>
                    <button onClick={() => copyInviteCode(campaign.inviteCode)} className="text-faint hover:text-ink transition-colors">
                      {copiedCode === campaign.inviteCode ? <Check className="w-3.5 h-3.5 text-buff" /> : <Copy className="w-3.5 h-3.5" />}
                    </button>
                  </div>
                  <span className="font-body text-[12px] font-medium text-muted">
                    {campaign.members.length} member{campaign.members.length !== 1 ? 's' : ''}
                  </span>
                </div>

                <div className="mt-3 flex flex-wrap gap-2">
                  {campaign.members.map((m) => (
                    <span key={m.userId} className="inline-flex items-center gap-1.5 px-2 py-0.5 bg-page border border-rule">
                      <span className="font-body text-[11px] font-medium text-ink">{m.displayName}</span>
                      <span className={`font-heading text-[9px] font-medium ${m.role === 'DM' ? 'text-cls-cleric' : 'text-cls-wizard'}`}>{m.role}</span>
                    </span>
                  ))}
                </div>
              </div>
            ))}
          </div>
        )}
      </main>
    </div>
  );
}
