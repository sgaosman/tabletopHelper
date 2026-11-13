package com.tabletophelper.monster;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.JacksonDefaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A monster's action templates, the {@code monsters.action_templates} column (D030, D168): its action lists, the
 * counts that size its legendary pools, and its spellcasting, as records. The records name only the keys the encounter
 * reads; the column's JSON is kept whole beside them, and it is what is stored and what the API returns, so a key the
 * records don't name, such as a stat block's description or a review note, is never lost. The seeded data writes each
 * key the records read one way, except a legendary action's cost: a number, or a range such as "1-3", so it is read
 * as text.
 *
 * @param traits     read only for a multiattack's components, which can name an action in any of the lists
 * @param json       the column's JSON as stored; null only for {@link #NONE}
 * @param unreadable why the JSON doesn't fit the records, or null; the records are then empty, and {@link #of}
 *                   stops a command that needs them with a 409 (D155)
 */
public record MonsterTemplates(List<Action> actions, List<Action> legendaryActions, List<Action> lairActions,
                               List<Action> bonusActions, List<Action> reactions, List<Action> traits,
                               Integer legendaryActionCount, Integer legendaryResistanceCount,
                               Spellcasting spellcasting, List<Spellcasting> additionalSpellcasting, JsonNode json,
                               String unreadable) {

    private static final Logger log = LoggerFactory.getLogger(MonsterTemplates.class);

    // Reads the parts into the records, with the application's Jackson settings. Each record ignores the keys it
    // doesn't name, as the column's mapper does
    private static final ObjectMapper READER = JacksonDefaults.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private static final TypeReference<List<Action>> ACTIONS = new TypeReference<>() {
    };
    private static final TypeReference<List<Spellcasting>> SPELLCASTING_BLOCKS = new TypeReference<>() {
    };

    // A legendary action's cost as a whole number
    private static final Pattern WHOLE = Pattern.compile("^\\s*(\\d{1,2})\\s*$");

    /** A monster without templates: no actions, no counts and no spellcasting. */
    public static final MonsterTemplates NONE =
            new MonsterTemplates(null, null, null, null, null, null, null, null, null, null, null, null);

    public MonsterTemplates {
        actions = listOf(actions);
        legendaryActions = listOf(legendaryActions);
        lairActions = listOf(lairActions);
        bonusActions = listOf(bonusActions);
        reactions = listOf(reactions);
        traits = listOf(traits);
        additionalSpellcasting = listOf(additionalSpellcasting);
    }

    /**
     * The templates of this JSON, which they keep as it is: an object, or an array whose first entry counts, as before
     * R5. Any other JSON has no actions, counts or spellcasting. JSON whose parts don't fit the records is kept too,
     * marked unreadable, so the monster still loads and only a command that needs its templates fails.
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static MonsterTemplates of(JsonNode json) {
        JsonNode root = json != null && json.isArray() ? json.path(0) : json;
        if (root == null || !root.isObject()) {
            return new MonsterTemplates(null, null, null, null, null, null, null, null, null, null, json, null);
        }
        try {
            return new MonsterTemplates(actions(root, "actions"), actions(root, "legendaryActions"),
                    actions(root, "lairActions"), actions(root, "bonusActions"), actions(root, "reactions"),
                    actions(root, "traits"), READER.convertValue(root.get("legendaryActionCount"), Integer.class),
                    READER.convertValue(root.get("legendaryResistanceCount"), Integer.class),
                    READER.convertValue(root.get("spellcasting"), Spellcasting.class),
                    READER.convertValue(root.get("additionalSpellcasting"), SPELLCASTING_BLOCKS), json, null);
        } catch (IllegalArgumentException | JacksonException e) {
            return new MonsterTemplates(null, null, null, null, null, null, null, null, null, null, json,
                    e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    /** The JSON as stored and as the API returns it. */
    @JsonValue
    @Override
    public JsonNode json() {
        return json;
    }

    /**
     * The monster's templates, NONE when it has none. Templates that don't fit these records stop the command with a
     * 409 naming them (D155), and the cause is logged.
     */
    public static MonsterTemplates of(Monster monster) {
        return readable(monster.getActionTemplates(), monster.getName() + "'s action templates");
    }

    /**
     * The templates of the column's text, as {@link #of(Monster)} reads them: NONE for null or blank text, and a 409
     * naming the data for text that isn't JSON or doesn't fit the records.
     *
     * @param what names the data for the message, such as "Goblin's action templates"
     */
    public static MonsterTemplates parse(String json, String what) {
        if (json == null || json.isBlank()) {
            return NONE;
        }
        try {
            return readable(of(READER.readTree(json)), what);
        } catch (JacksonException e) {
            log.error("{} are not valid JSON", what, e);
            throw new ConflictException(what + " can't be read");
        }
    }

    /**
     * The templates of the column's text as the column holds them, for data the caller knows to be JSON, such as a
     * test's; null for null.
     */
    public static MonsterTemplates parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return of(READER.readTree(json));
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Not JSON: " + json, e);
        }
    }

    // The templates, NONE for none or for JSON that holds no object; a 409 when they don't fit the records
    private static MonsterTemplates readable(MonsterTemplates templates, String what) {
        JsonNode json = templates != null ? templates.json() : null;
        JsonNode root = json != null && json.isArray() ? json.path(0) : json;
        if (root == null || !root.isObject()) {
            return NONE;
        }
        if (templates.unreadable() != null) {
            log.error("{} don't fit the template records: {}", what, templates.unreadable());
            throw new ConflictException(what + " can't be read");
        }
        return templates;
    }

    /**
     * The list an action of this source is in: LEGENDARY, LAIR, BONUS (the bonus actions) and REACTION (the reactions)
     * name theirs, and anything else the actions.
     */
    public List<Action> list(String source) {
        return switch (source != null ? source : "") {
            case "LEGENDARY" -> legendaryActions;
            case "LAIR" -> lairActions;
            case "BONUS" -> bonusActions;
            case "REACTION" -> reactions;
            default -> actions;
        };
    }

    /** The action of that name, exactly as named, in the source's list. */
    public Optional<Action> find(String source, String name) {
        return list(source).stream().filter(action -> Objects.equals(action.name(), name)).findFirst();
    }

    /**
     * The action a multiattack component names, in any case: the first of that name in the actions, then the
     * legendary actions, lair actions, bonus actions, reactions and traits.
     */
    public Optional<Action> component(String name) {
        String wanted = name != null ? name.trim() : "";
        return Stream.of(actions, legendaryActions, lairActions, bonusActions, reactions, traits)
                .flatMap(List::stream)
                .filter(action -> action.name() != null && action.name().trim().equalsIgnoreCase(wanted))
                .findFirst();
    }

    /**
     * The heads of a monster whose multiattack makes an attack for each of its heads, as a hydra's (MM p.190, D206);
     * empty for any other.
     */
    public Optional<Heads> heads() {
        return actions.stream()
                .filter(action -> action.multiattack() && action.heads() != null && action.heads().count() != null
                        && action.heads().attack() != null)
                .map(Action::heads)
                .findFirst();
    }

    /** Whether the monster has lair actions, so it can fight in its lair (D163). */
    public boolean hasLairActions() {
        return !lairActions.isEmpty();
    }

    /**
     * One action, legendary action, lair action, bonus action, reaction or trait.
     *
     * @param automatable             false when the data leaves the action to the DM
     * @param deliveryMethod          ATTACK_ROLL, SAVING_THROW, AUTO_HIT or AUTO_DAMAGE, any other being the DM's;
     *                                none means AUTO_HIT for an action with effects
     * @param attackType              such as MELEE_WEAPON or RANGED_SPELL
     * @param targetCount             how many creatures it targets, when the data says
     * @param secondarySaveDc         the DC of the save an attack's hit calls for, where the data records it apart
     *                                from saveDC, as the Mind Flayer's tentacles do (MM)
     * @param secondarySaveAbility    that save's ability
     * @param recharge                the d6 rolls that recharge it, such as "5-6"; for some actions instead a limit,
     *                                such as "SHORT_REST", "LONG_REST" or "3/DAY"
     * @param uses                    how many times it can be used before its recharge, when the data says
     * @param usesPerDay              how many times a day it can be used, when the data says
     * @param usesRecharge            what brings back its uses, such as "LONG_REST"
     * @param rechargeType            the same, as some entries write it
     * @param cost                    a legendary action's cost: a number, or a range such as "1-3" for a spell that
     *                                costs one per spell level
     * @param multiattackComponents   the actions a multiattack makes, by name, in order
     * @param multiattackDescription  the stat block's wording of a multiattack
     * @param multiattackNote         the data's review note on a multiattack with an and/or pattern it couldn't
     *                                resolve
     * @param rayCount                how many of its rays an Eye Rays action fires
     * @param dmNote                  what the DM applies by hand when the action is used, such as how a bite's
     *                                swallowing works; the combat log shows it
     * @param grappleLimit            how many creatures the attack's grapple can hold at once, as a giant constrictor
     *                                snake's constriction holds one (MM p.324) and a giant crab's two claws two (MM
     *                                p.324); null for no limit (D203)
     * @param grappleLimitBlocks      whether the attack can't be used on another creature while it holds its limit,
     *                                as "the snake can't constrict another target"; otherwise it still hits, but
     *                                grapples no one more (D203)
     * @param immuneAfterSave         for a saving throw action, how long a creature that succeeds on its save is
     *                                immune to it, as "for the next 24 hours" after a dragon's Frightful Presence (MM
     *                                p.87); the immunity is kept for the encounter, and also follows once the effect
     *                                ends for a creature that failed; null for none (D203)
     * @param exemptTypes             creature types the whole action has no effect on, as a banshee's wail on
     *                                constructs and undead (MM p.23); empty for none (D203)
     * @param targetTypes             the only creature types the action affects, as a ghost's Possession takes "one
     *                                humanoid" (MM p.147); any other target is unaffected, and the log says so; empty
     *                                for any (D204)
     * @param targetGrappled          the action can be used only on a creature the monster is grappling, as a giant
     *                                toad's Swallow (MM p.329); another target is refused (D204)
     * @param heads                   for a multiattack that makes "as many Bite attacks as it has heads", as a hydra's
     *                                (MM p.190): the attack, the stat block's head count and its head rules; the
     *                                participant's Heads pool holds how many it has now (D206)
     * @param onAllHit                for a multiattack, what follows when all of one attack's hits land on the same
     *                                creature, as a shambling mound's grapple and Engulf after both Slams hit (MM
     *                                p.270); null for nothing (D206)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Action(String name, String description, Boolean automatable, String deliveryMethod,
                         String attackType, Integer attackBonus, @JsonProperty("saveDC") Integer saveDc,
                         String saveAbility, boolean halfOnSave, Integer targetCount,
                         @JsonProperty("secondarySaveDC") Integer secondarySaveDc, String secondarySaveAbility,
                         List<Part> effects, String recharge, Integer uses, Integer usesPerDay,
                         String usesRecharge, String rechargeType, String cost,
                         @JsonProperty("isMultiattack") boolean multiattack, List<String> multiattackComponents,
                         String multiattackDescription, @JsonProperty("_multiattackNote") String multiattackNote,
                         @JsonProperty("isEyeRays") boolean eyeRays, Integer rayCount, List<Ray> rays,
                         String dmNote, Integer grappleLimit, boolean grappleLimitBlocks, String immuneAfterSave,
                         List<String> exemptTypes, List<String> targetTypes, boolean targetGrappled, Heads heads,
                         OnAllHit onAllHit) {

        public Action {
            effects = listOf(effects);
            multiattackComponents = listOf(multiattackComponents);
            rays = listOf(rays);
            exemptTypes = listOf(exemptTypes);
            targetTypes = listOf(targetTypes);
        }

        /**
         * An action of these parts that needs no roll, on one creature, as a shambling mound's grapple once both its
         * Slams hit (MM p.270, D206).
         */
        public static Action automatic(String name, List<Part> effects) {
            return new Action(name, null, null, "AUTO_HIT", null, null, null, null, false, 1, null, null, effects,
                    null, null, null, null, null, null, false, null, null, null, false, null, null, null, null, false,
                    null, null, null, false, null, null);
        }

        /** A legendary action's cost: its number, or 1 when it has none or gives a range. */
        public int legendaryCost() {
            Matcher whole = WHOLE.matcher(cost != null ? cost : "");
            return whole.matches() ? Integer.parseInt(whole.group(1)) : 1;
        }
    }

    /**
     * One effect of an action or a ray.
     *
     * @param effectType        DAMAGE, CONDITION or HEAL; any other is the DM's
     * @param damageDice        damage as dice or a flat amount, such as "2d10 + 6" or "1"; a flat amount also carries
     *                          a flatDamage flag, which adds nothing
     * @param saveDc            the DC of the save against a condition an attack gives on a hit, when the data records
     *                          it on the condition rather than the action
     * @param saveAbility       that save's ability, as the data records it
     * @param durationRounds    how many rounds a condition lasts, when the data says
     * @param saveToEndEachTurn whether the condition's creature repeats a save at the end of each of its turns
     * @param saveToEndDc       that save's DC, when it isn't the condition's or the action's
     * @param saveToEndAbility  that save's ability, when it isn't the condition's or the action's
     * @param escapeDc          the DC of the check that escapes a grapple, or the restraint that lasts while it does;
     *                          never a saving throw, so the condition comes with the hit
     * @param halfOnSave        for a DAMAGE part with its own save (saveDC and saveAbility), dealt only as the hit's
     *                          save allows, as a phase spider's poison (MM p.334): whether a success halves it, else
     *                          it prevents it (D202)
     * @param onlyIf            for a DAMAGE part, what must hold for it to be dealt, as a gray render's claw's
     *                          bludgeoning against a prone target (MTF p.209); null for always (D202). For a CONDITION
     *                          part, what must hold for the target to get it, as a kraken's bite swallows only a
     *                          creature it is grappling (MM p.197) (D204)
     * @param replacesDamage    for a DAMAGE part with onlyIf, whether it replaces the action's first unconditional
     *                          damage, as a duergar's war pick's 2d8 + 2 while enlarged (MM p.122), rather than adding
     *                          to it (D202)
     * @param maxTargetSize     for a CONDITION part, the largest size it reaches, such as "Large" for "a Large or
     *                          smaller creature"; a bigger target doesn't get it (D202)
     * @param endOfUsersTurn    for a CONDITION part, it lasts until the end of the user's next turn, as a scarecrow's
     *                          frightening claw (MM p.268), not the target's (D202)
     * @param endsWith          the condition the same hit gives that this part lasts while, ending with it: "grappled"
     *                          for a giant constrictor snake's restraint "until this grapple ends" (MM p.324),
     *                          "poisoned" for a carrion crawler's paralysis "until this poison ends" (MM p.37); its own
     *                          duration and repeat save then give way to that condition's (D203)
     * @param endsOnDamage      for a CONDITION part, it also ends when its creature takes damage (D203)
     * @param exemptTypes       creature types the part doesn't reach, as a ghoul's paralysis passes over elves and undead
     *                          (MM p.148); a race or a stat block's tag counts, such as "elf" (D203)
     * @param until             for a lasting part, when it ends: LONG_REST or SHORT_REST (a rest, which the encounter
     *                          doesn't reach, so the log reminds the table for Write-back), TARGET_TURN_END,
     *                          TARGET_TURN_START, USER_TURN_END or USER_TURN_START; null for durationRounds, or for
     *                          as long as endsWith's condition (D203)
     * @param diesAtZero        for HP_MAX_REDUCTION, the creature dies if the reduction takes its maximum to 0, as a
     *                          wight's Life Drain's (MM p.300) (D203)
     * @param half              for HP_MAX_REDUCTION or LIFE_DRAIN, half the damage counts rather than all of it
     * @param tempHp            for LIFE_DRAIN, the attacker gains temporary hit points rather than regaining hit points
     * @param levels            for EXHAUSTION, how many levels the creature gains
     * @param modifier          for MODIFIER, the Ongoing Modifier the hit leaves on its target (D184, D203)
     * @param turnOf            for ONGOING_DAMAGE, whose turns' start deals it: TARGET (the default), or USER, as a
     *                          stirge drains blood at the start of each of its own turns (MM p.284)
     * @param total             for ONGOING_DAMAGE, the most it deals in all, as a stirge's 10 hit points, after which
     *                          it and its endsWith condition end; null for no limit
     * @param swallow           for a swallowed or engulfed CONDITION, what the swallower's gullet does: how many it
     *                          holds, the damage from inside that makes it regurgitate, and the escape when it dies
     *                          (D204); null for any other condition
     * @param counted           for COUNTED_SAVES, the saves the creature counts at the end of its turns and what the
     *                          failures leave, as a cockatrice's petrification (MM p.42) (D204)
     * @param ability           for ABILITY_REDUCTION, the ability score it lowers, such as "STR" for a shadow's Strength
     *                          Drain (MM p.269), by damageDice (D204)
     * @param atZero            for AT_ZERO, what happens to a creature the hit drops to 0 hit points (D204)
     * @param onKill            for ON_KILL, what rises from a creature the hit kills (D204)
     * @param turnSave          for ONGOING_DAMAGE, the save the creature makes each time before the damage, as a
     *                          shambling mound's engulfed creature's DC 14 Constitution save at the start of each of
     *                          the mound's turns (MM p.270); null when the damage comes every turn (D206)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Part(String effectType, String damageDice, String damageType, String condition,
                       @JsonProperty("saveDC") Integer saveDc, String saveAbility,
                       Integer durationRounds, boolean saveToEndEachTurn,
                       @JsonProperty("saveToEndDC") Integer saveToEndDc, String saveToEndAbility,
                       @JsonProperty("escapeDC") Integer escapeDc, boolean halfOnSave, DamageCondition onlyIf,
                       boolean replacesDamage, String maxTargetSize, boolean endOfUsersTurn, String endsWith,
                       boolean endsOnDamage, List<String> exemptTypes, String until, boolean diesAtZero, boolean half,
                       boolean tempHp, Integer levels, Modifier modifier, String turnOf, Integer total,
                       Swallow swallow, Counted counted, String ability, AtZero atZero, OnKill onKill,
                       TurnSave turnSave) {

        public Part {
            exemptTypes = listOf(exemptTypes);
        }

        /** A part with none of the riders D206 reads. */
        public Part(String effectType, String damageDice, String damageType, String condition, Integer saveDc,
                    String saveAbility, Integer durationRounds, boolean saveToEndEachTurn, Integer saveToEndDc,
                    String saveToEndAbility, Integer escapeDc, boolean halfOnSave, DamageCondition onlyIf,
                    boolean replacesDamage, String maxTargetSize, boolean endOfUsersTurn, String endsWith,
                    boolean endsOnDamage, List<String> exemptTypes, String until, boolean diesAtZero, boolean half,
                    boolean tempHp, Integer levels, Modifier modifier, String turnOf, Integer total,
                    Swallow swallow, Counted counted, String ability, AtZero atZero, OnKill onKill) {
            this(effectType, damageDice, damageType, condition, saveDc, saveAbility, durationRounds, saveToEndEachTurn,
                    saveToEndDc, saveToEndAbility, escapeDc, halfOnSave, onlyIf, replacesDamage, maxTargetSize,
                    endOfUsersTurn, endsWith, endsOnDamage, exemptTypes, until, diesAtZero, half, tempHp, levels,
                    modifier, turnOf, total, swallow, counted, ability, atZero, onKill, null);
        }

        /** A part with none of the riders D204 reads. */
        public Part(String effectType, String damageDice, String damageType, String condition, Integer saveDc,
                    String saveAbility, Integer durationRounds, boolean saveToEndEachTurn, Integer saveToEndDc,
                    String saveToEndAbility, Integer escapeDc, boolean halfOnSave, DamageCondition onlyIf,
                    boolean replacesDamage, String maxTargetSize, boolean endOfUsersTurn, String endsWith,
                    boolean endsOnDamage, List<String> exemptTypes, String until, boolean diesAtZero, boolean half,
                    boolean tempHp, Integer levels, Modifier modifier, String turnOf, Integer total) {
            this(effectType, damageDice, damageType, condition, saveDc, saveAbility, durationRounds, saveToEndEachTurn,
                    saveToEndDc, saveToEndAbility, escapeDc, halfOnSave, onlyIf, replacesDamage, maxTargetSize,
                    endOfUsersTurn, endsWith, endsOnDamage, exemptTypes, until, diesAtZero, half, tempHp, levels,
                    modifier, turnOf, total, null, null, null, null, null);
        }

        /** A part with none of the riders D203 reads. */
        public Part(String effectType, String damageDice, String damageType, String condition, Integer saveDc,
                    String saveAbility, Integer durationRounds, boolean saveToEndEachTurn, Integer saveToEndDc,
                    String saveToEndAbility, Integer escapeDc, boolean halfOnSave, DamageCondition onlyIf,
                    boolean replacesDamage, String maxTargetSize, boolean endOfUsersTurn) {
            this(effectType, damageDice, damageType, condition, saveDc, saveAbility, durationRounds, saveToEndEachTurn,
                    saveToEndDc, saveToEndAbility, escapeDc, halfOnSave, onlyIf, replacesDamage, maxTargetSize,
                    endOfUsersTurn, null, false, null, null, false, false, false, null, null, null, null);
        }

        /** A part with none of the riders D202 reads. */
        public Part(String effectType, String damageDice, String damageType, String condition, Integer saveDc,
                    String saveAbility, Integer durationRounds, boolean saveToEndEachTurn, Integer saveToEndDc,
                    String saveToEndAbility, Integer escapeDc) {
            this(effectType, damageDice, damageType, condition, saveDc, saveAbility, durationRounds, saveToEndEachTurn,
                    saveToEndDc, saveToEndAbility, escapeDc, false, null, false, null, false);
        }

        /** Whether the part records a save of its own: both its DC and its ability. */
        public boolean ownSave() {
            return saveDc != null && saveAbility != null && !saveAbility.isBlank();
        }
    }

    /**
     * The Ongoing Modifier a monster's hit leaves on its target (D203), named as {@code OngoingModifier.Kind}: such as
     * NO_HEALING for "can't regain hit points", SPEED with flat -10 for "its speed is reduced by 10 feet", SPEED_HALVED,
     * SPEED_MAX with flat 0 for "its speed becomes 0", ATTACK_MODE, SAVE_MODE, CHECK_MODE or ATTACKED_MODE with a mode,
     * NO_REACTIONS, or LOSE_RESISTANCE with damageTypes.
     *
     * @param mode    ADVANTAGE or DISADVANTAGE, for the *_MODE kinds
     * @param flat    feet, for SPEED and SPEED_MAX
     * @param ability the one ability whose saves or checks it changes, such as "STR"; null for all
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Modifier(String kind, String mode, Integer flat, String ability, List<String> damageTypes) {

        public Modifier {
            damageTypes = listOf(damageTypes);
        }
    }

    /**
     * What a swallowing creature's gullet does (D204), on its swallowed or engulfed CONDITION part, as a purple worm's
     * (MM p.255): the creature inside is blinded and restrained, with total cover against everything outside, and takes
     * damage at the start of each of the swallower's turns, which the action's other parts give, tied to this condition.
     *
     * @param limit              how many creatures it can hold at once, as a giant toad's one (MM p.329) or a
     *                           froghemoth's two (VGM p.145); null for no limit
     * @param regurgitateDamage  the damage it must take on a single turn from creatures inside it to make the save that
     *                           keeps them down, as a purple worm's 30; null for none
     * @param regurgitateDc      that Constitution save's DC, as a purple worm's 21; null when it regurgitates without one,
     *                           as an eye monger does (BAM)
     * @param escapeFeet         the movement a swallowed creature spends to leave the corpse once the swallower dies,
     *                           exiting prone, as a purple worm's 20 feet; null when the stat block gives none
     * @param keepsGrapple       true when the monster's grapple on the creature goes on, as a shambling mound's
     *                           engulfing of a creature it grapples, which ends when the grapple does (MM p.270); null
     *                           when the grapple ends, as "and the grapple ends" (D206)
     * @param cover              false when the creature inside has no cover against what is outside, as the shambling
     *                           mound's text gives none (MM p.270); null for the total cover a swallowed creature has
     *                           (D206)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Swallow(Integer limit, Integer regurgitateDamage, @JsonProperty("regurgitateDC") Integer regurgitateDc,
                          Integer escapeFeet, Boolean keepsGrapple, Boolean cover) {

        /** A gullet that ends the grapple and gives total cover (D204). */
        public Swallow(Integer limit, Integer regurgitateDamage, Integer regurgitateDc, Integer escapeFeet) {
            this(limit, regurgitateDamage, regurgitateDc, escapeFeet, null, null);
        }

        /** Whether the monster's grapple on the creature goes on once it is inside (D206). */
        public boolean grappleKept() {
            return Boolean.TRUE.equals(keepsGrapple);
        }

        /** Whether the creature inside has total cover against what is outside (D204, D206). */
        public boolean givesCover() {
            return !Boolean.FALSE.equals(cover);
        }
    }

    /**
     * The save a creature makes before each tick of a monster's damage at the start of a turn (D206), as a shambling
     * mound's engulfed creature "must succeed on a DC 14 Constitution saving throw at the start of each of the mound's
     * turns or take 13 (2d8 + 4) bludgeoning damage" (MM p.270).
     *
     * @param saveDc      its DC
     * @param saveAbility its ability, such as "CON"
     * @param halfOnSave  whether a success halves the damage, as a parasite-infested behir's parasites (CM); otherwise
     *                    a success prevents it
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TurnSave(@JsonProperty("saveDC") Integer saveDc, String saveAbility, boolean halfOnSave) {
    }

    /**
     * A multiattack that makes "as many Bite attacks as it has heads" (D206), as a hydra's (MM p.190).
     *
     * @param count  the heads the stat block gives it, such as 5, which a participant starts with unless the DM chose
     *               another number when adding it
     * @param attack the attack made once per head, such as "Bite"; any of the multiattack's components of that name
     *               give way to these
     * @param rules  the stat block's rules for its heads, which the log reminds the DM of, as heads dying and regrowing
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Heads(Integer count, String attack, String rules) {
    }

    /**
     * What follows a multiattack when all of one attack's hits land on the same creature (D206), as a shambling mound's
     * "If both attacks hit a Medium or smaller target, the target is grappled (escape DC 14), and the shambling mound
     * uses its Engulf on it" (MM p.270).
     *
     * @param attack  the attack whose every hit must land on the creature, such as "Slam"
     * @param effects what the creature then gets without a roll, as a grapple with its escape DC and its size limit
     * @param uses    the action the monster then uses on the creature at once, such as "Engulf"; null for none
     * @param text    the stat block's words, which the log quotes
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OnAllHit(String attack, List<Part> effects, String uses, String text) {

        public OnAllHit {
            effects = listOf(effects);
        }
    }

    /**
     * The saves a creature counts at the end of its turns after a monster's effect, reusing D187's counted saves (D204):
     * a cockatrice's bite restrains as the creature begins to turn to stone, and the save at the end of its next turn
     * ends it on a success or petrifies on a failure (MM p.42). The save is the part's saveToEndAbility and saveToEndDC.
     *
     * @param successes        how many successes end it
     * @param failures         how many failures leave failureCondition
     * @param endsConditions   the conditions the action gave that end on either count, as the restraint
     * @param failureCondition the condition the failures leave for good, as petrified
     * @param onFailures       what the log reminds the table of then, as "petrified for 24 hours"
     * @param failBy           a first save failed by this much or more leaves failureCondition at once, as a medusa's
     *                         gaze failed by 5 or more (MM p.214); null for none
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Counted(Integer successes, Integer failures, List<String> endsConditions, String failureCondition,
                          String onFailures, Integer failBy) {

        public Counted {
            endsConditions = listOf(endsConditions);
        }
    }

    /**
     * What happens to a creature a monster's hit drops to 0 hit points (D204): it dies, where the stat block says "the
     * target dies if this attack reduces it to 0 hit points", or it is stable but poisoned, as from a giant spider's
     * bite (MM p.328). A monster at 0 hit points is dead already, so this matters for player characters.
     *
     * @param dies           it dies outright, through D189's kill-outright path
     * @param stable         it is stable rather than dying, as the poison of a giant spider leaves it
     * @param condition      the condition it gains then, as "poisoned"
     * @param durationRounds how long that lasts, as 600 for an hour, "even after regaining hit points"
     * @param linked         the conditions it has while that condition lasts, as "paralyzed while poisoned in this way"
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AtZero(boolean dies, boolean stable, String condition, Integer durationRounds, List<String> linked) {

        public AtZero {
            linked = listOf(linked);
        }
    }

    /**
     * What rises from a creature a monster's hit kills (D204), reusing D189's raising: a humanoid a barrowghast slays
     * rises at once as a zombie (BGG), while a wight's rises 24 hours later (MM p.300), which the log only reminds the DM
     * of.
     *
     * @param creatureTypes the creature types that rise, as "humanoid"; empty for any
     * @param form          what it rises as, a stat block's name, such as "Zombie"
     * @param immediately   it rises in the fight, at the start of the monster's next turn; otherwise later
     * @param text          the stat block's words, which the log quotes
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OnKill(List<String> creatureTypes, String form, boolean immediately, String text) {

        public OnKill {
            creatureTypes = listOf(creatureTypes);
        }
    }

    /**
     * What must hold for a DAMAGE part to be dealt (D202), one of: the target has a condition (given by the attacker,
     * with byAttacker, as "grappled by Geryon"), the target is of a creature type, the attacker had advantage on the
     * attack roll, the attacker is in a form ("enlarged", "crocodile form", "raging"), or the attacker has half its
     * hit points or fewer, as a swarm.
     *
     * @param text the stat block's words, such as "if the target is prone", which the combat log quotes
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DamageCondition(String targetCondition, boolean byAttacker, List<String> targetType,
                                  boolean attackerAdvantage, String attackerForm, boolean attackerBloodied,
                                  String text) {

        public DamageCondition {
            targetType = listOf(targetType);
        }
    }

    /** One ray of an Eye Rays action: a saving throw and its effects. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ray(String name, @JsonProperty("saveDC") Integer saveDc, String saveAbility, boolean halfOnSave,
                      List<Part> effects) {

        public Ray {
            effects = listOf(effects);
        }
    }

    /**
     * A monster's spellcasting block, which the encounter's spellbook reads.
     *
     * @param slots         slots by spell level, keyed "1" to "9"
     * @param spellsByLevel spells under their level, "0" for cantrips, or under a daily or at-will key
     * @param dailySpells   spells under daily keys such as "1e/day" or "3/day each"; a second block may name them
     *                      "daily", as the stat block does (D193)
     * @param innateSpells  a flag, except for the few monsters that keep their daily and at-will lists in it
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Spellcasting(String ability, Integer attackBonus, @JsonProperty("saveDC") Integer saveDc,
                               Map<String, Integer> slots, Map<String, List<String>> spellsByLevel,
                               @JsonAlias("daily") Map<String, List<String>> dailySpells, List<String> atWillSpells,
                               JsonNode innateSpells) {

        public Spellcasting {
            slots = mapOf(slots);
            spellsByLevel = mapOf(spellsByLevel);
            dailySpells = mapOf(dailySpells);
            atWillSpells = listOf(atWillSpells);
        }

        /** The lists the innate block keeps under daily and at-will keys; none when it is only a flag. */
        public Map<String, List<String>> innateLists() {
            Map<String, List<String>> lists = new LinkedHashMap<>();
            if (innateSpells != null && innateSpells.isObject()) {
                innateSpells.properties().forEach(group -> {
                    List<String> names = new ArrayList<>();
                    group.getValue().forEach(name -> {
                        if (name.isString()) {
                            names.add(name.asString(""));
                        }
                    });
                    lists.put(group.getKey(), names);
                });
            }
            return lists;
        }
    }

    // One of the lists, read into records; null when absent
    private static List<Action> actions(JsonNode root, String key) {
        return READER.convertValue(root.get(key), ACTIONS);
    }

    // A list without nulls, empty when there is none
    private static <T> List<T> listOf(List<T> list) {
        return list == null ? List.of() : list.stream().filter(Objects::nonNull).toList();
    }

    // A map in its JSON order, empty when there is none
    private static <K, V> Map<K, V> mapOf(Map<K, V> map) {
        return map == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }
}
