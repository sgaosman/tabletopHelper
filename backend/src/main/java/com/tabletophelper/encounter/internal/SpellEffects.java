package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import com.tabletophelper.reference.SpellTemplate;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.combat.ActionCost;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.RollMode;
import com.tabletophelper.rules.combat.SpellTrigger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Reads a spell's effect template into an Effect (D030, D160). A cantrip's dice scale with the caster's level,
 * a spell cast with a higher slot adds its upcast dice, and the template's MOD placeholder becomes the
 * caster's spellcasting modifier as a number. A spell the template leaves to the DM, or whose dice can't be
 * rolled, comes back as a plan without an Effect, with the reason.
 */
@Component
@RequiredArgsConstructor
public class SpellEffects {

    private static final String NO_TEMPLATE = "No effect template available";

    private final SpellRepository spellRepository;
    private final CreatureStats stats;

    /**
     * A spell as cast.
     *
     * @param spellLevel     the spell's level, 0 for a cantrip
     * @param effect         what it does, or null when the DM resolves it by hand
     * @param manualReason   why the DM resolves it, when effect is null
     * @param concentration  whether it needs concentration, which casting starts even when resolved by hand
     * @param durationRounds how many rounds it lasts; null when unknown
     * @param verbal         whether it has a verbal component, which silence prevents
     */
    public record SpellPlan(String spellName, int spellLevel, Effect effect, String manualReason,
                            boolean concentration, Integer durationRounds, boolean verbal) {

        public boolean automated() {
            return effect != null;
        }
    }

    /**
     * The spell cast with a slot of slotLevel, or 0 for none.
     *
     * @param castingAbility the ability the spell is cast with, which gives its spellcasting modifier; null when
     *                       unknown, and then the modifier is worked back from the attack bonus
     * @param attackBonus    the caster's spell attack bonus
     * @param saveDc         the caster's spell save DC
     * @param advantage      true for advantage on a spell attack, false for disadvantage, null for neither
     */
    public SpellPlan forCast(String spellName, int slotLevel, EncounterParticipant caster, String castingAbility,
                             int attackBonus, int saveDc, Boolean advantage) {
        Optional<SpellTemplate> found = template(spellName);
        if (found.isEmpty()) {
            return manual(spellName, 0, NO_TEMPLATE, false, null, false);
        }
        SpellTemplate template = found.get();
        int spellLevel = template.level();
        boolean concentration = Boolean.TRUE.equals(template.concentration());
        Integer durationRounds = template.durationRounds();
        boolean verbal = template.verbal();

        if (Boolean.TRUE.equals(template.requiresManualResolution())) {
            String reason = template.manualResolutionReason() != null ? template.manualResolutionReason()
                    : "Complex spell requires DM adjudication";
            return manual(spellName, spellLevel, reason, concentration, durationRounds, verbal);
        }
        String deliveryMethod = orNone(template.deliveryMethod());
        Effect.Delivery delivery = delivery(deliveryMethod, template.spellAttackType(), template.saveAbility(),
                template.halfOnSave(), attackBonus, saveDc, advantage);
        if (delivery == null) {
            return manual(spellName, spellLevel, "Delivery method '" + deliveryMethod + "' requires DM adjudication",
                    concentration, durationRounds, verbal);
        }

        int modifier = stats.spellcastingModifier(caster, castingAbility, attackBonus);
        // A spell on its caster heals or gives conditions; any damage in its template is for others to take
        Optional<List<Effect.DamagePart>> damage = delivery instanceof Effect.Self
                ? Optional.of(List.of())
                : damage(template.firstDamageEffect(), spellLevel, slotLevel, caster, modifier);
        SpellTemplate.Healing healingData = template.healing();
        Optional<Healing> healing = healing(healingData != null ? healingData.anyDice() : null,
                healingData != null ? healingData.upcastScaling() : null,
                spellLevel > 0 ? slotLevel - spellLevel : 0, modifier);
        if (damage.isEmpty() || healing.isEmpty()) {
            return manual(spellName, spellLevel, spellName + "'s dice can't be rolled automatically",
                    concentration, durationRounds, verbal);
        }

        Effect effect = new Effect(spellName, delivery, damage.get(), healing.get().dice(), conditions(template),
                concentration, durationRounds, conditionEnds(template, saveDc));
        return new SpellPlan(spellName, spellLevel, effect, null, concentration, durationRounds, verbal);
    }

