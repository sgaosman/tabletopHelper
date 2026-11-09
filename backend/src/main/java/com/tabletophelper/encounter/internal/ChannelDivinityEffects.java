package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.UsePoolRequest;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.EndRule;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.ProficiencyBonus;
import com.tabletophelper.rules.combat.ActionCost;
import com.tabletophelper.rules.combat.ChannelDivinity;
import com.tabletophelper.rules.combat.ChannelDivinity.Kind;
import com.tabletophelper.rules.combat.ChannelDivinity.Option;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.Outcome;
import com.tabletophelper.rules.combat.Resolver;
import com.tabletophelper.rules.combat.RollMode;
import com.tabletophelper.rules.combat.WeaponRules;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Channel Divinity used at the table (M17): the use-pool command's option on a cleric's or paladin's Channel Divinity
 * pool names what it does ({@link ChannelDivinity.Option}). Without an option the pool only spends, as before, for an
 * option the app doesn't model, such as one from outside the PHB. The save DC is the class's spell save DC (PHB p.205).
 * <ul>
 *   <li>Automated: Turn Undead with Destroy Undead (PHB p.59), Turn the Unholy (p.86) and Turn the Faithless (p.87),
 *       whose creatures that fail a Wisdom save are {@value ChannelDivinity#TURNED} for 1 minute or until they take
 *       damage; Charm Animals and Plants (p.62), charmed the same way; Preserve Life (p.60), healing split among the
 *       creatures picked; Radiance of the Dawn (p.61), a Constitution save against radiant damage; Nature's Wrath
 *       (p.87), restrained on a failed Strength or Dexterity save; Abjure Enemy (p.88), frightened on a failed Wisdom
 *       save; Cloak of Shadows (p.63), invisible until the end of the cleric's next turn or until it attacks or
 *       casts a spell; and markers the attack rules
 *       read: Sacred Weapon (p.86), Vow of Enmity (p.88) and War God's Blessing (p.63). Guided Strike (p.63) is chosen
 *       on the attack roll itself.</li>
 *   <li>Logged for the DM: Destructive Wrath (p.62), Knowledge of the Ages (p.59), Read Thoughts (p.59) and Invoke
 *       Duplicity (p.63).</li>
 * </ul>
 * There is no map, so ranges and "can see or hear" are the user's to respect: the creatures it picks are the ones
 * affected.
 */
@Component
@RequiredArgsConstructor
public class ChannelDivinityEffects {

    /** The use-pool command's effect for a Channel Divinity pool. */
    public static final String EFFECT = "channel-divinity";

    private static final int MOST_TARGETS = 20;

    private final EffectApplier effectApplier;
    private final CreatureStats stats;
    private final Resolver resolver;
    private final CharacterWeapons characterWeapons;

    /** The options the participant's Channel Divinity offers now, by its class, level and subclass; none for a monster. */
    public static List<Option> available(EncounterParticipant participant) {
        PlayerCharacter character = participant.getCharacter();
        if (character == null) {
            return List.of();
        }
        List<Option> options = new ArrayList<>();
        for (String className : List.of(ChannelDivinity.CLERIC, ChannelDivinity.PALADIN)) {
            int level = FeatureEffects.classLevel(character, className);
            if (level > 0) {
                options.addAll(ChannelDivinity.available(className, subclassOf(character, className), level));
            }
        }
        return options;
    }

    /**
     * Checks a use before anything is charged.
     *
     * @throws ValidationException for an unknown option, one of the other class's, Guided Strike (chosen on the
     *                             attack), more than one use, or targets the option doesn't take
     * @throws ConflictException   for an option the participant's level or subclass doesn't give, a creature of a type
     *                             the option doesn't affect, too much healing, or a dead target
     */
    public FeatureEffects.Plan plan(Encounter encounter, EncounterParticipant user, ResourcePoolEntry pool,
                                    UsePoolRequest request, String optionId, int uses, UUID userId,
                                    ActionCost poolCost) {
        List<UUID> targetIds = request.targetIds() != null ? request.targetIds() : List.of();
        if (optionId == null) {
            if (!targetIds.isEmpty() || request.amounts() != null) {
                throw new ValidationException(pool.displayName() + " takes targets only with an option");
            }
            return new FeatureEffects.Plan(EFFECT, null, poolCost, uses, user, "");
        }
        if (uses != 1) {
            throw new ValidationException(pool.displayName() + " is used once at a time");
        }
        String className = classOf(pool);
        Option option = ChannelDivinity.option(optionId)
                .filter(found -> found.className().equals(className))
                .orElseThrow(() -> new ValidationException(className + " Channel Divinity has no option \"" + optionId
                        + "\"; its options are " + java.util.Arrays.stream(Option.values())
                        .filter(known -> known.className().equals(className))
                        .map(Option::id).collect(Collectors.joining(", "))));
        PlayerCharacter character = user.getCharacter();
        int level = FeatureEffects.classLevel(character, className);
        if (!option.availableTo(className, subclassOf(character, className), level)) {
            throw new ConflictException(option.label() + " needs " + className + " level " + option.level()
                    + (option.subclass() != null ? " and the " + subclassLabel(option) : "") + ", and "
                    + user.getDisplayName() + " doesn't have " + (level < option.level() ? "the level" : "it"));
        }
        if (request.amounts() != null && option.kind() != Kind.PRESERVE_LIFE) {
            throw new ValidationException(option.label() + " takes no amounts");
        }
        if (request.weapon() != null && option != Option.SACRED_WEAPON) {
            throw new ValidationException(option.label() + " takes no weapon");
        }
        int dc = saveDc(user, className);
        String save = " (" + saveName(option, dc) + ")";
        String note = " for " + option.label();
        return switch (option.kind()) {
            case ON_ATTACK -> throw new ValidationException(option.label() + " is chosen on the attack roll it adds "
                    + "to: tick it on the attack");
            case TURN, CHARM, RADIANCE -> {
                List<EncounterParticipant> targets = targets(encounter, user, targetIds, userId, option);
                if (option.kind() != Kind.RADIANCE) {
                    for (EncounterParticipant target : targets) {
                        String type = typeOf(target);
                        if (!ChannelDivinity.ofType(type, option.turnedTypes())) {
                            throw new ValidationException(option.label() + " affects only "
                                    + String.join(" or ", option.turnedTypes()) + " creatures, and "
                                    + target.getDisplayName() + " is " + (type != null ? type : "neither"));
                        }
                    }
                }
                String destroy = option == Option.TURN_UNDEAD && ChannelDivinity.destroyUndeadRating(level).isPresent()
                        ? "; Destroy Undead destroys one of challenge rating "
                        + rating(ChannelDivinity.destroyUndeadRating(level).getAsDouble()) + " or lower that fails"
                        : "";
                yield new FeatureEffects.Plan(EFFECT, option.id(), option.cost(), 1, user,
                        note + save + ": " + option.text() + destroy, targets, List.of());
            }
            case PRESERVE_LIFE -> preserveLife(encounter, user, request, option, level, userId, note);
            case RESTRAIN, FRIGHTEN -> {
                EncounterParticipant target = single(encounter, user, targetIds, userId, option, false);
                yield new FeatureEffects.Plan(EFFECT, option.id(), option.cost(), 1, target,
                        note + save + ": " + option.text(), List.of(target), List.of());
            }
            case TARGET_MARKER -> {
                EncounterParticipant target = single(encounter, user, targetIds, userId, option,
                        option == Option.WAR_GODS_BLESSING);
                yield new FeatureEffects.Plan(EFFECT, option.id(), option.cost(), 1, target,
                        note + (target != user ? " on " + target.getDisplayName() : "") + ": " + option.text(),
                        List.of(target), List.of());
            }
            case SELF_MARKER, INVISIBLE -> {
                requireNoTargets(option, targetIds);
                if (option != Option.SACRED_WEAPON) {
                    yield new FeatureEffects.Plan(EFFECT, option.id(), option.cost(), 1, user,
                            note + ": " + option.text(), List.of(user), List.of());
                }
                String weapon = sacredWeapon(user, request.weapon());
                String bonus = " (+" + ChannelDivinity.sacredWeaponBonus(stats.abilityScore(user, Ability.CHA)) + ")";
                yield new FeatureEffects.Plan(EFFECT, option.id(), option.cost(), 1, user,
                        note + " on the " + weapon + bonus + ": " + option.text(),
                        List.of(user), List.of(), weapon);
            }
            case LOG -> {
                if (option == Option.READ_THOUGHTS) {
                    EncounterParticipant target = single(encounter, user, targetIds, userId, option, false);
                    yield new FeatureEffects.Plan(EFFECT, option.id(), option.cost(), 1, target,
                            note + " (WIS save DC " + dc + ") on " + target.getDisplayName() + ": " + option.text(),
                            List.of(target), List.of());
                }
                requireNoTargets(option, targetIds);
                yield new FeatureEffects.Plan(EFFECT, option.id(), option.cost(), 1, user, note + ": " + option.text());
            }
        };
    }

    /** Does what the option does, once the turn is charged and the pool spent. */
    public void apply(Encounter encounter, EncounterParticipant user, FeatureEffects.Plan plan) {
        if (plan.option() == null) {
            return;
        }
        Option option = ChannelDivinity.option(plan.option()).orElseThrow();
        String className = option.className();
        int dc = saveDc(user, className);
        switch (option.kind()) {
            case TURN -> turn(encounter, user, plan.targets(), option, dc,
                    FeatureEffects.classLevel(user.getCharacter(), className));
            case CHARM -> saveAgainst(encounter, user, plan.targets(), option,
                    new Effect(option.label(), new Effect.Save(Ability.WIS, dc, false), List.of(), null,
                            List.of("charmed"), false, ChannelDivinity.MINUTE_ROUNDS,
                            Map.of("charmed", Effect.ConditionEnd.untilDamaged(ChannelDivinity.MINUTE_ROUNDS))),
                    false);
            case RADIANCE -> saveAgainst(encounter, user, plan.targets(), option,
                    new Effect(option.label(), new Effect.Save(Ability.CON, dc, true),
                            List.of(new Effect.DamagePart(ChannelDivinity.radianceOfTheDawn(
                                    Math.min(20, FeatureEffects.classLevel(user.getCharacter(), className))),
                                    "radiant")), null, List.of(), false, null), false);
            case PRESERVE_LIFE -> {
                for (int i = 0; i < plan.targets().size(); i++) {
                    effectApplier.heal(encounter, user, plan.targets().get(i), plan.amounts().get(i), option.label());
                }
            }
            case RESTRAIN -> {
                EncounterParticipant target = plan.targets().getFirst();
                // The creature chooses Strength or Dexterity (PHB p.87): the better of its two saves
                Ability ability = stats.saveModifier(target, Ability.DEX) > stats.saveModifier(target, Ability.STR)
                        ? Ability.DEX : Ability.STR;
                saveAgainst(encounter, user, plan.targets(), option,
                        new Effect(option.label(), new Effect.Save(ability, dc, false), List.of(), null,
                                List.of("restrained"), false, null,
                                Map.of("restrained", new Effect.ConditionEnd(null, new Effect.RepeatSave(ability, dc)))),
                        false);
            }
            case FRIGHTEN -> saveAgainst(encounter, user, plan.targets(), option,
                    new Effect(option.label(), new Effect.Save(Ability.WIS, dc, false), List.of(), null,
                            List.of("frightened"), false, ChannelDivinity.MINUTE_ROUNDS,
                            Map.of("frightened", Effect.ConditionEnd.untilDamaged(ChannelDivinity.MINUTE_ROUNDS))),
                    true);
            case INVISIBLE -> {
                // Invisible until the end of the cleric's next turn: on its own turn that is a round on; it becomes
                // visible if it attacks or casts a spell (PHB p.63)
                boolean ownTurn = EncounterTurns.isCurrentTurn(encounter, user.getId());
                Effect effect = new Effect(option.label(), new Effect.Self(), List.of(), null, List.of("invisible"),
                        false, null, Map.of("invisible", new Effect.ConditionEnd(ownTurn ? 1 : null, null, null, true)
                                .endingOnAttackOrCast()));
                apply(encounter, user, option, effect, List.of(stats.target(user, effect)));
            }
            case SELF_MARKER -> effectApplier.addFeatureEntry(user, new ConditionEntry(UUID.randomUUID(),
                    ChannelDivinity.SACRED_WEAPON, ChannelDivinity.MINUTE_ROUNDS, encounter.getRoundNumber(),
                    option.label(), user.getId(), null, null, EndRule.afterRounds(user.getId()), null, null, null,
                    plan.weapon()));
            case TARGET_MARKER -> mark(encounter, user, plan.targets().getFirst(), option);
            case ON_ATTACK, LOG -> {
            }
        }
    }

    // ── The options ──────────────────────────────────────────────

    /*
     * The one weapon Sacred Weapon imbues, "one weapon that you are holding" (PHB p.86), by the name the attack options
     * give it: the one the request names, or the paladin's only weapon, or the only one of several it has equipped. An
     * unarmed strike isn't a weapon it holds. There is no hand tracking, so any weapon it carries counts as one it can
     * hold.
     */
    private String sacredWeapon(EncounterParticipant user, String named) {
        PlayerCharacter character = user.getCharacter();
        List<String> weapons = character == null ? List.of() : characterWeapons.of(character).stream()
                .map(held -> held.weapon().name())
                .filter(name -> !name.equalsIgnoreCase(WeaponRules.UNARMED_STRIKE.name()))
                .toList();
        if (named != null && !named.isBlank()) {
            if (named.trim().equalsIgnoreCase(WeaponRules.UNARMED_STRIKE.name())) {
                throw new ValidationException("Sacred Weapon imbues a weapon " + user.getDisplayName()
                        + " is holding, not an unarmed strike");
            }
            return weapons.stream().filter(name -> name.equalsIgnoreCase(named.trim())).findFirst()
                    .orElseThrow(() -> new ValidationException(user.getDisplayName() + " has no weapon called "
                            + named.trim() + (weapons.isEmpty() ? "" : "; their weapons are "
                            + String.join(", ", weapons))));
        }
        if (weapons.isEmpty()) {
            throw new ConflictException(user.getDisplayName() + " has no weapon for Sacred Weapon to imbue");
        }
        if (weapons.size() == 1) {
            return weapons.getFirst();
        }
        List<String> equipped = equipped(character, weapons);
        if (equipped.size() == 1) {
            return equipped.getFirst();
        }
        throw new ValidationException("Choose the weapon Sacred Weapon imbues: " + String.join(", ", weapons));
    }

    // The weapons whose inventory entry is marked equipped, by the entry's name
    private static List<String> equipped(PlayerCharacter character, List<String> weapons) {
        Set<String> names = character.getEquipment() == null ? Set.of() : character.getEquipment().stream()
                .filter(entry -> entry != null && Boolean.TRUE.equals(entry.equipped()) && entry.name() != null)
                .map(entry -> entry.name().trim().toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.toSet());
        return weapons.stream().filter(name -> names.contains(name.toLowerCase(java.util.Locale.ROOT))).toList();
    }

    // Each creature picked makes its Wisdom save, with advantage for one whose stat block resists turning, such as a
    // vampire spawn's Turn Resistance; Destroy Undead destroys a cleric's turned undead of a low enough rating
    private void turn(Encounter encounter, EncounterParticipant user, List<EncounterParticipant> targets, Option option,
                      int dc, int classLevel) {
        Effect effect = new Effect(option.label(), new Effect.Save(Ability.WIS, dc, false), List.of(), null,
                List.of(ChannelDivinity.TURNED), false, ChannelDivinity.MINUTE_ROUNDS,
                Map.of(ChannelDivinity.TURNED, Effect.ConditionEnd.untilDamaged(ChannelDivinity.MINUTE_ROUNDS)));
        List<Resolver.Target> rolled = targets.stream()
                .map(target -> {
                    Resolver.Target base = stats.target(target, effect);
                    return target.getMonster() != null
                            && ChannelDivinity.resistsTurning(target.getMonster().getTraits())
                            ? base.withSaveSource(RollMode.ADVANTAGE) : base;
                })
                .toList();
        List<EffectApplier.Applied> applied = apply(encounter, user, option, effect, rolled);
        if (option != Option.TURN_UNDEAD) {
            return;
        }
        // Destroy Undead (Cleric 5, PHB p.59)
        for (EffectApplier.Applied result : applied) {
            EncounterParticipant target = result.target();
            if (result.outcome().outcome() == Outcome.SAVE_FAIL && target.getMonster() != null
                    && ChannelDivinity.destroys(classLevel, target.getMonster().getChallengeRating())) {
                effectApplier.removeFeatureEntries(target, ChannelDivinity.TURNED, user.getId());
                // Legendary Resistance on the failed save undoes the destruction too
                effectApplier.destroy(encounter, target, "Destroy Undead", result);
            }
        }
    }

    private void saveAgainst(Encounter encounter, EncounterParticipant user, List<EncounterParticipant> targets,
                             Option option, Effect effect, boolean fiendsAndUndeadDisadvantaged) {
        List<Resolver.Target> rolled = targets.stream()
                .map(target -> {
                    Resolver.Target base = stats.target(target, effect);
                    // Abjure Enemy: fiends and undead save with disadvantage (PHB p.88)
                    return fiendsAndUndeadDisadvantaged
                            && ChannelDivinity.ofType(typeOf(target), Set.of("fiend", "undead"))
                            ? base.withSaveSource(RollMode.DISADVANTAGE) : base;
                })
                .toList();
        apply(encounter, user, option, effect, rolled);
    }

    private List<EffectApplier.Applied> apply(Encounter encounter, EncounterParticipant user, Option option,
                                              Effect effect, List<Resolver.Target> targets) {
        EffectApplier.Use use = new EffectApplier.Use(user, user.getDisplayName(), CombatActionType.POOL_USE,
                "channels divinity: " + option.label(), "at", option.label(), null);
        return effectApplier.apply(encounter, use, effect, resolver.resolve(effect, targets));
    }

    // Vow of Enmity: one vow at a time, so an earlier one by the paladin ends; War God's Blessing lasts until the
    // creature's next attack roll, at the latest the end of the turn it is given on
    private void mark(Encounter encounter, EncounterParticipant user, EncounterParticipant target, Option option) {
        if (option == Option.VOW_OF_ENMITY) {
            for (EncounterParticipant other : encounter.getParticipants()) {
                effectApplier.removeFeatureEntries(other, ChannelDivinity.VOW_OF_ENMITY, user.getId());
            }
            effectApplier.addFeatureEntry(target, new ConditionEntry(UUID.randomUUID(), ChannelDivinity.VOW_OF_ENMITY,
                    ChannelDivinity.MINUTE_ROUNDS, encounter.getRoundNumber(), option.label(), user.getId(), null,
                    null, EndRule.afterRounds(user.getId()), null));
            return;
        }
        UUID turnOf = EncounterTurns.currentParticipant(encounter).map(EncounterParticipant::getId)
                .orElse(target.getId());
        effectApplier.addFeatureEntry(target, new ConditionEntry(UUID.randomUUID(), ChannelDivinity.WAR_GODS_BLESSING,
                null, encounter.getRoundNumber(), option.label(), user.getId(), null, null,
                EndRule.endOfTurn(turnOf), null));
    }

    private FeatureEffects.Plan preserveLife(Encounter encounter, EncounterParticipant user, UsePoolRequest request,
                                             Option option, int level, UUID userId, String note) {
        List<UUID> targetIds = request.targetIds() != null ? request.targetIds() : List.of();
        List<Integer> amounts = request.amounts() != null ? request.amounts() : List.of();
        if (targetIds.isEmpty() || amounts.size() != targetIds.size()) {
            throw new ValidationException(option.label() + " takes the creatures it heals and an amount for each");
        }
        if (new HashSet<>(targetIds).size() != targetIds.size()) {
            throw new ValidationException(option.label() + " names each creature once");
        }
        List<EncounterParticipant> targets = CombatAccess.targets(encounter, targetIds, userId);
        CombatAccess.requireNotDead(targets);
        int pool = ChannelDivinity.PRESERVE_LIFE_PER_LEVEL * level;
        int total = 0;
        for (int i = 0; i < targets.size(); i++) {
            Integer amount = amounts.get(i);
            if (amount == null || amount < 1) {
                throw new ValidationException(option.label() + " restores at least 1 hit point to each creature");
            }
            total += amount;
            EncounterParticipant target = targets.get(i);
            int hp = target.getHpCurrent() != null ? target.getHpCurrent() : 0;
            int max = CreatureStats.hpMax(target);
            ChannelDivinity.preserveLifeRefusal(target.getDisplayName(), typeOf(target), hp, max, amount)
                    .ifPresent(reason -> {
                        throw new ConflictException(reason);
                    });
        }
        if (total > pool) {
            throw new ConflictException(option.label() + " restores " + pool + " hit points at most (5 × cleric "
                    + "level " + level + "), not " + total);
        }
        return new FeatureEffects.Plan(EFFECT, option.id(), option.cost(), 1,
                targets.size() == 1 ? targets.getFirst() : user,
                note + ": restores " + total + " of " + pool + " hit points among " + targets.size() + " creature"
                        + (targets.size() == 1 ? "" : "s"), targets, amounts);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private static List<EncounterParticipant> targets(Encounter encounter, EncounterParticipant user,
                                                      List<UUID> targetIds, UUID userId, Option option) {
        if (targetIds.isEmpty()) {
            throw new ValidationException(option.label() + " needs the creatures it affects");
        }
        if (targetIds.size() > MOST_TARGETS || new HashSet<>(targetIds).size() != targetIds.size()) {
            throw new ValidationException(option.label() + " names each creature once, " + MOST_TARGETS + " at most");
        }
        List<EncounterParticipant> targets = CombatAccess.targets(encounter, targetIds, userId);
        if (targets.contains(user)) {
            throw new ValidationException(option.label() + " affects other creatures, not "
                    + user.getDisplayName());
        }
        CombatAccess.requireNotDead(targets);
        return targets;
    }

    private static EncounterParticipant single(Encounter encounter, EncounterParticipant user, List<UUID> targetIds,
                                               UUID userId, Option option, boolean selfAllowed) {
        if (targetIds.size() != 1) {
            throw new ValidationException(option.label() + " affects one creature: name it");
        }
        EncounterParticipant target = CombatAccess.target(encounter, targetIds.getFirst(), userId);
        if (target == user && !selfAllowed) {
            throw new ValidationException(option.label() + " affects another creature, not "
                    + user.getDisplayName());
        }
        CombatAccess.requireNotDead(List.of(target));
        return target;
    }

    private static void requireNoTargets(Option option, List<UUID> targetIds) {
        if (!targetIds.isEmpty()) {
            throw new ValidationException(option.label() + " takes no target");
        }
    }

    // The class whose Channel Divinity the pool is: a paladin's, or else a cleric's
    private static String classOf(ResourcePoolEntry pool) {
        return pool.poolId() != null && pool.poolId().toLowerCase().contains("paladin")
                ? ChannelDivinity.PALADIN : ChannelDivinity.CLERIC;
    }

    /** The subclass the character has in the class, from its class entries, or its own subclass when single-class. */
    static String subclassOf(PlayerCharacter character, String className) {
        if (character == null) {
            return null;
        }
        List<ClassEntry> entries = character.getMulticlassEntries();
        if (entries != null && !entries.isEmpty()) {
            return entries.stream()
                    .filter(entry -> entry != null && className.equalsIgnoreCase(
                            entry.className() != null ? entry.className().trim() : null))
                    .map(ClassEntry::subclassName)
                    .filter(name -> name != null && !name.isBlank())
                    .findFirst()
                    .orElse(null);
        }
        return className.equalsIgnoreCase(character.getCharacterClass() != null
                ? character.getCharacterClass().trim() : null) ? character.getSubclass() : null;
    }

    // The class's spell save DC (PHB p.205): Wisdom for a cleric, Charisma for a paladin
    private int saveDc(EncounterParticipant user, String className) {
        return ChannelDivinity.saveDc(proficiencyBonus(user.getCharacter()),
                stats.abilityScore(user, ChannelDivinity.saveAbility(className)));
    }

    private static int proficiencyBonus(PlayerCharacter character) {
        if (character != null && character.getProficiencyBonus() != null) {
            return character.getProficiencyBonus();
        }
        return ProficiencyBonus.forLevel(character != null && character.getLevel() != null ? character.getLevel() : 1);
    }

    private static String typeOf(EncounterParticipant participant) {
        return CreatureStats.creatureType(participant);
    }

    private static String saveName(Option option, int dc) {
        return switch (option.kind()) {
            case RADIANCE -> "CON save DC " + dc;
            case RESTRAIN -> "STR or DEX save DC " + dc;
            default -> "WIS save DC " + dc;
        };
    }

    private static String subclassLabel(Option option) {
        return option.className().equals(ChannelDivinity.CLERIC) ? option.subclass() + " Domain"
                : "Oath of " + (option.subclass().equals("Ancients") ? "the Ancients" : option.subclass());
    }

    private static String rating(double cr) {
        return cr == 0.5 ? "1/2" : String.valueOf((int) cr);
    }
}
