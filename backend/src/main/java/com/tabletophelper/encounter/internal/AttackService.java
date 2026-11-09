package com.tabletophelper.encounter.internal;

import com.tabletophelper.campaign.AccessPolicy;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterRepository;
import com.tabletophelper.encounter.EncounterService;
import com.tabletophelper.encounter.dto.AttackCost;
import com.tabletophelper.encounter.dto.AttackOption;
import com.tabletophelper.encounter.dto.AttackRollRequest;
import com.tabletophelper.encounter.dto.EncounterResponse;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.TurnEconomy;
import com.tabletophelper.encounter.state.WeaponHit;
import com.tabletophelper.resourcepool.ResourcePoolService;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.combat.ActionCost;
import com.tabletophelper.rules.combat.AttackFeatures;
import com.tabletophelper.rules.combat.AttackRules;
import com.tabletophelper.rules.combat.ChannelDivinity;
import com.tabletophelper.rules.combat.Charge;
import com.tabletophelper.rules.combat.ClassFeatures;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.Outcome;
import com.tabletophelper.rules.combat.Resolver;
import com.tabletophelper.rules.combat.RollMode;
import com.tabletophelper.rules.combat.WeaponRules;
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
 * the Attack action is taken, without a positive ability modifier to its damage (PHB p.195); an opportunity attack as a
 * reaction, which a player may make on another creature's turn; or a monk's bonus-action unarmed strike from Martial
 * Arts or Flurry of Blows (PHB p.78).
 *
 * <p>Class features (M17): a raging barbarian adds its Rage Damage to melee weapon attacks using Strength, a flat bonus
 * that a critical hit doesn't double (PHB p.48, p.196). A barbarian with Reckless Attack may attack recklessly on the
 * first attack of its turn, giving its melee Strength attacks advantage for the rest of the turn and attacks against it
 * advantage until its next (PHB p.48). Every attack records that its attacker attacked, which keeps a rage going.
 * Fighting Styles, Martial Arts and a Champion's critical range change the numbers ({@link AttackFeatures}); Sneak
 * Attack adds its dice to a hit once per turn (PHB p.96); and a melee weapon hit is recorded on the attacker's turn
 * economy, for Divine Smite and Stunning Strike to follow on the same turn ({@link HitFollowUpService}).
 *
 * <p>Channel Divinity and Bardic Inspiration (M17, workstream C): Sacred Weapon adds the paladin's Charisma modifier,
 * at least +1, to its weapon attack rolls (PHB p.86); Vow of Enmity gives the paladin advantage against the creature
 * vowed against (PHB p.88); War God's Blessing adds +10 to the blessed creature's next attack roll (PHB p.63). Two are
 * decided after the d20 is seen, before the hit is known (PHB p.53, p.63): a held Bardic Inspiration die and a War
 * cleric's Guided Strike, which the attack asks for and the server adds only to a roll that would miss, Guided Strike
 * only when its +10 makes it hit, spending the Channel Divinity use then.
 */
@Service
@RequiredArgsConstructor
public class AttackService {

    /** The pool whose points Flurry of Blows spends (PHB p.78). */
    static final String KI_POOL = "class:monk-ki";

    private static final String ROGUE = "Rogue";
    private static final String MONK = "Monk";
    private static final String PALADIN = "Paladin";
    private static final String CLERIC_CHANNEL_DIVINITY = "class:cleric-channel-divinity";

    private final CombatAccess access;
    private final AccessPolicy accessPolicy;
    private final CharacterWeapons characterWeapons;
    private final Resolver resolver;
    private final CreatureStats stats;
    private final EffectApplier effectApplier;
    private final ActionEconomyKeeper economy;
    private final ManualModifiers manualModifiers;
    private final CombatLogWriter combatLog;
    private final EncounterRepository encounterRepository;
    private final EncounterService encounterService;

    /**
     * An attack ready to resolve: its Effect, how its entry reads, the Manual Modifiers it uses, whether it starts the
     * attacker's Reckless Attack for the turn (PHB p.48), and what the class features record once it is rolled.
     *
     * @param weapon      the weapon, or null for an attack entered by hand
     * @param melee       a melee attack, which Divine Smite and Stunning Strike can follow
     * @param sneakAttack it carries Sneak Attack's dice, spent for the turn only on a hit
     * @param martialArts Martial Arts applies to it, so an Attack action with it lets a monk strike as a bonus action
     * @param blessed     it carries War God's Blessing's +10, which it spends (PHB p.63)
     */
    private record Prepared(Effect effect, String action, String preposition, String subject,
                            List<ManualModifiers.Use> modifiers, boolean startsReckless, WeaponRules.Weapon weapon,
                            boolean melee, boolean sneakAttack, boolean martialArts, boolean blessed) {
    }

