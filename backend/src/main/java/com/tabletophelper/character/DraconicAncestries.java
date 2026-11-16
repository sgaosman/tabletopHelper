package com.tabletophelper.character;

import com.tabletophelper.character.state.LevelChoices;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.reference.Race;
import com.tabletophelper.rules.combat.RacialFeatures.DraconicAncestry;
import com.tabletophelper.shared.ConflictException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A Dragonborn's draconic ancestry (PHB p.34, D208): which races owe the choice, and the one a character made. The
 * choice is a Pending Choice of its race (DRACONIC_ANCESTRY), recorded on its 1st level's choices; its Breath Weapon
 * reads the ancestry in an encounter, and choosing it gives the resistance to the ancestry's damage type.
 */
public final class DraconicAncestries {

    /** The trait whose race owes the choice: the PHB Dragonborn's, and the EGW Draconblood's and Ravenite's. */
    public static final String TRAIT = "Draconic Ancestry";

    private DraconicAncestries() {
    }

    /**
     * Whether the race has the Draconic Ancestry trait, among its traits as the races loader merged them (a subrace
     * has its parent's). The FTD dragonborn's Chromatic, Metallic and Gem Ancestry aren't it.
     *
     * @throws ConflictException when the race's traits can't be read (D155)
     */
    public static boolean owedBy(Race race, ObjectMapper objectMapper) {
        if (race == null || race.getTraits() == null || race.getTraits().isBlank()) {
            return false;
        }
        JsonNode traits;
        try {
            traits = objectMapper.readTree(race.getTraits());
        } catch (JacksonException e) {
            throw new ConflictException("The traits of the race " + race.getName() + " can't be read");
        }
        if (!traits.isArray()) {
            return false;
        }
        for (JsonNode trait : traits) {
            if (TRAIT.equalsIgnoreCase(trait.path("name").asString("").trim())) {
                return true;
            }
        }
        return false;
    }

    /** The ancestry the character's levels record, or none, as a character made before D208 or still choosing. */
    public static Optional<DraconicAncestry> of(PlayerCharacter character) {
        List<LevelHistoryEntry> history = character.getLevelHistory();
        if (history == null) {
            return Optional.empty();
        }
        for (LevelHistoryEntry level : history) {
            LevelChoices choices = level != null ? level.choices() : null;
            if (choices != null && choices.draconicAncestry() != null) {
                Optional<DraconicAncestry> found = DraconicAncestry.of(choices.draconicAncestry());
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The dragons a choice made now offers: those whose damage type the sheet resists already, as a character created
     * before D208 chose its resistance in the wizard, or all ten when it resists none of their types.
     */
    static List<String> options(PlayerCharacter character) {
        List<String> resisted = character.getDamageResistances() != null ? character.getDamageResistances() : List.of();
        List<String> matching = new ArrayList<>();
        for (DraconicAncestry ancestry : DraconicAncestry.values()) {
            if (resisted.stream().anyMatch(type -> type != null && type.trim().equalsIgnoreCase(ancestry.damageType()))) {
                matching.add(ancestry.dragon());
            }
        }
        return matching.isEmpty() ? DraconicAncestry.dragons() : matching;
    }

    /** Gives the sheet the resistance to the ancestry's damage type, unless it has it (PHB p.34). */
    static void grantResistance(PlayerCharacter character, DraconicAncestry ancestry) {
        List<String> resistances = character.getDamageResistances() != null
                ? new ArrayList<>(character.getDamageResistances()) : new ArrayList<>();
        if (resistances.stream().noneMatch(type -> type != null
                && type.trim().equalsIgnoreCase(ancestry.damageType()))) {
            resistances.add(ancestry.damageType());
            character.setDamageResistances(resistances);
        }
    }
}