    /**
     * A lasting spell's effect used again by its caster on a later turn, such as Spiritual Weapon's attack or Flaming
     * Sphere's ram, as the template's repeatEffect describes it: true for the spell's own effect again, conditions
     * included, or an object that replaces its delivery, dice and save and gives no conditions. It starts no
     * concentration.
     */
    public SpellPlan forRepeat(String spellName, int slotLevel, EncounterParticipant caster, String castingAbility,
                               int attackBonus, int saveDc, Boolean advantage) {
        Optional<SpellTemplate> found = template(spellName);
        if (found.isEmpty()) {
            return manual(spellName, 0, NO_TEMPLATE, false, null, false);
        }
        SpellTemplate template = found.get();
        if (template.repeatEffect() == null) {
            return manual(spellName, template.level(), "Spell has no repeatable effect", false, null, false);
        }
        return planFor(template, spellName, template.repeatEffect(), true, slotLevel, caster, castingAbility,
                attackBonus, saveDc, advantage);
    }

    /**
     * What a lasting spell's area does by itself at a moment of a creature's turn (M15), such as Moonbeam's save
     * against its damage when a creature starts its turn in the beam (PHB p.261): the trigger's own effect, or the
     * spell's. It gives no conditions and starts no concentration.
     */
    public SpellPlan forTrigger(String spellName, SpellTrigger when, int slotLevel, EncounterParticipant caster,
                                String castingAbility, int attackBonus, int saveDc) {
        Optional<SpellTemplate> found = template(spellName);
        if (found.isEmpty()) {
            return manual(spellName, 0, NO_TEMPLATE, false, null, false);
        }
        SpellTemplate template = found.get();
        Optional<SpellTemplate.EffectTrigger> trigger = template.trigger(when);
        if (trigger.isEmpty()) {
            return manual(spellName, template.level(), spellName + " does nothing by itself at that moment",
                    false, null, false);
        }
        return planFor(template, spellName, trigger.get().effect(), false, slotLevel, caster, castingAbility,
                attackBonus, saveDc, null);
    }

