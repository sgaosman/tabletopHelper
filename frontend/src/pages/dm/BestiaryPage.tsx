import { useState } from 'react';
import { Search, ChevronLeft, ChevronRight, ChevronUp, ChevronDown, X, RotateCcw } from 'lucide-react';
import { apiErrorMessage } from '../../api/errors';
import { useMonsterFilterOptions, useMonsterList } from '../../queries/monsters';
import type { Monster } from '../../types/monster';
import MonsterStatBlock from '../../components/monster/MonsterStatBlock';
import MultiSelect from '../../components/common/MultiSelect';
import { sourceName } from '../../utils/sourceNames';
import NavBar from '../../components/common/NavBar';
import IconButton from '../../components/ui/IconButton';

function crBadgeClasses(cr: string): string {
  const num = cr.includes('/') ? 0.5 : parseFloat(cr);
  if (num <= 2)  return 'text-buff bg-buff-bg border border-buff-border';
  if (num <= 10) return 'text-hp-wounded bg-trans-bg border border-div-border';
  return 'text-debuff bg-debuff-bg border border-debuff-border';
}

const COLUMNS: { label: string; field: string; hideClass?: string }[] = [
  { label: 'Name', field: 'name' },
  { label: 'Type', field: 'type', hideClass: 'hidden md:table-cell' },
  { label: 'CR', field: 'challenge_rating' },
  { label: 'HP', field: 'hit_points', hideClass: 'hidden md:table-cell' },
  { label: 'AC', field: 'armour_class', hideClass: 'hidden md:table-cell' },
  { label: 'Source', field: 'source', hideClass: 'hidden lg:table-cell' },
];

