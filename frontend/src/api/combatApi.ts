import api from './axiosConfig';
import type {
  ActiveSpellRequest, CommandSummonsRequest, CompanionSummonRequest, DismissSummonsRequest, Encounter,
  EndSpellEffectRequest, MoveSpellEffectRequest, PlaceSummonRequest, SummonOptions,
} from '../types/encounter';
import type {
  AttackOption,
  AttackRollPreview,
  AttackRollRequest,
  CombatLogEntry,
  CastSpellRequest,
  CastSpellResponse,
  SpellConsent,
  RepeatSpellEffectRequest,
  MonsterActionRequest,
  MonsterActionResponse,
  MonsterActionTemplate,
  MultiattackOption,
  BardicInspirationRequest,
  ChangeFormRequest,
  DeftStrikeRequest,
  DivineSmiteRequest,
  FontOfMagicUseRequest,
  FormOptions,
  ObjectInteractionRequest,
  ProtectionRequest,
  SpellOption,
  StandardActionRequest,
  StunningStrikeRequest,
  TurnEconomyRequest,
  UndoRequest,
  UsePoolRequest,
  WildShapeHealRequest,
  WildShapeOptions,
} from '../types/combat';

export const combatApi = {
  // A weapon attack the server works out, or an attack entered by hand. Extra bonuses, a forced critical and an
  // attack entered by hand are Manual Modifiers, which a player may send only while the campaign allows them
  // (D164). within5Ft decides whether a hit on an unconscious or paralyzed target is a critical hit; left out, it
  // is true, except for a ranged weapon
  rollAttack(encounterId: string, request: AttackRollRequest, actorId?: string) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/attack`, request, {
      params: actorId ? { actorId } : undefined,
    });
  },

  // Divine Smite on the paladin's latest melee weapon hit this turn (PHB p.85, M17): costs nothing from the turn,
  // spends the slot, and is refused (409) when there is no such hit or it was smitten already
  divineSmite(encounterId: string, request: DivineSmiteRequest) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/divine-smite`, request);
  },

  // Stunning Strike on the monk's latest melee weapon hit this turn, spending 1 ki (PHB p.79, M17)
  stunningStrike(encounterId: string, request: StunningStrikeRequest) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/stunning-strike`, request);
  },

  // Deft Strike on a Kensei monk's latest hit with a kensei weapon this turn, on its own turn, spending 1 ki for the
  // martial arts die (XGE p.34); refused (409) once used this turn
  deftStrike(encounterId: string, request: DeftStrikeRequest) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/deft-strike`, request);
  },

  // A held Bardic Inspiration die kept for the next failed save, kept back, or rolled now (PHB p.53, M17)
  bardicInspiration(encounterId: string, request: BardicInspirationRequest) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/bardic-inspiration`, request);
  },

  // The Protection fighting style's reaction, logged for the DM to apply (PHB p.72, M17)
  protection(encounterId: string, request: ProtectionRequest) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/protection`, request);
  },

  // The weapons the participant can attack with, and the numbers for each (D164)
  getAttackOptions(encounterId: string, participantId: string) {
    return api.get<AttackOption[]>(`/encounters/${encounterId}/combat/attack-options`, { params: { participantId } });
  },

  // What the attacker's and the target's conditions do to an attack roll between them; no attacker is the DM's own
  attackRollPreview(encounterId: string, attackerId: string | null, targetId: string, withinFiveFeet: boolean) {
    return api.get<AttackRollPreview>(`/encounters/${encounterId}/combat/attack-roll-preview`, {
      params: { ...(attackerId ? { attackerId } : {}), targetId, withinFiveFeet },
    });
  },

  applyDamage(encounterId: string, targetId: string, amount: number, damageType?: string, actorId?: string) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/damage`, { targetId, amount, damageType }, {
      params: actorId ? { actorId } : undefined,
    });
  },

  applyHealing(encounterId: string, targetId: string, amount: number, actorId?: string) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/heal`, { targetId, amount }, {
      params: actorId ? { actorId } : undefined,
    });
  },

  // The DM's Set HP / revive: a correction, or the only way back from Dead (D161)
  setHp(encounterId: string, targetId: string, hpCurrent: number, hpTemp?: number) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/hp`, { targetId, hpCurrent, hpTemp });
  },

  // sourceParticipantId: on frightened, the creature it is frightened of (D201)
  addCondition(encounterId: string, targetId: string, condition: string, duration?: number, sourceParticipantId?: string) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/condition/add`,
      { targetId, condition, duration: duration ?? undefined, sourceParticipantId: sourceParticipantId ?? undefined });
  },

  /** The DM marks the source of a creature's fear out of its sight, or back in sight (PHB p.290, D201). */
  markFearSight(encounterId: string, participantId: string, conditionId: string, outOfSight: boolean) {
    return api.put<Encounter>(`/encounters/${encounterId}/combat/condition/fear-sight`,
      { participantId, conditionId, outOfSight });
  },

  /** The DM sets a creature's exhaustion level, 0 to 6, while the encounter is prepared or under way (D183). */
  setExhaustion(encounterId: string, participantId: string, level: number) {
    return api.put<Encounter>(`/encounters/${encounterId}/combat/exhaustion`, { participantId, level });
  },

  /**
   * Whoever controls a creature with a trait such as Limited Magic Immunity sets which spells it lets through, while the
   * encounter is prepared or under way (D198).
   */
  setSpellConsent(encounterId: string, participantId: string, consent: SpellConsent) {
    return api.put<Encounter>(`/encounters/${encounterId}/combat/spell-consent`, { participantId, consent });
  },

  removeCondition(encounterId: string, targetId: string, condition: string) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/condition/remove`, { targetId, condition });
  },

  rollDeathSave(encounterId: string, participantId: string) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/death-save`, { participantId });
  },

  // A Repeat Save the campaign leaves to the player, which the server rolls (D162)
  rollRepeatSave(encounterId: string, participantId: string, conditionId: string) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/repeat-save`, { participantId, conditionId });
  },

  // A save the creature makes with its action to end a condition, as Otto's Irresistible Dance's (D188)
  rollActionSave(encounterId: string, participantId: string, conditionId: string) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/action-save`, { participantId, conditionId });
  },

  setConcentration(encounterId: string, participantId: string, spellName: string | null) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/concentration`, { participantId, spellName });
  },

  useSpellSlot(encounterId: string, participantId: string, slotLevel: number) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/spell-slot/use`, { participantId, slotLevel });
  },

  restoreSpellSlot(encounterId: string, participantId: string, slotLevel: number) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/spell-slot/restore`, { participantId, slotLevel });
  },

  // Player characters and monsters cast alike, the caster named by actorId (D165)
  castSpell(encounterId: string, request: CastSpellRequest, actorId?: string) {
    return api.post<CastSpellResponse>(`/encounters/${encounterId}/combat/cast-spell`, request, {
      params: actorId ? { actorId } : undefined,
    });
  },

  advanceTurn(encounterId: string) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/turn/next`);
  },

  previousTurn(encounterId: string) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/turn/previous`);
  },

  // What the participant can cast now, and what can pay for each spell (D164, D165)
  getSpellOptions(encounterId: string, participantId: string) {
    return api.get<SpellOption[]>(`/encounters/${encounterId}/combat/spell-options`, { params: { participantId } });
  },

  repeatSpellEffect(encounterId: string, request: RepeatSpellEffectRequest, actorId?: string) {
    return api.post<CastSpellResponse>(`/encounters/${encounterId}/combat/repeat-spell-effect`, request, {
      params: actorId ? { actorId } : undefined,
    });
  },

  // Ends a Spell Effect by hand, and the concentration it belongs to (M15): the DM's for anyone, a player's for their own
  endSpellEffect(encounterId: string, request: EndSpellEffectRequest) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/spell-effect/end`, request);
  },

  // The DM's record that a caster's lasting spell is already going, while prepared or under way (D184)
  startActiveSpell(encounterId: string, request: ActiveSpellRequest) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/active-spell`, request);
  },

  // Moves Hunter's Mark or Hex to a new creature with a bonus action (PHB p.251, D184)
  moveSpellEffect(encounterId: string, request: MoveSpellEffectRequest) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/spell-effect/move`, request);
  },

  // The DM's alone (M14): undoes the newest `steps` commands (one when left out), refused with 409 when
  // expectedCommandId is no longer the newest, so the DM never undoes what they didn't review
  undo(encounterId: string, expectedCommandId: string, steps?: number) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/undo`, { expectedCommandId, steps } satisfies UndoRequest);
  },

  getCombatLog(encounterId: string) {
    return api.get<CombatLogEntry[]>(`/encounters/${encounterId}/combat/log`);
  },

  // ── M12 Monster Actions ─────────────────────────────────────────

  monsterAction(encounterId: string, request: MonsterActionRequest, actorId?: string) {
    return api.post<MonsterActionResponse>(`/encounters/${encounterId}/combat/monster-action`, request, {
      params: actorId ? { actorId } : undefined,
    });
  },

  // A participant's resource pool, costing its spendActionType from the turn: the DM may use anyone's, a player
  // their own participant's, and an action or bonus action only on its turn. Action Surge adds an action, once per
  // turn (D174)
  usePool(encounterId: string, request: UsePoolRequest) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/use-pool`, request);
  },

  // The beasts a druid can become with Wild Shape now (PHB p.66): the DM's for anyone, a player's for their own
  // character. Wild Shape itself is a use-pool request with the beast's ID
  getWildShapeOptions(encounterId: string, participantId: string) {
    return api.get<WildShapeOptions>(`/encounters/${encounterId}/combat/wild-shape-options`, { params: { participantId } });
  },

  // A Circle of the Moon druid's Combat Wild Shape healing, a bonus action while transformed: a spell slot for 1d8 HP
  // per level (PHB p.69)
  wildShapeHeal(encounterId: string, request: WildShapeHealRequest) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/wild-shape-heal`, request);
  },

  // The creatures a form spell can turn the target into (D192), within the spell's limits for it; for Shapechange the
  // target is the caster and may be left out
  getFormOptions(encounterId: string, participantId: string, spell: string, targetId?: string) {
    return api.get<FormOptions>(`/encounters/${encounterId}/combat/form-options`, {
      params: { participantId, spell, ...(targetId ? { targetId } : {}) },
    });
  },

  // A new form during Shapechange (the caster's own) or Animal Shapes (a creature the caster transformed), the
  // caster's action (D192)
  changeForm(encounterId: string, request: ChangeFormRequest) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/change-form`, request);
  },

  // A sorcerer's Flexible Casting, a bonus action on their turn: a slot for sorcery points, or points for a slot
  // of 1st to 5th level (PHB p.101, M18)
  fontOfMagic(encounterId: string, request: FontOfMagicUseRequest) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/font-of-magic`, request);
  },

  // Dash, Disengage, Dodge, Help, Hide, Ready, Search or Use an Object on the participant's turn: an action, or a
  // bonus action with Cunning Action or Nimble Escape; a player's request over budget is a 409 (D174)
  standardAction(encounterId: string, request: StandardActionRequest) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/standard-action`, request);
  },

  // An object interaction on the participant's turn: the first each turn is free, and a player's second is a 409
  // (D174)
  objectInteraction(encounterId: string, request: ObjectInteractionRequest) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/object-interaction`, request);
  },

  // The DM's alone: replaces a participant's turn economy, such as for Haste or Slow (D174)
  setTurnEconomy(encounterId: string, request: TurnEconomyRequest) {
    return api.put<Encounter>(`/encounters/${encounterId}/combat/turn-economy`, request);
  },

  useLegendaryResistance(encounterId: string, combatLogId: string) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/legendary-resistance`, { combatLogId });
  },

  getMonsterActions(encounterId: string, participantId: string) {
    return api.get<MonsterActionTemplate>(`/encounters/${encounterId}/participants/${participantId}/actions`);
  },

  // A monster's multiattacks and the attacks each makes, for a target per attack; DM only (D157, D165)
  getMultiattackOptions(encounterId: string, participantId: string) {
    return api.get<MultiattackOption[]>(`/encounters/${encounterId}/combat/multiattack-options`, {
      params: { participantId },
    });
  },

  // Every monster participant fighting in its lair, which gives the Lair a turn on initiative 20 (D163)
  setLairStatus(encounterId: string, monsterIds: string[]) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/lair-status`, { monsterIds });
  },
  // ── M26 Summoning (D182) ─────────────────────────────────────────

  // What a summoning spell can bring when the participant casts it at this slot level (its own level when left out):
  // the DM's for anyone, a player's for a participant they control. Any other spell is a 400
  getSummonOptions(encounterId: string, participantId: string, spellName: string, slotLevel?: number) {
    return api.get<SummonOptions>(`/encounters/${encounterId}/combat/summon-options`, {
      params: { participantId, spellName, ...(slotLevel != null ? { slotLevel } : {}) },
    });
  },

  // The DM's creatures for a summoning waiting for them, or in place of a placed one's
  placeSummon(encounterId: string, request: PlaceSummonRequest) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/summons`, request);
  },

  // A summoning the DM places with no slot spent, while the encounter is prepared or active: a familiar, steed,
  // Animate Dead's undead or a tiny servant cast before the fight
  placeCompanion(encounterId: string, request: CompanionSummonRequest) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/summons/companion`, request);
  },

  // The DM dismisses summoned creatures, while their spell goes on
  dismissSummons(encounterId: string, request: DismissSummonsRequest) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/summons/dismiss`, request);
  },

  // The caster's bonus action that lets creatures act which act only when commanded (PHB p.212, XGE p.168); a player
  // only on the caster's turn
  commandSummons(encounterId: string, request: CommandSummonsRequest) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/summons/command`, request);
  },
};
