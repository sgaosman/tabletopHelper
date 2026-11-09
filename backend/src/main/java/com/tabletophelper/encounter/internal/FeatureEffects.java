package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.UsePoolRequest;
import com.tabletophelper.encounter.state.TurnEconomy;
import com.tabletophelper.reference.Item;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.Rng;
import com.tabletophelper.rules.combat.ActionCost;
import com.tabletophelper.rules.combat.ActionEconomy;
import com.tabletophelper.rules.combat.ClassFeatures;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * What a class feature does when its pool is used at the table (M17), for a pool whose metadata names an
 * {@code effect}; any other pool just spends its uses (D174). The use-pool command stays the one command that spends a
 * pool: it asks {@link #plan} before anything is charged, so every refusal comes first, and {@link #apply} once the
 * pool is spent and the turn charged. Every change to conditions and hit points goes through {@link EffectApplier}.
 * <ul>
 *   <li>{@value #RAGE} (Barbarian 1, PHB p.48): a bonus action starts a rage for 1 minute, ending the barbarian's
 *       concentration; refused while it already rages or wears heavy armour, where a rage gives nothing. The option
 *       "end" ends it on the barbarian's turn as a bonus action, spending nothing.</li>
 *   <li>{@value #SECOND_WIND} (Fighter 1, PHB p.72): a bonus action that heals the fighter 1d10 + its fighter
 *       level.</li>
 *   <li>{@value #LAY_ON_HANDS} (Paladin 1, PHB p.84): an action touching one creature, the paladin itself when no
 *       target is named. "heal" (the default) restores as many hit points as the uses spent; "cure" spends 5 for each
 *       disease or poison cured, which is logged for the DM to remove. It has no effect on undead or constructs.</li>
 *   <li>{@value #KI} (Monk 2, PHB p.78): "patient-defense" takes the Dodge action, and "step-of-the-wind-dash" or
 *       "step-of-the-wind-disengage" the Dash or Disengage action with the jump distance doubled, each as a bonus
 *       action for 1 ki point. Without an option, ki is spent as before, for what the app doesn't model yet, such as
 *       Flurry of Blows.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class FeatureEffects {

    public static final String RAGE = "rage";
    public static final String SECOND_WIND = "second-wind";
    public static final String LAY_ON_HANDS = "lay-on-hands";
    public static final String KI = "ki";

    public static final String END = "end";
    public static final String HEAL = "heal";
    public static final String CURE = "cure";
    public static final String PATIENT_DEFENSE = "patient-defense";
    public static final String STEP_DASH = "step-of-the-wind-dash";
    public static final String STEP_DISENGAGE = "step-of-the-wind-disengage";

    private static final List<String> KI_OPTIONS = List.of(PATIENT_DEFENSE, STEP_DASH, STEP_DISENGAGE);

    private final EffectApplier effectApplier;
    private final CreatureStats stats;
    private final Rng rng;

    /**
     * A use, checked and ready.
     *
     * @param effect the pool's effect, or null for a pool that only spends
     * @param option the option chosen, or null
     * @param cost   what the use costs from the turn: the pool's spendActionType unless the option says otherwise
     * @param spend  how many uses it spends from the pool: 0 to end a rage
     * @param target the creature it touches: the user, or Lay on Hands' target
     * @param note   what the use does, for its log entry, such as ": takes the Dodge action until ..."; empty for none
     */
    public record Plan(String effect, String option, ActionCost cost, int spend, EncounterParticipant target,
                       String note) {
    }

    /** The effect a pool's metadata names, such as "rage", or null. */
    public static String effectOf(ResourcePoolEntry pool) {
        return pool.metadata() instanceof Map<?, ?> map && map.get("effect") instanceof String effect
                ? effect.trim().toLowerCase(Locale.ROOT) : null;
    }

    /**
     * Checks the use before anything is charged or spent.
     *
     * @param poolCost what the pool's spendActionType says a use costs
     * @throws ValidationException for an option or target the feature doesn't take, more than one use where only one
     *                             is allowed, Lay on Hands on an undead or construct, or a cure not in fives
     * @throws ConflictException   for a rage started while raging or in heavy armour, or ended while not raging, and
     *                             a dead target
     */
    public Plan plan(Encounter encounter, EncounterParticipant user, ResourcePoolEntry pool, UsePoolRequest request,
                     UUID userId, ActionCost poolCost) {
        String effect = effectOf(pool);
        String option = request.option() != null && !request.option().isBlank()
                ? request.option().trim().toLowerCase(Locale.ROOT) : null;
        boolean targeted = request.targetIds() != null && !request.targetIds().isEmpty();
        int uses = request.uses() != null ? request.uses() : 1;
        if (effect == null) {
            if (option != null || targeted) {
                throw new ValidationException(pool.displayName() + " takes no option or target");
            }
            return new Plan(null, null, poolCost, uses, user, "");
        }
        if (targeted && !LAY_ON_HANDS.equals(effect)) {
            throw new ValidationException(pool.displayName() + " is used only on " + user.getDisplayName());
        }
        return switch (effect) {
            case RAGE -> rage(user, option, uses, poolCost);
            case SECOND_WIND -> {
                requireNoOption(pool, option);
                requireOneUse(pool, uses);
                if (classLevel(user.getCharacter(), "Fighter") < 1) {
                    throw new ConflictException(user.getDisplayName() + " has no Fighter levels for Second Wind");
                }
                yield new Plan(effect, null, poolCost, 1, user, "");
            }
            case LAY_ON_HANDS -> layOnHands(encounter, user, pool, request, option, uses, userId, poolCost);
            case KI -> ki(user, pool, option, request.uses(), poolCost);
            default -> throw new IllegalStateException("The " + pool.poolId() + " pool has an unknown effect " + effect);
        };
    }

    /**
     * Does what the feature does, once the turn is charged and the pool spent, writing the entries for what changes,
     * such as Second Wind's healing.
     */
    public void apply(Encounter encounter, EncounterParticipant user, Plan plan) {
        if (plan.effect() == null) {
            return;
        }
        switch (plan.effect()) {
            case RAGE -> {
                if (END.equals(plan.option())) {
                    effectApplier.endRage(encounter, user, "ended early");
                } else {
                    effectApplier.startRage(encounter, user);
                }
            }
            case SECOND_WIND -> {
                Dice dice = ClassFeatures.secondWind(Math.min(20, classLevel(user.getCharacter(), "Fighter")));
                int rolled = dice.rollDice(rng);
                int amount = dice.total(rolled);
                effectApplier.heal(encounter, user, user, amount,
                        "Second Wind: 1d10(" + rolled + ") + " + dice.modifier() + " = " + amount);
            }
            case LAY_ON_HANDS -> {
                if (!CURE.equals(plan.option())) {
                    effectApplier.heal(encounter, user, plan.target(), plan.spend(), "Lay on Hands");
                }
            }
            case KI -> {
                if (PATIENT_DEFENSE.equals(plan.option())) {
                    effectApplier.dodge(encounter, user, "Patient Defense");
                } else if (STEP_DASH.equals(plan.option())) {
                    user.setTurnEconomy(TurnEconomy.of(ActionEconomy.dash(economy(user).budget())));
                } else if (STEP_DISENGAGE.equals(plan.option())) {
                    user.setTurnEconomy(TurnEconomy.of(ActionEconomy.disengage(economy(user).budget())));
                }
            }
            default -> throw new IllegalStateException("Unknown effect " + plan.effect());
        }
    }

    // ── The features ─────────────────────────────────────────────

    private Plan rage(EncounterParticipant barbarian, String option, int uses, ActionCost poolCost) {
        boolean raging = ClassFeatures.raging(CreatureStats.conditionNames(barbarian));
        if (END.equals(option)) {
            if (!raging) {
                throw new ConflictException(barbarian.getDisplayName() + " isn't raging");
            }
            // Ending a rage early is a bonus action on the barbarian's turn (PHB p.48), and spends no rage
            return new Plan(RAGE, END, ActionCost.BONUS_ACTION, 0, barbarian, "");
        }
        if (option != null) {
            throw new ValidationException("Rage takes the option \"" + END + "\" or none");
        }
        if (uses != 1) {
            throw new ValidationException("Rage can be used only once at a time");
        }
        if (raging) {
            throw new ConflictException(barbarian.getDisplayName() + " is already raging");
        }
        Item armour = stats.armour(barbarian).armour();
        if (armour != null && armour.getArmourCategory() == Item.ArmourCategory.HEAVY) {
            throw new ConflictException("Rage gives no benefits in heavy armour, and " + barbarian.getDisplayName()
                    + " wears " + armour.getName());
        }
        int level = classLevel(barbarian.getCharacter(), "Barbarian");
        String damage = level >= 1 ? "+" + ClassFeatures.rageDamage(Math.min(20, level)) + " damage on melee weapon "
                + "attacks using Strength, " : "";
        return new Plan(RAGE, null, poolCost, 1, barbarian, ": rages for 1 minute, with " + damage
                + "resistance to bludgeoning, piercing and slashing damage, and advantage on Strength checks and "
                + "saving throws, but can't cast spells or concentrate on them");
    }

    private Plan layOnHands(Encounter encounter, EncounterParticipant paladin, ResourcePoolEntry pool,
                            UsePoolRequest request, String option, int uses, UUID userId, ActionCost poolCost) {
        if (option != null && !HEAL.equals(option) && !CURE.equals(option)) {
            throw new ValidationException("Lay on Hands takes the option \"" + HEAL + "\" or \"" + CURE + "\"");
        }
        EncounterParticipant target = request.targetIds() != null && !request.targetIds().isEmpty()
                ? CombatAccess.target(encounter, request.targetIds().getFirst(), userId) : paladin;
        CombatAccess.requireNotDead(List.of(target));
        String type = target.getMonster() != null ? target.getMonster().getType() : null;
        ClassFeatures.layOnHandsRefusal(type).ifPresent(reason -> {
            throw new ValidationException(reason);
        });
        String on = target == paladin ? "" : " on " + target.getDisplayName();
        if (CURE.equals(option)) {
            if (uses % ClassFeatures.LAY_ON_HANDS_CURE_COST != 0) {
                throw new ValidationException("Lay on Hands spends " + ClassFeatures.LAY_ON_HANDS_CURE_COST
                        + " points for each disease or poison it cures");
            }
            int cured = uses / ClassFeatures.LAY_ON_HANDS_CURE_COST;
            return new Plan(LAY_ON_HANDS, CURE, poolCost, uses, target, on + " to cure " + cured + " disease"
                    + (cured == 1 ? " or poison" : "s or poisons") + "; the DM removes what it cures");
        }
        return new Plan(LAY_ON_HANDS, HEAL, poolCost, uses, target, on + " to restore " + uses + " HP");
    }

    private Plan ki(EncounterParticipant monk, ResourcePoolEntry pool, String option, Integer uses,
                    ActionCost poolCost) {
        if (option == null) {
            return new Plan(KI, null, poolCost, uses != null ? uses : 1, monk, "");
        }
        if (!KI_OPTIONS.contains(option)) {
            throw new ValidationException(pool.displayName() + " takes the option \"" + String.join("\", \"",
                    KI_OPTIONS) + "\" or none");
        }
        if (uses != null && uses != 1) {
            throw new ValidationException(optionName(option) + " costs 1 ki point");
        }
        String note = switch (option) {
            case PATIENT_DEFENSE -> " for Patient Defense: takes the Dodge action until the start of its next turn, so "
                    + "attacks against it have disadvantage and it has advantage on Dexterity saves";
            case STEP_DASH -> " for Step of the Wind: takes the Dash action, gaining extra movement equal to its speed "
                    + "this turn, and its jump distance is doubled";
            default -> " for Step of the Wind: takes the Disengage action, so its movement doesn't provoke "
                    + "opportunity attacks this turn, and its jump distance is doubled";
        };
        // Patient Defense and Step of the Wind are bonus actions for 1 ki point (PHB p.78)
        return new Plan(KI, option, ActionCost.BONUS_ACTION, 1, monk, note);
    }

    private static String optionName(String option) {
        return PATIENT_DEFENSE.equals(option) ? "Patient Defense" : "Step of the Wind";
    }

    private static void requireNoOption(ResourcePoolEntry pool, String option) {
        if (option != null) {
            throw new ValidationException(pool.displayName() + " takes no option");
        }
    }

    private static void requireOneUse(ResourcePoolEntry pool, int uses) {
        if (uses != 1) {
            throw new ValidationException(pool.displayName() + " can be used only once at a time");
        }
    }

    private static TurnEconomy economy(EncounterParticipant participant) {
        return participant.getTurnEconomy() != null ? participant.getTurnEconomy() : TurnEconomy.fresh();
    }

    // ── What the sheet says ──────────────────────────────────────

    /**
     * The character's level in the class, from its class entries, or its single class and level when it has none; 0
     * for a creature without a sheet or without the class.
     */
    public static int classLevel(PlayerCharacter character, String className) {
        if (character == null) {
            return 0;
        }
        List<ClassEntry> entries = character.getMulticlassEntries();
        if (entries != null && !entries.isEmpty()) {
            return entries.stream()
                    .filter(entry -> entry != null && entry.className() != null
                            && entry.className().trim().equalsIgnoreCase(className))
                    .mapToInt(ClassEntry::level)
                    .sum();
        }
        boolean single = character.getCharacterClass() != null
                && character.getCharacterClass().trim().equalsIgnoreCase(className);
        return single && character.getLevel() != null ? character.getLevel() : 0;
    }

    /** Whether the character's features include one of this name, in any case. */
    public static boolean hasFeature(PlayerCharacter character, String featureName) {
        if (character == null || character.getFeatures() == null) {
            return false;
        }
        return character.getFeatures().stream()
                .filter(Objects::nonNull)
                .map(Feature::name)
                .anyMatch(name -> name != null && name.trim().equalsIgnoreCase(featureName));
    }
}
