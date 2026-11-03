package com.tabletophelper.character;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.campaign.AccessPolicy;
import com.tabletophelper.campaign.CampaignMemberRepository;
import com.tabletophelper.campaign.CampaignRepository;
import com.tabletophelper.character.dto.ApplyChoicesRequest;
import com.tabletophelper.character.dto.CharacterCreateRequest;
import com.tabletophelper.character.dto.CharacterResponse;
import com.tabletophelper.character.dto.LevelUpRequest;
import com.tabletophelper.character.dto.LevelUpResponse;
import com.tabletophelper.character.internal.CharacterStats;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.encounter.EncounterParticipantRepository;
import com.tabletophelper.identity.User;
import com.tabletophelper.identity.UserService;
import com.tabletophelper.reference.BackgroundRepository;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.CharacterClassRepository;
import com.tabletophelper.reference.ClassFeaturePool;
import com.tabletophelper.reference.ClassFeaturePoolRepository;
import com.tabletophelper.reference.FeatRepository;
import com.tabletophelper.reference.Race;
import com.tabletophelper.reference.RaceRepository;
import com.tabletophelper.reference.ResourcePoolDefinition;
import com.tabletophelper.reference.SpellRepository;
import com.tabletophelper.reference.SubclassRepository;
import com.tabletophelper.resourcepool.ResourcePoolService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * CharacterService wired as in the app, with CharacterStats, CharacterMapper, FeatEffectResolver and
 * ResourcePoolService real, over mocked repositories. Saved characters are kept in memory, so a test creates a
 * character through the service and levels it up and down by its ID. The classes carry the class data the rules
 * read: hit dice, spellcasting, the features of the levels the tests take, and multiclassing prerequisites and
 * proficiencies as the 5eTools data has them. A Fighter gains Action Surge's pool at 2nd level.
 */
class CharacterFixture {

    final CharacterRepository characterRepository = mock(CharacterRepository.class);
    final UserService userService = mock(UserService.class);
    final CampaignRepository campaignRepository = mock(CampaignRepository.class);
    final CampaignMemberRepository campaignMemberRepository = mock(CampaignMemberRepository.class);
    final RaceRepository raceRepository = mock(RaceRepository.class);
    final CharacterClassRepository classRepository = mock(CharacterClassRepository.class);
    final SubclassRepository subclassRepository = mock(SubclassRepository.class);
    final BackgroundRepository backgroundRepository = mock(BackgroundRepository.class);
    final EncounterParticipantRepository participantRepository = mock(EncounterParticipantRepository.class);
    final FeatRepository featRepository = mock(FeatRepository.class);
    final SpellRepository spellRepository = mock(SpellRepository.class);
    final ClassFeaturePoolRepository featurePoolRepository = mock(ClassFeaturePoolRepository.class);

    final ObjectMapper objectMapper = new ObjectMapper();
    final ResourcePoolService resourcePoolService = new ResourcePoolService(featurePoolRepository);
    final CharacterStats characterStats = new CharacterStats(classRepository, resourcePoolService, objectMapper);
    final CharacterMapper mapper = new CharacterMapper(objectMapper, subclassRepository, characterStats);
    final FeatEffectResolver featEffectResolver = new FeatEffectResolver(objectMapper, spellRepository);
    final CharacterService service = new CharacterService(characterRepository, userService, campaignRepository,
            raceRepository, classRepository, subclassRepository, backgroundRepository, participantRepository,
            featRepository, featEffectResolver, mapper, objectMapper, resourcePoolService,
            new AccessPolicy(campaignRepository, campaignMemberRepository), characterStats);

    final UUID userId = UUID.randomUUID();
    final User user = User.builder().id(userId).username("aria").email("aria@test.com").passwordHash("hash")
            .displayName("Aria").build();

