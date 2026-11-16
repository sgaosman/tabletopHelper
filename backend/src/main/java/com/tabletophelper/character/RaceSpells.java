package com.tabletophelper.character;

import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.reference.Race;
import com.tabletophelper.shared.ConflictException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A race's fixed spells as the race data gives them now, against the sheet's, for the recompute-characters job (D231).
 * The creation wizard copies a race's fixed spells into the sheet, with how each is cast; before D231 the race loader
 * gave a known levelled spell, such as a Hexblood's hex (VRGR p.18), as at will, and dropped the spells cast once
 * between short or long rests, such as a VGM firbolg's detect magic and disguise self (VGM p.107). A sheet's race spell
 * whose level, at will, uses or reset differ from the data's takes the data's, and a fixed spell available at the
 * character's level that the sheet lacks is added with the race's source. Races that offer a choice of spell lists
 * ({@code options}) are left alone, as the spells chosen aren't recorded apart.
 */
final class RaceSpells {

    private RaceSpells() {
    }

    /**
     * One change to a sheet's race spells.
     *
     * @param spell  the spell, by the data's name
     * @param before how the sheet casts it, or "none" for one it lacks
     * @param after  how the data casts it
     * @param entry  the sheet's entry as it should be
     */
    record Change(String spell, String before, String after, KnownSpell entry) {}

    /**
     * The changes the character's race spells need; none for a character without a race or race spells.
     *
     * @throws ConflictException when the race's spells can't be read (D155)
     */
    static List<Change> changes(ObjectMapper objectMapper, PlayerCharacter character) {
        Race race = character.getRaceRef();
        if (race == null || race.getAdditionalSpells() == null || race.getAdditionalSpells().isBlank()) {
            return List.of();
        }
        JsonNode fixed;
        try {
            fixed = objectMapper.readTree(race.getAdditionalSpells()).path("fixedSpells");
        } catch (JacksonException e) {
            throw new ConflictException("The spells of the race " + race.getName() + " can't be read");
        }
        int level = character.getLevel() != null ? character.getLevel() : 1;
        String source = "race:" + race.getName();
        List<KnownSpell> known = CharacterUpdates.orEmpty(character.getSpellsKnown());
        List<Change> changes = new ArrayList<>();
        for (JsonNode spell : fixed) {
            String name = spell.path("name").asString("").trim();
            int unlocks = spell.path("unlocksAtLevel").asInt(1);
            if (name.isEmpty() || unlocks > level) continue;
            boolean atWill = spell.path("atWill").asBoolean(false);
            int uses = spell.path("usesPerLongRest").asInt(0);
            KnownSpell wanted = new KnownSpell(null, name, spell.path("level").asInt(0), source, null, null,
                    atWill ? Boolean.TRUE : null, uses > 0 ? uses : null, unlocks > 1 ? unlocks : null,
                    uses > 0 && spell.path("usesResetOn").isString() ? spell.path("usesResetOn").asString() : null);
            KnownSpell held = known.stream()
                    .filter(entry -> entry.source() != null && entry.source().startsWith("race:")
                            && entry.name() != null && key(entry.name()).equals(key(name)))
                    .findFirst()
                    .orElse(null);
            if (held == null) {
                changes.add(new Change(name, "none", describe(wanted), wanted));
            } else if (!sameCasting(held, wanted)) {
                changes.add(new Change(name, describe(held), describe(wanted), new KnownSpell(held.id(), held.name(),
                        wanted.level() > 0 ? wanted.level() : held.level(), held.source(), held.prepared(),
                        held.alwaysPrepared(), wanted.atWill(), wanted.usesPerLongRest(), held.unlocksAtLevel(),
                        wanted.usesResetOn())));
            }
        }
        return changes;
    }

    /** Applies the changes to the sheet; the caller saves the character. */
    static void apply(PlayerCharacter character, List<Change> changes) {
        List<KnownSpell> known = CharacterUpdates.copy(character.getSpellsKnown());
        for (Change change : changes) {
            int at = -1;
            for (int i = 0; i < known.size(); i++) {
                KnownSpell entry = known.get(i);
                if (entry.source() != null && entry.source().startsWith("race:") && entry.name() != null
                        && key(entry.name()).equals(key(change.spell()))) {
                    at = i;
                    break;
                }
            }
            if (at >= 0) {
                known.set(at, change.entry());
            } else {
                known.add(change.entry());
            }
        }
        character.setSpellsKnown(known);
    }

    private static boolean sameCasting(KnownSpell held, KnownSpell wanted) {
        return (wanted.level() == 0 || held.level() == wanted.level())
                && Boolean.TRUE.equals(held.atWill()) == Boolean.TRUE.equals(wanted.atWill())
                && Objects.equals(positive(held.usesPerLongRest()), positive(wanted.usesPerLongRest()))
                && Objects.equals(held.usesResetOn(), wanted.usesResetOn());
    }

    private static Integer positive(Integer uses) {
        return uses != null && uses > 0 ? uses : null;
    }

    // Such as "level 1, 1/short rest" or "level 0, at will"
    private static String describe(KnownSpell spell) {
        String casting = Boolean.TRUE.equals(spell.atWill()) ? "at will"
                : positive(spell.usesPerLongRest()) != null
                ? spell.usesPerLongRest() + (spell.usesResetOnShortRest() ? "/short rest" : "/long rest")
                : "with slots";
        return "level " + spell.level() + ", " + casting;
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
