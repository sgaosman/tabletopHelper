package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.UsePoolRequest;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.EndRule;
import com.tabletophelper.encounter.state.HeldDie;
import com.tabletophelper.encounter.state.TurnEconomy;
import com.tabletophelper.reference.Item;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.Rng;
import com.tabletophelper.rules.combat.ActionCost;
import com.tabletophelper.rules.combat.ActionEconomy;
import com.tabletophelper.rules.combat.ClassFeatures;
import com.tabletophelper.rules.combat.KenseiFeatures;
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
 *       Flurry of Blows. A Way of the Kensei monk also has "kenseis-shot" (Monk 3, XGE p.34), a bonus action spending
 *       no ki that adds 1d4 to its ranged kensei weapon attacks' hits for the rest of the turn, and
 *       "sharpen-the-blade" (Monk 11, XGE p.35), a bonus action spending 1 to 3 ki points to give one kensei weapon that
 *       bonus to attack and damage rolls for 1 minute, until it is used again.</li>
 *   <li>{@value ChannelDivinityEffects#EFFECT} (Cleric 2, PHB p.58–59; Paladin 3, PHB p.85): the option names what
 *       the use does, as {@link ChannelDivinityEffects} carries it out.</li>
 *   <li>{@value #BARDIC_INSPIRATION} (Bard 1, PHB p.53–54): a bonus action giving one other creature a Bardic
 *       Inspiration die, d6 to d12 by the bard's level, which it holds for 10 minutes and adds to one attack roll, saving
 *       throw or ability check. One creature holds one die at a time.</li>
 *   <li>{@value WildShapeEffects#EFFECT} (Druid 2, PHB p.66–67): the request's beastId names the beast the druid
 *       becomes, as {@link WildShapeEffects} carries it out; "end" reverts early as a bonus action.</li>
 *   <li>{@value #ARCANE_RECOVERY} (Wizard 1, PHB p.115): used only when finishing a short rest, on the character
 *       sheet, so the table refuses it.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class FeatureEffects {

    public static final String RAGE = "rage";
    public static final String SECOND_WIND = "second-wind";
    public static final String LAY_ON_HANDS = "lay-on-hands";
    public static final String KI = "ki";
    public static final String BARDIC_INSPIRATION = "bardic-inspiration";
    public static final String ARCANE_RECOVERY = "arcane-recovery";
    /**
     * A spell's things to use, as Goodberry's berries (PHB p.246, D199): an action eats one, restoring the hit points the
     * pool's metadata names ({@code heals}) to the eater, or to the one creature named, fed it (rules call).
     */
    public static final String SPELL_HEALING = "spell-healing";

    public static final String END = "end";
    public static final String HEAL = "heal";
    public static final String CURE = "cure";
    public static final String PATIENT_DEFENSE = "patient-defense";
    public static final String STEP_DASH = "step-of-the-wind-dash";
    public static final String STEP_DISENGAGE = "step-of-the-wind-disengage";
    public static final String KENSEIS_SHOT = "kenseis-shot";
    public static final String SHARPEN_THE_BLADE = "sharpen-the-blade";

    private static final List<String> KI_OPTIONS = List.of(PATIENT_DEFENSE, STEP_DASH, STEP_DISENGAGE, KENSEIS_SHOT,
            SHARPEN_THE_BLADE);

    private static final String BARD = "Bard";

    private final EffectApplier effectApplier;
    private final CreatureStats stats;
    private final Rng rng;
    private final ChannelDivinityEffects channelDivinity;
    private final WildShapeEffects wildShape;
    private final CharacterWeapons characterWeapons;

    /**
     * A use, checked and ready.
     *
     * @param effect the pool's effect, or null for a pool that only spends
     * @param option the option chosen, or null
     * @param cost   what the use costs from the turn: the pool's spendActionType unless the option says otherwise
     * @param spend  how many uses it spends from the pool: 0 to end a rage
     * @param target  the creature its log entry is about: the user, or the one creature it touches
     * @param note    what the use does, for its log entry, such as ": takes the Dodge action until ..."; empty for none
     * @param targets every creature it affects, for a feature that affects several, such as Turn Undead
     * @param amounts what each of targets gets, in the same order, for a feature that splits something among them, as
     *                Preserve Life splits its healing; empty otherwise
     * @param weapon  the weapon the feature imbues, as Sacred Weapon's (PHB p.86), as the attack options name it; null
     *                otherwise
     */
    public record Plan(String effect, String option, ActionCost cost, int spend, EncounterParticipant target,
                       String note, List<EncounterParticipant> targets, List<Integer> amounts, String weapon) {

        public Plan {
            targets = targets != null ? List.copyOf(targets) : List.of();
            amounts = amounts != null ? List.copyOf(amounts) : List.of();
        }

        /** A use that imbues no weapon. */
        public Plan(String effect, String option, ActionCost cost, int spend, EncounterParticipant target,
                    String note, List<EncounterParticipant> targets, List<Integer> amounts) {
            this(effect, option, cost, spend, target, note, targets, amounts, null);
        }

        /** A use that affects only its one target. */
        public Plan(String effect, String option, ActionCost cost, int spend, EncounterParticipant target,
                    String note) {
            this(effect, option, cost, spend, target, note, List.of(), List.of());
        }
    }

    /** The effect a pool's metadata names, such as "rage", or null. */
    public static String effectOf(ResourcePoolEntry pool) {
        if (pool.metadata() instanceof Map<?, ?> map && map.get("effect") instanceof String effect) {
            return effect.trim().toLowerCase(Locale.ROOT);
        }
        // A Wild Shape pool copied before its definition named the effect is still Wild Shape
        return WildShapeEffects.POOL_ID.equals(pool.poolId()) ? WildShapeEffects.EFFECT : null;
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
            if (option != null || targeted || request.beastId() != null || request.weapon() != null) {
                throw new ValidationException(pool.displayName() + " takes no option, target, beast or weapon");
            }
            return new Plan(null, null, poolCost, uses, user, "");
        }
        if (request.beastId() != null && !WildShapeEffects.EFFECT.equals(effect)) {
            throw new ValidationException(pool.displayName() + " takes no beast");
        }
        boolean sharpening = KI.equals(effect) && SHARPEN_THE_BLADE.equals(option);
        if (request.weapon() != null && !ChannelDivinityEffects.EFFECT.equals(effect) && !sharpening) {
            throw new ValidationException(pool.displayName() + " takes no weapon");
        }
        if (ChannelDivinityEffects.EFFECT.equals(effect)) {
            return channelDivinity.plan(encounter, user, pool, request, option, uses, userId, poolCost);
        }
        if (WildShapeEffects.EFFECT.equals(effect)) {
            return wildShape.plan(user, pool, request, option, uses);
        }
        if (request.amounts() != null) {
            throw new ValidationException(pool.displayName() + " takes no amounts");
        }
        if (SPELL_HEALING.equals(effect)) {
            return spellHealing(encounter, user, pool, request, option, uses, userId, poolCost);
        }
        if (targeted && !LAY_ON_HANDS.equals(effect) && !BARDIC_INSPIRATION.equals(effect)) {
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
            case KI -> ki(user, pool, option, request.uses(), request.weapon(), poolCost);
            case BARDIC_INSPIRATION -> bardicInspiration(encounter, user, pool, request, option, uses, userId,
                    poolCost);
            case ARCANE_RECOVERY -> throw new ConflictException(pool.displayName() + " is used when finishing a short "
                    + "rest, from the character sheet");
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
            case SPELL_HEALING -> {
                if (!plan.amounts().isEmpty() && plan.amounts().getFirst() > 0) {
                    effectApplier.heal(encounter, user, plan.target(), plan.amounts().getFirst(), plan.option());
                }
            }
            case ChannelDivinityEffects.EFFECT -> channelDivinity.apply(encounter, user, plan);
            case WildShapeEffects.EFFECT -> wildShape.apply(encounter, user, plan);
            case BARDIC_INSPIRATION -> {
                int sides = ClassFeatures.bardicInspirationDie(Math.max(1, Math.min(20,
                        classLevel(user.getCharacter(), BARD))));
                effectApplier.addFeatureEntry(plan.target(), new ConditionEntry(UUID.randomUUID(),
                        ClassFeatures.INSPIRED, ClassFeatures.INSPIRATION_ROUNDS, encounter.getRoundNumber(),
                        "Bardic Inspiration", user.getId(), null, null, EndRule.afterRounds(user.getId()), null,
                        new HeldDie(sides, false)));
            }
            case KI -> {
                if (KENSEIS_SHOT.equals(plan.option())) {
                    // For the rest of the turn: it ends at the end of the monk's turn (XGE p.34)
                    effectApplier.addFeatureEntry(user, new ConditionEntry(UUID.randomUUID(),
                            KenseiFeatures.KENSEIS_SHOT, null, encounter.getRoundNumber(), "Kensei's Shot", user.getId(),
                            null, null, EndRule.endOfTurn(user.getId()), null));
                } else if (SHARPEN_THE_BLADE.equals(plan.option())) {
                    // 1 minute, or until the feature is used again, which replaces the entry (XGE p.35)
                    effectApplier.addFeatureEntry(user, new ConditionEntry(UUID.randomUUID(),
                            KenseiFeatures.SHARPEN_THE_BLADE, KenseiFeatures.SHARPEN_THE_BLADE_ROUNDS,
                            encounter.getRoundNumber(), "Sharpen the Blade", user.getId(), null, null,
                            EndRule.afterRounds(user.getId()), null, null, null, plan.weapon(), null, plan.spend()));
                } else if (PATIENT_DEFENSE.equals(plan.option())) {
                    effectApplier.dodge(encounter, user, "Patient Defense");
                } else if (STEP_DASH.equals(plan.option())) {
                    user.setTurnEconomy(economy(user).withBudget(ActionEconomy.dash(economy(user).budget())));
                } else if (STEP_DISENGAGE.equals(plan.option())) {
                    user.setTurnEconomy(economy(user).withBudget(ActionEconomy.disengage(economy(user).budget())));
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

    private Plan bardicInspiration(Encounter encounter, EncounterParticipant bard, ResourcePoolEntry pool,
                                   UsePoolRequest request, String option, int uses, UUID userId, ActionCost poolCost) {
        requireNoOption(pool, option);
        requireOneUse(pool, uses);
        int level = classLevel(bard.getCharacter(), BARD);
        if (level < 1) {
            throw new ConflictException(bard.getDisplayName() + " has no Bard levels for Bardic Inspiration");
        }
        if (request.targetIds() == null || request.targetIds().size() != 1) {
            throw new ValidationException("Bardic Inspiration goes to one creature: name it");
        }
        EncounterParticipant target = CombatAccess.target(encounter, request.targetIds().getFirst(), userId);
        if (target == bard) {
            throw new ValidationException("Bardic Inspiration goes to a creature other than the bard (PHB p.53)");
        }
        CombatAccess.requireNotDead(List.of(target));
        if (EffectApplier.inspiration(target).isPresent()) {
            throw new ConflictException(target.getDisplayName() + " already holds a Bardic Inspiration die");
        }
        int sides = ClassFeatures.bardicInspirationDie(Math.min(20, level));
        return new Plan(BARDIC_INSPIRATION, null, poolCost, 1, target, " on " + target.getDisplayName() + ": a d"
                + sides + " to add to one ability check, attack roll or saving throw within 10 minutes");
    }

    // One of a spell's things eaten, as a goodberry (PHB p.246, D199): its hit points to the eater, or to the one creature
    // it is fed to; the option carries the pool's name for the healing's log entry
    private Plan spellHealing(Encounter encounter, EncounterParticipant user, ResourcePoolEntry pool,
                              UsePoolRequest request, String option, int uses, UUID userId, ActionCost poolCost) {
        requireNoOption(pool, option);
        requireOneUse(pool, uses);
        if (request.targetIds() != null && request.targetIds().size() > 1) {
            throw new ValidationException("One of " + pool.displayName() + " is eaten by one creature");
        }
        EncounterParticipant target = request.targetIds() != null && !request.targetIds().isEmpty()
                ? CombatAccess.target(encounter, request.targetIds().getFirst(), userId) : user;
        CombatAccess.requireNotDead(List.of(target));
        int heals = pool.metadata() instanceof Map<?, ?> map && map.get("heals") instanceof Number number
                ? number.intValue() : 0;
        String on = target == user ? " (eats one)" : " (feeds one to " + target.getDisplayName() + ")";
        return new Plan(SPELL_HEALING, pool.displayName(), poolCost, 1, target, on, List.of(target), List.of(heals));
    }

    private Plan layOnHands(Encounter encounter, EncounterParticipant paladin, ResourcePoolEntry pool,
                            UsePoolRequest request, String option, int uses, UUID userId, ActionCost poolCost) {
        if (option != null && !HEAL.equals(option) && !CURE.equals(option)) {
            throw new ValidationException("Lay on Hands takes the option \"" + HEAL + "\" or \"" + CURE + "\"");
        }
        if (request.targetIds() != null && request.targetIds().size() > 1) {
            throw new ValidationException("Lay on Hands touches one creature at a time");
        }
        EncounterParticipant target = request.targetIds() != null && !request.targetIds().isEmpty()
                ? CombatAccess.target(encounter, request.targetIds().getFirst(), userId) : paladin;
        CombatAccess.requireNotDead(List.of(target));
        String type = CreatureStats.creatureType(target);
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

    private Plan ki(EncounterParticipant monk, ResourcePoolEntry pool, String option, Integer uses, String weapon,
                    ActionCost poolCost) {
        if (option == null) {
            return new Plan(KI, null, poolCost, uses != null ? uses : 1, monk, "");
        }
        if (!KI_OPTIONS.contains(option)) {
            throw new ValidationException(pool.displayName() + " takes the option \"" + String.join("\", \"",
                    KI_OPTIONS) + "\" or none");
        }
        if (KENSEIS_SHOT.equals(option)) {
            return kenseisShot(monk, uses);
        }
        if (SHARPEN_THE_BLADE.equals(option)) {
            return sharpenTheBlade(monk, uses, weapon);
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

    /**
     * Kensei's Shot (Way of the Kensei 3, XGE p.34): a bonus action spending no ki, after which a ranged attack with a
     * kensei weapon deals an extra 1d4 of the weapon's type on a hit, until the end of the turn.
     */
    private static Plan kenseisShot(EncounterParticipant monk, Integer uses) {
        requireKensei(monk, KenseiFeatures.KENSEIS_SHOT_LEVEL, "Kensei's Shot");
        if (uses != null && uses != 1) {
            throw new ValidationException("Kensei's Shot spends no ki points");
        }
        return new Plan(KI, KENSEIS_SHOT, ActionCost.BONUS_ACTION, 0, monk, " for Kensei's Shot, spending no ki: its "
                + "ranged attacks with a kensei weapon deal an extra " + KenseiFeatures.KENSEIS_SHOT_DAMAGE
                + " damage of the weapon's type on a hit until the end of the turn");
    }

    /**
     * Sharpen the Blade (Way of the Kensei 11, XGE p.35): a bonus action spending 1 to 3 ki points to give one kensei
     * weapon the monk touches a bonus to attack and damage rolls equal to the points, for 1 minute or until it is used
     * again. A magic weapon that already has a bonus gains nothing; magic bonuses are added by hand (D164), so the log
     * says so for the DM to judge. Without a weapon named, the monk's only kensei weapon it carries.
     *
     * @throws ValidationException for another number of points, or a weapon that isn't one of its kensei weapons it
     *                             carries, or none named when it carries several
     */
    private Plan sharpenTheBlade(EncounterParticipant monk, Integer uses, String weapon) {
        requireKensei(monk, KenseiFeatures.SHARPEN_THE_BLADE_LEVEL, "Sharpen the Blade");
        int ki = uses != null ? uses : 1;
        String refusal = KenseiFeatures.sharpenTheBladeRefusal(ki);
        if (refusal != null) {
            throw new ValidationException(refusal);
        }
        PlayerCharacter character = monk.getCharacter();
        var attacker = characterWeapons.attacker(character, false);
        List<String> carried = characterWeapons.of(character).stream()
                .map(CharacterWeapons.Held::weapon)
                .filter(attacker::kenseiWeapon)
                .map(com.tabletophelper.rules.combat.WeaponRules.Weapon::name)
                .toList();
        String chosen;
        if (weapon != null && !weapon.isBlank()) {
            chosen = carried.stream().filter(name -> name.equalsIgnoreCase(weapon.trim())).findFirst()
                    .orElseThrow(() -> new ValidationException("Sharpen the Blade sharpens a kensei weapon "
                            + monk.getDisplayName() + " carries, and " + weapon.trim() + " isn't one"
                            + (carried.isEmpty() ? "" : "; choose " + String.join(", ", carried))));
        } else if (carried.size() == 1) {
            chosen = carried.getFirst();
        } else {
            throw new ValidationException(carried.isEmpty()
                    ? monk.getDisplayName() + " carries no kensei weapon to sharpen"
                    : "Name the kensei weapon Sharpen the Blade sharpens: " + String.join(", ", carried));
        }
        return new Plan(KI, SHARPEN_THE_BLADE, ActionCost.BONUS_ACTION, ki, monk, " for Sharpen the Blade: its "
                + chosen + " gains +" + ki + " to attack and damage rolls for 1 minute (no effect on a magic weapon "
                + "that already has a bonus to attack and damage rolls; the DM judges)", List.of(), List.of(), chosen);
    }

    // The monk's Way of the Kensei must reach the level
    private static void requireKensei(EncounterParticipant monk, int level, String feature) {
        int kensei = subclassLevel(monk.getCharacter(), KenseiFeatures.MONK, KenseiFeatures.SUBCLASS);
        if (kensei < level) {
            throw new ValidationException(feature + " needs Monk level " + level + " in the Way of the Kensei, and "
                    + monk.getDisplayName() + (kensei == 0 ? " isn't a Kensei monk" : " is level " + kensei));
        }
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

    /**
     * The character's level in the class when that class has the subclass, from its class entries, or its single class,
     * level and subclass when it has none; 0 otherwise. The subclass data gives a subclass's first level one feature
     * named after the subclass, such as "Life Domain", and the features that level brings, such as Disciple of Life,
     * aren't features of their own, so such a feature is found through the subclass and the class level rather than by
     * its name.
     */
    public static int subclassLevel(PlayerCharacter character, String className, String subclassName) {
        if (character == null) {
            return 0;
        }
        List<ClassEntry> entries = character.getMulticlassEntries();
        if (entries != null && !entries.isEmpty()) {
            return entries.stream()
                    .filter(entry -> entry != null && entry.className() != null && entry.subclassName() != null
                            && entry.className().trim().equalsIgnoreCase(className)
                            && entry.subclassName().trim().equalsIgnoreCase(subclassName))
                    .mapToInt(ClassEntry::level)
                    .sum();
        }
        boolean single = character.getCharacterClass() != null && character.getSubclass() != null
                && character.getCharacterClass().trim().equalsIgnoreCase(className)
                && character.getSubclass().trim().equalsIgnoreCase(subclassName);
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
