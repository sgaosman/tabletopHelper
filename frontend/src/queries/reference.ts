import { useCallback } from 'react';
import { keepPreviousData, queryOptions, useQueries, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import {
  getAllConditions, getBackgrounds, getClasses, getClassTables, getFeats, getGlossary, getItemRarities, getItemSources, getItemTypes,
  getOptionalFeatures, getQuickReference, getRaces, getSpellClasses, getSpellSchools, getSpellSources, getSpellSubclasses,
  getSpellTargeting, getSubclasses, searchItems, searchSpells,
} from '../api/referenceApi';
import type { ItemSearchParams, OptionalFeature, Spell, SpellSearchParams } from '../types/reference';
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

function spellByNameQuery(name: string) {
  return queryOptions({
    queryKey: queryKeys.reference.spellByName(name),
    // The search matches part of a name, so the spell of exactly that name is picked from what it finds
    queryFn: async (): Promise<Spell | null> => {
      const page = await searchSpells({ name, size: 20 });
      const wanted = name.trim().toLowerCase();
      return page.content.find(s => s.name.toLowerCase() === wanted) ?? page.content[0] ?? null;
    },
    staleTime: REFERENCE_STALE_TIME,
  });
}

/** The spell of a name, such as one on the sheet; null when there is none. With no name nothing is fetched. */
export function useSpellByName(name: string | null) {
  return useQuery({ ...spellByNameQuery(name ?? ''), enabled: !!name });
}

function spellsOf(results: UseQueryResult<Spell | null>[]): (Spell | null | undefined)[] {
  return results.map(result => result.data);
}

/** The spells of each of `names`, in order: undefined while one loads, null for a name with no spell. */
export function useSpellsByName(names: string[]) {
  return useQueries({ queries: names.map(spellByNameQuery), combine: spellsOf });
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

/**
 * The Glossary (M19), which GlossaryProvider links description terms to. It changes only with a release, so it is
 * fetched once; with enabled false, such as before signing in, nothing is fetched.
 */
export function useGlossaryEntries({ enabled = true }: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: queryKeys.reference.glossary(),
    queryFn: getGlossary,
    staleTime: REFERENCE_STALE_TIME,
    enabled,
  });
}

export function useQuickReference() {
  return useQuery({
    queryKey: queryKeys.reference.quickReference(),
    queryFn: getQuickReference,
    staleTime: REFERENCE_STALE_TIME,
  });
}

/** Every race, as the creation wizard lists them. */
export function racesQuery() {
  return queryOptions({
    queryKey: queryKeys.reference.races(),
    queryFn: () => getRaces(),
    staleTime: REFERENCE_STALE_TIME,
  });
}

export function useRaces() {
  return useQuery(racesQuery());
}

/** Every class, as the creation wizard lists them. */
export function classesQuery() {
  return queryOptions({
    queryKey: queryKeys.reference.classes(),
    queryFn: getClasses,
    staleTime: REFERENCE_STALE_TIME,
  });
}

export function useClasses() {
  return useQuery(classesQuery());
}

/** Every background, as the creation wizard lists them. */
export function backgroundsQuery() {
  return queryOptions({
    queryKey: queryKeys.reference.backgrounds(),
    queryFn: getBackgrounds,
    staleTime: REFERENCE_STALE_TIME,
  });
}

export function useBackgrounds() {
  return useQuery(backgroundsQuery());
}

/** A class's subclasses, by name. */
export function subclassesQuery(classId: string) {
  return queryOptions({
    queryKey: queryKeys.reference.subclasses(classId),
    queryFn: () => getSubclasses(classId),
    staleTime: REFERENCE_STALE_TIME,
  });
}

export function useSubclasses(classId: string) {
  return useQuery(subclassesQuery(classId));
}

/** Every feat, by name, with its prerequisite, ability score increases, effects and spells as typed records. */
export function featsQuery() {
  return queryOptions({
    queryKey: queryKeys.reference.feats(),
    queryFn: getFeats,
    staleTime: REFERENCE_STALE_TIME,
  });
}

export function useFeats() {
  return useQuery(featsQuery());
}

// The optional features of every type asked for, in one list
function allOptionalFeatures(results: UseQueryResult<OptionalFeature[]>[]): OptionalFeature[] {
  return results.flatMap(result => result.data ?? []);
}

/** The optional features of several types at once, such as a feat's Eldritch Invocations, each type cached alone. */
export function useOptionalFeaturesOf(types: string[]) {
  return useQueries({
    queries: types.map(type => ({
      queryKey: queryKeys.reference.optionalFeatures(type),
      queryFn: () => getOptionalFeatures(type),
      staleTime: REFERENCE_STALE_TIME,
    })),
    combine: allOptionalFeatures,
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
