package com.tabletophelper.character.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.character.DerivedStats;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.dto.AbilityScoreMaximums;
import com.tabletophelper.character.dto.ClassSpellcasting;
import com.tabletophelper.character.state.AbilityScoreImprovement;
import com.tabletophelper.character.state.AppliedFeatEffects;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.HitDicePool;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.CharacterClassRepository;
import com.tabletophelper.reference.Race;
import com.tabletophelper.resourcepool.ResourcePoolService;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.ProficiencyBonus;
import com.tabletophelper.rules.SpellSlotCalculator;
import com.tabletophelper.rules.character.AbilityScores;
import com.tabletophelper.rules.character.ClassProgression;
import com.tabletophelper.rules.character.HitPoints;
import com.tabletophelper.shared.ConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Function;

/**
 * The one derivation of a character's Derived Stats (D158, D167), from its sheet, its recorded level history and the
 * rules in rules.character: the proficiency bonus, hit point maximum, initiative and speed, the ability scores and
 * their maximums, the spell slot and Hit Dice maximums, what each class casts with, and the main spell save DC and
 * attack bonus that race and feat spells use (D164). Applying it writes the stored copies and syncs the resource
 * pools, the innate uses of known spells among them (D165).
 * <p>
 * Tough, the Hill Dwarf and the Draconic Bloodline are matched by name until R7's data files hold their effects, and
 * initiative and speed add only the feat bonuses the level history records.
 */
@Component
@RequiredArgsConstructor
public class CharacterStats {

    // Matched by name until R7's data files hold them (D167)
    static final String TOUGH = "Tough";
    static final String HILL_DWARF = "Hill Dwarf";
    static final String DRACONIC_BLOODLINE = "Draconic Bloodline";
    static final String BARBARIAN = "Barbarian";

    private static final int PRIMAL_CHAMPION_LEVEL = 20;
    private static final int DEFAULT_SPEED = 30;
    // A character created without a class has always had d8 levels
    private static final int CLASSLESS_HIT_DIE = 8;
    // The Eldritch Knight and Arcane Trickster cast with Intelligence (PHB p.75, p.98)
    private static final String THIRD_CASTER_ABILITY = "INT";

    private final CharacterClassRepository classRepository;
    private final ResourcePoolService resourcePoolService;
    private final ObjectMapper objectMapper;

    /** One of the character's classes: its class entry and the class it names. */
    private record ClassLevel(ClassEntry entry, CharacterClass characterClass) {

        String name() {
            return characterClass.getName();
        }
    }

    // ── Deriving ─────────────────────────────────────────────────

    /**
     * Everything the rules give the character now. Changes nothing, so a dry run can compare it with the sheet.
     *
     * @throws ConflictException when the sheet can't be worked out: a class it names that doesn't exist, no level
     *                           history, or reference data that can't be read
     */
    public DerivedStats derive(PlayerCharacter character) {
        List<ClassLevel> classes = classes(character);
        Map<Ability, Integer> scores = abilityScores(character);
        int proficiencyBonus = ProficiencyBonus.forLevel(level(character));
        List<ClassSpellcasting> spellcasting = spellcasting(classes, scores, proficiencyBonus);
        String mainAbility = mainAbility(character, spellcasting);
        Integer mainModifier = mainAbility != null
                ? Ability.modifier(scores.get(Ability.parse(mainAbility).orElseThrow())) : null;

        Map<String, Integer> poolContext = resourcePoolService.buildCharacterContext(character);
        poolContext.put("proficiencyBonus", proficiencyBonus);

        return new DerivedStats(
                proficiencyBonus,
                hitPointMaximum(character, orEmpty(character.getLevelHistory()), classes),
                Ability.modifier(scores.get(Ability.DEX)) + recordedFeatBonus(character, AppliedFeatEffects::initiativeBonus),
                raceSpeed(character.getRaceRef()) + recordedFeatBonus(character, AppliedFeatEffects::speedBonus),
                scores,
                abilityScoreMaximums(classes),
                spellSlots(character, classes),
                hitDice(character, classes),
                spellcasting,
                mainAbility,
                mainModifier != null ? 8 + proficiencyBonus + mainModifier : null,
                mainModifier != null ? proficiencyBonus + mainModifier : null,
                resourcePoolService.reconciledPools(character, poolContext),
                warnings(character, spellcasting));
    }

