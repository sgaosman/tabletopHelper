package com.questkeeper.monster;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface MonsterRepository extends JpaRepository<Monster, UUID> {

    @Query(value = "SELECT * FROM monsters m WHERE " +
           "(CAST(:name AS TEXT) IS NULL OR LOWER(m.name) LIKE LOWER('%' || CAST(:name AS TEXT) || '%')) AND " +
           "(:typeCount = 0 OR LOWER(m.type) IN (:typeList)) AND " +
           "(:crCount = 0 OR m.challenge_rating IN (:crList)) AND " +
           "(:sourceCount = 0 OR m.source IN (:sourceList))",
           countQuery = "SELECT COUNT(*) FROM monsters m WHERE " +
           "(CAST(:name AS TEXT) IS NULL OR LOWER(m.name) LIKE LOWER('%' || CAST(:name AS TEXT) || '%')) AND " +
           "(:typeCount = 0 OR LOWER(m.type) IN (:typeList)) AND " +
           "(:crCount = 0 OR m.challenge_rating IN (:crList)) AND " +
           "(:sourceCount = 0 OR m.source IN (:sourceList))",
           nativeQuery = true)
    Page<Monster> searchMonsters(
            @Param("name") String name,
            @Param("typeCount") int typeCount,
            @Param("typeList") List<String> typeList,
            @Param("crCount") int crCount,
            @Param("crList") List<String> crList,
            @Param("sourceCount") int sourceCount,
            @Param("sourceList") List<String> sourceList,
            Pageable pageable);

    @Query("SELECT DISTINCT m.source FROM Monster m ORDER BY m.source")
    java.util.List<String> findDistinctSources();

    @Query("SELECT DISTINCT m.type FROM Monster m WHERE m.type IS NOT NULL ORDER BY m.type")
    java.util.List<String> findDistinctTypes();

    @Query("SELECT DISTINCT m.challengeRating FROM Monster m WHERE m.challengeRating IS NOT NULL ORDER BY m.challengeRating")
    java.util.List<String> findDistinctChallengeRatings();
}
