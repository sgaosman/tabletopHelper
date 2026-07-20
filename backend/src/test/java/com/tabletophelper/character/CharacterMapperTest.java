package com.tabletophelper.character;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.character.dto.CharacterResponse;
import com.tabletophelper.reference.Subclass;
import com.tabletophelper.reference.SubclassRepository;
import com.tabletophelper.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CharacterMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private SubclassRepository subclassRepository;

    private CharacterMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new CharacterMapper(objectMapper, subclassRepository);
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
                .hitDiceMap(null)
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

    // --- 8.2 toResponse: parses JSONB strings into response fields ---

    @Test
    @DisplayName("8.2 toResponse passes through JSONB string fields")
    void toResponseJsonbStringFields() {
        User user = testUser();
        PlayerCharacter c = baseCharacter(user);
        c.setMulticlassEntries("[{\"classId\":\"abc\",\"className\":\"Fighter\",\"level\":3}]");
        c.setLevelHistory("[{\"characterLevel\":1,\"className\":\"Fighter\"}]");
        c.setHitDiceMap("{\"Fighter\":{\"total\":5,\"remaining\":5,\"faces\":10}}");
        c.setFeatures("[{\"name\":\"Second Wind\",\"source\":\"Fighter\"}]");
        c.setSpellsKnown("[{\"name\":\"Shield\",\"level\":1}]");

        CharacterResponse response = mapper.toResponse(c);

        assertEquals(c.getMulticlassEntries(), response.getMulticlassEntries());
        assertEquals(c.getLevelHistory(), response.getLevelHistory());
        assertEquals(c.getHitDiceMap(), response.getHitDiceMap());
        assertEquals(c.getFeatures(), response.getFeatures());
        assertEquals(c.getSpellsKnown(), response.getSpellsKnown());
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
        c.setHitDiceMap(null);
        c.setFeatResources(null);

        CharacterResponse response = assertDoesNotThrow(() -> mapper.toResponse(c));

        assertNull(response.getFeatures());
        assertNull(response.getSpellsKnown());
        assertNull(response.getMulticlassEntries());
        assertNull(response.getLevelHistory());
        assertNull(response.getHitDiceMap());
        assertNull(response.getFeatResources());
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

        assertNotNull(response.getSubclassAlwaysPreparedSpells());
        assertTrue(response.getSubclassAlwaysPreparedSpells().contains("Life Domain"));
        assertTrue(response.getSubclassAlwaysPreparedSpells().contains("Beacon of Hope"));
        assertTrue(response.getSubclassAlwaysPreparedSpells().contains("Revivify"));
    }
}
