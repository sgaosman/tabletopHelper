import { Link, useNavigate } from 'react-router-dom';
import { BookOpen, ScrollText, Shield, Swords, Users, FlaskConical } from 'lucide-react';
import NavBar from '../../components/common/NavBar';
import { apiErrorMessage } from '../../api/errors';
import { useRunningEncounters } from '../../queries/encounters';
import type { RunningEncounter } from '../../types/encounter';

const cards = [
  { title: 'Campaigns', description: 'Manage your campaigns and players', icon: Users, path: '/dm/campaigns', colour: 'var(--color-abj)' },
  { title: 'Bestiary', description: 'Browse monsters from all sourcebooks', icon: Swords, path: '/dm/bestiary', colour: 'var(--color-monster)' },
  { title: 'Spells', description: 'Search the complete spell list', icon: BookOpen, path: '/dm/spells', colour: 'var(--color-illu)' },
  { title: 'Items', description: 'Browse weapons, armour, and magic items', icon: Shield, path: '/dm/items', colour: 'var(--color-trans)' },
  { title: 'Conditions', description: 'Reference conditions and their effects', icon: FlaskConical, path: '/dm/conditions', colour: 'var(--color-conj)' },
  { title: 'Encounters', description: 'Build and run combat encounters', icon: ScrollText, path: '/dm/encounters', colour: 'var(--color-evo)' },
];

/**
 * The DM's Active and Paused encounters, each with the way back into its session (D236). It shows nothing while
 * loading or when no encounter is under way, so the dashboard looks as it did.
 */
function RunningEncounters() {
  const { data: encounters, error } = useRunningEncounters();

  if (error) {
    return <p role="alert" className="font-body text-[15px] text-debuff mb-8">{apiErrorMessage(error)}</p>;
  }
  if (!encounters || encounters.length === 0) return null;

  return (
    <section aria-labelledby="running-encounters-heading" className="mb-8">
      <h3
        id="running-encounters-heading"
        className="font-heading text-[13px] font-semibold tracking-[0.1em] uppercase text-faint mb-3 flex items-center gap-2"
      >
        <Swords className="w-3.5 h-3.5" aria-hidden="true" /> Running encounters
      </h3>
      <ul className="space-y-2">
        {encounters.map((encounter) => <RunningEncounterRow key={encounter.id} encounter={encounter} />)}
      </ul>
    </section>
  );
}

function RunningEncounterRow({ encounter }: { encounter: RunningEncounter }) {
  const active = encounter.status === 'ACTIVE';
  return (
    <li
      className="bg-card border border-rule p-4 flex flex-wrap items-center justify-between gap-3"
      style={{ borderLeftWidth: '3px', borderLeftColor: active ? 'var(--color-buff)' : 'var(--color-rule)' }}
    >
      <div className="min-w-0">
        <p className="font-heading text-[15px] font-semibold text-ink truncate">{encounter.name}</p>
        <p className="font-body text-[14px] font-semibold text-muted">
          {encounter.campaignName}
          {' · '}
          <span className={active ? 'text-buff' : 'text-faint'}>{active ? 'Active' : 'Paused'}</span>
          {encounter.roundNumber != null && <> · Round {encounter.roundNumber}</>}
        </p>
      </div>
      <Link
        to={`/dm/encounter/${encounter.id}/session`}
        aria-label={`Go to session: ${encounter.name}`}
        className="px-3 py-1.5 font-body text-[15px] font-semibold text-card flex items-center gap-2 shrink-0"
        style={{ backgroundColor: 'var(--color-buff)' }}
      >
        <Swords className="w-4 h-4" aria-hidden="true" /> Go to Session
      </Link>
    </li>
  );
}

export default function DmDashboard() {
  const navigate = useNavigate();

  return (
    <div className="min-h-screen bg-page">
      <NavBar />

      <main className="max-w-5xl mx-auto px-6 py-10">
        <h2 className="font-heading text-[19px] font-semibold tracking-[0.02em] text-ink mb-6">Dashboard</h2>

        <RunningEncounters />

        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {cards.map((card) => (
            <button
              key={card.title}
              onClick={() => navigate(card.path)}
              className="bg-card border border-rule hover:border-muted p-6 text-left transition-colors group"
              style={{ borderLeftWidth: '3px', borderLeftColor: card.colour }}
            >
              <card.icon
                className="w-7 h-7 mb-3 group-hover:scale-105 transition-transform"
                style={{ color: card.colour }}
              />
              <h3 className="font-heading text-[16px] font-semibold text-ink mb-1">{card.title}</h3>
              <p className="font-body text-[15px] font-semibold text-muted">{card.description}</p>
            </button>
          ))}
        </div>
      </main>
    </div>
  );
}
