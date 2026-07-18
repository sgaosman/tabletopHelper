package com.tabletophelper.encounter;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CombatLogRepository extends JpaRepository<CombatLog, UUID> {

    List<CombatLog> findByEncounterIdOrderByCreatedAtAsc(UUID encounterId);
}
