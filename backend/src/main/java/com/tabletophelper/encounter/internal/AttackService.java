package com.tabletophelper.encounter.internal;

import com.tabletophelper.campaign.AccessPolicy;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterRepository;
import com.tabletophelper.encounter.EncounterService;
import com.tabletophelper.encounter.dto.AttackCost;
import com.tabletophelper.encounter.dto.AttackOption;
import com.tabletophelper.encounter.dto.AttackRollRequest;
import com.tabletophelper.encounter.dto.EncounterResponse;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.combat.ActionCost;
import com.tabletophelper.rules.combat.Charge;
import com.tabletophelper.rules.combat.ClassFeatures;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.RollMode;
import com.tabletophelper.rules.combat.Resolver;
import com.tabletophelper.rules.combat.WeaponRules;
import com.tabletophelper.encounter.state.TurnEconomy;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.NotFoundException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Weapon attacks (D164). A player character attacks with a weapon from their inventory, or an unarmed strike, and the
 * server works out the ability, proficiency, attack bonus and damage, with Great Weapon Master's or Sharpshooter's
 * trade when the character may take it. A magic weapon's bonus is added by hand as a Manual Modifier, and so is a
 * forced critical hit; an attack entered by hand, for a weapon the server doesn't know, is a Manual Modifier
 * altogether. The DM may always enter them, a player while the campaign allows it.
 *
 * <p>An attack on an unconscious or paralyzed target has advantage, and a hit from within 5 feet is a critical hit;
 * attacks never hit automatically, and the dead can't be attacked (D161).
 *
 * <p>An attack is charged to its attacker's turn economy (D174): by default an attack of the Attack action, which the
 * first attack opens with the attacker's action; an off-hand attack with a light melee weapon as a bonus action once
 * the Attack action is taken, without a positive ability modifier to its damage (PHB p.195); or an opportunity attack
 * as a reaction, which a player may make on another creature's turn.
 *
 * <p>Class features (M17): a raging barbarian adds its Rage Damage to melee weapon attacks using Strength, a flat bonus
 * that a critical hit doesn't double (PHB p.48, p.196). A barbarian with Reckless Attack may attack recklessly on the
 * first attack of its turn, giving its melee Strength attacks advantage for the rest of the turn and attacks against it
 * advantage until its next (PHB p.48). Every attack records that its attacker attacked, which keeps a rage going.
 */
@Service
@RequiredArgsConstructor
public class AttackService {

    private final CombatAccess access;
    private final AccessPolicy accessPolicy;
    private final CharacterWeapons characterWeapons;
    private final Resolver resolver;
    private final CreatureStats stats;
    private final EffectApplier effectApplier;
    private final ActionEconomyKeeper economy;
    private final ManualModifiers manualModifiers;
    private final EncounterRepository encounterRepository;
    private final EncounterService encounterService;

    // An attack ready to resolve: its Effect, how its entry reads, the Manual Modifiers it uses, and whether it starts
    // the attacker's Reckless Attack for the turn (PHB p.48)
    private record Prepared(Effect effect, String action, String preposition, String subject,
                            List<ManualModifiers.Use> modifiers, boolean startsReckless) {
    }

