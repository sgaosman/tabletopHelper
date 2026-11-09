package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.UsePoolRequest;
import com.tabletophelper.encounter.dto.WildShapeOptions;
import com.tabletophelper.encounter.state.WildShapeForm;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.rules.combat.ActionCost;
import com.tabletophelper.rules.combat.BeastShapes;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.rules.combat.SummonRules;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wild Shape used at the table (Druid 2, PHB p.66–67), through the druid's Wild Shape pool (M17): the use names the
 * beast, which must be a beast within the Beast Shapes limits of the druid's level ({@link BeastShapes}), and costs an
 * action, or a bonus action for a Circle of the Moon druid (PHB p.69). The option "end" reverts early as a bonus action,
 * spending no use. A Circle of the Moon druid of 10th level may name one of the four elementals instead, Elemental Wild
 * Shape, which spends two uses at once (PHB p.69); an elemental takes the place of a beast in everything that follows,
 * its defences, condition immunities and actions included. Whether the druid has seen the beast is the DM's call. While
 * transformed the druid can't start another Wild Shape; its beast's statistics are copied onto the form when it
 * transforms ({@link WildShapeForm}), and {@link EffectApplier} keeps the form's hit points apart from the druid's own.
 */
@Component
@RequiredArgsConstructor
public class WildShapeEffects {

    public static final String EFFECT = "wild-shape";

    /** The Wild Shape pool's ID in the content data, which names the effect where older pool entries don't. */
    public static final String POOL_ID = "class:druid-wild-shape";

    private static final String DRUID = "Druid";
    private static final List<String> MOON = List.of("Circle of the Moon", "Moon");
    private static final String BEAST = "beast";
    private static final Pattern BONUS = Pattern.compile("^\\s*([+-]?\\d{1,3})");

    private final MonsterRepository monsterRepository;
    private final ObjectMapper objectMapper;
    private final EffectApplier effectApplier;

    /**
     * Checks a use of Wild Shape before anything is charged or spent.
     *
     * @throws ValidationException for a target, amounts, more than one use (two for an elemental), no beast named, or a
     *                             creature that isn't a beast or one of the four elementals
     * @throws ConflictException   for a druid below 2nd level, one already transformed, a beast beyond its limits or
     *                             without hit points, an elemental for a druid without Elemental Wild Shape, a druid not
     *                             alive, or ending a Wild Shape it isn't in. Too few uses left is the pool's own 409
     */
    public FeatureEffects.Plan plan(EncounterParticipant druid, ResourcePoolEntry pool, UsePoolRequest request,
                                    String option, int uses) {
        if (request.targetIds() != null && !request.targetIds().isEmpty() || request.amounts() != null) {
            throw new ValidationException(pool.displayName() + " is used only on " + druid.getDisplayName());
        }
        if (uses != 1 && (uses != BeastShapes.ELEMENTAL_USES || option != null)) {
            throw new ValidationException(pool.displayName() + " can be used only once at a time");
        }
        Optional<WildShapeForm> current = WildShapeForm.of(druid);
        if (FeatureEffects.END.equals(option)) {
            if (current.isEmpty()) {
                throw new ConflictException(druid.getDisplayName() + " isn't in Wild Shape");
            }
            // Reverting early is a bonus action and spends no use (PHB p.67)
            return new FeatureEffects.Plan(EFFECT, FeatureEffects.END, ActionCost.BONUS_ACTION, 0, druid, "");
        }
        if (option != null) {
            throw new ValidationException("Wild Shape takes a beast, or the option \"" + FeatureEffects.END + "\"");
        }
        if (request.beastId() == null) {
            throw new ValidationException("Choose the beast " + druid.getDisplayName() + " becomes");
        }
        if (druid.getLifeState() != LifeState.ALIVE) {
            throw new ConflictException(druid.getDisplayName() + " can't use Wild Shape at 0 hit points");
        }
        if (current.isPresent()) {
            throw new ConflictException(druid.getDisplayName() + " is already in Wild Shape as a "
                    + current.get().beastName() + ": revert first");
        }
        BeastShapes.Limit limit = limit(druid);
        Monster beast = monsterRepository.findById(request.beastId())
                .orElseThrow(() -> new ValidationException("Beast not found"));
        int level = druidLevel(druid.getCharacter());
        int hours = BeastShapes.durationHours(level);
        String lasts = " for up to " + hours + " hour" + (hours == 1 ? "" : "s");
        if (BeastShapes.elemental(beast.getName(), beast.getSource())) {
            // Elemental Wild Shape: two uses at the same time, outside the Beast Shapes limits (PHB p.69)
            if (!BeastShapes.elementalWildShape(level, circleOfTheMoon(druid))) {
                throw new ConflictException(beast.getName() + " is a form only Elemental Wild Shape takes, which needs a "
                        + "Circle of the Moon druid of level " + BeastShapes.ELEMENTAL_LEVEL + ", and "
                        + druid.getDisplayName() + " isn't one");
            }
            return new FeatureEffects.Plan(EFFECT, beast.getId().toString(), BeastShapes.cost(true),
                    BeastShapes.ELEMENTAL_USES, druid, ": Elemental Wild Shape, two uses at once, becomes "
                    + article(beast.getName()) + " " + beast.getName() + " (CR " + beast.getChallengeRating() + ", "
                    + beast.getHitPoints() + " HP, AC " + beast.getArmourClass() + ")" + lasts);
        }
        if (uses != 1) {
            throw new ValidationException(pool.displayName() + " can be used only once at a time, except for "
                    + "Elemental Wild Shape");
        }
        if (!isBeast(beast)) {
            throw new ValidationException(beast.getName() + " isn't a beast, so Wild Shape can't take its form");
        }
        if (beast.getHitPoints() == null || beast.getHitPoints() <= 0) {
            throw new ConflictException(beast.getName() + "'s stat block gives no hit points, so Wild Shape can't take "
                    + "its form");
        }
        BeastShapes.refusal(limit, beast.getName(), beast.getChallengeRating(),
                TurnEconomies.speed(beast, objectMapper)).ifPresent(reason -> {
            throw new ConflictException(reason + " for " + druid.getDisplayName());
        });
        return new FeatureEffects.Plan(EFFECT, beast.getId().toString(), BeastShapes.cost(circleOfTheMoon(druid)), 1,
                druid, ": becomes " + article(beast.getName()) + " " + beast.getName() + " (CR "
                        + beast.getChallengeRating() + ", " + beast.getHitPoints() + " HP, AC " + beast.getArmourClass()
                        + ")" + lasts);
    }