    // A repeat effect or a trigger as an Effect: the spell's own effect unless it replaces it
    private SpellPlan planFor(SpellTemplate template, String spellName, SpellTemplate.RepeatEffect repeat,
                              boolean ownConditions, int slotLevel, EncounterParticipant caster, String castingAbility,
                              int attackBonus, int saveDc, Boolean advantage) {
        int spellLevel = template.level();
        int modifier = stats.spellcastingModifier(caster, castingAbility, attackBonus);

        // The repeat effect's delivery and save, where it gives them, in place of the spell's
        String deliveryMethod;
        String spellAttackType;
        String saveAbility;
        Boolean halfOnSave;
        Optional<List<Effect.DamagePart>> damage;
        Optional<Healing> healing;
        if (repeat.replaces()) {
            deliveryMethod = orNone(repeat.deliveryMethod() != null ? repeat.deliveryMethod()
                    : template.deliveryMethod());
            spellAttackType = repeat.spellAttackType() != null ? repeat.spellAttackType() : template.spellAttackType();
            saveAbility = repeat.saveAbility() != null ? repeat.saveAbility() : template.saveAbility();
            halfOnSave = repeat.halfOnSave() != null ? repeat.halfOnSave() : template.halfOnSave();
            SpellTemplate.Upcast upcastScaling = repeat.upcastScaling() != null ? repeat.upcastScaling()
                    : template.firstDamageEffect().map(SpellTemplate.Part::upcastScaling).orElse(null);
            int levelsAbove = repeat.usesUpcastScaling() && spellLevel > 0 ? slotLevel - spellLevel : 0;
            damage = damageParts(repeat.damageDice(), repeat.damageType(), upcastScaling, levelsAbove, modifier);
            healing = healing(repeat.healingDice(), null, 0, modifier);
        } else {
            deliveryMethod = orNone(template.deliveryMethod());
            spellAttackType = template.spellAttackType();
            saveAbility = template.saveAbility();
            halfOnSave = template.halfOnSave();
            damage = damage(template.firstDamageEffect(), spellLevel, slotLevel, caster, modifier);
            healing = healing(template.healing() != null ? template.healing().anyDice() : null, null, 0, modifier);
        }
        if (damage.isEmpty() || healing.isEmpty()) {
            return manual(spellName, spellLevel, spellName + "'s dice can't be rolled automatically",
                    false, null, false);
        }

        Effect.Delivery delivery;
        if (healing.get().dice() != null && damage.get().isEmpty()) {
            // Healing alone reaches its targets with no roll
            delivery = new Effect.Automatic();
        } else {
            delivery = delivery(deliveryMethod, spellAttackType, saveAbility, halfOnSave, attackBonus, saveDc,
                    advantage);
            if (delivery == null || delivery instanceof Effect.Self) {
                return manual(spellName, spellLevel,
                        "Repeat delivery method '" + deliveryMethod + "' requires DM adjudication", false, null, false);
            }
        }
        boolean spellsOwn = ownConditions && !repeat.replaces();
        Effect effect = new Effect(spellName, delivery, damage.get(), healing.get().dice(),
                spellsOwn ? conditions(template) : List.of(), false, null,
                spellsOwn ? conditionEnds(template, saveDc) : Map.of());
        return new SpellPlan(spellName, spellLevel, effect, null, false, null, false);
    }

    /**
     * What a spell's casting leaves behind (M15).
     *
     * @param durationRounds how many rounds it lasts; null when unknown
     * @param uses           what it has to spend, such as Melf's Minute Meteors' meteors; null when it has no count
     */
    public record Lasting(Integer durationRounds, Integer uses) {
    }

    /** What casting the spell with a slot of slotLevel leaves behind, if it leaves a Spell Effect. */
    public Optional<Lasting> lasting(String spellName, int slotLevel) {
        return template(spellName).filter(SpellTemplate::lasting).map(template -> new Lasting(
                template.durationRounds(),
                template.effectUses() != null ? template.effectUses().at(template.level(), slotLevel) : null));
    }

    /**
     * How a Spell Effect is used (M15).
     *
     * @param directedCost     what its caster's use of it costs from their turn: ACTION, BONUS_ACTION or NONE; null when
     *                         its caster has nothing to do with it, as for Spirit Guardians
     * @param directedResolves false when that use only moves it, as Moonbeam's action does
     * @param maxUsesEachTurn  the most of its uses one directed use may spend, such as two meteors; null without uses
     * @param triggers         the moments its area acts on a creature by itself, which the DM applies
     */
    public record EffectUse(ActionCost directedCost, boolean directedResolves, Integer maxUsesEachTurn,
                            List<SpellTrigger> triggers) {

        public static final EffectUse NONE = new EffectUse(null, false, null, List.of());
    }

    /** How the spell's Spell Effect is used; nothing to do with it when the spell has no template. */
    public EffectUse effectUse(String spellName) {
        return template(spellName).map(SpellEffects::effectUse).orElse(EffectUse.NONE);
    }

    private static EffectUse effectUse(SpellTemplate template) {
        ActionCost cost = null;
        if (template.repeatEffect() != null) {
            cost = template.repeatActionType() != null ? template.repeatActionType() : ActionCost.ACTION;
        }
        return new EffectUse(cost, template.repeatEffect() != null && template.resolvesWhenDirected(),
                template.effectUses() != null ? template.effectUses().maxEachUse() : null,
                template.effectTriggers().stream().flatMap(trigger -> trigger.when().stream()).distinct().toList());
    }

