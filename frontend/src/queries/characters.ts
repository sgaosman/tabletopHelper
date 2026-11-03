import { skipToken, useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { characterApi } from '../api/characterApi';
import type {
  ApplyChoicesRequest, CharacterDetailsRequest, HitPointChangeRequest, InventoryRequest, KnownSpellsRequest,
  PlayerCharacter, PoolUseRequest, PreparedSpellsRequest, ShortRestRequest,
} from '../types/character';
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
 * A command on one of the user's characters (D158): the character the server answers with replaces the cached
 * copies. A 409 means the cached character is out of date, or the command has to wait, such as a rest during an
 * encounter, so the character is fetched again.
 */
function useCharacterCommand<Variables extends { characterId: string }>(
  send: (variables: Variables) => Promise<{ data: PlayerCharacter }>,
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (variables: Variables) => (await send(variables)).data,
    onSuccess: character => writeCharacter(queryClient, character),
    onError: (error, { characterId }) =>
      invalidateOnConflict(queryClient, error, queryKeys.characters.detail(characterId)),
  });
}

/** Sets the details a character's owner chooses, such as its name or notes; those left out keep their values. */
export function useUpdateDetails() {
  return useCharacterCommand(({ characterId, details }: { characterId: string; details: CharacterDetailsRequest }) =>
    characterApi.updateDetails(characterId, details));
}

/**
 * Puts a character in a campaign its owner has joined, or takes it out of its campaign with null. The campaigns'
 * character lists change too, so they are fetched again.
 */
export function useSetCampaign() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ characterId, campaignId }: { characterId: string; campaignId: string | null }) =>
      (await characterApi.setCampaign(characterId, { campaignId })).data,
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

/** Replaces a character's inventory and coins. */
export function useUpdateInventory() {
  return useCharacterCommand(({ characterId, inventory }: { characterId: string; inventory: InventoryRequest }) =>
    characterApi.updateInventory(characterId, inventory));
}

/**
 * Damage, healing or temporary hit points on the sheet. The server applies the rules: damage comes off temporary
 * hit points first, healing stops at the maximum, and temporary hit points keep the larger amount.
 */
export function useChangeHitPoints() {
  return useCharacterCommand(({ characterId, change }: { characterId: string; change: HitPointChangeRequest }) =>
    characterApi.changeHitPoints(characterId, change));
}

/** Spends one slot of a spell level, "1" to "9", or a pact slot, "pact_N". */
export function useSpendSlot() {
  return useCharacterCommand(({ characterId, slot }: { characterId: string; slot: string }) =>
    characterApi.spendSlot(characterId, slot));
}

/** Gets back one spent slot of a spell level, or a pact slot. */
export function useRestoreSlot() {
  return useCharacterCommand(({ characterId, slot }: { characterId: string; slot: string }) =>
    characterApi.restoreSlot(characterId, slot));
}

/** Spends uses of one of a character's resource pools. */
export function useSpendPool() {
  return useCharacterCommand(({ characterId, use }: { characterId: string; use: PoolUseRequest }) =>
    characterApi.spendPool(characterId, use));
}

/** Gets back spent uses of one of a character's resource pools. */
export function useRecoverPool() {
  return useCharacterCommand(({ characterId, use }: { characterId: string; use: PoolUseRequest }) =>
    characterApi.recoverPool(characterId, use));
}

/**
 * A short rest, spending the Hit Dice named with the rolls entered or the server's. Pools that reset on a short
 * rest and pact slots come back.
 */
export function useShortRest() {
  return useCharacterCommand(({ characterId, rest }: { characterId: string; rest: ShortRestRequest }) =>
    characterApi.shortRest(characterId, rest));
}

/** A long rest: hit points, half the Hit Dice, every slot and every pool come back. */
export function useLongRest() {
  return useCharacterCommand(({ characterId }: { characterId: string }) => characterApi.longRest(characterId));
}

/** Sets which of a prepared caster's spells are prepared. */
export function useSetPreparedSpells() {
  return useCharacterCommand(({ characterId, prepared }: { characterId: string; prepared: PreparedSpellsRequest }) =>
    characterApi.setPreparedSpells(characterId, prepared));
}

/** Sets the cantrips, or the spells, one class knows (interim, until Pending Choices). */
export function useSetKnownSpells() {
  return useCharacterCommand(({ characterId, known }: { characterId: string; known: KnownSpellsRequest }) =>
    characterApi.setKnownSpells(characterId, known));
}

/** Copies spells into a wizard's spellbook. */
export function useAddToSpellbook() {
  return useCharacterCommand(({ characterId, spells }: { characterId: string; spells: string[] }) =>
    characterApi.addToSpellbook(characterId, { spells }));
}

/** Takes spells out of a wizard's spellbook. */
export function useRemoveFromSpellbook() {
  return useCharacterCommand(({ characterId, spells }: { characterId: string; spells: string[] }) =>
    characterApi.removeFromSpellbook(characterId, { spells }));
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
