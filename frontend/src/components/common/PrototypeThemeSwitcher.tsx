// PROTOTYPE: throwaway readability study comparing alternative visual themes.
// Re-skins the whole app through the theme tokens; switch with ?variant= or the floating bar (or ← / → keys).
// Dev only. Delete this file and its mount in App.tsx once a direction is chosen.
import { useCallback, useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import { ChevronLeft, ChevronRight } from 'lucide-react';

type VariantKey = 'current' | 'T' | 'A' | 'B' | 'C';

const VARIANTS: { key: VariantKey; name: string }[] = [
  { key: 'current', name: 'Current (Tourmaline)' },
  { key: 'T', name: 'Tourmaline, easier to read' },
  { key: 'A', name: 'Parchment & Ink' },
  { key: 'B', name: 'Tavern by Candlelight' },
  { key: 'C', name: "Adventurer's Handbook" },
];

const STORAGE_KEY = 'prototype-theme-variant';
const FONTS_ID = 'prototype-theme-fonts';
const STYLE_ID = 'prototype-theme-styles';
const FONTS_HREF = 'https://fonts.googleapis.com/css2?family=Alegreya:ital,wght@0,400..900;1,400..900'
  + '&family=Alegreya+Sans:ital,wght@0,400;0,500;0,700;0,800;1,400'
  + '&family=Alegreya+SC:wght@400;500;700;800'
  + '&family=Libre+Baskerville:ital,wght@0,400;0,700;1,400'
  + '&family=Crimson+Pro:ital,wght@0,400..700;1,400&display=swap';

// Accent colours that need lightening, with darker -bg/-border tints, on the dark variant
const ACCENTS = [
  'cls-barbarian', 'cls-bard', 'cls-cleric', 'cls-druid', 'cls-fighter', 'cls-monk', 'cls-paladin',
  'cls-ranger', 'cls-rogue', 'cls-sorcerer', 'cls-warlock', 'cls-wizard', 'cls-artificer',
  'monster', 'buff', 'debuff', 'hp-healthy', 'hp-wounded', 'hp-critical', 'active',
  'evo', 'abj', 'conj', 'ench', 'necro', 'div', 'trans', 'illu',
];

// Every alternative variant bumps the hard-coded small text sizes up a step
const SIZE_BUMPS: [number, number][] = [[9, 11], [10, 12], [11, 13], [12, 14], [13, 15], [14, 16], [15, 17]];

function buildCss(): string {
  // Read the base accent values before any variant attribute is applied
  const root = getComputedStyle(document.documentElement);
  const darkAccents = ACCENTS.map(name => {
    const base = root.getPropertyValue(`--color-${name}`).trim();
    if (!base) return '';
    return `--color-${name}: color-mix(in srgb, ${base} 55%, #F3EADB);`
      + `--color-${name}-bg: color-mix(in srgb, ${base} 22%, #211A14);`
      + `--color-${name}-border: color-mix(in srgb, ${base} 45%, #211A14);`;
  }).join('\n    ');

  const sizes = SIZE_BUMPS
    .map(([from, to]) => `html[data-prototype-theme] .text-\\[${from}px\\] { font-size: ${to}px; }`)
    .join('\n');

  return `
html[data-prototype-theme], html[data-prototype-theme] body {
  background-color: var(--color-page);
  color: var(--color-ink);
}
html[data-prototype-theme] body { font-family: var(--font-body); line-height: 1.55; }
${sizes}

/* T: Tourmaline, easier to read. Same light look and Cinzel headings; sturdier body font, softer black,
   darker grey text and rules, and a calmer nav (the gemstone line stays, the rainbow wash goes) */
html[data-prototype-theme="T"] {
  --font-body: 'Crimson Pro', serif;
  --color-page: #F3F2EC;
  --color-card: #FBFAF5;
  --color-page-alt: #F6F5F0;
  --color-ink: #1C1917;
  --color-muted: #38332F;
  --color-faint: #57524C;
  --color-rule: #C9C4BE;
  --color-rule-light: #E3E0DA;
}
html[data-prototype-theme="T"] nav > div:nth-of-type(2) { background: var(--color-card) !important; }

/* A: Parchment & Ink. Warm light page, sturdy calligraphic body text, darker ink and rules */
html[data-prototype-theme="A"] {
  --font-heading: 'Cinzel', serif;
  --font-body: 'Alegreya', serif;
  --color-page: #F2E8D5;
  --color-card: #FBF6EC;
  --color-page-alt: #EFE3CB;
  --color-ink: #1C140D;
  --color-muted: #3D3024;
  --color-faint: #5E4E3D;
  --color-rule: #A08A66;
  --color-rule-light: #D9C8A6;
}

/* B: Tavern by Candlelight. Dark walnut surfaces, warm off-white text, humanist sans body */
html[data-prototype-theme="B"] {
  color-scheme: dark;
  --font-heading: 'Cinzel', serif;
  --font-body: 'Alegreya Sans', sans-serif;
  --color-page: #15110D;
  --color-card: #211A14;
  --color-page-alt: #1A1511;
  --color-ink: #F3EADB;
  --color-muted: #D9CBB4;
  --color-faint: #B3A38A;
  --color-rule: #7A6852;
  --color-rule-light: #3A3027;
    ${darkAccents}
}

/* C: Adventurer's Handbook. Rulebook page, small-caps headings in book red, red primary actions */
html[data-prototype-theme="C"] {
  --font-heading: 'Alegreya SC', serif;
  --font-body: 'Libre Baskerville', serif;
  --color-page: #EEE5CE;
  --color-card: #FDF9EE;
  --color-page-alt: #F5EDDA;
  --color-ink: #1B1B1B;
  --color-muted: #3B3226;
  --color-faint: #5C4F3F;
  --color-rule: #A88B5A;
  --color-rule-light: #DDCBA3;
}
html[data-prototype-theme="C"] :is(h1, h2, h3).font-heading { color: #6B1D12; }
html[data-prototype-theme="C"] .bg-ink { background-color: #6B1D12; }
html[data-prototype-theme="C"] .border-ink { border-color: #6B1D12; }
html[data-prototype-theme="C"] .accent-ink { accent-color: #6B1D12; }
`;
}

function readStoredVariant(): VariantKey {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return VARIANTS.some(v => v.key === stored) ? (stored as VariantKey) : 'current';
  } catch {
    return 'current';
  }
}

