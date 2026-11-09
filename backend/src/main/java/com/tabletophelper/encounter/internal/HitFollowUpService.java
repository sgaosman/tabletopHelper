package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterRepository;
import com.tabletophelper.encounter.EncounterService;
import com.tabletophelper.encounter.dto.DivineSmiteRequest;
import com.tabletophelper.encounter.dto.EncounterResponse;
import com.tabletophelper.encounter.dto.ProtectionRequest;
import com.tabletophelper.encounter.dto.StunningStrikeRequest;
import com.tabletophelper.encounter.state.TurnEconomy;
import com.tabletophelper.encounter.state.WeaponHit;
import com.tabletophelper.resourcepool.ResourcePoolService;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.ProficiencyBonus;
import com.tabletophelper.rules.combat.ActionCost;
import com.tabletophelper.rules.combat.AttackFeatures;
import com.tabletophelper.rules.combat.Charge;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.Resolver;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a class feature does after a melee weapon hit, chosen once the hit is known (M17): the attack resolves in one
 * call (D160), so these follow it as commands of their own on the hit the attacker's turn economy records
 * ({@link WeaponHit}), on the same turn, each once. They cost nothing from the turn, so a player may take them after an
 * opportunity attack on another creature's turn. Each is an undoable command like any other (D176).
 * <ul>
 *   <li>Divine Smite (Paladin 2, PHB p.85): a spell slot for radiant damage to the creature hit, 2d8 and 1d8 for each
 *       slot level above 1st to 5d8, 1d8 more against an undead or a fiend, every die doubled when the hit was a critical
 *       hit (PHB p.196). Any melee weapon attack's hit will do, an unarmed strike's included, since an unarmed strike is
 *       a melee weapon attack (PHB p.195). The damage goes through the target's defences as any other does. It is
 *       part of the hit's damage, so a concentrating target makes one concentration check against the two together,
 *       judged by the roll the hit's damage made it roll (PHB p.203).</li>
 *   <li>Stunning Strike (Monk 5, PHB p.79): 1 ki point; the creature hit makes a Constitution save against the ki save
 *       DC (8 + proficiency bonus + WIS modifier, PHB p.78) or is stunned until the end of the monk's next turn. A
 *       creature with Legendary Resistance is offered it, as for any failed save (D163).</li>
 * </ul>
 * The Protection fighting style's reaction (PHB p.72) is here too: it imposes disadvantage on an attack the DM is
 * rolling, so it is recorded, charged to the reaction and left to the DM.
 */
@Service
@RequiredArgsConstructor
public class HitFollowUpService {

    private static final String PALADIN = "Paladin";
    private static final String MONK = "Monk";

    private final CombatAccess access;
    private final Resolver resolver;
    private final CreatureStats stats;
    private final EffectApplier effectApplier;
    private final ActionEconomyKeeper economy;
    private final CombatLogWriter combatLog;
    private final EncounterRepository encounterRepository;
    private final EncounterService encounterService;

