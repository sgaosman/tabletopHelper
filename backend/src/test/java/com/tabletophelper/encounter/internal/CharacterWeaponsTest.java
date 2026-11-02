package com.tabletophelper.encounter.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.reference.ItemRepository;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.combat.WeaponRules;
import com.tabletophelper.rules.combat.WeaponRules.PowerAttack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.tabletophelper.encounter.internal.CombatFixture.weapon;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A player character's weapons from their sheet (D164). The item rows, proficiencies and features are spelt as the
 * seeders and the character creation wizard write them: class proficiencies such as "simple" and "hand crossbow",
 * multiclass ones such as "Martial", race ones such as "Longsword", and feats listed among the features with the
 * source "Feat" or the race or background that granted them.
 */
class CharacterWeaponsTest {

    private final ItemRepository itemRepository = mock(ItemRepository.class);
    private final CharacterWeapons weapons = new CharacterWeapons(itemRepository, new ObjectMapper());
    private PlayerCharacter character;

    @BeforeEach
    void setUp() {
        when(itemRepository.findMundaneWeapons()).thenReturn(List.of(
                weapon("Longsword", "Melee Weapon", "martial", "1d8", "slashing", "V"),
                weapon("Greatsword", "Melee Weapon", "martial", "2d6", "slashing", "H", "2H"),
                weapon("Dagger", "Melee Weapon", "simple", "1d4", "piercing", "F", "L", "T"),
                weapon("Hand Crossbow", "Ranged Weapon", "martial", "1d6", "piercing", "A", "L", "LD"),
                weapon("Musket", "Ranged Weapon", "martial", "1d12", "piercing", "AF|DMG", "LD", "2H")));
        character = PlayerCharacter.builder().name("Aria").level(5).strength(16).dexterity(14).proficiencyBonus(3)
                .build();
    }

    private List<String> names(List<CharacterWeapons.Held> held) {
        return held.stream().map(h -> h.weapon().name()).toList();
    }

    @Test
    @DisplayName("Inventory entries match a weapon by name in any case or plural, once each in order, then the unarmed strike")
    void inventoryEntries() {
        character.setEquipment("""
                [{"name": "longsword", "quantity": 1, "description": "Family heirloom"},
                 {"name": "Daggers", "quantity": 4}, {"name": "Dagger"}, {"name": "Flame Tongue Longsword"},
                 {"name": "Hand Crossbow", "quantity": 0}, {"name": "Rope, hempen (50 feet)"}, "Greatsword"]""");

        List<CharacterWeapons.Held> held = weapons.of(character);

        assertEquals(List.of("Longsword", "Dagger", "Greatsword", "Unarmed Strike"), names(held));
        WeaponRules.Weapon dagger = held.get(1).weapon();
        assertEquals(new WeaponRules.Weapon("Dagger", WeaponRules.Category.SIMPLE, false, true, false, Dice.parse("1d4"),
                "piercing"), dagger);
        assertTrue(held.getLast().proficient());
    }

    @Test
    @DisplayName("A sheet with no equipment has only its unarmed strike")
    void noEquipment() {
        assertEquals(List.of("Unarmed Strike"), names(weapons.of(character)));
        assertTrue(weapons.find(character, "unarmed strike").isPresent());
        assertTrue(weapons.find(character, "Longsword").isEmpty());
    }

    @Test
    @DisplayName("Proficiency comes from a category, a weapon's name or plural, or firearms, however the sheet spells it")
    void proficiencySpellings() {
        WeaponRules.Weapon longsword = held("Longsword").weapon();
        WeaponRules.Weapon dagger = held("Dagger").weapon();
        WeaponRules.Weapon handCrossbow = held("Hand Crossbow").weapon();

        assertTrue(CharacterWeapons.proficient(List.of("simple", "martial"), longsword, false));
        assertTrue(CharacterWeapons.proficient(List.of("Martial"), longsword, false));
        assertTrue(CharacterWeapons.proficient(List.of("Martial Weapons"), longsword, false));
        assertTrue(CharacterWeapons.proficient(List.of("martial weapon"), longsword, false));
        assertFalse(CharacterWeapons.proficient(List.of("simple"), longsword, false));
        assertTrue(CharacterWeapons.proficient(List.of("Simple Weapons"), dagger, false));
        assertTrue(CharacterWeapons.proficient(List.of("simple", "hand crossbow", "longsword"), handCrossbow, false));
        assertTrue(CharacterWeapons.proficient(List.of("Longsword"), longsword, false));
        assertTrue(CharacterWeapons.proficient(List.of("longswords"), longsword, false));
        assertTrue(CharacterWeapons.proficient(List.of("{@item longsword|phb|longswords}"), longsword, false));
        assertTrue(CharacterWeapons.proficient(List.of("longsword|phb"), longsword, false));
        assertFalse(CharacterWeapons.proficient(List.of("Firearms"), handCrossbow, false));
        assertTrue(CharacterWeapons.proficient(List.of("Firearms"), held("Musket").weapon(), true));
        assertFalse(CharacterWeapons.proficient(List.of(), longsword, false));
    }

