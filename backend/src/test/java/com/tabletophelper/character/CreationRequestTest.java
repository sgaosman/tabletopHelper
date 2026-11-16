package com.tabletophelper.character;

import com.tabletophelper.character.dto.CharacterCreateRequest;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.PendingChoice;
import com.tabletophelper.character.state.PendingChoice.Type;
import com.tabletophelper.reference.Background;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.Race;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.Subclass;
import com.tabletophelper.rules.combat.DamageRules;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * Creating a character checks what the request gives it (D232; open item of the security review D221): the saving
 * throws are the first class's (PHB p.164), resistances only the race's (D158), skills only the race's, classes' and
 * background's (PHB p.125, D226), Expertise only in skills held and no more than the levels give (PHB p.96, p.54), and
 * spells only on the class's list, of levels it casts and within its numbers (PHB p.114, p.164), race spells as the
 * race data has them (D231). The requests the creation wizard sends, as its request snapshots
 * (CharacterCreateWizard.requests.test.tsx) and selectors build them, still pass.
 */
class CreationRequestTest {

    private final CharacterFixture f = new CharacterFixture();
    private final Map<String, Spell> spells = new HashMap<>();

    private final CharacterClass bard = CharacterClass.builder().id(UUID.randomUUID()).name("Bard").hitDice(8)
            .subclassLevel(3).isSpellcaster(true).isPactMagic(false).spellcastingAbility("CHA")
            .savingThrowProficiencies("[\"DEX\",\"CHA\"]")
            .skillChoices("{\"count\":3,\"from\":[\"Acrobatics\",\"Animal Handling\",\"Arcana\",\"Athletics\","
                    + "\"Deception\",\"History\",\"Insight\",\"Intimidation\",\"Investigation\",\"Medicine\","
                    + "\"Nature\",\"Perception\",\"Performance\",\"Persuasion\",\"Religion\",\"Sleight of Hand\","
                    + "\"Stealth\",\"Survival\"]}")
            .build();
    private final Subclass lifeDomain = Subclass.builder().id(UUID.randomUUID()).name("Life Domain")
            .characterClass(f.cleric).features("[]").build();

    private final Race humanRace = Race.builder().id(UUID.randomUUID()).name("Human").speed("{\"walk\": 30}")
            .resistances("[]").proficiencies("{\"languages\":[\"Common\"]}")
            .raceChoices("{\"languages\":[{\"anyStandard\":1}]}").build();
    private final Race variantHuman = Race.builder().id(UUID.randomUUID()).name("Variant Human")
            .speed("{\"walk\": 30}").resistances("[]")
            .raceChoices("{\"feats\":1,\"skills\":[{\"any\":1}],\"languages\":[{\"anyStandard\":1}]}").build();
    private final Race highElf = Race.builder().id(UUID.randomUUID()).name("High Elf").speed("{\"walk\": 30}")
            .resistances("[]").proficiencies("{\"skills\":[\"Perception\"],\"languages\":[\"Common\",\"Elvish\"]}")
            .build();
    private final Race tiefling = Race.builder().id(UUID.randomUUID()).name("Tiefling").speed("{\"walk\": 30}")
            .resistances("[\"fire\"]")
            .additionalSpells("{\"fixedSpells\":[{\"name\":\"Thaumaturgy\",\"level\":0,\"atWill\":true},"
                    + "{\"name\":\"Hellish Rebuke\",\"level\":1,\"unlocksAtLevel\":3,\"usesPerLongRest\":1},"
                    + "{\"name\":\"Darkness\",\"level\":2,\"unlocksAtLevel\":5,\"usesPerLongRest\":1}]}")
            .build();
    private final Race dragonborn = Race.builder().id(UUID.randomUUID()).name("Dragonborn").speed("{\"walk\": 30}")
            .resistances("[]").traits("[{\"name\":\"Draconic Ancestry\",\"description\":\"Choose a dragon\"}]")
            .build();