    /**
     * Derives the stats and writes the stored copies: the proficiency bonus, hit point maximum, initiative, speed,
     * spell slots, Hit Dice, main spell DC and attack bonus, and the resource pools. A higher hit point maximum raises
     * current hit points by as much; a lower one caps them (D167).
     */
    public DerivedStats apply(PlayerCharacter character) {
        DerivedStats stats = derive(character);
        Integer oldMaximum = character.getHpMax();
        int current = character.getHpCurrent() != null ? character.getHpCurrent() : stats.hpMax();
        if (oldMaximum != null && stats.hpMax() > oldMaximum) {
            current += stats.hpMax() - oldMaximum;
        }

        character.setProficiencyBonus(stats.proficiencyBonus());
        character.setHpMax(stats.hpMax());
        character.setHpCurrent(Math.max(0, Math.min(current, stats.hpMax())));
        character.setInitiativeBonus(stats.initiativeBonus());
        character.setSpeed(stats.speed());
        character.setSpellSlots(stats.spellSlots());
        character.setHitDice(stats.hitDice());
        character.setSpellSaveDc(stats.spellSaveDc());
        character.setSpellAttackBonus(stats.spellAttackBonus());
        character.setResourcePools(stats.resourcePools());
        return stats;
    }

    /** What each of the character's classes casts with, in the order the classes were taken. */
    public List<ClassSpellcasting> spellcasting(PlayerCharacter character) {
        return spellcasting(classes(character), abilityScores(character),
                ProficiencyBonus.forLevel(level(character)));
    }

    /** The main spellcasting ability, which race and feat spells use; null for a character without one. */
    public String mainSpellcastingAbility(PlayerCharacter character) {
        return mainAbility(character, spellcasting(character));
    }

    /** The highest each ability score can be increased to. */
    public AbilityScoreMaximums abilityScoreMaximums(PlayerCharacter character) {
        return abilityScoreMaximums(classes(character));
    }

    /**
     * Whether the character has Primal Champion, the barbarian's 20th-level feature, which raises Strength's and
     * Constitution's maximum to 24 (PHB p.49).
     */
    public static boolean primalChampion(PlayerCharacter character) {
        return orEmpty(character.getMulticlassEntries()).stream()
                .anyMatch(entry -> BARBARIAN.equals(entry.className()) && entry.level() >= PRIMAL_CHAMPION_LEVEL);
    }

    /**
     * The calculator's entry for each class that has spell slots, read from the class entries: shared with the
     * recompute-spell-slots maintenance job (D153).
     */
    public Map<String, SpellSlotCalculator.ClassEntry> spellSlotEntries(PlayerCharacter character) {
        return spellSlotEntries(classes(character));
    }

    /**
     * The hit point maximum some of the character's levels give, such as the first few of a character being created:
     * each one's hit die result plus the current Constitution modifier, and the per-level bonuses of Tough, the Hill
     * Dwarf and the Draconic Bloodline (D167).
     *
     * @throws ConflictException for no levels
     */
    public int hitPointMaximum(PlayerCharacter character, List<LevelHistoryEntry> levels) {
        return hitPointMaximum(character, levels, classes(character));
    }

    // ── Classes and scores ───────────────────────────────────────

    private List<ClassLevel> classes(PlayerCharacter character) {
        List<ClassLevel> classes = new ArrayList<>();
        for (ClassEntry entry : orEmpty(character.getMulticlassEntries())) {
            CharacterClass characterClass = classRepository.findById(entry.classId())
                    .orElseThrow(() -> new ConflictException("The class " + entry.className()
                            + " that the character has can't be found"));
            classes.add(new ClassLevel(entry, characterClass));
        }
        return classes;
    }

    private static Map<Ability, Integer> abilityScores(PlayerCharacter character) {
        Map<Ability, Integer> scores = new EnumMap<>(Ability.class);
        scores.put(Ability.STR, orTen(character.getStrength()));
        scores.put(Ability.DEX, orTen(character.getDexterity()));
        scores.put(Ability.CON, orTen(character.getConstitution()));
        scores.put(Ability.INT, orTen(character.getIntelligence()));
        scores.put(Ability.WIS, orTen(character.getWisdom()));
        scores.put(Ability.CHA, orTen(character.getCharisma()));
        return scores;
    }

