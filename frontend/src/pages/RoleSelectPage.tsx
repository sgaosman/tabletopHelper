import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { Shield, Swords } from 'lucide-react';

export default function RoleSelectPage() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  return (
    <div className="min-h-screen flex flex-col items-center justify-center bg-page px-4">
      <div className="text-center mb-10">
        <h1 className="font-heading text-[20px] font-semibold tracking-[0.02em] text-ink mb-1">
          Welcome, {user?.displayName}
        </h1>
        <p className="font-body text-[14px] font-medium text-muted">Choose your role for this session</p>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-6 w-full max-w-2xl">
        <button
          onClick={() => navigate('/dm')}
          className="group bg-card border border-rule hover:border-muted p-8 text-center transition-colors"
        >
          <Shield className="w-14 h-14 mx-auto mb-4 text-cls-cleric group-hover:scale-105 transition-transform" />
          <h2 className="font-heading text-[15px] font-semibold text-ink mb-2">Dungeon Master</h2>
          <p className="font-body text-[13px] font-medium text-muted">
            Create campaigns, build encounters, and run combat sessions
          </p>
        </button>

        <button
          onClick={() => navigate('/player')}
          className="group bg-card border border-rule hover:border-muted p-8 text-center transition-colors"
        >
          <Swords className="w-14 h-14 mx-auto mb-4 text-cls-wizard group-hover:scale-105 transition-transform" />
          <h2 className="font-heading text-[15px] font-semibold text-ink mb-2">Player</h2>
          <p className="font-body text-[13px] font-medium text-muted">
            Manage your characters, join campaigns, and play in encounters
          </p>
        </button>
      </div>

      <button
        onClick={logout}
        className="mt-10 font-body text-[13px] font-medium text-faint hover:text-muted transition-colors"
      >
        Sign out
      </button>
    </div>
  );
}
