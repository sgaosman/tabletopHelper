import { useNavigate } from 'react-router-dom';
import { BookOpen, ScrollText, Shield, Swords, Users, FlaskConical } from 'lucide-react';
import NavBar from '../../components/common/NavBar';

const cards = [
  { title: 'Campaigns', description: 'Manage your campaigns and players', icon: Users, path: '/dm/campaigns', colour: '#4F46E5' },
  { title: 'Bestiary', description: 'Browse monsters from all sourcebooks', icon: Swords, path: '/dm/bestiary', colour: '#991B1B' },
  { title: 'Spells', description: 'Search the complete spell list', icon: BookOpen, path: '/dm/spells', colour: '#7C3AED' },
  { title: 'Items', description: 'Browse weapons, armour, and magic items', icon: Shield, path: '/dm/items', colour: '#B45309' },
  { title: 'Conditions', description: 'Reference conditions and their effects', icon: FlaskConical, path: '/dm/conditions', colour: '#059669' },
  { title: 'Encounters', description: 'Build and run combat encounters', icon: ScrollText, path: '/dm/encounters', colour: '#DC2626' },
];

export default function DmDashboard() {
  const navigate = useNavigate();

  return (
    <div className="min-h-screen bg-page">
      <NavBar />

      <main className="max-w-5xl mx-auto px-6 py-10">
        <h2 className="font-heading text-[19px] font-semibold tracking-[0.02em] text-ink mb-6">Dashboard</h2>

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
              <h3 className="font-heading text-[14px] font-semibold text-ink mb-1">{card.title}</h3>
              <p className="font-body text-[13px] font-medium text-muted">{card.description}</p>
            </button>
          ))}
        </div>
      </main>
    </div>
  );
}
