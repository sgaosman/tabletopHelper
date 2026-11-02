package com.tabletophelper.encounter;

import com.tabletophelper.encounter.dto.CastSpellResponse;
import com.tabletophelper.encounter.dto.CombatLogResponse;
import com.tabletophelper.encounter.dto.EncounterResponse;
import com.tabletophelper.encounter.dto.EncounterResponse.ParticipantResponse;
import com.tabletophelper.encounter.dto.EncounterResponse.TurnResponse;
import com.tabletophelper.encounter.dto.MonsterActionResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The two views of a live encounter (D157). The DM View is the full state; the Player View is what
 * every player receives. These are pure functions: each returns a copy and never changes its input.
 */
public final class EncounterViews {

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
     * their notes, known spells, spell slots, spellcasting numbers, resource pools and the ID of their
     * bestiary entry, which would lead to the full stat block. Unless the campaign shows exact monster
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

        List<ParticipantResponse> participants = dmView.getParticipants().stream()
                .filter(p -> visible.contains(p.getId()))
                .map(p -> ParticipantType.MONSTER.name().equals(p.getParticipantType())
                        ? monsterForPlayers(p, dmView.isShowExactMonsterHp())
                        : p)
                .map(p -> p.toBuilder().sortOrder(places.get(p.getId())).build())
                .toList();
        return dmView.toBuilder()
                .participants(participants)
                .turnOrder(turns)
                .currentTurnIndex(current)
                .monstersInLair(visibleInLair)
                .build();
    }

    /**
     * The combat log as players read it: entries whose actor, target or Legendary Resistance target
     * is hidden are left out, and no entry keeps the Legendary Resistance fields. An entry logged
     * during a hidden participant's turn keeps its place without that participant's name.
     *
     * @param hiddenParticipants each hidden participant's ID, mapped to its display name
     */
    public static List<CombatLogResponse> playerLog(List<CombatLogResponse> log, Map<UUID, String> hiddenParticipants) {
        return log.stream()
                .filter(entry -> !isHidden(entry.getActorId(), hiddenParticipants)
                        && !isHidden(entry.getTargetId(), hiddenParticipants)
                        && !isHidden(entry.getLrTargetId(), hiddenParticipants))
                .map(entry -> entry.toBuilder()
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

    /** Each participant the DM View marks hidden, mapped to its display name, as {@link #playerLog} takes them. */
    public static Map<UUID, String> hiddenParticipants(EncounterResponse dmView) {
        return dmView.getParticipants().stream()
                .filter(p -> Boolean.FALSE.equals(p.getIsVisibleToPlayers()))
                .collect(Collectors.toMap(ParticipantResponse::getId, ParticipantResponse::getDisplayName));
    }

    private static boolean isHidden(UUID participantId, Map<UUID, String> hiddenParticipants) {
        return participantId != null && hiddenParticipants.containsKey(participantId);
    }

    // The log stores the turn's participant by name only
    private static boolean namesHiddenParticipant(String name, Map<UUID, String> hiddenParticipants) {
        return name != null && hiddenParticipants.containsValue(name);
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
                .resourcePoolsCurrent(null);
        if (!showExactHp) {
            HealthStatus status = HealthStatus.of(monster.getHpCurrent(), monster.getHpMax());
            view.healthStatus(status != null ? status.name() : null)
                    .hpCurrent(null)
                    .hpMax(null)
                    .hpTemp(null);
        }
        return view.build();
    }
}