    // "a" or "an", by the name's first letter, as for "an Air Elemental"
    private static String article(String name) {
        return name != null && !name.isEmpty() && "AEIOUaeiou".indexOf(name.charAt(0)) >= 0 ? "an" : "a";
    }

    /** Transforms the druid, or reverts it, once the turn is charged and the pool spent. */
    public void apply(Encounter encounter, EncounterParticipant druid, FeatureEffects.Plan plan) {
        if (FeatureEffects.END.equals(plan.option())) {
            effectApplier.endWildShape(encounter, druid, "bonus action");
            return;
        }
        Monster beast = monsterRepository.findById(UUID.fromString(plan.option()))
                .orElseThrow(() -> new ValidationException("Beast not found"));
        effectApplier.startWildShape(encounter, druid, form(beast, druid));
    }

    /**
     * The beasts the druid can become now, by the Beast Shapes limits of its level, in challenge rating then name
     * order, from the rulebooks and with hit points ({@link BeastShapes#offered}); and for a Circle of the Moon druid of
     * 10th level the four elementals of Elemental Wild Shape (PHB p.69). Empty for a creature without Wild Shape.
     */
    public WildShapeOptions options(EncounterParticipant druid) {
        int level = druidLevel(druid.getCharacter());
        boolean moon = circleOfTheMoon(druid);
        Optional<BeastShapes.Limit> limit = BeastShapes.limit(level, moon);
        if (limit.isEmpty()) {
            return new WildShapeOptions(level, moon, null, false, false, null, null, 0, List.of(), List.of());
        }
        // Each beast once: a reprint replaces the older printing, such as MPMM's over VGM's (SummonRules.REPRINTS)
        Map<String, WildShapeOptions.Beast> byName = new LinkedHashMap<>();
        for (Monster beast : monsterRepository.findByTypeIgnoreCase(BEAST)) {
            Map<String, Integer> speed = TurnEconomies.speed(beast, objectMapper);
            if (BeastShapes.offered(beast.getSource(), beast.getHitPoints())
                    && BeastShapes.refusal(limit.get(), beast.getName(), beast.getChallengeRating(), speed).isEmpty()) {
                byName.merge(beast.getName().trim().toLowerCase(Locale.ROOT), option(beast, speed),
                        (kept, other) -> SummonRules.reprints(other.source(), kept.source()) ? other : kept);
            }
        }
        List<WildShapeOptions.Beast> beasts = new ArrayList<>(byName.values());
        List<WildShapeOptions.Beast> elementals = new ArrayList<>();
        if (BeastShapes.elementalWildShape(level, moon)) {
            for (String name : BeastShapes.ELEMENTALS) {
                monsterRepository.findByNameIgnoreCaseAndSource(name, BeastShapes.ELEMENTAL_SOURCE).ifPresent(
                        elemental -> elementals.add(option(elemental, TurnEconomies.speed(elemental, objectMapper))));
            }
        }
        beasts.sort(Comparator.comparingDouble((WildShapeOptions.Beast beast) ->
                        BeastShapes.challengeRating(beast.challengeRating()).orElse(0))
                .thenComparing(WildShapeOptions.Beast::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(beast -> beast.source() != null ? beast.source() : ""));
        BeastShapes.Limit max = limit.get();
        return new WildShapeOptions(level, moon, max.label(), max.flyingAllowed(), max.swimmingAllowed(),
                BeastShapes.cost(moon).name(), WildShapeForm.of(druid).map(WildShapeForm::beastName).orElse(null),
                BeastShapes.durationHours(level), beasts, elementals);
    }

    private static WildShapeOptions.Beast option(Monster beast, Map<String, Integer> speed) {
        return new WildShapeOptions.Beast(beast.getId(), beast.getName(), beast.getChallengeRating(),
                orZero(beast.getHitPoints()), orZero(beast.getArmourClass()), speed, beast.getSize(), beast.getSource());
    }

    /** The beast's statistics as the druid takes them on, with the druid's own hit points, maximum and AC kept. */
    WildShapeForm form(Monster beast, EncounterParticipant druid) {
        String name = beast.getName();
        return new WildShapeForm(beast.getId(), name, beast.getChallengeRating(),
                Math.max(1, orZero(beast.getHitPoints())), orZero(beast.getArmourClass()),
                orTen(beast.getStrength()), orTen(beast.getDexterity()), orTen(beast.getConstitution()),
                bonuses(beast.getSavingThrows(), name + "'s saving throws"),
                bonuses(beast.getSkills(), name + "'s skills"),
                TurnEconomies.speed(beast, objectMapper), senses(beast),
                plainNames(beast.getDamageResistances(), name + "'s damage resistances"),
                plainNames(beast.getDamageImmunities(), name + "'s damage immunities"),
                plainNames(beast.getDamageVulnerabilities(), name + "'s damage vulnerabilities"),
                plainNames(beast.getConditionImmunities(), name + "'s condition immunities"),
                orZero(druid.getHpCurrent()), Math.max(1, orZero(druid.getHpMax())), orZero(druid.getArmourClass()),
                druidLevel(druid.getCharacter()));
    }

    // ── What the sheet says ──────────────────────────────────────

    /** The druid's Beast Shapes limits, refused with a 409 below 2nd level. */
    private static BeastShapes.Limit limit(EncounterParticipant druid) {
        int level = druidLevel(druid.getCharacter());
        return BeastShapes.limit(level, circleOfTheMoon(druid)).orElseThrow(() -> new ConflictException(
                druid.getDisplayName() + " has no Wild Shape: it comes at druid level 2"));
    }

    /** The participant's druid level, capped at 20; 0 for a creature without druid levels. */
    public static int druidLevel(PlayerCharacter character) {
        return Math.min(20, FeatureEffects.classLevel(character, DRUID));
    }

    /** Whether the participant is a Circle of the Moon druid (PHB p.68–69). */
    public static boolean circleOfTheMoon(EncounterParticipant participant) {
        PlayerCharacter character = participant.getCharacter();
        return MOON.stream().anyMatch(name -> FeatureEffects.subclassLevel(character, DRUID, name) > 0);
    }

    private static boolean isBeast(Monster monster) {
        return monster.getType() != null && monster.getType().trim().toLowerCase(Locale.ROOT).equals(BEAST);
    }

    // ── The stat block ───────────────────────────────────────────

    // Bonuses as a stat block lists them, such as {"dex": "+5"} or {"perception": "+3"}, by lower-case key
    private Map<String, Integer> bonuses(String json, String what) {
        Map<String, Integer> bonuses = new LinkedHashMap<>();
        JsonNode node = StoredJson.read(objectMapper, json, what);
        if (!node.isObject()) {
            return bonuses;
        }
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            Integer bonus = bonus(entry.getValue());
            if (bonus != null) {
                bonuses.put(entry.getKey().trim().toLowerCase(Locale.ROOT), bonus);
            }
        }
        return bonuses;
    }