    final CharacterClass fighter = CharacterClass.builder().id(UUID.randomUUID()).name("Fighter").hitDice(10)
            .subclassLevel(3).isSpellcaster(false).isPactMagic(false)
            .features("[{\"level\":1,\"name\":\"Fighting Style\",\"description\":\"Choose a style\"},"
                    + "{\"level\":1,\"name\":\"Second Wind\",\"description\":\"Heal on a bonus action\"},"
                    + "{\"level\":2,\"name\":\"Action Surge\",\"description\":\"Take an extra action\"},"
                    + "{\"level\":4,\"name\":\"Ability Score Improvement\",\"description\":\"Improve\"},"
                    + "{\"level\":6,\"name\":\"Ability Score Improvement\",\"description\":\"Improve\"}]")
            .multiclassRequirements("[{\"ability\":\"STR\",\"minimum\":13,\"operator\":\"OR\"},"
                    + "{\"ability\":\"DEX\",\"minimum\":13,\"operator\":\"OR\"}]")
            .multiclassProficiencies("{\"armor\":[\"Light\",\"Medium\",\"Shield\"],\"weapons\":[\"Simple\",\"Martial\"]}")
            .build();
    final CharacterClass wizard = CharacterClass.builder().id(UUID.randomUUID()).name("Wizard").hitDice(6)
            .subclassLevel(2).isSpellcaster(true).isPactMagic(false).spellcastingAbility("INT")
            .features("[{\"level\":1,\"name\":\"Arcane Recovery\",\"description\":\"Recover spell slots\"},"
                    + "{\"level\":2,\"name\":\"Arcane Tradition\",\"description\":\"Choose a tradition\"}]")
            .multiclassRequirements("[{\"ability\":\"INT\",\"minimum\":13}]")
            .build();
    final CharacterClass paladin = CharacterClass.builder().id(UUID.randomUUID()).name("Paladin").hitDice(10)
            .subclassLevel(3).isSpellcaster(true).isPactMagic(false).spellcastingAbility("CHA")
            .features("[{\"level\":1,\"name\":\"Divine Sense\",\"description\":\"Sense celestials\"},"
                    + "{\"level\":2,\"name\":\"Divine Smite\",\"description\":\"Smite on a hit\"}]")
            .multiclassRequirements("[{\"ability\":\"STR\",\"minimum\":13},{\"ability\":\"CHA\",\"minimum\":13}]")
            .multiclassProficiencies("{\"armor\":[\"Light\",\"Medium\",\"Shield\"],\"weapons\":[\"Simple\",\"Martial\"]}")
            .build();
    final CharacterClass rogue = CharacterClass.builder().id(UUID.randomUUID()).name("Rogue").hitDice(8)
            .subclassLevel(3).isSpellcaster(false).isPactMagic(false)
            .features("[{\"level\":1,\"name\":\"Sneak Attack\",\"description\":\"Extra damage\"}]")
            .multiclassRequirements("[{\"ability\":\"DEX\",\"minimum\":13}]")
            .multiclassProficiencies("{\"armor\":[\"Light\"],\"tools\":[\"thieves' tools\"],"
                    + "\"skills\":{\"from\":[\"Stealth\"],\"count\":1}}")
            .build();
    final CharacterClass barbarian = CharacterClass.builder().id(UUID.randomUUID()).name("Barbarian").hitDice(12)
            .subclassLevel(3).isSpellcaster(false).isPactMagic(false)
            .features("[{\"level\":20,\"name\":\"Primal Champion\",\"description\":\"STR and CON up by 4\"}]")
            .multiclassRequirements("[{\"ability\":\"STR\",\"minimum\":13}]")
            .build();
    final CharacterClass cleric = CharacterClass.builder().id(UUID.randomUUID()).name("Cleric").hitDice(8)
            .subclassLevel(1).isSpellcaster(true).isPactMagic(false).spellcastingAbility("WIS")
            .multiclassRequirements("[{\"ability\":\"WIS\",\"minimum\":13}]")
            .build();
    final CharacterClass warlock = CharacterClass.builder().id(UUID.randomUUID()).name("Warlock").hitDice(8)
            .subclassLevel(1).isSpellcaster(true).isPactMagic(true).spellcastingAbility("CHA")
            .multiclassRequirements("[{\"ability\":\"CHA\",\"minimum\":13}]")
            .build();

    final Race human = Race.builder().id(UUID.randomUUID()).name("Human").speed("{\"walk\": 30}").build();
    final Race hillDwarf = Race.builder().id(UUID.randomUUID()).name("Hill Dwarf").speed("{\"walk\": 25}").build();

    final ResourcePoolDefinition actionSurge = ResourcePoolDefinition.builder().poolId("class:fighter-action-surge")
            .displayName("Action Surge").sourceType("CLASS").maxUsesFormula("1").resetOn("shortRest").build();

    private final Map<UUID, PlayerCharacter> saved = new HashMap<>();

