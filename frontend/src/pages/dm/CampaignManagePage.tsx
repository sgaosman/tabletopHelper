import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { Plus, Copy, Users, Check } from 'lucide-react';
import { apiErrorMessage } from '../../api/errors';
import { useCampaigns, useCreateCampaign } from '../../queries/campaigns';
import NavBar from '../../components/common/NavBar';
import IconButton from '../../components/ui/IconButton';

export default function CampaignManagePage() {
  const { data: campaigns, error: loadError, isPending } = useCampaigns();
  const createCampaign = useCreateCampaign();
  const [showCreate, setShowCreate] = useState(false);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [copiedCode, setCopiedCode] = useState<string | null>(null);
  const navigate = useNavigate();

  function handleCreate(e: FormEvent) {
    e.preventDefault();
    createCampaign.mutate({ name, description: description || undefined }, {
      onSuccess: () => {
        setName('');
        setDescription('');
        setShowCreate(false);
      },
    });
  }

  // Opening or closing the form forgets why the last attempt failed
  function toggleCreateForm(show: boolean) {
    createCampaign.reset();
    setShowCreate(show);
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
            onClick={() => toggleCreateForm(true)}
            className="flex items-center gap-1.5 px-3 py-1.5 bg-ink text-card font-body text-[15px] font-semibold border border-ink hover:bg-muted hover:border-muted transition-colors"
          >
            <Plus className="w-3.5 h-3.5" /> New Campaign
          </button>
        </div>

        {showCreate && (
          <form onSubmit={handleCreate} className="bg-card border border-rule p-6 mb-6 space-y-4">
            <h2 className="font-heading text-[17px] font-semibold text-ink">Create Campaign</h2>
            <div>
              <label className="block font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-faint mb-1.5">Campaign Name</label>
              <input
                type="text" value={name} onChange={(e) => setName(e.target.value)} required maxLength={200}
                className="w-full px-3 py-2 bg-card border border-rule font-body text-[16px] font-semibold text-ink placeholder-faint focus:border-muted focus:outline-none"
                placeholder="e.g. Curse of Strahd"
              />
            </div>
            <div>
              <label className="block font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-faint mb-1.5">
                Description <span className="text-faint normal-case tracking-normal font-body">(optional)</span>
              </label>
              <textarea
                value={description} onChange={(e) => setDescription(e.target.value)} rows={3}
                className="w-full px-3 py-2 bg-card border border-rule font-body text-[16px] font-semibold text-ink placeholder-faint focus:border-muted focus:outline-none resize-none"
                placeholder="A brief description of your campaign"
              />
            </div>
            {createCampaign.error && (
              <p role="alert" className="text-debuff font-body text-[15px]">{apiErrorMessage(createCampaign.error)}</p>
            )}
            <div className="flex gap-3">
              <button type="submit" disabled={createCampaign.isPending} className="px-4 py-2 bg-ink text-card font-body text-[15px] font-semibold border border-ink hover:bg-muted hover:border-muted transition-colors disabled:opacity-50 disabled:cursor-not-allowed">Create</button>
              <button type="button" onClick={() => toggleCreateForm(false)} className="px-4 py-2 bg-page border border-rule font-body text-[15px] font-semibold text-muted hover:border-muted transition-colors">Cancel</button>
            </div>
          </form>
        )}

        {isPending ? (
          <p className="font-body text-[16px] text-muted">Loading campaigns...</p>
        ) : !campaigns ? (
          <div role="alert" className="bg-debuff-bg border border-debuff-border text-debuff font-body text-[15px] p-3">{apiErrorMessage(loadError)}</div>
        ) : campaigns.length === 0 ? (
          <div className="text-center py-16">
            <Users className="w-10 h-10 text-faint mx-auto mb-4" />
            <p className="font-body text-[16px] text-muted mb-1">No campaigns yet</p>
            <p className="font-body text-[14px] text-faint">Create your first campaign to get started</p>
          </div>
        ) : (
          <div className="space-y-3">
            {campaigns.map((campaign) => (
              <div key={campaign.id} className="bg-card border border-rule p-5">
                <div className="flex items-start justify-between mb-3">
                  <div>
                    <h3 className="font-heading text-[17px] font-semibold text-ink">{campaign.name}</h3>
                    {campaign.description && <p className="font-body text-[15px] font-semibold text-muted mt-1">{campaign.description}</p>}
                  </div>
                  <button
                    onClick={() => navigate(`/dm/campaigns/${campaign.id}`)}
                    className="font-body text-[15px] font-semibold text-ink hover:text-muted transition-colors"
                  >
                    View Details
                  </button>
                </div>

                <div className="flex items-center gap-4 mt-4">
                  <div className="flex items-center gap-2 bg-page border border-rule px-3 py-1.5">
                    <span className="font-heading text-[11px] font-semibold tracking-[0.08em] uppercase text-faint">Invite Code</span>
                    <span className="font-heading text-[15px] font-bold tracking-wider text-ink">{campaign.inviteCode}</span>
                    <IconButton
                      label={copiedCode === campaign.inviteCode ? 'Invite code copied' : `Copy invite code for ${campaign.name}`}
                      onClick={() => copyInviteCode(campaign.inviteCode)}
                      className="text-faint hover:text-ink transition-colors"
                    >
                      {copiedCode === campaign.inviteCode ? <Check className="w-3.5 h-3.5 text-buff" /> : <Copy className="w-3.5 h-3.5" />}
                    </IconButton>
                  </div>
                  <span className="font-body text-[14px] font-semibold text-muted">
                    {campaign.members.length} member{campaign.members.length !== 1 ? 's' : ''}
                  </span>
                </div>

                <div className="mt-3 flex flex-wrap gap-2">
                  {campaign.members.map((m) => (
                    <span key={m.userId} className="inline-flex items-center gap-1.5 px-2 py-0.5 bg-page border border-rule">
                      <span className="font-body text-[13px] font-semibold text-ink">{m.displayName}</span>
                      <span className={`font-heading text-[11px] font-semibold ${m.role === 'DM' ? 'text-cls-cleric' : 'text-cls-wizard'}`}>{m.role}</span>
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
