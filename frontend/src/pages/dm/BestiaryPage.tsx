import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, Search, ChevronLeft, ChevronRight, ChevronUp, ChevronDown, X, RotateCcw } from 'lucide-react';
import { searchMonsters, getMonsterTypes, getMonsterChallengeRatings, getMonsterSources } from '../../api/monsterApi';
import type { Monster } from '../../types/monster';
import MonsterStatBlock from '../../components/monster/MonsterStatBlock';
import MultiSelect from '../../components/common/MultiSelect';
import { sourceName } from '../../utils/sourceNames';

const COLUMNS: { label: string; field: string; hideClass?: string }[] = [
  { label: 'Name', field: 'name' },
  { label: 'Type', field: 'type', hideClass: 'hidden md:table-cell' },
  { label: 'CR', field: 'challenge_rating' },
  { label: 'HP', field: 'hit_points', hideClass: 'hidden md:table-cell' },
  { label: 'AC', field: 'armour_class', hideClass: 'hidden md:table-cell' },
  { label: 'Source', field: 'source', hideClass: 'hidden lg:table-cell' },
];

export default function BestiaryPage() {
  const navigate = useNavigate();
  const [monsters, setMonsters] = useState<Monster[]>([]);
  const [selectedMonster, setSelectedMonster] = useState<Monster | null>(null);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [typeFilter, setTypeFilter] = useState<string[]>([]);
  const [crFilter, setCrFilter] = useState<string[]>([]);
  const [sourceFilter, setSourceFilter] = useState<string[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [sortField, setSortField] = useState('name');
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc');

  const [types, setTypes] = useState<string[]>([]);
  const [crs, setCrs] = useState<string[]>([]);
  const [sources, setSources] = useState<string[]>([]);

  useEffect(() => {
    Promise.all([getMonsterTypes(), getMonsterChallengeRatings(), getMonsterSources()])
      .then(([t, c, s]) => { setTypes(t); setCrs(c); setSources(s); });
  }, []);

  const loadMonsters = useCallback(async () => {
    setLoading(true);
    try {
      const result = await searchMonsters({
        name: search || undefined,
        type: typeFilter.length ? typeFilter.join(',') : undefined,
        cr: crFilter.length ? crFilter.join(',') : undefined,
        source: sourceFilter.length ? sourceFilter.join(',') : undefined,
        page,
        size: 20,
        sort: `${sortField},${sortDir}`,
      });
      setMonsters(result.content);
      setTotalPages(result.totalPages);
      setTotalElements(result.totalElements);
    } finally {
      setLoading(false);
    }
  }, [search, typeFilter, crFilter, sourceFilter, page, sortField, sortDir]);

  useEffect(() => { loadMonsters(); }, [loadMonsters]);
  useEffect(() => { setPage(0); }, [search, typeFilter, crFilter, sourceFilter]);

  const hasFilters = search || typeFilter.length > 0 || crFilter.length > 0 || sourceFilter.length > 0;
  const clearFilters = () => {
    setSearch('');
    setTypeFilter([]);
    setCrFilter([]);
    setSourceFilter([]);
    setPage(0);
  };

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

  return (
    <div className="min-h-screen bg-gray-900 text-white">
      <div className="sticky top-0 z-10 bg-gray-900 border-b border-gray-800 px-6 py-4">
        <button onClick={() => navigate('/dm')}
          className="flex items-center gap-2 text-gray-400 hover:text-white">
          <ArrowLeft size={20} /> Back to Dashboard
        </button>
      </div>

      <div className="p-6">
      <h1 className="text-3xl font-bold mb-6">Bestiary</h1>

      <div className="grid grid-cols-1 md:grid-cols-4 gap-3 mb-6">
        <div className="relative">
          <Search className="absolute left-3 top-2.5 text-gray-400" size={18} />
          <input type="text" placeholder="Search monsters..."
            value={search} onChange={(e) => setSearch(e.target.value)}
            className="w-full pl-10 pr-4 py-2 bg-gray-800 rounded-lg border border-gray-700 focus:border-red-500 focus:outline-none" />
        </div>
        <MultiSelect
          options={types}
          selected={typeFilter}
          onChange={setTypeFilter}
          placeholder="All Types"
          accentColor="red"
        />
        <MultiSelect
          options={crs}
          selected={crFilter}
          onChange={setCrFilter}
          placeholder="All CRs"
          renderLabel={(c) => `CR ${c}`}
          accentColor="red"
        />
        <MultiSelect
          options={sources}
          selected={sourceFilter}
          onChange={setSourceFilter}
          placeholder="All Sources"
          renderLabel={(s) => sourceName(s)}
          accentColor="red"
        />
      </div>

      <div className="flex items-center justify-between mb-4">
        <p className="text-gray-400 text-sm">{totalElements} monsters found</p>
        {hasFilters && (
          <button onClick={clearFilters}
            className="flex items-center gap-1.5 px-3 py-1.5 text-sm text-gray-400 hover:text-white bg-gray-800 hover:bg-gray-700 rounded-lg transition-colors">
            <RotateCcw size={14} /> Clear Filters
          </button>
        )}
      </div>

      <div className="flex gap-0">
        <div className={`min-w-0 transition-all duration-300 ${selectedMonster ? 'w-3/5' : 'w-full'}`}>
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
                    {monsters.map(m => (
                      <tr key={m.id} onClick={() => setSelectedMonster(m)}
                        className={`border-b border-gray-700/50 hover:bg-gray-700/50 cursor-pointer transition-colors ${selectedMonster?.id === m.id ? 'bg-gray-700/70' : ''}`}>
                        <td className="px-4 py-3 font-medium">{m.name}</td>
                        <td className="px-4 py-3 text-gray-400 hidden md:table-cell capitalize">{m.type}</td>
                        <td className="px-4 py-3 text-gray-400">{m.challengeRating}</td>
                        <td className="px-4 py-3 text-gray-400 hidden md:table-cell">{m.hitPoints}</td>
                        <td className="px-4 py-3 text-gray-400 hidden md:table-cell">{m.armourClass}</td>
                        <td className="px-4 py-3 text-gray-400 hidden lg:table-cell">{sourceName(m.source || '')}</td>
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

        {selectedMonster && (
          <div className="w-2/5 ml-4 flex-shrink-0 hidden md:block">
            <div className="sticky top-6">
              <div className="flex justify-end mb-2">
                <button onClick={() => setSelectedMonster(null)}
                  className="p-1 rounded-lg bg-gray-800 hover:bg-gray-700 text-gray-400 hover:text-white">
                  <X size={18} />
                </button>
              </div>
              <div className="max-h-[calc(100vh-8rem)] overflow-y-auto">
                <MonsterStatBlock monster={selectedMonster} />
              </div>
            </div>
          </div>
        )}
      </div>

      {selectedMonster && (
        <div className="md:hidden fixed inset-0 z-50 bg-gray-900/95 overflow-y-auto p-4">
          <button onClick={() => setSelectedMonster(null)}
            className="flex items-center gap-2 text-gray-400 hover:text-white mb-4">
            <ArrowLeft size={20} /> Back to Bestiary
          </button>
          <MonsterStatBlock monster={selectedMonster} />
        </div>
      )}
      </div>
    </div>
  );
}
