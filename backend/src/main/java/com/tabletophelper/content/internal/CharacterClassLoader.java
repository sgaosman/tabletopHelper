package com.tabletophelper.content.internal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.CharacterClassRepository;
import com.tabletophelper.reference.Subclass;
import com.tabletophelper.reference.SubclassRepository;
import com.tabletophelper.rules.character.ClassTables;
import com.tabletophelper.rules.character.ProficiencyLists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class CharacterClassLoader implements ContentLoader {

    private final CharacterClassRepository classRepository;
    private final SubclassRepository subclassRepository;
    private final ObjectMapper objectMapper;
    private final ClassTables classTables;

    // The effects the 5etools data doesn't give, such as the Draconic Bloodline's hit points, by the subclass's
    // natural key (V28)
    static final String SUBCLASS_EFFECTS_FILE = "data/content/subclass-effects.json";
    static final Set<String> SUBCLASS_EFFECTS = Set.of("hpPerClassLevel", "unarmouredAcBase", "unarmouredAcDex",
            "unarmouredAcCon", "unarmouredAcWithArmour");

    // The bonus proficiencies a subclass gives at its subclass level, such as a Life Domain's heavy armour (PHB p.60),
    // by the subclass's natural key: the 5etools data has them only in the features' text. Fixed grants are listed by
    // kind; those the player chooses, such as the College of Lore's three skills (PHB p.54), under "choices", each with
    // how many to choose and the options by kind, a name starting with * standing for a list of ProficiencyLists, for a
    // choice of skills whether they gain Expertise too, for a choice owed after the subclass level the class level that
    // owes it, and for a Way of the Kensei monk's kensei weapons (XGE p.34) that they are; those granted unless the
    // character has them already, when another is chosen instead, such as an Alchemist's alchemist's supplies (TCE
    // p.14), under "conditional", each with the one proficiency and the choice "otherwise", and for one granted after
    // the subclass level, as a Samurai's Wisdom saving throws at Fighter 7 (XGE p.31), its class level, and for a skill
    // that gains Expertise either way, as a Purple Dragon Knight's Persuasion (SCAG p.128), "expertise"; the skills of
    // its fixed grants that gain Expertise too, as a Scout's Nature and Survival (XGE p.47), under "expertise"; and the
    // cantrips the player chooses, as an Arcane Archer's prestidigitation or druidcraft (XGE p.28), under "cantrips",
    // each with how many and the spells by name or the spell list they come from
    static final String SUBCLASS_PROFICIENCIES_FILE = "data/content/subclass-proficiencies.json";
    static final Set<String> PROFICIENCY_KINDS = Set.of("armor", "weapons", "tools", "skills", "languages", "saves");
    static final String CHOICES = "choices";
    static final String CONDITIONAL = "conditional";
    static final String OTHERWISE = "otherwise";
    static final String EXPERTISE = "expertise";
    static final String CANTRIPS = "cantrips";
    // The saving throws, as the class data abbreviates them
    static final List<String> SAVES = List.of("STR", "DEX", "CON", "INT", "WIS", "CHA");
    // What a choice says besides its count and options
    private static final Set<String> CHOICE_FLAGS = Set.of("expertise", "kensei", "level");

    // Class files we don't load: Mystic is unofficial playtest material, and sidekicks aren't player classes
    private static final Set<String> SKIP_FILES = Set.of("class-mystic.json", "class-sidekick.json");

    private static final Set<String> PREPARED_CASTERS = Set.of("Cleric", "Druid", "Paladin", "Wizard", "Artificer");
    private static final Set<String> KNOWN_CASTERS = Set.of("Bard", "Ranger", "Sorcerer", "Warlock");

    @Override
    public String name() {
        return "classes";
    }

    @Override
    public List<String> files() {
        return List.of("data/5etools/class/class-*.json", ClassTablesFile.PATH, SUBCLASS_EFFECTS_FILE,
                SUBCLASS_PROFICIENCIES_FILE);
    }

    // The slot progressions and subclass levels come from the class tables, which files() lists; version 2 writes
    // the subclass effects overlay, version 3 the subclasses' bonus proficiencies, and version 4 the text of the
    // features nested inside another, such as the Life Domain's Disciple of Life, version 5 the bonus
    // proficiencies a subclass lets the player choose, and version 6 the conditional grants, the choices owed after
    // the subclass level, the kensei weapons and the Draconic Bloodline's, Storm Sorcery's and Circle of the
    // Shepherd's languages, version 7 saving throws, conditional grants after the subclass level and with
    // Expertise, the Scout's Expertise and the subclass cantrips, version 8 the School of Illusion's cantrip
    // granted unless known (Improved Minor Illusion, PHB p.118), and version 9 (D231) the granted spells (known spells
    // and the options, such as a Circle of the Land's terrains, whose prepared spells leave the always-prepared list),
    // spell names without their |source suffix, cantrips granted without a fallback choice and cantrip choices by school,
    // and version 10 an option's expanded spells, so the Genie's four kinds are options (TCE p.73), and the spells a
    // subclass teaches with a use a long rest, as the Fathomless's Evard's black tentacles (TCE p.72)
    @Override
    public int version() {
        return 10;
    }

    // The 5etools entries that place another feature's text inside a feature, such as the Life Domain's Bonus
    // Proficiency and Disciple of Life (PHB p.60) or the Monk's Flurry of Blows inside Ki (PHB p.78)
    static final String REF_CLASS_FEATURE = "refClassFeature";
    static final String REF_SUBCLASS_FEATURE = "refSubclassFeature";

    // How deep features nested inside nested features are followed, so a reference back to a parent can't loop
    private static final int MAX_NESTING = 4;

    // 5etools leaves a reference's sources blank for the Player's Handbook
    private static final String DEFAULT_SOURCE = "PHB";

    /** Loads the classes, then the subclasses under them. */
    @Override
    public void load(LoadContext context) {
        List<CharacterClass> allClasses = new ArrayList<>();
        List<SubclassData> allSubclassData = new ArrayList<>();

        for (LoadContext.DataFile file : context.files("data/5etools/class/class-*.json")) {
            String filename = file.name();
            if (filename == null || filename.startsWith("fluff-")) {
                context.skip("fluff files");
                continue;
            }
            if (SKIP_FILES.contains(filename)) {
                context.skip("SKIP_FILES");
                continue;
            }

            JsonNode root = context.read(file);
            JsonNode classArray = root.get("class");
            if (classArray == null || !classArray.isArray()) {
                context.skip("files with no class array");
                continue;
            }

            Map<String, JsonNode> classFeatureMap = buildFeatureMap(root.get("classFeature"));
            Map<String, JsonNode> subclassFeatureMap = buildSubclassFeatureMap(root.get("subclassFeature"));
            NestedFeatures nested = NestedFeatures.of(root.get("classFeature"), root.get("subclassFeature"));

            for (JsonNode classNode : classArray) {
                allClasses.add(LoadContext.reading(filename + ": class " + classNode.path("name").asString("?") + "|"
                        + classNode.path("source").asString(""), () -> parseClass(classNode, classFeatureMap, nested)));
            }

            JsonNode subclassArray = root.get("subclass");
            if (subclassArray != null && subclassArray.isArray()) {
                for (JsonNode scNode : subclassArray) {
                    allSubclassData.add(new SubclassData(filename, scNode, subclassFeatureMap, nested));
                }
            }
        }

        Map<String, CharacterClass> classLookup = context.upsert("character_classes", classRepository, allClasses,
                cc -> LoadContext.nameAndSource(cc.getName(), cc.getSource()));

        List<Subclass> allSubclasses = new ArrayList<>();
        for (SubclassData scd : allSubclassData) {
            JsonNode node = scd.node;
            String className = node.path("className").asString("");
            String classSource = node.path("classSource").asString("");
            CharacterClass parent = classLookup.get(className + "|" + classSource);
            if (parent == null) {
                log.debug("Parent class not found for subclass {}: {}|{}", node.path("name").asString(""), className,
                        classSource);
                context.skip("subclasses of a class not loaded");
                continue;
            }
            allSubclasses.add(LoadContext.reading(scd.filename + ": subclass " + node.path("name").asString("?") + "|"
                    + node.path("source").asString("") + " of " + className + "|" + classSource,
                    () -> parseSubclass(scd, parent)));
        }

        EffectsOverlay effects = EffectsOverlay.read(context, objectMapper, SUBCLASS_EFFECTS_FILE, SUBCLASS_EFFECTS);
        for (Subclass subclass : allSubclasses) {
            subclass.setEffects(effects.effectsOf(subclassKey(subclass)));
        }
        effects.requireEveryRowFound();
        Map<String, String> proficiencies = subclassProficiencies(context);
        Set<String> found = new HashSet<>();
        for (Subclass subclass : allSubclasses) {
            String key = subclassKey(subclass);
            subclass.setAdditionalProficiencies(proficiencies.get(key));
            if (proficiencies.containsKey(key)) found.add(key);
        }
        List<String> missing = new ArrayList<>(proficiencies.keySet());
        missing.removeAll(found);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(SUBCLASS_PROFICIENCIES_FILE + " names subclasses the data doesn't have: "
                    + missing);
        }
        context.upsert("subclasses", subclassRepository, allSubclasses, CharacterClassLoader::subclassKey);
    }

    /**
     * The bonus proficiencies file, as JSON text by subclass natural key: each row an object of the kinds in
     * {@link #PROFICIENCY_KINDS}, each a list of names, {@value #CHOICES}, a list of the choices the subclass
     * gives, each with its {@code count} (at least 1), at least one kind of options, {@code expertise} only for a
     * choice of skills alone, {@code kensei} only for a choice of weapons alone, and {@code level}, the class level
     * from 1 to 20 that owes it when that isn't the subclass level, and {@value #CONDITIONAL}, a list of grants each of
     * one proficiency of one kind, given unless the character has it, and the choice {@value #OTHERWISE} owed when it
     * does, with a count and options alone; such a grant may name its {@code level} after the subclass level and, for a
     * skill, {@code expertise}. {@value #EXPERTISE} lists skills of the row's own that gain Expertise too, and
     * {@value #CANTRIPS} the cantrips the player chooses, each with its count and either {@code spells}, a list of
     * names, or {@code spellList}, a class's list, and or {@code school}, a school of magic, from any class's list
     * without a spellList (D231); one with a {@code grant}, a cantrip's name, gives that cantrip unless the character
     * knows it and only then owes the choice from its spellList, which needs a count of 1, or without a spellList owes
     * nothing (D231). Saving throws ({@code saves}) are abbreviated as the class data has
     * them, such as "WIS". A choice's list names, such as {@code *languages}, are written out as their lists.
     *
     * @throws IllegalStateException for a row that isn't one
     */
    Map<String, String> subclassProficiencies(LoadContext context) {
        JsonNode root = context.read(SUBCLASS_PROFICIENCIES_FILE);
        if (!root.isObject()) {
            throw new IllegalStateException(SUBCLASS_PROFICIENCIES_FILE + " is not an object of natural keys");
        }
        Map<String, String> rows = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> row : root.properties()) {
            JsonNode value = row.getValue();
            if (!value.isObject() || value.isEmpty()) {
                throw new IllegalStateException(SUBCLASS_PROFICIENCIES_FILE + ": " + row.getKey()
                        + " has no proficiencies object");
            }
            ObjectNode stored = objectMapper.createObjectNode();
            for (Map.Entry<String, JsonNode> kind : value.properties()) {
                if (CHOICES.equals(kind.getKey())) {
                    stored.set(CHOICES, choices(row.getKey(), kind.getValue()));
                    continue;
                }
                if (CONDITIONAL.equals(kind.getKey())) {
                    stored.set(CONDITIONAL, conditionals(row.getKey(), kind.getValue()));
                    continue;
                }
                if (EXPERTISE.equals(kind.getKey())) {
                    stored.set(EXPERTISE, expertise(row.getKey(), kind.getValue(), value.path("skills")));
                    continue;
                }
                if (CANTRIPS.equals(kind.getKey())) {
                    stored.set(CANTRIPS, cantrips(row.getKey(), kind.getValue()));
                    continue;
                }
                requireKind(row.getKey(), kind.getKey());
                requireNames(row.getKey() + "'s " + kind.getKey(), kind.getValue());
                requireSaves(row.getKey() + "'s " + kind.getKey(), kind.getKey(), kind.getValue());
                stored.set(kind.getKey(), kind.getValue());
            }
            rows.put(row.getKey(), objectMapper.writeValueAsString(stored));
        }
        return rows;
    }

    // A row's skills that gain Expertise, each one of the row's own skills
    JsonNode expertise(String key, JsonNode expertise, JsonNode skills) {
        String what = key + "'s expertise";
        requireNames(what, expertise);
        for (JsonNode skill : expertise) {
            boolean granted = false;
            for (JsonNode own : skills) {
                granted |= own.asString("").trim().equalsIgnoreCase(skill.asString("").trim());
            }
            if (!granted) {
                throw new IllegalStateException(SUBCLASS_PROFICIENCIES_FILE + ": " + what + " names "
                        + skill.asString("") + ", which isn't among the row's skills");
            }
        }
        return expertise;
    }

    // A row's cantrip choices, each with its count and either spells by name or a spell list
    ArrayNode cantrips(String key, JsonNode cantrips) {
        if (!cantrips.isArray() || cantrips.isEmpty()) {
            throw new IllegalStateException(SUBCLASS_PROFICIENCIES_FILE + ": " + key + "'s cantrips are not a list");
        }
        ArrayNode stored = objectMapper.createArrayNode();
        for (JsonNode cantrip : cantrips) {
            String what = key + "'s cantrip choice " + (stored.size() + 1);
            if (!cantrip.isObject() || !cantrip.path("count").isInt() || cantrip.path("count").asInt() < 1) {
                throw new IllegalStateException(SUBCLASS_PROFICIENCIES_FILE + ": " + what
                        + " has no count of 1 or more");
            }
            for (Map.Entry<String, JsonNode> field : cantrip.properties()) {
                if (!Set.of("count", "spells", "spellList", "school", "grant", "ofCantripsKnown")
                        .contains(field.getKey())) {
                    throw new IllegalStateException(SUBCLASS_PROFICIENCIES_FILE + ": " + what + " has "
                            + field.getKey() + ", which isn't count, spells, spellList, school, grant or "
                            + "ofCantripsKnown");
                }
            }
            // ofCantripsKnown marks a cantrip that is one of the class's Cantrips Known, as an Arcane Trickster's Mage
            // Hand (PHB p.98, D223)
            if (cantrip.has("ofCantripsKnown") && !cantrip.path("ofCantripsKnown").isBoolean()) {
                throw new IllegalStateException(SUBCLASS_PROFICIENCIES_FILE + ": " + what
                        + "'s ofCantripsKnown is true or false");
            }
            boolean grant = cantrip.has("grant");
            if (grant && (!cantrip.path("grant").isString()
                    || cantrip.path("grant").asString("").isBlank() || cantrip.path("count").asInt() != 1
                    || cantrip.has("spells") || cantrip.has("school"))) {
                throw new IllegalStateException(SUBCLASS_PROFICIENCIES_FILE + ": " + what + "'s grant names one "
                        + "cantrip, with a count of 1 and, for a choice owed when it is known, a spellList");
            }
            boolean spells = cantrip.has("spells");
            boolean spellList = cantrip.path("spellList").isString()
                    && !cantrip.path("spellList").asString("").isBlank();
            boolean school = cantrip.path("school").isString() && !cantrip.path("school").asString("").isBlank();
            if ((cantrip.has("spellList") && !spellList) || (cantrip.has("school") && !school)) {
                throw new IllegalStateException(SUBCLASS_PROFICIENCIES_FILE + ": " + what
                        + "'s spellList and school are names");
            }
            // A cantrip granted without a fallback, as the Light Domain's light (PHB p.60), names nothing else; a
            // choice names its spells, its spellList, or its school from any class's list, as the Death Domain's
            // necromancy cantrip (DMG p.96), or a spellList and a school
            boolean chooses = spells || spellList || school;
            if ((grant && spells) || (!grant && !chooses) || (spells && (spellList || school))) {
                throw new IllegalStateException(SUBCLASS_PROFICIENCIES_FILE + ": " + what
                        + " names either its spells, or its spellList or school, or a grant");
            }
            if (spells) requireNames(what + "'s spells", cantrip.get("spells"));
            stored.add(cantrip);
        }
        return stored;
    }

    // A row's choices, each checked, with its list names written out
    ArrayNode choices(String key, JsonNode choices) {
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IllegalStateException(SUBCLASS_PROFICIENCIES_FILE + ": " + key + "'s choices are not a list");
        }
        ArrayNode stored = objectMapper.createArrayNode();
        for (JsonNode choice : choices) {
            String what = key + "'s choice " + (stored.size() + 1);
            stored.add(choice(what, choice, true));
        }
        return stored;
    }

    // A row's conditional grants, each checked, with its choice otherwise's list names written out
    ArrayNode conditionals(String key, JsonNode conditionals) {
        if (!conditionals.isArray() || conditionals.isEmpty()) {
            throw new IllegalStateException(SUBCLASS_PROFICIENCIES_FILE + ": " + key
                    + "'s conditional grants are not a list");
        }
        ArrayNode stored = objectMapper.createArrayNode();
        for (JsonNode conditional : conditionals) {
            String what = key + "'s conditional grant " + (stored.size() + 1);
            if (!conditional.isObject() || !conditional.path(OTHERWISE).isObject()) {
                throw new IllegalStateException(SUBCLASS_PROFICIENCIES_FILE + ": " + what
                        + " has no choice otherwise");
            }
            ObjectNode written = objectMapper.createObjectNode();
            for (Map.Entry<String, JsonNode> field : conditional.properties()) {
                if (field.getKey().equals(OTHERWISE) || field.getKey().equals("level")
                        || field.getKey().equals(EXPERTISE)) {
                    continue;
                }
                requireKind(what, field.getKey());
                requireNames(what + "'s " + field.getKey(), field.getValue());
                requireSaves(what + "'s " + field.getKey(), field.getKey(), field.getValue());
                if (field.getValue().size() != 1 || field.getValue().get(0).asString("")
                        .startsWith(ProficiencyLists.LIST_PREFIX)) {
                    throw new IllegalStateException(SUBCLASS_PROFICIENCIES_FILE + ": " + what
                            + " grants one proficiency by name");
                }
                written.set(field.getKey(), field.getValue());
            }
            if (written.size() != 1) {
                throw new IllegalStateException(SUBCLASS_PROFICIENCIES_FILE + ": " + what
                        + " grants one proficiency by name");
            }
            JsonNode expertise = conditional.path(EXPERTISE);
            if (!expertise.isMissingNode()) {
                if (!expertise.isBoolean() || !written.has("skills")) {
                    throw new IllegalStateException(SUBCLASS_PROFICIENCIES_FILE + ": " + what
                            + "'s expertise is true or false, and only for a skill");
                }
                if (expertise.asBoolean()) written.put(EXPERTISE, true);
            }
            JsonNode level = conditional.path("level");
            if (!level.isMissingNode()) {
                if (!level.isInt() || level.asInt() < 1 || level.asInt() > 20) {
                    throw new IllegalStateException(SUBCLASS_PROFICIENCIES_FILE + ": " + what
                            + "'s level is a class level from 1 to 20");
                }
                written.put("level", level.asInt());
            }
            written.set(OTHERWISE, choice(what + "'s choice otherwise", conditional.get(OTHERWISE), false));
            stored.add(written);
        }
        return stored;
    }

    // A choice, checked, with its list names written out; one otherwise has a count and options alone
    private ObjectNode choice(String what, JsonNode choice, boolean full) {
        if (!choice.isObject() || !choice.path("count").isInt() || choice.path("count").asInt() < 1) {
            throw new IllegalStateException(SUBCLASS_PROFICIENCIES_FILE + ": " + what
                    + " has no count of 1 or more");
        }
        ObjectNode written = objectMapper.createObjectNode();
        written.put("count", choice.path("count").asInt());
        List<String> kinds = new ArrayList<>();
        for (Map.Entry<String, JsonNode> field : choice.properties()) {
            if (field.getKey().equals("count")) continue;
            if (full && CHOICE_FLAGS.contains(field.getKey())) continue;
            requireKind(what, field.getKey());
            requireNames(what + "'s " + field.getKey(), field.getValue());
            requireSaves(what + "'s " + field.getKey(), field.getKey(), field.getValue());
            List<String> names = new ArrayList<>();
            field.getValue().forEach(name -> names.add(name.asString("")));
            List<String> options;
            try {
                options = ProficiencyLists.expand(names);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(SUBCLASS_PROFICIENCIES_FILE + ": " + what + ": " + e.getMessage());
            }
            ArrayNode list = written.putArray(field.getKey());
            options.forEach(list::add);
            kinds.add(field.getKey());
        }
        if (kinds.isEmpty()) {
            throw new IllegalStateException(SUBCLASS_PROFICIENCIES_FILE + ": " + what + " offers no options");
        }
        JsonNode expertise = choice.path("expertise");
        if (!expertise.isMissingNode()) {
            if (!expertise.isBoolean() || !kinds.equals(List.of("skills"))) {
                throw new IllegalStateException(SUBCLASS_PROFICIENCIES_FILE + ": " + what
                        + "'s expertise is true or false, and only for a choice of skills");
            }
            if (expertise.asBoolean()) written.put("expertise", true);
        }
        JsonNode kensei = choice.path("kensei");
        if (!kensei.isMissingNode()) {
            if (!kensei.isBoolean() || !kinds.equals(List.of("weapons"))) {
                throw new IllegalStateException(SUBCLASS_PROFICIENCIES_FILE + ": " + what
                        + "'s kensei is true or false, and only for a choice of weapons");
            }
            if (kensei.asBoolean()) written.put("kensei", true);
        }
        JsonNode level = choice.path("level");
        if (!level.isMissingNode()) {
            if (!level.isInt() || level.asInt() < 1 || level.asInt() > 20) {
                throw new IllegalStateException(SUBCLASS_PROFICIENCIES_FILE + ": " + what
                        + "'s level is a class level from 1 to 20");
            }
            written.put("level", level.asInt());
        }
        return written;
    }

    private static void requireKind(String what, String kind) {
        if (!PROFICIENCY_KINDS.contains(kind)) {
            throw new IllegalStateException(SUBCLASS_PROFICIENCIES_FILE + ": " + what + " has " + kind
                    + ", which isn't one of " + PROFICIENCY_KINDS);
        }
    }

    // Saving throws are abbreviated as the class data has them, such as "WIS"
    private static void requireSaves(String what, String kind, JsonNode items) {
        if (!"saves".equals(kind)) return;
        for (JsonNode item : items) {
            if (!SAVES.contains(item.asString(""))) {
                throw new IllegalStateException(SUBCLASS_PROFICIENCIES_FILE + ": " + what + " names "
                        + item.asString("") + ", which isn't one of " + SAVES);
            }
        }
    }

    private static void requireNames(String what, JsonNode items) {
        boolean names = items.isArray() && !items.isEmpty();
        for (JsonNode item : items) {
            names &= item.isString() && !item.asString("").isBlank();
        }
        if (!names) {
            throw new IllegalStateException(SUBCLASS_PROFICIENCIES_FILE + ": " + what + " is not a list of names");
        }
    }

    /** A subclass's natural key: its class's name and source, then its own. */
    static String subclassKey(Subclass subclass) {
        CharacterClass parent = subclass.getCharacterClass();
        return LoadContext.nameAndSource(parent.getName(), parent.getSource()) + "|"
                + LoadContext.nameAndSource(subclass.getName(), subclass.getSource());
    }

    private CharacterClass parseClass(JsonNode node, Map<String, JsonNode> featureMap, NestedFeatures nested)
            throws Exception {
        String name = node.path("name").asString("");
        String source = node.path("source").asString("");
        int hitDice = node.path("hd").path("faces").asInt(8);

        String spellcastingAbility = node.has("spellcastingAbility")
                ? node.get("spellcastingAbility").asString("").toUpperCase() : null;
        String casterProgression = node.path("casterProgression").asString(null);

        boolean isSpellcaster = spellcastingAbility != null;
        boolean isPreparedCaster = PREPARED_CASTERS.contains(name);
        boolean isKnownCaster = KNOWN_CASTERS.contains(name);
        boolean isPactMagic = "Warlock".equals(name);

        JsonNode profNode = node.get("proficiency");
        String savingThrows = parseSavingThrows(profNode);

        JsonNode startProf = node.path("startingProficiencies");
        String armorProf = parseStringArray(startProf.get("armor"));
        String weaponProf = parseStringArray(startProf.get("weapons"));
        String toolProf = parseToolProficiencies(startProf.get("tools"));
        String skillChoices = parseSkillChoices(startProf.get("skills"));

        String primaryAbility = inferPrimaryAbility(name, node);
        String spellSlotProgression = buildSpellSlotProgression(name, casterProgression);
        String features = parseClassFeatures(node, featureMap, nested);
        String startingEquipment = node.has("startingEquipment")
                ? objectMapper.writeValueAsString(node.get("startingEquipment")) : null;

        String multiclassRequirements = parseMulticlassRequirements(node);
        String multiclassProficiencies = parseMulticlassProficiencies(node);

        return CharacterClass.builder()
                .name(name)
                .source(source)
                .hitDice(hitDice)
                .primaryAbility(primaryAbility)
                .savingThrowProficiencies(savingThrows)
                .armorProficiencies(armorProf)
                .weaponProficiencies(weaponProf)
                .toolProficiencies(toolProf)
                .skillChoices(skillChoices)
                .spellcastingAbility(spellcastingAbility)
                .isSpellcaster(isSpellcaster)
                .isPreparedCaster(isPreparedCaster)
                .isKnownCaster(isKnownCaster)
                .isPactMagic(isPactMagic)
                .spellSlotProgression(spellSlotProgression)
                .features(features)
                .startingEquipment(startingEquipment)
                .subclassLevel(classTables.subclassLevel(name))
                .multiclassRequirements(multiclassRequirements)
                .multiclassProficiencies(multiclassProficiencies)
                .build();
    }

    private Subclass parseSubclass(SubclassData scd, CharacterClass parent) throws Exception {
        JsonNode node = scd.node;
        String name = node.path("name").asString("");
        String source = node.path("source").asString("");

        String features = parseSubclassFeatures(node, scd.featureMap, scd.nested);
        String alwaysPreparedSpells = parseAlwaysPreparedSpells(node);
        String expandedSpellList = parseExpandedSpellList(node);
        String grantedSpells = parseGrantedSpells(node, parent.getName());

        return Subclass.builder()
                .name(name)
                .source(source)
                .characterClass(parent)
                .features(features)
                .alwaysPreparedSpells(alwaysPreparedSpells)
                .expandedSpellList(expandedSpellList)
                .grantedSpells(grantedSpells)
                .build();
    }

    private String parseSavingThrows(JsonNode profNode) throws Exception {
        if (profNode == null || !profNode.isArray()) return null;
        ArrayNode arr = objectMapper.createArrayNode();
        for (JsonNode p : profNode) {
            arr.add(p.asString("").toUpperCase());
        }
        return objectMapper.writeValueAsString(arr);
    }

    private String parseStringArray(JsonNode node) throws Exception {
        if (node == null || !node.isArray()) return null;
        ArrayNode arr = objectMapper.createArrayNode();
        for (JsonNode item : node) {
            if (item.isString()) {
                arr.add(FiveEToolsMarkupParser.parse(item.asString("")));
            } else if (item.isObject() && item.has("proficiency")) {
                for (JsonNode p : item.get("proficiency")) {
                    arr.add(FiveEToolsMarkupParser.parse(p.asString("")));
                }
            }
        }
        return arr.isEmpty() ? null : objectMapper.writeValueAsString(arr);
    }

    private String parseToolProficiencies(JsonNode node) throws Exception {
        if (node == null || !node.isArray()) return null;
        ArrayNode arr = objectMapper.createArrayNode();
        for (JsonNode item : node) {
            arr.add(FiveEToolsMarkupParser.parse(item.asString("")));
        }
        return arr.isEmpty() ? null : objectMapper.writeValueAsString(arr);
    }

    private String parseSkillChoices(JsonNode skillsNode) throws Exception {
        if (skillsNode == null || !skillsNode.isArray() || skillsNode.isEmpty()) return null;
        JsonNode first = skillsNode.get(0);
        ObjectNode result = objectMapper.createObjectNode();

        if (first.has("any")) {
            result.put("count", first.get("any").asInt(0));
            ArrayNode allSkills = objectMapper.createArrayNode();
            for (String skill : ALL_SKILLS) allSkills.add(skill);
            result.set("from", allSkills);
        } else if (first.has("choose")) {
            JsonNode choose = first.get("choose");
            result.put("count", choose.path("count").asInt(2));
            ArrayNode from = objectMapper.createArrayNode();
            JsonNode fromArr = choose.get("from");
            if (fromArr != null && fromArr.isArray()) {
                for (JsonNode s : fromArr) {
                    from.add(titleCase(s.asString("")));
                }
            }
            result.set("from", from);
        }

        return objectMapper.writeValueAsString(result);
    }

    private String parseMulticlassRequirements(JsonNode classNode) throws Exception {
        JsonNode mc = classNode.path("multiclassing").path("requirements");
        if (mc.isMissingNode() || mc.isEmpty()) return null;

        ArrayNode result = objectMapper.createArrayNode();

        if (mc.has("or") && mc.get("or").isArray()) {
            JsonNode orBlock = mc.get("or").get(0);
            Iterator<String> fields = orBlock.propertyNames().iterator();
            while (fields.hasNext()) {
                String ability = fields.next();
                ObjectNode req = objectMapper.createObjectNode();
                req.put("ability", ability.toUpperCase());
                req.put("minimum", orBlock.get(ability).asInt(0));
                req.put("operator", "OR");
                result.add(req);
            }
        } else {
            Iterator<String> fields = mc.propertyNames().iterator();
            while (fields.hasNext()) {
                String ability = fields.next();
                ObjectNode req = objectMapper.createObjectNode();
                req.put("ability", ability.toUpperCase());
                req.put("minimum", mc.get(ability).asInt(0));
                result.add(req);
            }
        }

        return result.isEmpty() ? null : objectMapper.writeValueAsString(result);
    }

    private String parseMulticlassProficiencies(JsonNode classNode) throws Exception {
        JsonNode pg = classNode.path("multiclassing").path("proficienciesGained");
        if (pg.isMissingNode() || pg.isEmpty()) return null;

        ObjectNode result = objectMapper.createObjectNode();

        if (pg.has("armor") && pg.get("armor").isArray()) {
            ArrayNode armor = objectMapper.createArrayNode();
            for (JsonNode item : pg.get("armor")) {
                if (item.isString()) {
                    armor.add(titleCase(item.asString("")));
                } else if (item.isObject() && item.has("proficiency")) {
                    armor.add(titleCase(item.get("proficiency").asString("")));
                }
            }
            if (!armor.isEmpty()) result.set("armor", armor);
        }

        if (pg.has("weapons") && pg.get("weapons").isArray()) {
            ArrayNode weapons = objectMapper.createArrayNode();
            for (JsonNode item : pg.get("weapons")) {
                weapons.add(FiveEToolsMarkupParser.parse(titleCase(item.asString(""))));
            }
            if (!weapons.isEmpty()) result.set("weapons", weapons);
        }

        if (pg.has("tools") && pg.get("tools").isArray()) {
            ArrayNode tools = objectMapper.createArrayNode();
            for (JsonNode item : pg.get("tools")) {
                tools.add(FiveEToolsMarkupParser.parse(item.asString("")));
            }
            if (!tools.isEmpty()) result.set("tools", tools);
        }

        if (pg.has("skills") && pg.get("skills").isArray()) {
            JsonNode first = pg.get("skills").get(0);
            if (first != null && first.has("choose")) {
                ObjectNode skillChoice = objectMapper.createObjectNode();
                skillChoice.put("count", first.get("choose").path("count").asInt(1));
                ArrayNode from = objectMapper.createArrayNode();
                for (JsonNode s : first.get("choose").path("from")) {
                    from.add(titleCase(s.asString("")));
                }
                skillChoice.set("from", from);
                result.set("skills", skillChoice);
            }
        }

        return result.isEmpty() ? null : objectMapper.writeValueAsString(result);
    }

    // The abilities the class's multiclassing prerequisite names, as the PHB's class table gives its primary ability:
    // "STR and CHA" for a Paladin, and "STR or DEX" for a Fighter, whose prerequisite is either
    private String inferPrimaryAbility(String className, JsonNode node) {
        JsonNode mc = node.path("multiclassing").path("requirements");
        if (mc.isMissingNode() || mc.isEmpty()) {
            return switch (className) {
                case "Barbarian" -> "STR";
                case "Fighter" -> "STR or DEX";
                case "Monk" -> "DEX and WIS";
                case "Rogue" -> "DEX";
                default -> null;
            };
        }
        JsonNode either = mc.path("or").path(0);
        if (either.isObject()) {
            return String.join(" or ", abilityNames(either));
        }
        return String.join(" and ", abilityNames(mc));
    }

    private static List<String> abilityNames(JsonNode requirements) {
        List<String> abilities = new ArrayList<>();
        requirements.propertyNames().iterator().forEachRemaining(f -> abilities.add(f.toUpperCase()));
        return abilities;
    }

    private String parseClassFeatures(JsonNode classNode, Map<String, JsonNode> featureMap, NestedFeatures nested)
            throws Exception {
        JsonNode refs = classNode.get("classFeatures");
        if (refs == null || !refs.isArray()) return null;

        String className = classNode.path("name").asString("");
        ArrayNode features = objectMapper.createArrayNode();

        for (JsonNode ref : refs) {
            String refStr;
            if (ref.isString()) {
                refStr = ref.asString("");
            } else if (ref.isObject() && ref.has("classFeature")) {
                refStr = ref.get("classFeature").asString("");
            } else {
                continue;
            }

            JsonNode featureNode = featureMap.get(refStr);
            if (featureNode == null) continue;

            int level = featureNode.path("level").asInt(0);
            String featureName = featureNode.path("name").asString("");
            String description = flattenEntries(featureNode.get("entries"), nested, 0);

            ObjectNode feat = objectMapper.createObjectNode();
            feat.put("level", level);
            feat.put("name", featureName);
            feat.put("description", description != null ? description : "");
            features.add(feat);
        }

        return features.isEmpty() ? null : objectMapper.writeValueAsString(features);
    }

    private String parseSubclassFeatures(JsonNode scNode, Map<String, JsonNode> featureMap, NestedFeatures nested)
            throws Exception {
        JsonNode refs = scNode.get("subclassFeatures");
        if (refs == null || !refs.isArray()) return null;

        ArrayNode features = objectMapper.createArrayNode();

        for (JsonNode ref : refs) {
            String refStr = ref.asString("");
            JsonNode featureNode = featureMap.get(refStr);
            if (featureNode == null) continue;

            int level = featureNode.path("level").asInt(0);
            String featureName = featureNode.path("name").asString("");
            String description = flattenEntries(featureNode.get("entries"), nested, 0);

            ObjectNode feat = objectMapper.createObjectNode();
            feat.put("level", level);
            feat.put("name", featureName);
            feat.put("description", description != null ? description : "");
            features.add(feat);
        }

        return features.isEmpty() ? null : objectMapper.writeValueAsString(features);
    }

    // The always-prepared spells of the subclass's lists that every member gets. A subclass whose player picks one of
    // several named lists, as a Circle of the Land druid picks a terrain (PHB p.68-69), has its lists' spells under
    // the granted spells' options instead, so a Land druid gets only its own terrain's (D231); before, every terrain's
    // spells were written over one another by level, and the last, the Underdark's, won
    String parseAlwaysPreparedSpells(JsonNode scNode) throws Exception {
        JsonNode addSpells = scNode.get("additionalSpells");
        if (addSpells == null || !addSpells.isArray() || addSpells.isEmpty()) return null;

        ObjectNode result = objectMapper.createObjectNode();
        for (JsonNode spellBlock : addSpells) {
            if (isOption(addSpells, spellBlock)) continue;
            mergeByLevel(result, spellBlock.get("prepared"), true);
        }

        return result.isEmpty() ? null : objectMapper.writeValueAsString(result);
    }

    /**
     * The spells the subclass grants outside its always-prepared list (V41, D231), as {@code Subclass.grantedSpells}:
     * {@code known}, the spells it teaches by class level, such as a Gloom Stalker's disguise self at Ranger 3 (XGE
     * p.42), Aberrant Mind's psionic spells (TCE p.66) or Shadow Magic's darkness (XGE p.51), known with the source
     * "subclass:" and its name and not counted against Spells Known; and {@code options}, for a subclass whose player
     * picks one of two or more named lists, those lists in the data's order, each with its {@code name} and its
     * always-prepared ({@code prepared}) and known ({@code known}) spells by class level, as a Circle of the Land
     * druid's terrains (PHB p.68-69) or a Divine Soul's affinities (XGE p.50), and the levelled spells it adds to the
     * class's list ({@code expanded}, names in the data's order), as each of the Genie's four kinds (TCE p.73: the genie
     * spells every kind has, then the kind's own); and {@code daily}, the spells it teaches by class level that are also
     * cast once without a slot between long rests, as the Fathomless's Evard's black tentacles at Warlock 10 (Grasping
     * Tentacles, TCE p.72: 5etools' {@code "known": {"10": {"daily": {"1": [...]}}}}). Cantrips ({@code #c}) are left
     * out: data/content/subclass-proficiencies.json grants them, so levelling down forgets them with the subclass's other
     * cantrips. Choices (objects, such as a College of Lore's Additional Magical Secrets or a Divine Soul's cleric list)
     * are left out too. A prepared caster's subclass learns no spells this way: the only one the data gives, a Circle of
     * the Moon druid's alter self at Druid 14, is cast at will (Thousand Forms, PHB p.69), which isn't modelled yet. Null
     * for a subclass with none.
     */
    String parseGrantedSpells(JsonNode scNode, String className) throws Exception {
        JsonNode addSpells = scNode.get("additionalSpells");
        if (addSpells == null || !addSpells.isArray() || addSpells.isEmpty()) return null;
        boolean learnsKnown = !PREPARED_CASTERS.contains(className);

        ObjectNode known = objectMapper.createObjectNode();
        ObjectNode daily = objectMapper.createObjectNode();
        // A list, as PostgreSQL's jsonb doesn't keep an object's keys in order, and the options are offered in the
        // book's order
        ArrayNode options = objectMapper.createArrayNode();
        for (JsonNode spellBlock : addSpells) {
            if (isOption(addSpells, spellBlock)) {
                ObjectNode option = objectMapper.createObjectNode();
                ObjectNode prepared = objectMapper.createObjectNode();
                mergeByLevel(prepared, spellBlock.get("prepared"), false);
                ObjectNode optionKnown = objectMapper.createObjectNode();
                if (learnsKnown) mergeByLevel(optionKnown, spellBlock.get("known"), false);
                ArrayNode expanded = expandedNames(spellBlock.get("expanded"));
                if (prepared.isEmpty() && optionKnown.isEmpty() && expanded.isEmpty()) continue;
                option.put("name", spellBlock.path("name").asString("").trim());
                if (!prepared.isEmpty()) option.set("prepared", prepared);
                if (!optionKnown.isEmpty()) option.set("known", optionKnown);
                if (!expanded.isEmpty()) option.set("expanded", expanded);
                options.add(option);
            } else if (learnsKnown) {
                mergeByLevel(known, spellBlock.get("known"), false);
                mergeDailyByLevel(daily, spellBlock.get("known"));
            }
        }
        ObjectNode result = objectMapper.createObjectNode();
        if (!known.isEmpty()) result.set("known", known);
        if (!daily.isEmpty()) result.set("daily", daily);
        if (!options.isEmpty()) result.set("options", options);
        return result.isEmpty() ? null : objectMapper.writeValueAsString(result);
    }

    // The levelled spells a block adds to its class's list, each once in the data's order, from 5etools' "expanded"
    // keyed by spell level ("s1") or class level; choices (objects, such as a Divine Soul's {"all": ...}) are left out
    private ArrayNode expandedNames(JsonNode expanded) {
        ArrayNode names = objectMapper.createArrayNode();
        if (expanded == null || !expanded.isObject()) return names;
        expanded.properties().forEach(level -> {
            if (!level.getValue().isArray()) return;
            for (JsonNode s : level.getValue()) {
                if (!s.isString() || isCantrip(s.asString(""))) continue;
                String name = spellName(s.asString(""));
                boolean present = name.isBlank();
                for (JsonNode existing : names) present |= existing.asString("").equalsIgnoreCase(name);
                if (!present) names.add(name);
            }
        });
        return names;
    }

    // Adds a block's known spells that are cast once a long rest without a slot, 5etools' {"daily": {"1": [...]}} under
    // a class level, to that level's list. Only one use ("1") is read: the data gives no other for a known spell
    private void mergeDailyByLevel(ObjectNode target, JsonNode byLevel) {
        if (byLevel == null || !byLevel.isObject()) return;
        byLevel.properties().forEach(level -> {
            JsonNode once = level.getValue().path("daily").path("1");
            if (!once.isArray()) return;
            ObjectNode wrapper = objectMapper.createObjectNode();
            wrapper.set(level.getKey(), once);
            mergeByLevel(target, wrapper, false);
        });
    }

    // Whether the block is one of two or more named lists the player picks from: a Circle of the Land's terrains or a
    // Divine Soul's affinities. A single named list, as Lunar Sorcery's, is every member's
    private static boolean isOption(JsonNode addSpells, JsonNode spellBlock) {
        if (!spellBlock.path("name").isString() || spellBlock.path("name").asString("").isBlank()) return false;
        int named = 0;
        for (JsonNode block : addSpells) {
            if (block.path("name").isString() && !block.path("name").asString("").isBlank()) named++;
        }
        return named >= 2;
    }

    // Adds a block's spells by name to the class levels' lists, each once; cantrips too unless they are left out.
    // Choices (objects) are skipped
    private void mergeByLevel(ObjectNode target, JsonNode byLevel, boolean cantrips) {
        if (byLevel == null || !byLevel.isObject()) return;
        byLevel.properties().forEach(level -> {
            if (!level.getValue().isArray()) return;
            for (JsonNode s : level.getValue()) {
                if (!s.isString() || (!cantrips && isCantrip(s.asString("")))) continue;
                String name = spellName(s.asString(""));
                if (name.isBlank()) continue;
                ArrayNode list = target.has(level.getKey()) ? (ArrayNode) target.get(level.getKey())
                        : target.putArray(level.getKey());
                boolean present = false;
                for (JsonNode existing : list) present |= existing.asString("").equalsIgnoreCase(name);
                if (!present) list.add(name);
            }
        });
    }

    private static boolean isCantrip(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT).endsWith("#c");
    }

    /**
     * A spell's name as the spells table has it, from a 5etools reference: without its {@code #c} cantrip mark or its
     * {@code |source} suffix, so "summon elemental|tce" (the Fathomless's, TCE p.72) and "fire shield|" (PSA) match
     * their spells, as "mind sliver|tce#c" does (D231).
     */
    static String spellName(String raw) {
        String name = raw.trim();
        int mark = name.indexOf('#');
        if (mark >= 0) name = name.substring(0, mark);
        int bar = name.indexOf('|');
        if (bar >= 0) name = name.substring(0, bar);
        return FiveEToolsMarkupParser.parse(name.trim()).trim();
    }

    private String parseExpandedSpellList(JsonNode scNode) throws Exception {
        JsonNode addSpells = scNode.get("additionalSpells");
        if (addSpells == null || !addSpells.isArray() || addSpells.isEmpty()) return null;

        ObjectNode result = objectMapper.createObjectNode();
        for (JsonNode spellBlock : addSpells) {
            JsonNode expanded = spellBlock.get("expanded");
            if (expanded == null) continue;

            expanded.propertyNames().iterator().forEachRemaining(level -> {
                JsonNode spells = expanded.get(level);
                if (spells != null && spells.isArray()) {
                    ArrayNode arr = objectMapper.createArrayNode();
                    for (JsonNode s : spells) {
                        if (s.isString()) {
                            arr.add(spellName(s.asString("")));
                        }
                    }
                    if (!arr.isEmpty()) result.set(level, arr);
                }
            });
        }

        return result.isEmpty() ? null : objectMapper.writeValueAsString(result);
    }

    private Map<String, JsonNode> buildFeatureMap(JsonNode features) {
        Map<String, JsonNode> map = new HashMap<>();
        if (features == null || !features.isArray()) return map;
        for (JsonNode f : features) {
            String name = f.path("name").asString("");
            String className = f.path("className").asString("");
            String classSource = f.path("classSource").asString("");
            String source = f.path("source").asString("");
            int level = f.path("level").asInt(0);
            String key = name + "|" + className + "|" + classSource + "|" + level;
            if (!source.equals(classSource)) {
                key = name + "|" + className + "|" + classSource + "|" + level + "|" + source;
            }
            map.put(key, f);
            map.put(name + "|" + className + "||" + level, f);
            if (!source.equals(classSource)) {
                map.put(name + "|" + className + "||" + level + "|" + source, f);
            }
        }
        return map;
    }

    private Map<String, JsonNode> buildSubclassFeatureMap(JsonNode features) {
        Map<String, JsonNode> map = new HashMap<>();
        if (features == null || !features.isArray()) return map;
        for (JsonNode f : features) {
            String name = f.path("name").asString("");
            String className = f.path("className").asString("");
            String classSource = f.path("classSource").asString("");
            String scShort = f.path("subclassShortName").asString("");
            String scSource = f.path("subclassSource").asString("");
            String source = f.path("source").asString("");
            int level = f.path("level").asInt(0);
            String key = name + "|" + className + "||" + scShort + "||" + level;
            map.put(key, f);
            if (!source.equals(scSource)) {
                map.put(key + "|" + source, f);
            }
            String key2 = name + "|" + className + "|" + classSource + "|" + scShort + "|" + scSource + "|" + level;
            map.put(key2, f);
        }
        return map;
    }

    /**
     * A feature's entries as plain text, a paragraph to a line. A feature nested inside it by reference, as the Life
     * Domain holds Bonus Proficiency and Disciple of Life (PHB p.60), adds its own paragraphs, the first led by its name,
     * as the book prints it: "Disciple of Life. Also starting at 1st level, ...". Optional features a reference lists,
     * such as maneuvers or fighting styles, are left out: they are choices, each with its own text.
     */
    String flattenEntries(JsonNode entries, NestedFeatures nested, int depth) {
        if (entries == null) return null;
        StringBuilder sb = new StringBuilder();
        for (JsonNode entry : entries) {
            if (entry.isString()) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append(FiveEToolsMarkupParser.parse(entry.asString("")));
            } else if (entry.isObject()) {
                String type = entry.path("type").asString("");
                if ("entries".equals(type) && entry.has("entries")) {
                    if (!sb.isEmpty()) sb.append("\n");
                    String inner = flattenEntries(entry.get("entries"), nested, depth);
                    if (inner != null) sb.append(inner);
                } else if ((REF_CLASS_FEATURE.equals(type) || REF_SUBCLASS_FEATURE.equals(type))
                        && depth < MAX_NESTING) {
                    JsonNode feature = nested.resolve(entry);
                    if (feature == null) continue;
                    String text = flattenEntries(feature.get("entries"), nested, depth + 1);
                    if (!sb.isEmpty()) sb.append("\n");
                    sb.append(FiveEToolsMarkupParser.parse(feature.path("name").asString("")));
                    if (text != null) sb.append(". ").append(text);
                } else if ("list".equals(type) && entry.has("items")) {
                    for (JsonNode item : entry.get("items")) {
                        if (!sb.isEmpty()) sb.append("\n");
                        if (item.isString()) {
                            sb.append("- ").append(FiveEToolsMarkupParser.parse(item.asString("")));
                        } else if (item.isObject() && item.has("entry")) {
                            sb.append("- ").append(FiveEToolsMarkupParser.parse(item.path("entry").asString("")));
                        } else if (item.isObject() && item.has("entries")) {
                            String inner = flattenEntries(item.get("entries"), nested, depth);
                            if (inner != null) sb.append("- ").append(inner);
                        }
                    }
                } else if ("table".equals(type) && entry.has("rows")) {
                    for (JsonNode row : entry.get("rows")) {
                        if (row.isArray()) {
                            if (!sb.isEmpty()) sb.append("\n");
                            List<String> cells = new ArrayList<>();
                            for (JsonNode cell : row) {
                                cells.add(FiveEToolsMarkupParser.parse(cell.asString("")));
                            }
                            sb.append("| ").append(String.join(" | ", cells)).append(" |");
                        }
                    }
                } else if ("options".equals(type) && entry.has("entries")) {
                    String inner = flattenEntries(entry.get("entries"), nested, depth);
                    if (inner != null) {
                        if (!sb.isEmpty()) sb.append("\n");
                        sb.append(inner);
                    }
                }
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private String buildSpellSlotProgression(String className, String casterProgression) throws Exception {
        if (casterProgression == null) return null;
        // The class tables' slots, which SpellSlotCalculator reads too (D153); 5etools names the half-caster
        // progression "1/2"
        Map<String, Map<String, Integer>> table = switch (casterProgression) {
            case "full", "pact", "artificer" -> classTables.slotProgression(casterProgression);
            case "1/2" -> classTables.slotProgression("half");
            default -> null;
        };
        if (table == null) return null;
        return objectMapper.writeValueAsString(table);
    }

    private static String titleCase(String s) {
        if (s == null || s.isEmpty()) return s;
        return Arrays.stream(s.split("\\s+"))
                .map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase())
                .reduce((a, b) -> a + " " + b)
                .orElse(s);
    }

    private static final List<String> ALL_SKILLS = List.of(
            "Acrobatics", "Animal Handling", "Arcana", "Athletics", "Deception",
            "History", "Insight", "Intimidation", "Investigation", "Medicine",
            "Nature", "Perception", "Performance", "Persuasion", "Religion",
            "Sleight of Hand", "Stealth", "Survival"
    );

    private record SubclassData(String filename, JsonNode node, Map<String, JsonNode> featureMap,
                                NestedFeatures nested) {}

    /**
     * A class file's class and subclass features by the references that nest one inside another: "name|class|class
     * source|level|source" for a class feature, and "name|class|class source|subclass short name|subclass source|level|
     * source" for a subclass feature, a blank source being the Player's Handbook, or the class's or subclass's source.
     */
    record NestedFeatures(Map<String, JsonNode> classFeatures, Map<String, JsonNode> subclassFeatures) {

        static NestedFeatures of(JsonNode classFeatureArray, JsonNode subclassFeatureArray) {
            Map<String, JsonNode> classFeatures = new HashMap<>();
            if (classFeatureArray != null && classFeatureArray.isArray()) {
                for (JsonNode f : classFeatureArray) {
                    classFeatures.put(classKey(f.path("name").asString(""), f.path("className").asString(""),
                            f.path("classSource").asString(""), f.path("level").asString(""),
                            f.path("source").asString("")), f);
                }
            }
            Map<String, JsonNode> subclassFeatures = new HashMap<>();
            if (subclassFeatureArray != null && subclassFeatureArray.isArray()) {
                for (JsonNode f : subclassFeatureArray) {
                    subclassFeatures.put(subclassKey(f.path("name").asString(""), f.path("className").asString(""),
                            f.path("classSource").asString(""), f.path("subclassShortName").asString(""),
                            f.path("subclassSource").asString(""), f.path("level").asString(""),
                            f.path("source").asString("")), f);
                }
            }
            return new NestedFeatures(classFeatures, subclassFeatures);
        }

        /** The feature a reference entry names, or null when the file has none by that reference. */
        JsonNode resolve(JsonNode entry) {
            String type = entry.path("type").asString("");
            if (REF_CLASS_FEATURE.equals(type)) {
                String[] p = parts(entry.path("classFeature").asString(""), 5);
                return classFeatures.get(classKey(p[0], p[1], p[2], p[3], p[4]));
            }
            if (REF_SUBCLASS_FEATURE.equals(type)) {
                String[] p = parts(entry.path("subclassFeature").asString(""), 7);
                return subclassFeatures.get(subclassKey(p[0], p[1], p[2], p[3], p[4], p[5], p[6]));
            }
            return null;
        }

        private static String classKey(String name, String className, String classSource, String level,
                                       String source) {
            String cs = orPhb(classSource);
            return String.join("|", name, className, cs, level, source.isBlank() ? cs : source);
        }

        private static String subclassKey(String name, String className, String classSource, String shortName,
                                          String subclassSource, String level, String source) {
            String ss = orPhb(subclassSource);
            return String.join("|", name, className, orPhb(classSource), shortName, ss, level,
                    source.isBlank() ? ss : source);
        }

        private static String orPhb(String source) {
            return source == null || source.isBlank() ? DEFAULT_SOURCE : source;
        }

        private static String[] parts(String reference, int size) {
            String[] split = reference.split("\\|", -1);
            String[] parts = new String[size];
            for (int i = 0; i < size; i++) {
                parts[i] = i < split.length ? split[i].trim() : "";
            }
            return parts;
        }
    }
}
