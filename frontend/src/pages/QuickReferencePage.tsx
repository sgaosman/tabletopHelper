import { useState, useEffect, useRef } from 'react';

import { getQuickReference } from '../api/referenceApi';
import { parseMarkup } from '../utils/parseMarkup';
import NavBar from '../components/common/NavBar';

const CHAPTER_NAMES = [
  'Character Creation',
  'Equipment',
  'Playing the Game',
  'Combat',
  'Movement',
];

const CHAPTER_ACCENT: Record<string, string> = {
  'Character Creation': '#4F46E5',
  'Equipment': '#B45309',
  'Playing the Game': '#059669',
  'Combat': '#DC2626',
  'Movement': '#7C3AED',
};

interface QuickRefChapter {
  entries: QuickRefEntry[];
}

interface QuickRefEntry {
  type: string;
  name?: string;
  entries?: unknown[];
  caption?: string;
  colLabels?: string[];
  colStyles?: string[];
  rows?: unknown[][];
  items?: unknown[];
  columns?: number;
  entry?: string;
  text?: string;
  nameDot?: boolean;
  row?: string[];
  style?: string;
  href?: { type: string; path: string };
  source?: string;
  page?: number;
  data?: Record<string, unknown>;
}

function RichText({ text }: { text: string }) {
  const html = parseMarkup(text);
  return <span dangerouslySetInnerHTML={{ __html: html }} />;
}

