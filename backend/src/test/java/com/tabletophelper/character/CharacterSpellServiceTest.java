package com.tabletophelper.character;

import com.tabletophelper.character.dto.CharacterResponse;
import com.tabletophelper.character.dto.PreparedSpellsRequest;
import com.tabletophelper.character.dto.SpellbookRequest;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * Preparing spells and a wizard's spellbook (D158), checked against the spells table's class lists, the highest level
 * each class can cast and the numbers it prepares (D167). They replaced the spells the sheet wrote through the PUT,
 * which checked none of these. The interim known-spells command's cases went to PendingChoiceServiceTest's "Cantrips
 * and spells" group in R6 workstream F, whose CANTRIPS and SPELLS choices replaced it (D159), with the same checks.
 */
class CharacterSpellServiceTest {

    private final CharacterFixture f = new CharacterFixture();
    private final Map<String, Spell> spells = new HashMap<>();

    @BeforeEach
    void knowTheSpells() {
        spell("Bless", 1, "Cleric", "Paladin", "Cleric (Life Domain)");
        spell("Cure Wounds", 1, "Bard", "Cleric", "Druid", "Paladin", "Ranger");
        spell("Shield of Faith", 1, "Cleric", "Paladin");
        spell("Aid", 2, "Cleric", "Paladin");
        spell("Spirit Guardians", 3, "Cleric");
        spell("Sacred Flame", 0, "Cleric");
        spell("Guidance", 0, "Cleric", "Druid");
        spell("Magic Missile", 1, "Sorcerer", "Wizard");
        spell("Shield", 1, "Sorcerer", "Wizard", "Warlock (The Hexblade)");
        spell("Misty Step", 2, "Sorcerer", "Warlock", "Wizard");
        spell("Fireball", 3, "Sorcerer", "Wizard");
        spell("Fire Bolt", 0, "Sorcerer", "Wizard");
        spell("Hex", 1, "Warlock");
        spell("Armor of Agathys", 1, "Warlock");
        spell("Hellish Rebuke", 1, "Warlock");
        spell("Witch Bolt", 1, "Sorcerer", "Warlock", "Wizard");
        spell("Eldritch Blast", 0, "Warlock");
        spell("Chill Touch", 0, "Sorcerer", "Warlock", "Wizard");
        spell("Minor Illusion", 0, "Bard", "Sorcerer", "Warlock", "Wizard");
        spell("Wish", 9);
        lenient().when(f.spellRepository.findByNameIgnoreCase(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(spells.get(inv.<String>getArgument(0).toLowerCase(Locale.ROOT))));
    }

    private void spell(String name, int level, String... classes) {
        String lists = classes.length == 0 ? null : "[" + String.join(",",
                Arrays.stream(classes).map(c -> "\"" + c + "\"").toList()) + "]";
        spells.put(name.toLowerCase(Locale.ROOT), Spell.builder().id(UUID.randomUUID()).name(name).level(level)
                .classes(lists).build());
    }

    // Cleric 3 with WIS 16 prepares 6 spells of up to 2nd level, and knows 3 cantrips
    private PlayerCharacter cleric() {
        return f.create(f.human, new int[]{10, 10, 12, 10, 16, 10}, f.cleric, 3);
    }

    // Wizard 3 with INT 16 prepares 6 spells of up to 2nd level from its spellbook
    private PlayerCharacter wizard() {
        return f.create(f.human, CharacterFixture.scores(), f.wizard, 3);
    }

    // Warlock 3 with CHA 16 knows 2 cantrips and 4 spells of up to 2nd level
    private PlayerCharacter warlock() {
        return f.create(f.human, new int[]{10, 10, 12, 10, 10, 16}, f.warlock, 3);
    }

    private static List<String> names(CharacterResponse response, String source, boolean prepared) {
        return response.getSpellsKnown().stream()
                .filter(s -> source.equals(s.source()) && (!prepared || Boolean.TRUE.equals(s.prepared())))
                .map(KnownSpell::name)
                .toList();
    }

    @Nested
    @DisplayName("Prepared spells")
    class Prepared {

        @Test
        @DisplayName("A cleric prepares spells from its list; those it no longer names stay on the sheet unprepared")
        void clericPrepares() {
            PlayerCharacter pc = cleric();

            f.spells.setPrepared(pc.getId(), new PreparedSpellsRequest("Cleric", List.of("bless", "Cure Wounds")),
                    f.userId);
            CharacterResponse response = f.spells.setPrepared(pc.getId(),
                    new PreparedSpellsRequest("Cleric", List.of("Cure Wounds", "Aid")), f.userId);

            assertThat(response.getSpellsKnown())
                    .extracting(KnownSpell::name, KnownSpell::level, KnownSpell::source, KnownSpell::prepared)
                    .containsExactly(tuple("Bless", 1, "class:Cleric", false),
                            tuple("Cure Wounds", 1, "class:Cleric", true), tuple("Aid", 2, "class:Cleric", true));
        }

        @Test
        @DisplayName("Always-prepared spells don't count towards the limit, and stay prepared")
        void alwaysPreparedDontCount() {
            PlayerCharacter pc = cleric();
            // WIS 10: 3 + 0 = 3 spells
            pc.setWisdom(10);
            pc.setSpellsKnown(List.of(new KnownSpell(null, "Bless", 1, "class:Cleric", true, true, null, null, null)));

            CharacterResponse response = f.spells.setPrepared(pc.getId(), new PreparedSpellsRequest("Cleric",
                    List.of("Bless", "Cure Wounds", "Shield of Faith", "Aid")), f.userId);

            assertThat(names(response, "class:Cleric", true)).containsExactly("Bless", "Cure Wounds",
                    "Shield of Faith", "Aid");
        }

        @Test
        @DisplayName("More spells than the class prepares, one it can't cast yet, or one off its list is a 400")
        void refusesWhatTheClassCantPrepare() {
            PlayerCharacter pc = cleric();
            pc.setWisdom(10);

            // WIS 10: 3 + 0 = 3 spells
            assertThatThrownBy(() -> f.spells.setPrepared(pc.getId(), new PreparedSpellsRequest("Cleric",
                    List.of("Bless", "Cure Wounds", "Shield of Faith", "Aid")), f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("A level 3 Cleric prepares 3 spells, not 4");
            assertThatThrownBy(() -> f.spells.setPrepared(pc.getId(),
                    new PreparedSpellsRequest("Cleric", List.of("Spirit Guardians")), f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("A level 3 Cleric casts spells up to level 2, so can't take Spirit Guardians (level 3)");
            assertThatThrownBy(() -> f.spells.setPrepared(pc.getId(),
                    new PreparedSpellsRequest("Cleric", List.of("Magic Missile")), f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Magic Missile isn't on the Cleric spell list");
            assertThatThrownBy(() -> f.spells.setPrepared(pc.getId(),
                    new PreparedSpellsRequest("Cleric", List.of("Sacred Flame")), f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Sacred Flame is a cantrip");
            assertThatThrownBy(() -> f.spells.setPrepared(pc.getId(),
                    new PreparedSpellsRequest("Cleric", List.of("Mordenkainen's Lunch")), f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("There is no spell called Mordenkainen's Lunch");
            assertThat(pc.getSpellsKnown()).isNull();
        }

        @Test
        @DisplayName("A class that knows its spells, or one the character has no levels in, prepares none")
        void onlyPreparedCastersPrepare() {
            PlayerCharacter pc = warlock();

            assertThatThrownBy(() -> f.spells.setPrepared(pc.getId(),
                    new PreparedSpellsRequest("Warlock", List.of("Hex")), f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("A Warlock knows its spells rather than preparing them");
            assertThatThrownBy(() -> f.spells.setPrepared(pc.getId(),
                    new PreparedSpellsRequest("Druid", List.of("Cure Wounds")), f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Aria casts no Druid spells");
        }

        @Test
        @DisplayName("A wizard prepares only spells in its spellbook")
        void wizardPreparesFromItsSpellbook() {
            PlayerCharacter pc = wizard();
            f.spells.addToSpellbook(pc.getId(), new SpellbookRequest(List.of("Magic Missile", "Shield")), f.userId);

            CharacterResponse response = f.spells.setPrepared(pc.getId(),
                    new PreparedSpellsRequest("Wizard", List.of("Shield")), f.userId);

            assertThat(names(response, "class:Wizard", false)).containsExactly("Magic Missile", "Shield");
            assertThat(names(response, "class:Wizard", true)).containsExactly("Shield");
            assertThatThrownBy(() -> f.spells.setPrepared(pc.getId(),
                    new PreparedSpellsRequest("Wizard", List.of("Misty Step")), f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Misty Step isn't in Aria's spellbook");
        }
    }

    @Nested
    @DisplayName("Spellbook")
    class Spellbook {

        @Test
        @DisplayName("A wizard copies wizard spells it can cast into its spellbook, each once")
        void copiesSpells() {
            PlayerCharacter pc = wizard();

            f.spells.addToSpellbook(pc.getId(), new SpellbookRequest(List.of("Magic Missile")), f.userId);
            CharacterResponse response = f.spells.addToSpellbook(pc.getId(),
                    new SpellbookRequest(List.of("magic missile", "Misty Step")), f.userId);

            assertThat(response.getSpellsKnown())
                    .extracting(KnownSpell::name, KnownSpell::level, KnownSpell::source, KnownSpell::prepared)
                    .containsExactly(tuple("Magic Missile", 1, "class:Wizard", false),
                            tuple("Misty Step", 2, "class:Wizard", false));
        }

        @Test
        @DisplayName("A cantrip, a spell above the wizard's level or off its list, or a character with no wizard levels is a 400")
        void refusesWhatCantBeCopied() {
            PlayerCharacter pc = wizard();

            assertThatThrownBy(() -> f.spells.addToSpellbook(pc.getId(), new SpellbookRequest(List.of("Fire Bolt")),
                    f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Cantrips aren't kept in a spellbook: Fire Bolt is a cantrip");
            assertThatThrownBy(() -> f.spells.addToSpellbook(pc.getId(), new SpellbookRequest(List.of("Fireball")),
                    f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("A level 3 Wizard casts spells up to level 2, so can't take Fireball (level 3)");
            assertThatThrownBy(() -> f.spells.addToSpellbook(pc.getId(), new SpellbookRequest(List.of("Bless")),
                    f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Bless isn't on the Wizard spell list");
            assertThatThrownBy(() -> f.spells.addToSpellbook(cleric().getId(),
                    new SpellbookRequest(List.of("Magic Missile")), f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Aria has no wizard levels, so has no spellbook");
            assertThat(pc.getSpellsKnown()).isNull();
        }

        @Test
        @DisplayName("Spells are taken out of the spellbook, and one that isn't in it is a 400")
        void takesSpellsOut() {
            PlayerCharacter pc = wizard();
            f.spells.addToSpellbook(pc.getId(), new SpellbookRequest(List.of("Magic Missile", "Shield")), f.userId);

            CharacterResponse response = f.spells.removeFromSpellbook(pc.getId(),
                    new SpellbookRequest(List.of("Shield")), f.userId);

            assertThat(names(response, "class:Wizard", false)).containsExactly("Magic Missile");
            assertThatThrownBy(() -> f.spells.removeFromSpellbook(pc.getId(),
                    new SpellbookRequest(List.of("Shield")), f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Shield isn't in Aria's spellbook");
        }
    }
}
