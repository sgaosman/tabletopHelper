import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { Shield, Swords } from 'lucide-react';

export default function RoleSelectPage() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  return (
    <div className="min-h-screen flex flex-col items-center justify-center bg-gray-950 px-4">
      <div className="text-center mb-10">
        <h1 className="text-4xl font-bold text-white mb-2">Welcome, {user?.displayName}</h1>
        <p className="text-gray-400">Choose your role for this session</p>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-6 w-full max-w-2xl">
        <button
          onClick={() => navigate('/dm')}
          className="group bg-gray-900 border border-gray-800 hover:border-amber-500 rounded-2xl p-8 text-center transition-all hover:shadow-lg hover:shadow-amber-500/10"
        >
          <Shield className="w-16 h-16 text-amber-500 mx-auto mb-4 group-hover:scale-110 transition-transform" />
          <h2 className="text-2xl font-bold text-white mb-2">Dungeon Master</h2>
          <p className="text-gray-400 text-sm">
            Create campaigns, build encounters, and run combat sessions
          </p>
        </button>

        <button
          onClick={() => navigate('/player')}
          className="group bg-gray-900 border border-gray-800 hover:border-indigo-500 rounded-2xl p-8 text-center transition-all hover:shadow-lg hover:shadow-indigo-500/10"
        >
          <Swords className="w-16 h-16 text-indigo-500 mx-auto mb-4 group-hover:scale-110 transition-transform" />
          <h2 className="text-2xl font-bold text-white mb-2">Player</h2>
          <p className="text-gray-400 text-sm">
            Manage your characters, join campaigns, and play in encounters
          </p>
        </button>
      </div>

      <button
        onClick={logout}
        className="mt-10 text-gray-500 hover:text-gray-300 text-sm transition-colors"
      >
        Sign out
      </button>
    </div>
  );
}
