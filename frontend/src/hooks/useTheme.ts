import { useCallback, useState } from 'react';

// The selectable themes (D173). Each key matches a data-theme block in index.css;
// the swatch colours are only for the theme menu.
export const THEMES = [
  { key: 'tourmaline', name: 'Tourmaline', swatch: { page: '#F3F2EC', edge: '#1C1917' } },
  { key: 'parchment', name: 'Parchment & Ink', swatch: { page: '#F2E8D5', edge: '#6B5A44' } },
  { key: 'tavern', name: 'Tavern by Candlelight', swatch: { page: '#211A14', edge: '#D9CBB4' } },
  { key: 'handbook', name: "Adventurer's Handbook", swatch: { page: '#FDF9EE', edge: '#6B1D12' } },
] as const;

export type ThemeKey = (typeof THEMES)[number]['key'];

// The inline script in index.html reads the same key to apply the theme before the first paint
const STORAGE_KEY = 'theme';
const DEFAULT_THEME: ThemeKey = 'tourmaline';

function isThemeKey(value: string | undefined): value is ThemeKey {
  return THEMES.some(t => t.key === value);
}

/** The current theme, and a setter that applies it to the page and saves it on this device. */
export function useTheme() {
  const [theme, setThemeState] = useState<ThemeKey>(() => {
    const applied = document.documentElement.dataset.theme;
    return isThemeKey(applied) ? applied : DEFAULT_THEME;
  });

  const setTheme = useCallback((next: ThemeKey) => {
    document.documentElement.dataset.theme = next;
    try {
      localStorage.setItem(STORAGE_KEY, next);
    } catch (err) {
      console.warn('Theme choice not saved', err);
    }
    setThemeState(next);
  }, []);

  return { theme, setTheme };
}
