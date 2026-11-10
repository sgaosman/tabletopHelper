package com.tabletophelper.encounter.state;

import com.tabletophelper.encounter.EncounterParticipant;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * The beast a druid has become through Wild Shape (PHB p.66–67), or the creature a spell such as Polymorph turns a
 * creature into (D192), kept on the {@code wild shape} entry among its
 * conditions, as Rage's is (M17). While it lasts the participant's hit points, hit point maximum and AC are the beast's;
 * the druid's own are kept here and come back when it reverts. The beast's other game statistics are copied from its
 * stat block when the druid transforms, so the rules read them without the monster row: its Strength, Dexterity and
 * Constitution, the save and skill bonuses its stat block lists, its speeds, senses, damage defences and condition
 * immunities. Its attacks are read from the monster's action templates through {@code monsterId}.
 *
 * @param monsterId            the beast's monster row
 * @param beastName            the beast, such as "Wolf"
 * @param challengeRating      its challenge rating, as the stat block writes it
 * @param hpMax                its hit point maximum, the participant's while transformed
 * @param armourClass          its AC, the participant's while transformed
 * @param strength             its Strength score
 * @param dexterity            its Dexterity score
 * @param constitution         its Constitution score
 * @param saves                the saving throw bonuses its stat block lists, by ability ("str" to "cha")
 * @param skills               the skill bonuses its stat block lists, by skill in lower case, such as "perception"
 * @param speed                its speeds in feet by kind, walking first
 * @param senses               its senses as the stat block lists them, such as "darkvision 60 ft."
 * @param damageResistances    the damage types it resists
 * @param damageImmunities     the damage types it is immune to
 * @param damageVulnerabilities the damage types it is vulnerable to
 * @param conditionImmunities  the conditions it is immune to
 * @param normalHpCurrent      the druid's own hit points when it transformed, which the beast form leaves alone
 * @param normalHpMax          the druid's own hit point maximum
 * @param normalArmourClass    the druid's own AC
 * @param druidLevel           the druid level it transformed at, for the duration; 0 for a spell's form
 * @param spell                the spell that gave the form, such as "Polymorph" (D192); null for Wild Shape
 * @param creatureType         the form's creature type, such as "beast" or "dragon"; null reads as a beast
 * @param intelligence         the form's Intelligence, when it replaces the creature's own (Polymorph, PHB p.266); null
 *                             when the creature keeps its own, as in Wild Shape, Shapechange and Animal Shapes
 * @param wisdom               the form's Wisdom, as for intelligence
 * @param charisma             the form's Charisma, as for intelligence
 * @param keepsOwnTraits       true when the creature keeps its own proficiencies, features and defences alongside the
 *                             form's, using the higher save and skill bonus, as in Wild Shape (PHB p.67) and
 *                             Shapechange (PHB p.274); false when the form's statistics replace them all, as
 *                             Polymorph's do; null reads as true for Wild Shape and false for a spell
 * @param canCast              true when the creature can cast spells in the form, as with Shapechange (PHB p.274)
 * @param traits               the form's stat block traits as stored, a list of named entries, such as a Magic
 *                             Resistance the rules read (D191); null for none, or for a form taken before they were
 *                             copied
 */
