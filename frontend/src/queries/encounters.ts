import { useMemo, type RefObject } from 'react';
import {
  queryOptions, skipToken, useMutation, useQuery, useQueryClient, type QueryClient, type QueryKey,
} from '@tanstack/react-query';
import type { AxiosResponse } from 'axios';
import { combatApi } from '../api/combatApi';
import { encounterApi } from '../api/encounterApi';
import { useLiveEncounter } from '../context/liveEncounter';
import type { CombatLogEntry } from '../types/combat';
import type {
  AddParticipantRequest, Encounter, EncounterCreateRequest, EncounterView, SetInitiativeRequest,
} from '../types/encounter';
import { queryKeys } from './keys';
import { invalidateOnConflict } from './queryClient';

/** The encounter a builder change applies to. */
type EncounterRef = Pick<Encounter, 'id' | 'campaignId'>;

/** A campaign's encounters, newest first. */
export function useCampaignEncounters(campaignId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.campaigns.encounters(campaignId ?? ''),
    queryFn: campaignId ? async () => (await encounterApi.getByCampaign(campaignId)).data : skipToken,
  });
}

/**
 * Finds the encounter a session code opens. It is a mutation because it runs when the form is sent and
 * its result is only needed to open the session page.
 */
export function useFindEncounterBySessionCode() {
  return useMutation({
    mutationFn: async (sessionCode: string) => (await encounterApi.getBySessionCode(sessionCode)).data,
  });
}

/** Creates an encounter; it joins its campaign's list at once, and the list is fetched again. */
export function useCreateEncounter() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (request: EncounterCreateRequest) => (await encounterApi.create(request)).data,
    onSuccess: async encounter => {
      await writeEncounter(queryClient, encounter);
      void queryClient.invalidateQueries({ queryKey: queryKeys.campaigns.encounters(encounter.campaignId) });
    },
    onError: (error, { campaignId }) =>
      invalidateOnConflict(queryClient, error, queryKeys.campaigns.encounters(campaignId)),
  });
}

/** Deletes an encounter; it leaves its campaign's list at once, and the list is fetched again. */
export function useDeleteEncounter() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (encounter: EncounterRef) => encounterApi.delete(encounter.id),
    onSuccess: (_response, encounter) => {
      const list = queryKeys.campaigns.encounters(encounter.campaignId);
      queryClient.setQueryData<Encounter[]>(list, encounters => encounters?.filter(e => e.id !== encounter.id));
      queryClient.removeQueries({ queryKey: queryKeys.encounters.views(encounter.id) });
      void queryClient.invalidateQueries({ queryKey: list });
    },
    onError: (error, encounter) =>
      invalidateOnConflict(queryClient, error, queryKeys.campaigns.encounters(encounter.campaignId)),
  });
}

export function useAddParticipant() {
  return useEncounterChange(({ encounter, request }: { encounter: EncounterRef; request: AddParticipantRequest }) =>
    encounterApi.addParticipant(encounter.id, request));
}

export function useRemoveParticipant() {
  return useEncounterChange(({ encounter, participantId }: { encounter: EncounterRef; participantId: string }) =>
    encounterApi.removeParticipant(encounter.id, participantId));
}

export function useRenameParticipant() {
  return useEncounterChange(
    ({ encounter, participantId, displayName }: { encounter: EncounterRef; participantId: string; displayName: string }) =>
      encounterApi.renameParticipant(encounter.id, participantId, displayName),
  );
}

/** Hides a monster from the Player View, or shows it again (D157). */
export function useSetParticipantVisibility() {
  return useEncounterChange(
    ({ encounter, participantId, visible }: { encounter: EncounterRef; participantId: string; visible: boolean }) =>
      encounterApi.setVisibility(encounter.id, participantId, visible),
  );
}

export function useSetInitiatives() {
  return useEncounterChange(
    ({ encounter, initiatives }: { encounter: EncounterRef; initiatives: SetInitiativeRequest[] }) =>
      encounterApi.setInitiatives(encounter.id, { initiatives }),
  );
}

export function useRollInitiatives() {
  return useEncounterChange(({ encounter }: { encounter: EncounterRef }) => encounterApi.rollInitiatives(encounter.id));
}

export function useStartEncounter() {
  return useEncounterChange(({ encounter }: { encounter: EncounterRef }) => encounterApi.start(encounter.id));
}

/** A change made in the encounter builder. The encounter the server returns replaces the cached copy. */
function useEncounterChange<TVariables extends { encounter: EncounterRef }>(
  change: (variables: TVariables) => Promise<AxiosResponse<Encounter>>,
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (variables: TVariables) => (await change(variables)).data,
    onSuccess: encounter => writeEncounter(queryClient, encounter),
    onError: (error, { encounter }) =>
      invalidateOnConflict(queryClient, error, queryKeys.campaigns.encounters(encounter.campaignId)),
  });
}

async function writeEncounter(queryClient: QueryClient, encounter: Encounter) {
  const list = queryKeys.campaigns.encounters(encounter.campaignId);
  // A refetch that was already under way would otherwise land afterwards and undo the change
  await queryClient.cancelQueries({ queryKey: list, exact: true });
  queryClient.setQueryData<Encounter[]>(list, encounters => {
    if (!encounters) return encounters;
    return encounters.some(e => e.id === encounter.id)
      ? encounters.map(e => (e.id === encounter.id ? encounter : e))
      : [encounter, ...encounters];
  });
}

