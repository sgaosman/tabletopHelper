import { useContext } from 'react';
import { GlossaryContext, type GlossaryContextType } from '../context/glossary';

/**
 * The Glossary from the nearest GlossaryProvider (M19), or null outside one or while it loads. Text then shows
 * its terms without links.
 */
export function useGlossary(): GlossaryContextType | null {
  return useContext(GlossaryContext);
}
