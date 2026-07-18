import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, Search, ChevronLeft, ChevronRight, ChevronUp, ChevronDown, X, RotateCcw } from 'lucide-react';
import { searchSpells, getSpellSchools, getSpellSources, getSpellClasses, getSpellSubclasses } from '../../api/referenceApi';
import type { Spell } from '../../types/reference';
import SpellCard from '../../components/reference/SpellCard';
import { sourceName } from '../../utils/sourceNames';

const COLUMNS: { label: string; field: string; hideClass?: string }[] = [
  { label: 'Name', field: 'name' },
  { label: 'Level', field: 'level' },
  { label: 'School', field: 'school', hideClass: 'hidden md:table-cell' },
  { label: 'Casting Time', field: 'casting_time', hideClass: 'hidden md:table-cell' },
  { label: 'Range', field: 'range_distance', hideClass: 'hidden lg:table-cell' },
  { label: 'Source', field: 'source', hideClass: 'hidden lg:table-cell' },
];

export default function SpellsPage() {
  const navigate = useNavigate();
  const [spells, setSpells] = useState<Spell[]>([]);
  const [selectedSpell, setSelectedSpell] = useState<Spell | null>(null);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [levelFilter, setLevelFilter] = useState<number | ''>('');
  const [schoolFilter, setSchoolFilter] = useState('');
  const [sourceFilter, setSourceFilter] = useState('');
  const [classFilter, setClassFilter] = useState('');
  const [subclassFilter, setSubclassFilter] = useState('');
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
    if (classFilter) {
      getSpellSubclasses(classFilter).then(setSubclasses);
    } else {
      setSubclasses([]);
      setSubclassFilter('');
    }
  }, [classFilter]);

  const hasFilters = search || levelFilter !== '' || schoolFilter || sourceFilter ||
    classFilter || subclassFilter || concFilter || ritualFilter;

  const clearFilters = () => {
    setSearch('');
    setLevelFilter('');
    setSchoolFilter('');
    setSourceFilter('');
    setClassFilter('');
    setSubclassFilter('');
    setConcFilter('');
    setRitualFilter('');
    setPage(0);
  };

  const loadSpells = useCallback(async () => {
    setLoading(true);
    try {
      const result = await searchSpells({
        name: search || undefined,
        level: levelFilter === '' ? undefined : levelFilter,
        school: schoolFilter || undefined,
        source: sourceFilter || undefined,
        className: classFilter || undefined,
        subclass: subclassFilter || undefined,
        concentration: concFilter || undefined,
        ritual: ritualFilter || undefined,
        page,
        size: 20,
        sort: `${sortField},${sortDir}`,
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
    if (field === sortField) {
      setSortDir(d => d === 'asc' ? 'desc' : 'asc');
    } else {
      setSortField(field);
      setSortDir('asc');
    }
    setPage(0);
  };

  const SortIcon = ({ field }: { field: string }) => {
    if (field !== sortField) return null;
    return sortDir === 'asc' ? <ChevronUp size={14} /> : <ChevronDown size={14} />;
  };

  const levelLabel = (level: number) => level === 0 ? 'Cantrip' : `Level ${level}`;

  return (
    <div className="min-h-screen bg-gray-900 text-white">
      <div className="sticky top-0 z-10 bg-gray-900 border-b border-gray-800 px-6 py-4">
        <button onClick={() => navigate('/dm')}
          className="flex items-center gap-2 text-gray-400 hover:text-white">
          <ArrowLeft size={20} /> Back to Dashboard
        </button>
      </div>

      <div className="p-6">
      <h1 className="text-3xl font-bold mb-6">Spells</h1>

      <div className="grid grid-cols-1 md:grid-cols-3 lg:grid-cols-4 gap-3 mb-3">
        <div className="relative">
          <Search className="absolute left-3 top-2.5 text-gray-400" size={18} />
          <input type="text" placeholder="Search spells..."
            value={search} onChange={(e) => setSearch(e.target.value)}
            className="w-full pl-10 pr-4 py-2 bg-gray-800 rounded-lg border border-gray-700 focus:border-indigo-500 focus:outline-none" />
        </div>
        <select value={levelFilter} onChange={(e) => setLevelFilter(e.target.value === '' ? '' : parseInt(e.target.value))}
          className="px-4 py-2 bg-gray-800 rounded-lg border border-gray-700 focus:border-indigo-500 focus:outline-none">
          <option value="">All Levels</option>
          <option value="0">Cantrip</option>
          {[1, 2, 3, 4, 5, 6, 7, 8, 9].map(l => <option key={l} value={l}>Level {l}</option>)}
        </select>
        <select value={schoolFilter} onChange={(e) => setSchoolFilter(e.target.value)}
          className="px-4 py-2 bg-gray-800 rounded-lg border border-gray-700 focus:border-indigo-500 focus:outline-none">
          <option value="">All Schools</option>
          {schools.map(s => <option key={s} value={s}>{s}</option>)}
        </select>
        <select value={classFilter} onChange={(e) => { setClassFilter(e.target.value); setSubclassFilter(''); }}
          className="px-4 py-2 bg-gray-800 rounded-lg border border-gray-700 focus:border-indigo-500 focus:outline-none">
          <option value="">All Classes</option>
          {classes.map(c => <option key={c} value={c}>{c}</option>)}
        </select>
        {classFilter && subclasses.length > 0 && (
          <select value={subclassFilter} onChange={(e) => setSubclassFilter(e.target.value)}
            className="px-4 py-2 bg-gray-800 rounded-lg border border-gray-700 focus:border-indigo-500 focus:outline-none">
            <option value="">All {classFilter} Subclasses</option>
            {subclasses.map(sc => (
              <option key={sc} value={sc}>{sc.replace(`${classFilter} (`, '').replace(/\)$/, '')}</option>
            ))}
          </select>
        )}
        <select value={concFilter} onChange={(e) => setConcFilter(e.target.value)}
          className="px-4 py-2 bg-gray-800 rounded-lg border border-gray-700 focus:border-indigo-500 focus:outline-none">
          <option value="">Concentration: Any</option>
          <option value="true">Concentration Only</option>
          <option value="false">Non-Concentration Only</option>
        </select>
        <select value={ritualFilter} onChange={(e) => setRitualFilter(e.target.value)}
          className="px-4 py-2 bg-gray-800 rounded-lg border border-gray-700 focus:border-indigo-500 focus:outline-none">
          <option value="">Ritual: Any</option>
          <option value="true">Ritual Only</option>
          <option value="false">Non-Ritual Only</option>
        </select>
        <select value={sourceFilter} onChange={(e) => setSourceFilter(e.target.value)}
          className="px-4 py-2 bg-gray-800 rounded-lg border border-gray-700 focus:border-indigo-500 focus:outline-none">
          <option value="">All Sources</option>
          {sources.map(s => <option key={s} value={s}>{sourceName(s)}</option>)}
        </select>
      </div>

      <div className="flex items-center justify-between mb-4">
        <p className="text-gray-400 text-sm">{totalElements} spells found</p>
        {hasFilters && (
          <button onClick={clearFilters}
            className="flex items-center gap-1.5 px-3 py-1.5 text-sm text-gray-400 hover:text-white bg-gray-800 hover:bg-gray-700 rounded-lg transition-colors">
            <RotateCcw size={14} /> Clear Filters
          </button>
        )}
      </div>

      <div className="flex gap-0">
        <div className={`min-w-0 transition-all duration-300 ${selectedSpell ? 'w-3/5' : 'w-full'}`}>
          {loading ? (
            <div className="text-center py-12 text-gray-400">Loading...</div>
          ) : (
            <>
              <div className="bg-gray-800 rounded-lg overflow-hidden">
                <table className="w-full">
                  <thead>
                    <tr className="border-b border-gray-700 text-left text-gray-400 text-sm">
                      {COLUMNS.map(col => (
                        <th key={col.field}
                          onClick={() => handleSort(col.field)}
                          className={`px-4 py-3 cursor-pointer hover:text-white select-none ${col.hideClass || ''}`}>
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
                        className={`border-b border-gray-700/50 hover:bg-gray-700/50 cursor-pointer transition-colors ${selectedSpell?.id === s.id ? 'bg-gray-700/70' : ''}`}>
                        <td className="px-4 py-3 font-medium">
                          {s.name}
                          {s.concentration && <span className="ml-2 text-xs text-yellow-400">C</span>}
                          {s.ritual && <span className="ml-1 text-xs text-blue-400">R</span>}
                        </td>
                        <td className="px-4 py-3 text-gray-400">{levelLabel(s.level)}</td>
                        <td className="px-4 py-3 text-gray-400 hidden md:table-cell">{s.school}</td>
                        <td className="px-4 py-3 text-gray-400 hidden md:table-cell">{s.castingTime}</td>
                        <td className="px-4 py-3 text-gray-400 hidden lg:table-cell">{s.rangeDistance}</td>
                        <td className="px-4 py-3 text-gray-400 hidden lg:table-cell">{sourceName(s.source || '')}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              {totalPages > 1 && (
                <div className="flex items-center justify-center gap-4 mt-6">
                  <button onClick={() => setPage(p => Math.max(0, p - 1))} disabled={page === 0}
                    className="p-2 rounded-lg bg-gray-800 hover:bg-gray-700 disabled:opacity-50 disabled:cursor-not-allowed">
                    <ChevronLeft size={20} />
                  </button>
                  <span className="text-gray-400">Page {page + 1} of {totalPages}</span>
                  <button onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))} disabled={page >= totalPages - 1}
                    className="p-2 rounded-lg bg-gray-800 hover:bg-gray-700 disabled:opacity-50 disabled:cursor-not-allowed">
                    <ChevronRight size={20} />
                  </button>
                </div>
              )}
            </>
          )}
        </div>

        {selectedSpell && (
          <div className="w-2/5 ml-4 flex-shrink-0 hidden md:block">
            <div className="sticky top-6">
              <div className="flex justify-end mb-2">
                <button onClick={() => setSelectedSpell(null)}
                  className="p-1 rounded-lg bg-gray-800 hover:bg-gray-700 text-gray-400 hover:text-white">
                  <X size={18} />
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
        <div className="md:hidden fixed inset-0 z-50 bg-gray-900/95 overflow-y-auto p-4">
          <button onClick={() => setSelectedSpell(null)}
            className="flex items-center gap-2 text-gray-400 hover:text-white mb-4">
            <ArrowLeft size={20} /> Back to Spells
          </button>
          <SpellCard spell={selectedSpell} />
        </div>
      )}
      </div>
    </div>
  );
}
