import { useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';

interface NavLink {
  label: string;
  path: string;
}

const dmLinks: NavLink[] = [
  { label: 'Dashboard', path: '/dm' },
  { label: 'Campaigns', path: '/dm/campaigns' },
  { label: 'Encounters', path: '/dm/encounters' },
  { label: 'Bestiary', path: '/dm/bestiary' },
  { label: 'Spells', path: '/dm/spells' },
  { label: 'Items', path: '/dm/items' },
  { label: 'Conditions', path: '/dm/conditions' },
  { label: 'Quick Ref', path: '/dm/quickref' },
];

const playerLinks: NavLink[] = [
  { label: 'Dashboard', path: '/player' },
  { label: 'Quick Ref', path: '/player/quickref' },
];

export default function NavBar() {
  const { logout } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const isDm = location.pathname.startsWith('/dm');
  const links = isDm ? dmLinks : playerLinks;

  function isActive(path: string) {
    if (path === '/dm' || path === '/player') {
      return location.pathname === path;
    }
    return location.pathname.startsWith(path);
  }

  return (
    <nav className="sticky top-0 z-50">
      {/* Tourmaline gradient accent line */}
      <div
        className="h-[2px]"
        style={{
          background: 'linear-gradient(90deg, #E11D48, #C026D3, #7C3AED, #4F46E5, #0D9488, #059669)',
        }}
      />

      {/* Nav body */}
      <div
        className="border-b border-rule px-5 py-3.5 flex items-center justify-between"
        style={{
          background: 'linear-gradient(135deg, #E11D4810, #C026D308, #7C3AED10, #4F46E510, #0D948808, #05966910)',
        }}
      >
        {/* Brand */}
        <button
          onClick={() => navigate(isDm ? '/dm' : '/player')}
          className="font-heading text-[17px] font-semibold tracking-[0.04em] text-ink hover:text-muted transition-colors"
        >
          Tabletop Helper
        </button>

        {/* Links */}
        <div className="flex items-center gap-1">
          {links.map((link) => (
            <button
              key={link.path}
              onClick={() => navigate(link.path)}
              className={`px-2.5 py-1 font-body text-[14px] font-medium transition-colors ${
                isActive(link.path)
                  ? 'text-ink font-semibold border-b-2 border-ink'
                  : 'text-muted hover:text-ink'
              }`}
            >
              {link.label}
            </button>
          ))}

          <span className="mx-2 w-px h-4 bg-rule" />

          <button
            onClick={() => navigate('/select-role')}
            className="px-2.5 py-1 font-body text-[14px] font-medium text-muted hover:text-ink transition-colors"
          >
            Switch Role
          </button>
          <button
            onClick={logout}
            className="px-2.5 py-1 font-body text-[14px] font-medium text-muted hover:text-ink transition-colors"
          >
            Sign Out
          </button>
        </div>
      </div>
    </nav>
  );
}
