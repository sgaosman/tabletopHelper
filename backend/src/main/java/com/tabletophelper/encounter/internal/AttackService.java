package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterRepository;
import com.tabletophelper.encounter.EncounterService;
import com.tabletophelper.encounter.dto.AttackRollRequest;
import com.tabletophelper.encounter.dto.EncounterResponse;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.Resolver;
import com.tabletophelper.shared.ForbiddenException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Weapon attacks with the numbers the attacker gives (D022, until R5 workstream G calculates them). An attack
 * on an unconscious or paralyzed target has advantage, and a hit from within 5 feet is a critical hit; attacks
 * never hit automatically, and the dead can't be attacked (D161).
 */
@Service
@RequiredArgsConstructor
public class AttackService {

    private final CombatAccess access;
    private final Resolver resolver;
    private final CreatureStats stats;
    private final EffectApplier effectApplier;
    private final EncounterRepository encounterRepository;
    private final EncounterService encounterService;

    /**
     * An attack by the actor, or by the DM as no one when actorParticipantId is null. A player attacks only
     * with their own character on its turn (D028), and only the DM may force a critical hit (D161).
     */
    @Transactional
    public EncounterResponse rollAttack(UUID encounterId, AttackRollRequest request, UUID actorParticipantId,
                                        UUID userId) {
        Encounter encounter = access.beginOnTurnFor(encounterId, actorParticipantId, userId);
        boolean forcedCritical = Boolean.TRUE.equals(request.getForceCrit());
        if (forcedCritical && !CombatAccess.isDm(encounter, userId)) {
            throw new ForbiddenException("Only the DM can force a critical hit");
        }
        EncounterParticipant actor = actorParticipantId != null
                ? CombatAccess.participant(encounter, actorParticipantId) : null;
        EncounterParticipant target = CombatAccess.target(encounter, request.getTargetId(), userId);
        CombatAccess.requireNotDead(List.of(target));

        String damageType = request.getDamageType() != null && !request.getDamageType().isBlank()
                ? request.getDamageType().trim().toLowerCase() : null;
        Effect effect = new Effect("Attack",
                new Effect.Attack(request.getAttackBonus(), SpellEffects.rollMode(request.getAdvantage()),
                        !Boolean.FALSE.equals(request.getWithin5Ft()), forcedCritical),
                List.of(new Effect.DamagePart(Dice.parse(request.getDamageDice()), damageType)),
                null, List.of(), false, null);

        EffectApplier.Use use = new EffectApplier.Use(actor, actor != null ? actor.getDisplayName() : "DM",
                CombatActionType.ATTACK, "attacks", "", null, null);
        effectApplier.apply(encounter, use, effect, resolver.resolve(effect, List.of(stats.target(target, effect))));
        return encounterService.toResponse(encounterRepository.save(encounter));
    }
}
