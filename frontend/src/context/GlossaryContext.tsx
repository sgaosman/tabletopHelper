import { useCallback, useMemo, useState, type ReactNode } from 'react';
import { useAuth } from '../hooks/useAuth';
import { useGlossaryEntries } from '../queries/reference';
import { buildTermIndex, findTerm, glossaryPatterns } from '../utils/glossary';
import GlossaryModal from '../components/glossary/GlossaryModal';
import { GlossaryContext, type GlossaryContextType } from './glossary';

/**
 * Loads the Glossary once signed in and links description terms to it (M19). It renders the glossary dialog after
 * its children, so the dialog stacks above any dialog the term was in, such as AsiModal's.
 */
export function GlossaryProvider({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  const { data: entries } = useGlossaryEntries({ enabled: status === 'authenticated' });
  const [openId, setOpenId] = useState<string | null>(null);
  const openTerm = useCallback((id: string) => setOpenId(id), []);

  const value = useMemo<GlossaryContextType | null>(() => {
    if (!entries) return null;
    const index = buildTermIndex(entries);
    const byId = new Map(entries.map(e => [e.id, e]));
    return {
      findTerm: text => findTerm(index, text),
      entry: id => byId.get(id),
      patterns: glossaryPatterns(entries),
      openTerm,
    };
  }, [entries, openTerm]);

  const open = openId != null ? value?.entry(openId) : undefined;

  return (
    <GlossaryContext.Provider value={value}>
      {children}
      {open && value && (
        <GlossaryModal entry={open} related={id => value.entry(id)} onSelect={setOpenId} onClose={() => setOpenId(null)} />
      )}
    </GlossaryContext.Provider>
  );
}