    /** What an attack charges its attacker's turn, why it is over budget whatever the turn says, and ki it spends. */
    private record Cost(Charge charge, String overReason, List<ResourcePoolEntry> poolsAfter) {
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
        EncounterParticipant target = CombatAccess.target(encounter, request.getTargetId(), userId);
        Prepared attack = request.getWeapon() != null ? weaponAttack(encounter, actor, target, request, cost)
                : enteredAttack(actor, request, cost);
        attack = withRescue(actor, request, attack);
        manualModifiers.require(encounter, userId, attack.modifiers());
        CombatAccess.requireNotDead(List.of(target));
        String costNote = costNote(cost);
        if (actor != null) {
            Cost charged = cost(encounter, actor, cost, attack);
            economy.charge(encounter, actor, charged.charge(), charged.overReason(), userId,
                    new ActionEconomyKeeper.Command(CombatActionType.ATTACK, attack.subject() + costNote));
            if (charged.poolsAfter() != null) {
                actor.setResourcePoolsCurrent(charged.poolsAfter());
            }
            afterCharge(actor, cost, attack);
            effectApplier.markAttackedOrDamaged(actor);
            if (attack.startsReckless()) {
                effectApplier.attackRecklessly(encounter, actor);
            }
            // War God's Blessing adds to the next attack roll only (PHB p.63)
            if (attack.blessed()) {
                effectApplier.removeFeatureEntries(actor, ChannelDivinity.WAR_GODS_BLESSING, null);
            }
        }

        EffectApplier.Use use = new EffectApplier.Use(actor, actor != null ? actor.getDisplayName() : "DM",
                CombatActionType.ATTACK, attack.action() + costNote, attack.preposition(), "", null);
        manualModifiers.log(encounter, CombatActionType.ATTACK, actor, attack.subject(), userId, attack.modifiers());
        List<EffectApplier.Applied> applied = effectApplier.apply(encounter, use, attack.effect(),
                resolver.resolve(attack.effect(), List.of(stats.target(target, attack.effect()))));
        if (actor != null) {
            recordHit(encounter, actor, target, attack, applied.getFirst());
            if (applied.getFirst().outcome().guidedStrike()) {
                spendGuidedStrike(encounter, actor);
            }
        }
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    // ── Decided after the roll ───────────────────────────────────

    /**
     * The attack with what its attacker adds once the roll would miss: a held Bardic Inspiration die (PHB p.53) and a
     * War cleric's Guided Strike (PHB p.63), each only when asked for.
     *
     * @throws ValidationException Guided Strike for an attacker without it
     * @throws ConflictException   a die asked for that the attacker doesn't hold, or Guided Strike with no Channel
     *                             Divinity left
     */
    private Prepared withRescue(EncounterParticipant actor, AttackRollRequest request, Prepared attack) {
        boolean inspiration = Boolean.TRUE.equals(request.getUseInspiration());
        boolean guided = Boolean.TRUE.equals(request.getGuidedStrike());
        if (!inspiration && !guided) {
            return attack;
        }
        if (actor == null) {
            throw new ValidationException("Bardic Inspiration and Guided Strike are a creature's own: attack as it");
        }
        int sides = 0;
        if (inspiration) {
            ConditionEntry held = EffectApplier.inspiration(actor).orElseThrow(() -> new ConflictException(
                    actor.getDisplayName() + " holds no Bardic Inspiration die"));
            sides = held.heldDie().sides();
        }
        if (guided) {
            if (!ChannelDivinityEffects.available(actor).contains(ChannelDivinity.Option.GUIDED_STRIKE)) {
                throw new ValidationException(actor.getDisplayName() + " doesn't have Guided Strike (War Domain)");
            }
            ResourcePoolEntry pool = channelDivinityPool(actor);
            if (pool == null || (!pool.unlimited() && pool.currentUses() < 1)) {
                throw new ConflictException(actor.getDisplayName() + " has no Channel Divinity left for Guided Strike");
            }
        }
        Effect effect = attack.effect();
        if (!(effect.delivery() instanceof Effect.Attack delivery)) {
            return attack;
        }
        Effect rescued = new Effect(effect.name(), delivery.withRescue(new Effect.Rescue(sides, guided)),
                effect.damage(), effect.healing(), effect.conditions(), effect.concentration(), effect.durationRounds(),
                effect.conditionEnds());
        return new Prepared(rescued, attack.action(), attack.preposition(), attack.subject(), attack.modifiers(),
                attack.startsReckless(), attack.weapon(), attack.melee(), attack.sneakAttack(), attack.martialArts(),
                attack.blessed());
    }

