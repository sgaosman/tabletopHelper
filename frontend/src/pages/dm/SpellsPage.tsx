import { useState, useEffect, useCallback } from 'react';
import { Search, ChevronLeft, ChevronRight, ChevronUp, ChevronDown, X, RotateCcw } from 'lucide-react';
import { searchSpells, getSpellSchools, getSpellSources, getSpellClasses, getSpellSubclasses } from '../../api/referenceApi';
import type { Spell } from '../../types/reference';
import SpellCard from '../../components/reference/SpellCard';
import MultiSelect from '../../components/common/MultiSelect';
import { sourceName } from '../../utils/sourceNames';
import NavBar from '../../components/common/NavBar';

const SCHOOL_COLOURS: Record<string, string> = {
  Evocation: '#DC2626', Abjuration: '#4F46E5', Conjuration: '#059669', Enchantment: '#DB2777',
  Necromancy: '#374151', Divination: '#CA8A04', Transmutation: '#B45309', Illusion: '#7C3AED',
};

const COLUMNS: { label: string; field: string; hideClass?: string }[] = [
  { label: 'Name', field: 'name' },
  { label: 'Level', field: 'level' },
  { label: 'School', field: 'school', hideClass: 'hidden md:table-cell' },
  { label: 'Casting Time', field: 'casting_time', hideClass: 'hidden md:table-cell' },
  { label: 'Range', field: 'range_distance', hideClass: 'hidden lg:table-cell' },
  { label: 'Source', field: 'source', hideClass: 'hidden lg:table-cell' },
];

