package com.tabletophelper.character;

import java.util.List;
import java.util.Locale;

/**
 * Thieves' tools as a Rogue's Expertise (PHB p.96): "choose two of your skill proficiencies, or one of your skill
 * proficiencies and your proficiency with thieves' tools", and at 6th level "two more of your proficiencies (in skills
 * or with thieves' tools)". A Bard's Expertise (PHB p.54) takes skills only. The tool's Expertise is kept with the
 * skills' in the character's Expertise list, as "Thieves' Tools", so no stored shape changes.
 */
final class ThievesTools {

    /** The name thieves' tools' Expertise is stored and shown under. */
    static final String NAME = "Thieves' Tools";

    private static final String KEY = "thieves' tools";

    private ThievesTools() {
    }

    /** Whether the name is thieves' tools, in any case, with a straight or curly apostrophe. */
    static boolean is(String name) {
        return name != null && name.trim().replace('’', '\'').toLowerCase(Locale.ROOT).equals(KEY);
    }

    /** Whether the class's Expertise can take thieves' tools: a Rogue's only. */
    static boolean offeredBy(String className) {
        return "Rogue".equalsIgnoreCase(className == null ? null : className.trim());
    }

    /** Whether the list, tool proficiencies or Expertise, names thieves' tools. */
    static boolean in(List<String> names) {
        return names != null && names.stream().anyMatch(ThievesTools::is);
    }

    /** Whether the character is proficient with thieves' tools, which a Rogue always is (PHB p.95). */
    static boolean heldBy(PlayerCharacter character) {
        return in(character.getToolProficiencies());
    }
}