    /**
     * The most targets the spell may have with a slot of slotLevel: its template's target count, plus any per
     * level above its own. Empty when the template sets no limit.
     */
    public OptionalInt maxTargets(String spellName, int slotLevel) {
        Optional<SpellTemplate> found = template(spellName);
        if (found.isEmpty() || found.get().targetCount() == null) {
            return OptionalInt.empty();
        }
        SpellTemplate template = found.get();
        int spellLevel = template.level();
        int max = template.targetCount();
        SpellTemplate.TargetScaling scaling = template.targetCountUpcastScaling();
        if (slotLevel > spellLevel && scaling != null && scaling.additionalTargetsPerLevel() != null) {
            max += scaling.additionalTargetsPerLevel() * (slotLevel - spellLevel);
        }
        return OptionalInt.of(max);
    }

    /**
     * What using the spell's effect again costs from its caster's turn (D174): the template's repeatActionType, an
     * action, a bonus action or nothing; an action when the template doesn't say.
     */
    public ActionCost repeatCost(String spellName) {
        return template(spellName).map(SpellTemplate::repeatActionType).orElse(ActionCost.ACTION);
    }

    static RollMode rollMode(Boolean advantage) {
        if (advantage == null) return RollMode.NORMAL;
        return advantage ? RollMode.ADVANTAGE : RollMode.DISADVANTAGE;
    }

    // ── Template reading ─────────────────────────────────────────

    // Healing dice, or null dice for none
    private record Healing(Dice dice) {
    }

    // The spell's template; empty without one, or when it isn't an object, and a 409 when it doesn't fit the records
    private Optional<SpellTemplate> template(String spellName) {
        Optional<Spell> spell = spellRepository.findByNameIgnoreCase(spellName);
        if (spell.isEmpty() || spell.get().getEffectTemplate() == null) {
            return Optional.empty();
        }
        return spell.get().getEffectTemplate().readable(spell.get().getName() + "'s effect template");
    }

    private static String orNone(String deliveryMethod) {
        return deliveryMethod != null ? deliveryMethod : "NONE";
    }

    private static SpellPlan manual(String spellName, int spellLevel, String reason, boolean concentration,
                                    Integer durationRounds, boolean verbal) {
        return new SpellPlan(spellName, spellLevel, null, reason, concentration, durationRounds, verbal);
    }

    // Null for a delivery the app can't resolve
    private static Effect.Delivery delivery(String deliveryMethod, String spellAttackType, String saveAbility,
                                            Boolean halfOnSave, int attackBonus, int saveDc, Boolean advantage) {
        return switch (deliveryMethod) {
            // There is no map, so a melee spell attack counts as within 5 feet and a ranged one as not (D161)
            case "SPELL_ATTACK" -> new Effect.Attack(attackBonus, rollMode(advantage),
                    "MELEE".equalsIgnoreCase(spellAttackType != null ? spellAttackType : ""), false);
            case "SAVING_THROW" -> new Effect.Save(Ability.parse(saveAbility).orElse(null), saveDc,
                    Boolean.TRUE.equals(halfOnSave));
            case "AUTO_HIT" -> new Effect.Automatic();
            case "SELF" -> new Effect.Self();
            default -> null;
        };
    }

    // The damage effect's parts at this slot level: none without a damage effect, empty when unreadable
    private Optional<List<Effect.DamagePart>> damage(Optional<SpellTemplate.Part> damageEffect, int spellLevel,
                                                     int slotLevel, EncounterParticipant caster, int modifier) {
        if (damageEffect.isEmpty()) {
            return Optional.of(List.of());
        }
        SpellTemplate.Part part = damageEffect.get();
        if (spellLevel == 0) {
            String scaled = cantripDice(part.cantripScaling(), stats.level(caster));
            return damageParts(scaled != null ? scaled : part.damageDice(), part.damageType(), null, 0, modifier);
        }
        return damageParts(part.damageDice(), part.damageType(), part.upcastScaling(), slotLevel - spellLevel,
                modifier);
    }

