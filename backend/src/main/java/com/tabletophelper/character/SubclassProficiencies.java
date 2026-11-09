package com.tabletophelper.character;

import com.tabletophelper.character.state.ProficienciesGained;
import com.tabletophelper.reference.Subclass;
import com.tabletophelper.rules.character.ProficiencyLists;
import com.tabletophelper.shared.ConflictException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The bonus proficiencies a subclass gives when it is chosen, at its subclass level, such as a Life Domain's heavy
 * armour (PHB p.60) or a College of Valor's medium armour, shields and martial weapons (PHB p.55): the subclass's
 * {@code additional_proficiencies}, which the content loader fills from {@code data/content/subclass-proficiencies.json}.
 * Those the subclass lets the player choose, such as the College of Lore's three skills (PHB p.54), are its
 * {@code choices}, each owed as a PROFICIENCY Pending Choice on the level that records the subclass, or for one with a
 * {@code level}, such as a Way of the Kensei monk's further kensei weapons at Monk 6, 11 and 17 (XGE p.34), on that
 * class level. Those granted unless the character has them already, such as an Alchemist's alchemist's supplies (TCE
 * p.14) or a Path of the Giant barbarian's Giant (BGG p.11), are its {@code conditional} grants: each gives its one
 * proficiency, or when the character has it, owes its choice {@code otherwise}.
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

    /**
     * A choice of bonus proficiencies the subclass gives.
     *
     * @param count     how many to choose
     * @param options   what may be chosen, by kind
     * @param expertise whether the skills chosen gain Expertise too, as the Knowledge Domain's do (PHB p.59)
     * @param kensei    whether the weapons chosen become kensei weapons (XGE p.34)
     * @param level     the class level that owes it; null for the subclass level
     */
    record Choice(int count, ProficienciesGained options, boolean expertise, boolean kensei, Integer level) {

        /** A choice owed on the subclass level that makes no kensei weapons. */
        Choice(int count, ProficienciesGained options, boolean expertise) {
            this(count, options, expertise, false, null);
        }

        /** Whether the class level owes it, for a class whose subclass level is the one given. */
        boolean owedAt(int classLevel, int subclassLevel) {
            return (level != null ? level : subclassLevel) == classLevel;
        }
    }

    /**
     * A proficiency the subclass grants unless the character has it already, when it owes the choice otherwise, such
     * as an Alchemist's alchemist's supplies or one other type of artisan's tools (TCE p.14).
     */
    record Conditional(Option grant, Choice otherwise) {}

    /** One proficiency a choice offers, of a kind of {@link ProficienciesGained}: "skills", "languages" and so on. */
    record Option(String kind, String name) {}

    /**
     * The choices of bonus proficiencies the subclass gives, in the file's order; empty for a subclass that gives none.
     *
     * @throws ConflictException when the subclass's proficiencies can't be read (D155)
     */
    static List<Choice> choices(ObjectMapper objectMapper, Subclass subclass) {
        String json = subclass.getAdditionalProficiencies();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        ConflictException unreadable = new ConflictException("The bonus proficiencies of the subclass "
                + subclass.getName() + " can't be read");
        try {
            JsonNode choices = objectMapper.readTree(json).path("choices");
            if (choices.isMissingNode() || choices.isNull()) {
                return List.of();
            }
            if (!choices.isArray()) {
                throw unreadable;
            }
            List<Choice> found = new ArrayList<>();
            for (JsonNode choice : choices) {
                found.add(choice(objectMapper, choice, unreadable));
            }
            return found;
        } catch (JacksonException e) {
            throw unreadable;
        }
    }

    /**
     * The choices the subclass gives that the class level owes, for a class whose subclass level is the one given.
     *
     * @throws ConflictException when the subclass's proficiencies can't be read (D155)
     */
    static List<Choice> choicesAt(ObjectMapper objectMapper, Subclass subclass, int classLevel, int subclassLevel) {
        return choices(objectMapper, subclass).stream()
                .filter(choice -> choice.owedAt(classLevel, subclassLevel))
                .toList();
    }

    /**
     * The proficiencies the subclass grants unless the character has them, in the file's order; empty for a subclass
     * with none.
     *
     * @throws ConflictException when the subclass's proficiencies can't be read (D155)
     */
    static List<Conditional> conditionals(ObjectMapper objectMapper, Subclass subclass) {
        String json = subclass.getAdditionalProficiencies();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        ConflictException unreadable = new ConflictException("The bonus proficiencies of the subclass "
                + subclass.getName() + " can't be read");
        try {
            JsonNode conditionals = objectMapper.readTree(json).path("conditional");
            if (conditionals.isMissingNode() || conditionals.isNull()) {
                return List.of();
            }
            if (!conditionals.isArray()) {
                throw unreadable;
            }
            List<Conditional> found = new ArrayList<>();
            for (JsonNode conditional : conditionals) {
                ProficienciesGained grant = objectMapper.treeToValue(conditional, ProficienciesGained.class);
                List<Option> granted = grant != null ? optionsOf(grant) : List.of();
                if (granted.size() != 1 || !conditional.path("otherwise").isObject()) {
                    throw unreadable;
                }
                found.add(new Conditional(granted.getFirst(), choice(objectMapper, conditional.get("otherwise"),
                        unreadable)));
            }
            return found;
        } catch (JacksonException e) {
            throw unreadable;
        }
    }

    private static Choice choice(ObjectMapper objectMapper, JsonNode choice, ConflictException unreadable) {
        int count = choice.path("count").asInt(0);
        ProficienciesGained options = objectMapper.treeToValue(choice, ProficienciesGained.class);
        if (count < 1 || options == null || optionsOf(options).isEmpty()) {
            throw unreadable;
        }
        JsonNode level = choice.path("level");
        return new Choice(count, options, choice.path("expertise").asBoolean(false),
                choice.path("kensei").asBoolean(false), level.isInt() ? level.asInt() : null);
    }

    /** What a choice offers, skills first, then languages, tools, weapons and armour, each in its list's order. */
    static List<Option> optionsOf(ProficienciesGained options) {
        List<Option> all = new ArrayList<>();
        addOptions(all, "skills", options.skills());
        addOptions(all, "languages", options.languages());
        addOptions(all, "tools", options.tools());
        addOptions(all, "weapons", options.weapons());
        addOptions(all, "armor", options.armor());
        return all;
    }

    private static void addOptions(List<Option> all, String kind, List<String> names) {
        for (String name : names != null ? names : List.<String>of()) {
            if (name != null && !name.isBlank()) all.add(new Option(kind, name.trim()));
        }
    }

    /** The option of the choice with the name, in any case. */
    static Optional<Option> find(ProficienciesGained options, String name) {
        String wanted = key(name);
        return optionsOf(options).stream().filter(option -> key(option.name()).equals(wanted)).findFirst();
    }

    /**
     * Whether the proficiencies held include the option, in any case; a weapon also when they include its category,
     * simple or martial (PHB p.149), or its name in the plural, as "Daggers".
     */
    static boolean holds(ProficienciesGained held, Option option) {
        List<String> list = switch (option.kind()) {
            case "armor" -> held.armor();
            case "weapons" -> held.weapons();
            case "tools" -> held.tools();
            case "skills" -> held.skills();
            case "languages" -> held.languages();
            default -> null;
        };
        String wanted = key(option.name());
        Optional<String> category = option.kind().equals("weapons")
                ? ProficiencyLists.weaponCategory(option.name()) : Optional.empty();
        for (String item : list != null ? list : List.<String>of()) {
            if (item == null) continue;
            String have = key(item);
            if (have.equals(wanted)) return true;
            if (option.kind().equals("weapons")) {
                if (have.equals(wanted + "s")) return true;
                if (category.isPresent() && (have.equals(category.get()) || have.equals(category.get() + " weapons"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether the names include the option's, in any case. */
    static boolean named(List<String> names, Option option) {
        String wanted = key(option.name());
        return names != null && names.stream().anyMatch(name -> name != null && key(name).equals(wanted));
    }

    /** The options as proficiencies gained, each under its kind. */
    static ProficienciesGained grants(List<Option> options) {
        return new ProficienciesGained(names(options, "armor"), names(options, "weapons"), names(options, "tools"),
                names(options, "skills"), names(options, "languages"));
    }

    private static List<String> names(List<Option> options, String kind) {
        List<String> names = options.stream().filter(option -> option.kind().equals(kind)).map(Option::name).toList();
        return names.isEmpty() ? null : names;
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
