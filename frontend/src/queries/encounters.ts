import { skipToken, useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import type { AxiosResponse } from 'axios';
import { encounterApi } from '../api/encounterApi';
import type { AddParticipantRequest, Encounter, EncounterCreateRequest, SetInitiativeRequest } from '../types/encounter';
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