    // Guided Strike turned the miss into a hit, so its Channel Divinity use is spent (PHB p.63)
    private void spendGuidedStrike(Encounter encounter, EncounterParticipant actor) {
        ResourcePoolEntry pool = channelDivinityPool(actor);
        List<ResourcePoolEntry> pools = ResourcePoolService.spend(actor.getResourcePoolsCurrent(), pool.poolId(), 1,
                actor.getDisplayName());
        actor.setResourcePoolsCurrent(pools);
        ResourcePoolEntry after = pools.stream().filter(p -> pool.poolId().equals(p.poolId())).findFirst()
                .orElse(pool);
        combatLog.write(encounter, CombatActionType.POOL_USE, actor.getDisplayName() + " uses Channel Divinity for "
                + "Guided Strike: +10 turns the miss into a hit; " + (after.unlimited() ? "unlimited"
                : after.currentUses() + "/" + after.maxUses() + " left"), actor, null);
    }

    private static ResourcePoolEntry channelDivinityPool(EncounterParticipant participant) {
        List<ResourcePoolEntry> pools = participant.getResourcePoolsCurrent();
        return pools == null ? null : pools.stream()
                .filter(pool -> pool != null && CLERIC_CHANNEL_DIVINITY.equals(pool.poolId()))
                .findFirst()
                .orElse(null);
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
        AttackFeatures.Attacker attacker = characterWeapons.attacker(character, armoured(participant));
        RollBonuses rollBonuses = rollBonuses(participant, CreatureStats.conditionNames(participant));
        return characterWeapons.of(character).stream()
                .map(held -> option(character, attacker, held, rollBonuses))
                .toList();
    }

    /**
     * The bonuses to a weapon attack roll the attacker has in force now: Sacred Weapon's Charisma modifier, at least
     * +1 (PHB p.86), and War God's Blessing's +10, which the next attack spends (PHB p.63).
     */
    record RollBonuses(int sacredWeapon, boolean blessed) {

        int total() {
            return sacredWeapon + (blessed ? ChannelDivinity.ATTACK_BONUS : 0);
        }

        List<String> notes() {
            List<String> notes = new ArrayList<>();
            if (sacredWeapon > 0) notes.add("Sacred Weapon +" + sacredWeapon);
            if (blessed) notes.add("War God's Blessing +" + ChannelDivinity.ATTACK_BONUS);
            return notes;
        }
    }

    private RollBonuses rollBonuses(EncounterParticipant attacker, List<String> conditions) {
        int sacredWeapon = ClassFeatures.has(conditions, ChannelDivinity.SACRED_WEAPON)
                ? ChannelDivinity.sacredWeaponBonus(stats.abilityScore(attacker, Ability.CHA)) : 0;
        return new RollBonuses(sacredWeapon, ClassFeatures.has(conditions, ChannelDivinity.WAR_GODS_BLESSING));
    }