    private static Optional<List<Effect.DamagePart>> damageParts(String dice, String damageType,
                                                                 SpellTemplate.Upcast upcastScaling, int levelsAbove,
                                                                 int modifier) {
        if (dice == null) {
            return Optional.of(List.of());
        }
        String type = damageType != null && !damageType.isBlank() ? damageType.trim().toLowerCase() : null;
        return TemplateDice.read(dice, modifier)
                .flatMap(groups -> upcast(groups, upcastScaling, levelsAbove))
                .map(groups -> groups.stream().map(each -> new Effect.DamagePart(each, type)).toList());
    }

    // A cantrip's dice at its caster's level: the entry for the highest of 17, 11 and 5 the level reaches
    private static String cantripDice(Map<String, String> cantripScaling, int casterLevel) {
        for (int threshold : new int[]{17, 11, 5}) {
            String dice = cantripScaling.get(String.valueOf(threshold));
            if (casterLevel >= threshold && dice != null) {
                return dice;
            }
        }
        return null;
    }

    // Upcast dice and flat amounts for each scalingInterval levels above the spell's own; empty when unreadable
    private static Optional<List<Dice>> upcast(List<Dice> groups, SpellTemplate.Upcast upcastScaling,
                                               int levelsAbove) {
        if (upcastScaling == null || levelsAbove <= 0) {
            return Optional.of(groups);
        }
        int levels = levelsAbove / upcastScaling.interval();
        Dice perLevelDice = null;
        if (upcastScaling.additionalDicePerLevel() != null) {
            Optional<List<Dice>> read = TemplateDice.read(upcastScaling.additionalDicePerLevel());
            if (read.isEmpty() || read.get().size() != 1) {
                return Optional.empty();
            }
            perLevelDice = read.get().getFirst();
        }
        return Optional.of(TemplateDice.upcast(groups, perLevelDice, upcastScaling.flatPerLevel(), levels));
    }

    // The healing at this slot level: null dice for none, empty when unreadable
    private static Optional<Healing> healing(String dice, SpellTemplate.Upcast upcastScaling, int levelsAbove,
                                             int modifier) {
        if (dice == null) {
            return Optional.of(new Healing(null));
        }
        return TemplateDice.read(dice, modifier)
                .filter(groups -> groups.size() == 1)
                .flatMap(groups -> upcast(groups, upcastScaling, levelsAbove))
                .filter(groups -> groups.size() == 1)
                .map(groups -> new Healing(groups.getFirst()));
    }

    // Each condition the spell inflicts, listed by name or as an object naming it
    private static List<String> conditions(SpellTemplate template) {
        return template.conditionsInflicted().stream()
                .map(SpellTemplate.ConditionInflicted::name)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * How each condition ends where its entry says (D162): its own duration in rounds, and a save repeated at
     * the end of each of its creature's turns against the caster's spell save DC, in the entry's ability or
     * else the spell's. A repeat save the entry makes conditional, such as Fear's on losing sight of the
     * caster, is left to the DM.
     */
    private static Map<String, Effect.ConditionEnd> conditionEnds(SpellTemplate template, int saveDc) {
        Map<String, Effect.ConditionEnd> ends = new LinkedHashMap<>();
        for (SpellTemplate.ConditionInflicted condition : template.conditionsInflicted()) {
            String name = condition.name();
            if (name == null) {
                continue;
            }
            Effect.RepeatSave repeatSave = null;
            if (condition.saveToEndEachTurn() && !condition.conditionalSave()) {
                repeatSave = Ability.parse(condition.saveToEndAbility())
                        .or(() -> Ability.parse(template.saveAbility()))
                        .map(ability -> new Effect.RepeatSave(ability, saveDc))
                        .orElse(null);
            }
            if (condition.durationRounds() != null || repeatSave != null) {
                ends.put(name, new Effect.ConditionEnd(condition.durationRounds(), repeatSave));
            }
        }
        return ends;
    }
}
