import { useCallback } from 'react';
import { keepPreviousData, queryOptions, useQueries, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import {
  getAllConditions, getClassTables, getItemRarities, getItemSources, getItemTypes, getOptionalFeatures, getQuickReference,
  getSpellClasses, getSpellSchools, getSpellSources, getSpellSubclasses, getSpellTargeting, getSubclasses, searchItems,
  searchSpells,
} from '../api/referenceApi';
import type { ItemSearchParams, SpellSearchParams } from '../types/reference';
import { queryKeys } from './keys';
import { REFERENCE_STALE_TIME } from './queryClient';

/**
 * One page of the spell list, searched, filtered and sorted. The last page stays on screen while the next loads.
 * With enabled false nothing is fetched.
 */
export function useSpellSearch(params: SpellSearchParams, { enabled = true }: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: queryKeys.reference.spells(params),
    queryFn: () => searchSpells(params),
    staleTime: REFERENCE_STALE_TIME,
    placeholderData: keepPreviousData,
    enabled,
  });
}

const SPELL_FILTER_OPTIONS = {
  schools: getSpellSchools,
  sources: getSpellSources,
  classes: getSpellClasses,
};

/** The values one of the spell list's filters offers. */
export function useSpellFilterOptions(filter: keyof typeof SPELL_FILTER_OPTIONS) {
  return useQuery({
    queryKey: queryKeys.reference.spellFilterOptions(filter),
    queryFn: SPELL_FILTER_OPTIONS[filter],
    staleTime: REFERENCE_STALE_TIME,
  });
}

function allSubclasses(results: UseQueryResult<string[]>[]): string[] {
  return results.flatMap(result => result.data ?? []);
}

/**
 * The subclasses with spell lists of their own in each of `classNames`, such as "Cleric (Life)", in the order
 * of the classes. Each class's subclasses are fetched once, the first time it is picked.
 */
export function useSpellSubclasses(classNames: string[]) {
  return useQueries({
    queries: classNames.map(className => ({
      queryKey: queryKeys.reference.spellSubclasses(className),
      queryFn: () => getSpellSubclasses(className),
      staleTime: REFERENCE_STALE_TIME,
    })),
    combine: allSubclasses,
  });
}

function spellTargetingQuery(spellName: string, slotLevel: number) {
  return queryOptions({
    queryKey: queryKeys.reference.spellTargeting(spellName, slotLevel),
    queryFn: () => getSpellTargeting(spellName, slotLevel),
    staleTime: REFERENCE_STALE_TIME,
  });
}

/**
 * Who a spell can target when it is cast with a slot of `slotLevel`. The lookup fails with a 404 for a spell
 * without an effect template, which leaves the targets unrestricted.
 */
export function useSpellTargeting(spellName: string, slotLevel: number) {
  return useQuery(spellTargetingQuery(spellName, slotLevel));
}

/**
 * Looks up who a spell can target when the caster picks it, as useSpellTargeting does. Each spell and slot
 * level is fetched once; the promise rejects when the lookup fails.
 */
export function useFetchSpellTargeting() {
  const queryClient = useQueryClient();
  return useCallback(
    (spellName: string, slotLevel: number) => queryClient.fetchQuery(spellTargetingQuery(spellName, slotLevel)),
    [queryClient],
  );
}

/** One page of the item list, searched, filtered and sorted. The last page stays on screen while the next loads. */
export function useItemSearch(params: ItemSearchParams) {
  return useQuery({
    queryKey: queryKeys.reference.items(params),
    queryFn: () => searchItems(params),
    staleTime: REFERENCE_STALE_TIME,
    placeholderData: keepPreviousData,
  });
}

const ITEM_FILTER_OPTIONS = {
  types: getItemTypes,
  rarities: getItemRarities,
  sources: getItemSources,
};

/** The values one of the item list's filters offers. */
export function useItemFilterOptions(filter: keyof typeof ITEM_FILTER_OPTIONS) {
  return useQuery({
    queryKey: queryKeys.reference.itemFilterOptions(filter),
    queryFn: ITEM_FILTER_OPTIONS[filter],
    staleTime: REFERENCE_STALE_TIME,
  });
}

/** Every condition, by name. */
export function useConditions() {
  return useQuery({
    queryKey: queryKeys.reference.conditions(),
    queryFn: getAllConditions,
    staleTime: REFERENCE_STALE_TIME,
  });
}

/** The chapters of the quick rules reference, as 5etools entries. */
/**
 * The class tables the server's rules use (D167, D169), for the creation wizard's and the sheet's numbers. They
 * change only with a release, so they are fetched once.
 */
export function useClassTables() {
  return useQuery({
    queryKey: queryKeys.reference.classTables(),
    queryFn: getClassTables,
    staleTime: REFERENCE_STALE_TIME,
  });
}

export function useQuickReference() {
  return useQuery({
    queryKey: queryKeys.reference.quickReference(),
    queryFn: getQuickReference,
    staleTime: REFERENCE_STALE_TIME,
  });
}

/** A class's subclasses, by name. */
export function useSubclasses(classId: string) {
  return useQuery({
    queryKey: queryKeys.reference.subclasses(classId),
    queryFn: () => getSubclasses(classId),
    staleTime: REFERENCE_STALE_TIME,
  });
}

/** The optional features of one type, such as 'FightingStyle', by name; a style each class has is listed once for each. */
export function useOptionalFeatures(type: string) {
  return useQuery({
    queryKey: queryKeys.reference.optionalFeatures(type),
    queryFn: () => getOptionalFeatures(type),
    staleTime: REFERENCE_STALE_TIME,
  });
}
