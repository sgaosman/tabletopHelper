import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, Search, ChevronLeft, ChevronRight, ChevronUp, ChevronDown, X, RotateCcw } from 'lucide-react';
import { searchItems, getItemTypes, getItemRarities, getItemSources } from '../../api/referenceApi';
import type { Item } from '../../types/reference';
import ItemCard from '../../components/reference/ItemCard';
import MultiSelect from '../../components/common/MultiSelect';
import { sourceName } from '../../utils/sourceNames';

const RARITY_COLORS: Record<string, string> = {
  'common': 'text-gray-400',
  'uncommon': 'text-green-400',
  'rare': 'text-blue-400',
  'very rare': 'text-purple-400',
  'legendary': 'text-orange-400',
  'artifact': 'text-red-400',
};

const COLUMNS: { label: string; field: string; hideClass?: string }[] = [
  { label: 'Name', field: 'name' },
  { label: 'Type', field: 'type', hideClass: 'hidden md:table-cell' },
  { label: 'Rarity', field: 'rarity', hideClass: 'hidden md:table-cell' },
  { label: 'Cost', field: 'cost', hideClass: 'hidden lg:table-cell' },
  { label: 'Source', field: 'source', hideClass: 'hidden lg:table-cell' },
];

export default function ItemsPage() {
  const navigate = useNavigate();
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
        page,
        size: 20,
        sort: `${sortField},${sortDir}`,
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
  const clearFilters = () => {
    setSearch('');
    setTypeFilter([]);
    setRarityFilter([]);
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
      <h1 className="text-3xl font-bold mb-6">Items</h1>

      <div className="grid grid-cols-1 md:grid-cols-4 gap-3 mb-6">
        <div className="relative">
          <Search className="absolute left-3 top-2.5 text-gray-400" size={18} />
          <input type="text" placeholder="Search items..."
            value={search} onChange={(e) => setSearch(e.target.value)}
            className="w-full pl-10 pr-4 py-2 bg-gray-800 rounded-lg border border-gray-700 focus:border-emerald-500 focus:outline-none" />
        </div>
        <MultiSelect
          options={types}
          selected={typeFilter}
          onChange={setTypeFilter}
          placeholder="All Types"
          accentColor="emerald"
        />
        <MultiSelect
          options={rarities}
          selected={rarityFilter}
          onChange={setRarityFilter}
          placeholder="All Rarities"
          accentColor="emerald"
        />
        <MultiSelect
          options={sources}
          selected={sourceFilter}
          onChange={setSourceFilter}
          placeholder="All Sources"
          renderLabel={(s) => sourceName(s)}
          accentColor="emerald"
        />
      </div>

      <div className="flex items-center justify-between mb-4">
        <p className="text-gray-400 text-sm">{totalElements} items found</p>
        {hasFilters && (
          <button onClick={clearFilters}
            className="flex items-center gap-1.5 px-3 py-1.5 text-sm text-gray-400 hover:text-white bg-gray-800 hover:bg-gray-700 rounded-lg transition-colors">
            <RotateCcw size={14} /> Clear Filters
          </button>
        )}
      </div>

      <div className="flex gap-0">
        <div className={`min-w-0 transition-all duration-300 ${selectedItem ? 'w-3/5' : 'w-full'}`}>
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
                    {items.map(item => (
                      <tr key={item.id} onClick={() => setSelectedItem(item)}
                        className={`border-b border-gray-700/50 hover:bg-gray-700/50 cursor-pointer transition-colors ${selectedItem?.id === item.id ? 'bg-gray-700/70' : ''}`}>
                        <td className="px-4 py-3 font-medium">
                          {item.name}
                          {item.requiresAttunement && <span className="ml-2 text-xs text-purple-400">A</span>}
                        </td>
                        <td className="px-4 py-3 text-gray-400 hidden md:table-cell">{item.type}</td>
                        <td className={`px-4 py-3 hidden md:table-cell capitalize ${RARITY_COLORS[item.rarity?.toLowerCase() || ''] || 'text-gray-400'}`}>
                          {item.rarity || '-'}
                        </td>
                        <td className="px-4 py-3 text-gray-400 hidden lg:table-cell">{item.cost || '-'}</td>
                        <td className="px-4 py-3 text-gray-400 hidden lg:table-cell">{sourceName(item.source || '')}</td>
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

        {selectedItem && (
          <div className="w-2/5 ml-4 flex-shrink-0 hidden md:block">
            <div className="sticky top-6">
              <div className="flex justify-end mb-2">
                <button onClick={() => setSelectedItem(null)}
                  className="p-1 rounded-lg bg-gray-800 hover:bg-gray-700 text-gray-400 hover:text-white">
                  <X size={18} />
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
        <div className="md:hidden fixed inset-0 z-50 bg-gray-900/95 overflow-y-auto p-4">
          <button onClick={() => setSelectedItem(null)}
            className="flex items-center gap-2 text-gray-400 hover:text-white mb-4">
            <ArrowLeft size={20} /> Back to Items
          </button>
          <ItemCard item={selectedItem} />
        </div>
      )}
      </div>
    </div>
  );
}
