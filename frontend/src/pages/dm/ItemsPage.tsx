import { useState } from 'react';
import { Search, ChevronLeft, ChevronRight, ChevronUp, ChevronDown, X, RotateCcw } from 'lucide-react';
import { apiErrorMessage } from '../../api/errors';
import { useItemFilterOptions, useItemSearch } from '../../queries/reference';
import type { Item } from '../../types/reference';
import ItemCard from '../../components/reference/ItemCard';
import MultiSelect from '../../components/common/MultiSelect';
import { sourceName } from '../../utils/sourceNames';
import NavBar from '../../components/common/NavBar';

const RARITY_COLOURS: Record<string, { text: string; bg: string; border: string }> = {
  'common':    { text: 'var(--color-faint)', bg: 'var(--color-page-alt)', border: 'var(--color-rule-light)' },
  'uncommon':  { text: 'var(--color-buff)', bg: 'var(--color-buff-bg)', border: 'var(--color-buff-border)' },
  'rare':      { text: 'var(--color-abj)', bg: 'var(--color-abj-bg)', border: 'var(--color-abj-border)' },
  'very rare': { text: 'var(--color-cls-sorcerer)', bg: 'var(--color-cls-sorcerer-bg)', border: 'var(--color-illu-border)' },
  'legendary': { text: 'var(--color-trans)', bg: 'var(--color-trans-bg)', border: 'var(--color-trans-border)' },
  'artifact':  { text: 'var(--color-debuff)', bg: 'var(--color-debuff-bg)', border: 'var(--color-debuff-border)' },
};

const COLUMNS: { label: string; field: string; hideClass?: string }[] = [
  { label: 'Name', field: 'name' },
  { label: 'Type', field: 'type', hideClass: 'hidden md:table-cell' },
  { label: 'Rarity', field: 'rarity', hideClass: 'hidden md:table-cell' },
  { label: 'Cost', field: 'cost', hideClass: 'hidden lg:table-cell' },
  { label: 'Source', field: 'source', hideClass: 'hidden lg:table-cell' },
];