    /**
     * An attack by the actor, or by the DM as no one when actorParticipantId is null. A player attacks only with
     * their own character on its turn (D028), and enters Manual Modifiers only while the campaign allows players
     * them; the DM always may (D164). A player's opportunity attack may be made on any turn (D174). Everything is
     * checked, and the attack charged to the actor's turn economy, before anything rolls.
     */
    @Transactional
    public EncounterResponse rollAttack(UUID encounterId, AttackRollRequest request, UUID actorParticipantId,
                                        UUID userId) {
        AttackCost cost = request.getCost() != null ? request.getCost() : AttackCost.ACTION;
        Encounter encounter = access.beginForCost(encounterId, actorParticipantId, userId, cost.actionCost());
        EncounterParticipant actor = actorParticipantId != null
                ? CombatAccess.participant(encounter, actorParticipantId) : null;
        boolean offHand = cost == AttackCost.BONUS_ACTION;
        Prepared attack = request.getWeapon() != null ? weaponAttack(encounter, actor, request, offHand)
                : enteredAttack(actor, request);
        manualModifiers.require(encounter, userId, attack.modifiers());
        EncounterParticipant target = CombatAccess.target(encounter, request.getTargetId(), userId);
        CombatAccess.requireNotDead(List.of(target));
        String costNote = costNote(cost);
        if (actor != null) {
            economy.charge(encounter, actor, charge(encounter, actor, cost), userId,
                    new ActionEconomyKeeper.Command(CombatActionType.ATTACK, attack.subject() + costNote));
            effectApplier.markAttackedOrDamaged(actor);
            if (attack.startsReckless()) {
                effectApplier.attackRecklessly(encounter, actor);
            }
        }

        EffectApplier.Use use = new EffectApplier.Use(actor, actor != null ? actor.getDisplayName() : "DM",
                CombatActionType.ATTACK, attack.action() + costNote, attack.preposition(), "", null);
        manualModifiers.log(encounter, CombatActionType.ATTACK, actor, attack.subject(), userId, attack.modifiers());
        effectApplier.apply(encounter, use, attack.effect(),
                resolver.resolve(attack.effect(), List.of(stats.target(target, attack.effect()))));
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    /**
     * The weapons a participant can attack with now, with the numbers the server works out (D164): a player
     * character's inventory weapons and unarmed strike; none for a monster, whose attacks are its actions. The DM
     * may read anyone's, a player only their own character's. It changes nothing, so it needs no lock and any
     * status will do.
     */
    @Transactional(readOnly = true)
    public List<AttackOption> attackOptions(UUID encounterId, UUID participantId, UUID userId) {
        Encounter encounter = encounterRepository.findById(encounterId)
                .orElseThrow(() -> new NotFoundException("Encounter not found"));
        UUID campaignId = encounter.getCampaign().getId();
        accessPolicy.requireMember(campaignId, userId);
        EncounterParticipant participant = CombatAccess.target(encounter, participantId, userId);
        accessPolicy.requireControls(campaignId, participant.getControlledByUserId(), userId);
        PlayerCharacter character = participant.getCharacter();
        if (character == null) {
            return List.of();
        }
        return characterWeapons.of(character).stream().map(held -> option(character, held)).toList();
    }

    private AttackOption option(PlayerCharacter character, CharacterWeapons.Held held) {
        WeaponRules.Weapon weapon = held.weapon();
        WeaponRules.Numbers numbers = characterWeapons.numbers(character, held, false);
        AttackOption.PowerAttackOption powerAttack = null;
        if (held.powerAttack() != null) {
            WeaponRules.Numbers traded = characterWeapons.numbers(character, held, true);
            powerAttack = new AttackOption.PowerAttackOption(held.powerAttack().featName(), traded.attackBonus(),
                    traded.damage().toString());
        }
        boolean reckless = FeatureEffects.hasFeature(character, ClassFeatures.RECKLESS_ATTACK)
                && ClassFeatures.meleeStrengthAttack(weapon.ranged(), numbers.ability());
        return new AttackOption(weapon.name(), numbers.ability(), held.proficient(), numbers.attackBonus(),
                numbers.damage().toString(), weapon.damageType(), weapon.ranged(), powerAttack, reckless);
    }

    // What the attack charges: an attack of the Attack action, opening it when none is going; an off-hand attack;
    // or an opportunity attack
    private static Charge charge(Encounter encounter, EncounterParticipant actor, AttackCost cost) {
        boolean ownTurn = EncounterTurns.isCurrentTurn(encounter, actor.getId());
        return switch (cost) {
            case ACTION -> new Charge(ActionCost.ACTION, true, Charge.SpellKind.NONE, ownTurn,
                    TurnEconomies.attacksPerAction(actor));
            case BONUS_ACTION -> new Charge(ActionCost.BONUS_ACTION, true, Charge.SpellKind.NONE, ownTurn, 1);
            case REACTION -> Charge.reactionAttack(ownTurn);
        };
    }

    // How the log names an attack's cost when it isn't the attack of the Attack action
    private static String costNote(AttackCost cost) {
        return switch (cost) {
            case ACTION -> "";
            case BONUS_ACTION -> " (bonus action)";
            case REACTION -> " (reaction)";
        };
    }

    // A player character's attack with a weapon they hold: the numbers are the server's, and what is added to them
    // by hand is a Manual Modifier. An off-hand attack needs a light melee weapon (PHB p.195)
    private Prepared weaponAttack(Encounter encounter, EncounterParticipant actor, AttackRollRequest request,
                                  boolean offHand) {
        if (request.getAttackBonus() != null || request.getDamageDice() != null || request.getDamageType() != null) {
            throw new ValidationException("A weapon attack takes its numbers from the weapon: add a magic bonus as an "
                    + "extra attack bonus or extra damage");
        }
        if (actor == null || actor.getCharacter() == null) {
            throw new ValidationException("Only a player character attacks with a weapon from their inventory");
        }
        PlayerCharacter character = actor.getCharacter();
        CharacterWeapons.Held held = characterWeapons.find(character, request.getWeapon())
                .orElseThrow(() -> new ValidationException(actor.getDisplayName() + " has no "
                        + request.getWeapon().trim() + " to attack with"));
        WeaponRules.Weapon weapon = held.weapon();
        if (offHand && !WeaponRules.offHandWeapon(weapon)) {
            throw new ValidationException("An off-hand attack needs a light melee weapon, and "
                    + (weapon == WeaponRules.UNARMED_STRIKE ? "an unarmed strike" : "the " + weapon.name())
                    + " isn't one");
        }
        boolean powerAttack = Boolean.TRUE.equals(request.getPowerAttack());
        if (powerAttack && held.powerAttack() == null) {
            throw new ValidationException(powerAttackRefusal(actor, held));
        }

        WeaponRules.Numbers numbers = characterWeapons.numbers(character, held, powerAttack, offHand);
        boolean meleeStrength = ClassFeatures.meleeStrengthAttack(weapon.ranged(), numbers.ability());
        boolean ownTurn = EncounterTurns.isCurrentTurn(encounter, actor.getId());
        List<String> conditions = CreatureStats.conditionNames(actor);
        boolean alreadyReckless = ClassFeatures.reckless(conditions);
        boolean startsReckless = Boolean.TRUE.equals(request.getRecklessAttack()) && !alreadyReckless;
        if (Boolean.TRUE.equals(request.getRecklessAttack())) {
            requireReckless(actor, weapon, meleeStrength, ownTurn, alreadyReckless);
        }
        boolean reckless = ownTurn && meleeStrength && (alreadyReckless || startsReckless);
        int rageDamage = ClassFeatures.raging(conditions) && meleeStrength
                ? rageDamage(FeatureEffects.classLevel(character, "Barbarian")) : 0;
        Dice weaponDamage = rageDamage == 0 ? numbers.damage() : new Dice(numbers.damage().count(),
                numbers.damage().sides(), numbers.damage().modifier() + rageDamage);
        Dice extraDamage = extraDamage(request);
        String extraType = extraDamage == null ? null
                : request.getExtraDamageType() != null && !request.getExtraDamageType().isBlank()
                        ? request.getExtraDamageType().trim().toLowerCase() : weapon.damageType();
        List<Effect.DamagePart> damage = new ArrayList<>(List.of(new Effect.DamagePart(weaponDamage,
                weapon.damageType())));
        if (extraDamage != null) {
            damage.add(new Effect.DamagePart(extraDamage, extraType));
        }
        int attackBonus = numbers.attackBonus()
                + (request.getExtraAttackBonus() != null ? request.getExtraAttackBonus() : 0);
        // There is no map: a melee weapon attacks from within 5 feet, and a ranged one from beyond (D161)
        boolean withinFiveFeet = request.getWithin5Ft() != null ? request.getWithin5Ft() : !weapon.ranged();
        boolean forcedCritical = Boolean.TRUE.equals(request.getForceCrit());
        List<String> notes = new ArrayList<>();
        if (powerAttack) notes.add(held.powerAttack().featName());
        if (reckless) notes.add(ClassFeatures.RECKLESS_ATTACK);
        if (rageDamage > 0) notes.add("Rage +" + rageDamage);
        String label = weapon.name() + (notes.isEmpty() ? "" : " (" + String.join(", ", notes) + ")");

        // Every weapon attack uses STR or DEX, so armour worn without proficiency gives it disadvantage (PHB p.144):
        // one more source, combined with the attacker's choice and the target's conditions at once (PHB p.173), as is
        // Reckless Attack's advantage (PHB p.48)
        List<RollMode> sources = new ArrayList<>();
        if (stats.armourPenalty(actor)) sources.add(RollMode.DISADVANTAGE);
        if (reckless) sources.add(RollMode.ADVANTAGE);
        Effect effect = new Effect(label, new Effect.Attack(attackBonus, SpellEffects.rollMode(request.getAdvantage()),
                withinFiveFeet, forcedCritical, null, sources), damage, null, List.of(), false, null);
        return new Prepared(effect, "uses " + label, "on", actor.getDisplayName() + "'s " + weapon.name() + " attack",
                ManualModifiers.forWeaponAttack(request.getExtraAttackBonus(), extraDamage, extraType, forcedCritical),
                startsReckless);
    }

    // Reckless Attack is the barbarian's, with a melee weapon attack using Strength, chosen on the first attack of its
    // own turn (PHB p.48); once chosen, the turn's later attacks may say so again
    private static void requireReckless(EncounterParticipant actor, WeaponRules.Weapon weapon, boolean meleeStrength,
                                        boolean ownTurn, boolean alreadyReckless) {
        if (!FeatureEffects.hasFeature(actor.getCharacter(), ClassFeatures.RECKLESS_ATTACK)) {
            throw new ValidationException(actor.getDisplayName() + " doesn't have Reckless Attack");
        }
        if (!meleeStrength) {
            throw new ValidationException("Reckless Attack is for melee weapon attacks using Strength, and "
                    + (weapon == WeaponRules.UNARMED_STRIKE ? "this unarmed strike" : "this " + weapon.name()
                    + " attack") + " isn't one");
        }
        if (!ownTurn) {
            throw new ConflictException("Reckless Attack is chosen on an attack on " + actor.getDisplayName()
                    + "'s own turn");
        }
        TurnEconomy economy = actor.getTurnEconomy() != null ? actor.getTurnEconomy() : TurnEconomy.fresh();
        if (!alreadyReckless && economy.attackActionTaken()) {
            throw new ConflictException("Reckless Attack is chosen on the first attack of the turn, and "
                    + actor.getDisplayName() + " has already attacked");
        }
    }

    // The Rage Damage of the barbarian's level (PHB p.46); none for a creature raging without barbarian levels
    private static int rageDamage(int barbarianLevel) {
        return barbarianLevel >= 1 ? ClassFeatures.rageDamage(Math.min(20, barbarianLevel)) : 0;
    }

    // An attack whose numbers are all entered by hand: a Manual Modifier as a whole
    private Prepared enteredAttack(EncounterParticipant actor, AttackRollRequest request) {
        if (request.getAttackBonus() == null || request.getDamageDice() == null || request.getDamageDice().isBlank()) {
            throw new ValidationException("Choose a weapon, or enter the attack bonus and damage dice");
        }
        if (request.getPowerAttack() != null || request.getExtraAttackBonus() != null
                || request.getExtraDamageDice() != null || request.getExtraDamageType() != null) {
            throw new ValidationException("Great Weapon Master, Sharpshooter and extra bonuses go with a weapon attack; "
                    + "an attack entered by hand includes them in its numbers");
        }
        if (Boolean.TRUE.equals(request.getRecklessAttack())) {
            throw new ValidationException("Reckless Attack goes with a weapon attack, whose numbers the server works out");
        }
        Dice damage = Dice.parse(request.getDamageDice());
        String damageType = request.getDamageType() != null && !request.getDamageType().isBlank()
                ? request.getDamageType().trim().toLowerCase() : null;
        boolean forcedCritical = Boolean.TRUE.equals(request.getForceCrit());
        Effect effect = new Effect("Attack",
                new Effect.Attack(request.getAttackBonus(), SpellEffects.rollMode(request.getAdvantage()),
                        !Boolean.FALSE.equals(request.getWithin5Ft()), forcedCritical),
                List.of(new Effect.DamagePart(damage, damageType)), null, List.of(), false, null);
        String subject = actor != null ? actor.getDisplayName() + "'s attack" : "The DM's attack";
        return new Prepared(effect, "attacks", "", subject,
                ManualModifiers.forEnteredAttack(request.getAttackBonus(), damage, damageType, forcedCritical), false);
    }

    // The extra damage entered for a weapon attack, such as "1d6" or "+1"; a type alone is a 400
    private static Dice extraDamage(AttackRollRequest request) {
        String dice = request.getExtraDamageDice();
        if (dice == null || dice.isBlank()) {
            if (request.getExtraDamageType() != null && !request.getExtraDamageType().isBlank()) {
                throw new ValidationException("Enter the extra damage's dice along with its type");
            }
            return null;
        }
        String trimmed = dice.trim();
        return Dice.parse(trimmed.startsWith("+") ? trimmed.substring(1).trim() : trimmed);
    }

    // Why the character can't take the trade with this weapon: the weapon allows neither, or they lack the feat or
    // proficiency it needs
    private String powerAttackRefusal(EncounterParticipant actor, CharacterWeapons.Held held) {
        WeaponRules.Weapon weapon = held.weapon();
        return WeaponRules.powerAttackFor(weapon)
                .map(trade -> characterWeapons.feats(actor.getCharacter()).contains(trade)
                        ? trade.featName() + " needs a weapon " + actor.getDisplayName() + " is proficient with, and "
                                + actor.getDisplayName() + " isn't proficient with the " + weapon.name()
                        : actor.getDisplayName() + " doesn't have the " + trade.featName() + " feat")
                .orElse("Great Weapon Master needs a heavy melee weapon and Sharpshooter a ranged weapon, and "
                        + (weapon == WeaponRules.UNARMED_STRIKE ? "an unarmed strike" : "the " + weapon.name())
                        + " is neither");
    }
}