    private static AbilityScoreMaximums abilityScoreMaximums(List<ClassLevel> classes) {
        boolean primalChampion = classes.stream()
                .anyMatch(c -> BARBARIAN.equals(c.name()) && c.entry().level() >= PRIMAL_CHAMPION_LEVEL);
        return new AbilityScoreMaximums(
                AbilityScores.maximum(Ability.STR, primalChampion),
                AbilityScores.maximum(Ability.DEX, primalChampion),
                AbilityScores.maximum(Ability.CON, primalChampion),
                AbilityScores.maximum(Ability.INT, primalChampion),
                AbilityScores.maximum(Ability.WIS, primalChampion),
                AbilityScores.maximum(Ability.CHA, primalChampion));
    }

    // ── Hit points ───────────────────────────────────────────────

    private int hitPointMaximum(PlayerCharacter character, List<LevelHistoryEntry> history, List<ClassLevel> classes) {
        if (history.isEmpty()) {
            throw new ConflictException(character.getName() + " has no level history to work its hit points out from");
        }
        List<HitPoints.Level> levels = new ArrayList<>();
        for (LevelHistoryEntry entry : history) {
            int result = entry.hitDieResult() != null
                    ? entry.hitDieResult()
                    : HitPoints.fixedHitDieResult(entry.characterLevel(), hitDie(entry, classes));
            levels.add(new HitPoints.Level(entry.className(), result));
        }
        List<HitPoints.PerLevelBonus> bonuses = new ArrayList<>();
        if (orEmpty(character.getFeatures()).stream().anyMatch(f -> TOUGH.equalsIgnoreCase(trimmed(f.name())))) {
            bonuses.add(HitPoints.TOUGH);
        }
        Race race = character.getRaceRef();
        if (race != null && HILL_DWARF.equalsIgnoreCase(trimmed(race.getName()))) {
            bonuses.add(HitPoints.DWARVEN_TOUGHNESS);
        }
        if (classes.stream().anyMatch(c -> DRACONIC_BLOODLINE.equals(c.entry().subclassName()))) {
            bonuses.add(HitPoints.DRACONIC_RESILIENCE);
        }
        return HitPoints.maximum(levels, Ability.modifier(orTen(character.getConstitution())), bonuses, 0);
    }

    // The Hit Die of the class a level was taken in
    private int hitDie(LevelHistoryEntry entry, List<ClassLevel> classes) {
        if (entry.classId() == null) {
            return CLASSLESS_HIT_DIE;
        }
        return classes.stream()
                .filter(c -> entry.classId().equals(c.entry().classId()))
                .map(ClassLevel::characterClass)
                .findFirst()
                .or(() -> classRepository.findById(entry.classId()))
                .map(CharacterClass::getHitDice)
                .orElseThrow(() -> new ConflictException("The class " + entry.className() + " of level "
                        + entry.characterLevel() + " can't be found"));
    }

    // ── Initiative and speed ─────────────────────────────────────

    // The bonus the feats in the level history recorded, such as Alert's +5 initiative or Mobile's +10 speed
    private static int recordedFeatBonus(PlayerCharacter character, Function<AppliedFeatEffects, Integer> bonus) {
        int total = 0;
        for (LevelHistoryEntry entry : orEmpty(character.getLevelHistory())) {
            AbilityScoreImprovement asi = entry.choices() != null ? entry.choices().asi() : null;
            if (asi != null && asi.appliedEffects() != null) {
                Integer value = bonus.apply(asi.appliedEffects());
                total += value != null ? value : 0;
            }
        }
        return total;
    }

    // The race's walking speed, such as {"walk": 30} or 30; 30 for a race that records none
    private int raceSpeed(Race race) {
        if (race == null || race.getSpeed() == null || race.getSpeed().isBlank()) {
            return DEFAULT_SPEED;
        }
        try {
            JsonNode speed = objectMapper.readTree(race.getSpeed());
            if (speed.isNumber()) return speed.asInt();
            if (speed.path("walk").isNumber()) return speed.path("walk").asInt();
        } catch (JsonProcessingException e) {
            // Falls through to the 409 below
        }
        throw new ConflictException("The speed of the race " + race.getName() + " can't be read: " + race.getSpeed());
    }

