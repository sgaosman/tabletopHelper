package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterRepository;
import com.tabletophelper.encounter.EncounterService;
import com.tabletophelper.encounter.ParticipantType;
import com.tabletophelper.encounter.dto.DamageRequest;
import com.tabletophelper.encounter.dto.DeathSaveRequest;
import com.tabletophelper.encounter.dto.EncounterResponse;
import com.tabletophelper.encounter.dto.HealRequest;
import com.tabletophelper.encounter.dto.SetHpRequest;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Hit points by hand (D160): the DM's damage, healing and Set HP / revive, and death saving throws. Damage and
 * healing are the DM's alone again, as D028 says (D161).
 */
@Service
@RequiredArgsConstructor
public class HitPointService {

    private final CombatAccess access;
    private final EffectApplier effectApplier;
    private final EncounterRepository encounterRepository;
    private final EncounterService encounterService;

    /**
     * Damage the DM applies, untyped unless a type is given. actorParticipantId names the source for the log;
     * without it the DM is the source.
     */
    @Transactional
    public EncounterResponse applyDamage(UUID encounterId, DamageRequest request, UUID actorParticipantId,
                                         UUID userId) {
        Encounter encounter = access.beginAsDm(encounterId, userId);
        EncounterParticipant actor = actorParticipantId != null
                ? CombatAccess.participant(encounter, actorParticipantId) : null;
        EncounterParticipant target = CombatAccess.participant(encounter, request.getTargetId());
        if (target.getLifeState() == LifeState.DEAD) {
            throw new ConflictException(target.getDisplayName() + " is already dead");
        }
        effectApplier.damage(encounter, actor, actor != null ? actor.getDisplayName() : "DM", target,
                request.getAmount(), request.getDamageType());
        return respond(encounter);
    }

    /** Healing the DM applies. The dead can't be healed; Set HP revives them (D161). */
    @Transactional
    public EncounterResponse applyHealing(UUID encounterId, HealRequest request, UUID actorParticipantId,
                                          UUID userId) {
        Encounter encounter = access.beginAsDm(encounterId, userId);
        EncounterParticipant actor = actorParticipantId != null
                ? CombatAccess.participant(encounter, actorParticipantId) : null;
        EncounterParticipant target = CombatAccess.participant(encounter, request.getTargetId());
        if (target.getLifeState() == LifeState.DEAD) {
            throw new ConflictException(target.getDisplayName()
                    + " is dead, and healing can't bring them back; use Set HP to revive them");
        }
        effectApplier.heal(encounter, actor, actor != null ? actor.getDisplayName() : "DM", target,
                request.getAmount());
        return respond(encounter);
    }

    /** The DM's Set HP / revive, logged as HP_SET (D161). */
    @Transactional
    public EncounterResponse setHp(UUID encounterId, SetHpRequest request, UUID userId) {
        Encounter encounter = access.beginAsDm(encounterId, userId);
        EncounterParticipant target = CombatAccess.participant(encounter, request.getTargetId());
        effectApplier.setHp(encounter, target, request.getHpCurrent(), request.getHpTemp());
        return respond(encounter);
    }

    /** A dying PC's death saving throw, by the DM or the PC's player (D028). */
    @Transactional
    public EncounterResponse rollDeathSave(UUID encounterId, DeathSaveRequest request, UUID userId) {
        Encounter encounter = access.beginFor(encounterId, request.getParticipantId(), userId);
        EncounterParticipant participant = CombatAccess.participant(encounter, request.getParticipantId());
        if (participant.getParticipantType() != ParticipantType.PLAYER) {
            throw new ValidationException("Only player characters make death saving throws");
        }
        switch (participant.getLifeState()) {
            case DYING -> effectApplier.deathSave(encounter, participant);
            case ALIVE -> throw new ConflictException(participant.getDisplayName() + " is not dying");
            case STABLE -> throw new ConflictException(participant.getDisplayName()
                    + " is stable and makes no death saves");
            case DEAD -> throw new ConflictException(participant.getDisplayName() + " is dead");
        }
        return respond(encounter);
    }

    private EncounterResponse respond(Encounter encounter) {
        return encounterService.toResponse(encounterRepository.save(encounter));
    }
}
