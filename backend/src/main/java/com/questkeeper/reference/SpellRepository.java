package com.questkeeper.reference;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SpellRepository extends JpaRepository<Spell, UUID> {

    @Query(value = "SELECT * FROM spells s WHERE " +
           "(CAST(:name AS TEXT) IS NULL OR LOWER(s.name) LIKE LOWER('%' || CAST(:name AS TEXT) || '%')) AND " +
           "(:levelCount = 0 OR s.level IN (:levelList)) AND " +
           "(:schoolCount = 0 OR s.school IN (:schoolList)) AND " +
           "(:sourceCount = 0 OR s.source IN (:sourceList)) AND " +
           "(:classCount = 0 OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(s.classes) AS c WHERE c IN (:classList))) AND " +
           "(CAST(:conc AS TEXT) IS NULL OR s.concentration = CAST(CAST(:conc AS TEXT) AS BOOLEAN)) AND " +
           "(CAST(:ritual AS TEXT) IS NULL OR s.ritual = CAST(CAST(:ritual AS TEXT) AS BOOLEAN))",
           countQuery = "SELECT COUNT(*) FROM spells s WHERE " +
           "(CAST(:name AS TEXT) IS NULL OR LOWER(s.name) LIKE LOWER('%' || CAST(:name AS TEXT) || '%')) AND " +
           "(:levelCount = 0 OR s.level IN (:levelList)) AND " +
           "(:schoolCount = 0 OR s.school IN (:schoolList)) AND " +
           "(:sourceCount = 0 OR s.source IN (:sourceList)) AND " +
           "(:classCount = 0 OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(s.classes) AS c WHERE c IN (:classList))) AND " +
           "(CAST(:conc AS TEXT) IS NULL OR s.concentration = CAST(CAST(:conc AS TEXT) AS BOOLEAN)) AND " +
           "(CAST(:ritual AS TEXT) IS NULL OR s.ritual = CAST(CAST(:ritual AS TEXT) AS BOOLEAN))",
           nativeQuery = true)
    Page<Spell> searchSpells(
            @Param("name") String name,
            @Param("levelCount") int levelCount,
            @Param("levelList") List<Integer> levelList,
            @Param("schoolCount") int schoolCount,
            @Param("schoolList") List<String> schoolList,
            @Param("sourceCount") int sourceCount,
            @Param("sourceList") List<String> sourceList,
            @Param("classCount") int classCount,
            @Param("classList") List<String> classList,
            @Param("conc") String concentration,
            @Param("ritual") String ritual,
            Pageable pageable);

    @Query("SELECT DISTINCT s.school FROM Spell s WHERE s.school IS NOT NULL ORDER BY s.school")
    List<String> findDistinctSchools();

    @Query("SELECT DISTINCT s.source FROM Spell s ORDER BY s.source")
    List<String> findDistinctSources();

    @Query(value = "SELECT DISTINCT val FROM spells, jsonb_array_elements_text(classes) AS val " +
           "WHERE val NOT LIKE '% (%' ORDER BY val",
           nativeQuery = true)
    List<String> findDistinctClasses();

    @Query(value = "SELECT DISTINCT val FROM spells, jsonb_array_elements_text(classes) AS val " +
           "WHERE val LIKE CAST(:className AS TEXT) || ' (%' ORDER BY val",
           nativeQuery = true)
    List<String> findDistinctSubclasses(@Param("className") String className);
}
