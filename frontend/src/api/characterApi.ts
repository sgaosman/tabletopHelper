import api from './axiosConfig';
import type {
  PlayerCharacter, CharacterCreateRequest, LevelUpResponse, ApplyChoicesRequest, EligibleClassResponse,
  CharacterDetailsRequest, CharacterCampaignRequest, InventoryRequest, HitPointChangeRequest, PoolUseRequest,
  ShortRestRequest, PreparedSpellsRequest, KnownSpellsRequest, SpellbookRequest,
} from '../types/character';

/**
 * The character endpoints. A character changes only through its intent commands (D158), each answering with the
 * character as the server then works it out.
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

  shortRest(id: string, data: ShortRestRequest) {
    return api.post<PlayerCharacter>(`/characters/${id}/short-rest`, data);
  },

  longRest(id: string) {
    return api.post<PlayerCharacter>(`/characters/${id}/long-rest`);
  },

  setPreparedSpells(id: string, data: PreparedSpellsRequest) {
    return api.put<PlayerCharacter>(`/characters/${id}/spells/prepared`, data);
  },

  setKnownSpells(id: string, data: KnownSpellsRequest) {
    return api.put<PlayerCharacter>(`/characters/${id}/spells/known`, data);
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
};