const BAR: React.CSSProperties = {
  position: 'fixed', bottom: 16, left: '50%', transform: 'translateX(-50%)', zIndex: 9999,
  display: 'flex', alignItems: 'center', gap: 8, padding: '6px 8px',
  background: '#111', color: '#fff', boxShadow: '0 4px 16px rgba(0,0,0,0.35)',
  fontFamily: 'system-ui, sans-serif', fontSize: 13, lineHeight: 1.2,
};
const ARROW: React.CSSProperties = {
  display: 'flex', alignItems: 'center', justifyContent: 'center', width: 28, height: 28,
  background: '#2a2a2a', color: '#fff', border: 'none', cursor: 'pointer',
};

export default function PrototypeThemeSwitcher() {
  const [searchParams, setSearchParams] = useSearchParams();
  const fromUrl = searchParams.get('variant');
  const current: VariantKey = VARIANTS.some(v => v.key === fromUrl) ? (fromUrl as VariantKey) : readStoredVariant();

  useEffect(() => {
    if (!document.getElementById(FONTS_ID)) {
      const link = document.createElement('link');
      link.id = FONTS_ID;
      link.rel = 'stylesheet';
      link.href = FONTS_HREF;
      document.head.appendChild(link);
    }
    if (!document.getElementById(STYLE_ID)) {
      const style = document.createElement('style');
      style.id = STYLE_ID;
      style.textContent = buildCss();
      document.head.appendChild(style);
    }
  }, []);

  useEffect(() => {
    const root = document.documentElement;
    if (current === 'current') delete root.dataset.prototypeTheme;
    else root.dataset.prototypeTheme = current;
    try {
      localStorage.setItem(STORAGE_KEY, current);
    } catch (err) {
      console.warn('Prototype theme choice not saved', err);
    }
  }, [current]);

  const cycle = useCallback((step: number) => {
    const index = VARIANTS.findIndex(v => v.key === current);
    const next = VARIANTS[(index + step + VARIANTS.length) % VARIANTS.length].key;
    setSearchParams(prev => {
      const params = new URLSearchParams(prev);
      params.set('variant', next);
      return params;
    }, { replace: true });
  }, [current, setSearchParams]);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      const target = e.target as HTMLElement | null;
      if (target?.closest('input, textarea, select, [contenteditable="true"]')) return;
      if (e.key === 'ArrowLeft') cycle(-1);
      if (e.key === 'ArrowRight') cycle(1);
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [cycle]);

  const active = VARIANTS.find(v => v.key === current) ?? VARIANTS[0];
  return (
    <div role="toolbar" aria-label="Prototype theme switcher" style={BAR}>
      <button type="button" aria-label="Previous theme" onClick={() => cycle(-1)} style={ARROW}>
        <ChevronLeft size={16} />
      </button>
      <span style={{ minWidth: 210, textAlign: 'center' }}>
        {current === 'current' ? active.name : `${current}: ${active.name}`}
      </span>
      <button type="button" aria-label="Next theme" onClick={() => cycle(1)} style={ARROW}>
        <ChevronRight size={16} />
      </button>
    </div>
  );
}
