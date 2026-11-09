import { createContext } from 'react';
import type { GlossaryEntry } from '../types/reference';

/** What useGlossary() gives once the Glossary has loaded (M19). */
export interface GlossaryContextType {
  /** The entry a bold phrase or condition name names, if any */
  findTerm: (text: string) => GlossaryEntry | undefined;
  /** The entry with an ID */
  entry: (id: string) => GlossaryEntry | undefined;
  /** The patterns that find glossary terms in running text (utils/glossary.ts) */
  patterns: readonly RegExp[];
  /** Opens the glossary dialog on an entry */
  openTerm: (id: string) => void;
}

/** Filled by GlossaryProvider (GlossaryContext.tsx); read it with useGlossary() (hooks/useGlossary.ts). */
export const GlossaryContext = createContext<GlossaryContextType | null>(null);