export default function ItemsPage() {
  const [selectedItem, setSelectedItem] = useState<Item | null>(null);
  const [search, setSearch] = useState('');
  const [typeFilter, setTypeFilter] = useState<string[]>([]);
  const [rarityFilter, setRarityFilter] = useState<string[]>([]);
  const [sourceFilter, setSourceFilter] = useState<string[]>([]);
  const [page, setPage] = useState(0);
  const [sortField, setSortField] = useState('name');
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc');

  const { data: types = [] } = useItemFilterOptions('types');
  const { data: rarities = [] } = useItemFilterOptions('rarities');
  const { data: sources = [] } = useItemFilterOptions('sources');

  // While another page or filter loads, the current results stay on screen, dimmed
  const itemsQuery = useItemSearch({
    name: search || undefined,
    type: typeFilter.length ? typeFilter.join(',') : undefined,
    rarity: rarityFilter.length ? rarityFilter.join(',') : undefined,
    source: sourceFilter.length ? sourceFilter.join(',') : undefined,
    page, size: 20, sort: `${sortField},${sortDir}`,
  });
  const items = itemsQuery.data?.content ?? [];
  const totalPages = itemsQuery.data?.totalPages ?? 0;
  const totalElements = itemsQuery.data?.totalElements ?? 0;

  const hasFilters = search || typeFilter.length > 0 || rarityFilter.length > 0 || sourceFilter.length > 0;
  const clearFilters = () => { setSearch(''); setTypeFilter([]); setRarityFilter([]); setSourceFilter([]); setPage(0); };

  const handleSort = (field: string) => {
    if (field === sortField) setSortDir(d => d === 'asc' ? 'desc' : 'asc');
    else { setSortField(field); setSortDir('asc'); }
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
        <h1 className="font-heading text-[19px] font-semibold tracking-[0.02em] text-ink mb-1">Items</h1>
        <p className="font-body text-[15px] font-semibold text-muted mb-6">Browse weapons, armour, and magic items</p>

        <div className="grid grid-cols-1 md:grid-cols-4 gap-3 mb-4">
          <div className="relative">
            <Search className="absolute left-3 top-2.5 text-faint" size={16} />
            <input type="text" placeholder="Search items..."
              value={search} onChange={(e) => { setSearch(e.target.value); setPage(0); }}
              className="w-full pl-9 pr-4 py-2 bg-card border border-rule font-body text-[16px] font-semibold text-ink placeholder-faint focus:border-muted focus:outline-none" />
          </div>
          <MultiSelect options={types} selected={typeFilter} onChange={(v) => { setTypeFilter(v); setPage(0); }} placeholder="All Types" accentColor="emerald" />
          <MultiSelect options={rarities} selected={rarityFilter} onChange={(v) => { setRarityFilter(v); setPage(0); }} placeholder="All Rarities" accentColor="emerald" />
          <MultiSelect options={sources} selected={sourceFilter} onChange={(v) => { setSourceFilter(v); setPage(0); }} placeholder="All Sources" renderLabel={(s) => sourceName(s)} accentColor="emerald" />
        </div>

        <div className="flex items-center justify-between mb-4">
          <p className="font-body text-[14px] font-semibold text-muted">{totalElements} items found</p>
          {hasFilters && (
            <button onClick={clearFilters}
              className="flex items-center gap-1.5 px-3 py-1 font-heading text-[11px] font-semibold tracking-[0.04em] text-muted hover:text-ink transition-colors">
              <RotateCcw size={12} /> Clear Filters
            </button>
          )}
        </div>

        <div className="flex gap-0">
          <div className={`min-w-0 transition-all duration-300 ${selectedItem ? 'w-3/5' : 'w-full'}`}>
            {itemsQuery.isPending ? (
              <div className="text-center py-12 font-body text-[16px] text-muted">Loading...</div>
            ) : itemsQuery.isError ? (
              <p role="alert" className="text-center py-12 font-body text-[16px] text-debuff">{apiErrorMessage(itemsQuery.error)}</p>
            ) : (
              <>
                <div aria-busy={itemsQuery.isPlaceholderData}
                  className={`bg-card border border-rule overflow-hidden transition-opacity ${itemsQuery.isPlaceholderData ? 'opacity-60' : ''}`}>
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
                      {items.map(item => {
                        const rarity = RARITY_COLOURS[item.rarity?.toLowerCase() || ''];
                        return (
                          <tr key={item.id} onClick={() => setSelectedItem(item)}
                            className={`border-b border-rule-light hover:bg-page-alt cursor-pointer transition-colors ${selectedItem?.id === item.id ? 'bg-page-alt' : ''}`}>
                            <td className="px-3 py-2">
                              <span className="font-heading text-[15px] font-bold text-ink">{item.name}</span>
                              {item.requiresAttunement && <span className="ml-2 font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5 text-cls-warlock border border-illu-border bg-illu-bg">A</span>}
                            </td>
                            <td className="px-3 py-2 font-body text-[15px] font-semibold text-muted hidden md:table-cell">{item.type}</td>
                            <td className="px-3 py-2 hidden md:table-cell">
                              {rarity ? (
                                <span className="font-heading text-[11px] font-semibold tracking-[0.02em] px-1.5 py-0.5 capitalize" style={{ color: rarity.text, backgroundColor: rarity.bg, border: `1px solid ${rarity.border}` }}>
                                  {item.rarity}
                                </span>
                              ) : (
                                <span className="font-body text-[15px] text-faint">-</span>
                              )}
                            </td>
                            <td className="px-3 py-2 font-body text-[15px] font-semibold text-muted hidden lg:table-cell">{item.cost || '-'}</td>
                            <td className="px-3 py-2 font-body text-[15px] font-semibold text-faint hidden lg:table-cell">{sourceName(item.source || '')}</td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>

                {totalPages > 1 && (
                  <div className="flex items-center justify-center gap-4 mt-6">
                    <button onClick={() => setPage(p => Math.max(0, p - 1))} disabled={page === 0}
                      className="p-2 bg-card border border-rule hover:border-muted disabled:opacity-40 disabled:cursor-not-allowed transition-colors">
                      <ChevronLeft size={18} className="text-muted" />
                    </button>
                    <span className="font-body text-[15px] font-semibold text-muted">Page {page + 1} of {totalPages}</span>
                    <button onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))} disabled={page >= totalPages - 1}
                      className="p-2 bg-card border border-rule hover:border-muted disabled:opacity-40 disabled:cursor-not-allowed transition-colors">
                      <ChevronRight size={18} className="text-muted" />
                    </button>
                  </div>
                )}
              </>
            )}
          </div>

          {selectedItem && (
            <div className="w-2/5 ml-4 flex-shrink-0 hidden md:block">
              <div className="sticky top-16">
                <div className="flex justify-end mb-2">
                  <button onClick={() => setSelectedItem(null)}
                    className="p-1 bg-card border border-rule hover:border-muted text-faint hover:text-ink transition-colors">
                    <X size={16} />
                  </button>
                </div>
                <div className="max-h-[calc(100vh-8rem)] overflow-y-auto">
                  <ItemCard item={selectedItem} />
                </div>
              </div>
            </div>
          )}
        </div>

        {selectedItem && (
          <div className="md:hidden fixed inset-0 z-50 bg-page overflow-y-auto p-4">
            <button onClick={() => setSelectedItem(null)}
              className="flex items-center gap-2 font-body text-[16px] font-semibold text-muted hover:text-ink mb-4">
              <ChevronLeft size={18} /> Back to Items
            </button>
            <ItemCard item={selectedItem} />
          </div>
        )}
      </div>
    </div>
  );
}
