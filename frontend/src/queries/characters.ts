import { skipToken, useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { characterApi } from '../api/characterApi';
import type { ApplyChoicesRequest, CharacterUpdateRequest, PlayerCharacter } from '../types/character';
import { queryKeys } from './keys';
import { invalidateOnConflict } from './queryClient';

/** The signed-in user's characters. */
export function useMyCharacters() {
  return useQuery({
    queryKey: queryKeys.characters.mine(),
    queryFn: async () => (await characterApi.getMine()).data,
  });
}

/** One character, which its owner and the DM of its campaign may read (D155). */
export function useCharacter(characterId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.characters.detail(characterId ?? ''),
    queryFn: characterId ? async () => (await characterApi.getById(characterId)).data : skipToken,
  });
}

/**
 * The classes a character could take its next level in, with whether it meets each one's multiclass
 * prerequisites. Every change to the character makes them stale.
 */
export function useEligibleClasses(characterId: string) {
  return useQuery({
    queryKey: queryKeys.characters.eligibleClasses(characterId),
    queryFn: async () => (await characterApi.getEligibleClasses(characterId)).data,
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

/**
 * Saves changes to one of the user's characters; the character the server returns replaces the cached
 * copies. Joining or leaving a campaign also changes the campaigns' lists, which are fetched again.
 */
export function useUpdateCharacter() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ characterId, updates }: { characterId: string; updates: CharacterUpdateRequest }) =>
      (await characterApi.update(characterId, updates)).data,
    onSuccess: async character => {
      const campaignBefore = queryClient.getQueryData<PlayerCharacter>(queryKeys.characters.detail(character.id))?.campaignId;
      await writeCharacter(queryClient, character);
      if (campaignBefore === character.campaignId) return;
      [campaignBefore, character.campaignId].forEach(campaignId => {
        if (campaignId) void queryClient.invalidateQueries({ queryKey: queryKeys.campaigns.characters(campaignId) });
      });
    },
    onError: (error, { characterId }) =>
      invalidateOnConflict(queryClient, error, queryKeys.characters.detail(characterId)),
  });
}

/**
 * Adds a level in `classId` to a character. The response says what the player still has to choose for it;
 * the levelled character replaces the cached copies.
 */
export function useLevelUp() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ characterId, classId }: { characterId: string; classId: string }) =>
      (await characterApi.levelUp(characterId, classId)).data,
    onSuccess: response => writeCharacter(queryClient, response.character),
    onError: (error, { characterId }) =>
      invalidateOnConflict(queryClient, error, queryKeys.characters.detail(characterId)),
  });
}

/** Removes a character's last level and every choice made at it; the result replaces the cached copies. */
export function useLevelDown() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (characterId: string) => (await characterApi.levelDown(characterId)).data,
    onSuccess: character => writeCharacter(queryClient, character),
    onError: (error, characterId) => invalidateOnConflict(queryClient, error, queryKeys.characters.detail(characterId)),
  });
}

/**
 * Applies the choices of a new level, such as an ability score improvement, a feat, a subclass or expertise;
 * the updated character replaces the cached copies.
 */
export function useApplyChoices() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ characterId, choices }: { characterId: string; choices: ApplyChoicesRequest }) =>
      (await characterApi.applyChoices(characterId, choices)).data,
    onSuccess: character => writeCharacter(queryClient, character),
    onError: (error, { characterId }) =>
      invalidateOnConflict(queryClient, error, queryKeys.characters.detail(characterId)),
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

/** Puts a character the server returned in the cache, without fetching it again. */
async function writeCharacter(queryClient: QueryClient, character: PlayerCharacter) {
  const detail = queryKeys.characters.detail(character.id);
  // A load that was already under way would otherwise land afterwards and put the old character back
  await queryClient.cancelQueries({ queryKey: detail, exact: true });
  queryClient.setQueryData(detail, character);
  queryClient.setQueryData<PlayerCharacter[]>(queryKeys.characters.mine(), characters =>
    characters?.map(c => (c.id === character.id ? character : c)));
  // Its ability scores and class levels decide which classes it may take next. They are fetched again the next
  // time the level-up dialog opens, not while the one that made the change closes.
  void queryClient.invalidateQueries({ queryKey: queryKeys.characters.eligibleClasses(character.id), refetchType: 'none' });
}