    // A weapon's option with the attack roll bonuses in force, which the attack itself adds (Sacred Weapon, War God's
    // Blessing), so the numbers shown are those the attack rolls
    private AttackOption option(PlayerCharacter character, AttackFeatures.Attacker attacker,
                                CharacterWeapons.Held held, RollBonuses rollBonuses) {
        WeaponRules.Weapon weapon = held.weapon();
        AttackFeatures.Grip grip = AttackFeatures.defaultGrip(weapon, false);
        AttackFeatures.Numbers numbers = characterWeapons.numbers(held, attacker, false, false, grip);
        AttackOption.PowerAttackOption powerAttack = null;
        if (held.powerAttack() != null) {
            AttackFeatures.Numbers traded = characterWeapons.numbers(held, attacker, true, false, grip);
            powerAttack = new AttackOption.PowerAttackOption(held.powerAttack().featName(),
                    traded.attackBonus() + rollBonuses.total(), traded.damage().toString());
        }
        List<String> features = new ArrayList<>(rollBonuses.notes());
        features.addAll(numbers.notes());
        String versatile = weapon.versatile() && !weapon.twoHanded()
                ? characterWeapons.numbers(held, attacker, false, false, AttackFeatures.Grip.TWO_HANDED).damage()
                        .toString() : null;
        boolean reckless = FeatureEffects.hasFeature(character, ClassFeatures.RECKLESS_ATTACK)
                && ClassFeatures.meleeStrengthAttack(weapon.ranged(), numbers.ability());
        int rogueLevel = Math.min(20, FeatureEffects.classLevel(character, ROGUE));
        String sneakAttack = rogueLevel >= 1 && AttackFeatures.sneakAttackWeapon(weapon)
                ? AttackFeatures.sneakAttack(rogueLevel).toString() : null;
        return new AttackOption(weapon.name(), numbers.ability(), held.proficient(),
                numbers.attackBonus() + rollBonuses.total(), numbers.damage().toString(), weapon.damageType(),
                weapon.ranged(), powerAttack, reckless, weapon.twoHanded(), versatile, features, numbers.critRange(),
                sneakAttack, numbers.martialArts());
    }

    // ── What an attack costs ─────────────────────────────────────

    // What the attack charges: an attack of the Attack action, opening it when none is going; an off-hand attack; an
    // opportunity attack; or a monk's bonus-action unarmed strike, Flurry of Blows' first spending a ki point
    private Cost cost(Encounter encounter, EncounterParticipant actor, AttackCost cost, Prepared attack) {
        boolean ownTurn = EncounterTurns.isCurrentTurn(encounter, actor.getId());
        TurnEconomy turn = economyOf(actor);
        return switch (cost) {
            case ACTION -> new Cost(new Charge(ActionCost.ACTION, true, Charge.SpellKind.NONE, ownTurn,
                    TurnEconomies.attacksPerAction(actor)), null, null);
            case BONUS_ACTION -> new Cost(new Charge(ActionCost.BONUS_ACTION, true, Charge.SpellKind.NONE, ownTurn, 1),
                    null, null);
            case REACTION -> new Cost(Charge.reactionAttack(ownTurn), null, null);
            // Martial Arts: after the Attack action with an unarmed strike or a monk weapon, without armour or a
            // shield (PHB p.78)
            case MARTIAL_ARTS -> new Cost(Charge.of(ActionCost.BONUS_ACTION, ownTurn),
                    !attack.martialArts() ? "Martial Arts needs no armour and no shield"
                            : !turn.martialArtsAttack() ? "Martial Arts' bonus unarmed strike follows the Attack action "
                            + "taken with an unarmed strike or a monk weapon this turn" : null,
                    null);
            // Flurry of Blows: the second strike costs nothing more; the first needs the Attack action this turn and
            // spends 1 ki point and the bonus action (PHB p.78)
            case FLURRY_OF_BLOWS -> turn.flurryStrikesLeft() > 0
                    ? new Cost(Charge.of(ActionCost.FREE, ownTurn), null, null)
                    : new Cost(Charge.of(ActionCost.BONUS_ACTION, ownTurn),
                            turn.attackActionTaken() ? null : "Flurry of Blows follows the Attack action this turn",
                            ResourcePoolService.spend(actor.getResourcePoolsCurrent(), KI_POOL, 1,
                                    actor.getDisplayName()));
        };
    }

    // What the class features record once the attack is charged: an Attack action with Martial Arts, which its bonus
    // strike follows, and the Flurry of Blows strikes left
    private static void afterCharge(EncounterParticipant actor, AttackCost cost, Prepared attack) {
        TurnEconomy turn = economyOf(actor);
        if (cost == AttackCost.ACTION && attack.martialArts() && !turn.martialArtsAttack()) {
            actor.setTurnEconomy(turn.withMartialArtsAttack(true));
        } else if (cost == AttackCost.FLURRY_OF_BLOWS) {
            actor.setTurnEconomy(turn.withFlurryStrikesLeft(turn.flurryStrikesLeft() > 0
                    ? turn.flurryStrikesLeft() - 1 : AttackFeatures.FLURRY_OF_BLOWS_STRIKES - 1));
        }
    }

    // How the log names an attack's cost when it isn't the attack of the Attack action
    private static String costNote(AttackCost cost) {
        return switch (cost) {
            case ACTION -> "";
            case BONUS_ACTION -> " (bonus action)";
            case REACTION -> " (reaction)";
            case MARTIAL_ARTS -> " (bonus action)";
            case FLURRY_OF_BLOWS -> " (Flurry of Blows)";
        };
    }

