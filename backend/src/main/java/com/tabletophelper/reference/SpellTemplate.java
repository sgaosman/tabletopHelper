package com.tabletophelper.reference;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.tabletophelper.rules.combat.ActionCost;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.JacksonDefaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A spell's effect template, the {@code spells.effect_template} column (D030, D168): what casting it does, as records.
 * The records name only the keys combat and the targeting rules read; the column's JSON is kept whole beside them, and
 * it is what is stored and what the API returns, so the keys they don't name, such as the notes, are never lost.
 *
 * @param spellLevel                   the spell's level, 0 for a cantrip; null when the data doesn't say
 * @param concentration                whether it needs concentration
 * @param durationRounds               how many rounds it lasts, when the data says
 * @param components                   its components, of which combat reads only the verbal one
 * @param requiresManualResolution     true when the DM resolves it by hand
 * @param manualResolutionReason       why, when they do
 * @param deliveryMethod               SPELL_ATTACK, SAVING_THROW, AUTO_HIT or SELF; any other is the DM's
 * @param spellAttackType              MELEE or RANGED, for a spell attack
 * @param saveAbility                  its saving throw's ability, as the data records it
 * @param halfOnSave                   whether a successful save halves its damage
 * @param targetCount                  how many targets it has at its own level, when it has a limit
 * @param targetType                   how it picks its targets, such as SINGLE_TARGET or AREA
 * @param targetCountUpcastScaling     the targets it adds per slot level above its own
 * @param persistsWithoutConcentration whether it keeps going without concentration, so its effect can be used again
 * @param selfOnly                     whether it can only target its caster
 * @param canTargetSelf                whether it can target its caster
 * @param canTargetAllies              whether it can target allies; null when the data doesn't say
 * @param canTargetEnemies             whether it can target enemies; null when the data doesn't say
 * @param effects                      its effects, of which combat rolls the first with damage dice
 * @param healing                      its healing, or null
 * @param repeatEffect                 its effect used again on a later turn, or null when it has none
 * @param repeatActionType             what using that effect again costs from the caster's turn (D174): ACTION,
 *                                     BONUS_ACTION, or NONE for an effect that needs neither, such as one that
 *                                     happens when a creature starts its turn in the area; null when the data
 *                                     doesn't say. Any other value makes the template unreadable.
 * @param conditionsInflicted          the conditions it gives, by name or with how they end
 * @param json                         the column's JSON as stored; null only for a template built without one
 * @param unreadable                   why the JSON doesn't fit the records, or null; the records are then empty, and
 *                                     {@link #readable} stops a command that needs them with a 409 (D155)
 */
public record SpellTemplate(Integer spellLevel, Boolean concentration, Integer durationRounds, Components components,
                            Boolean requiresManualResolution, String manualResolutionReason, String deliveryMethod,
                            String spellAttackType, String saveAbility, Boolean halfOnSave, Integer targetCount,
                            String targetType, TargetScaling targetCountUpcastScaling, Boolean persistsWithoutConcentration,
                            Boolean selfOnly, Boolean canTargetSelf, Boolean canTargetAllies,
                            Boolean canTargetEnemies, List<Part> effects, Healing healing,
                            RepeatEffect repeatEffect, ActionCost repeatActionType,
                            List<ConditionInflicted> conditionsInflicted, JsonNode json, String unreadable) {

    private static final Logger log = LoggerFactory.getLogger(SpellTemplate.class);

    // Reads the parts into the records, with the application's Jackson settings. Each record ignores the keys it
    // doesn't name, as the column's mapper does
    private static final ObjectMapper READER = JacksonDefaults.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private static final TypeReference<List<Part>> PARTS = new TypeReference<>() {
    };
    private static final TypeReference<List<ConditionInflicted>> CONDITIONS = new TypeReference<>() {
    };

    public SpellTemplate {
        effects = listOf(effects);
        conditionsInflicted = listOf(conditionsInflicted);
    }

    /**
     * The template of this JSON, which it keeps as it is. JSON that isn't an object has no fields, and JSON whose parts
     * don't fit the records is kept too, marked unreadable, so the spell still loads and only a command that needs its
     * template fails.
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static SpellTemplate of(JsonNode json) {
        if (json == null || !json.isObject()) {
            return empty(json, null);
        }
        try {
            return new SpellTemplate(read(json, "spellLevel", Integer.class), read(json, "concentration", Boolean.class),
                    read(json, "durationRounds", Integer.class), read(json, "components", Components.class),
                    read(json, "requiresManualResolution", Boolean.class),
                    read(json, "manualResolutionReason", String.class), read(json, "deliveryMethod", String.class),
                    read(json, "spellAttackType", String.class), read(json, "saveAbility", String.class),
                    read(json, "halfOnSave", Boolean.class), read(json, "targetCount", Integer.class),
                    read(json, "targetType", String.class), read(json, "targetCountUpcastScaling", TargetScaling.class),
                    read(json, "persistsWithoutConcentration", Boolean.class), read(json, "selfOnly", Boolean.class),
                    read(json, "canTargetSelf", Boolean.class), read(json, "canTargetAllies", Boolean.class),
                    read(json, "canTargetEnemies", Boolean.class), READER.convertValue(json.get("effects"), PARTS),
                    read(json, "healing", Healing.class), RepeatEffect.of(json.get("repeatEffect")),
                    repeatActionType(json.get("repeatActionType")), READER.convertValue(json.get("conditionsInflicted"), CONDITIONS), json, null);
        } catch (IllegalArgumentException | JacksonException e) {
            return empty(json, e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    /** The JSON as stored and as the API returns it. */
    @JsonValue
    @Override
    public JsonNode json() {
        return json;
    }

    /** The template of the column's text, for data the caller knows to be valid JSON, such as a test's. */
    public static SpellTemplate parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return of(READER.readTree(json));
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Not JSON: " + json, e);
        }
    }

    /**
     * The template as records, empty when the JSON isn't an object. One that doesn't fit the records stops the command
     * with a 409 naming it (D155), and the cause is logged.
     *
     * @param what names the data for the message, such as "Fireball's effect template"
     */
    public Optional<SpellTemplate> readable(String what) {
        if (unreadable != null) {
            log.error("{} doesn't fit the template records: {}", what, unreadable);
            throw new ConflictException(what + " can't be read");
        }
        return json != null && json.isObject() ? Optional.of(this) : Optional.empty();
    }

    /** The spell's level, 0 when the data doesn't say. */
    public int level() {
        return spellLevel != null ? spellLevel : 0;
    }

    /** Whether it has a verbal component. */
    public boolean verbal() {
        return components != null && Boolean.TRUE.equals(components.verbal());
    }

    /** The first effect with damage dice, if any. */
    public Optional<Part> firstDamageEffect() {
        return effects.stream().filter(effect -> effect.damageDice() != null).findFirst();
    }

    /** Its components; combat reads only whether it has a verbal one. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Components(Boolean verbal) {
    }

    /** The targets a spell adds for each slot level above its own. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TargetScaling(Integer additionalTargetsPerLevel) {
    }

    /**
     * One effect of a spell.
     *
     * @param damageDice     its damage, such as "8d6" or "1d8 + MOD"
     * @param cantripScaling a cantrip's damage dice from the caster level of each key: "5", "11" and "17"
     * @param upcastScaling  what each slot level above the spell's own adds
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Part(String damageDice, String damageType, Map<String, String> cantripScaling,
                       Upcast upcastScaling) {

        public Part {
            cantripScaling = cantripScaling == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(cantripScaling));
        }
    }

    /**
     * What a spell cast with a higher slot adds.
     *
     * @param additionalDicePerLevel  dice per step, such as "1d6"
     * @param additionalFlatPerLevel  a flat amount per step
     * @param scalingInterval         how many slot levels make one step; 1 when the data doesn't say
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Upcast(String additionalDicePerLevel, Integer additionalFlatPerLevel, Integer scalingInterval) {

        public int flatPerLevel() {
            return additionalFlatPerLevel != null ? additionalFlatPerLevel : 0;
        }

        public int interval() {
            return Math.max(1, scalingInterval != null ? scalingInterval : 1);
        }
    }

    /**
     * A spell's healing.
     *
     * @param healingDice its dice, such as "1d8 + MOD"
     * @param dice        its dice under the other key some templates use
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Healing(String healingDice, String dice, Upcast upcastScaling) {

        /** Its dice under either key; null for none. */
        public String anyDice() {
            return healingDice != null ? healingDice : dice;
        }
    }

    /**
     * A concentration or lasting spell's effect used again on a later turn: the spell's own effect again, or one that
     * replaces its delivery, dice and save.
     *
     * @param replaces          false for the spell's own effect again, true when the fields below replace its own
     * @param usesUpcastScaling whether the slot the spell was cast with adds its upcast dice
     * @param upcastScaling     the upcast dice it adds, in place of the spell's first damage effect's
     */
    public record RepeatEffect(boolean replaces, String deliveryMethod, String spellAttackType, String saveAbility,
                               Boolean halfOnSave, String damageDice, String damageType, String healingDice,
                               boolean usesUpcastScaling, Upcast upcastScaling) {

        /**
         * The repeat effect the data gives: true for the spell's own effect, an object for one of its own; null for
         * none, which is anything else.
         */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static RepeatEffect of(JsonNode node) {
            if (node == null) {
                return null;
            }
            if (node.isBoolean()) {
                return node.asBoolean(false) ? new RepeatEffect(false, null, null, null, null, null, null, null, false, null)
                        : null;
            }
            if (!node.isObject()) {
                return null;
            }
            return new RepeatEffect(true, text(node, "deliveryMethod"), text(node, "spellAttackType"),
                    text(node, "saveAbility"), read(node, "halfOnSave", Boolean.class), text(node, "damageDice"),
                    text(node, "damageType"), text(node, "healingDice"),
                    node.path("usesUpcastScaling").asBoolean(false), read(node, "upcastScaling", Upcast.class));
        }
    }

    /**
     * A condition the spell gives, named alone or with how it ends (D162).
     *
     * @param condition         its name as the data gives it; null when it gives none
     * @param durationRounds    how many rounds it lasts, when the data gives a whole number above 0
     * @param saveToEndEachTurn whether its creature repeats a save at the end of each of its turns
     * @param saveToEndAbility  that save's ability, when it isn't the spell's
     * @param conditionalSave   whether the data makes that save conditional, as Fear's is on losing sight of the
     *                          caster, which leaves it to the DM
     */
    public record ConditionInflicted(String condition, Integer durationRounds, boolean saveToEndEachTurn,
                                     String saveToEndAbility, boolean conditionalSave) {

        /** A condition as the data lists it: its name, or an object naming it. */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static ConditionInflicted of(JsonNode node) {
            if (node == null || !node.isObject()) {
                return new ConditionInflicted(node != null && node.isString() ? node.asString("") : null,
                        null, false, null, false);
            }
            JsonNode duration = node.path("durationRounds");
            return new ConditionInflicted(text(node, "condition"),
                    duration.isInt() && duration.asInt(0) > 0 ? duration.asInt(0) : null,
                    node.path("saveToEndEachTurn").asBoolean(false), text(node, "saveToEndAbility"),
                    !node.path("saveToEndCondition").isMissingNode());
        }

        /** Its name in lower case, or null when it gives none. */
        public String name() {
            return condition != null && !condition.isBlank() ? condition.trim().toLowerCase() : null;
        }
    }

    private static SpellTemplate empty(JsonNode json, String unreadable) {
        return new SpellTemplate(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, json, unreadable);
    }

    // What using the repeat effect again costs: ACTION, BONUS_ACTION or NONE, and null when the data doesn't say
    private static ActionCost repeatActionType(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.isString() ? node.asString("") : node.toString();
        return switch (value) {
            case "ACTION" -> ActionCost.ACTION;
            case "BONUS_ACTION" -> ActionCost.BONUS_ACTION;
            case "NONE" -> ActionCost.NONE;
            default -> throw new IllegalArgumentException("An unknown repeatActionType: " + value
                    + " (ACTION, BONUS_ACTION or NONE)");
        };
    }

    private static <T> T read(JsonNode node, String key, Class<T> type) {
        return READER.convertValue(node.get(key), type);
    }

    // The key's text, or null when it isn't text
    private static String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        return value.isString() ? value.asString("") : null;
    }

    // A list without nulls, empty when there is none
    private static <T> List<T> listOf(List<T> list) {
        return list == null ? List.of() : list.stream().filter(Objects::nonNull).toList();
    }
}
