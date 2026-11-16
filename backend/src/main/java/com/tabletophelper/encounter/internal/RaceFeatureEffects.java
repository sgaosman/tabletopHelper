package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.DraconicAncestries;
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
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.Expression;
import com.tabletophelper.rules.ProficiencyBonus;
import com.tabletophelper.rules.Rng;
import com.tabletophelper.rules.combat.ActionCost;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.RacialFeatures;
import com.tabletophelper.rules.combat.RacialFeatures.DraconicAncestry;
import com.tabletophelper.rules.combat.Resolver;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What a racial feature does when its pool is used at the table (M24.5b, D208), for a race pool whose metadata names one
 * of these effects; {@link FeatureEffects} hands them here. As for class features, {@link #plan} checks the use before
 * anything is charged and {@link #apply} carries it out once the pool is spent, through {@link EffectApplier}. There is
 * no map, so ranges and areas are the user's to respect: the creatures it picks are the ones affected.
 * <ul>
 *   <li>{@value #BREATH_WEAPON} (Dragonborn, PHB p.34): an action; each creature picked makes a Dexterity save (a
 *       Constitution save against cold and poison) against 8 + CON modifier + proficiency bonus, taking 2d6 damage of
 *       the ancestry's type (3d6 at 6th level, 4d6 at 11th, 5d6 at 16th), half on a success. The ancestry is the one the
 *       character's levels record; until one is chosen, the request's option names it.</li>
 *   <li>{@value #RELENTLESS_ENDURANCE} (Half-Orc, PHB p.41): used by itself when the creature drops to 0 hit points
 *       (EffectApplier), so the table refuses it.</li>
 *   <li>{@value #STONES_ENDURANCE} (Goliath, VGM p.109): a reaction rolling 1d12 + CON modifier and restoring that much of
 *       the damage just taken, which the request's one amount gives; no more than that damage.</li>
 *   <li>{@value #HEALING_HANDS} (Aasimar, VGM p.105): an action touching one creature, the user when none is named, to
 *       restore the hit points its metadata's {@code healing} formula gives, the character's level.</li>
 *   <li>{@value #HIDDEN_STEP} (Firbolg, VGM p.107): a bonus action making the user invisible until the start of its next
 *       turn, ending early when it attacks or casts a spell.</li>
 *   <li>{@value #HUNGRY_JAWS} (Lizardfolk, VGM p.113): after the special bite, the option "hit" (the default) gives the
 *       temporary hit points of the metadata's {@code tempHp} formula; "miss" spends the use for nothing.</li>
 *   <li>{@value #SHIFTING} (Shifter, ERLW p.33): a bonus action giving the {@code tempHp} formula's temporary hit points
 *       and a {@value #SHIFTED} entry for 1 minute, logged with the subrace's {@code rider}.</li>
 *   <li>{@value #DAUNTING_ROAR} (Leonin, MOT p.21): a bonus action; each creature picked that isn't deafened makes a
 *       Wisdom save against 8 + CON modifier + proficiency bonus or is frightened until the end of the leonin's next
 *       turn.</li>
 *   <li>{@value #RADIANT_SOUL}, {@value #RADIANT_CONSUMPTION} and {@value #NECROTIC_SHROUD} (Aasimar 3rd level, VGM
 *       p.105): an action starting a 1-minute transformation, an entry whose extra damage the attacker adds by hand.
 *       Necrotic Shroud's creatures picked make a Charisma save against 8 + CHA modifier + proficiency bonus or are
 *       frightened until the end of the aasimar's next turn.</li>
 *   <li>{@value #NOTE} (Fey Step, Fury of the Small, Saving Face and others): the use is logged with the metadata's
 *       {@code note}, {level} and {proficiencyBonus} in it filled in; the DM resolves the rest. A pool whose metadata
 *       says {@code regain}, as Feline Agility's, takes the option "regain" to get its use back.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RaceFeatureEffects {

    public static final String BREATH_WEAPON = "breath-weapon";
    public static final String RELENTLESS_ENDURANCE = "relentless-endurance";
    public static final String STONES_ENDURANCE = "stones-endurance";
    public static final String HEALING_HANDS = "healing-hands";
    public static final String HIDDEN_STEP = "hidden-step";
    public static final String HUNGRY_JAWS = "hungry-jaws";
    public static final String SHIFTING = "shifting";
    public static final String DAUNTING_ROAR = "daunting-roar";
    public static final String RADIANT_SOUL = "radiant-soul";
    public static final String RADIANT_CONSUMPTION = "radiant-consumption";
    public static final String NECROTIC_SHROUD = "necrotic-shroud";
    public static final String NOTE = "racial-note";

    private static final Set<String> EFFECTS = Set.of(BREATH_WEAPON, RELENTLESS_ENDURANCE, STONES_ENDURANCE,
            HEALING_HANDS, HIDDEN_STEP, HUNGRY_JAWS, SHIFTING, DAUNTING_ROAR, RADIANT_SOUL, RADIANT_CONSUMPTION,
            NECROTIC_SHROUD, NOTE);

    /** Hungry Jaws' options: the bite hit, or it missed. */
    public static final String HIT = "hit";
    public static final String MISS = "miss";
    /** A regainable note pool's option, as Feline Agility's after a turn on which it moved 0 feet. */
    public static final String REGAIN = "regain";

    /** The entries the 1-minute features leave on their creature. */
    public static final String SHIFTED = "shifted";
    public static final String RADIANT_SOUL_ENTRY = "radiant soul";
    public static final String RADIANT_CONSUMPTION_ENTRY = "radiant consumption";
    public static final String NECROTIC_SHROUD_ENTRY = "necrotic shroud";

    private static final String FRIGHTENED = "frightened";
    private static final String INVISIBLE = "invisible";
    private static final String DEAFENED = "deafened";
    private static final int MOST_TARGETS = 20;

    private final EffectApplier effectApplier;
    private final CreatureStats stats;
    private final Resolver resolver;
    private final Rng rng;

    /** Whether the effect is a racial feature's, which this class carries out. */
    public static boolean handles(String effect) {
        return effect != null && EFFECTS.contains(effect);
    }

    /**
     * Checks a use before anything is charged.
     *
     * @throws ValidationException for an option, target or amount the feature doesn't take, more than one use, or a
     *                             Breath Weapon without an ancestry
     * @throws ConflictException   for Relentless Endurance, which is used by itself, a dead creature, or a regain of a
     *                             pool that is full
     */
    public FeatureEffects.Plan plan(Encounter encounter, EncounterParticipant user, ResourcePoolEntry pool,
                                    UsePoolRequest request, String option, int uses, UUID userId,
                                    ActionCost poolCost) {
        List<UUID> targetIds = request.targetIds() != null ? request.targetIds() : List.of();
        String effect = FeatureEffects.effectOf(pool);
        if (RELENTLESS_ENDURANCE.equals(effect)) {
            throw new ConflictException(pool.displayName() + " is used by itself when " + user.getDisplayName()
                    + " drops to 0 hit points without being killed outright");
        }
        if (request.amounts() != null && !STONES_ENDURANCE.equals(effect)) {
            throw new ValidationException(pool.displayName() + " takes no amounts");
        }
        if (uses != 1) {
            throw new ValidationException(pool.displayName() + " can be used only once at a time");
        }
        if (user.getLifeState() == com.tabletophelper.rules.combat.LifeState.DEAD) {
            throw new ConflictException(user.getDisplayName() + " is dead");
        }
        return switch (effect) {
            case BREATH_WEAPON -> breathWeapon(encounter, user, pool, targetIds, option, userId, poolCost);
            case STONES_ENDURANCE -> stonesEndurance(user, pool, request, option, targetIds, poolCost);
            case HEALING_HANDS -> healingHands(encounter, user, pool, targetIds, option, userId, poolCost);
            case HIDDEN_STEP -> {
                requireNoOptionOrTargets(pool, option, targetIds);
                yield new FeatureEffects.Plan(effect, null, poolCost, 1, user, ": turns invisible until the start of "
                        + "its next turn, or until it attacks or casts a spell");
            }
            case HUNGRY_JAWS -> hungryJaws(user, pool, option, targetIds, poolCost);
            case SHIFTING -> {
                requireNoOptionOrTargets(pool, option, targetIds);
                String rider = text(pool, "rider");
                yield new FeatureEffects.Plan(effect, null, poolCost, 1, user, ": shifts for 1 minute"
                        + (rider != null ? ", with " + rider : ""));
            }
            case DAUNTING_ROAR -> {
                requireNoOption(pool, option);
                List<EncounterParticipant> targets = others(encounter, user, targetIds, userId, pool, true);
                int dc = saveDc(user, Ability.CON);
                yield new FeatureEffects.Plan(effect, null, poolCost, 1, user, " (WIS save DC " + dc + "): each "
                        + "creature picked that can hear it is frightened of it until the end of its next turn on a "
                        + "failed save", targets, List.of());
            }
            case RADIANT_SOUL, RADIANT_CONSUMPTION -> {
                requireNoOptionOrTargets(pool, option, targetIds);
                yield new FeatureEffects.Plan(effect, null, poolCost, 1, user, ": transforms for 1 minute, "
                        + transformation(effect, level(user)));
            }
            case NECROTIC_SHROUD -> {
                requireNoOption(pool, option);
                List<EncounterParticipant> targets = targetIds.isEmpty() ? List.of()
                        : others(encounter, user, targetIds, userId, pool, true);
                int dc = saveDc(user, Ability.CHA);
                yield new FeatureEffects.Plan(effect, null, poolCost, 1, user, ": transforms for 1 minute, "
                        + transformation(effect, level(user)) + (targets.isEmpty() ? ""
                        : "; the creatures picked make a CHA save DC " + dc + " or are frightened of it until the end "
                        + "of its next turn"), targets, List.of());
            }
            case NOTE -> note(user, pool, option, targetIds, poolCost);
            default -> throw new IllegalStateException("The " + pool.poolId() + " pool has an unknown effect " + effect);
        };
    }

    /** Does what the feature does, once the turn is charged and the pool spent. */
    public void apply(Encounter encounter, EncounterParticipant user, FeatureEffects.Plan plan) {
        switch (plan.effect()) {
            case BREATH_WEAPON -> {
                DraconicAncestry ancestry = DraconicAncestry.of(plan.option()).orElseThrow();
                Effect effect = new Effect("Breath Weapon", new Effect.Save(ancestry.save(), saveDc(user, Ability.CON),
                        true), List.of(new Effect.DamagePart(RacialFeatures.breathWeaponDamage(level(user)),
                        ancestry.damageType())), null, List.of(), false, null);
                List<Resolver.Target> rolled = plan.targets().stream().map(t -> stats.target(t, effect)).toList();
                effectApplier.apply(encounter, use(user, "breathes " + ancestry.damageType(), "Breath Weapon"), effect,
                        resolver.resolve(effect, rolled));
            }
            case STONES_ENDURANCE -> {
                int taken = plan.amounts().getFirst();
                int rolled = rng.roll(12);
                int modifier = Ability.modifier(stats.abilityScore(user, Ability.CON));
                int total = Math.max(0, rolled + modifier);
                int restored = Math.min(total, taken);
                effectApplier.heal(encounter, user, user, restored, "Stone's Endurance: 1d12(" + rolled + ") "
                        + signed(modifier) + " = " + total + ", of the " + taken + " damage taken");
            }
            case HEALING_HANDS -> effectApplier.heal(encounter, user, plan.target(), plan.amounts().getFirst(),
                    "Healing Hands");
            case HIDDEN_STEP -> {
                // Invisible until the start of its next turn, or until it attacks or casts a spell (VGM p.107)
                Effect effect = new Effect("Hidden Step", new Effect.Self(), List.of(), null, List.of(INVISIBLE), false,
                        null, Map.of(INVISIBLE, new Effect.ConditionEnd(1, null, null, false).endingOnAttackOrCast()));
                effectApplier.apply(encounter, use(user, "uses Hidden Step", "Hidden Step"), effect,
                        resolver.resolve(effect, List.of(stats.target(user, effect))));
            }
            case HUNGRY_JAWS -> {
                if (!MISS.equals(plan.option())) {
                    gainTempHp(encounter, user, plan, "Hungry Jaws");
                }
            }
            case SHIFTING -> {
                gainTempHp(encounter, user, plan, "Shifting");
                effectApplier.addFeatureEntry(user, minuteEntry(encounter, user, SHIFTED, "Shifting"));
            }
            case DAUNTING_ROAR -> frighten(encounter, user, plan.targets(), "Daunting Roar", Ability.WIS,
                    saveDc(user, Ability.CON), true);
            case RADIANT_SOUL -> effectApplier.addFeatureEntry(user, minuteEntry(encounter, user, RADIANT_SOUL_ENTRY,
                    "Radiant Soul"));
            case RADIANT_CONSUMPTION -> effectApplier.addFeatureEntry(user, minuteEntry(encounter, user,
                    RADIANT_CONSUMPTION_ENTRY, "Radiant Consumption"));
            case NECROTIC_SHROUD -> {
                effectApplier.addFeatureEntry(user, minuteEntry(encounter, user, NECROTIC_SHROUD_ENTRY,
                        "Necrotic Shroud"));
                frighten(encounter, user, plan.targets(), "Necrotic Shroud", Ability.CHA, saveDc(user, Ability.CHA),
                        false);
            }
            case NOTE -> {
                if (REGAIN.equals(plan.option())) {
                    regain(user, plan);
                }
            }
            default -> throw new IllegalStateException("Unknown racial effect " + plan.effect());
        }
    }

    // ── The features ─────────────────────────────────────────────

    private FeatureEffects.Plan breathWeapon(Encounter encounter, EncounterParticipant user, ResourcePoolEntry pool,
                                             List<UUID> targetIds, String option, UUID userId, ActionCost poolCost) {
        PlayerCharacter character = user.getCharacter();
        DraconicAncestry recorded = character != null ? DraconicAncestries.of(character).orElse(null) : null;
        DraconicAncestry named = null;
        if (option != null) {
            named = DraconicAncestry.of(option).orElseThrow(() -> new ValidationException("A draconic ancestry is "
                    + "one of " + String.join(", ", DraconicAncestry.dragons()) + ", not " + option));
        }
        if (recorded != null && named != null && recorded != named) {
            throw new ValidationException(user.getDisplayName() + "'s draconic ancestry is " + recorded.dragon()
                    + ", not " + named.dragon());
        }
        DraconicAncestry ancestry = recorded != null ? recorded : named;
        if (ancestry == null) {
            throw new ValidationException(user.getDisplayName() + " has no draconic ancestry recorded: choose it on the "
                    + "character sheet, or name the dragon for this breath");
        }
        List<EncounterParticipant> targets = others(encounter, user, targetIds, userId, pool, true);
        int dc = saveDc(user, Ability.CON);
        Dice dice = RacialFeatures.breathWeaponDamage(level(user));
        return new FeatureEffects.Plan(BREATH_WEAPON, ancestry.dragon(), poolCost, 1, user, " (" + ancestry.dragon()
                + " ancestry, " + ancestry.shape().text() + ", " + ancestry.save().name() + " save DC " + dc + "): "
                + dice.count() + "d" + dice.sides() + " " + ancestry.damageType() + " damage, half on a success",
                targets, List.of());
    }

    // The damage just taken is the request's one amount: the app keeps no record of the last damage a creature took
    private static FeatureEffects.Plan stonesEndurance(EncounterParticipant user, ResourcePoolEntry pool,
                                                       UsePoolRequest request, String option, List<UUID> targetIds,
                                                       ActionCost poolCost) {
        requireNoOptionOrTargets(pool, option, targetIds);
        if (request.amounts() == null || request.amounts().size() != 1) {
            throw new ValidationException(pool.displayName() + " takes the damage just taken, as its one amount");
        }
        int taken = request.amounts().getFirst();
        return new FeatureEffects.Plan(STONES_ENDURANCE, null, poolCost, 1, user, " to reduce the " + taken
                + " damage it just took by 1d12 + its Constitution modifier", List.of(user), List.of(taken));
    }

    private FeatureEffects.Plan healingHands(Encounter encounter, EncounterParticipant user, ResourcePoolEntry pool,
                                             List<UUID> targetIds, String option, UUID userId, ActionCost poolCost) {
        requireNoOption(pool, option);
        if (targetIds.size() > 1) {
            throw new ValidationException(pool.displayName() + " touches one creature");
        }
        EncounterParticipant target = targetIds.isEmpty() ? user : CombatAccess.target(encounter, targetIds.getFirst(),
                userId);
        CombatAccess.requireNotDead(List.of(target));
        String formula = text(pool, "healing");
        int amount = Math.max(0, formula != null ? Expression.evaluate(formula, context(user), rng) : level(user));
        return new FeatureEffects.Plan(HEALING_HANDS, null, poolCost, 1, target,
                (target != user ? " on " + target.getDisplayName() : "") + " to restore " + amount + " HP",
                List.of(target), List.of(amount));
    }

    private static FeatureEffects.Plan hungryJaws(EncounterParticipant user, ResourcePoolEntry pool, String option,
                                                  List<UUID> targetIds, ActionCost poolCost) {
        if (option != null && !HIT.equals(option) && !MISS.equals(option)) {
            throw new ValidationException(pool.displayName() + " takes the option \"" + HIT + "\" or \"" + MISS + "\"");
        }
        if (!targetIds.isEmpty()) {
            throw new ValidationException(pool.displayName() + " takes no target: roll the bite on the attack form");
        }
        boolean missed = MISS.equals(option);
        return new FeatureEffects.Plan(HUNGRY_JAWS, missed ? MISS : HIT, poolCost, 1, user, missed
                ? ": the special bite missed, so it gains nothing"
                : ": the special bite hit, dealing its normal damage (roll it on the attack form)");
    }

    private FeatureEffects.Plan note(EncounterParticipant user, ResourcePoolEntry pool, String option,
                                     List<UUID> targetIds, ActionCost poolCost) {
        if (!targetIds.isEmpty()) {
            throw new ValidationException(pool.displayName() + " takes no target");
        }
        if (REGAIN.equals(option)) {
            if (!(pool.metadata() instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("regain")))) {
                throw new ValidationException(pool.displayName() + " is regained only by resting");
            }
            if (pool.unlimited() || pool.currentUses() >= pool.maxUses()) {
                throw new ConflictException(pool.displayName() + " has no use to regain");
            }
            return new FeatureEffects.Plan(NOTE, REGAIN, ActionCost.FREE, 0, user, " after a turn on which it moved 0 "
                    + "feet");
        }
        requireNoOption(pool, option);
        String note = text(pool, "note");
        return new FeatureEffects.Plan(NOTE, null, poolCost, 1, user, note != null ? ": " + fill(note, user) : "");
    }

    // ── What the features do ────────────────────────────────────

    // A 1-minute entry on the user, counted at the start of its turns
    private static ConditionEntry minuteEntry(Encounter encounter, EncounterParticipant user, String name,
                                              String source) {
        return new ConditionEntry(UUID.randomUUID(), name, RacialFeatures.MINUTE_ROUNDS, encounter.getRoundNumber(),
                source, user.getId(), null, null, EndRule.afterRounds(user.getId()), null);
    }

    // The temporary hit points the pool's tempHp formula gives, rolling any dice it has, as Beasthide's 1d6
    private void gainTempHp(Encounter encounter, EncounterParticipant user, FeatureEffects.Plan plan, String source) {
        ResourcePoolEntry pool = pool(user, plan);
        String formula = pool != null ? text(pool, "tempHp") : null;
        if (formula == null) {
            return;
        }
        int amount = Math.max(1, Expression.evaluate(formula, context(user), rng));
        String name = pool.displayName() != null ? pool.displayName() : source;
        int held = user.getHpTemp() != null ? user.getHpTemp() : 0;
        if (amount <= held) {
            effectApplier.summary(encounter, use(user, "uses " + name, name), user.getDisplayName() + " keeps its "
                    + held + " temporary hit points rather than " + amount + " (" + name + ")");
            return;
        }
        effectApplier.gainTempHp(encounter, user, user, amount, name);
    }

    // Frightened until the end of the user's next turn on a failed save; Daunting Roar can't frighten a deafened
    // creature, which can't hear it (MOT p.21)
    private void frighten(Encounter encounter, EncounterParticipant user, List<EncounterParticipant> picked,
                          String feature, Ability ability, int dc, boolean needsHearing) {
        if (picked.isEmpty()) {
            return;
        }
        boolean ownTurn = EncounterTurns.isCurrentTurn(encounter, user.getId());
        Effect effect = new Effect(feature, new Effect.Save(ability, dc, false), List.of(), null, List.of(FRIGHTENED),
                false, null, Map.of(FRIGHTENED, new Effect.ConditionEnd(ownTurn ? 1 : null, null, null, true)));
        EffectApplier.Use use = use(user, "uses " + feature, feature);
        List<Resolver.Target> rolled = new ArrayList<>();
        for (EncounterParticipant target : picked) {
            if (needsHearing && CreatureStats.conditionNames(target).stream().anyMatch(DEAFENED::equalsIgnoreCase)) {
                effectApplier.summary(encounter, use, target.getDisplayName() + " is deafened, so " + feature
                        + " doesn't affect it.");
            } else {
                rolled.add(stats.target(target, effect));
            }
        }
        if (!rolled.isEmpty()) {
            effectApplier.apply(encounter, use, effect, resolver.resolve(effect, rolled));
        }
    }

    // A regainable pool's use given back, which the use-pool command logged
    private static void regain(EncounterParticipant user, FeatureEffects.Plan plan) {
        ResourcePoolEntry pool = pool(user, plan);
        if (pool == null) {
            return;
        }
        user.setResourcePoolsCurrent(new ArrayList<>(user.getResourcePoolsCurrent().stream()
                .map(p -> p != null && p.poolId().equals(pool.poolId())
                        ? p.withCurrentUses(Math.min(p.maxUses(), p.currentUses() + 1)) : p)
                .toList()));
    }

    // The user's pool the plan is for, by its effect: the first such pool it has
    private static ResourcePoolEntry pool(EncounterParticipant user, FeatureEffects.Plan plan) {
        List<ResourcePoolEntry> pools = user.getResourcePoolsCurrent() != null ? user.getResourcePoolsCurrent()
                : List.of();
        return pools.stream().filter(p -> p != null && plan.effect().equals(FeatureEffects.effectOf(p)))
                .findFirst().orElse(null);
    }

    private static String transformation(String effect, int level) {
        return switch (effect) {
            case RADIANT_SOUL -> "with a flying speed of 30 feet; once on each of its turns one attack or spell deals "
                    + level + " extra radiant damage (add it by hand)";
            case RADIANT_CONSUMPTION -> "shedding bright light 10 feet and dim light 10 more; at the end of each of its "
                    + "turns it and each creature within 10 feet take " + (level + 1) / 2 + " radiant damage (the DM "
                    + "applies it), and once on each of its turns one attack or spell deals " + level + " extra radiant "
                    + "damage (add it by hand)";
            default -> "once on each of its turns one attack or spell deals " + level + " extra necrotic damage (add it "
                    + "by hand)";
        };
    }

    // ── Helpers ──────────────────────────────────────────────────

    private static EffectApplier.Use use(EncounterParticipant user, String action, String source) {
        return new EffectApplier.Use(user, user.getDisplayName(), CombatActionType.POOL_USE, action, "at", source,
                null);
    }

    // Other creatures, named once each, alive; at least one when required
    private static List<EncounterParticipant> others(Encounter encounter, EncounterParticipant user,
                                                     List<UUID> targetIds, UUID userId, ResourcePoolEntry pool,
                                                     boolean required) {
        if (targetIds.isEmpty()) {
            if (required) {
                throw new ValidationException(pool.displayName() + " needs the creatures it affects");
            }
            return List.of();
        }
        if (targetIds.size() > MOST_TARGETS || new HashSet<>(targetIds).size() != targetIds.size()) {
            throw new ValidationException(pool.displayName() + " names each creature once, " + MOST_TARGETS
                    + " at most");
        }
        List<EncounterParticipant> targets = CombatAccess.targets(encounter, targetIds, userId);
        if (targets.contains(user)) {
            throw new ValidationException(pool.displayName() + " affects other creatures, not "
                    + user.getDisplayName());
        }
        CombatAccess.requireNotDead(targets);
        return targets;
    }

    private static void requireNoOption(ResourcePoolEntry pool, String option) {
        if (option != null) {
            throw new ValidationException(pool.displayName() + " takes no option");
        }
    }

    private static void requireNoOptionOrTargets(ResourcePoolEntry pool, String option, List<UUID> targetIds) {
        requireNoOption(pool, option);
        if (!targetIds.isEmpty()) {
            throw new ValidationException(pool.displayName() + " is used only on its user");
        }
    }

    private static String text(ResourcePoolEntry pool, String key) {
        return pool.metadata() instanceof Map<?, ?> map && map.get(key) instanceof String value && !value.isBlank()
                ? value : null;
    }

    // The note with the user's level and proficiency bonus filled in
    private static String fill(String note, EncounterParticipant user) {
        return note.replace("{level}", String.valueOf(level(user)))
                .replace("{proficiencyBonus}", String.valueOf(proficiencyBonus(user.getCharacter())));
    }

    // 8 + the ability's modifier + the proficiency bonus (PHB p.34, MOT p.21, VGM p.105)
    private int saveDc(EncounterParticipant user, Ability ability) {
        return RacialFeatures.saveDc(proficiencyBonus(user.getCharacter()), stats.abilityScore(user, ability));
    }

    /** The variables a racial formula reads, as a sheet's pool formulas do: the user's level, proficiency and scores. */
    Map<String, Integer> context(EncounterParticipant user) {
        Map<String, Integer> context = new LinkedHashMap<>();
        PlayerCharacter character = user.getCharacter();
        context.put("proficiencyBonus", proficiencyBonus(character));
        context.put("totalLevel", level(user));
        if (character != null && character.getMulticlassEntries() != null) {
            for (ClassEntry entry : character.getMulticlassEntries()) {
                if (entry != null && entry.className() != null) {
                    context.merge(entry.className().toLowerCase(Locale.ROOT) + "Level", entry.level(), Integer::sum);
                }
            }
        }
        for (Ability ability : Ability.values()) {
            context.put(ability.fullName() + "Modifier", Ability.modifier(stats.abilityScore(user, ability)));
        }
        return context;
    }

    private static int level(EncounterParticipant user) {
        PlayerCharacter character = user.getCharacter();
        return character != null && character.getLevel() != null ? Math.max(1, character.getLevel()) : 1;
    }

    private static int proficiencyBonus(PlayerCharacter character) {
        if (character != null && character.getProficiencyBonus() != null) {
            return character.getProficiencyBonus();
        }
        return ProficiencyBonus.forLevel(character != null && character.getLevel() != null ? character.getLevel() : 1);
    }

    private static String signed(int modifier) {
        return modifier < 0 ? "- " + -modifier : "+ " + modifier;
    }
}
