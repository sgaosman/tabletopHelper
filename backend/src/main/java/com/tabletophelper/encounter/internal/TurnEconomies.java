package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.state.WildShapeForm;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.rules.Exhaustion;
import com.tabletophelper.rules.combat.ActionEconomy;
import com.tabletophelper.rules.combat.OngoingModifiers;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a participant's turn economy needs from its sheet or stat block (D174): the attacks one Attack action gives
 * it, and its speeds.
 */
@Slf4j
public final class TurnEconomies {

    private static final String WALK = "walk";

    private TurnEconomies() {}

    /**
     * The attacks one Attack action gives the participant: a player character's from its features and class levels
     * (rules.combat.ActionEconomy), and 1 for a monster, whose Multiattack is an action of its own.
     */
    public static int attacksPerAction(EncounterParticipant participant) {
        PlayerCharacter character = participant.getCharacter();
        if (character == null) {
            return 1;
        }
        List<String> features = orEmpty(character.getFeatures()).stream()
                .map(Feature::name)
                .toList();
        List<ActionEconomy.ClassLevel> classes = orEmpty(character.getMulticlassEntries()).stream()
                .map(entry -> new ActionEconomy.ClassLevel(entry.className(), entry.subclassName(), entry.level()))
                .toList();
        return ActionEconomy.attacksPerAction(features, classes);
    }

    /**
     * The participant's speeds in feet by kind, walking first: a player character's walking speed from its sheet,
     * a monster's speeds from its stat block (a speed with a condition, such as "(hover)", by its number), and a
     * druid's in beast form the beast's. Exhaustion halves every one of them from level 2, rounding down, and makes
     * them 0 from level 5 (PHB p.291, D183); a druid's exhaustion carries into its beast form, and a summoned creature
     * has its own.
     * The Ongoing Modifiers on it come first (D184): feet added or taken, as Longstrider's +10 (PHB p.256), then Haste's
     * doubling (PHB p.250) and Slow's halving (PHB p.277), and exhaustion last, so level 5 still makes it 0.
     * Empty when neither has one.
     */
    public static Map<String, Integer> speed(EncounterParticipant participant, ObjectMapper objectMapper) {
        int exhaustion = Exhaustion.clamp(participant.getExhaustionLevel());
        Map<String, Integer> speeds = new LinkedHashMap<>();
        OngoingModifiers.speeds(baseSpeed(participant, objectMapper), ActiveModifiers.modifiersOn(participant))
                .forEach((kind, feet) -> speeds.put(kind, Exhaustion.speed(feet, exhaustion)));
        return speeds;
    }

    // The participant's speeds before exhaustion
    private static Map<String, Integer> baseSpeed(EncounterParticipant participant, ObjectMapper objectMapper) {
        Map<String, Integer> speeds = new LinkedHashMap<>();
        // A druid in beast form moves at the beast's speeds (PHB p.67)
        Optional<WildShapeForm> form = WildShapeForm.of(participant);
        if (form.isPresent()) {
            speeds.putAll(form.get().speed());
            return speeds;
        }
        PlayerCharacter character = participant.getCharacter();
        if (character != null) {
            if (character.getSpeed() != null) {
                speeds.put(WALK, character.getSpeed());
            }
            return speeds;
        }
        // A summoned spirit moves only as its form allows, such as the Land Bestial Spirit's climb (TCE p.109)
        String variant = participant.getSummon() != null ? participant.getSummon().variant() : null;
        return speed(participant.getMonster(), objectMapper, variant);
    }

    /** As {@link #speed(Monster, ObjectMapper, String)} with no form. */
    public static Map<String, Integer> speed(Monster monster, ObjectMapper objectMapper) {
        return speed(monster, objectMapper, null);
    }

    /**
     * A monster's speeds in feet by kind from its stat block, walking first (a speed with a condition, such as
     * "(hover)", by its number); empty for none or none that can be read. With a form (a summoned spirit's variant),
     * a speed limited to other forms, such as "(air only)" for a Land spirit, is left out.
     */
    public static Map<String, Integer> speed(Monster monster, ObjectMapper objectMapper, String form) {
        Map<String, Integer> speeds = new LinkedHashMap<>();
        if (monster == null || monster.getSpeed() == null || monster.getSpeed().isBlank()) {
            return speeds;
        }
        JsonNode stats;
        try {
            stats = objectMapper.readTree(monster.getSpeed());
        } catch (JacksonException e) {
            log.warn("Monster {} has a speed that can't be read", monster.getId());
            return speeds;
        }
        if (!stats.isObject()) {
            return speeds;
        }
        Integer walk = feet(stats.get(WALK));
        if (walk != null) {
            speeds.put(WALK, walk);
        }
        for (Map.Entry<String, JsonNode> entry : stats.properties()) {
            Integer feet = feet(entry.getValue());
            if (!entry.getKey().equals(WALK) && feet != null && forForm(entry.getValue(), form)) {
                speeds.put(entry.getKey(), feet);
            }
        }
        return speeds;
    }

    // A speed whose condition limits it to forms, such as "(air only; hover)", counts only for those forms
    private static boolean forForm(JsonNode value, String form) {
        JsonNode condition = value.get("condition");
        if (form == null || condition == null || !condition.isString()) {
            return true;
        }
        Matcher only = ONLY.matcher(condition.asString());
        return !only.find() || only.group(1).trim().equalsIgnoreCase(form.trim());
    }

    private static final Pattern ONLY = Pattern.compile("\\(([^;)]+?) only", Pattern.CASE_INSENSITIVE);

    // A speed is a number, or an object with its number and a condition; anything else (canHover) isn't one
    private static Integer feet(JsonNode value) {
        if (value == null) {
            return null;
        }
        if (value.isNumber()) {
            return value.asInt();
        }
        JsonNode number = value.get("number");
        return number != null && number.isNumber() ? number.asInt() : null;
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list != null ? list : List.of();
    }
}
