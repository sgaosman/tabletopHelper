import { useState, useEffect, useCallback } from 'react';
import { Search, ChevronLeft, ChevronRight, ChevronUp, ChevronDown, X, RotateCcw } from 'lucide-react';
import { searchItems, getItemTypes, getItemRarities, getItemSources } from '../../api/referenceApi';
import type { Item } from '../../types/reference';
import ItemCard from '../../components/reference/ItemCard';
import MultiSelect from '../../components/common/MultiSelect';
import { sourceName } from '../../utils/sourceNames';
import NavBar from '../../components/common/NavBar';

const RARITY_COLOURS: Record<string, { text: string; bg: string; border: string }> = {
  'common':    { text: '#78716C', bg: '#F5F5F0', border: '#E7E5E4' },
  'uncommon':  { text: '#166534', bg: '#F0FDF4', border: '#BBF7D0' },
  'rare':      { text: '#4F46E5', bg: '#EEF2FF', border: '#C7D2FE' },
  'very rare': { text: '#9333EA', bg: '#FAF5FF', border: '#D8B4FE' },
  'legendary': { text: '#B45309', bg: '#FFFBEB', border: '#FDE68A' },
  'artifact':  { text: '#991B1B', bg: '#FEF2F2', border: '#FECACA' },
};

const COLUMNS: { label: string; field: string; hideClass?: string }[] = [
  { label: 'Name', field: 'name' },
  { label: 'Type', field: 'type', hideClass: 'hidden md:table-cell' },
  { label: 'Rarity', field: 'rarity', hideClass: 'hidden md:table-cell' },
  { label: 'Cost', field: 'cost', hideClass: 'hidden lg:table-cell' },
  { label: 'Source', field: 'source', hideClass: 'hidden lg:table-cell' },
];

export default function ItemsPage() {
  const [items, setItems] = useState<Item[]>([]);
  const [selectedItem, setSelectedItem] = useState<Item | null>(null);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [typeFilter, setTypeFilter] = useState<string[]>([]);
  const [rarityFilter, setRarityFilter] = useState<string[]>([]);
  const [sourceFilter, setSourceFilter] = useState<string[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [sortField, setSortField] = useState('name');
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc');

  const [types, setTypes] = useState<string[]>([]);
  const [rarities, setRarities] = useState<string[]>([]);
  const [sources, setSources] = useState<string[]>([]);

  useEffect(() => {
    Promise.all([getItemTypes(), getItemRarities(), getItemSources()])
      .then(([t, r, s]) => { setTypes(t); setRarities(r); setSources(s); });
  }, []);

  const loadItems = useCallback(async () => {
    setLoading(true);
    try {
      const result = await searchItems({
        name: search || undefined,
        type: typeFilter.length ? typeFilter.join(',') : undefined,
        rarity: rarityFilter.length ? rarityFilter.join(',') : undefined,
        source: sourceFilter.length ? sourceFilter.join(',') : undefined,
        page, size: 20, sort: `${sortField},${sortDir}`,
      });
      setItems(result.content);
      setTotalPages(result.totalPages);
      setTotalElements(result.totalElements);
    } finally {
      setLoading(false);
    }
  }, [search, typeFilter, rarityFilter, sourceFilter, page, sortField, sortDir]);

  useEffect(() => { loadItems(); }, [loadItems]);
  useEffect(() => { setPage(0); }, [search, typeFilter, rarityFilter, sourceFilter]);

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
        <p className="font-body text-[13px] font-medium text-muted mb-6">Browse weapons, armour, and magic items</p>

        <div className="grid grid-cols-1 md:grid-cols-4 gap-3 mb-4">
          <div className="relative">
            <Search className="absolute left-3 top-2.5 text-faint" size={16} />
            <input type="text" placeholder="Search items..."
              value={search} onChange={(e) => setSearch(e.target.value)}
              className="w-full pl-9 pr-4 py-2 bg-card border border-rule font-body text-[14px] font-medium text-ink placeholder-faint focus:border-muted focus:outline-none" />
          </div>
          <MultiSelect options={types} selected={typeFilter} onChange={setTypeFilter} placeholder="All Types" accentColor="emerald" />
          <MultiSelect options={rarities} selected={rarityFilter} onChange={setRarityFilter} placeholder="All Rarities" accentColor="emerald" />
          <MultiSelect options={sources} selected={sourceFilter} onChange={setSourceFilter} placeholder="All Sources" renderLabel={(s) => sourceName(s)} accentColor="emerald" />
        </div>

        <div className="flex items-center justify-between mb-4">
          <p className="font-body text-[12px] font-medium text-muted">{totalElements} items found</p>
          {hasFilters && (
            <button onClick={clearFilters}
              className="flex items-center gap-1.5 px-3 py-1 font-heading text-[9px] font-medium tracking-[0.04em] text-muted hover:text-ink transition-colors">
              <RotateCcw size={12} /> Clear Filters
            </button>
          )}
        </div>

        <div className="flex gap-0">
          <div className={`min-w-0 transition-all duration-300 ${selectedItem ? 'w-3/5' : 'w-full'}`}>
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
                      {items.map(item => {
                        const rarity = RARITY_COLOURS[item.rarity?.toLowerCase() || ''];
                        return (
                          <tr key={item.id} onClick={() => setSelectedItem(item)}
                            className={`border-b border-rule-light hover:bg-page-alt cursor-pointer transition-colors ${selectedItem?.id === item.id ? 'bg-page-alt' : ''}`}>
                            <td className="px-3 py-2">
                              <span className="font-heading text-[13px] font-bold text-ink">{item.name}</span>
                              {item.requiresAttunement && <span className="ml-2 font-heading text-[9px] font-medium tracking-[0.02em] px-1.5 py-0.5 text-cls-warlock border border-illu-border bg-illu-bg">A</span>}
                            </td>
                            <td className="px-3 py-2 font-body text-[13px] font-medium text-muted hidden md:table-cell">{item.type}</td>
                            <td className="px-3 py-2 hidden md:table-cell">
                              {rarity ? (
                                <span className="font-heading text-[9px] font-medium tracking-[0.02em] px-1.5 py-0.5 capitalize" style={{ color: rarity.text, backgroundColor: rarity.bg, border: `1px solid ${rarity.border}` }}>
                                  {item.rarity}
                                </span>
                              ) : (
                                <span className="font-body text-[13px] text-faint">-</span>
                              )}
                            </td>
                            <td className="px-3 py-2 font-body text-[13px] font-medium text-muted hidden lg:table-cell">{item.cost || '-'}</td>
                            <td className="px-3 py-2 font-body text-[13px] font-medium text-faint hidden lg:table-cell">{sourceName(item.source || '')}</td>
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
              className="flex items-center gap-2 font-body text-[14px] font-medium text-muted hover:text-ink mb-4">
              <ChevronLeft size={18} /> Back to Items
            </button>
            <ItemCard item={selectedItem} />
          </div>
        )}
      </div>
    </div>
  );
}
