package com.tabletophelper.encounter;

import com.tabletophelper.encounter.dto.CastSpellResponse;
import com.tabletophelper.encounter.dto.CombatLogResponse;
import com.tabletophelper.encounter.dto.EncounterResponse;
import com.tabletophelper.encounter.dto.EncounterResponse.ParticipantResponse;
import com.tabletophelper.encounter.dto.EncounterResponse.TurnResponse;
import com.tabletophelper.encounter.dto.MonsterActionResponse;
import com.tabletophelper.encounter.state.TurnEconomy;
import com.tabletophelper.encounter.state.WeaponHit;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The two views of a live encounter (D157). The DM View is the full state; the Player View is what
 * every player receives. These are pure functions: each returns a copy and never changes its input.
 */
public final class EncounterViews {

    // The entries that say what a creature has left of a resource: "(2/3 remaining)", "(1 remaining)", "(4/4)",
    // "(unlimited)", "; 2/3 left" or "; unlimited"
    private static final Set<String> RESOURCE_ENTRIES = Set.of(CombatActionType.POOL_USE.name(),
            CombatActionType.SPELL_SLOT_USE.name(), CombatActionType.SPELL_SLOT_RESTORE.name(),
            CombatActionType.LEGENDARY_RESISTANCE_USED.name());
    private static final Pattern RESOURCE_TALLY = Pattern.compile(
            " \\((?:\\d+/\\d+(?: remaining)?|\\d+ remaining|unlimited)\\)|; (?:\\d+/\\d+ left|unlimited)$");

    /** What players read for the DM's undo of a command about a participant hidden from them (D157, M14). */
    public static final String UNDID_HIDDEN = "The DM undid an action.";

    private EncounterViews() {}

    /** The DM View for the campaign's DM, the Player View for everyone else. */
    public static EncounterResponse forUser(EncounterResponse dmView, UUID userId) {
        return userId.equals(dmView.getDmUserId()) ? dmView : playerView(dmView);
    }

    /** As {@link #forUser(EncounterResponse, UUID)}, for the encounter state inside a spell result. */
    public static CastSpellResponse forUser(CastSpellResponse response, UUID userId) {
        return response.toBuilder()
                .encounterState(forUser(response.getEncounterState(), userId))
                .build();
    }

    /** As {@link #forUser(EncounterResponse, UUID)}, for the encounter state inside a monster action result. */
    public static MonsterActionResponse forUser(MonsterActionResponse response, UUID userId) {
        return response.withEncounterState(forUser(response.encounterState(), userId));
    }

    /**
     * Leaves out participants hidden from players (a null flag counts as visible). Monsters lose
     * their notes, known spells, spell slots, spellcasting numbers, resource pools, turn economy and attacks per
     * Attack action (D174), the slot levels of their Spell Effects (M15) and the ID of their bestiary entry, which would lead to the full stat block. Unless the campaign shows exact monster
     * HP, their HP, temporary HP included, is replaced by a Health Status.
     * <p>
     * The turn order leaves out the hidden participants' turns, and the Lair's unless players can see a
     * monster in its lair. The turns left are numbered again, so sortOrder and currentTurnIndex count
     * only what players see, and there is no current turn while a turn they can't see is taken (D157).
     */
    public static EncounterResponse playerView(EncounterResponse dmView) {
        Set<UUID> visible = dmView.getParticipants().stream()
                .filter(p -> !Boolean.FALSE.equals(p.getIsVisibleToPlayers()))
                .map(ParticipantResponse::getId)
                .collect(Collectors.toSet());
        List<UUID> visibleInLair = Optional.ofNullable(dmView.getMonstersInLair()).orElse(List.of()).stream()
                .filter(visible::contains)
                .toList();

        List<TurnResponse> turns = Optional.ofNullable(dmView.getTurnOrder()).orElse(List.of()).stream()
                .filter(turn -> turn.isLair() ? !visibleInLair.isEmpty() : visible.contains(turn.getParticipantId()))
                .map(turn -> turn.toBuilder().tieGroup(null).build())
                .toList();
        Map<UUID, Integer> places = new HashMap<>();
        Integer current = null;
        for (int i = 0; i < turns.size(); i++) {
            if (turns.get(i).getParticipantId() != null) {
                places.put(turns.get(i).getParticipantId(), i);
            }
            if (turns.get(i).isCurrent()) {
                current = i;
            }
        }

        Set<UUID> hidden = dmView.getParticipants().stream()
                .map(ParticipantResponse::getId)
                .filter(id -> !visible.contains(id))
                .collect(Collectors.toSet());
        List<ParticipantResponse> participants = dmView.getParticipants().stream()
                .filter(p -> visible.contains(p.getId()))
                .map(p -> numbersHidden(p)
                        ? monsterForPlayers(p, dmView.isShowExactMonsterHp())
                        : p.toBuilder().turnEconomy(turnEconomyForPlayers(p.getTurnEconomy(), hidden)).build())
                .map(p -> p.toBuilder().sortOrder(places.get(p.getId())).build())
                .map(p -> withoutHidden(p, hidden))
                .toList();
        return dmView.toBuilder()
                .turnStamp(maskTurn(dmView.getTurnStamp(), hidden))
                .participants(participants)
                .turnOrder(turns)
                .currentTurnIndex(current)
                .monstersInLair(visibleInLair)
                .undo(null)
                .build();
    }