function EntryRenderer({ entry, depth = 0 }: { entry: unknown; depth?: number }) {
  if (typeof entry === 'string') {
    return <p className="font-body text-[13px] font-medium text-muted leading-relaxed mb-2"><RichText text={entry} /></p>;
  }

  if (!entry || typeof entry !== 'object') return null;

  const e = entry as QuickRefEntry;

  switch (e.type) {
    case 'section':
    case 'entries': {
      return (
        <div className={depth > 0 ? 'ml-0 mt-3' : 'mt-4'}>
          {e.name && e.type !== 'section' && (
            <h4 className="font-heading text-[12px] font-semibold text-ink mb-2 mt-4">{e.name}</h4>
          )}
          {e.entries?.map((sub, i) => (
            <EntryRenderer key={i} entry={sub} depth={depth + 1} />
          ))}
        </div>
      );
    }

    case 'list': {
      const cols = e.columns ?? 1;
      return (
        <ul className={`mb-3 ${cols > 1 ? 'grid gap-x-4 gap-y-1' : 'space-y-1'}`}
          style={cols > 1 ? { gridTemplateColumns: `repeat(${Math.min(cols, 3)}, minmax(0, 1fr))` } : undefined}>
          {(e.items ?? []).map((item, i) => (
            <li key={i} className="flex gap-2 font-body text-[12px] font-medium text-muted">
              <span className="text-faint mt-0.5 flex-shrink-0">&bull;</span>
              <span>{typeof item === 'string' ? <RichText text={item} /> : <EntryRenderer entry={item} depth={depth + 1} />}</span>
            </li>
          ))}
        </ul>
      );
    }

    case 'table': {
      return (
        <div className="mb-4 overflow-x-auto">
          {e.caption && <p className="font-heading text-[9px] font-semibold tracking-[0.04em] uppercase text-faint mb-1">{e.caption}</p>}
          <table className="w-full border-collapse bg-card border border-rule">
            {e.colLabels && (
              <thead>
                <tr className="border-b border-rule bg-page">
                  {e.colLabels.map((label, i) => (
                    <th key={i} className="px-3 py-2 text-left font-heading text-[10px] font-semibold tracking-[0.08em] uppercase text-faint">
                      <RichText text={label} />
                    </th>
                  ))}
                </tr>
              </thead>
            )}
            <tbody>
              {(e.rows ?? []).map((row, i) => {
                if (!Array.isArray(row)) return null;
                return (
                  <tr key={i} className="border-b border-rule-light">
                    {row.map((cell, j) => (
                      <td key={j} className="px-3 py-1.5 font-body text-[12px] font-medium text-muted">
                        {typeof cell === 'string' ? <RichText text={cell} /> : <EntryRenderer entry={cell} depth={depth + 1} />}
                      </td>
                    ))}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      );
    }

    case 'inset': {
      return (
        <div className="bg-page-alt border-l-2 border-cls-cleric px-4 py-3 mb-3">
          {e.name && <p className="font-heading text-[11px] font-semibold text-cls-cleric mb-1">{e.name}</p>}
          {e.entries?.map((sub, i) => (
            <EntryRenderer key={i} entry={sub} depth={depth + 1} />
          ))}
        </div>
      );
    }

    case 'item': {
      const dot = e.nameDot !== false ? '.' : '';
      return (
        <p className="font-body text-[12px] font-medium text-muted mb-2">
          <strong className="text-ink">{e.name}{dot}</strong>{' '}
          {e.entry && <RichText text={e.entry} />}
          {e.entries?.map((sub, i) => (
            <span key={i}>{typeof sub === 'string' ? <RichText text={sub} /> : <EntryRenderer entry={sub} depth={depth + 1} />}</span>
          ))}
        </p>
      );
    }

    case 'abilityGeneric': {
      return (
        <p className="font-body text-[12px] font-medium text-muted mb-2 bg-page px-3 py-2 border border-rule">
          <RichText text={e.text ?? ''} />
        </p>
      );
    }

    case 'row': {
      if (!e.row) return null;
      return (
        <div className="flex gap-4 font-body text-[12px] font-medium text-muted pl-4 py-0.5">
          {e.row.map((cell, i) => (
            <span key={i} className={i === 0 ? 'flex-1' : 'w-20 text-right text-faint'}>
              <RichText text={cell} />
            </span>
          ))}
        </div>
      );
    }

    case 'inlineBlock': {
      return (
        <div className="mb-2">
          {e.entries?.map((sub, i) => (
            <EntryRenderer key={i} entry={sub} depth={depth + 1} />
          ))}
        </div>
      );
    }

    case 'image':
    case 'internal':
      return null;

    default:
      if (e.entries) {
        return (
          <div className="mb-2">
            {e.name && <p className="font-heading text-[12px] font-semibold text-ink mb-1">{e.name}</p>}
            {e.entries.map((sub, i) => (
              <EntryRenderer key={i} entry={sub} depth={depth + 1} />
            ))}
          </div>
        );
      }
      return null;
  }
}

function slugify(text: string): string {
  return text.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
}

export default function QuickReferencePage() {
  const [chapters, setChapters] = useState<QuickRefChapter[]>([]);
  const [loading, setLoading] = useState(true);
  const [activeSection, setActiveSection] = useState<string>('');
  const [expandedSections, setExpandedSections] = useState<Set<string>>(new Set());
  const contentRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    getQuickReference()
      .then(data => setChapters(data as QuickRefChapter[]))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            setActiveSection(entry.target.id);
            break;
          }
        }
      },
      { rootMargin: '-80px 0px -60% 0px', threshold: 0 }
    );

    const sections = contentRef.current?.querySelectorAll('[data-section]');
    sections?.forEach(s => observer.observe(s));

    return () => observer.disconnect();
  }, [chapters, expandedSections]);

  const toggleSection = (slug: string) => {
    setExpandedSections(prev => {
      const next = new Set(prev);
      if (next.has(slug)) next.delete(slug);
      else next.add(slug);
      return next;
    });
  };

  const scrollToSection = (slug: string) => {
    const parentChapter = CHAPTER_NAMES.find((_ch, ci) => {
      const chapter = chapters[ci];
      return chapter?.entries?.some((e: unknown) => {
        const entry = e as QuickRefEntry;
        return slugify(entry.name ?? '') === slug;
      });
    });
    if (parentChapter) {
      const combinedKey = `${slugify(parentChapter)}/${slug}`;
      if (!expandedSections.has(combinedKey)) {
        setExpandedSections(prev => new Set(prev).add(combinedKey));
      }
    }
    setTimeout(() => {
      document.getElementById(slug)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }, 50);
  };

  return (
    <div className="min-h-screen bg-page">
      <NavBar />

      {loading ? (
        <div className="text-center py-20 font-body text-[14px] text-muted">Loading quick rules reference...</div>
      ) : (
        <div className="flex">
          <aside className="hidden lg:block w-56 flex-shrink-0 border-r border-rule">
            <div className="sticky top-14 max-h-screen overflow-y-auto py-6 px-4">
              <h3 className="font-heading text-[9px] font-semibold tracking-[0.1em] uppercase text-faint mb-4">Index</h3>
              {chapters.map((chapter, ci) => {
                const chapterName = CHAPTER_NAMES[ci] ?? `Chapter ${ci + 1}`;
                const accent = CHAPTER_ACCENT[chapterName] ?? '#78716C';

                return (
                  <div key={ci} className="mb-4">
                    <p className="font-heading text-[9px] font-bold tracking-[0.08em] uppercase mb-1.5" style={{ color: accent }}>
                      {chapterName}
                    </p>
                    <ul className="space-y-0.5">
                      {(chapter.entries ?? []).map((rawEntry, si) => {
                        const section = rawEntry as QuickRefEntry;
                        const sectionName = section.name ?? '';
                        const sectionSlug = slugify(sectionName);

                        return (
                          <li key={si}>
                            <button
                              onClick={() => scrollToSection(sectionSlug)}
                              className={`text-left font-body text-[11px] w-full px-2 py-0.5 transition-colors ${
                                activeSection === sectionSlug
                                  ? 'bg-page-alt text-ink font-semibold'
                                  : 'text-muted hover:text-ink hover:bg-page-alt'
                              }`}>
                              {sectionName}
                            </button>
                          </li>
                        );
                      })}
                    </ul>
                  </div>
                );
              })}
            </div>
          </aside>

          <main ref={contentRef} className="flex-1 min-w-0 px-6 py-6 max-w-4xl">
            <h1 className="font-heading text-[19px] font-semibold tracking-[0.02em] text-ink mb-8">Quick Rules Reference</h1>
            {chapters.map((chapter, ci) => {
              const chapterName = CHAPTER_NAMES[ci] ?? `Chapter ${ci + 1}`;
              const accent = CHAPTER_ACCENT[chapterName] ?? '#78716C';

              return (
                <div key={ci} className="mb-10">
                  <h2 className="font-heading text-[15px] font-semibold mb-4 pb-2 border-b-2" style={{ borderColor: accent, color: accent }}>
                    {chapterName}
                  </h2>
                  {(chapter.entries ?? []).map((rawEntry, si) => {
                    const section = rawEntry as QuickRefEntry;
                    const sectionName = section.name ?? `Section ${si}`;
                    const sectionSlug = slugify(sectionName);
                    const combinedKey = `${slugify(chapterName)}/${sectionSlug}`;
                    const isExpanded = expandedSections.has(combinedKey);

                    return (
                      <div key={si} id={sectionSlug} data-section className="mb-1.5">
                        <button
                          onClick={() => toggleSection(combinedKey)}
                          className="w-full text-left px-3 py-2.5 bg-card border border-rule hover:bg-page-alt flex items-center justify-between transition-colors">
                          <span className="font-heading text-[13px] font-semibold text-ink">{sectionName}</span>
                          <span className="text-faint text-[11px]">
                            {isExpanded ? '▲' : '▼'}
                          </span>
                        </button>
                        {isExpanded && (
                          <div className="px-4 py-4 border-l-2 ml-2 mt-1 mb-3" style={{ borderColor: accent }}>
                            {section.entries?.map((sub, i) => (
                              <EntryRenderer key={i} entry={sub} />
                            ))}
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              );
            })}
          </main>
        </div>
      )}
    </div>
  );
}