    private static Integer bonus(JsonNode value) {
        if (value.isInt()) {
            return value.asInt(0);
        }
        if (value.isString()) {
            Matcher matcher = BONUS.matcher(value.asString(""));
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
        }
        return null;
    }

    // The plain names in a stat block's list; a conditional entry, such as resistance to nonmagical attacks, is the
    // DM's call, as it is for a monster (CreatureStats)
    private List<String> plainNames(String json, String what) {
        List<String> names = new ArrayList<>();
        for (JsonNode entry : StoredJson.read(objectMapper, json, what)) {
            if (entry.isString() && !entry.asString("").isBlank()) {
                names.add(entry.asString("").trim().toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    // The senses as text, from a list such as ["darkvision 60 ft."] or the text itself
    private String senses(Monster beast) {
        String senses = beast.getSenses();
        if (senses == null || senses.isBlank()) {
            return null;
        }
        if (!senses.trim().startsWith("[")) {
            return senses.trim();
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode entry : StoredJson.read(objectMapper, senses, beast.getName() + "'s senses")) {
            if (entry.isString()) {
                parts.add(entry.asString(""));
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static int orZero(Integer value) {
        return value != null ? value : 0;
    }

    private static int orTen(Integer value) {
        return value != null ? value : 10;
    }
}
