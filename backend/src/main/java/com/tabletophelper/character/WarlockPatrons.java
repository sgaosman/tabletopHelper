package com.tabletophelper.character;

import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.LevelChoices;
import com.tabletophelper.character.state.LevelHistoryEntry;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What the Genie's and the Fathomless's features (TCE) read from a warlock's sheet (D242):
 * <ul>
 *   <li>The Genie's kind, the {@code subclassOption} recorded on the level that records the subclass (D240), and the
 *       damage type it gives Genie's Wrath (Warlock 1) and Elemental Gift's resistance (Warlock 6): "bludgeoning (dao),
 *       thunder (djinni), fire (efreeti), or cold (marid)".</li>
 *   <li>Grasping Tentacles (Fathomless 10): "Whenever you cast this spell [Evard's black tentacles], ... temporary hit
 *       points equal to your warlock level. Moreover, damage can't break your concentration on this spell."</li>
 * </ul>
 * Until a Genie warlock's kind is chosen, none of the kind's features apply.
 */
public final class WarlockPatrons {

    private WarlockPatrons() {
    }

    public static final String WARLOCK = "Warlock";
    public static final String GENIE = "The Genie";
    public static final String FATHOMLESS = "The Fathomless";

    /** The spell Grasping Tentacles teaches and bolsters. */
    public static final String BLACK_TENTACLES = "Evard's Black Tentacles";

    /** The warlock level Elemental Gift comes at. */
    public static final int ELEMENTAL_GIFT_LEVEL = 6;
    /** The warlock level Grasping Tentacles comes at. */
    public static final int GRASPING_TENTACLES_LEVEL = 10;

    // The damage type each kind gives, as Genie's Wrath and Elemental Gift both list it
    private static final Map<String, String> KIND_DAMAGE = Map.of(
            "dao", "bludgeoning",
            "djinni", "thunder",
            "efreeti", "fire",
            "marid", "cold");

    /**
     * The character's warlock level when its warlock subclass is the one named, from its class entries, or its single
     * class, level and subclass when it has none; 0 otherwise.
     */
    public static int patronLevel(PlayerCharacter character, String patron) {
        if (character == null) return 0;
        List<ClassEntry> entries = character.getMulticlassEntries();
        if (entries != null && !entries.isEmpty()) {
            return entries.stream()
                    .filter(entry -> entry != null && WARLOCK.equalsIgnoreCase(trim(entry.className()))
                            && patron.equalsIgnoreCase(trim(entry.subclassName())))
                    .mapToInt(ClassEntry::level)
                    .sum();
        }
        boolean single = WARLOCK.equalsIgnoreCase(trim(character.getCharacterClass()))
                && patron.equalsIgnoreCase(trim(character.getSubclass()));
        return single && character.getLevel() != null ? character.getLevel() : 0;
    }

    /** The Genie's kind the character's levels record, such as "Dao"; empty for no Genie warlock or no kind yet. */
    public static Optional<String> genieKind(PlayerCharacter character) {
        if (patronLevel(character, GENIE) < 1) return Optional.empty();
        for (LevelHistoryEntry level : CharacterUpdates.orEmpty(character.getLevelHistory())) {
            LevelChoices choices = level.choices();
            if (choices != null && choices.subclass() != null
                    && GENIE.equalsIgnoreCase(trim(choices.subclass().subclassName()))
                    && choices.subclassOption() != null && !choices.subclassOption().isBlank()) {
                return Optional.of(choices.subclassOption().trim());
            }
        }
        return Optional.empty();
    }

    /** The damage type the kind gives, such as "fire" for an efreeti; empty for a name that is no kind. */
    public static Optional<String> kindDamageType(String kind) {
        return kind == null ? Optional.empty()
                : Optional.ofNullable(KIND_DAMAGE.get(kind.trim().toLowerCase(Locale.ROOT)));
    }

    /** The damage type of the character's Genie's Wrath, from its recorded kind; empty without one. */
    public static Optional<String> geniesWrathType(PlayerCharacter character) {
        return genieKind(character).flatMap(WarlockPatrons::kindDamageType);
    }

    /** The resistance Elemental Gift gives, from Warlock 6 with a kind recorded; empty otherwise. */
    public static Optional<String> elementalGiftResistance(PlayerCharacter character) {
        if (patronLevel(character, GENIE) < ELEMENTAL_GIFT_LEVEL) return Optional.empty();
        return geniesWrathType(character);
    }

    /** Whether the character has Grasping Tentacles: a Fathomless warlock of 10th level or higher. */
    public static boolean graspingTentacles(PlayerCharacter character) {
        return patronLevel(character, FATHOMLESS) >= GRASPING_TENTACLES_LEVEL;
    }

    /** Whether the spell is Evard's black tentacles, in any case. */
    public static boolean blackTentacles(String spellName) {
        return spellName != null && BLACK_TENTACLES.equalsIgnoreCase(spellName.trim());
    }

    private static String trim(String value) {
        return value != null ? value.trim() : "";
    }
}
