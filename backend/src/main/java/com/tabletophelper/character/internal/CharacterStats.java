package com.tabletophelper.character.internal;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.tabletophelper.character.DerivedStats;
import com.tabletophelper.character.DmAdjustments;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.dto.AbilityScoreMaximums;
import com.tabletophelper.character.dto.ClassSpellcasting;
import com.tabletophelper.character.state.AppliedFeatEffects;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.DmAdjustment.Stat;
import com.tabletophelper.character.state.HitDicePool;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.CharacterClassRepository;
import com.tabletophelper.reference.Race;
import com.tabletophelper.reference.Subclass;
import com.tabletophelper.reference.SubclassRepository;
import com.tabletophelper.resourcepool.ResourcePoolService;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.ProficiencyBonus;
import com.tabletophelper.rules.SpellSlotCalculator;
import com.tabletophelper.rules.character.AbilityScores;
import com.tabletophelper.rules.character.ClassProgression;
import com.tabletophelper.rules.character.ClassTables;
import com.tabletophelper.rules.character.CurrentHitPoints;
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
import java.util.UUID;
import java.util.function.Function;

/**
 * The one derivation of a character's Derived Stats (D158, D167), from its sheet, its recorded level history, its DM
 * Adjustments and the rules in rules.character: the proficiency bonus, hit point maximum, initiative and speed, the
 * ability scores and their maximums, the spell slot and Hit Dice maximums, what each class casts with, and the main
 * spell save DC and attack bonus that race and feat spells use (D164). Applying it writes the stored copies and syncs
 * the resource pools, the innate uses of known spells (D165) and the limited uses of feats, such as Lucky's, among them
 * (D158).
 * <p>
 * The DM's ability score adjustments apply first, so the modifiers and everything worked out from them use the
 * adjusted scores; the other STAT adjustments are added to the hit point maximum, initiative, speed and every spell
 * save DC and attack bonus, each class's and the main ones. Feats count whether a level took one in place of an
 * Ability Score Improvement, the race or background gave it at creation (D159) or the DM granted it: Alert's
 * initiative, Mobile's speed and feats' pools come from each, and so do Tough's hit points. Hit points for every level
 * come from the effects in the data (R7): a feat's recorded {@code hpPerLevel}, such as Tough's, and the race's
 * {@code hpPerLevel}, such as the Hill Dwarf's, for every character level, and a subclass's {@code hpPerClassLevel},
 * such as the Draconic Bloodline's, for every level in its class. The class tables come from the content module.
 */
@Component
@RequiredArgsConstructor
public class CharacterStats {

    static final String BARBARIAN = "Barbarian";

    // The effects that add hit points for every level: a race's for every character level, a subclass's for every
    // level in its class
    static final String HP_PER_LEVEL = "hpPerLevel";
    static final String HP_PER_CLASS_LEVEL = "hpPerClassLevel";

    private static final int PRIMAL_CHAMPION_LEVEL = 20;
    private static final int DEFAULT_SPEED = 30;
    // A character created without a class has always had d8 levels
    private static final int CLASSLESS_HIT_DIE = 8;
    // The subclasses that give a class Spellcasting, the Eldritch Knight and Arcane Trickster, cast with Intelligence
    // (PHB p.75, p.98)
    private static final String SUBCLASS_CASTER_ABILITY = "INT";

    private final CharacterClassRepository classRepository;
    private final SubclassRepository subclassRepository;
    private final ResourcePoolService resourcePoolService;
    private final ObjectMapper objectMapper;
    private final ClassTables classTables;

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
        List<ClassSpellcasting> spellcasting = spellcasting(character, classes, scores, proficiencyBonus);
        String mainAbility = mainAbility(character, spellcasting);
        Integer mainModifier = mainAbility != null
                ? Ability.modifier(scores.get(Ability.parse(mainAbility).orElseThrow())) : null;

        Map<String, Integer> poolContext = resourcePoolService.buildCharacterContext(character);
        poolContext.put("proficiencyBonus", proficiencyBonus);

