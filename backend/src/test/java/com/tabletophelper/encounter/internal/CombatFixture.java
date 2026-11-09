package com.tabletophelper.encounter.internal;

import com.tabletophelper.shared.JacksonDefaults;
import tools.jackson.databind.ObjectMapper;
import com.tabletophelper.campaign.AccessPolicy;
import com.tabletophelper.campaign.Campaign;
import com.tabletophelper.campaign.CampaignMemberRepository;
import com.tabletophelper.campaign.CampaignRepository;
import com.tabletophelper.character.CharacterService;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.state.AbilityScoreImprovement;
import com.tabletophelper.character.state.InventoryItem;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.LevelChoices;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.CombatCommand;
import com.tabletophelper.encounter.CombatCommandRepository;
import com.tabletophelper.encounter.CombatLog;
import com.tabletophelper.encounter.CombatLogRepository;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterCommands;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterRepository;
import com.tabletophelper.encounter.EncounterService;
import com.tabletophelper.encounter.EncounterStatus;
import com.tabletophelper.encounter.ParticipantType;
import com.tabletophelper.encounter.dto.EncounterResponse;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.TurnEconomy;
import com.tabletophelper.identity.User;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.reference.ClassFeaturePoolRepository;
import com.tabletophelper.reference.Item;
import com.tabletophelper.reference.ItemRepository;
import com.tabletophelper.character.EquippedArmour;
import com.tabletophelper.reference.MonsterPoolTriggerRepository;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import com.tabletophelper.reference.SpellTemplate;
import com.tabletophelper.reference.SubclassRepository;
import com.tabletophelper.resourcepool.ResourcePoolService;
import com.tabletophelper.rules.Rng;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.rules.combat.Resolver;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * The combat services wired as in the app, over mocked repositories, an Active encounter whose DM is
 * {@link #dmUserId}, and dice the test scripts. Participants act in the order they are added, and the first
 * has the turn. Every combat log entry saved is kept in {@link #log}.
 */
class CombatFixture {

    final EncounterRepository encounterRepository = mock(EncounterRepository.class);
    final CombatLogRepository combatLogRepository = mock(CombatLogRepository.class);
    final CombatCommandRepository combatCommandRepository = mock(CombatCommandRepository.class);
    final CampaignRepository campaignRepository = mock(CampaignRepository.class);
    final CampaignMemberRepository campaignMemberRepository = mock(CampaignMemberRepository.class);
    final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    final EncounterService encounterService = mock(EncounterService.class);
    final SpellRepository spellRepository = mock(SpellRepository.class);
    final SubclassRepository subclassRepository = mock(SubclassRepository.class);
    final MonsterPoolTriggerRepository monsterPoolTriggerRepository = mock(MonsterPoolTriggerRepository.class);
    final ItemRepository itemRepository = mock(ItemRepository.class);
    final MonsterRepository monsterRepository = mock(MonsterRepository.class);
    // A class spell's numbers come from the character module; unstubbed, a spell uses the caster's main numbers
    final CharacterService characterService = mock(CharacterService.class);

    final ObjectMapper objectMapper = JacksonDefaults.mapper();
    // Records commands only inside a transaction's synchronization, which UndoServiceTest starts
    final CommandRecorder commandRecorder =
            new CommandRecorder(combatCommandRepository, combatLogRepository, objectMapper);
    final ScriptedDice dice = new ScriptedDice();
    final Resolver resolver = new Resolver(dice);
    final CreatureStats stats = new CreatureStats(objectMapper, new EquippedArmour(itemRepository));
    final CombatLogWriter combatLog = new CombatLogWriter(combatLogRepository);
    final ResourcePoolService resourcePoolService =
            new ResourcePoolService(mock(ClassFeaturePoolRepository.class), JacksonDefaults.mapper());
    final EffectApplier effectApplier = new EffectApplier(resolver, stats, combatLog, resourcePoolService);
    final AccessPolicy accessPolicy = new AccessPolicy(campaignRepository, campaignMemberRepository);
    final EncounterCommands encounterCommands = new EncounterCommands(encounterRepository, accessPolicy, eventPublisher);
    final CombatAccess access = new CombatAccess(encounterCommands, accessPolicy, commandRecorder);
    final SpellEffects spellEffects = new SpellEffects(spellRepository, stats);
    final SpellCaster spellCaster = new SpellCaster(spellEffects, resolver, effectApplier, stats);
    final MonsterEffects monsterEffects = new MonsterEffects(dice);
    final MonsterPools monsterPools = new MonsterPools(monsterPoolTriggerRepository, spellRepository, objectMapper);
    final ManualModifiers manualModifiers = new ManualModifiers(combatLog);
    final CasterSpells casterSpells = new CasterSpells(monsterPools, resourcePoolService, combatLog, spellRepository,
            subclassRepository, objectMapper);
    final CharacterWeapons characterWeapons = new CharacterWeapons(itemRepository, objectMapper);
    final ActionEconomyKeeper economy = new ActionEconomyKeeper(combatLog);

    final HitPointService hitPoints = new HitPointService(access, effectApplier, encounterRepository, encounterService);
    final ConditionService conditionService =
            new ConditionService(access, effectApplier, encounterRepository, encounterService);
    final AttackService attacks = new AttackService(access, accessPolicy, characterWeapons, resolver, stats,
            effectApplier, economy, manualModifiers, combatLog, encounterRepository, encounterService);
    final MetamagicCasting metamagicCasting = new MetamagicCasting(spellEffects, stats);
    final SpellcastingService spellcasting = new SpellcastingService(access, accessPolicy, casterSpells, spellCaster,
            spellEffects, effectApplier, economy, manualModifiers, combatLog, encounterRepository, encounterService,
            characterService, stats, metamagicCasting);
    final FontOfMagicService fontOfMagic = new FontOfMagicService(access, economy, combatLog, encounterRepository,
            encounterService);
    final MonsterActionService monsterActions = new MonsterActionService(access, accessPolicy, monsterEffects, resolver,
            stats, effectApplier, economy, resourcePoolService, objectMapper, monsterRepository, encounterRepository,
            encounterService);
    final LegendaryResistanceService legendaryResistance = new LegendaryResistanceService(access, effectApplier,
            resourcePoolService, combatLogRepository, combatCommandRepository, combatLog, encounterRepository,
            encounterService);
    final ChannelDivinityEffects channelDivinity = new ChannelDivinityEffects(effectApplier, stats, resolver,
            characterWeapons);
    final WildShapeEffects wildShapeEffects = new WildShapeEffects(monsterRepository, objectMapper, effectApplier);
    final FeatureEffects featureEffects = new FeatureEffects(effectApplier, stats, dice, channelDivinity,
            wildShapeEffects);
    final WildShapeService wildShape = new WildShapeService(access, accessPolicy, economy, effectApplier,
            wildShapeEffects, dice, encounterRepository, encounterService);
    final BardicInspirationService bardicInspiration = new BardicInspirationService(access, combatLog, dice,
            encounterRepository, encounterService);
    final PoolUseService poolUse = new PoolUseService(access, economy, combatLog, featureEffects, encounterRepository,
            encounterService);
    final StandardActionService standardActions = new StandardActionService(access, economy, resolver, stats,
            combatLog, effectApplier, objectMapper, encounterRepository, encounterService);
    final TurnEconomyEditService turnEconomyEdit = new TurnEconomyEditService(access, combatLog, encounterRepository,
            encounterService);
    final HitFollowUpService hitFollowUps = new HitFollowUpService(access, resolver, stats, effectApplier, economy,
            combatLog, encounterRepository, encounterService);
    final TurnProcessor turnProcessor = new TurnProcessor(effectApplier, combatLog);
    final TurnService turns = new TurnService(access, turnProcessor, encounterRepository, encounterService);
    final UndoService undoService = new UndoService(encounterCommands, commandRecorder, combatLog, combatLogRepository,
            encounterRepository, encounterService);

    final UUID dmUserId = UUID.randomUUID();
    final Campaign campaign;
    final Encounter encounter;
    final List<CombatLog> log = new ArrayList<>();

    CombatFixture() {
        User dm = User.builder().id(dmUserId).username("dm").email("dm@test.com").passwordHash("hash").build();
        campaign = Campaign.builder().id(UUID.randomUUID()).name("Test Campaign").dm(dm).inviteCode("ABC123").build();
        encounter = Encounter.builder()
                .id(UUID.randomUUID())
                .campaign(campaign)
                .name("Test Encounter")
                .status(EncounterStatus.ACTIVE)
                .roundNumber(1)
                .participants(new ArrayList<>())
                .build();

        lenient().when(campaignRepository.findById(campaign.getId())).thenReturn(Optional.of(campaign));
        // The DM is a member of their own campaign, as CampaignService records on creation
        lenient().when(campaignMemberRepository.existsByCampaignIdAndUserId(campaign.getId(), dmUserId)).thenReturn(true);
        lenient().when(encounterRepository.findById(encounter.getId())).thenReturn(Optional.of(encounter));
        // Commands find the campaign, then lock the encounter's row (D160)
        lenient().when(encounterRepository.findCampaignIdById(encounter.getId())).thenReturn(Optional.of(campaign.getId()));
        lenient().when(encounterRepository.lockById(encounter.getId())).thenReturn(Optional.of(encounter));
        lenient().when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(encounterService.toResponse(any(Encounter.class))).thenReturn(EncounterResponse.builder().build());
        lenient().when(combatLogRepository.save(any(CombatLog.class))).thenAnswer(inv -> {
            CombatLog entry = inv.getArgument(0);
            if (entry.getId() == null) {
                entry.setId(UUID.randomUUID());
            }
            if (!log.contains(entry)) {
                log.add(entry);
            }
            return entry;
        });
        // An entry the log holds, found by its ID; a test may stub an entry of its own
        lenient().when(combatLogRepository.findById(any(UUID.class))).thenAnswer(inv -> log.stream()
                .filter(entry -> inv.getArgument(0).equals(entry.getId()))
                .findFirst());
    }

    UUID encounterId() {
        return encounter.getId();
    }

    // ── Participants ─────────────────────────────────────────────

    /** A monster with a stat block whose abilities are all 10, unless the caller changes them. */
    EncounterParticipant monster(String name, int hp, int ac) {
        Monster monster = Monster.builder().id(UUID.randomUUID()).name(name).challengeRating("1")
                .strength(10).dexterity(10).constitution(10).intelligence(10).wisdom(10).charisma(10)
                .build();
        return add(EncounterParticipant.builder()
                .id(UUID.randomUUID())
                .encounter(encounter)
                .participantType(ParticipantType.MONSTER)
                .monster(monster)
                .displayName(name)
                .hpMax(hp).hpCurrent(hp).hpTemp(0)
                .armourClass(ac)
                .deathSaveSuccesses(0).deathSaveFailures(0)
                .sortOrder(encounter.getParticipants().size())
                .build());
    }

    /** A player character the DM controls, with a sheet whose abilities are all 10 and +2 proficiency. */
    EncounterParticipant player(String name, int hp, int ac) {
        PlayerCharacter character = PlayerCharacter.builder().id(UUID.randomUUID()).name(name).level(1)
                .strength(10).dexterity(10).constitution(10).intelligence(10).wisdom(10).charisma(10)
                .proficiencyBonus(2)
                .build();
        return add(EncounterParticipant.builder()
                .id(UUID.randomUUID())
                .encounter(encounter)
                .participantType(ParticipantType.PLAYER)
                .character(character)
                .displayName(name)
                .hpMax(hp).hpCurrent(hp).hpTemp(0)
                .armourClass(ac)
                .deathSaveSuccesses(0).deathSaveFailures(0)
                .controlledByUserId(dmUserId)
                .sortOrder(encounter.getParticipants().size())
                .build());
    }

    // The first participant added has the turn, as an encounter starts on its first; turnOf moves it
    private EncounterParticipant add(EncounterParticipant participant) {
        encounter.getParticipants().add(participant);
        if (encounter.getCurrentTurnParticipantId() == null && !Boolean.TRUE.equals(encounter.getLairTurn())) {
            encounter.setCurrentTurnParticipantId(participant.getId());
        }
        return participant;
    }

    /** Gives the participant the turn (D163). */
    void turnOf(EncounterParticipant participant) {
        encounter.setCurrentTurnParticipantId(participant.getId());
        encounter.setLairTurn(false);
    }

    /** Starts the participant's next turn's economy afresh, as the start of its turn does (D174). */
    static void newTurn(EncounterParticipant participant) {
        participant.setTurnEconomy(TurnEconomy.fresh());
    }

    /** Gives the Lair the turn (D163). */
    void lairTurn() {
        encounter.setCurrentTurnParticipantId(null);
        encounter.setLairTurn(true);
    }

    /** A user who is a member of the campaign and controls the participant. */
    UUID playerControlling(EncounterParticipant participant) {
        UUID userId = UUID.randomUUID();
        participant.setControlledByUserId(userId);
        lenient().when(campaignMemberRepository.existsByCampaignIdAndUserId(campaign.getId(), userId)).thenReturn(true);
        return userId;
    }

    /** Puts a PC at 0 HP, Dying or Stable with the given failures, unconscious and prone as dropping to 0 left it. */
    static void downed(EncounterParticipant pc, LifeState state, int failures) {
        pc.setHpCurrent(0);
        pc.setLifeState(state);
        pc.setDeathSaveFailures(failures);
        List<ConditionEntry> conditions = new ArrayList<>();
        conditions.add(ConditionEntry.unconsciousAtZeroHp(1));
        conditions.add(ConditionEntry.manual("prone", null, 1));
        pc.setActiveConditions(conditions);
    }

    static void dead(EncounterParticipant participant) {
        participant.setHpCurrent(0);
        participant.setLifeState(LifeState.DEAD);
    }

    static List<ResourcePoolEntry> legendaryResistance(int uses) {
        return new ArrayList<>(List.of(new ResourcePoolEntry(EffectApplier.LEGENDARY_RESISTANCE_POOL,
                "Legendary Resistance", "MONSTER", "Adult Red Dragon", 3, "3", uses, "longRest", null, null,
                "REACTION", "shield", null)));
    }

    static List<String> conditionNames(EncounterParticipant participant) {
        return CreatureStats.conditionNames(participant);
    }

    // ── Spells ───────────────────────────────────────────────────

    /** Makes the spell repository return the spell with this effect template, for its name in any case. */
    Spell spell(String name, int level, String template) {
        Spell spell = Spell.builder().id(UUID.randomUUID()).name(name).level(level).effectTemplate(SpellTemplate.parse(template)).build();
        lenient().when(spellRepository.findByNameIgnoreCase(argThat(name::equalsIgnoreCase)))
                .thenReturn(Optional.of(spell));
        return spell;
    }

    /** Gives a player character a spell {@link #spell} made, prepared, as a Wizard's (D164). */
    void knows(EncounterParticipant pc, String spellName) {
        int level = spellRepository.findByNameIgnoreCase(spellName).map(Spell::getLevel).orElseThrow();
        knows(pc, new KnownSpell(null, spellName, level, "class:Wizard", true, null, null, null, null));
    }

    /** Adds the entry to the participant's known spells, unless it is there already. */
    void knows(EncounterParticipant participant, KnownSpell spell) {
        List<KnownSpell> spells = participant.getSpellsKnown() != null
                ? new ArrayList<>(participant.getSpellsKnown()) : new ArrayList<>();
        if (!spells.contains(spell)) {
            spells.add(spell);
            participant.setSpellsKnown(spells);
        }
    }

    // ── Weapons ──────────────────────────────────────────────────

    // ── Armour ───────────────────────────────────────────────────

    /** An armour or shield row as ItemLoader writes one (M13.5). */
    static Item armour(String name, Item.ArmourCategory category, int base, boolean stealthDisadvantage) {
        return Item.builder().id(UUID.randomUUID()).name(name).source("PHB").armourCategory(category)
                .armourClassBase(base).stealthDisadvantage(stealthDisadvantage).build();
    }

    /** Equips the player character with the armour, linked to its rows in the items table. */
    void wears(EncounterParticipant pc, Item... items) {
        List<InventoryItem> equipment = new ArrayList<>(pc.getCharacter().getEquipment() != null
                ? pc.getCharacter().getEquipment() : List.of());
        for (Item item : items) {
            lenient().when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));
            equipment.add(new InventoryItem(item.getName(), 1, null, item.getId(), true, null));
        }
        pc.getCharacter().setEquipment(equipment);
    }

    /** Makes the items table hold these mundane weapons (D164). */
    void weapons(Item... items) {
        lenient().when(itemRepository.findMundaneWeapons()).thenReturn(List.of(items));
    }

    /**
     * A weapon row as ItemLoader writes one from the 5etools base items: its type, category in subtype, damage, and
     * property tags such as F (finesse), H (heavy) and AF|DMG (firearm ammunition).
     */
    static Item weapon(String name, String type, String category, String dice, String damageType, String... tags) {
        String properties = tags.length == 0 ? null
                : "{\"tags\": [" + String.join(", ", Arrays.stream(tags).map(tag -> "\"" + tag + "\"").toList()) + "]}";
        return Item.builder().id(UUID.randomUUID()).name(name).type(type).subtype(category).damageDice(dice)
                .damageType(damageType).properties(properties).source("PHB").build();
    }

    /** A versatile weapon row, its two-handed damage kept as ItemLoader keeps 5etools' dmg2. */
    static Item versatileWeapon(String name, String category, String dice, String twoHanded, String damageType) {
        return Item.builder().id(UUID.randomUUID()).name(name).type("Melee Weapon").subtype(category).damageDice(dice)
                .damageType(damageType).properties("{\"tags\": [\"V\"], \"versatile_damage\": \"" + twoHanded + "\"}")
                .source("PHB").build();
    }

    /** A level history entry recording a feat taken instead of an Ability Score Improvement. */
    static LevelHistoryEntry featTaken(String featName) {
        return new LevelHistoryEntry(4, null, null, 4, 0, null, null,
                LevelChoices.NONE.withAsi(new AbilityScoreImprovement("feat", null, featName, null, null, null, null)),
                null, null);
    }

    /** Gives a player character's sheet these inventory entries, by name, and these weapon proficiencies. */
    static void carries(EncounterParticipant pc, List<String> proficiencies, String... weaponNames) {
        pc.getCharacter().setEquipment(Arrays.stream(weaponNames)
                .map(name -> new InventoryItem(name, 1, null, null, null, null)).toList());
        pc.getCharacter().setWeaponProficiencies(proficiencies);
    }

    // ── Commands (M14) ───────────────────────────────────────────

    /** The combat commands recorded, in the order saved, once {@link #recordCommands} is called. */
    final List<CombatCommand> commands = new ArrayList<>();

    /**
     * Makes the command repository keep commands in {@link #commands}, answering its queries from them, and the log
     * count the entries of each. Commands are recorded only inside {@link #inTransaction}.
     */
    void recordCommands() {
        lenient().when(combatCommandRepository.save(any(CombatCommand.class))).thenAnswer(inv -> {
            CombatCommand command = inv.getArgument(0);
            if (command.getId() == null) {
                command.setId(UUID.randomUUID());
            }
            if (!commands.contains(command)) {
                commands.add(command);
            }
            return command;
        });
        lenient().doAnswer(inv -> commands.remove(inv.<CombatCommand>getArgument(0)))
                .when(combatCommandRepository).delete(any(CombatCommand.class));
        lenient().when(combatCommandRepository.lastSequence(any())).thenAnswer(inv ->
                commands.stream().mapToLong(CombatCommand::getSequence).max().orElse(0));
        lenient().when(combatCommandRepository.lastBarrier(any())).thenAnswer(inv ->
                commands.stream().filter(CombatCommand::isBarrier).mapToLong(CombatCommand::getSequence).max().orElse(0));
        lenient().when(combatCommandRepository.undoable(any(), anyLong(), any(Pageable.class))).thenAnswer(inv ->
                undoableAfter(inv.getArgument(1)).limit(inv.<Pageable>getArgument(2).getPageSize()).toList());
        lenient().when(combatCommandRepository.countUndoable(any(), anyLong())).thenAnswer(inv ->
                undoableAfter(inv.getArgument(1)).count());
        lenient().when(combatCommandRepository.undoneIds(any())).thenAnswer(inv -> commands.stream()
                .filter(command -> command.getUndoneAt() != null).map(CombatCommand::getId).collect(Collectors.toSet()));
        lenient().when(combatCommandRepository.existsByIdAndUndoneAtIsNotNull(any())).thenAnswer(inv -> commands.stream()
                .anyMatch(command -> command.getId().equals(inv.getArgument(0)) && command.getUndoneAt() != null));
        lenient().when(combatLogRepository.countByCommand(any())).thenAnswer(inv -> {
            Collection<UUID> ids = inv.getArgument(0);
            return log.stream().filter(entry -> ids.contains(entry.getCommandId()))
                    .collect(Collectors.groupingBy(CombatLog::getCommandId, Collectors.counting()))
                    .entrySet().stream().map(e -> new Object[] {e.getKey(), e.getValue()}).toList();
        });
    }

    private Stream<CombatCommand> undoableAfter(long sequence) {
        return commands.stream()
                .filter(command -> command.getUndoneAt() == null && !command.isBarrier() && command.getSequence() > sequence)
                .sorted(Comparator.comparing(CombatCommand::getSequence).reversed());
    }

    /** Records the start of the encounter, the barrier undo can't go past. */
    void started() {
        commandRecorder.barrier(encounter, dmUserId, "ENCOUNTER_START", "The encounter starts");
    }

    /**
     * Runs the command as its transaction would: with synchronization on, so the recorder records it, and then
     * committed, which runs the recorder's check before commit. A command that throws rolls back instead, and the
     * command saved for it is dropped, as the database would drop it.
     */
    <T> T inTransaction(Supplier<T> command) {
        TransactionSynchronizationManager.initSynchronization();
        int before = commands.size();
        boolean committed = false;
        try {
            T result = command.get();
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.beforeCommit(false));
            committed = true;
            return result;
        } finally {
            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            TransactionSynchronizationManager.clearSynchronization();
            int status = committed
                    ? TransactionSynchronization.STATUS_COMMITTED : TransactionSynchronization.STATUS_ROLLED_BACK;
            synchronizations.forEach(sync -> sync.afterCompletion(status));
            if (!committed) {
                while (commands.size() > before) {
                    commands.removeLast();
                }
            }
        }
    }

    // ── The log ──────────────────────────────────────────────────

    List<String> descriptions() {
        return log.stream().map(CombatLog::getDescription).toList();
    }

    List<CombatLog> entries(CombatActionType type) {
        return log.stream().filter(entry -> entry.getActionType() == type).toList();
    }

    /** The entries about a target, in the order written. */
    List<CombatLog> entriesAbout(EncounterParticipant target) {
        return log.stream().filter(entry -> target.getId().equals(entry.getTargetId())).toList();
    }

    /** Dice that return the rolls scripted last, in order, and fail on any roll not scripted. */
    static final class ScriptedDice implements Rng {

        private Rng.Scripted rolls = Rng.scripted();

        void script(int... rolls) {
            this.rolls = Rng.scripted(rolls);
        }

        int remaining() {
            return rolls.remaining();
        }

        @Override
        public int roll(int sides) {
            return rolls.roll(sides);
        }
    }
}
