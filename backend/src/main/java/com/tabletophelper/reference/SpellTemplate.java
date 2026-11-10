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
            SpellTemplate template = new SpellTemplate(read(json, "spellLevel", Integer.class),
                    read(json, "concentration", Boolean.class),
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
            // The 4th-level spells' keys (D185) are read when used; reading them now makes a bad one unreadable here
            template.delayedDamage();
            template.retaliation();
            template.durationRounds(Integer.MAX_VALUE);
            // And the 5th-level spells' keys (D187)
            template.lifeDrain();
            template.countedSaves();
            template.contest();
            template.hitThreshold();
            template.repeatConditions();
            // And the 7th-level spells' keys (D189)
            template.maxTargetHp();
            template.hpThresholdEffects();
            template.banishes();
            template.rayTable();
            template.detonation();
            template.raisesOnKill();
            template.conditionsByOption();
            template.followUpSave();
            // And the 8th- and 9th-level spells' keys (D190)
            template.unaffectedAtOrBelow();
            template.escapeCheck();
            template.roundReminders();
            template.castRoll();
            template.healingPool();
            template.castsSpellUpTo();
            template.critRange();
            template.critDamageDice();
            // And the keys that close the high-level gaps (D195)
            template.concentrationSave();
            template.layers();
            template.roundEffects();
            // And the low-level spells' keys (D199)
            template.heldObjectSave();
            template.attackAdvantageVs();
            if (json.path("tempHitPoints").path("dice").isString()) {
                checkedDice(json.path("tempHitPoints").path("dice").asString());
            }
            return template;
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

    /**
     * The damage the spell deals in one roll (D185): its first effect with damage dice, and every later one marked
     * {@code combined}, each of its own type, as Ice Storm's 2d8 bludgeoning and 4d6 cold (PHB p.252). Empty when it
     * deals none.
     */
    public List<Part> damageEffects() {
        Optional<Part> first = firstDamageEffect();
        if (first.isEmpty()) {
            return List.of();
        }
        List<Part> parts = new java.util.ArrayList<>(List.of(first.get()));
        effects.stream().filter(effect -> effect != first.get() && effect.damageDice() != null && effect.combined())
                .forEach(parts::add);
        return List.copyOf(parts);
    }

    // ── Keys the 4th-level spells added (D185), read from the stored JSON ──

    /**
     * Damage a creature takes later, as Vitriolic Sphere's 5d4 acid at the end of its next turn after a failed save (XGE
     * p.170) or Melf's Acid Arrow's 2d4 after a hit (PHB p.259): the template's {@code delayedDamage}; null for none.
     *
     * @param on            the outcome that brings it: FAILED_SAVE or HIT
     * @param upcastScaling what each slot level above the spell's own adds, or null
     */
    public record DelayedDamage(String damageDice, String damageType, String on, Upcast upcastScaling) {
    }

    /** The template's delayed damage, or null when it has none. */
    public DelayedDamage delayedDamage() {
        JsonNode node = json != null ? json.get("delayedDamage") : null;
        if (node == null || !node.isObject()) {
            return null;
        }
        return new DelayedDamage(text(node, "damageDice"), text(node, "damageType"),
                text(node, "on") != null ? text(node, "on") : "FAILED_SAVE", read(node, "upcastScaling", Upcast.class));
    }

    /**
     * The total damage its Spell Effect deals before it ends, as Guardian of Faith's 60 (PHB p.246); null for no limit.
     */
    public Integer damageBudget() {
        JsonNode node = json != null ? json.get("damageBudget") : null;
        return node != null && node.isInt() && node.asInt(0) > 0 ? node.asInt(0) : null;
    }

    /**
     * Damage its Spell Effect deals back to a creature that hits the creature it protects with a melee attack, as Fire
     * Shield's 2d8 (PHB p.242) or Shadow of Moil's (XGE p.164).
     *
     * @param damageDice     the dice
     * @param damageType     its type, or null when the cast option names it
     * @param typeByOption   the type for each cast option, as Fire Shield's warm (fire) and chill (cold)
     */
    public record Retaliation(String damageDice, String damageType, Map<String, String> typeByOption,
                              String saveAbility, List<String> conditions, List<String> attackerTypes) {

        /** A retaliation that only deals damage, as Fire Shield's. */
        public Retaliation(String damageDice, String damageType, Map<String, String> typeByOption) {
            this(damageDice, damageType, typeByOption, null, List.of(), List.of());
        }

        /** The type the option gives, or the fixed one. */
        public String typeFor(String option) {
            if (option != null && typeByOption != null) {
                for (Map.Entry<String, String> entry : typeByOption.entrySet()) {
                    if (entry.getKey().equalsIgnoreCase(option)) {
                        return entry.getValue();
                    }
                }
            }
            return damageType;
        }
    }

    /** The template's retaliation, or null when it has none. */
    public Retaliation retaliation() {
        JsonNode node = json != null ? json.get("retaliation") : null;
        if (node == null || !node.isObject() || (text(node, "damageDice") == null && text(node, "saveAbility") == null)) {
            return null;
        }
        if (text(node, "damageDice") != null) {
            checkDice(text(node, "damageDice"));
        }
        Map<String, String> byOption = new LinkedHashMap<>();
        JsonNode types = node.path("damageTypeByOption");
        if (types.isObject()) {
            for (Map.Entry<String, JsonNode> entry : types.properties()) {
                if (entry.getValue().isString()) {
                    byOption.put(entry.getKey(), entry.getValue().asString(""));
                }
            }
        }
        // A save the attacker makes instead, with the conditions a failure gives, for attackers of the types named only,
        // as Holy Aura's blinding of a fiend or undead (PHB p.250, D195)
        List<String> conditions = new java.util.ArrayList<>();
        node.path("conditionsInflicted").forEach(entry -> {
            if (entry.isString()) {
                conditions.add(entry.asString(""));
            }
        });
        List<String> attackerTypes = new java.util.ArrayList<>();
        node.path("attackerTypes").forEach(entry -> {
            if (entry.isString()) {
                attackerTypes.add(entry.asString(""));
            }
        });
        return new Retaliation(text(node, "damageDice"), text(node, "damageType"), Map.copyOf(byOption),
                text(node, "saveAbility"), List.copyOf(conditions), List.copyOf(attackerTypes));
    }

    /**
     * The ability of the save each creature the spell reaches makes, when it is concentrating, to keep its concentration,
     * as Earthquake's Constitution save on the cast and each later use (PHB p.236, D195): the template's
     * {@code concentrationSave}, or null for none.
     */
    public String concentrationSave() {
        return json != null ? text(json, "concentrationSave") : null;
    }

    /**
     * Layers a creature passing through the spell meets in order, each with its own save, as Prismatic Wall's seven
     * (PHB p.267, D195): the rows read as {@link #rayTable()}'s, their roll giving the order.
     *
     * @param saveAbility the ability of each layer's save
     * @param rows        the layers, in order
     */
    public record Layers(String saveAbility, List<Ray> rows) {
    }

    /**
     * What a use of the spell's effect deals in one round of the spell, the cast's round being 1, as Storm of Vengeance's
     * acid rain in round 2 and its six lightning bolts in round 3 (PHB p.279), or Tsunami's wall, a d10 weaker each
     * round (PHB p.284) (D195).
     *
     * @param damageDice  the damage
     * @param damageType  its type
     * @param saveAbility the save against it, or null for none
     * @param halfOnSave  whether a success halves it; otherwise a success prevents it
     * @param maxTargets  how many creatures one use can reach, or null for no limit
     */
    public record RoundEffect(String damageDice, String damageType, String saveAbility, boolean halfOnSave,
                              Integer maxTargets) {
    }

    /** The template's {@code roundEffects}, by round; empty for none (D195). */
    public Map<Integer, RoundEffect> roundEffects() {
        JsonNode node = json != null ? json.get("roundEffects") : null;
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("roundEffects is not an object");
        }
        Map<Integer, RoundEffect> effects = new java.util.TreeMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            int round;
            try {
                round = Integer.parseInt(entry.getKey().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("roundEffects needs round numbers as keys, not " + entry.getKey());
            }
            JsonNode value = entry.getValue();
            String dice = text(value, "damageDice");
            if (dice == null) {
                throw new IllegalArgumentException("Each of roundEffects needs its damageDice");
            }
            checkDice(dice);
            Integer max = value.path("maxTargets").isInt() ? value.path("maxTargets").asInt() : null;
            effects.put(round, new RoundEffect(dice, text(value, "damageType"), text(value, "saveAbility"),
                    value.path("halfOnSave").asBoolean(false), max));
        }
        return Collections.unmodifiableMap(effects);
    }

    /** The template's {@code layers}, or null for none (D195). */
    public Layers layers() {
        JsonNode node = json != null ? json.get("layers") : null;
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject() || text(node, "saveAbility") == null || !node.path("rows").isArray()) {
            throw new IllegalArgumentException("layers needs a saveAbility and rows");
        }
        return new Layers(text(node, "saveAbility"), rays(node.get("rows"), "layers"));
    }

    /**
     * How many rounds it lasts when cast with a slot of slotLevel: the template's {@code durationBySlot} entry for the
     * highest slot level it names at or below slotLevel, as Dominate Beast's 10 minutes at 5th, an hour at 6th and 8
     * hours at 7th (PHB p.234); else its durationRounds.
     */
    public Integer durationRounds(int slotLevel) {
        JsonNode node = json != null ? json.get("durationBySlot") : null;
        Integer rounds = durationRounds;
        if (node != null && node.isObject()) {
            int best = -1;
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                try {
                    int level = Integer.parseInt(entry.getKey().trim());
                    if (level <= slotLevel && level > best && entry.getValue().isInt()) {
                        best = level;
                        rounds = entry.getValue().asInt(0);
                    }
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("durationBySlot needs slot levels as keys, not " + entry.getKey());
                }
            }
        }
        return rounds;
    }

    /**
     * The dice of temporary hit points the cast rolls for each creature it affects, for the option chosen when the
     * template's {@code tempHitPoints} names one, as Enhance Ability's Bear's Endurance's 2d6 (PHB p.237, D199); null
     * for none.
     */
    public String tempHitPointsDice(String option) {
        JsonNode node = json != null ? json.get("tempHitPoints") : null;
        if (node == null || !node.isObject() || !node.path("dice").isString()) {
            return null;
        }
        String wanted = text(node, "option");
        if (wanted != null && !wanted.equalsIgnoreCase(option)) {
            return null;
        }
        return checkedDice(node.path("dice").asString());
    }

    // Dice the data gives, which must be rolled; an IllegalArgumentException for dice that can't, so the template is
    // unreadable rather than failing a load
    private static String checkedDice(String dice) {
        try {
            com.tabletophelper.rules.Dice.parse(dice);
            return dice;
        } catch (com.tabletophelper.shared.ValidationException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /**
     * The save a creature makes each time it takes the spell's damage while holding the object the spell heats, as
     * Heat Metal's Constitution save (PHB p.250, D199): the template's {@code heldObjectSave}, with the cast option for
     * an object it can't drop, as worn armour, and the Ongoing Modifiers it has while it keeps the object.
     *
     * @param ability    the save's ability
     * @param wornOption the cast option for an object the creature can't drop, so no save keeps the modifiers off it
     * @param modifiers  what it has while it keeps the object, as disadvantage on attack rolls and ability checks until
     *                   the start of the caster's next turn
     */
    public record HeldObjectSave(String ability, String wornOption, List<ModifierDefinition> modifiers) {

        public HeldObjectSave {
            modifiers = listOf(modifiers);
        }
    }

    private static final TypeReference<List<ModifierDefinition>> MODIFIERS = new TypeReference<>() {
    };

    /** The template's {@code heldObjectSave} (D199); null for none. */
    public HeldObjectSave heldObjectSave() {
        JsonNode node = json != null ? json.get("heldObjectSave") : null;
        if (node == null || !node.isObject()) {
            return null;
        }
        String ability = text(node, "ability");
        if (ability == null) {
            throw new IllegalArgumentException("heldObjectSave needs the save's ability");
        }
        return new HeldObjectSave(ability, text(node, "wornOption"),
                READER.convertValue(node.get("modifiers"), MODIFIERS));
    }

    /**
     * What gives the spell's attack advantage against a target (D199): "METAL_ARMOUR" for one wearing armour made of
     * metal, as Shocking Grasp's (PHB p.275); null for nothing.
     */
    public String attackAdvantageVs() {
        String against = json != null ? text(json, "attackAdvantageVs") : null;
        if (against != null && !METAL_ARMOUR.equals(against)) {
            throw new IllegalArgumentException("attackAdvantageVs can only be " + METAL_ARMOUR + ", not " + against);
        }
        return against;
    }

    /**
     * Whether a creature the caster or its companions are fighting has advantage on the spell's save, as against Enthrall
     * (PHB p.238, D199): the template's {@code foesSaveWithAdvantage}. In an encounter that is a creature of the other
     * side from the caster's.
     */
    public boolean foesSaveWithAdvantage() {
        return json != null && json.path("foesSaveWithAdvantage").asBoolean(false);
    }

    /** {@link #attackAdvantageVs()} against a target in metal armour (D199). */
    public static final String METAL_ARMOUR = "METAL_ARMOUR";

    /**
     * The temporary hit points the cast gives each creature it affects, for the option chosen when the template's
     * {@code tempHitPoints} names one, as Guardian of Nature's Great Tree's 10 (XGE p.157); null for none.
     */
    public Integer tempHitPoints(String option) {
        JsonNode node = json != null ? json.get("tempHitPoints") : null;
        if (node == null || !node.isObject() || !node.path("flat").isInt()) {
            return null;
        }
        String wanted = text(node, "option");
        if (wanted != null && !wanted.equalsIgnoreCase(option)) {
            return null;
        }
        return node.path("flat").asInt(0);
    }

    /** The creature types it can target alone, as Dominate Beast's beast (PHB p.234); empty for any. */
    public List<String> targetTypes() {
        return texts("targetTypes");
    }

    /** The creature types it has no effect on, as Blight's undead and constructs (PHB p.219); empty for none. */
    public List<String> noEffectOn() {
        return texts("noEffectOn");
    }

    /**
     * The creature types that save with disadvantage and take the most damage its dice can deal, as a plant creature
     * against Blight (PHB p.219); empty for none.
     */
    public List<String> maximumDamageTo() {
        return texts("maximumDamageTo");
    }

    /**
     * A reminder logged at the start of each of its caster's turns while its Spell Effect lasts, as Mordenkainen's
     * Faithful Hound's bite (PHB p.261); null for none.
     */
    public String turnReminder() {
        return json != null ? text(json, "turnReminder") : null;
    }

    // ── Keys the 5th-level spells added (D187), read from the stored JSON ──

    /** The cast option that lowers the target's exhaustion by one level instead of ending a condition (D187). */
    public static final String EXHAUSTION_OPTION = "exhaustion";

    /**
     * The conditions a cast ends on each creature it reaches, as Lesser Restoration's blinded, deafened, paralyzed or
     * poisoned (PHB p.255) and Greater Restoration's charmed or petrified (PHB p.246): the template's
     * {@code removesConditions}. With cast options, only the chosen one is ended; {@link #EXHAUSTION_OPTION} lowers
     * exhaustion by one level instead (D183). Empty for none.
     */
    public List<String> removesConditions() {
        return texts("removesConditions");
    }

    /**
     * Whether a creature that fails the save comes under its caster's control while the spell lasts, as Dominate
     * Person's (PHB p.235): the template's {@code controlTransfer} (D187).
     */
    public boolean controlTransfer() {
        return json != null && json.path("controlTransfer").asBoolean(false);
    }

    /**
     * The share of the damage it deals that its caster regains as hit points, as Enervation's and Vampiric Touch's half
     * (XGE p.155, PHB p.285): the template's {@code lifeDrain}, such as 0.5; 0 for none (D187).
     */
    public double lifeDrain() {
        JsonNode node = json != null ? json.get("lifeDrain") : null;
        if (node == null || node.isNull()) {
            return 0;
        }
        if (!node.isNumber() || node.asDouble(0) < 0 || node.asDouble(0) > 1) {
            throw new IllegalArgumentException("lifeDrain is not a share between 0 and 1");
        }
        return node.asDouble(0);
    }

    /**
     * Whether the spell ends when every creature its cast reaches succeeds on the first save, as Enervation and
     * Immolation do (XGE p.155, p.158): the template's {@code endsOnInitialSave} (D187).
     */
    public boolean endsOnInitialSave() {
        return json != null && json.path("endsOnInitialSave").asBoolean(false);
    }

    /**
     * Whether using its effect again ends the spell, as Holy Weapon's burst does (XGE p.157): the template's
     * {@code repeatEndsSpell} (D187). The conditions that use gives then keep their own durations.
     */
    public boolean repeatEndsSpell() {
        return json != null && json.path("repeatEndsSpell").asBoolean(false);
    }

    /**
     * The conditions its repeat effect gives when it replaces the spell's own, as Holy Weapon's burst blinds (XGE
     * p.157): the repeat effect's own {@code conditionsInflicted} (D187); empty for none.
     */
    public List<ConditionInflicted> repeatConditions() {
        JsonNode node = json != null ? json.path("repeatEffect").path("conditionsInflicted") : null;
        return node != null && node.isArray() ? READER.convertValue(node, CONDITIONS) : List.of();
    }

    /**
     * A tally of saves each creature it reaches makes at the end of each of its turns, as Contagion's three successes
     * or three failures (PHB p.227, D187).
     *
     * @param ability   the save's ability
     * @param successes how many successes end the spell on the creature
     * @param failures  how many failures make the spell's lasting effect take hold
     * @param onFailures what the log reminds the DM of when the failures are reached, such as Contagion's disease
     * @param endsConditions the spell's conditions that end on either outcome, as Contagion's poisoned
     * @param failureCondition the condition the failures give for good, as Prismatic Spray's indigo ray's petrified
     *                       (PHB p.267, D189); null for none
     */
    public record CountedSaves(String ability, int successes, int failures, String onFailures,
                               List<String> endsConditions, String failureCondition) {

        /** Counted saves whose failures give no condition (D187). */
        public CountedSaves(String ability, int successes, int failures, String onFailures,
                            List<String> endsConditions) {
            this(ability, successes, failures, onFailures, endsConditions, null);
        }
    }

    /** The template's counted saves, or null for none. */
    public CountedSaves countedSaves() {
        return countedSaves(json != null ? json.get("countedSaves") : null);
    }

    // Counted saves as an object gives them, or null for none
    private static CountedSaves countedSaves(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        if (text(node, "ability") == null || !node.path("successes").isInt() || !node.path("failures").isInt()) {
            throw new IllegalArgumentException("countedSaves needs an ability and numbers of successes and failures");
        }
        List<String> ends = new java.util.ArrayList<>();
        node.path("endsConditions").forEach(each -> {
            if (each.isString()) ends.add(each.asString("").toLowerCase(java.util.Locale.ROOT));
        });
        String failureCondition = text(node, "failureCondition");
        return new CountedSaves(text(node, "ability"), node.path("successes").asInt(3), node.path("failures").asInt(3),
                text(node, "onFailures"), List.copyOf(ends),
                failureCondition != null ? failureCondition.toLowerCase(java.util.Locale.ROOT) : null);
    }

    /**
     * An ability check contest its cast and each use again roll against each target, as Telekinesis's spellcasting
     * ability check against the creature's Strength check (PHB p.280, D187): the caster's win gives the condition until
     * the end of the caster's next turn.
     *
     * @param targetAbility the target's ability, such as STR
     * @param condition     the condition a win gives, or null for none
     * @param winNote       what the log says a win does, such as "moves it up to 30 feet"
     */
    public record Contest(String targetAbility, String condition, String winNote) {
    }

    /** The template's contest, or null for none. */
    public Contest contest() {
        JsonNode node = json != null ? json.get("contest") : null;
        if (node == null || !node.isObject()) {
            return null;
        }
        if (text(node, "targetAbility") == null) {
            throw new IllegalArgumentException("A contest needs the target's ability");
        }
        return new Contest(text(node, "targetAbility"), text(node, "condition"), text(node, "winNote"));
    }

    /**
     * A hit-point threshold a one-shot weapon rider checks after its hit, as Banishing Smite's banishing a creature left
     * at 50 hit points or fewer (PHB p.216, D187).
     *
     * @param hp        the threshold, inclusive
     * @param condition the condition the creature then gets, held by the spell's concentration
     */
    public record HitThreshold(int hp, String condition) {
    }

    /** The template's hit-point threshold, or null for none. */
    public HitThreshold hitThreshold() {
        JsonNode node = json != null ? json.get("hitThreshold") : null;
        if (node == null || !node.isObject()) {
            return null;
        }
        if (!node.path("hp").isInt() || text(node, "condition") == null) {
            throw new IllegalArgumentException("hitThreshold needs hp and a condition");
        }
        return new HitThreshold(node.path("hp").asInt(0), text(node, "condition").toLowerCase(java.util.Locale.ROOT));
    }

    // ── Keys the 7th-level spells added (D189), read from the stored JSON ──

    /**
     * The conditions that leave a creature unaffected, as a deafened creature can't hear Divine Word (PHB p.234): the
     * template's {@code noEffectOnConditions}; empty for none.
     */
    public List<String> noEffectOnConditions() {
        return texts("noEffectOnConditions");
    }

    /**
     * The most current hit points a creature may have for the spell to affect it, as Power Word Pain's 100 (XGE p.163):
     * the template's {@code maxTargetHp}; a creature with more is unaffected, with no roll. Null for no limit.
     */
    public Integer maxTargetHp() {
        JsonNode node = json != null ? json.get("maxTargetHp") : null;
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isInt() || node.asInt(0) < 0) {
            throw new IllegalArgumentException("maxTargetHp is not a whole number of hit points");
        }
        return node.asInt(0);
    }

    /**
     * One band of an effect that depends on a creature's current hit points, as Divine Word's (PHB p.234).
     *
     * @param maxHp         the band's top, inclusive
     * @param conditions    the conditions a creature in the band gets, each with its own duration
     * @param killsOutright whether a creature in the band dies outright, with no damage dealt
     */
    public record HpBand(int maxHp, List<ConditionInflicted> conditions, boolean killsOutright) {
    }

    /**
     * The bands a creature the cast reaches (a failed save, or no roll) falls into by its current hit points, as Divine
     * Word's 50, 40, 30 and 20 (PHB p.234): the template's {@code hpThresholdEffects}, lowest top first. A creature
     * takes the lowest band it fits, and one above every band is unaffected. Empty for none (D189).
     */
    public List<HpBand> hpThresholdEffects() {
        JsonNode node = json != null ? json.get("hpThresholdEffects") : null;
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("hpThresholdEffects is not a list");
        }
        List<HpBand> bands = new java.util.ArrayList<>();
        for (JsonNode band : node) {
            if (!band.path("maxHp").isInt()) {
                throw new IllegalArgumentException("Each of hpThresholdEffects needs maxHp");
            }
            List<ConditionInflicted> conditions = band.path("conditionsInflicted").isArray()
                    ? READER.convertValue(band.get("conditionsInflicted"), CONDITIONS) : List.of();
            bands.add(new HpBand(band.path("maxHp").asInt(0), listOf(conditions),
                    band.path("killsOutright").asBoolean(false)));
        }
        bands.sort(java.util.Comparator.comparingInt(HpBand::maxHp));
        return List.copyOf(bands);
    }

    /**
     * Creatures of these types that the cast reaches are sent away, as Divine Word's celestials, elementals, fey and
     * fiends, which can't return for 24 hours (PHB p.234).
     *
     * @param types          the creature types
     * @param durationRounds how long they stay away; null until the DM brings them back
     */
    public record Banishes(List<String> types, Integer durationRounds) {
    }

    /** The template's {@code banishes}, or null for none (D189). */
    public Banishes banishes() {
        JsonNode node = json != null ? json.get("banishes") : null;
        if (node == null || !node.isObject()) {
            return null;
        }
        List<String> types = new java.util.ArrayList<>();
        node.path("types").forEach(each -> {
            if (each.isString()) types.add(each.asString("").toLowerCase(java.util.Locale.ROOT));
        });
        if (types.isEmpty()) {
            throw new IllegalArgumentException("banishes needs a list of creature types");
        }
        return new Banishes(List.copyOf(types), node.path("durationRounds").isInt()
                ? node.path("durationRounds").asInt(0) : null);
    }

    /**
     * One row of a table each creature the cast reaches rolls on, as Prismatic Spray's d8 (PHB p.267).
     *
     * @param roll          the die face
     * @param color         its name, for the log, such as "red"
     * @param damageDice    the damage a failed save deals (half on a success, when the spell halves), or null
     * @param damageType    its type
     * @param conditions    the conditions a failed save gives
     * @param countedSaves  the saves a creature with them counts at the end of its turns, or null
     * @param reminder      what the log reminds the DM of for a creature that failed, or null
     * @param rollsTwice    whether the creature is struck by two rows instead, rolled again with this one rerolled
     */
    public record Ray(int roll, String color, String damageDice, String damageType,
                      List<ConditionInflicted> conditions, CountedSaves countedSaves, String reminder,
                      boolean rollsTwice) {
    }

    /** The template's {@code rayTable}, in roll order; empty for none (D189). */
    public List<Ray> rayTable() {
        JsonNode node = json != null ? json.get("rayTable") : null;
        if (node == null || node.isNull()) {
            return List.of();
        }
        return rays(node, "rayTable");
    }

    // Rows of a ray table or of layers, sorted by roll
    private static List<Ray> rays(JsonNode node, String key) {
        if (!node.isArray()) {
            throw new IllegalArgumentException(key + " is not a list");
        }
        List<Ray> rays = new java.util.ArrayList<>();
        for (JsonNode ray : node) {
            if (!ray.path("roll").isInt()) {
                throw new IllegalArgumentException("Each row of " + key + " needs its roll");
            }
            List<ConditionInflicted> conditions = ray.path("conditionsInflicted").isArray()
                    ? READER.convertValue(ray.get("conditionsInflicted"), CONDITIONS) : List.of();
            String dice = text(ray, "damageDice");
            if (dice != null) {
                com.tabletophelper.rules.Dice.parse(dice.trim());
            }
            rays.add(new Ray(ray.path("roll").asInt(0), text(ray, "color"), dice, text(ray, "damageType"),
                    listOf(conditions), countedSaves(ray.get("countedSaves")), text(ray, "reminder"),
                    ray.path("rollsTwice").asBoolean(false)));
        }
        rays.sort(java.util.Comparator.comparingInt(Ray::roll));
        return List.copyOf(rays);
    }

    /**
     * Damage the Spell Effect deals when it ends, however it ends, to the creatures it marked when cast, as Delayed Blast
     * Fireball's bead (PHB p.230).
     *
     * @param damageDice    the damage at the spell's own level, such as "12d6"
     * @param growsEachTurn the dice it gains at the end of each of its caster's turns before it ends, such as "1d6"
     * @param upcastScaling what each slot level above the spell's own adds to the starting damage
     */
    public record Detonation(String damageDice, String damageType, String saveAbility, boolean halfOnSave,
                             String growsEachTurn, Upcast upcastScaling) {
    }

    /** The template's {@code detonation}, or null for none (D189). */
    public Detonation detonation() {
        JsonNode node = json != null ? json.get("detonation") : null;
        if (node == null || !node.isObject()) {
            return null;
        }
        if (text(node, "damageDice") == null || text(node, "saveAbility") == null) {
            throw new IllegalArgumentException("A detonation needs damageDice and saveAbility");
        }
        com.tabletophelper.rules.Dice.parse(text(node, "damageDice").trim());
        if (text(node, "growsEachTurn") != null) {
            com.tabletophelper.rules.Dice.parse(text(node, "growsEachTurn").trim());
        }
        return new Detonation(text(node, "damageDice"), text(node, "damageType"), text(node, "saveAbility"),
                node.path("halfOnSave").asBoolean(false), text(node, "growsEachTurn"),
                read(node, "upcastScaling", Upcast.class));
    }

    /**
     * A creature the cast kills rises at the start of its caster's next turn as a creature under the caster's command,
     * as Finger of Death's humanoid rising as a zombie (PHB p.241).
     *
     * @param creatureTypes the types that rise
     * @param form          the stat block it rises as, such as "Zombie"
     */
    public record RaisesOnKill(List<String> creatureTypes, String form) {
    }

    /** The template's {@code raisesOnKill}, or null for none (D189). */
    public RaisesOnKill raisesOnKill() {
        JsonNode node = json != null ? json.get("raisesOnKill") : null;
        if (node == null || !node.isObject()) {
            return null;
        }
        if (text(node, "form") == null) {
            throw new IllegalArgumentException("raisesOnKill needs a form");
        }
        List<String> types = new java.util.ArrayList<>();
        node.path("creatureTypes").forEach(each -> {
            if (each.isString()) types.add(each.asString("").toLowerCase(java.util.Locale.ROOT));
        });
        return new RaisesOnKill(List.copyOf(types), text(node, "form"));
    }

    /**
     * The conditions each cast option gives in place of the template's list, as Forcecage's cage and box (PHB p.243):
     * the template's {@code conditionsByOption}, the names keyed by option in lower case; empty for none (D189). An
     * option's list names its conditions, or gives them whole with how they end, as Eyebite's asleep (PHB p.238, D188).
     */
    public Map<String, List<String>> conditionsByOption() {
        Map<String, List<String>> byOption = new LinkedHashMap<>();
        optionConditions().forEach((option, conditions) -> byOption.put(option, conditions.stream()
                .map(ConditionInflicted::name).filter(Objects::nonNull).toList()));
        return Map.copyOf(byOption);
    }

    /**
     * The conditions the cast gives for the option chosen (D188, D189): the option's own list from
     * {@code conditionsByOption}, an entry named alone taking how it ends from the template's conditionsInflicted, as
     * Forcecage's box (PHB p.243), and one given whole keeping its own, as Eyebite's asleep (PHB p.238); the template's
     * conditionsInflicted for no option, or one the key doesn't list.
     */
    public List<ConditionInflicted> conditionsFor(String option) {
        List<ConditionInflicted> listed = option == null ? null
                : optionConditions().get(option.toLowerCase(java.util.Locale.ROOT));
        return listed != null ? listed : conditionsInflicted;
    }

    // The conditions of each option, keyed in lower case; an entry named alone is the template's own of that name
    private Map<String, List<ConditionInflicted>> optionConditions() {
        JsonNode node = json != null ? json.get("conditionsByOption") : null;
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("conditionsByOption is not an object");
        }
        Map<String, List<ConditionInflicted>> byOption = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if (!entry.getValue().isArray()) {
                throw new IllegalArgumentException("conditionsByOption's " + entry.getKey() + " is not a list");
            }
            List<ConditionInflicted> conditions = new java.util.ArrayList<>();
            for (JsonNode each : entry.getValue()) {
                ConditionInflicted read = ConditionInflicted.of(each);
                ConditionInflicted own = each.isString() ? conditionsInflicted.stream()
                        .filter(c -> c.name() != null && c.name().equals(read.name())).findFirst().orElse(null) : null;
                if (read.name() != null) {
                    conditions.add(own != null ? own : read);
                }
            }
            byOption.put(entry.getKey().toLowerCase(java.util.Locale.ROOT), List.copyOf(conditions));
        }
        return Map.copyOf(byOption);
    }

    /**
     * A second roll after a hit, as Plane Shift's Charisma save after its melee spell attack (PHB p.266): the creature
     * hit makes the save against the caster's spell save DC, and on a failure gets the conditions.
     *
     * @param option the cast option it belongs to, or null for every cast
     * @param on     what brings it: HIT, as Plane Shift's, or FAILED_SAVE, as Whirlwind's Strength save after a failed
     *               Dexterity save (XGE p.171)
     */
    public record FollowUpSave(String ability, List<ConditionInflicted> conditions, String option, String on) {
    }

    /** The template's {@code followUpSave}, or null for none (D189). */
    public FollowUpSave followUpSave() {
        JsonNode node = json != null ? json.get("followUpSave") : null;
        if (node == null || !node.isObject()) {
            return null;
        }
        if (text(node, "ability") == null) {
            throw new IllegalArgumentException("followUpSave needs an ability");
        }
        List<ConditionInflicted> conditions = node.path("conditionsInflicted").isArray()
                ? READER.convertValue(node.get("conditionsInflicted"), CONDITIONS) : List.of();
        return new FollowUpSave(text(node, "ability"), listOf(conditions), text(node, "option"),
                text(node, "on") != null ? text(node, "on") : "HIT");
    }

    // ── Keys the 6th-level spells added (D188), read from the stored JSON ──

    /** The fewest hit points its damage can leave a creature with, as Harm's 1 (PHB p.249); 0 for no floor. */
    public int damageFloor() {
        JsonNode node = json != null ? json.get("damageFloor") : null;
        return node != null && node.isInt() ? Math.max(0, node.asInt(0)) : 0;
    }

    /**
     * Whether a creature its damage leaves at 0 hit points is disintegrated, dead with no death saves, as Disintegrate's
     * (PHB p.233).
     */
    public boolean disintegrates() {
        return json != null && json.path("disintegrates").asBoolean(false);
    }

    /**
     * The words the log adds when the spell's damage kills a creature, as Psychic Scream's "...and its head explodes!"
     * (XGE p.163): the template's {@code killNote} object's text, or null. Flavour only; it changes nothing.
     */
    public String killNote() {
        JsonNode node = json != null ? json.path("killNote").path("text") : null;
        return node != null && node.isString() && !node.asString("").isBlank() ? node.asString() : null;
    }

    /** Whether the kill note is only for a creature with a head: the {@code killNote} object's needsHead. */
    public boolean killNoteNeedsHead() {
        return json != null && json.path("killNote").path("needsHead").asBoolean(false);
    }

    /**
     * Whether a creature would want to keep the spell off (D198), as a rakshasa with Limited Magic Immunity "unless it
     * wishes to be" affected (MM p.257) would an ally's Fireball but not its Heal or Bless: it calls for a save or an
     * attack roll, or deals damage; or, for a spell that can target enemies, gives conditions or can target only them, as
     * Hex and Hunter's Mark can. A spell only for allies is cast on willing creatures, so its conditions, as
     * Invisibility's and Sanctuary's, are helpful, as are healing and buffs.
     */
    public boolean harmful() {
        if (json == null) {
            return false;
        }
        String delivery = json.path("deliveryMethod").asString("");
        if ("SAVING_THROW".equals(delivery) || "SPELL_ATTACK".equals(delivery)) {
            return true;
        }
        for (JsonNode effect : json.path("effects")) {
            if ("DAMAGE".equals(effect.path("effectType").asString(""))) {
                return true;
            }
        }
        boolean allies = json.path("canTargetAllies").asBoolean(false);
        boolean enemies = json.path("canTargetEnemies").asBoolean(false);
        if (allies && !enemies) {
            return false;
        }
        return !json.path("conditionsInflicted").isEmpty() || enemies && !allies;
    }

    /**
     * Whether a creature that fails its save has its hit point maximum reduced by the damage it took, as Harm's for an
     * hour (PHB p.249): the template's {@code hpMaxReducedByDamage} object.
     */
    public boolean reducesHpMax() {
        return json != null && json.path("hpMaxReducedByDamage").isObject();
    }

    /** How many rounds that reduction lasts: the object's durationRounds, 600 (1 hour) when it doesn't say. */
    public int hpMaxReductionRounds() {
        JsonNode node = json != null ? json.path("hpMaxReducedByDamage").path("durationRounds") : null;
        return node != null && node.isInt() ? node.asInt(600) : 600;
    }

    /**
     * The condition whose immunity makes a creature immune to the spell, as one that can't be charmed is to Mass
     * Suggestion (PHB p.258); null for none.
     */
    public String immuneIfImmuneTo() {
        return json != null ? text(json, "immuneIfImmuneTo") : null;
    }

    /**
     * The condition whose immunity makes a creature succeed on the spell's save automatically, as one that can't be
     * charmed does against Mental Prison (XGE p.161); null for none.
     */
    public String succeedsIfImmuneTo() {
        return json != null ? text(json, "succeedsIfImmuneTo") : null;
    }

    /** The creature types that make its save with disadvantage, as Sunbeam's undead and oozes (PHB p.279). */
    public List<String> saveDisadvantageTypes() {
        return texts("saveDisadvantageTypes");
    }

    /** Whether a successful save still takes all the damage, as against Mental Prison's 5d10 (XGE p.161). */
    public boolean fullDamageOnSave() {
        return json != null && json.path("fullDamageOnSave").asBoolean(false);
    }

    /** Whether its caster can't cast spells while its Spell Effect lasts, as with Tenser's Transformation (XGE p.168). */
    public boolean blocksCasting() {
        return json != null && json.path("blocksCasting").asBoolean(false);
    }

    /**
     * Whether its caster can move its Spell Effect to another creature with a bonus action at any time, as Fizban's
     * Platinum Shield (FTD p.20), not only once its creature drops to 0 hit points.
     */
    public boolean movable() {
        return json != null && json.path("movable").asBoolean(false);
    }

    /**
     * A condition its creature ends by using its action on a save, as Otto's Irresistible Dance's (PHB p.264), whose
     * success ends the spell: the template's {@code actionSave}.
     *
     * @param condition the condition's name
     * @param ability   the save's ability, such as "WIS"
     */
    public record ActionSave(String condition, String ability) {
    }

    /** The template's action save, or null when it has none. */
    public ActionSave actionSave() {
        JsonNode node = json != null ? json.get("actionSave") : null;
        if (node == null || !node.isObject() || text(node, "condition") == null || text(node, "ability") == null) {
            return null;
        }
        return new ActionSave(text(node, "condition"), text(node, "ability"));
    }

    // ── Keys the 8th- and 9th-level spells added (D190), read from the stored JSON ──

    /**
     * An ability score at or below which a creature is unaffected, as Psychic Scream's Intelligence of 2 or lower (XGE
     * p.163).
     */
    public record AbilityFloor(String ability, int max) {
    }

    /** The template's {@code unaffectedAtOrBelow}, or null for none. */
    public AbilityFloor unaffectedAtOrBelow() {
        JsonNode node = json != null ? json.get("unaffectedAtOrBelow") : null;
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject() || text(node, "ability") == null || !node.path("max").isInt()) {
            throw new IllegalArgumentException("unaffectedAtOrBelow needs an ability and a max");
        }
        return new AbilityFloor(text(node, "ability").toUpperCase(java.util.Locale.ROOT), node.path("max").asInt(0));
    }

    /**
     * An ability check a creature its condition holds makes to escape, as Maze's DC 20 Intelligence check (PHB p.258); a
     * success ends the spell.
     *
     * @param autoSucceed names of creatures that escape without a roll, as minotaurs and goristros do
     */
    public record EscapeCheck(String ability, int dc, List<String> autoSucceed) {
    }

    /** The template's {@code escapeCheck}, or null for none. */
    public EscapeCheck escapeCheck() {
        JsonNode node = json != null ? json.get("escapeCheck") : null;
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject() || text(node, "ability") == null || !node.path("dc").isInt()) {
            throw new IllegalArgumentException("escapeCheck needs an ability and a dc");
        }
        List<String> auto = new java.util.ArrayList<>();
        node.path("autoSucceed").forEach(each -> {
            if (each.isString()) auto.add(each.asString("").toLowerCase(java.util.Locale.ROOT));
        });
        return new EscapeCheck(text(node, "ability").toUpperCase(java.util.Locale.ROOT), node.path("dc").asInt(0),
                List.copyOf(auto));
    }

    /**
     * Whether a successful save against its trigger at that moment ends the spell for that creature only, as Weird's
     * (PHB p.288): the trigger's {@code endsForCreatureOnSave}.
     */
    public boolean triggerEndsForCreature(SpellTrigger when) {
        if (json == null || !json.path("effectTriggers").isArray()) {
            return false;
        }
        for (JsonNode trigger : json.path("effectTriggers")) {
            boolean at = false;
            for (JsonNode moment : trigger.path("when")) {
                at |= when.name().equals(moment.asString(""));
            }
            if (at && trigger.path("endsForCreatureOnSave").asBoolean(false)) {
                return true;
            }
        }
        return false;
    }

    /**
     * What the log reminds the table of at the start of its caster's turn in each round of the spell, the cast's round
     * being 1, as Storm of Vengeance's acid rain in round 2 (PHB p.279): the template's {@code roundReminders}, by round;
     * empty for none.
     */
    public Map<Integer, String> roundReminders() {
        JsonNode node = json != null ? json.get("roundReminders") : null;
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<Integer, String> reminders = new java.util.TreeMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            try {
                if (entry.getValue().isString()) {
                    reminders.put(Integer.parseInt(entry.getKey().trim()), entry.getValue().asString(""));
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("roundReminders needs round numbers as keys, not " + entry.getKey());
            }
        }
        return Collections.unmodifiableMap(reminders);
    }

    /**
     * Dice the cast rolls and logs for the table, as Time Stop's 1d4 + 1 turns (PHB p.283).
     *
     * @param extraTurns whether the total is a number of turns its caster takes in a row after this one (D195)
     */
    public record CastRoll(String dice, String note, boolean extraTurns) {

        public CastRoll(String dice, String note) {
            this(dice, note, false);
        }
    }

    /** The template's {@code castRoll}, or null for none. */
    public CastRoll castRoll() {
        JsonNode node = json != null ? json.get("castRoll") : null;
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject() || text(node, "dice") == null) {
            throw new IllegalArgumentException("castRoll needs dice");
        }
        checkDice(text(node, "dice"));
        return new CastRoll(text(node, "dice"), text(node, "note"), node.path("extraTurns").asBoolean(false));
    }

    /**
     * Whether each creature it reaches regains all its hit points, as Power Word Heal's (PHB p.266): the template's
     * {@code healsToFull}.
     */
    public boolean healsToFull() {
        return json != null && json.path("healsToFull").asBoolean(false);
    }

    /**
     * Hit points it divides among the creatures it reaches, as Mass Heal's 700 (PHB p.258): the template's
     * {@code healingPool}; null for none.
     */
    public Integer healingPool() {
        JsonNode node = json != null ? json.get("healingPool") : null;
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isInt() || node.asInt(0) <= 0) {
            throw new IllegalArgumentException("healingPool is not a whole number above 0");
        }
        return node.asInt(0);
    }

    /**
     * The highest level of spell a cast may duplicate, as Wish's 8th (PHB p.288): the template's
     * {@code castsSpell.maxLevel}; null when it duplicates none.
     */
    public Integer castsSpellUpTo() {
        JsonNode node = json != null ? json.get("castsSpell") : null;
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject() || !node.path("maxLevel").isInt()) {
            throw new IllegalArgumentException("castsSpell needs a maxLevel");
        }
        return node.path("maxLevel").asInt(0);
    }

    /**
     * The lowest d20 its spell attack scores a critical hit with, as Blade of Disaster's 18 (TCE p.106): the template's
     * {@code critRange}; 20 when it doesn't say.
     */
    public int critRange() {
        JsonNode node = json != null ? json.get("critRange") : null;
        if (node == null || node.isNull()) {
            return 20;
        }
        if (!node.isInt() || node.asInt(0) < 2 || node.asInt(0) > 20) {
            throw new IllegalArgumentException("critRange is not a d20 face from 2 to 20");
        }
        return node.asInt(0);
    }

    /**
     * The dice its first damage part deals on a critical hit, in place of its doubled dice, as Blade of Disaster's 12d12
     * (TCE p.106): the template's {@code critDamageDice}; null for the doubled dice.
     */
    public String critDamageDice() {
        JsonNode node = json != null ? json.get("critDamageDice") : null;
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw new IllegalArgumentException("critDamageDice is not dice");
        }
        checkDice(node.asString(""));
        return node.asString("");
    }

    // Dice the app can roll, or an IllegalArgumentException naming them
    private static void checkDice(String dice) {
        try {
            com.tabletophelper.rules.Dice.parse(dice.replace(" ", ""));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not dice: " + dice);
        }
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
                       Upcast upcastScaling, List<ModifierDefinition> modifiers, boolean combined) {

        public Part {
            cantripScaling = cantripScaling == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(cantripScaling));
            modifiers = listOf(modifiers);
        }

        /** A part not dealt together with the first (D185). */
        public Part(String damageDice, String damageType, Map<String, String> cantripScaling, Upcast upcastScaling,
                    List<ModifierDefinition> modifiers) {
            this(damageDice, damageType, cantripScaling, upcastScaling, modifiers, false);
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
     * @param note                 a NOTE's words, shown on the creature (D199)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModifierDefinition(OngoingModifier.Kind kind, OngoingModifier.Scope scope, String dice, Integer flat,
                                     RollMode mode, String ability, List<String> damageTypes, boolean weaponOnly,
                                     boolean meleeOnly, boolean oneShot, OngoingModifier.Until until, String requires,
                                     List<String> attackerTypes, String condition, String skill, String option,
                                     ModifierUpcast upcast, boolean spellcastingModifier, boolean plusDex,
                                     String note) {

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
     * @param endsOnDamage      whether it ends for a creature that takes damage, as Hypnotic Pattern's does (PHB p.252)
     * @param saveToEndOnDamage whether a creature that takes damage repeats the save, a success ending the spell, as
     *                          Dominate Beast's target does (PHB p.234)
     * @param damageSaveAdvantage whether that save has advantage, as Tasha's Hideous Laughter's (PHB p.280)
     */
    public record ConditionInflicted(String condition, Integer durationRounds, boolean saveToEndEachTurn,
                                     String saveToEndAbility, boolean conditionalSave, boolean endsOnAttackOrCast,
                                     boolean endsOnDamage, boolean saveToEndOnDamage, boolean damageSaveAdvantage) {

        /** A condition that taking damage doesn't end. */
        public ConditionInflicted(String condition, Integer durationRounds, boolean saveToEndEachTurn,
                                  String saveToEndAbility, boolean conditionalSave, boolean endsOnAttackOrCast) {
            this(condition, durationRounds, saveToEndEachTurn, saveToEndAbility, conditionalSave, endsOnAttackOrCast,
                    false, false, false);
        }

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
                    !node.path("saveToEndCondition").isMissingNode(), node.path("endsOnAttackOrCast").asBoolean(false),
                    node.path("endsOnDamage").asBoolean(false), node.path("saveToEndOnDamage").asBoolean(false),
                    node.path("saveToEndOnDamageAdvantage").asBoolean(false));
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
    public record EffectTrigger(List<SpellTrigger> when, RepeatEffect effect, boolean endsSpellOnSave,
                                List<ConditionInflicted> conditions, String whenConditioned, int exhaustion,
                                boolean addsAffected) {

        public EffectTrigger {
            when = listOf(when);
            conditions = listOf(conditions);
            exhaustion = Math.max(0, exhaustion);
        }

        /** A trigger whose save, if any, leaves the spell going. */
        public EffectTrigger(List<SpellTrigger> when, RepeatEffect effect) {
            this(when, effect, false);
        }

        /** A trigger that gives no conditions or exhaustion (D185). */
        public EffectTrigger(List<SpellTrigger> when, RepeatEffect effect, boolean endsSpellOnSave) {
            this(when, effect, endsSpellOnSave, List.of(), null, 0, false);
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
            // The conditions a creature it reaches gets, as Evard's Black Tentacles' restrained (PHB p.238, D185)
            List<ConditionInflicted> conditions = trigger.path("conditionsInflicted").isArray()
                    ? READER.convertValue(trigger.get("conditionsInflicted"), CONDITIONS) : List.of();
            JsonNode exhaustion = trigger.path("exhaustion");
            if (!exhaustion.isMissingNode() && !exhaustion.isNull() && !exhaustion.isInt()) {
                throw new IllegalArgumentException("A trigger's exhaustion is not a whole number");
            }
            triggers.add(new EffectTrigger(when, effect, ends.asBoolean(false), conditions,
                    text(trigger, "whenConditioned"), exhaustion.asInt(0), trigger.path("addsAffected").asBoolean(false)));
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
