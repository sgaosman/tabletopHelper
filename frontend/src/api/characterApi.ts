import api from './axiosConfig';
import type {
  PlayerCharacter, CharacterCreateRequest, LevelUpResponse, ApplyChoicesRequest, EligibleClassResponse, FeatOptionResponse,
  CharacterDetailsRequest, CharacterCampaignRequest, InventoryRequest, HitPointChangeRequest, PoolUseRequest,
  ShortRestRequest, ShortRestResponse, PreparedSpellsRequest, SpellbookRequest, DmAdjustmentRequest,
} from '../types/character';

/**
 * The character endpoints. A character changes only through its intent commands (D158), the DM's adjustments and
 * revival, and Write-back when an encounter ends (D166), each command answering with the character as the server then
 * works it out.
 */
export const characterApi = {
  create(data: CharacterCreateRequest) {
    return api.post<PlayerCharacter>('/characters', data);
  },

  getMine() {
    return api.get<PlayerCharacter[]>('/characters');
  },

  getByCampaign(campaignId: string) {
    return api.get<PlayerCharacter[]>(`/characters/campaign/${campaignId}`);
  },

  getById(id: string) {
    return api.get<PlayerCharacter>(`/characters/${id}`);
  },

  delete(id: string) {
    return api.delete(`/characters/${id}`);
  },

  updateDetails(id: string, data: CharacterDetailsRequest) {
    return api.patch<PlayerCharacter>(`/characters/${id}/details`, data);
  },

  setCampaign(id: string, data: CharacterCampaignRequest) {
    return api.put<PlayerCharacter>(`/characters/${id}/campaign`, data);
  },

  updateInventory(id: string, data: InventoryRequest) {
    return api.put<PlayerCharacter>(`/characters/${id}/inventory`, data);
  },

  changeHitPoints(id: string, data: HitPointChangeRequest) {
    return api.post<PlayerCharacter>(`/characters/${id}/hp`, data);
  },

  /** A slot of a spell level, "1" to "9", or a pact slot, "pact_N". */
  spendSlot(id: string, slot: string) {
    return api.post<PlayerCharacter>(`/characters/${id}/spell-slots/${slot}/spend`);
  },

  restoreSlot(id: string, slot: string) {
    return api.post<PlayerCharacter>(`/characters/${id}/spell-slots/${slot}/restore`);
  },

  spendPool(id: string, data: PoolUseRequest) {
    return api.post<PlayerCharacter>(`/characters/${id}/pools/spend`, data);
  },

  recoverPool(id: string, data: PoolUseRequest) {
    return api.post<PlayerCharacter>(`/characters/${id}/pools/recover`, data);
  },

  /** Answers with the rested character and what each Hit Die spent rolled. */
  shortRest(id: string, data: ShortRestRequest) {
    return api.post<ShortRestResponse>(`/characters/${id}/short-rest`, data);
  },

  longRest(id: string) {
    return api.post<PlayerCharacter>(`/characters/${id}/long-rest`);
  },

  setPreparedSpells(id: string, data: PreparedSpellsRequest) {
    return api.put<PlayerCharacter>(`/characters/${id}/spells/prepared`, data);
  },

  addToSpellbook(id: string, data: SpellbookRequest) {
    return api.post<PlayerCharacter>(`/characters/${id}/spellbook`, data);
  },

  removeFromSpellbook(id: string, data: SpellbookRequest) {
    return api.delete<PlayerCharacter>(`/characters/${id}/spellbook`, { data });
  },

  levelUp(id: string, classId?: string) {
    return api.post<LevelUpResponse>(`/characters/${id}/level-up`, classId ? { classId } : {});
  },

  levelDown(id: string) {
    return api.post<PlayerCharacter>(`/characters/${id}/level-down`);
  },

  applyChoices(id: string, data: ApplyChoicesRequest) {
    return api.post<PlayerCharacter>(`/characters/${id}/apply-choices`, data);
  },

  getEligibleClasses(id: string) {
    return api.get<EligibleClassResponse[]>(`/characters/${id}/eligible-classes`);
  },

  /** Every feat as the character could take it, for its owner or its campaign's DM (D159). */
  getFeatOptions(id: string) {
    return api.get<FeatOptionResponse[]>(`/characters/${id}/feat-options`);
  },

  /** A DM Adjustment, which only the DM of the character's campaign may add (D158). */
  addAdjustment(id: string, data: DmAdjustmentRequest) {
    return api.post<PlayerCharacter>(`/characters/${id}/adjustments`, data);
  },

  /** Removes a DM Adjustment, which stays on the sheet as the record. */
  removeAdjustment(id: string, adjustmentId: string) {
    return api.delete<PlayerCharacter>(`/characters/${id}/adjustments/${adjustmentId}`);
  },

  /** Revives a character who died in an encounter, at 1 hit point; only the DM of its campaign may (D166). */
  revive(id: string) {
    return api.post<PlayerCharacter>(`/characters/${id}/revive`);
  },
};
