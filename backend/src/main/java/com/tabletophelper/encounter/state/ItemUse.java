package com.tabletophelper.encounter.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Locale;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * One item a participant has used up in the encounter, such as a potion drunk or a spell scroll read (D210), kept in
 * {@code encounter_participants.items_used} (V38). Undo takes it back with the rest of the participant's state, and
 * Write-back takes one of the item off the character's sheet when the encounter ends.
 *
 * @param name  the inventory entry's name, as the sheet has it
 * @param spell the spell a spell scroll held, which tells two scrolls of one level apart; null for anything else
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ItemUse(@Schema(requiredMode = REQUIRED) String name, String spell) {

    /**
     * How the inventory entry is known to Write-back: its name, with a scroll's spell after a bar, as
     * "Spell Scroll (1st Level)|Shield".
     */
    public static String key(String name, String spell) {
        return spell == null || spell.isBlank() ? name.trim() : name.trim() + "|" + spell.trim();
    }

    @JsonIgnore
    public String key() {
        return key(name, spell);
    }

    /** Whether this is a use of the entry with that name and spell, ignoring case. */
    public boolean of(String entryName, String entrySpell) {
        return key().toLowerCase(Locale.ROOT).equals(key(entryName, entrySpell).toLowerCase(Locale.ROOT));
    }

    /** How many of the entry with that name and spell the uses take. */
    public static int count(List<ItemUse> uses, String entryName, String entrySpell) {
        return uses == null ? 0 : (int) uses.stream().filter(use -> use != null && use.of(entryName, entrySpell)).count();
    }
}