        return new DerivedStats(
                proficiencyBonus,
                hitPointMaximum(character, orEmpty(character.getLevelHistory()), classes,
                        DmAdjustments.total(character, Stat.HP_MAX)),
                Ability.modifier(scores.get(Ability.DEX)) + featBonus(character, AppliedFeatEffects::initiativeBonus)
                        + DmAdjustments.total(character, Stat.INITIATIVE),
                // A speed taken below 0 stops there
                Math.max(0, raceSpeed(character.getRaceRef()) + featBonus(character, AppliedFeatEffects::speedBonus)
                        + DmAdjustments.total(character, Stat.SPEED)),
                scores,
                abilityScoreMaximums(classes),
                spellSlots(character, classes),
                hitDice(character, classes),
                spellcasting,
                mainAbility,
                mainModifier != null
                        ? 8 + proficiencyBonus + mainModifier + DmAdjustments.total(character, Stat.SPELL_SAVE_DC) : null,
                mainModifier != null
                        ? proficiencyBonus + mainModifier + DmAdjustments.total(character, Stat.SPELL_ATTACK_BONUS) : null,
                resourcePoolService.reconciledPools(character, poolContext),
                warnings(character, spellcasting));
    }

    /**
     * Derives the stats and writes the stored copies: the proficiency bonus, hit point maximum, initiative, speed,
     * spell slots, Hit Dice, main spell DC and attack bonus, and the resource pools. A higher hit point maximum raises
     * current hit points by as much; a lower one caps them (D167). A dead character stays at 0 hit points whatever its
     * maximum does, since only the DM's revival brings it back (D166).
     */
    public DerivedStats apply(PlayerCharacter character) {
        DerivedStats stats = derive(character);
        int current = character.getHpCurrent() != null ? character.getHpCurrent() : stats.hpMax();
        int newCurrent = CurrentHitPoints.afterMaximum(current, character.getHpMax(), stats.hpMax(),
                character.isDead());

        character.setProficiencyBonus(stats.proficiencyBonus());
        character.setHpMax(stats.hpMax());
        character.setHpCurrent(newCurrent);
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
        return spellcasting(character, classes(character), abilityScores(character),
                ProficiencyBonus.forLevel(level(character)));
    }

    /** The main spellcasting ability, which race and feat spells use; null for a character without one. */
    public String mainSpellcastingAbility(PlayerCharacter character) {
        return mainAbility(character, spellcasting(character));
    }

    /**
     * The spellcasting ability of the first of the character's classes that casts, in the order taken: its own, or
     * Intelligence for an Eldritch Knight or Arcane Trickster from 3rd level. The stored spellcasting ability plays no
     * part. Null when none of its classes casts.
     *
     * @throws ConflictException when a class the character has can't be found, or its ability can't be read
     */
    public String classSpellcastingAbility(PlayerCharacter character) {
        ClassLevel c = firstCaster(character);
        if (c == null) return null;
        String abilityName = castingAbility(c);
        return Ability.parse(abilityName).map(Ability::name).orElseThrow(() -> new ConflictException(
                "The spellcasting ability of the class " + c.name() + " can't be read: " + abilityName));
    }

    /**
     * The class entry whose ability {@link #classSpellcastingAbility} gives: the first of the character's classes that
     * casts; null when none does.
     */
    public ClassEntry classSpellcastingEntry(PlayerCharacter character) {
        ClassLevel c = firstCaster(character);
        return c != null ? c.entry() : null;
    }

    private ClassLevel firstCaster(PlayerCharacter character) {
        return classes(character).stream().filter(c -> castingAbility(c) != null).findFirst().orElse(null);
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
     * The hit point maximum some of the character's levels give, such as the first few of a character being created:
     * each one's hit die result plus the current Constitution modifier, and the per-level bonuses the feats', race's
     * and subclasses' effects give, such as Tough's, the Hill Dwarf's and the Draconic Bloodline's (D167). The DM's
     * hit point adjustments aren't the levels', so aren't included.
     *
     * @throws ConflictException for no levels
     */
    public int hitPointMaximum(PlayerCharacter character, List<LevelHistoryEntry> levels) {
        return hitPointMaximum(character, levels, classes(character), 0);
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

    // Each ability score with the DM's adjustments to it, which everything worked out from the scores uses: the
    // modifiers, hit points from Constitution, initiative from Dexterity, and the spell numbers
    private static Map<Ability, Integer> abilityScores(PlayerCharacter character) {
        Map<Ability, Integer> scores = new EnumMap<>(Ability.class);
        for (Ability ability : Ability.values()) {
            scores.put(ability, DmAdjustments.abilityScore(character, ability));
        }
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

    // The levels' hit points, with the adjustment, the DM's hit point adjustments, added on top
    private int hitPointMaximum(PlayerCharacter character, List<LevelHistoryEntry> history, List<ClassLevel> classes,
                                int adjustment) {
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
        int featHitPoints = featBonus(character, AppliedFeatEffects::hpPerLevel);
        if (featHitPoints != 0) {
            bonuses.add(HitPoints.PerLevelBonus.everyLevel(featHitPoints));
        }
        Race race = character.getRaceRef();
        if (race != null) {
            int raceHitPoints = effect(race.getEffects(), HP_PER_LEVEL, "race " + race.getName());
            if (raceHitPoints != 0) {
                bonuses.add(HitPoints.PerLevelBonus.everyLevel(raceHitPoints));
            }
        }
        for (ClassLevel c : classes) {
            UUID subclassId = c.entry().subclassId();
            if (subclassId == null) continue;
            Subclass subclass = subclassRepository.findById(subclassId)
                    .orElseThrow(() -> new ConflictException("The subclass " + c.entry().subclassName()
                            + " that the character has can't be found"));
            int subclassHitPoints = effect(subclass.getEffects(), HP_PER_CLASS_LEVEL, "subclass " + subclass.getName());
            if (subclassHitPoints != 0) {
                bonuses.add(HitPoints.PerLevelBonus.perClassLevel(c.name(), subclassHitPoints));
            }
        }
        return HitPoints.maximum(levels, Ability.modifier(DmAdjustments.abilityScore(character, Ability.CON)), bonuses,
                adjustment);
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

    // A whole-number effect in a race's or subclass's effects, such as {"hpPerLevel": 1}; 0 for none
    private int effect(String effects, String name, String of) {
        if (effects == null || effects.isBlank()) {
            return 0;
        }
        try {
            JsonNode value = objectMapper.readTree(effects).get(name);
            if (value == null || value.isNull()) return 0;
            if (value.isIntegralNumber() && value.canConvertToInt()) return value.asInt(0);
        } catch (JacksonException e) {
            // Falls through to the 409 below
        }
        throw new ConflictException("The effects of the " + of + " can't be read: " + effects);
    }

    // ── Initiative and speed ─────────────────────────────────────

    // The bonus the character's feats recorded, such as Alert's +5 initiative or Mobile's +10 speed: those the level
    // history records, at an Ability Score Improvement or at creation, and those the DM granted
    private static int featBonus(PlayerCharacter character, Function<AppliedFeatEffects, Integer> bonus) {
        int total = 0;
        for (AppliedFeatEffects feat : DmAdjustments.featsTaken(character)) {
            Integer value = bonus.apply(feat);
            total += value != null ? value : 0;
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
            if (speed.isNumber()) return speed.asInt(0);
            if (speed.path("walk").isNumber()) return speed.path("walk").asInt(0);
        } catch (JacksonException e) {
            // Falls through to the 409 below
        }
        throw new ConflictException("The speed of the race " + race.getName() + " can't be read: " + race.getSpeed());
    }

    // ── Spell slots and Hit Dice ─────────────────────────────────

    private Map<String, SpellSlotCalculator.ClassEntry> spellSlotEntries(List<ClassLevel> classes) {
        Map<String, SpellSlotCalculator.ClassEntry> entries = new LinkedHashMap<>();
        for (ClassLevel c : classes) {
            CharacterClass cc = c.characterClass();
            if (castingAbility(c) == null) continue;
            String casterType = SpellSlotCalculator.casterType(classTables, cc.getName(),
                    Boolean.TRUE.equals(cc.getIsSpellcaster()), Boolean.TRUE.equals(cc.getIsPactMagic()),
                    c.entry().subclassName());
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
    private Map<String, SpellSlot> spellSlots(PlayerCharacter character, List<ClassLevel> classes) {
        Map<String, SpellSlotCalculator.ClassEntry> entries = spellSlotEntries(classes);
        Map<String, Integer> maximums = entries.isEmpty() ? Map.of()
                : SpellSlotCalculator.calculateSlots(classTables, entries);
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

    // Each casting class's numbers, with the DM's spell save DC and attack bonus adjustments, which apply to every class
    private List<ClassSpellcasting> spellcasting(PlayerCharacter character, List<ClassLevel> classes,
                                                 Map<Ability, Integer> scores, int proficiencyBonus) {
        int saveDcAdjustment = DmAdjustments.total(character, Stat.SPELL_SAVE_DC);
        int attackBonusAdjustment = DmAdjustments.total(character, Stat.SPELL_ATTACK_BONUS);
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
                    ClassProgression.spellList(classTables, c.name(), subclass).orElse(c.name()),
                    classLevel,
                    ability.name(),
                    8 + proficiencyBonus + modifier + saveDcAdjustment,
                    proficiencyBonus + modifier + attackBonusAdjustment,
                    ClassProgression.highestSpellLevel(classTables, c.name(), subclass, classLevel),
                    ClassProgression.cantripsKnown(classTables, c.name(), subclass, classLevel),
                    boxed(ClassProgression.spellsKnown(classTables, c.name(), subclass, classLevel)),
                    boxed(ClassProgression.preparedSpells(classTables, c.name(), classLevel, modifier)),
                    Boolean.TRUE.equals(c.characterClass().getIsPactMagic())));
        }
        return spellcasting;
    }

    // The class's spellcasting ability: its own, or Intelligence for a subclass the class tables give Spellcasting,
    // an Eldritch Knight or Arcane Trickster, from the subclass's level; null for a class that doesn't cast
    private String castingAbility(ClassLevel c) {
        if (Boolean.TRUE.equals(c.characterClass().getIsSpellcaster())) {
            return c.characterClass().getSpellcastingAbility();
        }
        boolean subclassCasts = ClassProgression.subclassCasterType(classTables, c.name(), c.entry().subclassName())
                .isPresent() && c.entry().level() >= classTables.subclassLevel(c.name());
        return subclassCasts ? SUBCLASS_CASTER_ABILITY : null;
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

    private static Integer boxed(OptionalInt value) {
        return value.isPresent() ? value.getAsInt() : null;
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list != null ? list : List.of();
    }
}