    CharacterFixture() {
        lenient().when(userService.getUser(userId)).thenReturn(user);
        for (Race race : List.of(human, hillDwarf)) {
            lenient().when(raceRepository.findById(race.getId())).thenReturn(Optional.of(race));
        }
        List<CharacterClass> classes = List.of(fighter, wizard, paladin, rogue, barbarian, cleric, warlock);
        for (CharacterClass c : classes) {
            lenient().when(classRepository.findById(c.getId())).thenReturn(Optional.of(c));
        }
        lenient().when(classRepository.findAll()).thenReturn(classes);
        lenient().when(characterRepository.save(any(PlayerCharacter.class))).thenAnswer(inv -> {
            PlayerCharacter pc = inv.getArgument(0);
            if (pc.getId() == null) {
                pc.setId(UUID.randomUUID());
            }
            saved.put(pc.getId(), pc);
            return pc;
        });
        lenient().when(characterRepository.findById(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(saved.get(inv.<UUID>getArgument(0))));
        ClassFeaturePool surge = ClassFeaturePool.builder().characterClass(fighter).minLevel(2)
                .poolDefinition(actionSurge).build();
        lenient().when(featurePoolRepository.findByCharacterClassIdAndSubclassIsNullAndMinLevelLessThanEqual(
                any(UUID.class), anyInt())).thenAnswer(inv -> fighter.getId().equals(inv.getArgument(0))
                && inv.<Integer>getArgument(1) >= 2 ? List.of(surge) : List.of());
    }

    /** The stored character with the ID. */
    PlayerCharacter stored(UUID id) {
        return saved.get(id);
    }

    /**
     * Creates a character with these scores in these classes, given as pairs of a class and its levels in the order
     * taken, and returns it as stored.
     */
    PlayerCharacter create(Race race, int[] scores, Object... classesAndLevels) {
        CharacterCreateRequest request = new CharacterCreateRequest();
        request.setName("Aria");
        request.setRaceId(race.getId());
        request.setStrength(scores[0]);
        request.setDexterity(scores[1]);
        request.setConstitution(scores[2]);
        request.setIntelligence(scores[3]);
        request.setWisdom(scores[4]);
        request.setCharisma(scores[5]);
        List<ClassEntry> entries = new ArrayList<>();
        int level = 0;
        for (int i = 0; i < classesAndLevels.length; i += 2) {
            CharacterClass c = (CharacterClass) classesAndLevels[i];
            int classLevel = (Integer) classesAndLevels[i + 1];
            entries.add(new ClassEntry(c.getId(), null, null, null, classLevel));
            level += classLevel;
        }
        request.setClassId(entries.getFirst().classId());
        request.setLevel(level);
        if (entries.size() > 1) {
            request.setMulticlassClassEntries(entries);
        }
        CharacterResponse created = service.createCharacter(request, userId);
        return stored(created.getId());
    }

    /** STR 16, DEX 14, CON 12, INT 16, WIS 10, CHA 13. */
    static int[] scores() {
        return new int[]{16, 14, 12, 16, 10, 13};
    }

    LevelUpResponse levelUp(PlayerCharacter pc, CharacterClass levelClass) {
        LevelUpRequest request = new LevelUpRequest();
        request.setClassId(levelClass.getId());
        return service.levelUp(pc.getId(), request, userId);
    }

    CharacterResponse levelDown(PlayerCharacter pc) {
        return service.levelDown(pc.getId(), userId);
    }

    /** An Ability Score Improvement of these abilities and bonuses, given in pairs. */
    CharacterResponse improve(PlayerCharacter pc, Object... abilitiesAndBonuses) {
        ApplyChoicesRequest.AsiChoice asi = new ApplyChoicesRequest.AsiChoice();
        asi.setType("ability");
        List<ApplyChoicesRequest.AbilityIncrease> increases = new ArrayList<>();
        for (int i = 0; i < abilitiesAndBonuses.length; i += 2) {
            ApplyChoicesRequest.AbilityIncrease increase = new ApplyChoicesRequest.AbilityIncrease();
            increase.setAbility((String) abilitiesAndBonuses[i]);
            increase.setBonus((Integer) abilitiesAndBonuses[i + 1]);
            increases.add(increase);
        }
        asi.setIncreases(increases);
        ApplyChoicesRequest request = new ApplyChoicesRequest();
        request.setAsi(asi);
        return service.applyChoices(pc.getId(), request, userId);
    }
}