    /**
     * The combat log as players read it: entries whose actor, target or Legendary Resistance target
     * is hidden are left out, and no entry keeps the Legendary Resistance fields. An entry logged
     * during a hidden participant's turn keeps its place without that participant's name. The DM's edit of a
     * monster's turn economy keeps its place as "The DM adjusts Goblin's turn.", without the changes, since players
     * don't see monsters' budgets (D157, D174). A monster's use of a pool, a spell slot or Legendary Resistance keeps
     * its text without what the monster has left, as players don't see its pools or slots either. The entries of
     * commands the DM undid are left out, and the UNDO entry for a command about a hidden participant reads only
     * {@link #UNDID_HIDDEN} (M14). No entry keeps the command IDs.
     *
     * @param hiddenParticipants each hidden participant's ID, mapped to its display name
     * @param monsters           each monster's ID
     */
    public static List<CombatLogResponse> playerLog(List<CombatLogResponse> log, Map<UUID, String> hiddenParticipants,
                                                    Set<UUID> monsters) {
        Set<UUID> hiddenCommands = log.stream()
                .filter(entry -> entry.getCommandId() != null && aboutHidden(entry, hiddenParticipants))
                .map(CombatLogResponse::getCommandId)
                .collect(Collectors.toSet());
        return log.stream()
                .filter(entry -> !entry.isUndone() && !aboutHidden(entry, hiddenParticipants))
                .map(entry -> entry.toBuilder()
                        .description(undoesHidden(entry, hiddenCommands, hiddenParticipants)
                                ? UNDID_HIDDEN : playerDescription(entry, monsters))
                        .commandId(null)
                        .undoesCommandId(null)
                        .turnParticipantName(namesHiddenParticipant(entry.getTurnParticipantName(), hiddenParticipants)
                                ? null : entry.getTurnParticipantName())
                        .legendaryResistanceEligible(null)
                        .lrTargetId(null)
                        .lrDamageDealt(null)
                        .lrConditionsApplied(null)
                        .lrResolved(null)
                        .lrSuccessDamage(null)
                        .lrConditionIds(null)
                        .build())
                .toList();
    }

    /** Each monster's ID, as {@link #playerLog} takes them. */
    public static Set<UUID> monsters(EncounterResponse dmView) {
        return dmView.getParticipants().stream()
                .filter(EncounterViews::numbersHidden)
                .map(ParticipantResponse::getId)
                .collect(Collectors.toSet());
    }

    /**
     * Whether players see the participant as a monster, without its numbers (D157): a monster, or a companion the DM
     * controls, such as Summon Lesser Demons' demons (XGE p.167, D182). A companion a player controls is seen as a
     * player character is.
     */
    public static boolean numbersHidden(ParticipantResponse participant) {
        return numbersHidden(participant.getParticipantType(), participant.getControlledByUserId());
    }

    /** As {@link #numbersHidden(ParticipantResponse)}, for a participant row. */
    public static boolean numbersHidden(EncounterParticipant participant) {
        return numbersHidden(participant.getParticipantType().name(), participant.getControlledByUserId());
    }

    private static boolean numbersHidden(String type, UUID controller) {
        return ParticipantType.MONSTER.name().equals(type)
                || ParticipantType.COMPANION.name().equals(type) && controller == null;
    }

    /** Each participant the DM View marks hidden, mapped to its display name, as {@link #playerLog} takes them. */
    public static Map<UUID, String> hiddenParticipants(EncounterResponse dmView) {
        return dmView.getParticipants().stream()
                .filter(p -> Boolean.FALSE.equals(p.getIsVisibleToPlayers()))
                .collect(Collectors.toMap(ParticipantResponse::getId, ParticipantResponse::getDisplayName));
    }

    // The entry's text as players read it, without a monster's turn economy or what it has left of its resources
    private static String playerDescription(CombatLogResponse entry, Set<UUID> monsters) {
        if (monsterEconomyEdit(entry, monsters)) {
            return "The DM adjusts " + entry.getTargetName() + "'s turn.";
        }
        if (monsterResourceUse(entry, monsters) && entry.getDescription() != null) {
            return RESOURCE_TALLY.matcher(entry.getDescription()).replaceAll("");
        }
        return entry.getDescription();
    }

    // A monster's use of a pool, a spell slot, an innate spell or Legendary Resistance, whose entry ends with what it
    // has left, which the Player View hides with the monster's pools and slots. A slot's entry names its caster as
    // the target, a pool's as the actor
    private static boolean monsterResourceUse(CombatLogResponse entry, Set<UUID> monsters) {
        return RESOURCE_ENTRIES.contains(entry.getActionType())
                && (entry.getActorId() != null && monsters.contains(entry.getActorId())
                || entry.getTargetId() != null && monsters.contains(entry.getTargetId()));
    }