public record WildShapeForm(
        @Schema(requiredMode = REQUIRED) UUID monsterId,
        @Schema(requiredMode = REQUIRED) String beastName,
        String challengeRating,
        @Schema(requiredMode = REQUIRED) int hpMax,
        @Schema(requiredMode = REQUIRED) int armourClass,
        @Schema(requiredMode = REQUIRED) int strength,
        @Schema(requiredMode = REQUIRED) int dexterity,
        @Schema(requiredMode = REQUIRED) int constitution,
        Map<String, Integer> saves,
        Map<String, Integer> skills,
        Map<String, Integer> speed,
        String senses,
        List<String> damageResistances,
        List<String> damageImmunities,
        List<String> damageVulnerabilities,
        List<String> conditionImmunities,
        @Schema(requiredMode = REQUIRED) int normalHpCurrent,
        @Schema(requiredMode = REQUIRED) int normalHpMax,
        @Schema(requiredMode = REQUIRED) int normalArmourClass,
        @Schema(requiredMode = REQUIRED) int druidLevel,
        @JsonInclude(JsonInclude.Include.NON_NULL) String spell,
        @JsonInclude(JsonInclude.Include.NON_NULL) String creatureType,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer intelligence,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer wisdom,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer charisma,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean keepsOwnTraits,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean canCast,
        @JsonInclude(JsonInclude.Include.NON_NULL) String traits
) {

    /** A Wild Shape form (PHB p.66), which keeps the druid's mind, features and proficiencies. */
    public WildShapeForm(UUID monsterId, String beastName, String challengeRating, int hpMax, int armourClass,
                         int strength, int dexterity, int constitution, Map<String, Integer> saves,
                         Map<String, Integer> skills, Map<String, Integer> speed, String senses,
                         List<String> damageResistances, List<String> damageImmunities,
                         List<String> damageVulnerabilities, List<String> conditionImmunities, int normalHpCurrent,
                         int normalHpMax, int normalArmourClass, int druidLevel) {
        this(monsterId, beastName, challengeRating, hpMax, armourClass, strength, dexterity, constitution, saves,
                skills, speed, senses, damageResistances, damageImmunities, damageVulnerabilities, conditionImmunities,
                normalHpCurrent, normalHpMax, normalArmourClass, druidLevel, null, null, null, null, null, null, null,
                null);
    }

    /** The form with its stat block's traits, as stored (D191, D192). */
    public WildShapeForm withTraits(String statBlockTraits) {
        return new WildShapeForm(monsterId, beastName, challengeRating, hpMax, armourClass, strength, dexterity,
                constitution, saves, skills, speed, senses, damageResistances, damageImmunities, damageVulnerabilities,
                conditionImmunities, normalHpCurrent, normalHpMax, normalArmourClass, druidLevel, spell, creatureType,
                intelligence, wisdom, charisma, keepsOwnTraits, canCast, statBlockTraits);
    }

    /** Whether this is a druid's Wild Shape rather than a spell's form (D192). */
    @JsonIgnore
    public boolean wildShape() {
        return spell == null;
    }

    /**
     * Whether the creature keeps its own proficiencies, features and defences in the form, as in Wild Shape (PHB p.67)
     * and Shapechange (PHB p.274), rather than taking only the form's, as with Polymorph (PHB p.266).
     */
    @JsonIgnore
    public boolean keepsOwn() {
        return keepsOwnTraits != null ? keepsOwnTraits : spell == null;
    }

    /** The form with the creature's own hit points, maximum and AC replaced, for a new form taken during a spell. */
    public WildShapeForm withNormal(int hpCurrent, int hpMax, int armourClass) {
        return new WildShapeForm(monsterId, beastName, challengeRating, this.hpMax, this.armourClass, strength,
                dexterity, constitution, saves, skills, speed, senses, damageResistances, damageImmunities,
                damageVulnerabilities, conditionImmunities, hpCurrent, hpMax, armourClass, druidLevel, spell,
                creatureType, intelligence, wisdom, charisma, keepsOwnTraits, canCast, traits);
    }

    public WildShapeForm {
        saves = saves != null ? Map.copyOf(saves) : Map.of();
        skills = skills != null ? Map.copyOf(skills) : Map.of();
        speed = speed != null ? Collections.unmodifiableMap(new LinkedHashMap<>(speed)) : Map.of();
        damageResistances = damageResistances != null ? List.copyOf(damageResistances) : List.of();
        damageImmunities = damageImmunities != null ? List.copyOf(damageImmunities) : List.of();
        damageVulnerabilities = damageVulnerabilities != null ? List.copyOf(damageVulnerabilities) : List.of();
        conditionImmunities = conditionImmunities != null ? List.copyOf(conditionImmunities) : List.of();
    }

    /** The beast form on the participant's {@code wild shape} entry, or empty when it isn't transformed. */
    public static Optional<WildShapeForm> of(EncounterParticipant participant) {
        return of(participant.getActiveConditions());
    }

    /**
     * The form on an entry among these conditions, or empty: a {@code wild shape} entry's, or a spell's entry that
     * carries one, such as Polymorph's {@code polymorphed} (D192).
     */
    public static Optional<WildShapeForm> of(List<ConditionEntry> conditions) {
        return entry(conditions).map(ConditionEntry::wildShape);
    }

    /** The entry among these conditions that carries a form, or empty (D192). */
    public static Optional<ConditionEntry> entry(List<ConditionEntry> conditions) {
        if (conditions == null) {
            return Optional.empty();
        }
        return conditions.stream()
                .filter(c -> c != null && c.wildShape() != null)
                .findFirst();
    }
}
