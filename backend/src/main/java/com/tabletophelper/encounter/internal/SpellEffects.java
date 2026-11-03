package com.tabletophelper.encounter.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.RollMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final ObjectMapper objectMapper;
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
        JsonNode template = template(spellName);
        if (template.isMissingNode()) {
            return manual(spellName, 0, NO_TEMPLATE, false, null, false);
        }
        int spellLevel = template.path("spellLevel").asInt(0);
        boolean concentration = template.path("concentration").asBoolean(false);
        Integer durationRounds = template.path("durationRounds").isNumber()
                ? template.path("durationRounds").asInt() : null;
        boolean verbal = template.path("components").path("verbal").asBoolean(false);

        if (template.path("requiresManualResolution").asBoolean(false)) {
            String reason = template.path("manualResolutionReason").asText("Complex spell requires DM adjudication");
            return manual(spellName, spellLevel, reason, concentration, durationRounds, verbal);
        }
        String deliveryMethod = template.path("deliveryMethod").asText("NONE");
        Effect.Delivery delivery = delivery(deliveryMethod, template, attackBonus, saveDc, advantage);
        if (delivery == null) {
            return manual(spellName, spellLevel, "Delivery method '" + deliveryMethod + "' requires DM adjudication",
                    concentration, durationRounds, verbal);
        }

        int modifier = stats.spellcastingModifier(caster, castingAbility, attackBonus);
        // A spell on its caster heals or gives conditions; any damage in its template is for others to take
        Optional<List<Effect.DamagePart>> damage = delivery instanceof Effect.Self
                ? Optional.of(List.of())
                : damage(firstDamageEffect(template.path("effects")), spellLevel, slotLevel, caster, modifier);
        JsonNode healingNode = template.path("healing");
        Optional<Healing> healing = healing(healingDice(healingNode), healingNode.path("upcastScaling"),
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
     * A concentration or lasting spell's effect used again on a later turn, such as Moonbeam's damage or
     * Spiritual Weapon's attack, as the template's repeatEffect describes it: true for the spell's own effect
     * again, or an object that replaces its delivery, dice and save. It starts no concentration.
     */
    public SpellPlan forRepeat(String spellName, int slotLevel, EncounterParticipant caster, String castingAbility,
                               int attackBonus, int saveDc, Boolean advantage) {
        JsonNode template = template(spellName);
        if (template.isMissingNode()) {
            return manual(spellName, 0, NO_TEMPLATE, false, null, false);
        }
        int spellLevel = template.path("spellLevel").asInt(0);
        JsonNode repeat = template.path("repeatEffect");
        if (!repeat.isObject() && !(repeat.isBoolean() && repeat.asBoolean())) {
            return manual(spellName, spellLevel, "Spell has no repeatable effect", false, null, false);
        }
        int modifier = stats.spellcastingModifier(caster, castingAbility, attackBonus);

        JsonNode source;
        Optional<List<Effect.DamagePart>> damage;
        Optional<Healing> healing;
        if (repeat.isObject()) {
            source = merged(repeat, template);
            JsonNode upcastScaling = repeat.has("upcastScaling") ? repeat.path("upcastScaling")
                    : firstDamageEffect(template.path("effects")).path("upcastScaling");
            int levelsAbove = repeat.path("usesUpcastScaling").asBoolean(false) && spellLevel > 0
                    ? slotLevel - spellLevel : 0;
            damage = damageParts(repeat.path("damageDice"), repeat.path("damageType"), upcastScaling, levelsAbove,
                    modifier);
            healing = healing(repeat.path("healingDice"), MissingNode.getInstance(), 0, modifier);
        } else {
            source = template;
            damage = damage(firstDamageEffect(template.path("effects")), spellLevel, slotLevel, caster, modifier);
            healing = healing(healingDice(template.path("healing")), MissingNode.getInstance(), 0, modifier);
        }
        if (damage.isEmpty() || healing.isEmpty()) {
            return manual(spellName, spellLevel, spellName + "'s dice can't be rolled automatically",
                    false, null, false);
        }

        Effect.Delivery delivery;
        String deliveryMethod = source.path("deliveryMethod").asText("NONE");
        if (healing.get().dice() != null && damage.get().isEmpty()) {
            // Healing alone reaches its targets with no roll
            delivery = new Effect.Automatic();
        } else {
            delivery = delivery(deliveryMethod, source, attackBonus, saveDc, advantage);
            if (delivery == null || delivery instanceof Effect.Self) {
                return manual(spellName, spellLevel,
                        "Repeat delivery method '" + deliveryMethod + "' requires DM adjudication", false, null, false);
            }
        }
        Effect effect = new Effect(spellName, delivery, damage.get(), healing.get().dice(), conditions(template),
                false, null, conditionEnds(template, saveDc));
        return new SpellPlan(spellName, spellLevel, effect, null, false, null, false);
    }

    /**
     * The most targets the spell may have with a slot of slotLevel: its template's target count, plus any per
     * level above its own. Empty when the template sets no limit.
     */
    public OptionalInt maxTargets(String spellName, int slotLevel) {
        JsonNode template = template(spellName);
        if (!template.path("targetCount").isNumber()) {
            return OptionalInt.empty();
        }
        int spellLevel = template.path("spellLevel").asInt(0);
        int max = template.path("targetCount").asInt();
        if (slotLevel > spellLevel) {
            max += template.path("targetCountUpcastScaling").path("additionalTargetsPerLevel").asInt(0)
                    * (slotLevel - spellLevel);
        }
        return OptionalInt.of(max);
    }

    /** Whether the spell keeps going without concentration, so its caster can use its effect again. */
    public boolean persistsWithoutConcentration(String spellName) {
        return template(spellName).path("persistsWithoutConcentration").asBoolean(false);
    }

    static RollMode rollMode(Boolean advantage) {
        if (advantage == null) return RollMode.NORMAL;
        return advantage ? RollMode.ADVANTAGE : RollMode.DISADVANTAGE;
    }

    // ── Template reading ─────────────────────────────────────────

    // Healing dice, or null dice for none
    private record Healing(Dice dice) {
    }

    private JsonNode template(String spellName) {
        Optional<Spell> spell = spellRepository.findByNameIgnoreCase(spellName);
        if (spell.isEmpty() || spell.get().getEffectTemplate() == null) {
            return MissingNode.getInstance();
        }
        JsonNode template = StoredJson.read(objectMapper, spell.get().getEffectTemplate(),
                spell.get().getName() + "'s effect template");
        return template.isObject() ? template : MissingNode.getInstance();
    }

    private static SpellPlan manual(String spellName, int spellLevel, String reason, boolean concentration,
                                    Integer durationRounds, boolean verbal) {
        return new SpellPlan(spellName, spellLevel, null, reason, concentration, durationRounds, verbal);
    }

    // Null for a delivery the app can't resolve
    private static Effect.Delivery delivery(String deliveryMethod, JsonNode template, int attackBonus, int saveDc,
                                            Boolean advantage) {
        return switch (deliveryMethod) {
            // There is no map, so a melee spell attack counts as within 5 feet and a ranged one as not (D161)
            case "SPELL_ATTACK" -> new Effect.Attack(attackBonus, rollMode(advantage),
                    "MELEE".equalsIgnoreCase(template.path("spellAttackType").asText("")), false);
            case "SAVING_THROW" -> new Effect.Save(
                    Ability.parse(template.path("saveAbility").asText(null)).orElse(null),
                    saveDc, template.path("halfOnSave").asBoolean(false));
            case "AUTO_HIT" -> new Effect.Automatic();
            case "SELF" -> new Effect.Self();
            default -> null;
        };
    }

    // The spell's fields with the repeat effect's in their place
    private static JsonNode merged(JsonNode repeat, JsonNode template) {
        ObjectNode merged = template.deepCopy();
        repeat.fields().forEachRemaining(field -> merged.set(field.getKey(), field.getValue()));
        return merged;
    }

    // The first effect with damage dice, or a missing node
    private static JsonNode firstDamageEffect(JsonNode effects) {
        for (JsonNode effect : effects) {
            if (effect.path("damageDice").isTextual()) {
                return effect;
            }
        }
        return MissingNode.getInstance();
    }

    private static JsonNode healingDice(JsonNode healing) {
        return healing.path("healingDice").isTextual() ? healing.path("healingDice") : healing.path("dice");
    }

    // The damage effect's parts at this slot level: none without a damage effect, empty when unreadable
    private Optional<List<Effect.DamagePart>> damage(JsonNode damageEffect, int spellLevel, int slotLevel,
                                                     EncounterParticipant caster, int modifier) {
        if (damageEffect.isMissingNode()) {
            return Optional.of(List.of());
        }
        if (spellLevel == 0) {
            JsonNode scaled = cantripDice(damageEffect.path("cantripScaling"), stats.level(caster));
            return damageParts(scaled != null ? scaled : damageEffect.path("damageDice"),
                    damageEffect.path("damageType"), MissingNode.getInstance(), 0, modifier);
        }
        return damageParts(damageEffect.path("damageDice"), damageEffect.path("damageType"),
                damageEffect.path("upcastScaling"), slotLevel - spellLevel, modifier);
    }

    private static Optional<List<Effect.DamagePart>> damageParts(JsonNode diceNode, JsonNode typeNode,
                                                                 JsonNode upcastScaling, int levelsAbove,
                                                                 int modifier) {
        if (!diceNode.isTextual()) {
            return Optional.of(List.of());
        }
        String type = typeNode.isTextual() && !typeNode.asText().isBlank()
                ? typeNode.asText().trim().toLowerCase() : null;
        return TemplateDice.read(diceNode.asText(), modifier)
                .flatMap(groups -> upcast(groups, upcastScaling, levelsAbove))
                .map(groups -> groups.stream().map(dice -> new Effect.DamagePart(dice, type)).toList());
    }

    // A cantrip's dice at its caster's level: the entry for the highest of 17, 11 and 5 the level reaches
    private static JsonNode cantripDice(JsonNode cantripScaling, int casterLevel) {
        for (int threshold : new int[]{17, 11, 5}) {
            JsonNode dice = cantripScaling.path(String.valueOf(threshold));
            if (casterLevel >= threshold && dice.isTextual()) {
                return dice;
            }
        }
        return null;
    }

    // Upcast dice and flat amounts for each scalingInterval levels above the spell's own; empty when unreadable
    private static Optional<List<Dice>> upcast(List<Dice> groups, JsonNode upcastScaling, int levelsAbove) {
        if (!upcastScaling.isObject() || levelsAbove <= 0) {
            return Optional.of(groups);
        }
        int levels = levelsAbove / Math.max(1, upcastScaling.path("scalingInterval").asInt(1));
        JsonNode perLevel = upcastScaling.path("additionalDicePerLevel");
        Dice perLevelDice = null;
        if (perLevel.isTextual()) {
            Optional<List<Dice>> read = TemplateDice.read(perLevel.asText());
            if (read.isEmpty() || read.get().size() != 1) {
                return Optional.empty();
            }
            perLevelDice = read.get().getFirst();
        }
        return Optional.of(TemplateDice.upcast(groups, perLevelDice,
                upcastScaling.path("additionalFlatPerLevel").asInt(0), levels));
    }

    // The healing at this slot level: null dice for none, empty when unreadable
    private static Optional<Healing> healing(JsonNode diceNode, JsonNode upcastScaling, int levelsAbove,
                                             int modifier) {
        if (!diceNode.isTextual()) {
            return Optional.of(new Healing(null));
        }
        return TemplateDice.read(diceNode.asText(), modifier)
                .filter(groups -> groups.size() == 1)
                .flatMap(groups -> upcast(groups, upcastScaling, levelsAbove))
                .filter(groups -> groups.size() == 1)
                .map(groups -> new Healing(groups.getFirst()));
    }

    // Each condition the spell inflicts, listed by name or as an object naming it
    private static List<String> conditions(JsonNode template) {
        List<String> conditions = new ArrayList<>();
        for (JsonNode condition : template.path("conditionsInflicted")) {
            String name = conditionName(condition);
            if (name != null) {
                conditions.add(name);
            }
        }
        return conditions;
    }

    /**
     * How each condition ends where its entry says (D162): its own duration in rounds, and a save repeated at
     * the end of each of its creature's turns against the caster's spell save DC, in the entry's ability or
     * else the spell's. A repeat save the entry makes conditional, such as Fear's on losing sight of the
     * caster, is left to the DM.
     */
    private static Map<String, Effect.ConditionEnd> conditionEnds(JsonNode template, int saveDc) {
        Map<String, Effect.ConditionEnd> ends = new LinkedHashMap<>();
        for (JsonNode condition : template.path("conditionsInflicted")) {
            String name = conditionName(condition);
            if (name == null || !condition.isObject()) {
                continue;
            }
            Integer duration = condition.path("durationRounds").isInt() && condition.path("durationRounds").asInt() > 0
                    ? condition.path("durationRounds").asInt() : null;
            Effect.RepeatSave repeatSave = null;
            if (condition.path("saveToEndEachTurn").asBoolean(false) && condition.path("saveToEndCondition").isMissingNode()) {
                repeatSave = Ability.parse(condition.path("saveToEndAbility").asText(null))
                        .or(() -> Ability.parse(template.path("saveAbility").asText(null)))
                        .map(ability -> new Effect.RepeatSave(ability, saveDc))
                        .orElse(null);
            }
            if (duration != null || repeatSave != null) {
                ends.put(name, new Effect.ConditionEnd(duration, repeatSave));
            }
        }
        return ends;
    }

    private static String conditionName(JsonNode condition) {
        JsonNode name = condition.isObject() ? condition.path("condition") : condition;
        return name.isTextual() && !name.asText().isBlank() ? name.asText().trim().toLowerCase() : null;
    }
}