// ── The live encounter of a session page (D171) ─────────────────────────

/**
 * Loads one of a live encounter's queries. A WebSocket message that arrives while the request is in flight is
 * at least as new as the response, so the copy it wrote to the cache is kept instead.
 */
async function loadUnlessSocketIsNewer<T>(
  client: QueryClient, queryKey: QueryKey, socketMessages: RefObject<number>, load: () => Promise<T>,
): Promise<T> {
  const messagesBefore = socketMessages.current;
  const loaded = await load();
  const cached = client.getQueryData<T>(queryKey);
  return socketMessages.current !== messagesBefore && cached !== undefined ? cached : loaded;
}

/**
 * A live encounter in one view (D157). It loads each time a session page opens and again after the WebSocket
 * reconnects; in between, socket messages and action responses keep it current, so it never goes stale.
 */
export function liveEncounterQuery(encounterId: string, view: EncounterView, socketMessages: RefObject<number>) {
  const queryKey = queryKeys.encounters.detail(encounterId, view);
  return queryOptions({
    queryKey,
    queryFn: ({ client }) => loadUnlessSocketIsNewer(client, queryKey, socketMessages,
      async () => (await encounterApi.getById(encounterId)).data),
    staleTime: Infinity,
    refetchOnMount: 'always',
  });
}

/** The combat log of a live encounter's view, which loads and stays current as liveEncounterQuery does. */
export function liveCombatLogQuery(encounterId: string, view: EncounterView, socketMessages: RefObject<number>) {
  const queryKey = queryKeys.encounters.log(encounterId, view);
  return queryOptions({
    queryKey,
    queryFn: ({ client }) => loadUnlessSocketIsNewer(client, queryKey, socketMessages,
      async () => (await combatApi.getCombatLog(encounterId)).data),
    staleTime: Infinity,
    refetchOnMount: 'always',
  });
}

/** The combat log of the page's live encounter, oldest entry first. The server sends it after every change. */
export function useCombatLog(): CombatLogEntry[] {
  return useLiveEncounter().log;
}

/** A monster's action templates, with the current uses of its resource pools; DM only (D157). */
export function useMonsterActionTemplates(participantId: string) {
  const { encounterId } = useLiveEncounter();
  return useQuery({
    queryKey: queryKeys.encounters.monsterActions(encounterId, participantId),
    queryFn: async () => (await combatApi.getMonsterActions(encounterId, participantId)).data,
    // The pools change with every monster action, so the templates load again each time they are shown
    staleTime: 0,
  });
}

/** An API call that changes an encounter, minus the encounter's ID, which the session page supplies. */
type EncounterRequest<A extends unknown[], T> = (encounterId: string, ...args: A) => Promise<AxiosResponse<T>>;

/**
 * The changes a session page makes to its live encounter. Each sends one request and writes the encounter
 * state in the response to the cache, unless a WebSocket message arrived while the request was in flight: the
 * server publishes every change before it answers, so that message is at least as new (D171). A failed
 * request rejects and leaves the cache as it was.
 */
export function useEncounterAction() {
  const queryClient = useQueryClient();
  const { encounterId, view, socketMessages } = useLiveEncounter();

  return useMemo(() => {
    const stateKey = queryKeys.encounters.detail(encounterId, view);

    async function run<T>(request: () => Promise<AxiosResponse<T>>, stateOf: (data: T) => Encounter): Promise<T> {
      const messagesBefore = socketMessages.current;
      const { data } = await request();
      if (socketMessages.current === messagesBefore) {
        queryClient.setQueryData(stateKey, stateOf(data));
      }
      return data;
    }

    // Answers with the encounter state itself
    const change = <A extends unknown[]>(request: EncounterRequest<A, Encounter>) =>
      (...args: A) => run(() => request(encounterId, ...args), encounter => encounter);
    // Answers with a result that carries the encounter state
    const resolve = <A extends unknown[], T extends { encounterState: Encounter }>(request: EncounterRequest<A, T>) =>
      (...args: A) => run(() => request(encounterId, ...args), result => result.encounterState);

    return {
      pause: change(encounterApi.pause),
      resume: change(encounterApi.resume),
      end: change(encounterApi.end),
      setVisibility: change(encounterApi.setVisibility),
      nextTurn: change(combatApi.advanceTurn),
      previousTurn: change(combatApi.previousTurn),
      rollAttack: change(combatApi.rollAttack),
      applyDamage: change(combatApi.applyDamage),
      applyHealing: change(combatApi.applyHealing),
      setHp: change(combatApi.setHp),
      addCondition: change(combatApi.addCondition),
      removeCondition: change(combatApi.removeCondition),
      rollDeathSave: change(combatApi.rollDeathSave),
      setConcentration: change(combatApi.setConcentration),
      spendSpellSlot: change(combatApi.useSpellSlot),
      restoreSpellSlot: change(combatApi.restoreSpellSlot),
      spendLegendaryResistance: change(combatApi.useLegendaryResistance),
      castSpell: resolve(combatApi.castSpell),
      repeatSpellEffect: resolve(combatApi.repeatSpellEffect),
      monsterSpell: resolve(combatApi.monsterSpell),
      monsterAction: resolve(combatApi.monsterAction),
    };
  }, [queryClient, encounterId, view, socketMessages]);
}