export default function BestiaryPage() {
  const [selectedMonster, setSelectedMonster] = useState<Monster | null>(null);
  const [search, setSearch] = useState('');
  const [typeFilter, setTypeFilter] = useState<string[]>([]);
  const [crFilter, setCrFilter] = useState<string[]>([]);
  const [sourceFilter, setSourceFilter] = useState<string[]>([]);
  const [page, setPage] = useState(0);
  const [sortField, setSortField] = useState('name');
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc');

  const { data: types = [] } = useMonsterFilterOptions('types');
  const { data: crs = [] } = useMonsterFilterOptions('challenge-ratings');
  const { data: sources = [] } = useMonsterFilterOptions('sources');

  // While another page or filter loads, the current results stay on screen, dimmed
  const monstersQuery = useMonsterList({
    name: search || undefined,
    type: typeFilter.length ? typeFilter.join(',') : undefined,
    cr: crFilter.length ? crFilter.join(',') : undefined,
    source: sourceFilter.length ? sourceFilter.join(',') : undefined,
    page,
    size: 20,
    sort: `${sortField},${sortDir}`,
  });
  const monsters = monstersQuery.data?.content ?? [];
  const totalPages = monstersQuery.data?.totalPages ?? 0;
  const totalElements = monstersQuery.data?.totalElements ?? 0;

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
    <div className="min-h-screen bg-page">
      <NavBar />

      <div className="max-w-7xl mx-auto px-6 py-8">
        <h1 className="font-heading text-[19px] font-semibold tracking-[0.02em] text-ink mb-1">Bestiary</h1>
        <p className="font-body text-[15px] font-semibold text-muted mb-6">Browse monsters from all sourcebooks</p>

        <div className="grid grid-cols-1 md:grid-cols-4 gap-3 mb-4">
          <div className="relative">
            <Search className="absolute left-3 top-2.5 text-faint" size={16} />
            <input type="text" placeholder="Search monsters..."
              value={search} onChange={(e) => { setSearch(e.target.value); setPage(0); }}
              className="w-full pl-9 pr-4 py-2 bg-card border border-rule font-body text-[16px] font-semibold text-ink placeholder-faint focus:border-muted focus:outline-none" />
          </div>
          <MultiSelect options={types} selected={typeFilter} onChange={(v) => { setTypeFilter(v); setPage(0); }} placeholder="All Types" accentColor="red" />
          <MultiSelect options={crs} selected={crFilter} onChange={(v) => { setCrFilter(v); setPage(0); }} placeholder="All CRs" renderLabel={(c) => `CR ${c}`} accentColor="red" />
          <MultiSelect options={sources} selected={sourceFilter} onChange={(v) => { setSourceFilter(v); setPage(0); }} placeholder="All Sources" renderLabel={(s) => sourceName(s)} accentColor="red" />
        </div>

        <div className="flex items-center justify-between mb-4">
          <p className="font-body text-[14px] font-semibold text-muted">{totalElements} monsters found</p>
          {hasFilters && (
            <button onClick={clearFilters}
              className="flex items-center gap-1.5 px-3 py-1 font-heading text-[11px] font-semibold tracking-[0.04em] text-muted hover:text-ink transition-colors">
              <RotateCcw size={12} /> Clear Filters
            </button>
          )}
        </div>

        <div className="flex gap-0">
          <div className={`min-w-0 transition-all duration-300 ${selectedMonster ? 'w-3/5' : 'w-full'}`}>
            {monstersQuery.isPending ? (
              <div className="text-center py-12 font-body text-[16px] text-muted">Loading...</div>
            ) : monstersQuery.isError ? (
              <p role="alert" className="text-center py-12 font-body text-[16px] text-debuff">{apiErrorMessage(monstersQuery.error)}</p>
            ) : (
              <>
                <div aria-busy={monstersQuery.isPlaceholderData}
                  className={`bg-card border border-rule overflow-hidden transition-opacity ${monstersQuery.isPlaceholderData ? 'opacity-60' : ''}`}>
                  <table className="w-full">
                    <thead>
                      <tr className="border-b border-rule bg-page">
                        {COLUMNS.map(col => (
                          <th key={col.field}
                            onClick={() => handleSort(col.field)}
                            className={`px-3 py-2.5 text-left font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-faint cursor-pointer hover:text-ink select-none ${col.hideClass || ''}`}>
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
                          className={`border-b border-rule-light hover:bg-page-alt cursor-pointer transition-colors ${selectedMonster?.id === m.id ? 'bg-page-alt' : ''}`}>
                          <td className="px-3 py-2 font-heading text-[15px] font-bold text-monster">{m.name}</td>
                          <td className="px-3 py-2 font-body text-[15px] font-semibold text-muted hidden md:table-cell capitalize">{m.type}</td>
                          <td className="px-3 py-2 hidden md:table-cell">
                            <span className={`inline-block font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5 ${crBadgeClasses(m.challengeRating ?? '')}`}>
                              {m.challengeRating}
                            </span>
                          </td>
                          <td className="px-3 py-2 font-body text-[15px] font-semibold text-muted hidden md:table-cell">{m.hitPoints}</td>
                          <td className="px-3 py-2 font-body text-[15px] font-semibold text-muted hidden md:table-cell">{m.armourClass}</td>
                          <td className="px-3 py-2 font-body text-[15px] font-semibold text-faint hidden lg:table-cell">{sourceName(m.source || '')}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>

                {totalPages > 1 && (
                  <div className="flex items-center justify-center gap-4 mt-6">
                    <IconButton label="Previous page" onClick={() => setPage(p => Math.max(0, p - 1))} disabled={page === 0}
                      className="p-2 bg-card border border-rule hover:border-muted disabled:opacity-40 disabled:cursor-not-allowed transition-colors">
                      <ChevronLeft size={18} className="text-muted" />
                    </IconButton>
                    <span className="font-body text-[15px] font-semibold text-muted">Page {page + 1} of {totalPages}</span>
                    <IconButton label="Next page" onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))} disabled={page >= totalPages - 1}
                      className="p-2 bg-card border border-rule hover:border-muted disabled:opacity-40 disabled:cursor-not-allowed transition-colors">
                      <ChevronRight size={18} className="text-muted" />
                    </IconButton>
                  </div>
                )}
              </>
            )}
          </div>

          {selectedMonster && (
            <div className="w-2/5 ml-4 flex-shrink-0 hidden md:block">
              <div className="sticky top-16">
                <div className="flex justify-end mb-2">
                  <IconButton label="Close monster details" onClick={() => setSelectedMonster(null)}
                    className="p-1 bg-card border border-rule hover:border-muted text-faint hover:text-ink transition-colors">
                    <X size={16} />
                  </IconButton>
                </div>
                <div className="max-h-[calc(100vh-8rem)] overflow-y-auto">
                  <MonsterStatBlock monster={selectedMonster} />
                </div>
              </div>
            </div>
          )}
        </div>

        {selectedMonster && (
          <div className="md:hidden fixed inset-0 z-50 bg-page overflow-y-auto p-4">
            <button onClick={() => setSelectedMonster(null)}
              className="flex items-center gap-2 font-body text-[16px] font-semibold text-muted hover:text-ink mb-4">
              <ChevronLeft size={18} /> Back to Bestiary
            </button>
            <MonsterStatBlock monster={selectedMonster} />
          </div>
        )}
      </div>
    </div>
  );
}