    // ── Spell slots and Hit Dice ─────────────────────────────────

    private static Map<String, SpellSlotCalculator.ClassEntry> spellSlotEntries(List<ClassLevel> classes) {
        Map<String, SpellSlotCalculator.ClassEntry> entries = new LinkedHashMap<>();
        for (ClassLevel c : classes) {
            CharacterClass cc = c.characterClass();
            if (castingAbility(c) == null) continue;
            String casterType = SpellSlotCalculator.casterType(cc.getName(), Boolean.TRUE.equals(cc.getIsSpellcaster()),
                    Boolean.TRUE.equals(cc.getIsPactMagic()), c.entry().subclassName());
            if (!"none".equals(casterType)) {
                entries.put(cc.getName(), new SpellSlotCalculator.ClassEntry(c.entry().level(), casterType));
            }
        }
        return entries;
    }

    /**
     * Each slot's maximum, keeping the used count up to it, so levelling restores no slot (D168). Pact slots that
     * change level on a warlock's level keep their used count too. Null for no slots.
     */
    private static Map<String, SpellSlot> spellSlots(PlayerCharacter character, List<ClassLevel> classes) {
        Map<String, SpellSlotCalculator.ClassEntry> entries = spellSlotEntries(classes);
        Map<String, Integer> maximums = entries.isEmpty() ? Map.of() : SpellSlotCalculator.calculateSlots(entries);
        if (maximums.isEmpty()) {
            return null;
        }
        Map<String, SpellSlot> stored = character.getSpellSlots() != null ? character.getSpellSlots() : Map.of();
        int pactUsed = stored.entrySet().stream()
                .filter(slot -> slot.getKey().startsWith("pact_") && slot.getValue() != null)
                .mapToInt(slot -> slot.getValue().used())
                .max().orElse(0);
        Map<String, SpellSlot> slots = new LinkedHashMap<>();
        maximums.forEach((key, max) -> {
            SpellSlot old = stored.get(key);
            int used = old != null ? old.used() : key.startsWith("pact_") ? pactUsed : 0;
            slots.put(key, new SpellSlot(max, Math.max(0, Math.min(used, max))));
        });
        return slots;
    }

    /**
     * One Hit Die per level in each class, of the class's size. The dice already spent stay spent, up to the new
     * total, so levelling up adds a die to the total and the remaining alike, and levelling down caps what was spent
     * (D167). A character without classes keeps its Hit Dice as they are.
     */
    private static Map<String, HitDicePool> hitDice(PlayerCharacter character, List<ClassLevel> classes) {
        Map<String, HitDicePool> stored = character.getHitDice() != null ? character.getHitDice() : Map.of();
        if (classes.isEmpty()) {
            return new LinkedHashMap<>(stored);
        }
        Map<String, HitDicePool> hitDice = new LinkedHashMap<>();
        for (ClassLevel c : classes) {
            HitDicePool old = stored.get(c.name());
            int total = c.entry().level();
            int spent = old != null ? Math.max(0, old.total() - old.remaining()) : 0;
            hitDice.put(c.name(), new HitDicePool(c.characterClass().getHitDice(), total, Math.max(0, total - spent)));
        }
        return hitDice;
    }

    // ── Spellcasting ─────────────────────────────────────────────

    private static List<ClassSpellcasting> spellcasting(List<ClassLevel> classes, Map<Ability, Integer> scores,
                                                        int proficiencyBonus) {
        List<ClassSpellcasting> spellcasting = new ArrayList<>();
        for (ClassLevel c : classes) {
            String abilityName = castingAbility(c);
            if (abilityName == null) continue;
            Ability ability = Ability.parse(abilityName).orElseThrow(() -> new ConflictException(
                    "The spellcasting ability of the class " + c.name() + " can't be read: " + abilityName));
            int modifier = Ability.modifier(scores.get(ability));
            String subclass = c.entry().subclassName();
            int classLevel = c.entry().level();
            spellcasting.add(new ClassSpellcasting(
                    c.name(),
                    subclass,
                    classLevel,
                    ability.name(),
                    8 + proficiencyBonus + modifier,
                    proficiencyBonus + modifier,
                    ClassProgression.highestSpellLevel(c.name(), subclass, classLevel),
                    ClassProgression.cantripsKnown(c.name(), subclass, classLevel),
                    boxed(ClassProgression.spellsKnown(c.name(), subclass, classLevel)),
                    boxed(ClassProgression.preparedSpells(c.name(), classLevel, modifier)),
                    Boolean.TRUE.equals(c.characterClass().getIsPactMagic())));
        }
        return spellcasting;
    }