    // ── What a hit leaves ────────────────────────────────────────

    // A hit spends the turn's Sneak Attack when it carried one (PHB p.96), and a melee weapon hit is what Divine Smite and
    // Stunning Strike follow on the same turn (PHB p.85, p.79); a miss leaves nothing to follow
    private static void recordHit(Encounter encounter, EncounterParticipant actor, EncounterParticipant target,
                                  Prepared attack, EffectApplier.Applied applied) {
        Outcome outcome = applied.outcome().outcome();
        boolean hit = outcome == Outcome.HIT || outcome == Outcome.CRITICAL;
        String turn = EncounterTurns.stamp(encounter);
        TurnEconomy economy = economyOf(actor);
        if (hit && attack.sneakAttack()) {
            economy = economy.withSneakAttackTurn(turn);
        }
        WeaponHit followed = null;
        if (hit && attack.melee() && attack.weapon() != null) {
            PlayerCharacter character = actor.getCharacter();
            // An unarmed strike is a melee weapon attack, so Divine Smite may follow it too (PHB p.195, p.85)
            followed = WeaponHit.of(turn, applied.logEntryId(), target.getId(), attack.weapon().name(),
                    outcome == Outcome.CRITICAL,
                    FeatureEffects.classLevel(character, PALADIN) >= AttackFeatures.DIVINE_SMITE_LEVEL,
                    FeatureEffects.classLevel(character, MONK) >= AttackFeatures.STUNNING_STRIKE_LEVEL);
        }
        actor.setTurnEconomy(economy.withLastMeleeHit(followed));
    }

    // ── Weapon attacks ───────────────────────────────────────────

