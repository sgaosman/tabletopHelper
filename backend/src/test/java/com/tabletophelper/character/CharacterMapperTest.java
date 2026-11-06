package com.tabletophelper.character;

import com.tabletophelper.shared.JacksonDefaults;
import tools.jackson.databind.ObjectMapper;
import com.tabletophelper.character.dto.AbilityScoreMaximums;
import com.tabletophelper.character.dto.BaseAbilityScores;
import com.tabletophelper.character.dto.CharacterResponse;
import com.tabletophelper.character.dto.ClassSpellcasting;
import com.tabletophelper.character.internal.CharacterStats;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.Currency;
import com.tabletophelper.character.state.DmAdjustment;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.character.state.HitDicePool;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.LevelChoices;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.identity.User;
import com.tabletophelper.reference.Subclass;
import com.tabletophelper.reference.SubclassRepository;
import com.tabletophelper.rules.combat.DamageRules.Defence;
import com.tabletophelper.shared.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CharacterMapperTest {

    private final ObjectMapper objectMapper = JacksonDefaults.mapper();

    @Mock
    private SubclassRepository subclassRepository;

    @Mock
    private CharacterStats characterStats;

    @Mock
    private LiveEncounters liveEncounters;

    private CharacterMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new CharacterMapper(objectMapper, subclassRepository, characterStats, liveEncounters);
    }

    private User testUser() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setDisplayName("TestUser");
        return user;
    }

    private PlayerCharacter baseCharacter(User user) {
        return PlayerCharacter.builder()
                .id(UUID.randomUUID())
                .user(user)
                .name("Thorin")
                .level(5)
                .experiencePoints(6500)
                .race("Dwarf")
                .characterClass("Fighter")
                .subclass("Champion")
                .alignment("Lawful Good")
                .background("Soldier")
                .strength(16)
                .dexterity(12)
                .constitution(14)
                .intelligence(10)
                .wisdom(13)
                .charisma(8)
                .hpMax(45)
                .hpCurrent(45)
                .hpTemp(0)
                .armourClass(18)
                .initiativeBonus(1)
                .speed(25)
                .proficiencyBonus(3)
                .features(null)
                .spellsKnown(null)
                .multiclassEntries(null)
                .levelHistory(null)
                .hitDice(null)
                .build();
    }

    // --- 8.1 toResponse: maps all scalar fields ---

    @Test
    @DisplayName("8.1 toResponse maps all scalar fields correctly")
    void toResponseMapsScalarFields() {
        User user = testUser();
        PlayerCharacter c = baseCharacter(user);

        CharacterResponse response = mapper.toResponse(c);

        assertEquals(c.getId(), response.getId());
        assertEquals(user.getId(), response.getUserId());
        assertEquals("TestUser", response.getOwnerDisplayName());
        assertEquals("Thorin", response.getName());
        assertEquals(5, response.getLevel());
        assertEquals(6500, response.getExperiencePoints());
        assertEquals(16, response.getStrength());
        assertEquals(12, response.getDexterity());
        assertEquals(14, response.getConstitution());
        assertEquals(10, response.getIntelligence());
        assertEquals(13, response.getWisdom());
        assertEquals(8, response.getCharisma());
        assertEquals(45, response.getHpMax());
        assertEquals(45, response.getHpCurrent());
        assertEquals(0, response.getHpTemp());
        assertEquals(18, response.getArmourClass());
        assertEquals(1, response.getInitiativeBonus());
        assertEquals(25, response.getSpeed());
        assertEquals(3, response.getProficiencyBonus());
        assertEquals("Dwarf", response.getRace());
        assertEquals("Fighter", response.getCharacterClass());
        assertEquals("Champion", response.getSubclass());
        assertEquals("Lawful Good", response.getAlignment());
        assertEquals("Soldier", response.getBackground());
    }

    // --- 8.2 toResponse: passes the typed JSON fields through ---

    @Test
    @DisplayName("8.2 toResponse passes through the typed JSON fields")
    void toResponseJsonFields() {
        User user = testUser();
        PlayerCharacter c = baseCharacter(user);
        UUID fighterId = UUID.randomUUID();
        c.setMulticlassEntries(List.of(new ClassEntry(fighterId, "Fighter", null, null, 3)));
        c.setLevelHistory(List.of(new LevelHistoryEntry(1, fighterId, "Fighter", 1, 12, 10, List.of(), LevelChoices.NONE,
                null, null)));
        c.setHitDice(Map.of("Fighter", new HitDicePool(10, 5, 5)));
        c.setFeatures(List.of(new Feature("Second Wind", null, "Fighter")));
        c.setSpellsKnown(List.of(new KnownSpell(null, "Shield", 1, null, null, null, null, null, null)));
        c.setSpellSlots(Map.of("1", new SpellSlot(2, 1)));
        c.setCurrency(new Currency(1, 2, 3, 4, 5));

        CharacterResponse response = mapper.toResponse(c);

        assertEquals(c.getMulticlassEntries(), response.getMulticlassEntries());
        assertEquals(c.getLevelHistory(), response.getLevelHistory());
        assertEquals(c.getHitDice(), response.getHitDice());
        assertEquals(c.getFeatures(), response.getFeatures());
        assertEquals(c.getSpellsKnown(), response.getSpellsKnown());
        assertEquals(c.getSpellSlots(), response.getSpellSlots());
        assertEquals(c.getCurrency(), response.getCurrency());
    }

    // --- 8.3 toResponse: handles null JSONB fields gracefully ---

    @Test
    @DisplayName("8.3 toResponse handles null JSONB fields without exception")
    void toResponseNullJsonbFields() {
        User user = testUser();
        PlayerCharacter c = baseCharacter(user);
        c.setFeatures(null);
        c.setSpellsKnown(null);
        c.setMulticlassEntries(null);
        c.setLevelHistory(null);
        c.setHitDice(null);

        CharacterResponse response = assertDoesNotThrow(() -> mapper.toResponse(c));

        assertNull(response.getFeatures());
        assertNull(response.getSpellsKnown());
        assertNull(response.getMulticlassEntries());
        assertNull(response.getLevelHistory());
        assertNull(response.getHitDice());
        assertNull(response.getSubclassAlwaysPreparedSpells());
        // Non-null fields still mapped
        assertEquals("Thorin", response.getName());
        assertEquals(5, response.getLevel());
    }

    // --- 8.4 toResponse: includes subclass always-prepared spells ---

    @Test
    @DisplayName("8.4 toResponse includes subclass always-prepared spells")
    void toResponseSubclassAlwaysPreparedSpells() {
        User user = testUser();
        PlayerCharacter c = baseCharacter(user);

        UUID lifeDomainId = UUID.randomUUID();
        Subclass lifeDomain = Subclass.builder()
                .id(lifeDomainId)
                .name("Life Domain")
                .alwaysPreparedSpells("{\"3\":[\"Beacon of Hope\",\"Revivify\"],\"5\":[\"Death Ward\",\"Guardian of Faith\"]}")
                .build();
        c.setSubclassRef(lifeDomain);

        CharacterResponse response = mapper.toResponse(c);

        assertEquals(Map.of("Life Domain", Map.of(
                        "3", List.of("Beacon of Hope", "Revivify"),
                        "5", List.of("Death Ward", "Guardian of Faith"))),
                response.getSubclassAlwaysPreparedSpells());
    }

    @Test
    @DisplayName("8.5 toResponse adds each other class's subclass spells after the primary subclass's, once each")
    void toResponseMulticlassSubclassAlwaysPreparedSpells() {
        User user = testUser();
        PlayerCharacter c = baseCharacter(user);
        Subclass lifeDomain = Subclass.builder().id(UUID.randomUUID()).name("Life Domain")
                .alwaysPreparedSpells("{\"1\":[\"Bless\"]}").build();
        Subclass devotion = Subclass.builder().id(UUID.randomUUID()).name("Oath of Devotion")
                .alwaysPreparedSpells("{\"3\":[\"Sanctuary\"]}").build();
        c.setSubclassRef(lifeDomain);
        c.setMulticlassEntries(List.of(
                new ClassEntry(UUID.randomUUID(), "Cleric", lifeDomain.getId(), "Life Domain", 1),
                new ClassEntry(UUID.randomUUID(), "Paladin", devotion.getId(), "Oath of Devotion", 3)));
        when(subclassRepository.findById(devotion.getId())).thenReturn(Optional.of(devotion));

        var spells = mapper.toResponse(c).getSubclassAlwaysPreparedSpells();

        assertEquals(List.of("Life Domain", "Oath of Devotion"), List.copyOf(spells.keySet()));
        assertEquals(Map.of("3", List.of("Sanctuary")), spells.get("Oath of Devotion"));
    }

    @Test
    @DisplayName("8.6 toResponse adds what each class casts with, the main spellcasting ability and the ability score maximums, which the sheet doesn't store")
    void toResponseAddsDerivedSpellcasting() {
        PlayerCharacter c = baseCharacter(testUser());
        List<ClassSpellcasting> spellcasting = List.of(
                new ClassSpellcasting("Wizard", null, 3, "INT", 13, 5, 2, 3, null, 6, false));
        AbilityScoreMaximums maximums = new AbilityScoreMaximums(20, 20, 20, 20, 20, 20);
        when(characterStats.spellcasting(c)).thenReturn(spellcasting);
        when(characterStats.mainSpellcastingAbility(c)).thenReturn("INT");
        when(characterStats.abilityScoreMaximums(c)).thenReturn(maximums);

        CharacterResponse response = mapper.toResponse(c);

        assertEquals(spellcasting, response.getSpellcasting());
        assertEquals("INT", response.getSpellcastingAbility());
        assertEquals(maximums, response.getAbilityScoreMaximums());
    }

    @Test
    @DisplayName("8.7 A subclass whose always-prepared spells can't be read is a 409, not a sheet without them (D155)")
    void unreadableAlwaysPreparedSpellsIsConflict() {
        PlayerCharacter c = baseCharacter(testUser());
        c.setSubclassRef(Subclass.builder().id(UUID.randomUUID()).name("Life Domain")
                .alwaysPreparedSpells("{not json").build());

        ConflictException e = assertThrows(ConflictException.class, () -> mapper.toResponse(c));

        assertEquals("The always-prepared spells of the subclass Life Domain can't be read", e.getMessage());
    }

    @Test
    @DisplayName("8.8 toResponse says whether the character is in an Active or Paused encounter, so the sheet can wait to rest (D166)")
    void toResponseSaysWhetherInLiveEncounter() {
        PlayerCharacter inCombat = baseCharacter(testUser());
        PlayerCharacter resting = baseCharacter(testUser());
        when(liveEncounters.includes(inCombat.getId())).thenReturn(true);

        assertTrue(mapper.toResponse(inCombat).isInLiveEncounter());
        assertFalse(mapper.toResponse(resting).isInLiveEncounter());
    }

    @Test
    @DisplayName("8.9 toResponse shows the scores, armour class and defences with the active DM Adjustments, the scores without them, and every adjustment, the removed ones as the record (D158)")
    void toResponseIncludesDmAdjustments() {
        PlayerCharacter c = baseCharacter(testUser());
        c.setDamageResistances(List.of("poison"));
        UUID dm = UUID.randomUUID();
        Instant added = Instant.parse("2026-11-03T10:00:00Z");
        List<DmAdjustment> adjustments = List.of(
                new DmAdjustment(UUID.randomUUID(), DmAdjustment.Kind.STAT, "ARMOUR_CLASS", 1, null, null, null,
                        "Ring of Protection", dm, added, null, null),
                new DmAdjustment(UUID.randomUUID(), DmAdjustment.Kind.STAT, "STR", 5, null, null, null,
                        "Belt of Hill Giant Strength", dm, added, null, null),
                new DmAdjustment(UUID.randomUUID(), DmAdjustment.Kind.STAT, "WIS", 2, null, null, null,
                        "Periapt, lost", dm, added, dm, added),
                new DmAdjustment(UUID.randomUUID(), DmAdjustment.Kind.DEFENCE, "fire", null, Defence.RESISTANCE, null,
                        null, "Ring of Fire Resistance", dm, added, null, null),
                new DmAdjustment(UUID.randomUUID(), DmAdjustment.Kind.DEFENCE, "cold", null, Defence.VULNERABILITY,
                        null, null, "Curse of the frost giant", dm, added, null, null));
        c.setDmAdjustments(adjustments);

        CharacterResponse response = mapper.toResponse(c);

        assertEquals(19, response.getArmourClass());
        assertEquals(21, response.getStrength());
        assertEquals(13, response.getWisdom());
        assertEquals(new BaseAbilityScores(16, 12, 14, 10, 13, 8), response.getBaseAbilityScores());
        assertEquals(List.of("poison", "fire"), response.getDamageResistances());
        assertEquals(List.of(), response.getDamageImmunities());
        assertEquals(List.of("cold"), response.getDamageVulnerabilities());
        assertEquals(adjustments, response.getDmAdjustments());
    }
}
