import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Type-ahead search: runs fetchResults shortly after the query stops changing and hands what it finds
 * to applyResults. Only the newest search is applied, so a slow earlier response can't overwrite a
 * later one. Nothing runs on the first render, where the caller's own initial load fetches the list.
 * fetchResults returns undefined when there is nothing to search.
 *
 * searchNow searches straight away (Enter, or a filter change); searching is true while the newest
 * search is in flight.
 */
export function useTypeAhead<T>(
  query: string,
  fetchResults: () => Promise<T> | undefined,
  applyResults: (results: T) => void,
  delayMs = 250,
) {
  const [searching, setSearching] = useState(false);
  const callbacks = useRef({ fetchResults, applyResults });
  const lastQuery = useRef(query);
  const latestRequest = useRef(0);
  const pendingTimer = useRef<ReturnType<typeof setTimeout>>(undefined);

  // Always call the latest callbacks, so a search sees the current query and filters
  useEffect(() => {
    callbacks.current = { fetchResults, applyResults };
  });

  const searchNow = useCallback(() => {
    clearTimeout(pendingTimer.current);
    const request = ++latestRequest.current;
    const results = callbacks.current.fetchResults();
    if (!results) {
      setSearching(false);
      return;
    }
    setSearching(true);
    results
      .then(found => {
        if (request === latestRequest.current) callbacks.current.applyResults(found);
      })
      .catch(err => console.warn('Search failed', err))
      .finally(() => {
        if (request === latestRequest.current) setSearching(false);
      });
  }, []);

  useEffect(() => {
    if (query === lastQuery.current) return;
    lastQuery.current = query;
    const timer = setTimeout(searchNow, delayMs);
    pendingTimer.current = timer;
    return () => clearTimeout(timer);
  }, [query, delayMs, searchNow]);

  return { searchNow, searching };
}