    /**
     * Divine Smite on the paladin's latest melee weapon hit.
     *
     * @throws ValidationException for a creature without Paladin level 2
     * @throws ConflictException   when there is no hit to follow on this turn, it was smitten already, it isn't the
     *                             hit named, its target is gone or dead, or the slot is spent
     */
    @Transactional
    public EncounterResponse divineSmite(UUID encounterId, DivineSmiteRequest request, UUID userId) {
        Encounter encounter = access.beginForCost(encounterId, request.participantId(), userId, ActionCost.FREE);
        EncounterParticipant paladin = CombatAccess.participant(encounter, request.participantId());
        if (FeatureEffects.classLevel(paladin.getCharacter(), PALADIN) < AttackFeatures.DIVINE_SMITE_LEVEL) {
            throw new ValidationException(paladin.getDisplayName() + " needs Paladin level "
                    + AttackFeatures.DIVINE_SMITE_LEVEL + " for Divine Smite");
        }
        WeaponHit hit = hitToFollow(encounter, paladin, request.attackLogId(), "Divine Smite");
        if (hit.smitten()) {
            throw new ConflictException(paladin.getDisplayName() + " has already smitten with that hit");
        }
        EncounterParticipant target = targetOf(encounter, hit);
        int level = request.slotLevel();
        Map<String, SpellSlot> slots = paladin.getSpellSlotsCurrent() != null
                ? new LinkedHashMap<>(paladin.getSpellSlotsCurrent()) : new LinkedHashMap<>();
        String key = slotKey(slots, level, Boolean.TRUE.equals(request.pactSlot()));
        SpellSlot slot = slots.get(key);
        if (slot == null || slot.remaining() < 1) {
            throw new ConflictException(paladin.getDisplayName() + " has no " + slotName(key, level) + " left");
        }
        slots.put(key, slot.spend());
        paladin.setSpellSlotsCurrent(slots);
        paladin.setTurnEconomy(economyOf(paladin).withLastMeleeHit(hit.smite()));

        String type = target.getMonster() != null ? target.getMonster().getType() : null;
        boolean undeadOrFiend = AttackFeatures.undeadOrFiend(type);
        Dice dice = AttackFeatures.divineSmite(level, undeadOrFiend);
        if (hit.critical()) {
            dice = dice.doubled();
        }
        SpellSlot after = slots.get(key);
        String note = slotName(key, level) + ", " + after.remaining() + "/" + after.total() + " left"
                + (hit.critical() ? "; critical hit" : "") + (undeadOrFiend ? "; +1d8 against " + type : "");
        Effect effect = new Effect("Divine Smite", new Effect.Automatic(),
                List.of(new Effect.DamagePart(dice, AttackFeatures.RADIANT)), null, List.of(), false, null);
        EffectApplier.Use use = new EffectApplier.Use(paladin, paladin.getDisplayName(), CombatActionType.ATTACK,
                "uses Divine Smite (" + dice + " radiant; " + note + ") after the " + hit.weapon() + " hit", "on",
                "Divine Smite", null);
        // The smite's damage is part of the hit's, so a concentrating target makes one check against both (PHB p.203)
        effectApplier.applyToHit(encounter, use, effect, resolver.resolve(effect, List.of(stats.target(target, effect))),
                hit.concentrationCheck());
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    /**
     * Stunning Strike on the monk's latest melee weapon hit.
     *
     * @throws ValidationException for a creature without Monk level 5
     * @throws ConflictException   when there is no hit to follow on this turn, Stunning Strike followed it already, it
     *                             isn't the hit named, its target is gone or dead, or the monk has no ki left
     */
    @Transactional
    public EncounterResponse stunningStrike(UUID encounterId, StunningStrikeRequest request, UUID userId) {
        Encounter encounter = access.beginForCost(encounterId, request.participantId(), userId, ActionCost.FREE);
        EncounterParticipant monk = CombatAccess.participant(encounter, request.participantId());
        PlayerCharacter character = monk.getCharacter();
        if (FeatureEffects.classLevel(character, MONK) < AttackFeatures.STUNNING_STRIKE_LEVEL) {
            throw new ValidationException(monk.getDisplayName() + " needs Monk level "
                    + AttackFeatures.STUNNING_STRIKE_LEVEL + " for Stunning Strike");
        }
        WeaponHit hit = hitToFollow(encounter, monk, request.attackLogId(), "Stunning Strike");
        if (hit.stunningStrikeUsed()) {
            throw new ConflictException(monk.getDisplayName() + " has already used Stunning Strike with that hit");
        }
        EncounterParticipant target = targetOf(encounter, hit);
        List<ResourcePoolEntry> pools = ResourcePoolService.spend(monk.getResourcePoolsCurrent(), AttackService.KI_POOL,
                1, monk.getDisplayName());
        monk.setResourcePoolsCurrent(pools);
        monk.setTurnEconomy(economyOf(monk).withLastMeleeHit(hit.stunningStrike()));

        int dc = AttackFeatures.kiSaveDc(proficiencyBonus(character), stats.abilityScore(monk, Ability.WIS));
        // Stunned until the end of the monk's next turn: on its own turn that is a round on, otherwise the next end of
        // its turn (PHB p.79)
        boolean ownTurn = EncounterTurns.isCurrentTurn(encounter, monk.getId());
        Effect effect = new Effect("Stunning Strike", new Effect.Save(Ability.CON, dc, false), List.of(), null,
                List.of(AttackFeatures.STUNNED), false, null,
                Map.of(AttackFeatures.STUNNED, new Effect.ConditionEnd(ownTurn ? 1 : null, null, null, true)));
        ResourcePoolEntry ki = pools.stream().filter(pool -> AttackService.KI_POOL.equals(pool.poolId())).findFirst()
                .orElse(null);
        String kiLeft = ki != null && !ki.unlimited() ? ", " + ki.currentUses() + "/" + ki.maxUses() + " left" : "";
        EffectApplier.Use use = new EffectApplier.Use(monk, monk.getDisplayName(), CombatActionType.ATTACK,
                "uses Stunning Strike (1 ki" + kiLeft + ") after the " + hit.weapon() + " hit", "on",
                "Stunning Strike", null);
        effectApplier.apply(encounter, use, effect, resolver.resolve(effect, List.of(stats.target(target, effect))));
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    /**
     * The Protection fighting style's reaction (PHB p.72): the defender, wielding a shield, imposes disadvantage on an
     * attack against a creature within 5 feet of it. The DM rolls that attack, so the reaction is charged and logged
     * for the DM to apply.
     *
     * @throws ValidationException for a defender without the style, or protecting itself
     * @throws ConflictException   for a defender without a shield, or a player's reaction already spent
     */
    @Transactional
    public EncounterResponse protection(UUID encounterId, ProtectionRequest request, UUID userId) {
        Encounter encounter = access.beginForCost(encounterId, request.participantId(), userId, ActionCost.REACTION);
        EncounterParticipant defender = CombatAccess.participant(encounter, request.participantId());
        EncounterParticipant protectedOne = CombatAccess.target(encounter, request.protectedId(), userId);
        if (!FeatureEffects.hasFeature(defender.getCharacter(),
                AttackFeatures.FightingStyle.PROTECTION.featureName())) {
            throw new ValidationException(defender.getDisplayName() + " doesn't have the Protection fighting style");
        }
        if (protectedOne == defender) {
            throw new ValidationException("Protection guards another creature, not the defender itself");
        }
        if (stats.armour(defender).shield() == null) {
            throw new ConflictException("Protection needs a shield, and " + defender.getDisplayName()
                    + " isn't wielding one");
        }
        boolean ownTurn = EncounterTurns.isCurrentTurn(encounter, defender.getId());
        economy.charge(encounter, defender, Charge.of(ActionCost.REACTION, ownTurn), userId,
                new ActionEconomyKeeper.Command(CombatActionType.STANDARD_ACTION,
                        defender.getDisplayName() + "'s Protection (reaction)"));
        combatLog.write(encounter, CombatActionType.STANDARD_ACTION, defender.getDisplayName()
                + " uses Protection (reaction): the attack against " + protectedOne.getDisplayName()
                + " has disadvantage; the DM rolls it so.", defender, protectedOne);
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    // The attacker's latest melee weapon hit, when it is on this turn and is the one named
    private static WeaponHit hitToFollow(Encounter encounter, EncounterParticipant attacker, UUID attackLogId,
                                         String feature) {
        WeaponHit hit = economyOf(attacker).lastMeleeHit();
        if (hit == null || !hit.turn().equals(EncounterTurns.stamp(encounter))) {
            throw new ConflictException(feature + " follows a melee weapon hit on the same turn, and "
                    + attacker.getDisplayName() + "'s last attack this turn wasn't one");
        }
        if (attackLogId != null && !attackLogId.equals(hit.attackLogId())) {
            throw new ConflictException("That attack is no longer " + attacker.getDisplayName()
                    + "'s latest hit; " + feature + " follows the latest");
        }
        return hit;
    }

    // The creature the hit struck, still in the encounter and alive
    private static EncounterParticipant targetOf(Encounter encounter, WeaponHit hit) {
        EncounterParticipant target = encounter.getParticipants().stream()
                .filter(p -> p.getId().equals(hit.targetId()))
                .findFirst()
                .orElseThrow(() -> new ConflictException("The creature that hit struck has left the encounter"));
        CombatAccess.requireNotDead(List.of(target));
        return target;
    }

    // A spell slot of the level, or a pact slot of that level when asked for or when it is the only one there is
    private static String slotKey(Map<String, SpellSlot> slots, int level, boolean pact) {
        String spell = String.valueOf(level);
        String pactKey = "pact_" + level;
        if (pact) return pactKey;
        return slots.containsKey(spell) || !slots.containsKey(pactKey) ? spell : pactKey;
    }

    private static String slotName(String key, int level) {
        return key.startsWith("pact_") ? "level " + level + " pact slot" : "level " + level + " spell slot";
    }

    private static int proficiencyBonus(PlayerCharacter character) {
        if (character != null && character.getProficiencyBonus() != null) {
            return character.getProficiencyBonus();
        }
        return ProficiencyBonus.forLevel(character != null && character.getLevel() != null ? character.getLevel() : 1);
    }

    private static TurnEconomy economyOf(EncounterParticipant participant) {
        return participant.getTurnEconomy() != null ? participant.getTurnEconomy() : TurnEconomy.fresh();
    }
}
