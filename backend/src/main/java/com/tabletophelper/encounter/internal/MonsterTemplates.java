package com.tabletophelper.encounter.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.shared.ConflictException;
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
 * A monster's action templates ({@code monsters.action_templates}, D030) read into records where the encounter uses
 * them, rather than walked as JSON: its action lists, the counts that size its legendary pools, and its spellcasting.
 * The column itself keeps its JSON until R7's data files give it a record type (D168), so these records name only
 * the keys the encounter reads and ignore the rest. The seeded data writes each of those keys one way, except a
 * legendary action's cost: a number, or a range such as "1-3", so it is read as text.
 *
 * @param traits read only for a multiattack's components, which can name an action in any of the lists
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MonsterTemplates(List<Action> actions, List<Action> legendaryActions, List<Action> lairActions,
                               List<Action> bonusActions, List<Action> reactions, List<Action> traits,
                               Integer legendaryActionCount, Integer legendaryResistanceCount,
                               Spellcasting spellcasting) {

    private static final Logger log = LoggerFactory.getLogger(MonsterTemplates.class);

    // A legendary action's cost as a whole number
    private static final Pattern WHOLE = Pattern.compile("^\\s*(\\d{1,2})\\s*$");

    /** A monster without templates: no actions, no counts and no spellcasting. */
    public static final MonsterTemplates NONE =
            new MonsterTemplates(null, null, null, null, null, null, null, null, null);

    public MonsterTemplates {
        actions = listOf(actions);
        legendaryActions = listOf(legendaryActions);
        lairActions = listOf(lairActions);
        bonusActions = listOf(bonusActions);
        reactions = listOf(reactions);
        traits = listOf(traits);
    }

    /**
     * The monster's templates: a JSON object, or an array whose first entry counts, as before R5; NONE when it has
     * none. Data that doesn't fit these records stops the command with a 409 naming it, as JSON that doesn't parse
     * does (D155), and the cause is logged.
     */
    public static MonsterTemplates of(ObjectMapper objectMapper, Monster monster) {
        return parse(objectMapper, monster.getActionTemplates(), monster.getName() + "'s action templates");
    }

    /**
     * As {@link #of}, from the column's text.
     *
     * @param what names the data for the message, such as "Goblin's action templates"
     */
    public static MonsterTemplates parse(ObjectMapper objectMapper, String json, String what) {
        JsonNode root = StoredJson.read(objectMapper, json, what);
        if (root.isArray()) {
            root = root.path(0);
        }
        if (!root.isObject()) {
            return NONE;
        }
        try {
            return objectMapper.treeToValue(root, MonsterTemplates.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.error("{} don't fit the template records", what, e);
            throw new ConflictException(what + " can't be read");
        }
    }

    /** The list an action of this source is in: LEGENDARY and LAIR name theirs, and anything else the actions. */
    public List<Action> list(String source) {
        return switch (source != null ? source : "") {
            case "LEGENDARY" -> legendaryActions;
            case "LAIR" -> lairActions;
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

    /** Whether the monster has lair actions, so it can fight in its lair (D163). */
    public boolean hasLairActions() {
        return !lairActions.isEmpty();
    }

    /**
     * One action, legendary action, lair action or trait.
     *
     * @param automatable             false when the data leaves the action to the DM
     * @param deliveryMethod          ATTACK_ROLL, SAVING_THROW, AUTO_HIT or AUTO_DAMAGE, any other being the DM's;
     *                                none means AUTO_HIT for an action with effects
     * @param attackType              such as MELEE_WEAPON or RANGED_SPELL
     * @param targetCount             how many creatures it targets, when the data says
     * @param recharge                the d6 rolls that recharge it, such as "5-6"
     * @param cost                    a legendary action's cost: a number, or a range such as "1-3" for a spell that
     *                                costs one per spell level
     * @param multiattackComponents   the actions a multiattack makes, by name, in order
     * @param multiattackDescription  the stat block's wording of a multiattack
     * @param multiattackNote         the data's review note on a multiattack with an and/or pattern it couldn't
     *                                resolve
     * @param rayCount                how many of its rays an Eye Rays action fires
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Action(String name, String description, Boolean automatable, String deliveryMethod,
                         String attackType, Integer attackBonus, @JsonProperty("saveDC") Integer saveDc,
                         String saveAbility, boolean halfOnSave, Integer targetCount, List<Part> effects,
                         String recharge, String cost,
                         @JsonProperty("isMultiattack") boolean multiattack, List<String> multiattackComponents,
                         String multiattackDescription, @JsonProperty("_multiattackNote") String multiattackNote,
                         @JsonProperty("isEyeRays") boolean eyeRays, Integer rayCount, List<Ray> rays) {

        public Action {
            effects = listOf(effects);
            multiattackComponents = listOf(multiattackComponents);
            rays = listOf(rays);
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
     * @param durationRounds    how many rounds a condition lasts, when the data says
     * @param saveToEndEachTurn whether the condition's creature repeats a save at the end of each of its turns
     * @param saveToEndDc       that save's DC, when it isn't the action's
     * @param saveToEndAbility  that save's ability, when it isn't the action's
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Part(String effectType, String damageDice, String damageType, String condition,
                       Integer durationRounds, boolean saveToEndEachTurn,
                       @JsonProperty("saveToEndDC") Integer saveToEndDc, String saveToEndAbility) {
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
     * A monster's spellcasting block, which {@link MonsterSpellbook} reads.
     *
     * @param slots         slots by spell level, keyed "1" to "9"
     * @param spellsByLevel spells under their level, "0" for cantrips, or under a daily or at-will key
     * @param dailySpells   spells under daily keys such as "1e/day" or "3/day each"
     * @param innateSpells  a flag, except for the few monsters that keep their daily and at-will lists in it
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Spellcasting(String ability, Integer attackBonus, @JsonProperty("saveDC") Integer saveDc,
                               Map<String, Integer> slots, Map<String, List<String>> spellsByLevel,
                               Map<String, List<String>> dailySpells, List<String> atWillSpells,
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
                        if (name.isTextual()) {
                            names.add(name.asText());
                        }
                    });
                    lists.put(group.getKey(), names);
                });
            }
            return lists;
        }
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
