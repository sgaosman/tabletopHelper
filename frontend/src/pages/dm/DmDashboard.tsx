import { useNavigate } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { BookOpen, ScrollText, Shield, Swords, Users, FlaskConical, Zap } from 'lucide-react';

const cards = [
  { title: 'Campaigns', description: 'Manage your campaigns and players', icon: Users, path: '/dm/campaigns', color: 'text-blue-400' },
  { title: 'Bestiary', description: 'Browse monsters from all sourcebooks', icon: Swords, path: '/dm/bestiary', color: 'text-red-400' },
  { title: 'Spells', description: 'Search the complete spell list', icon: BookOpen, path: '/dm/spells', color: 'text-purple-400' },
  { title: 'Items', description: 'Browse weapons, armour, and magic items', icon: Shield, path: '/dm/items', color: 'text-amber-400' },
  { title: 'Conditions', description: 'Reference conditions and their effects', icon: FlaskConical, path: '/dm/conditions', color: 'text-green-400' },
  { title: 'Encounters', description: 'Build and run combat encounters', icon: ScrollText, path: '/dm/encounters', color: 'text-orange-400' },
];

export default function DmDashboard() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  return (
    <div className="min-h-screen bg-gray-950">
      <header className="sticky top-0 z-10 bg-gray-950 border-b border-gray-800 px-6 py-4 flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-white">QuestKeeper</h1>
          <p className="text-sm text-gray-400">Dungeon Master — {user?.displayName}</p>
        </div>
        <div className="flex items-center gap-4">
          <button
            onClick={() => navigate('/dm/quickref')}
            className="flex items-center gap-1.5 text-gray-400 hover:text-cyan-400 text-sm transition-colors"
          >
            <Zap className="w-4 h-4" /> Quick Rules Reference
          </button>
          <button
            onClick={() => navigate('/select-role')}
            className="text-gray-400 hover:text-white text-sm transition-colors"
          >
            Switch Role
          </button>
          <button
            onClick={logout}
            className="text-gray-400 hover:text-white text-sm transition-colors"
          >
            Sign Out
          </button>
        </div>
      </header>

      <main className="max-w-5xl mx-auto px-6 py-10">
        <h2 className="text-2xl font-bold text-white mb-6">Dashboard</h2>

        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {cards.map((card) => (
            <button
              key={card.title}
              onClick={() => navigate(card.path)}
              className="bg-gray-900 border border-gray-800 hover:border-gray-600 rounded-xl p-6 text-left transition-all group"
            >
              <card.icon className={`w-8 h-8 ${card.color} mb-3 group-hover:scale-110 transition-transform`} />
              <h3 className="text-lg font-semibold text-white mb-1">{card.title}</h3>
              <p className="text-sm text-gray-400">{card.description}</p>
            </button>
          ))}
        </div>
      </main>
    </div>
  );
}
