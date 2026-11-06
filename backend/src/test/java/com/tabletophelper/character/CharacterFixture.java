package com.tabletophelper.character;

import com.tabletophelper.shared.JacksonDefaults;
import tools.jackson.databind.ObjectMapper;
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
import com.tabletophelper.character.state.PendingChoice;
import com.tabletophelper.identity.User;
import com.tabletophelper.identity.UserService;
import com.tabletophelper.reference.BackgroundRepository;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.CharacterClassRepository;
import com.tabletophelper.reference.ClassFeaturePool;
import com.tabletophelper.reference.ClassFeaturePoolRepository;
import com.tabletophelper.reference.FeatRepository;
import com.tabletophelper.reference.ItemRepository;
import com.tabletophelper.reference.OptionalFeatureRepository;
import com.tabletophelper.reference.Race;
import com.tabletophelper.reference.RaceRepository;
import com.tabletophelper.reference.ResourcePoolDefinition;
import com.tabletophelper.reference.SpellRepository;
import com.tabletophelper.reference.Subclass;
import com.tabletophelper.reference.SubclassRepository;
import com.tabletophelper.resourcepool.ResourcePoolService;
import com.tabletophelper.rules.Rng;
import com.tabletophelper.rules.character.ClassTables;
import com.tabletophelper.rules.character.TestClassTables;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * The character services wired as in the app, with CharacterStats, CharacterMapper, CharacterCommands,
 * FeatEffectResolver, PendingChoiceService, WriteBackService and ResourcePoolService real, over mocked repositories, a
 * mocked LiveEncounters (no character is in an encounter until a test says so) and dice a test scripts. Saved
 * characters are kept in memory, so a test creates a character through the service and levels it up and down, rests
 * it, makes its Pending Choices, writes an encounter's results back to it and changes its sheet by its ID; the row lock
 * is the stored character itself. The classes carry the
 * class data the rules read: hit dice, spellcasting, the features of the levels the tests take, and multiclassing
 * prerequisites and proficiencies as the 5eTools data has them. A Fighter gains Action Surge's pool at 2nd level, and
 * chooses between the Champion and the Eldritch Knight at 3rd; a Wizard has the School of Evocation at 2nd. No other
 * class has a subclass to choose, so their levels owe no SUBCLASS choice.
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
    final FeatRepository featRepository = mock(FeatRepository.class);
    final SpellRepository spellRepository = mock(SpellRepository.class);
    final ItemRepository itemRepository = mock(ItemRepository.class);
    final ClassFeaturePoolRepository featurePoolRepository = mock(ClassFeaturePoolRepository.class);
    final OptionalFeatureRepository optionalFeatureRepository = mock(OptionalFeatureRepository.class);
    final LiveEncounters liveEncounters = mock(LiveEncounters.class);

    // The rolls the server's dice will make, in order; running out fails the test
    final Deque<Integer> rolls = new ArrayDeque<>();
    final Rng rng = sides -> {
        Integer roll = rolls.poll();
        if (roll == null) {
            throw new IllegalStateException("No scripted roll left for a d" + sides);
        }
        return roll;
    };

    final ObjectMapper objectMapper = JacksonDefaults.mapper();
    final ClassTables classTables = TestClassTables.load();
    final AccessPolicy accessPolicy = new AccessPolicy(campaignRepository, campaignMemberRepository);
    final ResourcePoolService resourcePoolService =
            new ResourcePoolService(featurePoolRepository, JacksonDefaults.mapper());
    final CharacterStats characterStats = new CharacterStats(classRepository, subclassRepository,
            resourcePoolService, objectMapper, classTables);
    final CharacterMapper mapper = new CharacterMapper(objectMapper, subclassRepository, characterStats, liveEncounters);
    final CharacterCommands commands = new CharacterCommands(characterRepository, accessPolicy, liveEncounters,
            characterStats, mapper);
    final InventoryChecks inventoryChecks = new InventoryChecks(itemRepository);
    final FeatEffectResolver featEffectResolver = new FeatEffectResolver(spellRepository);
    final FeatEligibility featEligibility = new FeatEligibility(characterStats, featRepository,
            characterRepository, accessPolicy);
    final CharacterSpellService spells = new CharacterSpellService(commands, characterStats, spellRepository,
            objectMapper);
    final PendingChoiceService choices = new PendingChoiceService(commands, characterStats, spells,
            featEffectResolver, featEligibility, classRepository, subclassRepository, featRepository, optionalFeatureRepository,
            objectMapper, classTables);
    final CharacterService service = new CharacterService(characterRepository, userService, campaignRepository,
            raceRepository, classRepository, subclassRepository, backgroundRepository, mapper, objectMapper,
            accessPolicy, characterStats, commands, liveEncounters, inventoryChecks, choices, classTables);
    final CharacterSheetService sheet = new CharacterSheetService(commands, campaignRepository, accessPolicy,
            inventoryChecks);
    final RestService rests = new RestService(commands, resourcePoolService, rng);
    final WriteBackService writeBack = new WriteBackService(commands, characterRepository);

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
    final CharacterClass sorcerer = CharacterClass.builder().id(UUID.randomUUID()).name("Sorcerer").hitDice(6)
            .subclassLevel(1).isSpellcaster(true).isPactMagic(false).spellcastingAbility("CHA")
            .multiclassRequirements("[{\"ability\":\"CHA\",\"minimum\":13}]")
            .build();

    final Subclass champion = Subclass.builder().id(UUID.randomUUID()).name("Champion").characterClass(fighter)
            .features("[{\"level\":3,\"name\":\"Improved Critical\",\"description\":\"Crit on a 19\"},"
                    + "{\"level\":7,\"name\":\"Remarkable Athlete\",\"description\":\"Half proficiency\"},"
                    + "{\"level\":10,\"name\":\"Additional Fighting Style\",\"description\":\"Another style\"}]")
            .build();
    final Subclass eldritchKnight = Subclass.builder().id(UUID.randomUUID()).name("Eldritch Knight")
            .characterClass(fighter)
            .features("[{\"level\":3,\"name\":\"Spellcasting\",\"description\":\"Wizard spells\"},"
                    + "{\"level\":3,\"name\":\"Weapon Bond\",\"description\":\"Bonded weapons\"}]")
            .build();
    final Subclass evocation = Subclass.builder().id(UUID.randomUUID()).name("School of Evocation")
            .characterClass(wizard)
            .features("[{\"level\":2,\"name\":\"Sculpt Spells\",\"description\":\"Spare your allies\"}]")
            .build();

    final Race human = Race.builder().id(UUID.randomUUID()).name("Human").speed("{\"walk\": 30}").build();
    final Race hillDwarf = Race.builder().id(UUID.randomUUID()).name("Hill Dwarf").speed("{\"walk\": 25}")
            .effects("{\"hpPerLevel\": 1}").build();

    final ResourcePoolDefinition actionSurge = ResourcePoolDefinition.builder().poolId("class:fighter-action-surge")
            .displayName("Action Surge").sourceType("CLASS").maxUsesFormula("1").resetOn("shortRest").build();

    private final Map<UUID, PlayerCharacter> saved = new HashMap<>();

    CharacterFixture() {
        lenient().when(userService.getUser(userId)).thenReturn(user);
        for (Race race : List.of(human, hillDwarf)) {
            lenient().when(raceRepository.findById(race.getId())).thenReturn(Optional.of(race));
        }
        List<CharacterClass> classes = List.of(fighter, wizard, paladin, rogue, barbarian, cleric, warlock,
                sorcerer);
        for (CharacterClass c : classes) {
            lenient().when(classRepository.findById(c.getId())).thenReturn(Optional.of(c));
        }
        lenient().when(classRepository.findAll()).thenReturn(classes);
        lenient().when(subclassRepository.findByCharacterClassIdOrderByNameAsc(fighter.getId()))
                .thenReturn(List.of(champion, eldritchKnight));
        lenient().when(subclassRepository.findByCharacterClassIdOrderByNameAsc(wizard.getId()))
                .thenReturn(List.of(evocation));
        for (Subclass subclass : List.of(champion, eldritchKnight, evocation)) {
            lenient().when(subclassRepository.findById(subclass.getId())).thenReturn(Optional.of(subclass));
        }
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
        lenient().when(characterRepository.lockById(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(saved.get(inv.<UUID>getArgument(0))));
        lenient().when(characterRepository.findOwnerIdById(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(saved.get(inv.<UUID>getArgument(0)))
                        .map(pc -> pc.getUser().getId()));
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

    /** The character's latest Pending Choice of the type, the one the newest level owes; null for none. */
    PendingChoice pending(PlayerCharacter pc, PendingChoice.Type type) {
        List<PendingChoice> pending = stored(pc.getId()).getPendingChoices();
        PendingChoice latest = null;
        for (PendingChoice choice : pending != null ? pending : List.<PendingChoice>of()) {
            if (choice.type() == type) {
                latest = choice;
            }
        }
        return latest;
    }

    /** Makes a Pending Choice with the answer a test fills in. */
    CharacterResponse choose(PlayerCharacter pc, PendingChoice choice, Consumer<ApplyChoicesRequest> answer) {
        ApplyChoicesRequest request = new ApplyChoicesRequest();
        request.setChoiceId(choice != null ? choice.id() : UUID.randomUUID());
        answer.accept(request);
        return choices.apply(pc.getId(), request, userId);
    }

    /**
     * An Ability Score Improvement of these abilities and bonuses, given in pairs, for the latest level that owes one,
     * or for a choice the character doesn't have when none does.
     */
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
        return choose(pc, pending(pc, PendingChoice.Type.ASI), request -> request.setAsi(asi));
    }

    /** A feat taken for the latest level that owes an Ability Score Improvement. */
    CharacterResponse takeFeat(PlayerCharacter pc, UUID featId) {
        ApplyChoicesRequest.AsiChoice asi = new ApplyChoicesRequest.AsiChoice();
        asi.setType("feat");
        asi.setFeatId(featId);
        return choose(pc, pending(pc, PendingChoice.Type.ASI), request -> request.setAsi(asi));
    }
}