    // A player character's attack with a weapon they hold: the numbers are the server's, and what is added to them
    // by hand is a Manual Modifier. An off-hand attack needs a light melee weapon (PHB p.195), and a monk's bonus-action
    // strikes are unarmed strikes (PHB p.78)
    private Prepared weaponAttack(Encounter encounter, EncounterParticipant actor, EncounterParticipant target,
                                  AttackRollRequest request, AttackCost cost) {
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
        boolean offHand = cost == AttackCost.BONUS_ACTION;
        if (offHand && !WeaponRules.offHandWeapon(weapon)) {
            throw new ValidationException("An off-hand attack needs a light melee weapon, and "
                    + (weapon == WeaponRules.UNARMED_STRIKE ? "an unarmed strike" : "the " + weapon.name())
                    + " isn't one");
        }
        requireMonkStrike(actor, weapon, cost);
        boolean powerAttack = Boolean.TRUE.equals(request.getPowerAttack());
        if (powerAttack && held.powerAttack() == null) {
            throw new ValidationException(powerAttackRefusal(actor, held));
        }
        AttackFeatures.Grip grip = request.getGrip() != null ? request.getGrip()
                : AttackFeatures.defaultGrip(weapon, offHand);
        AttackFeatures.gripRefusal(weapon, grip, offHand).ifPresent(reason -> {
            throw new ValidationException(reason);
        });

        AttackFeatures.Numbers numbers = characterWeapons.numbers(held,
                characterWeapons.attacker(character, armoured(actor)), powerAttack, offHand, grip);
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
                weapon.damageType(), numbers.rerollUpTo())));
        if (extraDamage != null) {
            damage.add(new Effect.DamagePart(extraDamage, extraType));
        }
        // Sacred Weapon (PHB p.86) and War God's Blessing (PHB p.63) add to the attack roll
        RollBonuses rollBonuses = rollBonuses(actor, conditions);
        int sacredWeapon = rollBonuses.sacredWeapon();
        boolean blessed = rollBonuses.blessed();
        int attackBonus = numbers.attackBonus() + rollBonuses.total()
                + (request.getExtraAttackBonus() != null ? request.getExtraAttackBonus() : 0);
        // There is no map: a melee weapon attacks from within 5 feet, and a ranged one from beyond (D161)
        boolean withinFiveFeet = request.getWithin5Ft() != null ? request.getWithin5Ft() : !weapon.ranged();
        // A melee weapon thrown from beyond 5 feet makes a ranged attack, unless it is a reach weapon (PHB p.195)
        boolean melee = !weapon.ranged() && (withinFiveFeet || weapon.reach());
        boolean forcedCritical = Boolean.TRUE.equals(request.getForceCrit());
        List<String> notes = new ArrayList<>();
        if (powerAttack) notes.add(held.powerAttack().featName());
        if (reckless) notes.add(ClassFeatures.RECKLESS_ATTACK);
        if (rageDamage > 0) notes.add("Rage +" + rageDamage);
        if (sacredWeapon > 0) notes.add("Sacred Weapon +" + sacredWeapon);
        if (blessed) notes.add("War God's Blessing +" + ChannelDivinity.ATTACK_BONUS);
        boolean vowed = vowedAgainst(actor, target);
        if (vowed) notes.add("Vow of Enmity");
        notes.addAll(numbers.notes());

        // Every weapon attack uses STR or DEX, so armour worn without proficiency gives it disadvantage (PHB p.144):
        // one more source, combined with the attacker's choice and the target's conditions at once (PHB p.173), as is
        // Reckless Attack's advantage (PHB p.48)
        List<RollMode> sources = new ArrayList<>();
        if (stats.armourPenalty(actor)) sources.add(RollMode.DISADVANTAGE);
        if (reckless) sources.add(RollMode.ADVANTAGE);
        // Vow of Enmity's advantage against the creature vowed against (PHB p.88)
        if (vowed) sources.add(RollMode.ADVANTAGE);
        Effect.Attack delivery = new Effect.Attack(attackBonus, SpellEffects.rollMode(request.getAdvantage()),
                withinFiveFeet, forcedCritical, null, sources, numbers.critRange());

        boolean sneakAttack = false;
        Dice sneakDice = sneakAttack(encounter, actor, target, weapon, delivery, request.getSneakAttack(), notes);
        if (sneakDice != null) {
            damage.add(new Effect.DamagePart(sneakDice, weapon.damageType()));
            sneakAttack = true;
        }

        String label = weapon.name() + (notes.isEmpty() ? "" : " (" + String.join(", ", notes) + ")");
        Effect effect = new Effect(label, delivery, damage, null, List.of(), false, null);
        return new Prepared(effect, "uses " + label, "on", actor.getDisplayName() + "'s " + weapon.name() + " attack",
                ManualModifiers.forWeaponAttack(request.getExtraAttackBonus(), extraDamage, extraType, forcedCritical),
                startsReckless, weapon, melee, sneakAttack, numbers.martialArts(), blessed);
    }

    // Whether the attacker's Vow of Enmity is on the target (PHB p.88)
    private static boolean vowedAgainst(EncounterParticipant attacker, EncounterParticipant target) {
        return target.getActiveConditions() != null && target.getActiveConditions().stream()
                .anyMatch(c -> ChannelDivinity.VOW_OF_ENMITY.equalsIgnoreCase(c.name())
                        && attacker.getId().equals(c.sourceParticipantId()));
    }

    /**
     * Sneak Attack's dice for this attack, or null (PHB p.96): a Rogue's, once per turn, with a finesse or ranged
     * weapon, when the attack has advantage once every source counts or the player claims an ally of theirs next to the
     * target, and never with disadvantage. Its note goes into the attack's label, or why a claim came to nothing.
     *
     * @throws ValidationException a claim without Rogue levels or with another weapon
     * @throws ConflictException   a claim after Sneak Attack was dealt this turn
     */
    private Dice sneakAttack(Encounter encounter, EncounterParticipant actor, EncounterParticipant target,
                             WeaponRules.Weapon weapon, Effect.Attack delivery, Boolean claim, List<String> notes) {
        int rogueLevel = Math.min(20, FeatureEffects.classLevel(actor.getCharacter(), ROGUE));
        boolean claimed = Boolean.TRUE.equals(claim);
        if (claimed && rogueLevel < 1) {
            throw new ValidationException(actor.getDisplayName() + " has no Rogue levels for Sneak Attack");
        }
        if (claimed && !AttackFeatures.sneakAttackWeapon(weapon)) {
            throw new ValidationException("Sneak Attack needs a finesse or ranged weapon, and "
                    + (weapon == WeaponRules.UNARMED_STRIKE ? "an unarmed strike" : "the " + weapon.name())
                    + " is neither");
        }
        if (rogueLevel < 1 || !AttackFeatures.sneakAttackWeapon(weapon)) {
            return null;
        }
        boolean used = EncounterTurns.stamp(encounter).equals(economyOf(actor).sneakAttackTurn());
        if (used) {
            if (claimed) {
                throw new ConflictException(actor.getDisplayName() + " has already dealt Sneak Attack damage this "
                        + "turn; it can be dealt once per turn");
            }
            return null;
        }
        RollMode mode = AttackRules.setup(delivery.attackerSources(), CreatureStats.conditionNames(target),
                delivery.withinFiveFeet()).rollMode();
        if (!AttackFeatures.sneakAttackApplies(claim, mode)) {
            if (claimed) {
                notes.add("no Sneak Attack with disadvantage");
            }
            return null;
        }
        Dice dice = AttackFeatures.sneakAttack(rogueLevel);
        notes.add("Sneak Attack +" + dice);
        return dice;
    }

    // Martial Arts' and Flurry of Blows' strikes are a monk's unarmed strikes (PHB p.78)
    private static void requireMonkStrike(EncounterParticipant actor, WeaponRules.Weapon weapon, AttackCost cost) {
        if (cost != AttackCost.MARTIAL_ARTS && cost != AttackCost.FLURRY_OF_BLOWS) {
            return;
        }
        String feature = cost == AttackCost.MARTIAL_ARTS ? "Martial Arts" : "Flurry of Blows";
        int needs = cost == AttackCost.MARTIAL_ARTS ? 1 : AttackFeatures.FLURRY_OF_BLOWS_LEVEL;
        if (FeatureEffects.classLevel(actor.getCharacter(), MONK) < needs) {
            throw new ValidationException(feature + " needs " + (needs == 1 ? "Monk levels" : "Monk level " + needs)
                    + ", and " + actor.getDisplayName() + " hasn't " + (needs == 1 ? "any" : "it"));
        }
        if (weapon != WeaponRules.UNARMED_STRIKE) {
            throw new ValidationException(feature + " strikes with unarmed strikes, not the " + weapon.name());
        }
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
        TurnEconomy economy = economyOf(actor);
        if (!alreadyReckless && economy.attackActionTaken()) {
            throw new ConflictException("Reckless Attack is chosen on the first attack of the turn, and "
                    + actor.getDisplayName() + " has already attacked");
        }
    }

    // The Rage Damage of the barbarian's level (PHB p.46); none for a creature raging without barbarian levels
    private static int rageDamage(int barbarianLevel) {
        return barbarianLevel >= 1 ? ClassFeatures.rageDamage(Math.min(20, barbarianLevel)) : 0;
    }

    // ── Attacks entered by hand ──────────────────────────────────

    // An attack whose numbers are all entered by hand: a Manual Modifier as a whole
    private Prepared enteredAttack(EncounterParticipant actor, AttackRollRequest request, AttackCost cost) {
        if (request.getAttackBonus() == null || request.getDamageDice() == null || request.getDamageDice().isBlank()) {
            throw new ValidationException("Choose a weapon, or enter the attack bonus and damage dice");
        }
        if (request.getPowerAttack() != null || request.getExtraAttackBonus() != null
                || request.getExtraDamageDice() != null || request.getExtraDamageType() != null) {
            throw new ValidationException("Great Weapon Master, Sharpshooter and extra bonuses go with a weapon attack; "
                    + "an attack entered by hand includes them in its numbers");
        }
        if (Boolean.TRUE.equals(request.getRecklessAttack()) || request.getGrip() != null
                || request.getSneakAttack() != null) {
            throw new ValidationException("Reckless Attack, a grip and Sneak Attack go with a weapon attack, whose "
                    + "numbers the server works out");
        }
        if (cost == AttackCost.MARTIAL_ARTS || cost == AttackCost.FLURRY_OF_BLOWS) {
            throw new ValidationException("Martial Arts and Flurry of Blows strike with an unarmed strike: choose it "
                    + "as the weapon");
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
                ManualModifiers.forEnteredAttack(request.getAttackBonus(), damage, damageType, forcedCritical), false,
                null, false, false, false, false);
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

    // Whether the participant wears armour or carries a shield, which stops Martial Arts (PHB p.78)
    private boolean armoured(EncounterParticipant participant) {
        var worn = stats.armour(participant);
        return worn.armour() != null || worn.shield() != null;
    }

    private static TurnEconomy economyOf(EncounterParticipant participant) {
        return participant.getTurnEconomy() != null ? participant.getTurnEconomy() : TurnEconomy.fresh();
    }
}