export default function SpellsPage() {
  const [spells, setSpells] = useState<Spell[]>([]);
  const [selectedSpell, setSelectedSpell] = useState<Spell | null>(null);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [levelFilter, setLevelFilter] = useState<string[]>([]);
  const [schoolFilter, setSchoolFilter] = useState<string[]>([]);
  const [sourceFilter, setSourceFilter] = useState<string[]>([]);
  const [classFilter, setClassFilter] = useState<string[]>([]);
  const [subclassFilter, setSubclassFilter] = useState<string[]>([]);
  const [concFilter, setConcFilter] = useState('');
  const [ritualFilter, setRitualFilter] = useState('');
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [sortField, setSortField] = useState('name');
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc');

  const [schools, setSchools] = useState<string[]>([]);
  const [sources, setSources] = useState<string[]>([]);
  const [classes, setClasses] = useState<string[]>([]);
  const [subclasses, setSubclasses] = useState<string[]>([]);

  useEffect(() => {
    Promise.all([getSpellSchools(), getSpellSources(), getSpellClasses()])
      .then(([sc, so, cl]) => { setSchools(sc); setSources(so); setClasses(cl); });
  }, []);

  useEffect(() => {
    if (classFilter.length > 0) {
      Promise.all(classFilter.map(c => getSpellSubclasses(c)))
        .then(results => setSubclasses(results.flat()));
    } else {
      setSubclasses([]);
      setSubclassFilter([]);
    }
  }, [classFilter]);

  const hasFilters = search || levelFilter.length > 0 || schoolFilter.length > 0 || sourceFilter.length > 0 ||
    classFilter.length > 0 || subclassFilter.length > 0 || concFilter || ritualFilter;

  const clearFilters = () => {
    setSearch(''); setLevelFilter([]); setSchoolFilter([]); setSourceFilter([]);
    setClassFilter([]); setSubclassFilter([]); setConcFilter(''); setRitualFilter(''); setPage(0);
  };

  const loadSpells = useCallback(async () => {
    setLoading(true);
    try {
      const result = await searchSpells({
        name: search || undefined,
        level: levelFilter.length ? levelFilter.join(',') : undefined,
        school: schoolFilter.length ? schoolFilter.join(',') : undefined,
        source: sourceFilter.length ? sourceFilter.join(',') : undefined,
        className: classFilter.length ? classFilter.join(',') : undefined,
        subclass: subclassFilter.length ? subclassFilter.join(',') : undefined,
        concentration: concFilter || undefined,
        ritual: ritualFilter || undefined,
        page, size: 20, sort: `${sortField},${sortDir}`,
      });
      setSpells(result.content);
      setTotalPages(result.totalPages);
      setTotalElements(result.totalElements);
    } finally {
      setLoading(false);
    }
  }, [search, levelFilter, schoolFilter, sourceFilter, classFilter, subclassFilter, concFilter, ritualFilter, page, sortField, sortDir]);

  useEffect(() => { loadSpells(); }, [loadSpells]);
  useEffect(() => { setPage(0); }, [search, levelFilter, schoolFilter, sourceFilter, classFilter, subclassFilter, concFilter, ritualFilter]);

  const handleSort = (field: string) => {
    if (field === sortField) setSortDir(d => d === 'asc' ? 'desc' : 'asc');
    else { setSortField(field); setSortDir('asc'); }
    setPage(0);
  };

  const SortIcon = ({ field }: { field: string }) => {
    if (field !== sortField) return null;
    return sortDir === 'asc' ? <ChevronUp size={14} /> : <ChevronDown size={14} />;
  };

  const levelLabel = (level: number) => level === 0 ? 'Cantrip' : `Level ${level}`;

  return (
    <div className="min-h-screen bg-page">
      <NavBar />

      <div className="max-w-7xl mx-auto px-6 py-8">
        <h1 className="font-heading text-[19px] font-semibold tracking-[0.02em] text-ink mb-1">Spells</h1>
        <p className="font-body text-[13px] font-medium text-muted mb-6">Search the complete spell list</p>

        <div className="grid grid-cols-1 md:grid-cols-3 lg:grid-cols-4 gap-3 mb-3">
          <div className="relative">
            <Search className="absolute left-3 top-2.5 text-faint" size={16} />
            <input type="text" placeholder="Search spells..."
              value={search} onChange={(e) => setSearch(e.target.value)}
              className="w-full pl-9 pr-4 py-2 bg-card border border-rule font-body text-[14px] font-medium text-ink placeholder-faint focus:border-muted focus:outline-none" />
          </div>
          <MultiSelect options={['0','1','2','3','4','5','6','7','8','9']} selected={levelFilter} onChange={setLevelFilter} placeholder="All Levels" renderLabel={(l) => l === '0' ? 'Cantrip' : `Level ${l}`} accentColor="indigo" />
          <MultiSelect options={schools} selected={schoolFilter} onChange={setSchoolFilter} placeholder="All Schools" accentColor="indigo" />
          <MultiSelect options={classes} selected={classFilter} onChange={(v) => { setClassFilter(v); setSubclassFilter(prev => prev.filter(s => v.some(c => s.startsWith(c + ' (')))); }} placeholder="All Classes" accentColor="indigo" />
          {classFilter.length > 0 && subclasses.length > 0 && (
            <MultiSelect options={subclasses} selected={subclassFilter} onChange={setSubclassFilter} placeholder="All Subclasses" renderLabel={(sc) => sc.replace(/^.+ \(/, '').replace(/\)$/, '')} accentColor="indigo" />
          )}
          <select value={concFilter} onChange={(e) => setConcFilter(e.target.value)}
            className="px-3 py-2 bg-card border border-rule font-body text-[14px] font-medium text-ink focus:border-muted focus:outline-none">
            <option value="">Concentration: Any</option>
            <option value="true">Concentration Only</option>
            <option value="false">Non-Concentration Only</option>
          </select>
          <select value={ritualFilter} onChange={(e) => setRitualFilter(e.target.value)}
            className="px-3 py-2 bg-card border border-rule font-body text-[14px] font-medium text-ink focus:border-muted focus:outline-none">
            <option value="">Ritual: Any</option>
            <option value="true">Ritual Only</option>
            <option value="false">Non-Ritual Only</option>
          </select>
          <MultiSelect options={sources} selected={sourceFilter} onChange={setSourceFilter} placeholder="All Sources" renderLabel={(s) => sourceName(s)} accentColor="indigo" />
        </div>

        <div className="flex items-center justify-between mb-4">
          <p className="font-body text-[12px] font-medium text-muted">{totalElements} spells found</p>
          {hasFilters && (
            <button onClick={clearFilters}
              className="flex items-center gap-1.5 px-3 py-1 font-heading text-[9px] font-medium tracking-[0.04em] text-muted hover:text-ink transition-colors">
              <RotateCcw size={12} /> Clear Filters
            </button>
          )}
        </div>

        <div className="flex gap-0">
          <div className={`min-w-0 transition-all duration-300 ${selectedSpell ? 'w-3/5' : 'w-full'}`}>
            {loading ? (
              <div className="text-center py-12 font-body text-[14px] text-muted">Loading...</div>
            ) : (
              <>
                <div className="bg-card border border-rule overflow-hidden">
                  <table className="w-full">
                    <thead>
                      <tr className="border-b border-rule bg-page">
                        {COLUMNS.map(col => (
                          <th key={col.field}
                            onClick={() => handleSort(col.field)}
                            className={`px-3 py-2.5 text-left font-heading text-[10px] font-semibold tracking-[0.1em] uppercase text-faint cursor-pointer hover:text-ink select-none ${col.hideClass || ''}`}>
                            <span className="inline-flex items-center gap-1">
                              {col.label}
                              <SortIcon field={col.field} />
                            </span>
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {spells.map(s => (
                        <tr key={s.id} onClick={() => setSelectedSpell(s)}
                          className={`border-b border-rule-light hover:bg-page-alt cursor-pointer transition-colors ${selectedSpell?.id === s.id ? 'bg-page-alt' : ''}`}>
                          <td className="px-3 py-2">
                            <span className="font-heading text-[13px] font-bold" style={{ color: SCHOOL_COLOURS[s.school] || '#292524' }}>{s.name}</span>
                            {s.concentration && <span className="ml-2 font-heading text-[9px] font-medium tracking-[0.02em] px-1.5 py-0.5 text-muted border border-rule">C</span>}
                            {s.ritual && <span className="ml-1 font-heading text-[9px] font-medium tracking-[0.02em] px-1.5 py-0.5 text-cls-wizard border border-abj-border bg-abj-bg">R</span>}
                          </td>
                          <td className="px-3 py-2 font-body text-[13px] font-medium text-muted">{levelLabel(s.level)}</td>
                          <td className="px-3 py-2 font-body text-[13px] font-medium text-muted hidden md:table-cell">{s.school}</td>
                          <td className="px-3 py-2 font-body text-[13px] font-medium text-muted hidden md:table-cell">{s.castingTime}</td>
                          <td className="px-3 py-2 font-body text-[13px] font-medium text-muted hidden lg:table-cell">{s.rangeDistance}</td>
                          <td className="px-3 py-2 font-body text-[13px] font-medium text-faint hidden lg:table-cell">{sourceName(s.source || '')}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>

                {totalPages > 1 && (
                  <div className="flex items-center justify-center gap-4 mt-6">
                    <button onClick={() => setPage(p => Math.max(0, p - 1))} disabled={page === 0}
                      className="p-2 bg-card border border-rule hover:border-muted disabled:opacity-40 disabled:cursor-not-allowed transition-colors">
                      <ChevronLeft size={18} className="text-muted" />
                    </button>
                    <span className="font-body text-[13px] font-medium text-muted">Page {page + 1} of {totalPages}</span>
                    <button onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))} disabled={page >= totalPages - 1}
                      className="p-2 bg-card border border-rule hover:border-muted disabled:opacity-40 disabled:cursor-not-allowed transition-colors">
                      <ChevronRight size={18} className="text-muted" />
                    </button>
                  </div>
                )}
              </>
            )}
          </div>

          {selectedSpell && (
            <div className="w-2/5 ml-4 flex-shrink-0 hidden md:block">
              <div className="sticky top-16">
                <div className="flex justify-end mb-2">
                  <button onClick={() => setSelectedSpell(null)}
                    className="p-1 bg-card border border-rule hover:border-muted text-faint hover:text-ink transition-colors">
                    <X size={16} />
                  </button>
                </div>
                <div className="max-h-[calc(100vh-8rem)] overflow-y-auto">
                  <SpellCard spell={selectedSpell} />
                </div>
              </div>
            </div>
          )}
        </div>

        {selectedSpell && (
          <div className="md:hidden fixed inset-0 z-50 bg-page overflow-y-auto p-4">
            <button onClick={() => setSelectedSpell(null)}
              className="flex items-center gap-2 font-body text-[14px] font-medium text-muted hover:text-ink mb-4">
              <ChevronLeft size={18} /> Back to Spells
            </button>
            <SpellCard spell={selectedSpell} />
          </div>
        )}
      </div>
    </div>
  );
}