    private final Background soldier = Background.builder().id(UUID.randomUUID()).name("Soldier")
            .skillProficiencies("[\"Athletics\",\"Intimidation\"]").toolProficiencies("[\"Vehicles (Land)\"]")
            .build();
    private final Background sage = Background.builder().id(UUID.randomUUID()).name("Sage")
            .skillProficiencies("[\"Arcana\",\"History\"]").languageProficiencies("[{\"anyStandard\":2}]").build();
    private final Background acolyte = Background.builder().id(UUID.randomUUID()).name("Acolyte")
            .skillProficiencies("[\"Insight\",\"Religion\"]").languageProficiencies("[{\"anyStandard\":2}]").build();
    private final Background criminal = Background.builder().id(UUID.randomUUID()).name("Criminal")
            .skillProficiencies("[\"Deception\",\"Stealth\"]").build();

    @BeforeEach
    void knowTheReferenceData() {
        lenient().when(f.classRepository.findById(bard.getId())).thenReturn(Optional.of(bard));
        lenient().when(f.subclassRepository.findById(lifeDomain.getId())).thenReturn(Optional.of(lifeDomain));
        for (Race race : List.of(humanRace, variantHuman, highElf, tiefling, dragonborn)) {
            lenient().when(f.raceRepository.findById(race.getId())).thenReturn(Optional.of(race));
        }
        for (Background background : List.of(soldier, sage, acolyte, criminal)) {
            lenient().when(f.backgroundRepository.findById(background.getId())).thenReturn(Optional.of(background));
        }
        spell("Fire Bolt", 0, "Sorcerer", "Wizard");
        spell("Mage Hand", 0, "Bard", "Sorcerer", "Warlock", "Wizard");
        spell("Prestidigitation", 0, "Sorcerer", "Warlock", "Wizard");
        spell("Ray of Frost", 0, "Sorcerer", "Wizard");
        spell("Guidance", 0, "Cleric", "Druid");
        spell("Sacred Flame", 0, "Cleric");
        spell("Thaumaturgy", 0, "Cleric");
        spell("Burning Hands", 1, "Sorcerer", "Wizard");
        spell("Detect Magic", 1, "Bard", "Cleric", "Druid", "Paladin", "Ranger", "Sorcerer", "Wizard");
        spell("Mage Armor", 1, "Sorcerer", "Wizard");
        spell("Magic Missile", 1, "Sorcerer", "Wizard");
        spell("Shield", 1, "Sorcerer", "Wizard");
        spell("Sleep", 1, "Bard", "Sorcerer", "Wizard");
        spell("Thunderwave", 1, "Bard", "Druid", "Sorcerer", "Wizard");
        spell("Bless", 1, "Cleric", "Paladin");
        spell("Cure Wounds", 1, "Bard", "Cleric", "Druid", "Paladin", "Ranger");
        spell("Misty Step", 2, "Sorcerer", "Warlock", "Wizard");
        spell("Wish", 9, "Sorcerer", "Wizard");
        lenient().when(f.spellRepository.findByNameIgnoreCase(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(spells.get(inv.<String>getArgument(0).toLowerCase(Locale.ROOT))));
    }

    private void spell(String name, int level, String... classes) {
        String lists = "[" + String.join(",", java.util.Arrays.stream(classes).map(c -> "\"" + c + "\"").toList())
                + "]";
        spells.put(name.toLowerCase(Locale.ROOT), Spell.builder().id(UUID.randomUUID()).name(name).level(level)
                .classes(lists).build());
    }

    /** A request in one class, with the standard array as the wizard's tests assign it, adjusted by the test. */
    private CharacterCreateRequest request(Race race, CharacterClass characterClass, int level,
                                           Consumer<CharacterCreateRequest> adjust) {
        CharacterCreateRequest request = new CharacterCreateRequest();
        request.setName("Aria");
        request.setRaceId(race.getId());
        request.setClassId(characterClass.getId());
        request.setLevel(level);
        request.setStrength(15);
        request.setDexterity(14);
        request.setConstitution(13);
        request.setIntelligence(16);
        request.setWisdom(12);
        request.setCharisma(10);
        adjust.accept(request);
        return request;
    }

    private PlayerCharacter create(CharacterCreateRequest request) {
        return f.stored(f.service.createCharacter(request, f.userId).getId());
    }

    private static KnownSpell classSpell(String name, int level, String className) {
        return new KnownSpell(null, name, level, "class:" + className, null, null, null, null, null);
    }

    private static List<KnownSpell> spellsOf(String className, String... names) {
        return java.util.Arrays.stream(names).map(name -> classSpell(name, 0, className)).toList();
    }

    @Nested
    @DisplayName("The wizard's requests still pass")
    class WizardRequests {

        @Test
        @DisplayName("Fighter 1: a Human soldier, with the class's saves, two fighter skills and the soldier's two")
        void fighterSoldier() {
            PlayerCharacter pc = create(request(humanRace, f.fighter, 1, r -> {
                r.setBackgroundId(soldier.getId());
                r.setSavingThrowProficiencies(List.of("STR", "CON"));
                r.setSkillProficiencies(List.of("Acrobatics", "Perception", "Athletics", "Intimidation"));
            }));

            assertThat(pc.getSavingThrowProficiencies()).containsExactly("STR", "CON");
            assertThat(pc.getSkillProficiencies()).containsExactly("Acrobatics", "Perception", "Athletics",
                    "Intimidation");
        }

        @Test
        @DisplayName("Wizard 1: a High Elf sage with three cantrips and six spellbook spells")
        void wizardSage() {
            PlayerCharacter pc = create(request(highElf, f.wizard, 1, r -> {
                r.setBackgroundId(sage.getId());
                r.setSavingThrowProficiencies(List.of("INT", "WIS"));
                r.setSkillProficiencies(List.of("Perception", "Investigation", "Medicine", "Arcana", "History"));
                r.setSpellsKnown(List.of(classSpell("Fire Bolt", 0, "Wizard"), classSpell("Mage Hand", 0, "Wizard"),
                        classSpell("Prestidigitation", 0, "Wizard"), classSpell("Burning Hands", 1, "Wizard"),
                        classSpell("Detect Magic", 1, "Wizard"), classSpell("Mage Armor", 1, "Wizard"),
                        classSpell("Magic Missile", 1, "Wizard"), classSpell("Shield", 1, "Wizard"),
                        classSpell("Sleep", 1, "Wizard")));
            }));

            assertThat(pc.getSpellsKnown()).hasSize(9).allMatch(s -> "class:Wizard".equals(s.source()));
            assertThat(pc.getPendingChoices()).noneMatch(c -> c.type() == Type.SPELLS || c.type() == Type.CANTRIPS);
        }

        @Test
        @DisplayName("Cleric 1: a Variant Human acolyte of the Life Domain with three cantrips, its feat left pending")
        void clericAcolyte() {
            PlayerCharacter pc = create(request(variantHuman, f.cleric, 1, r -> {
                r.setSubclassId(lifeDomain.getId());
                r.setBackgroundId(acolyte.getId());
                r.setSavingThrowProficiencies(List.of("WIS", "CHA"));
                r.setSkillProficiencies(List.of("Perception", "Medicine", "Persuasion", "Insight", "Religion"));
                r.setSpellsKnown(spellsOf("Cleric", "Guidance", "Sacred Flame", "Thaumaturgy"));
            }));

            assertThat(pc.getSavingThrowProficiencies()).containsExactly("WIS", "CHA");
            assertThat(pc.getPendingChoices()).anyMatch(c -> c.type() == Type.FEAT);
        }

        @Test
        @DisplayName("Fighter 3 / Wizard 2: the fighter's saves only, and the soldier's Athletics replaced by Stealth")
        void fighterWizard() {
            PlayerCharacter pc = create(request(humanRace, f.fighter, 5, r -> {
                r.setBackgroundId(soldier.getId());
                r.setMulticlassClassEntries(List.of(new ClassEntry(f.fighter.getId(), null, null, null, 3),
                        new ClassEntry(f.wizard.getId(), null, null, null, 2)));
                r.setSavingThrowProficiencies(List.of("STR", "CON"));
                r.setSkillProficiencies(List.of("Athletics", "Survival", "Intimidation", "Stealth"));
            }));

            assertThat(pc.getSavingThrowProficiencies()).containsExactly("STR", "CON");
            assertThat(pc.getSkillProficiencies()).containsExactly("Athletics", "Survival", "Intimidation",
                    "Stealth");
        }

        @Test
        @DisplayName("A Red Dragonborn resists fire, recorded with its ancestry (PHB p.34, D208)")
        void dragonborn() {
            PlayerCharacter pc = create(request(dragonborn, f.paladin, 1, r -> {
                r.setDraconicAncestry("Red");
                r.setDamageResistances(List.of("fire"));
                r.setSkillProficiencies(List.of("Athletics", "Persuasion"));
            }));

            assertThat(pc.getDamageResistances()).containsExactly("fire");
            assertThat(pc.getLevelHistory().getFirst().choices().draconicAncestry()).isEqualTo("Red");
        }

        @Test
        @DisplayName("A Tiefling Sorcerer 3 resists fire and casts its race's spells as the race data has them")
        void tiefling() {
            PlayerCharacter pc = create(request(tiefling, f.sorcerer, 3, r -> {
                r.setDamageResistances(List.of("fire"));
                r.setSpellsKnown(List.of(
                        new KnownSpell(null, "Thaumaturgy", 0, "race:Tiefling", null, null, true, null, null),
                        new KnownSpell(null, "Hellish Rebuke", 1, "race:Tiefling", null, null, null, 1, 3),
                        classSpell("Fire Bolt", 0, "Sorcerer"), classSpell("Ray of Frost", 0, "Sorcerer"),
                        classSpell("Mage Hand", 0, "Sorcerer"), classSpell("Prestidigitation", 0, "Sorcerer"),
                        classSpell("Shield", 1, "Sorcerer"), classSpell("Misty Step", 2, "Sorcerer")));
            }));

            assertThat(pc.getDamageResistances()).containsExactly("fire");
            assertThat(pc.getSpellsKnown()).filteredOn(s -> "race:Tiefling".equals(s.source()))
                    .extracting(KnownSpell::name).containsExactly("Thaumaturgy", "Hellish Rebuke");
            assertThat(pc.getPendingChoices()).filteredOn(c -> c.type() == Type.SPELLS)
                    .extracting(PendingChoice::count).containsExactly(1, 1);
        }

        @Test
        @DisplayName("A Rogue 1 criminal with Expertise in two of its skills")
        void rogueExpertise() {
            PlayerCharacter pc = create(request(humanRace, f.rogue, 1, r -> {
                r.setBackgroundId(criminal.getId());
                r.setSkillProficiencies(List.of("Acrobatics", "Perception", "Sleight of Hand", "Investigation",
                        "Deception", "Stealth"));
                r.setSkillExpertises(List.of("Stealth", "Sleight of Hand"));
            }));

            assertThat(pc.getSkillExpertises()).containsExactly("Stealth", "Sleight of Hand");
            assertThat(pc.getPendingChoices()).noneMatch(c -> c.type() == Type.EXPERTISE);
        }
    }

    @Nested
    @DisplayName("A crafted request is refused")
    class Crafted {

        @Test
        @DisplayName("Every saving throw asked for is ignored: a Fighter has STR and CON (PHB p.164)")
        void everySave() {
            PlayerCharacter pc = create(request(humanRace, f.fighter, 1, r -> r.setSavingThrowProficiencies(
                    List.of("STR", "DEX", "CON", "INT", "WIS", "CHA"))));
            assertThat(pc.getSavingThrowProficiencies()).containsExactly("STR", "CON");

            PlayerCharacter multiclass = create(request(humanRace, f.wizard, 2, r -> {
                r.setMulticlassClassEntries(List.of(new ClassEntry(f.wizard.getId(), null, null, null, 1),
                        new ClassEntry(f.fighter.getId(), null, null, null, 1)));
                r.setSavingThrowProficiencies(List.of("INT", "WIS", "STR", "CON"));
            }));
            assertThat(multiclass.getSavingThrowProficiencies()).as("multiclassing adds no saves")
                    .containsExactly("INT", "WIS");
        }

        @Test
        @DisplayName("Resistance to all 13 damage types is a 400 naming them (D158)")
        void everyResistance() {
            CharacterCreateRequest request = request(tiefling, f.fighter, 1,
                    r -> r.setDamageResistances(DamageRules.DAMAGE_TYPES));
            assertThatThrownBy(() -> create(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageStartingWith("Resistance to acid, bludgeoning, cold, force,")
                    .hasMessageContaining("isn't one the Tiefling gives");
        }

        @Test
        @DisplayName("Proficiency in every skill is a 400")
        void everySkill() {
            CharacterCreateRequest request = request(humanRace, f.fighter, 1, r -> r.setSkillProficiencies(
                    com.tabletophelper.rules.character.ProficiencyLists.SKILLS));
            assertThatThrownBy(() -> create(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("isn't one the race, classes and background give (2 skills in all)");
        }

        @Test
        @DisplayName("Expertise in a skill without proficiency, or in more skills than the levels give, is a 400; "
                + "Rogue 6 gives four and Bard 3 two, and what isn't chosen stays pending (PHB p.96, p.54)")
        void expertise() {
            assertThatThrownBy(() -> create(request(humanRace, f.rogue, 1, r -> {
                r.setSkillProficiencies(List.of("Stealth"));
                r.setSkillExpertises(List.of("Stealth", "Arcana"));
            })))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Aria isn't proficient in Arcana, so can't have Expertise in it");
            assertThatThrownBy(() -> create(request(humanRace, f.rogue, 1, r -> {
                r.setSkillProficiencies(List.of("Stealth", "Perception", "Acrobatics"));
                r.setSkillExpertises(List.of("Stealth", "Perception", "Acrobatics"));
            })))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("A character created at level 1 with these classes has Expertise in 2 skills, not 3");

            PlayerCharacter rogue6 = create(request(humanRace, f.rogue, 6, r -> {
                r.setSkillProficiencies(List.of("Stealth", "Perception", "Acrobatics", "Deception"));
                r.setSkillExpertises(List.of("Stealth", "Perception", "Acrobatics", "Deception"));
            }));
            assertThat(rogue6.getLevelHistory().get(5).choices().expertise()).containsExactly("Acrobatics",
                    "Deception");
            assertThat(rogue6.getPendingChoices()).noneMatch(c -> c.type() == Type.EXPERTISE);

            PlayerCharacter bard3 = create(request(humanRace, bard, 3, r -> {
                r.setSkillProficiencies(List.of("Performance", "Persuasion", "Deception"));
                r.setSkillExpertises(List.of("Performance"));
            }));
            assertThat(bard3.getLevelHistory().get(2).choices().expertise()).containsExactly("Performance");
            assertThat(bard3.getPendingChoices()).filteredOn(c -> c.type() == Type.EXPERTISE)
                    .extracting(PendingChoice::count).containsExactly(1);
            assertThatThrownBy(() -> create(request(humanRace, bard, 3, r -> {
                r.setSkillProficiencies(List.of("Performance", "Persuasion", "Deception"));
                r.setSkillExpertises(List.of("Performance", "Persuasion", "Deception"));
            })))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("A character created at level 3 with these classes has Expertise in 2 skills, not 3");
        }

        @Test
        @DisplayName("A 9th-level spell at Wizard 1 is a 400, and so is one sent as a cantrip: the data's level counts")
        void spellLevel() {
            assertThatThrownBy(() -> create(request(humanRace, f.wizard, 1,
                    r -> r.setSpellsKnown(List.of(classSpell("Wish", 9, "Wizard"))))))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("A level 1 Wizard casts spells up to level 1, so can't take Wish (level 9)");
            assertThatThrownBy(() -> create(request(humanRace, f.wizard, 1,
                    r -> r.setSpellsKnown(List.of(classSpell("Wish", 0, "Wizard"))))))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("A level 1 Wizard casts spells up to level 1, so can't take Wish (level 9)");
        }

        @Test
        @DisplayName("A spell off the class's list, one that doesn't exist, or one of a class the character hasn't is "
                + "a 400")
        void spellLists() {
            assertThatThrownBy(() -> create(request(humanRace, f.wizard, 1,
                    r -> r.setSpellsKnown(List.of(classSpell("Cure Wounds", 1, "Wizard"))))))
                    .isInstanceOf(ValidationException.class).hasMessage("Cure Wounds isn't on the Wizard spell list");
            assertThatThrownBy(() -> create(request(humanRace, f.wizard, 1,
                    r -> r.setSpellsKnown(List.of(classSpell("Power Word Win", 1, "Wizard"))))))
                    .isInstanceOf(ValidationException.class).hasMessage("There is no spell called Power Word Win");
            assertThatThrownBy(() -> create(request(humanRace, f.fighter, 1,
                    r -> r.setSpellsKnown(List.of(classSpell("Fire Bolt", 0, "Wizard"))))))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Aria casts no Wizard spells at creation, so can't know Fire Bolt");
        }

        @Test
        @DisplayName("More cantrips, spellbook spells, spells known or spells prepared than the class has is a 400")
        void spellNumbers() {
            assertThatThrownBy(() -> create(request(humanRace, f.wizard, 1, r -> r.setSpellsKnown(spellsOf("Wizard",
                    "Fire Bolt", "Mage Hand", "Prestidigitation", "Ray of Frost")))))
                    .isInstanceOf(ValidationException.class).hasMessage("A level 1 Wizard knows 3 cantrips, not 4");
            assertThatThrownBy(() -> create(request(humanRace, f.wizard, 1, r -> r.setSpellsKnown(List.of(
                    classSpell("Burning Hands", 1, "Wizard"), classSpell("Detect Magic", 1, "Wizard"),
                    classSpell("Mage Armor", 1, "Wizard"), classSpell("Magic Missile", 1, "Wizard"),
                    classSpell("Shield", 1, "Wizard"), classSpell("Sleep", 1, "Wizard"),
                    classSpell("Thunderwave", 1, "Wizard"))))))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("A level 1 Wizard has 6 spells in its spellbook, not 7");
            assertThatThrownBy(() -> create(request(humanRace, f.sorcerer, 1, r -> r.setSpellsKnown(List.of(
                    classSpell("Shield", 1, "Sorcerer"), classSpell("Sleep", 1, "Sorcerer"),
                    classSpell("Magic Missile", 1, "Sorcerer"))))))
                    .isInstanceOf(ValidationException.class).hasMessage("A level 1 Sorcerer knows 2 spells, not 3");
            // WIS 12: a Cleric 1 prepares 1 + 1 spells (PHB p.58)
            assertThatThrownBy(() -> create(request(humanRace, f.cleric, 1, r -> r.setSpellsKnown(List.of(
                    classSpell("Bless", 1, "Cleric"), classSpell("Cure Wounds", 1, "Cleric"),
                    classSpell("Detect Magic", 1, "Cleric"))))))
                    .isInstanceOf(ValidationException.class).hasMessage("A level 1 Cleric prepares 2 spells, not 3");
        }

        @Test
        @DisplayName("A race spell the race lacks, or a spell of another source, is a 400; a race spell's casting is "
                + "the data's, not the request's")
        void spellSources() {
            assertThatThrownBy(() -> create(request(humanRace, f.fighter, 1, r -> r.setSpellsKnown(List.of(
                    new KnownSpell(null, "Wish", 9, "race:Human", null, null, true, null, null))))))
                    .isInstanceOf(ValidationException.class).hasMessage("Wish isn't one of the Human's spells at level 1");
            assertThatThrownBy(() -> create(request(tiefling, f.fighter, 1, r -> r.setSpellsKnown(List.of(
                    new KnownSpell(null, "Hellish Rebuke", 1, "race:Tiefling", null, null, true, null, null))))))
                    .as("Hellish Rebuke comes at 3rd level")
                    .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> create(request(humanRace, f.fighter, 1, r -> r.setSpellsKnown(List.of(
                    new KnownSpell(null, "Misty Step", 2, "feat:Fey Touched", null, null, true, null, null))))))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageStartingWith("Misty Step comes from feat:Fey Touched");

            PlayerCharacter pc = create(request(tiefling, f.fighter, 3, r -> r.setSpellsKnown(List.of(
                    new KnownSpell(null, "Hellish Rebuke", 9, "race:Tiefling", null, true, true, 99, null)))));
            assertThat(pc.getSpellsKnown()).singleElement().satisfies(spell -> {
                assertThat(spell.level()).isEqualTo(1);
                assertThat(spell.atWill()).isNull();
                assertThat(spell.alwaysPrepared()).isNull();
                assertThat(spell.usesPerLongRest()).isEqualTo(1);
            });
        }

        @Test
        @DisplayName("A class spell sent at will or with uses of its own casts with slots")
        void classSpellCasting() {
            PlayerCharacter pc = create(request(humanRace, f.sorcerer, 1, r -> r.setSpellsKnown(List.of(
                    new KnownSpell(null, "Shield", 1, "class:Sorcerer", null, true, true, 99, null)))));
            assertThat(pc.getSpellsKnown()).singleElement().satisfies(spell -> {
                assertThat(spell.atWill()).isNull();
                assertThat(spell.alwaysPrepared()).isNull();
                assertThat(spell.usesPerLongRest()).isNull();
            });
        }
    }
}
