package com.tabletophelper.character;

import com.tabletophelper.character.state.ProficienciesGained;
import com.tabletophelper.reference.Subclass;
import com.tabletophelper.shared.ConflictException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The bonus proficiencies a subclass gives when it is chosen, at its subclass level, such as a Life Domain's heavy
 * armour (PHB p.60) or a College of Valor's medium armour, shields and martial weapons (PHB p.55): the subclass's
 * {@code additional_proficiencies}, which the content loader fills from {@code data/content/subclass-proficiencies.json}.
 * Only fixed grants are there; a proficiency the subclass lets the player choose isn't.
 */
final class SubclassProficiencies {

    private SubclassProficiencies() {
    }

    /**
     * What the subclass gives; empty for a subclass that gives none.
     *
     * @throws ConflictException when the subclass's proficiencies can't be read (D155)
     */
    static ProficienciesGained of(ObjectMapper objectMapper, Subclass subclass) {
        String json = subclass.getAdditionalProficiencies();
        if (json == null || json.isBlank()) {
            return new ProficienciesGained(null, null, null, null, null);
        }
        try {
            ProficienciesGained grants = objectMapper.readValue(json, ProficienciesGained.class);
            return grants != null ? grants : new ProficienciesGained(null, null, null, null, null);
        } catch (JacksonException e) {
            throw new ConflictException("The bonus proficiencies of the subclass " + subclass.getName()
                    + " can't be read");
        }
    }

    /** The sheet's armour, weapon, tool, skill and language proficiencies. */
    static ProficienciesGained sheet(PlayerCharacter character) {
        return new ProficienciesGained(character.getArmorProficiencies(), character.getWeaponProficiencies(),
                character.getToolProficiencies(), character.getSkillProficiencies(),
                character.getLanguageProficiencies());
    }

    /** What of the grants the sheet lacks, each kind compared in any case; a kind it lacks none of is left out. */
    static ProficienciesGained missing(PlayerCharacter character, ProficienciesGained grants) {
        return missing(sheet(character), grants);
    }

    /** What of the grants the proficiencies held lack, each kind compared in any case. */
    static ProficienciesGained missing(ProficienciesGained held, ProficienciesGained grants) {
        return new ProficienciesGained(
                lacking(held.armor(), grants.armor()),
                lacking(held.weapons(), grants.weapons()),
                lacking(held.tools(), grants.tools()),
                lacking(held.skills(), grants.skills()),
                lacking(held.languages(), grants.languages()));
    }

    /** The proficiencies held with those added. */
    static ProficienciesGained plus(ProficienciesGained held, ProficienciesGained added) {
        return new ProficienciesGained(joined(held.armor(), added.armor()), joined(held.weapons(), added.weapons()),
                joined(held.tools(), added.tools()), joined(held.skills(), added.skills()),
                joined(held.languages(), added.languages()));
    }

    private static List<String> joined(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first != null ? first : List.of());
        if (second != null) all.addAll(second);
        return all;
    }

    /** The grants as the report shows them, such as "armour heavy; weapons martial". */
    static String describe(ProficienciesGained gained) {
        List<String> parts = new ArrayList<>();
        add(parts, "armour", gained.armor());
        add(parts, "weapons", gained.weapons());
        add(parts, "tools", gained.tools());
        add(parts, "skills", gained.skills());
        add(parts, "languages", gained.languages());
        return String.join("; ", parts);
    }

    private static void add(List<String> parts, String kind, List<String> items) {
        if (items != null && !items.isEmpty()) {
            parts.add(kind + " " + String.join(", ", items));
        }
    }

    // The granted items the sheet's list doesn't have, each once; null for none
    private static List<String> lacking(List<String> sheet, List<String> granted) {
        if (granted == null || granted.isEmpty()) {
            return null;
        }
        List<String> have = new ArrayList<>();
        for (String item : sheet != null ? sheet : List.<String>of()) {
            if (item != null) have.add(key(item));
        }
        List<String> lacking = new ArrayList<>();
        for (String item : granted) {
            if (item == null || item.isBlank() || have.contains(key(item))) continue;
            have.add(key(item));
            lacking.add(item);
        }
        return lacking.isEmpty() ? null : lacking;
    }

    private static String key(String item) {
        return item.trim().toLowerCase(Locale.ROOT);
    }
}
