package com.tabletophelper.character;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CharacterRepository extends JpaRepository<PlayerCharacter, UUID> {

    @EntityGraph(attributePaths = {"user", "campaign", "raceRef", "classRef", "subclassRef", "backgroundRef"})
    List<PlayerCharacter> findByUserIdAndIsActiveTrue(UUID userId);

    @EntityGraph(attributePaths = {"user", "campaign", "raceRef", "classRef", "subclassRef", "backgroundRef"})
    List<PlayerCharacter> findByCampaignIdAndIsActiveTrue(UUID campaignId);

    // Reads only the owner's ID, so a caller who may not change the character never locks its row
    @Query("SELECT c.user.id FROM PlayerCharacter c WHERE c.id = :characterId")
    Optional<UUID> findOwnerIdById(@Param("characterId") UUID characterId);

    /**
     * Loads the character under a pessimistic write lock held until the transaction ends, so commands on one
     * character run one after another and each starts from the last one's committed state (D158). Check access
     * first, so no row is locked for a caller who may not change it; {@link CharacterCommands} does both.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("SELECT c FROM PlayerCharacter c WHERE c.id = :characterId")
    Optional<PlayerCharacter> lockById(@Param("characterId") UUID characterId);

    /**
     * Limits every lock wait in the current transaction to five seconds, after which the waiting statement fails with
     * a PessimisticLockingFailureException. lockById's hint can't do this on PostgreSQL: Hibernate's dialect renders
     * only NOWAIT and SKIP LOCKED, so it leaves a timeout out of the SQL and the wait would never end.
     */
    @Query(value = "SELECT set_config('lock_timeout', '5000ms', true)", nativeQuery = true)
    String limitLockWaits();
}
