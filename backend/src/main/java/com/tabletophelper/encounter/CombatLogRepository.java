package com.tabletophelper.encounter;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CombatLogRepository extends JpaRepository<CombatLog, UUID> {

    List<CombatLog> findByEncounterIdOrderByCreatedAtAsc(UUID encounterId);

    /** How many entries each of the combat commands wrote (M14), as pairs of command ID and count. */
    @Query("select l.commandId, count(l) from CombatLog l where l.commandId in :commandIds group by l.commandId")
    List<Object[]> countByCommand(@Param("commandIds") Collection<UUID> commandIds);

    /** The entries a combat command wrote, oldest first (M14). */
    List<CombatLog> findByCommandIdOrderByCreatedAtAsc(UUID commandId);
}
