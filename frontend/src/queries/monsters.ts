import { keepPreviousData, skipToken, useQuery } from '@tanstack/react-query';
import {
  fuzzySearchMonsters, getMonsterChallengeRatings, getMonsterSources, getMonsterTypes, searchMonsters,
} from '../api/monsterApi';
import type { MonsterSearchParams } from '../types/monster';
import { queryKeys } from './keys';
import { REFERENCE_STALE_TIME } from './queryClient';

const SEARCH_RESULTS = 10;

/**
 * The monsters whose names come closest to `name`; nothing is fetched while it is empty. The last results
 * stay on screen while the next search loads.
 */
export function useMonsterSearch(name: string) {
  return useQuery({
    queryKey: queryKeys.monsters.search(name, SEARCH_RESULTS),
    queryFn: name ? () => fuzzySearchMonsters(name, SEARCH_RESULTS) : skipToken,
    staleTime: REFERENCE_STALE_TIME,
    placeholderData: keepPreviousData,
  });
}

/** One page of the bestiary, searched, filtered and sorted. The last page stays on screen while the next loads. */
export function useMonsterList(params: MonsterSearchParams) {
  return useQuery({
    queryKey: queryKeys.monsters.list(params),
    queryFn: () => searchMonsters(params),
    staleTime: REFERENCE_STALE_TIME,
    placeholderData: keepPreviousData,
  });
}

const MONSTER_FILTER_OPTIONS = {
  types: getMonsterTypes,
  'challenge-ratings': getMonsterChallengeRatings,
  sources: getMonsterSources,
};

/** The values one of the bestiary's filters offers. */
export function useMonsterFilterOptions(filter: keyof typeof MONSTER_FILTER_OPTIONS) {
  return useQuery({
    queryKey: queryKeys.monsters.filterOptions(filter),
    queryFn: MONSTER_FILTER_OPTIONS[filter],
    staleTime: REFERENCE_STALE_TIME,
  });
}
