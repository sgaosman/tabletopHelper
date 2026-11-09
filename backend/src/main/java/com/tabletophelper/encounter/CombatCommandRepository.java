package com.tabletophelper.encounter;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface CombatCommandRepository extends JpaRepository<CombatCommand, UUID> {

    /** The encounter's last command's sequence, 0 before its first. */
    @Query("select coalesce(max(c.sequence), 0) from CombatCommand c where c.encounterId = :encounterId")
    long lastSequence(@Param("encounterId") UUID encounterId);

    /** The encounter's last barrier's sequence, 0 when it has none. */
    @Query("select coalesce(max(c.sequence), 0) from CombatCommand c "
            + "where c.encounterId = :encounterId and c.barrier = true")
    long lastBarrier(@Param("encounterId") UUID encounterId);

    /** The commands after the sequence that undo may take, newest first. */
    @Query("select c from CombatCommand c where c.encounterId = :encounterId and c.undoneAt is null "
            + "and c.barrier = false and c.sequence > :after order by c.sequence desc")
    List<CombatCommand> undoable(@Param("encounterId") UUID encounterId, @Param("after") long after, Pageable page);

    /** How many commands after the sequence undo may take. */
    @Query("select count(c) from CombatCommand c where c.encounterId = :encounterId and c.undoneAt is null "
            + "and c.barrier = false and c.sequence > :after")
    long countUndoable(@Param("encounterId") UUID encounterId, @Param("after") long after);

    /** The encounter's commands that have been undone. */
    @Query("select c.id from CombatCommand c where c.encounterId = :encounterId and c.undoneAt is not null")
    Set<UUID> undoneIds(@Param("encounterId") UUID encounterId);

    boolean existsByIdAndUndoneAtIsNotNull(UUID id);

    /** Clears what the encounter's commands changed, once it has completed and nothing can be undone. */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE combat_commands SET before_state = NULL WHERE encounter_id = :encounterId", nativeQuery = true)
    int clearBeforeStates(@Param("encounterId") UUID encounterId);
}