    // The class's spellcasting ability: its own, or Intelligence for an Eldritch Knight or Arcane Trickster from 3rd
    // level; null for a class that doesn't cast
    private static String castingAbility(ClassLevel c) {
        if (Boolean.TRUE.equals(c.characterClass().getIsSpellcaster())) {
            return c.characterClass().getSpellcastingAbility();
        }
        String subclass = c.entry().subclassName();
        boolean thirdCaster = subclass != null && SpellSlotCalculator.THIRD_CASTER_SUBCLASSES.contains(subclass)
                && c.entry().level() >= 3;
        return thirdCaster ? THIRD_CASTER_ABILITY : null;
    }

    // The ability chosen at creation for race and feat spells, or else the first casting class's
    private static String mainAbility(PlayerCharacter character, List<ClassSpellcasting> spellcasting) {
        String chosen = character.getSpellcastingAbility();
        if (chosen != null && !chosen.isBlank()) {
            return Ability.parse(chosen).map(Ability::name).orElseThrow(() -> new ConflictException(
                    character.getName() + "'s spellcasting ability can't be read: " + chosen));
        }
        return spellcasting.isEmpty() ? null : spellcasting.getFirst().ability();
    }

    // ── Warnings ─────────────────────────────────────────────────

    // The limits a class's spells already exceed; levelling and the dry run report them and change nothing
    private static List<String> warnings(PlayerCharacter character, List<ClassSpellcasting> spellcasting) {
        List<String> warnings = new ArrayList<>();
        String defaultOrigin = character.getCharacterClass() != null ? "class:" + character.getCharacterClass() : null;
        for (ClassSpellcasting cls : spellcasting) {
            String origin = "class:" + cls.className();
            List<KnownSpell> spells = orEmpty(character.getSpellsKnown()).stream()
                    .filter(s -> origin.equals(s.source() != null && !s.source().isBlank() ? s.source() : defaultOrigin))
                    .toList();
            String classAtLevel = "a level " + cls.classLevel() + " " + cls.className();
            long cantrips = spells.stream().filter(s -> s.level() == 0).count();
            if (cantrips > cls.cantripsKnown()) {
                warnings.add("knows " + cantrips + " " + cls.className() + " cantrips, but " + classAtLevel
                        + " knows " + cls.cantripsKnown());
            }
            long levelled = spells.stream().filter(s -> s.level() > 0).count();
            if (cls.spellsKnown() != null && levelled > cls.spellsKnown()) {
                warnings.add("knows " + levelled + " " + cls.className() + " spells, but " + classAtLevel
                        + " knows " + cls.spellsKnown());
            }
            long prepared = spells.stream()
                    .filter(s -> s.level() > 0 && Boolean.TRUE.equals(s.prepared())
                            && !Boolean.TRUE.equals(s.alwaysPrepared()))
                    .count();
            if (cls.preparedSpells() != null && prepared > cls.preparedSpells()) {
                warnings.add("has " + prepared + " " + cls.className() + " spells prepared, but " + classAtLevel
                        + " prepares " + cls.preparedSpells());
            }
        }
        return warnings;
    }

    // ── Helpers ──────────────────────────────────────────────────

    private static int level(PlayerCharacter character) {
        return character.getLevel() != null ? character.getLevel() : 1;
    }

    private static int orTen(Integer score) {
        return score != null ? score : 10;
    }

    private static Integer boxed(OptionalInt value) {
        return value.isPresent() ? value.getAsInt() : null;
    }

    private static String trimmed(String text) {
        return text != null ? text.trim() : null;
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list != null ? list : List.of();
    }
}