    // The DM's edit of a monster's turn economy, whose changes would show players the budget the Player View hides
    private static boolean monsterEconomyEdit(CombatLogResponse entry, Set<UUID> monsters) {
        return CombatActionType.TURN_ECONOMY_SET.name().equals(entry.getActionType())
                && entry.getTargetId() != null && monsters.contains(entry.getTargetId());
    }

    private static boolean aboutHidden(CombatLogResponse entry, Map<UUID, String> hiddenParticipants) {
        return isHidden(entry.getActorId(), hiddenParticipants) || isHidden(entry.getTargetId(), hiddenParticipants)
                || isHidden(entry.getLrTargetId(), hiddenParticipants);
    }

    // An UNDO entry for a command about a participant hidden from players, whose summary could name it (D157)
    private static boolean undoesHidden(CombatLogResponse entry, Set<UUID> hiddenCommands,
                                        Map<UUID, String> hiddenParticipants) {
        if (!CombatActionType.UNDO.name().equals(entry.getActionType())) {
            return false;
        }
        return entry.getUndoesCommandId() != null && hiddenCommands.contains(entry.getUndoesCommandId())
                || entry.getDescription() != null && hiddenParticipants.values().stream()
                        .anyMatch(name -> name != null && !name.isBlank() && entry.getDescription().contains(name));
    }

    private static boolean isHidden(UUID participantId, Map<UUID, String> hiddenParticipants) {
        return participantId != null && hiddenParticipants.containsKey(participantId);
    }

    // The log stores the turn's participant by name only
    private static boolean namesHiddenParticipant(String name, Map<UUID, String> hiddenParticipants) {
        return name != null && hiddenParticipants.containsValue(name);
    }

    /**
     * A player character's turn economy as players see it (M17): its records of a turn name a hidden participant's turn
     * only as "<round>:hidden", and a melee hit on a hidden participant is left out (D157).
     */
    static TurnEconomy turnEconomyForPlayers(TurnEconomy economy, Set<UUID> hidden) {
        if (economy == null || hidden.isEmpty()) {
            return economy;
        }
        WeaponHit hit = economy.lastMeleeHit();
        WeaponHit shown = hit == null || hidden.contains(hit.targetId()) ? null
                : hit.withTurn(maskTurn(hit.turn(), hidden));
        return economy.withSneakAttackTurn(maskTurn(economy.sneakAttackTurn(), hidden)).withLastMeleeHit(shown);
    }

    // A turn's stamp, "<round>:<participant id>", with a hidden participant's ID replaced
    private static String maskTurn(String turn, Set<UUID> hidden) {
        if (turn == null) {
            return null;
        }
        int colon = turn.indexOf(':');
        String whose = colon >= 0 ? turn.substring(colon + 1) : "";
        return hidden.stream().anyMatch(id -> id.toString().equals(whose)) ? turn.substring(0, colon + 1) + "hidden"
                : turn;
    }

    /*
     * The Ongoing Modifiers' parts of a participant without the hidden participants (D157, D184): a chip names no hidden
     * caster, and a Spell Effect lists no hidden creature among those it affects.
     */
    private static ParticipantResponse withoutHidden(ParticipantResponse participant, Set<UUID> hidden) {
        if (hidden.isEmpty()) {
            return participant;
        }
        return participant.toBuilder()
                .spellModifiers(participant.getSpellModifiers() == null ? null : participant.getSpellModifiers()
                        .stream()
                        .map(chip -> hidden.contains(chip.getCasterId()) ? chip.toBuilder().casterId(null).build()
                                : chip)
                        .toList())
                .activeEffects(participant.getActiveEffects() == null ? null : participant.getActiveEffects().stream()
                        .map(effect -> effect.getAffectedIds() == null ? effect : effect.toBuilder()
                                .affectedIds(effect.getAffectedIds().stream().filter(id -> !hidden.contains(id))
                                        .toList())
                                // Damage still to come names its creature, so a hidden one's is left out (D157, D185)
                                .delayed(effect.getDelayed() == null ? null : effect.getDelayed().stream()
                                        .filter(delayed -> !hidden.contains(delayed.target())).toList())
                                .build())
                        .toList())
                .build();
    }

    private static ParticipantResponse monsterForPlayers(ParticipantResponse monster, boolean showExactHp) {
        var view = monster.toBuilder()
                .monsterId(null)
                .hasLairActions(null)
                .notes(null)
                .spellsKnown(null)
                .spellSlotsCurrent(null)
                .spellAttackBonus(null)
                .spellSaveDc(null)
                .spellcastingAbility(null)
                .resourcePoolsCurrent(null)
                .turnEconomy(null)
                .attacksPerAction(null)
                .activeEffects(monster.getActiveEffects() == null ? null : monster.getActiveEffects().stream()
                        .map(effect -> effect.toBuilder().slotLevel(null).build())
                        .toList());
        if (!showExactHp) {
            HealthStatus status = HealthStatus.of(monster.getHpCurrent(), monster.getHpMax());
            view.healthStatus(status != null ? status.name() : null)
                    .hpCurrent(null)
                    .hpMax(null)
                    .hpMaxBase(null)
                    .hpTemp(null);
        }
        return view.build();
    }
}
