package com.tabletophelper.encounter;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EncounterRepository extends JpaRepository<Encounter, UUID> {

    List<Encounter> findByCampaignIdOrderByCreatedAtDesc(UUID campaignId);

    Optional<Encounter> findBySessionCode(String sessionCode);

    // Reads only the foreign key, so the subscription check loads no entity and needs no transaction
    @Query("SELECT e.campaign.id FROM Encounter e WHERE e.id = :encounterId")
    Optional<UUID> findCampaignIdById(@Param("encounterId") UUID encounterId);

    /**
     * Loads the encounter under a pessimistic write lock held until the transaction ends, so commands on
     * one encounter run one after another and each starts from the last one's committed state (D160).
     * Check access first, so no row is locked for a caller who may not act; {@link EncounterCommands} does
     * both.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("SELECT e FROM Encounter e WHERE e.id = :encounterId")
    Optional<Encounter> lockById(@Param("encounterId") UUID encounterId);

    /**
     * Limits every lock wait in the current transaction to five seconds, after which the waiting statement
     * fails with a PessimisticLockingFailureException. lockById's hint can't do this on PostgreSQL:
     * Hibernate's dialect renders only NOWAIT and SKIP LOCKED, so it leaves a timeout out of the SQL and the
     * wait would never end.
     */
    @Query(value = "SELECT set_config('lock_timeout', '5000ms', true)", nativeQuery = true)
    String limitLockWaits();
}
