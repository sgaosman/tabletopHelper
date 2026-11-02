import { skipToken, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { characterApi } from '../api/characterApi';
import type { PlayerCharacter } from '../types/character';
import { queryKeys } from './keys';
import { invalidateOnConflict } from './queryClient';

/** The signed-in user's characters. */
export function useMyCharacters() {
  return useQuery({
    queryKey: queryKeys.characters.mine(),
    queryFn: async () => (await characterApi.getMine()).data,
  });
}

/**
 * The characters in a campaign, which only its DM can list. With `refetchInterval` they are fetched again
 * on that interval while the tab is visible.
 */
export function useCampaignCharacters(
  campaignId: string | undefined,
  { refetchInterval }: { refetchInterval?: number } = {},
) {
  return useQuery({
    queryKey: queryKeys.campaigns.characters(campaignId ?? ''),
    queryFn: campaignId ? async () => (await characterApi.getByCampaign(campaignId)).data : skipToken,
    refetchInterval,
  });
}

/** Deletes one of the user's characters. It leaves the list at once; the lists it was in are fetched again. */
export function useDeleteCharacter() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (character: PlayerCharacter) => characterApi.delete(character.id),
    onSuccess: (_response, character) => {
      queryClient.setQueryData<PlayerCharacter[]>(queryKeys.characters.mine(), characters =>
        characters?.filter(c => c.id !== character.id));
      queryClient.removeQueries({ queryKey: queryKeys.characters.detail(character.id) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.characters.mine(), exact: true });
      if (character.campaignId) {
        void queryClient.invalidateQueries({ queryKey: queryKeys.campaigns.characters(character.campaignId) });
      }
    },
    onError: error => invalidateOnConflict(queryClient, error, queryKeys.characters.mine()),
  });
}
