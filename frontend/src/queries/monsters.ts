import { keepPreviousData, skipToken, useQuery } from '@tanstack/react-query';
import { fuzzySearchMonsters } from '../api/monsterApi';
import { queryKeys } from './keys';

const SEARCH_RESULTS = 10;

/**
 * The monsters whose names come closest to `name`; nothing is fetched while it is empty. The last results
 * stay on screen while the next search loads.
 */
export function useMonsterSearch(name: string) {
  return useQuery({
    queryKey: queryKeys.monsters.search(name, SEARCH_RESULTS),
    queryFn: name ? () => fuzzySearchMonsters(name, SEARCH_RESULTS) : skipToken,
    // Monsters are reference data, which doesn't change while the app runs
    staleTime: Infinity,
    placeholderData: keepPreviousData,
  });
}
