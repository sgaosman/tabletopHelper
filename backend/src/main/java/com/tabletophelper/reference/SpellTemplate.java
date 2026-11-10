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
import com.tabletophelper.rules.combat.OngoingModifier;
import com.tabletophelper.rules.combat.RollMode;
import com.tabletophelper.rules.combat.SpellTrigger;
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
 * @param effectTriggers               what its area does by itself to a creature that enters it, or starts or ends its
 *                                     turn there (M15), which the DM applies; empty when nothing
 * @param directedResolves             false when using the effect again only moves it, as Moonbeam's action does, so it
 *                                     resolves nothing against creatures (PHB p.261); null counts as true
 * @param effectUses                   what the lasting effect has to spend, such as Melf's Minute Meteors' meteors; null
 *                                     when it has no count
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
                            List<ConditionInflicted> conditionsInflicted, List<EffectTrigger> effectTriggers,
                            Boolean directedResolves, EffectUses effectUses, JsonNode json, String unreadable) {

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
        effectTriggers = listOf(effectTriggers);
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
                    repeatActionType(json.get("repeatActionType")), READER.convertValue(json.get("conditionsInflicted"), CONDITIONS),
                    effectTriggers(json.get("effectTriggers")), read(json, "directedResolves", Boolean.class),
                    read(json, "effectUses", EffectUses.class), json, null);
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

    /** Whether it has a somatic component, read from the stored JSON (M18, for Subtle Spell). */
    public boolean somatic() {
        return json != null && json.path("components").path("somatic").asBoolean(false);
    }

    /** Its duration as the PHB words it, such as "Concentration, up to 1 minute", read from the stored JSON; or null. */
    public String durationText() {
        if (json == null || !json.path("durationText").isString()) {
            return null;
        }
        return json.path("durationText").asString();
    }

    /**
     * Whether casting it leaves a Spell Effect on its caster (M15): it has an effect its caster can use again on later
     * turns, or one its area applies by itself.
     */
    public boolean lasting() {
        return repeatEffect != null || !effectTriggers.isEmpty();
    }

    /** Whether using the effect again resolves against creatures, rather than only moving it. */
    public boolean resolvesWhenDirected() {
        return !Boolean.FALSE.equals(directedResolves);
    }

    /** The trigger that happens at this moment, if the spell has one. */
    public Optional<EffectTrigger> trigger(SpellTrigger when) {
        return effectTriggers.stream().filter(trigger -> trigger.when().contains(when)).findFirst();
    }

    /** Every Ongoing Modifier its effects give (D184), in order. */
    public List<ModifierDefinition> modifiers() {
        return effects.stream().flatMap(effect -> effect.modifiers().stream()).toList();
    }

    /** The options a cast chooses between (D184), such as "enlarge" and "reduce"; empty when it has none. */
    public List<String> castOptions() {
        return texts("castOptions");
    }

    /** The cast options that need no saving throw (D184), such as Enlarge on a willing creature; empty for none. */
    public List<String> noSaveOptions() {
        return texts("noSaveOptions");
    }

    /**
     * The saving throw each affected creature repeats at the end of each of its turns, ending the Ongoing Modifiers for
     * itself on a success, as Slow's Wisdom save (PHB p.277); null for none.
     */
    public String modifierRepeatSave() {
        return json != null ? text(json, "modifierRepeatSave") : null;
    }

    /** A reminder the cast logs for what the app leaves to the table, such as Haste's extra action (PHB p.250). */
    public String reminder() {
        return json != null ? text(json, "reminder") : null;
    }

    /** A reminder logged when its Spell Effect ends, such as Haste's lethargy (PHB p.250); null for none. */
    public String endReminder() {
        return json != null ? text(json, "endReminder") : null;
    }

    /**
     * Whether its caster can move it to a new creature with a bonus action once its creature drops to 0 hit points, as
     * Hunter's Mark (PHB p.251) and Hex (PHB p.251) can.
     */
    public boolean retargetable() {
        return json != null && json.path("retargetable").asBoolean(false);
    }

    // The JSON's list of texts under the key; empty when there is none
    private List<String> texts(String key) {
        if (json == null || !json.path(key).isArray()) {
            return List.of();
        }
        List<String> texts = new java.util.ArrayList<>();
        json.path(key).forEach(node -> {
            if (node.isString()) texts.add(node.asString(""));
        });
        return List.copyOf(texts);
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
                       Upcast upcastScaling, List<ModifierDefinition> modifiers) {

        public Part {
            cantripScaling = cantripScaling == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(cantripScaling));
            modifiers = listOf(modifiers);
        }

        /** A part with no Ongoing Modifiers. */
        public Part(String damageDice, String damageType, Map<String, String> cantripScaling, Upcast upcastScaling) {
            this(damageDice, damageType, cantripScaling, upcastScaling, List.of());
        }
    }

    /**
     * An Ongoing Modifier as a BUFF or DEBUFF effect's {@code modifiers} list gives it (D184), before casting fixes its
     * numbers: what {@link OngoingModifier} holds, and how the cast decides the rest.
     *
     * @param option               the cast option it belongs to, such as "enlarge" for Enlarge/Reduce (PHB p.237); null
     *                             for every cast. A damage type of "OPTION" is the option chosen, as Protection from
     *                             Energy's (PHB p.270)
     * @param upcast               what each step of slot levels above the spell's own adds
     * @param spellcastingModifier its flat amount is the caster's spellcasting modifier, as Heroism's temporary hit points
     *                             (PHB p.250)
     * @param plusDex              its flat amount adds the creature's Dexterity modifier, as Mage Armor's 13 + DEX (PHB
     *                             p.256)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModifierDefinition(OngoingModifier.Kind kind, OngoingModifier.Scope scope, String dice, Integer flat,
                                     RollMode mode, String ability, List<String> damageTypes, boolean weaponOnly,
                                     boolean meleeOnly, boolean oneShot, OngoingModifier.Until until, String requires,
                                     List<String> attackerTypes, String condition, String skill, String option,
                                     ModifierUpcast upcast, boolean spellcastingModifier, boolean plusDex) {

        public ModifierDefinition {
            Objects.requireNonNull(kind, "A modifier needs a kind");
            damageTypes = listOf(damageTypes);
            attackerTypes = listOf(attackerTypes);
            if (dice != null && !dice.isBlank()) {
                com.tabletophelper.rules.Dice.parse(dice.trim().replaceFirst("^[+-]", ""));
            }
        }

        /** Its flat amount, 0 when it has none. */
        public int flatAmount() {
            return flat != null ? flat : 0;
        }
    }

    /**
     * What an Ongoing Modifier gains for each step of slot levels above its spell's own (D184), such as Magic Weapon's
     * +1 for every two (PHB p.257) or Aid's 5 hit points for each (PHB p.211).
     *
     * @param every how many slot levels make a step; 1 when the data doesn't say
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModifierUpcast(Integer every, Integer flat, String dice) {

        /** How many steps a slot of slotLevel gives a spell of spellLevel. */
        public int steps(int spellLevel, int slotLevel) {
            int interval = Math.max(1, every != null ? every : 1);
            return Math.max(0, slotLevel - spellLevel) / interval;
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
     * @param endsOnAttackOrCast whether it ends for a creature that attacks or casts a spell, as Invisibility's does
     *                          (PHB p.254)
     */
    public record ConditionInflicted(String condition, Integer durationRounds, boolean saveToEndEachTurn,
                                     String saveToEndAbility, boolean conditionalSave, boolean endsOnAttackOrCast) {

        /** A condition as the data lists it: its name, or an object naming it. */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static ConditionInflicted of(JsonNode node) {
            if (node == null || !node.isObject()) {
                return new ConditionInflicted(node != null && node.isString() ? node.asString("") : null,
                        null, false, null, false, false);
            }
            JsonNode duration = node.path("durationRounds");
            return new ConditionInflicted(text(node, "condition"),
                    duration.isInt() && duration.asInt(0) > 0 ? duration.asInt(0) : null,
                    node.path("saveToEndEachTurn").asBoolean(false), text(node, "saveToEndAbility"),
                    !node.path("saveToEndCondition").isMissingNode(), node.path("endsOnAttackOrCast").asBoolean(false));
        }

        /** Its name in lower case, or null when it gives none. */
        public String name() {
            return condition != null && !condition.isBlank() ? condition.trim().toLowerCase() : null;
        }
    }

    /**
     * What a lasting spell's area does by itself at the moments it names (M15): the spell's own effect, such as
     * Moonbeam's save against its damage, or one of its own given as a repeat effect object, such as Hunger of Hadar's
     * cold damage at the start of a turn (PHB p.251).
     *
     * @param when            the moments it happens: ENTER, START_OF_TURN or END_OF_TURN
     * @param effect          what it does: the spell's own effect when it doesn't replace it
     * @param endsSpellOnSave whether a successful save against it ends the spell, as Searing Smite's does (PHB p.274);
     *                        the data's {@code endsSpellOnSave}, false when left out
     */
    public record EffectTrigger(List<SpellTrigger> when, RepeatEffect effect, boolean endsSpellOnSave) {

        public EffectTrigger {
            when = listOf(when);
        }

        /** A trigger whose save, if any, leaves the spell going. */
        public EffectTrigger(List<SpellTrigger> when, RepeatEffect effect) {
            this(when, effect, false);
        }
    }

    /**
     * What a lasting effect has to spend (M15), such as Melf's Minute Meteors' six meteors, two more for each slot
     * level above 3rd, one or two of them each time (XGE p.161).
     *
     * @param count      how many it has at the spell's own level
     * @param perSlotLevel how many more for each slot level above it
     * @param maxPerUse  the most one use may spend
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EffectUses(Integer count, Integer perSlotLevel, Integer maxPerUse) {

        /** How many it has when cast with a slot of slotLevel for a spell of spellLevel. */
        public int at(int spellLevel, int slotLevel) {
            int base = count != null ? count : 0;
            int per = perSlotLevel != null ? perSlotLevel : 0;
            return base + per * Math.max(0, slotLevel - spellLevel);
        }

        /** The most one use may spend, at least one. */
        public int maxEachUse() {
            return maxPerUse != null && maxPerUse > 0 ? maxPerUse : 1;
        }
    }

    // The triggers the data lists: each an object naming its moments, which replaces the spell's own effect when it
    // gives a delivery, damage or healing of its own, and may say a successful save ends the spell. A moment other than
    // the three, or an endsSpellOnSave that isn't true or false, makes the template unreadable
    private static List<EffectTrigger> effectTriggers(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("effectTriggers is not a list");
        }
        List<EffectTrigger> triggers = new java.util.ArrayList<>();
        for (JsonNode trigger : node) {
            if (!trigger.isObject() || !trigger.path("when").isArray() || trigger.path("when").isEmpty()) {
                throw new IllegalArgumentException("An effect trigger needs a list of moments (when)");
            }
            List<SpellTrigger> when = new java.util.ArrayList<>();
            for (JsonNode moment : trigger.path("when")) {
                try {
                    when.add(SpellTrigger.valueOf(moment.asString("")));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("An unknown effect trigger: " + moment
                            + " (ENTER, START_OF_TURN or END_OF_TURN)");
                }
            }
            boolean replaces = trigger.has("deliveryMethod") || trigger.has("damageDice") || trigger.has("healingDice");
            RepeatEffect effect = replaces ? RepeatEffect.of(trigger)
                    : new RepeatEffect(false, null, null, null, null, null, null, null, false, null);
            JsonNode ends = trigger.path("endsSpellOnSave");
            if (!ends.isMissingNode() && !ends.isNull() && !ends.isBoolean()) {
                throw new IllegalArgumentException("endsSpellOnSave is not true or false");
            }
            triggers.add(new EffectTrigger(when, effect, ends.asBoolean(false)));
        }
        return triggers;
    }

    private static SpellTemplate empty(JsonNode json, String unreadable) {
        return new SpellTemplate(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, json, unreadable);
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
