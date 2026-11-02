import { useRef, useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { Menu, X } from 'lucide-react';
import AccountMenu from './AccountMenu';
import { useDismiss } from '../../hooks/useDismiss';

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
  { label: 'Spells', path: '/player/spells' },
  { label: 'Items', path: '/player/items' },
  { label: 'Conditions', path: '/player/conditions' },
  { label: 'Quick Ref', path: '/player/quickref' },
];

export default function NavBar() {
  const navigate = useNavigate();
  const location = useLocation();
  const [linksOpen, setLinksOpen] = useState(false);
  const linksMenuRef = useRef<HTMLDivElement>(null);
  const linksButtonRef = useRef<HTMLButtonElement>(null);
  useDismiss(linksOpen, linksMenuRef, () => setLinksOpen(false), linksButtonRef);
  const isDm = location.pathname.startsWith('/dm');
  const links = isDm ? dmLinks : playerLinks;

  // Below these widths the links fold into a menu. The DM bar is longer, and in the widest
  // theme (Adventurer's Handbook) it needs about 1150px, the player bar about 810px.
  const wideLinks = isDm ? 'hidden xl:flex' : 'hidden lg:flex';
  const narrowMenu = isDm ? 'xl:hidden' : 'lg:hidden';

  function isActive(path: string) {
    if (path === '/dm' || path === '/player') {
      return location.pathname === path;
    }
    return location.pathname.startsWith(path);
  }

  function go(path: string) {
    setLinksOpen(false);
    navigate(path);
  }

  return (
    <nav className="sticky top-0 z-50">
      {/* Accent line, set by the theme */}
      <div className="h-[2px]" style={{ background: 'var(--nav-accent)' }} />

      {/* Nav body */}
      <div className="relative bg-card border-b border-rule px-5 py-3.5 flex items-center justify-between gap-4">
        {/* Brand */}
        <button
          onClick={() => go(isDm ? '/dm' : '/player')}
          className="font-heading text-[17px] font-semibold tracking-[0.04em] text-ink hover:text-muted transition-colors whitespace-nowrap"
        >
          Tabletop Helper
        </button>

        <div className="flex items-center gap-1">
          {/* Links */}
          <div className={`${wideLinks} items-center gap-1`}>
            {links.map((link) => (
              <button
                key={link.path}
                onClick={() => go(link.path)}
                className={`px-2.5 py-1 font-body text-[16px] font-semibold whitespace-nowrap transition-colors ${
                  isActive(link.path)
                    ? 'text-ink font-semibold border-b-2 border-ink'
                    : 'text-muted hover:text-ink'
                }`}
              >
                {link.label}
              </button>
            ))}

            <span className="mx-2 w-px h-4 bg-rule" />
          </div>

          {/* The same links in a menu, on narrower screens */}
          <div ref={linksMenuRef} className={`contents ${narrowMenu}`}>
            <button
              ref={linksButtonRef}
              type="button"
              aria-label="Menu"
              aria-expanded={linksOpen}
              aria-controls="nav-links-menu"
              onClick={() => setLinksOpen(o => !o)}
              className="p-1.5 text-muted hover:text-ink transition-colors"
            >
              {linksOpen ? <X className="w-5 h-5" aria-hidden="true" /> : <Menu className="w-5 h-5" aria-hidden="true" />}
            </button>

            {linksOpen && (
              <div id="nav-links-menu" className="absolute left-0 right-0 top-full bg-card border-b border-rule py-2">
                {links.map((link) => (
                  <button
                    key={link.path}
                    onClick={() => go(link.path)}
                    aria-current={isActive(link.path) ? 'page' : undefined}
                    className={`block w-full text-left px-5 py-2 font-body text-[16px] font-semibold transition-colors ${
                      isActive(link.path) ? 'text-ink bg-page-alt' : 'text-muted hover:text-ink hover:bg-page'
                    }`}
                  >
                    {link.label}
                  </button>
                ))}
              </div>
            )}
          </div>

          <AccountMenu />
        </div>
      </div>
    </nav>
  );
}