    @Test
    @DisplayName("The sheet's proficiencies decide each weapon's; the Musket counts as a firearm")
    void proficiencyFromTheSheet() {
        character.setEquipment("[{\"name\": \"Dagger\"}, {\"name\": \"Longsword\"}, {\"name\": \"Musket\"}]");
        character.setWeaponProficiencies("[\"dagger\", \"dart\", \"sling\", \"quarterstaff\", \"light crossbow\", \"Firearms\"]");

        List<CharacterWeapons.Held> held = weapons.of(character);

        assertEquals(List.of(true, false, true, true), held.stream().map(CharacterWeapons.Held::proficient).toList());
    }

    @Test
    @DisplayName("A feat counts from the features whatever granted it, or from the level history; a note in brackets doesn't matter")
    void feats() {
        character.setFeatures("""
                [{"name": "Second Wind", "source": "Fighter", "description": "..."},
                 {"name": "Sharpshooter (feat)", "source": "Variant Human", "description": "Racial feat"}]""");
        assertEquals(Set.of(PowerAttack.SHARPSHOOTER), weapons.feats(character));

        character.setFeatures("[{\"name\": \"Great Weapon Master\", \"description\": \"Feat\", \"source\": \"Feat\"}]");
        assertEquals(Set.of(PowerAttack.GREAT_WEAPON_MASTER), weapons.feats(character));

        character.setFeatures(null);
        character.setLevelHistory("""
                [{"characterLevel": 4, "className": "Fighter", "classLevel": 4,
                  "choices": {"asi": {"type": "feat", "featName": "Great Weapon Master", "featId": "f-1"}}}]""");
        assertEquals(Set.of(PowerAttack.GREAT_WEAPON_MASTER), weapons.feats(character));
    }

    @Test
    @DisplayName("The trade is offered with a qualifying weapon only when the character has the feat and is proficient")
    void powerAttackPerWeapon() {
        character.setEquipment("[\"Greatsword\", \"Longsword\", \"Hand Crossbow\"]");
        character.setWeaponProficiencies("[\"simple\", \"martial\"]");
        character.setFeatures("[{\"name\": \"Great Weapon Master\", \"source\": \"Feat\"}]");

        List<CharacterWeapons.Held> held = weapons.of(character);

        assertEquals(PowerAttack.GREAT_WEAPON_MASTER, held.get(0).powerAttack());
        assertNull(held.get(1).powerAttack());
        // A ranged weapon allows Sharpshooter, which the character lacks
        assertNull(held.get(2).powerAttack());

        character.setWeaponProficiencies("[\"simple\"]");
        assertNull(weapons.of(character).getFirst().powerAttack());
    }

    @Test
    @DisplayName("The numbers use the sheet's STR, DEX and proficiency bonus, or the level's bonus when the sheet has none")
    void numbers() {
        character.setEquipment("[\"Dagger\"]");
        character.setWeaponProficiencies("[\"simple\"]");
        CharacterWeapons.Held dagger = weapons.of(character).getFirst();

        assertEquals(new WeaponRules.Numbers(com.tabletophelper.rules.Ability.STR, 6, Dice.parse("1d4+3")),
                weapons.numbers(character, dagger, false));
        character.setProficiencyBonus(null);
        character.setLevel(9);
        assertEquals(7, weapons.numbers(character, dagger, false).attackBonus());
    }

    // The weapon of that name as the sheet holds it, with no proficiencies
    private CharacterWeapons.Held held(String name) {
        character.setEquipment("[\"" + name + "\"]");
        return weapons.of(character).getFirst();
    }
}
