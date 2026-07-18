package com.questkeeper.monster;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface MonsterRepository extends JpaRepository<Monster, UUID> {

    @Query(value = "SELECT * FROM monsters m WHERE " +
           "(CAST(:name AS TEXT) IS NULL OR LOWER(m.name) LIKE LOWER('%' || CAST(:name AS TEXT) || '%')) AND " +
           "(CAST(:type AS TEXT) IS NULL OR LOWER(m.type) LIKE LOWER('%' || CAST(:type AS TEXT) || '%')) AND " +
           "(CAST(:cr AS TEXT) IS NULL OR m.challenge_rating = CAST(:cr AS TEXT)) AND " +
           "(CAST(:source AS TEXT) IS NULL OR m.source = CAST(:source AS TEXT))",
           countQuery = "SELECT COUNT(*) FROM monsters m WHERE " +
           "(CAST(:name AS TEXT) IS NULL OR LOWER(m.name) LIKE LOWER('%' || CAST(:name AS TEXT) || '%')) AND " +
           "(CAST(:type AS TEXT) IS NULL OR LOWER(m.type) LIKE LOWER('%' || CAST(:type AS TEXT) || '%')) AND " +
           "(CAST(:cr AS TEXT) IS NULL OR m.challenge_rating = CAST(:cr AS TEXT)) AND " +
           "(CAST(:source AS TEXT) IS NULL OR m.source = CAST(:source AS TEXT))",
           nativeQuery = true)
    Page<Monster> searchMonsters(
            @Param("name") String name,
            @Param("type") String type,
            @Param("cr") String cr,
            @Param("source") String source,
            Pageable pageable);

    @Query("SELECT DISTINCT m.source FROM Monster m ORDER BY m.source")
    java.util.List<String> findDistinctSources();

    @Query("SELECT DISTINCT m.type FROM Monster m WHERE m.type IS NOT NULL ORDER BY m.type")
    java.util.List<String> findDistinctTypes();

    @Query("SELECT DISTINCT m.challengeRating FROM Monster m WHERE m.challengeRating IS NOT NULL ORDER BY m.challengeRating")
    java.util.List<String> findDistinctChallengeRatings();
}
