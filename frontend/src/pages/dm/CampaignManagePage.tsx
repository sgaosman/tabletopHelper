import { useState, useEffect, FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, Plus, Copy, Users, Check } from 'lucide-react';
import { campaignApi } from '../../api/campaignApi';
import type { Campaign } from '../../types/campaign';

export default function CampaignManagePage() {
  const [campaigns, setCampaigns] = useState<Campaign[]>([]);
  const [showCreate, setShowCreate] = useState(false);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [copiedCode, setCopiedCode] = useState<string | null>(null);
  const navigate = useNavigate();

  useEffect(() => {
    loadCampaigns();
  }, []);

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
    <div className="min-h-screen bg-gray-950">
      <header className="sticky top-0 z-10 bg-gray-950 border-b border-gray-800 px-6 py-4 flex items-center justify-between">
        <button onClick={() => navigate('/dm')} className="flex items-center gap-2 text-gray-400 hover:text-white transition-colors">
          <ArrowLeft className="w-4 h-4" /> Back to Dashboard
        </button>
        <button
          onClick={() => setShowCreate(true)}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-500 text-white rounded-lg text-sm transition-colors"
        >
          <Plus className="w-4 h-4" /> New Campaign
        </button>
      </header>

      <main className="max-w-4xl mx-auto px-6 py-8">
        <h1 className="text-2xl font-bold text-white mb-6">Campaigns</h1>

        {error && (
          <div className="bg-red-900/50 border border-red-700 text-red-300 rounded-lg p-3 mb-4 text-sm">{error}</div>
        )}

        {showCreate && (
          <form onSubmit={handleCreate} className="bg-gray-900 border border-gray-800 rounded-xl p-6 mb-6 space-y-4">
            <h2 className="text-lg font-semibold text-white">Create Campaign</h2>
            <div>
              <label className="block text-sm font-medium text-gray-300 mb-1">Campaign Name</label>
              <input
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                required
                maxLength={200}
                className="w-full px-4 py-2.5 bg-gray-800 border border-gray-700 rounded-lg text-white placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-indigo-500"
                placeholder="e.g. Curse of Strahd"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-300 mb-1">Description <span className="text-gray-500">(optional)</span></label>
              <textarea
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                rows={3}
                className="w-full px-4 py-2.5 bg-gray-800 border border-gray-700 rounded-lg text-white placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-indigo-500 resize-none"
                placeholder="A brief description of your campaign"
              />
            </div>
            <div className="flex gap-3">
              <button type="submit" className="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 text-white rounded-lg text-sm transition-colors">Create</button>
              <button type="button" onClick={() => setShowCreate(false)} className="px-4 py-2 bg-gray-800 hover:bg-gray-700 text-gray-300 rounded-lg text-sm transition-colors">Cancel</button>
            </div>
          </form>
        )}

        {loading ? (
          <p className="text-gray-400">Loading campaigns...</p>
        ) : campaigns.length === 0 ? (
          <div className="text-center py-16">
            <Users className="w-12 h-12 text-gray-600 mx-auto mb-4" />
            <p className="text-gray-400 mb-2">No campaigns yet</p>
            <p className="text-gray-500 text-sm">Create your first campaign to get started</p>
          </div>
        ) : (
          <div className="space-y-4">
            {campaigns.map((campaign) => (
              <div key={campaign.id} className="bg-gray-900 border border-gray-800 rounded-xl p-6">
                <div className="flex items-start justify-between mb-3">
                  <div>
                    <h3 className="text-lg font-semibold text-white">{campaign.name}</h3>
                    {campaign.description && <p className="text-gray-400 text-sm mt-1">{campaign.description}</p>}
                  </div>
                  <button
                    onClick={() => navigate(`/dm/campaigns/${campaign.id}`)}
                    className="text-indigo-400 hover:text-indigo-300 text-sm transition-colors"
                  >
                    View Details
                  </button>
                </div>

                <div className="flex items-center gap-4 mt-4">
                  <div className="flex items-center gap-2 bg-gray-800 px-3 py-1.5 rounded-lg">
                    <span className="text-gray-400 text-xs">INVITE CODE</span>
                    <span className="text-white font-mono font-bold tracking-wider">{campaign.inviteCode}</span>
                    <button onClick={() => copyInviteCode(campaign.inviteCode)} className="text-gray-400 hover:text-white transition-colors">
                      {copiedCode === campaign.inviteCode ? <Check className="w-4 h-4 text-green-400" /> : <Copy className="w-4 h-4" />}
                    </button>
                  </div>
                  <span className="text-gray-500 text-sm">
                    {campaign.members.length} member{campaign.members.length !== 1 ? 's' : ''}
                  </span>
                </div>

                <div className="mt-4 flex flex-wrap gap-2">
                  {campaign.members.map((m) => (
                    <span key={m.userId} className="inline-flex items-center gap-1 px-2 py-1 bg-gray-800 rounded-md text-xs">
                      <span className="text-white">{m.displayName}</span>
                      <span className={`${m.role === 'DM' ? 'text-amber-400' : 'text-indigo-400'}`}>{m.role}</span>
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
