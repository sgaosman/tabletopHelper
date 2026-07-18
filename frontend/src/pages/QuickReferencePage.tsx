import { useState, useEffect, useRef } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { ArrowLeft } from 'lucide-react';
import { getQuickReference } from '../api/referenceApi';
import { parseMarkup } from '../utils/parseMarkup';

const CHAPTER_NAMES = [
  'Character Creation',
  'Equipment',
  'Playing the Game',
  'Combat',
  'Movement',
];

const CHAPTER_COLORS: Record<string, string> = {
  'Character Creation': 'border-blue-500',
  'Equipment': 'border-amber-500',
  'Playing the Game': 'border-green-500',
  'Combat': 'border-red-500',
  'Movement': 'border-purple-500',
};

const CHAPTER_TEXT_COLORS: Record<string, string> = {
  'Character Creation': 'text-blue-400',
  'Equipment': 'text-amber-400',
  'Playing the Game': 'text-green-400',
  'Combat': 'text-red-400',
  'Movement': 'text-purple-400',
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
    return <p className="text-gray-300 leading-relaxed mb-2"><RichText text={entry} /></p>;
  }

  if (!entry || typeof entry !== 'object') return null;

  const e = entry as QuickRefEntry;

  switch (e.type) {
    case 'section':
    case 'entries': {
      const isTopLevel = e.type === 'section';
      return (
        <div className={depth > 0 ? 'ml-0 mt-3' : 'mt-4'}>
          {e.name && (
            isTopLevel ? null : (
              <h4 className="text-white font-semibold text-sm mb-2 mt-4">{e.name}</h4>
            )
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
        <ul className={`mb-3 ${cols > 1 ? `grid gap-x-4 gap-y-1` : 'space-y-1'}`}
          style={cols > 1 ? { gridTemplateColumns: `repeat(${Math.min(cols, 3)}, minmax(0, 1fr))` } : undefined}>
          {(e.items ?? []).map((item, i) => (
            <li key={i} className="flex gap-2 text-gray-300 text-sm">
              <span className="text-gray-500 mt-0.5 flex-shrink-0">&bull;</span>
              <span>{typeof item === 'string' ? <RichText text={item} /> : <EntryRenderer entry={item} depth={depth + 1} />}</span>
            </li>
          ))}
        </ul>
      );
    }

    case 'table': {
      return (
        <div className="mb-4 overflow-x-auto">
          {e.caption && <p className="text-gray-400 text-xs font-medium mb-1">{e.caption}</p>}
          <table className="w-full text-sm border-collapse">
            {e.colLabels && (
              <thead>
                <tr className="border-b border-gray-700">
                  {e.colLabels.map((label, i) => (
                    <th key={i} className="px-3 py-2 text-left text-gray-400 font-medium text-xs">
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
                  <tr key={i} className="border-b border-gray-800">
                    {row.map((cell, j) => (
                      <td key={j} className="px-3 py-1.5 text-gray-300 text-sm">
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
        <div className="bg-gray-800/50 border-l-2 border-amber-600 rounded-r-lg px-4 py-3 mb-3">
          {e.name && <p className="text-amber-400 font-semibold text-sm mb-1">{e.name}</p>}
          {e.entries?.map((sub, i) => (
            <EntryRenderer key={i} entry={sub} depth={depth + 1} />
          ))}
        </div>
      );
    }

    case 'item': {
      const dot = e.nameDot !== false ? '.' : '';
      return (
        <p className="text-gray-300 text-sm mb-2">
          <strong className="text-white">{e.name}{dot}</strong>{' '}
          {e.entry && <RichText text={e.entry} />}
          {e.entries?.map((sub, i) => (
            <span key={i}>{typeof sub === 'string' ? <RichText text={sub} /> : <EntryRenderer entry={sub} depth={depth + 1} />}</span>
          ))}
        </p>
      );
    }

    case 'abilityGeneric': {
      return (
        <p className="text-gray-300 text-sm mb-2 font-mono bg-gray-800 px-3 py-2 rounded">
          <RichText text={e.text ?? ''} />
        </p>
      );
    }

    case 'row': {
      if (!e.row) return null;
      return (
        <div className="flex gap-4 text-gray-300 text-sm pl-4 py-0.5">
          {e.row.map((cell, i) => (
            <span key={i} className={i === 0 ? 'flex-1' : 'w-20 text-right text-gray-400'}>
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

    case 'image': {
      return null;
    }

    case 'internal': {
      return null;
    }

    default:
      if (e.entries) {
        return (
          <div className="mb-2">
            {e.name && <p className="text-white font-semibold text-sm mb-1">{e.name}</p>}
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
  const navigate = useNavigate();
  const location = useLocation();
  const [chapters, setChapters] = useState<QuickRefChapter[]>([]);
  const [loading, setLoading] = useState(true);
  const [activeSection, setActiveSection] = useState<string>('');
  const [expandedSections, setExpandedSections] = useState<Set<string>>(new Set());
  const contentRef = useRef<HTMLDivElement>(null);
  const isPlayer = location.pathname.startsWith('/player');

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
      if (next.has(slug)) {
        next.delete(slug);
      } else {
        next.add(slug);
      }
      return next;
    });
  };

  const scrollToSection = (slug: string) => {
    const parentChapter = CHAPTER_NAMES.find((ch, ci) => {
      const chapter = chapters[ci];
      return chapter?.entries?.some((e: unknown) => {
        const entry = e as QuickRefEntry;
        return slugify(entry.name ?? '') === slug;
      });
    });
    if (parentChapter) {
      const chapterSlug = slugify(parentChapter);
      const sectionSlug = slug;
      const combinedKey = `${chapterSlug}/${sectionSlug}`;
      if (!expandedSections.has(combinedKey)) {
        setExpandedSections(prev => new Set(prev).add(combinedKey));
      }
    }
    setTimeout(() => {
      document.getElementById(slug)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }, 50);
  };

  return (
    <div className="min-h-screen bg-gray-900 text-white">
      <div className="sticky top-0 z-10 bg-gray-900 border-b border-gray-800 px-6 py-4">
        <button onClick={() => navigate(isPlayer ? '/player' : '/dm')}
          className="flex items-center gap-2 text-gray-400 hover:text-white">
          <ArrowLeft size={20} /> Back to Dashboard
        </button>
      </div>

      {loading ? (
        <div className="text-center py-20 text-gray-400">Loading quick rules reference...</div>
      ) : (
        <div className="flex">
          <aside className="hidden lg:block w-64 flex-shrink-0 border-r border-gray-800">
            <div className="sticky top-0 max-h-screen overflow-y-auto py-6 px-4">
              <h3 className="text-xs font-semibold text-gray-500 uppercase tracking-wider mb-4">Index</h3>
              {chapters.map((chapter, ci) => {
                const chapterName = CHAPTER_NAMES[ci] ?? `Chapter ${ci + 1}`;
                const textColor = CHAPTER_TEXT_COLORS[chapterName] ?? 'text-gray-400';

                return (
                  <div key={ci} className="mb-4">
                    <p className={`text-xs font-bold uppercase tracking-wider mb-1.5 ${textColor}`}>
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
                              className={`text-left text-xs w-full px-2 py-1 rounded transition-colors ${
                                activeSection === sectionSlug
                                  ? 'bg-gray-800 text-white'
                                  : 'text-gray-400 hover:text-gray-200 hover:bg-gray-800/50'
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

          <main ref={contentRef} className="flex-1 min-w-0 px-6 py-6">
            <h1 className="text-3xl font-bold mb-8">Quick Rules Reference</h1>
            {chapters.map((chapter, ci) => {
              const chapterName = CHAPTER_NAMES[ci] ?? `Chapter ${ci + 1}`;
              const borderColor = CHAPTER_COLORS[chapterName] ?? 'border-gray-500';

              return (
                <div key={ci} className="mb-10">
                  <h2 className={`text-xl font-bold mb-4 pb-2 border-b-2 ${borderColor}`}>
                    {chapterName}
                  </h2>
                  {(chapter.entries ?? []).map((rawEntry, si) => {
                    const section = rawEntry as QuickRefEntry;
                    const sectionName = section.name ?? `Section ${si}`;
                    const sectionSlug = slugify(sectionName);
                    const combinedKey = `${slugify(chapterName)}/${sectionSlug}`;
                    const isExpanded = expandedSections.has(combinedKey);

                    return (
                      <div key={si} id={sectionSlug} data-section
                        className="mb-2">
                        <button
                          onClick={() => toggleSection(combinedKey)}
                          className="w-full text-left px-4 py-3 bg-gray-800 hover:bg-gray-750 rounded-lg flex items-center justify-between group transition-colors">
                          <span className="font-medium text-white group-hover:text-gray-100">{sectionName}</span>
                          <span className="text-gray-500 text-sm">
                            {isExpanded ? '▲' : '▼'}
                          </span>
                        </button>
                        {isExpanded && (
                          <div className={`px-4 py-4 border-l-2 ${borderColor} ml-2 mt-1 mb-3`}>
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
