package com.tabletophelper.encounter.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.campaign.AccessPolicy;
import com.tabletophelper.campaign.Campaign;
import com.tabletophelper.campaign.CampaignMemberRepository;
import com.tabletophelper.campaign.CampaignRepository;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.dto.ResourcePoolEntry;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.CombatLog;
import com.tabletophelper.encounter.CombatLogRepository;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterCommands;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterRepository;
import com.tabletophelper.encounter.EncounterService;
import com.tabletophelper.encounter.EncounterStatus;
import com.tabletophelper.encounter.MonsterSpellCastService;
import com.tabletophelper.encounter.ParticipantType;
import com.tabletophelper.encounter.dto.EncounterResponse;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.identity.User;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.reference.ClassFeaturePoolRepository;
import com.tabletophelper.reference.MonsterPoolTriggerRepository;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import com.tabletophelper.resourcepool.ResourcePoolService;
import com.tabletophelper.rules.Rng;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.rules.combat.Resolver;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * The combat services wired as in the app, over mocked repositories, an Active encounter whose DM is
 * {@link #dmUserId}, and dice the test scripts. Every combat log entry saved is kept in {@link #log}.
 */
class CombatFixture {

    final EncounterRepository encounterRepository = mock(EncounterRepository.class);
    final CombatLogRepository combatLogRepository = mock(CombatLogRepository.class);
    final CampaignRepository campaignRepository = mock(CampaignRepository.class);
    final CampaignMemberRepository campaignMemberRepository = mock(CampaignMemberRepository.class);
    final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    final EncounterService encounterService = mock(EncounterService.class);
    final SpellRepository spellRepository = mock(SpellRepository.class);

    final ObjectMapper objectMapper = new ObjectMapper();
    final ScriptedDice dice = new ScriptedDice();
    final Resolver resolver = new Resolver(dice);
    final CreatureStats stats = new CreatureStats(objectMapper);
    final CombatLogWriter combatLog = new CombatLogWriter(combatLogRepository);
    final EffectApplier effectApplier = new EffectApplier(resolver, stats, combatLog);
    final AccessPolicy accessPolicy = new AccessPolicy(campaignRepository, campaignMemberRepository);
    final CombatAccess access = new CombatAccess(
            new EncounterCommands(encounterRepository, accessPolicy, eventPublisher), accessPolicy);
    final ResourcePoolService resourcePoolService = new ResourcePoolService(
            mock(ClassFeaturePoolRepository.class), mock(MonsterPoolTriggerRepository.class), objectMapper);
    final SpellEffects spellEffects = new SpellEffects(spellRepository, objectMapper, stats);
    final SpellCaster spellCaster = new SpellCaster(spellEffects, resolver, effectApplier, stats);
    final MonsterEffects monsterEffects = new MonsterEffects(dice);
    final MonsterSpellCastService monsterSpellCastService =
            new MonsterSpellCastService(spellCaster, resourcePoolService, objectMapper, spellRepository);

    final HitPointService hitPoints = new HitPointService(access, effectApplier, encounterRepository, encounterService);
    final ConditionService conditionService =
            new ConditionService(access, effectApplier, encounterRepository, encounterService);
    final AttackService attacks =
            new AttackService(access, resolver, stats, effectApplier, encounterRepository, encounterService);
    final SpellcastingService spellcasting = new SpellcastingService(access, spellCaster, spellEffects,
            effectApplier, combatLog, encounterRepository, encounterService);
    final MonsterActionService monsterActions = new MonsterActionService(access, monsterEffects, resolver, stats,
            effectApplier, monsterSpellCastService, resourcePoolService, objectMapper, encounterRepository,
            encounterService);
    final LegendaryResistanceService legendaryResistance = new LegendaryResistanceService(access, effectApplier,
            resourcePoolService, combatLogRepository, combatLog, encounterRepository, encounterService);
    final TurnService turns = new TurnService(access, effectApplier, resourcePoolService, combatLog,
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
                .currentTurnIndex(0)
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
                .isCurrentTurn(false)
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
                .isCurrentTurn(false)
                .deathSaveSuccesses(0).deathSaveFailures(0)
                .controlledByUserId(dmUserId)
                .sortOrder(encounter.getParticipants().size())
                .build());
    }

    private EncounterParticipant add(EncounterParticipant participant) {
        encounter.getParticipants().add(participant);
        return participant;
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

    /** Makes the spell repository return the spell with this effect template. */
    Spell spell(String name, int level, String template) {
        Spell spell = Spell.builder().id(UUID.randomUUID()).name(name).level(level).effectTemplate(template).build();
        lenient().when(spellRepository.findByNameIgnoreCase(name)).thenReturn(Optional.of(spell));
        return spell;
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
