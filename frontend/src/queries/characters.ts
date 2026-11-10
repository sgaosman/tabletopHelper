import { keepPreviousData, skipToken, useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { characterApi } from '../api/characterApi';
import type {
  ApplyChoicesRequest, CharacterCreateRequest, CharacterDetailsRequest, DmAdjustmentRequest, HitPointChangeRequest, InventoryRequest,
  FontOfMagicRequest, PlayerCharacter, PoolUseRequest, PreparedSpellsRequest, ShortRestRequest,
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

// How often an open sheet is fetched again while its character is in an encounter under way, and otherwise
const LIVE_SHEET_REFRESH_MS = 10_000;
const IDLE_SHEET_REFRESH_MS = 30_000;

/**
 * One character, which its owner and the DM of its campaign may read (D155). With `followChanges` it is fetched again
 * every 10 seconds while the character is in an encounter under way, and every 30 seconds otherwise, so an open sheet
 * shows what the DM writes back when the encounter ends or the character leaves it (D166), and a revival. Like every
 * refetch interval, it pauses while the tab is hidden.
 */
export function useCharacter(characterId: string | undefined, { followChanges = false }: { followChanges?: boolean } = {}) {
  return useQuery({
    queryKey: queryKeys.characters.detail(characterId ?? ''),
    queryFn: characterId ? async () => (await characterApi.getById(characterId)).data : skipToken,
    refetchInterval: followChanges
      ? query => (query.state.data?.inLiveEncounter ? LIVE_SHEET_REFRESH_MS : IDLE_SHEET_REFRESH_MS)
      : undefined,
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
 * Every feat as the character could take it: whether it meets each prerequisite, what it misses, and whether it has
 * the feat already (D159). Every change to the character makes them stale.
 */
export function useFeatOptions(characterId: string) {
  return useQuery({
    queryKey: queryKeys.characters.featOptions(characterId),
    queryFn: async () => (await characterApi.getFeatOptions(characterId)).data,
  });
}

/**
 * The armour class the creation wizard's request would give the character (M13.5's rules, worked out by the server
 * from the equipped armour and shield, the class, race and scores). With no request nothing is fetched; the last
 * preview stays on screen while the next loads.
 */
export function useArmourClassPreview(request: CharacterCreateRequest | null) {
  return useQuery({
    queryKey: queryKeys.characters.armourClassPreview(request),
    queryFn: request ? async () => (await characterApi.previewArmourClass(request)).data : skipToken,
    placeholderData: keepPreviousData,
    retry: false,
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

/** Sets the character's exhaustion level, 0 to 6 (PHB p.291, D183); the server halves and caps as the level says. */
export function useSetExhaustion() {
  return useCharacterCommand(({ characterId, level }: { characterId: string; level: number }) =>
    characterApi.setExhaustion(characterId, level));
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

/** A sorcerer's Flexible Casting on the sheet: a slot for sorcery points, or points for a slot (PHB p.101, M18). */
export function useFontOfMagic() {
  return useCharacterCommand(({ characterId, exchange }: { characterId: string; exchange: FontOfMagicRequest }) =>
    characterApi.fontOfMagic(characterId, exchange));
}

/** Gets back spent uses of one of a character's resource pools. */
export function useRecoverPool() {
  return useCharacterCommand(({ characterId, use }: { characterId: string; use: PoolUseRequest }) =>
    characterApi.recoverPool(characterId, use));
}

/**
 * A short rest, spending the Hit Dice named with the rolls entered or the server's. Pools that reset on a short
 * rest and pact slots come back. The rested character replaces the cached copies, and the result says what each die
 * rolled.
 */
export function useShortRest() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ characterId, rest }: { characterId: string; rest: ShortRestRequest }) =>
      (await characterApi.shortRest(characterId, rest)).data,
    onSuccess: response => writeCharacter(queryClient, response.character),
    onError: (error, { characterId }) =>
      invalidateOnConflict(queryClient, error, queryKeys.characters.detail(characterId)),
  });
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
 * Adds a level in `classId` to a character. What the player has to choose for it is among the levelled character's
 * Pending Choices (D159); the levelled character replaces the cached copies.
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

/**
 * Removes a character's last level, every choice made at it and those it still owed; the result replaces the cached
 * copies.
 */
export function useLevelDown() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (characterId: string) => (await characterApi.levelDown(characterId)).data,
    onSuccess: character => writeCharacter(queryClient, character),
    onError: (error, characterId) => invalidateOnConflict(queryClient, error, queryKeys.characters.detail(characterId)),
  });
}

/**
 * Makes one of a character's Pending Choices (D159), such as an ability score improvement, a feat, a subclass,
 * expertise, a fighting style, cantrips or spells; the updated character replaces the cached copies.
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

/**
 * A DM's command on a character in their campaign (D158, D166): the character the server answers with replaces the
 * cached copies, the campaign's character list that the DM's page shows among them. A 409 means the cached character
 * is out of date, such as an adjustment already removed in another tab, so the character and the list are fetched
 * again.
 */
function useDmCommand<Variables extends { characterId: string; campaignId: string }>(
  send: (variables: Variables) => Promise<{ data: PlayerCharacter }>,
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (variables: Variables) => (await send(variables)).data,
    onSuccess: async (character, { campaignId }) => {
      await writeCharacter(queryClient, character);
      queryClient.setQueryData<PlayerCharacter[]>(queryKeys.campaigns.characters(campaignId), characters =>
        characters?.map(c => (c.id === character.id ? character : c)));
    },
    onError: (error, { characterId, campaignId }) => invalidateOnConflict(queryClient, error,
      queryKeys.characters.detail(characterId), queryKeys.campaigns.characters(campaignId)),
  });
}

/** Adds a DM Adjustment to a character in the DM's campaign: a stat changed, a damage defence, or a feat granted. */
export function useAddAdjustment() {
  return useDmCommand((
    { characterId, adjustment }: { characterId: string; campaignId: string; adjustment: DmAdjustmentRequest },
  ) => characterApi.addAdjustment(characterId, adjustment));
}

/** Removes a DM Adjustment, which stays on the sheet as the record; a granted feat's effects are reversed. */
export function useRemoveAdjustment() {
  return useDmCommand((
    { characterId, adjustmentId }: { characterId: string; campaignId: string; adjustmentId: string },
  ) => characterApi.removeAdjustment(characterId, adjustmentId));
}

/** Revives a character in the DM's campaign who died in an encounter, at 1 hit point (D166). */
export function useReviveCharacter() {
  return useDmCommand(({ characterId, fullHitPoints }: { characterId: string; campaignId: string;
    fullHitPoints?: boolean }) => characterApi.revive(characterId, fullHitPoints));
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
  // Its scores, feats and levels decide which feats it may take, fetched again when a feat picker next opens
  void queryClient.invalidateQueries({ queryKey: queryKeys.characters.featOptions(character.id), refetchType: 'none' });
}
