package com.tabletophelper.reference;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RaceFeaturePoolRepository extends JpaRepository<RaceFeaturePool, UUID> {

    /** The pools the race row grants a character of this total level or higher. */
    List<RaceFeaturePool> findByRaceIdAndMinLevelLessThanEqual(UUID raceId, int level);

    /** Every pool the race row grants, at any level. */
    List<RaceFeaturePool> findByRaceId(UUID raceId);
}
