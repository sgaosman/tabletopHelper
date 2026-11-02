import { useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Check, ChevronDown, User } from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { THEMES, useTheme } from '../../hooks/useTheme';
import { useDismiss } from '../../hooks/useDismiss';

const ITEM = 'w-full flex items-center gap-3 px-4 py-1.5 text-left font-body text-[15px] font-semibold';

// The theme choice, Switch Role and Sign Out, behind one button at the right of the top bar (D173)
export default function AccountMenu() {
  const { logout } = useAuth();
  const navigate = useNavigate();
  const { theme, setTheme } = useTheme();
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  useDismiss(open, containerRef, () => setOpen(false), buttonRef);

  return (
    <div ref={containerRef} className="relative">
      <button
        ref={buttonRef}
        type="button"
        aria-label="Account"
        aria-expanded={open}
        aria-controls="account-menu"
        onClick={() => setOpen(o => !o)}
        className="flex items-center gap-1 px-2.5 py-1 font-body text-[16px] font-semibold text-muted hover:text-ink transition-colors"
      >
        {/* Phones show just the icon, to leave room for the menu button */}
        <User className="w-5 h-5 sm:hidden" aria-hidden="true" />
        <span className="hidden sm:inline">Account</span>
        <ChevronDown className="w-4 h-4" aria-hidden="true" />
      </button>

      {open && (
        <div id="account-menu" className="absolute right-0 top-full mt-2 w-64 bg-card border border-rule py-2 z-50">
          <fieldset>
            <legend className="px-4 pb-1 font-heading text-[11px] font-semibold tracking-[0.1em] uppercase text-faint">Theme</legend>
            {THEMES.map(t => (
              <label
                key={t.key}
                className={`${ITEM} cursor-pointer text-ink hover:bg-page has-[:focus-visible]:outline-[1.5px] has-[:focus-visible]:outline-faint`}
              >
                <input
                  type="radio"
                  name="theme"
                  value={t.key}
                  checked={theme === t.key}
                  onChange={() => setTheme(t.key)}
                  className="sr-only"
                />
                <span
                  aria-hidden="true"
                  className="w-4 h-4 shrink-0 border-[3px]"
                  style={{ backgroundColor: t.swatch.page, borderColor: t.swatch.edge }}
                />
                <span className="flex-1">{t.name}</span>
                {theme === t.key && <Check className="w-4 h-4" aria-hidden="true" />}
              </label>
            ))}
          </fieldset>

          <div className="border-t border-rule my-2" />

          <button
            type="button"
            onClick={() => {
              setOpen(false);
              navigate('/select-role');
            }}
            className={`${ITEM} text-muted hover:text-ink hover:bg-page`}
          >
            Switch Role
          </button>
          <button type="button" onClick={logout} className={`${ITEM} text-muted hover:text-ink hover:bg-page`}>
            Sign Out
          </button>
        </div>
      )}
    </div>
  );
}
