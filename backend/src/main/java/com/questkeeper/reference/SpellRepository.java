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
           "(CAST(:level AS INTEGER) IS NULL OR s.level = CAST(:level AS INTEGER)) AND " +
           "(CAST(:school AS TEXT) IS NULL OR s.school IN (SELECT unnest(string_to_array(CAST(:school AS TEXT), ',')))) AND " +
           "(CAST(:source AS TEXT) IS NULL OR s.source IN (SELECT unnest(string_to_array(CAST(:source AS TEXT), ',')))) AND " +
           "(CAST(:className AS TEXT) IS NULL OR " +
           "  s.classes::jsonb @> to_jsonb(CAST(:className AS TEXT)) OR " +
           "  (CAST(:subclass AS TEXT) IS NOT NULL AND s.classes::jsonb @> to_jsonb(CAST(:subclass AS TEXT)))) AND " +
           "(CAST(:conc AS TEXT) IS NULL OR s.concentration = CAST(CAST(:conc AS TEXT) AS BOOLEAN)) AND " +
           "(CAST(:ritual AS TEXT) IS NULL OR s.ritual = CAST(CAST(:ritual AS TEXT) AS BOOLEAN))",
           countQuery = "SELECT COUNT(*) FROM spells s WHERE " +
           "(CAST(:name AS TEXT) IS NULL OR LOWER(s.name) LIKE LOWER('%' || CAST(:name AS TEXT) || '%')) AND " +
           "(CAST(:level AS INTEGER) IS NULL OR s.level = CAST(:level AS INTEGER)) AND " +
           "(CAST(:school AS TEXT) IS NULL OR s.school IN (SELECT unnest(string_to_array(CAST(:school AS TEXT), ',')))) AND " +
           "(CAST(:source AS TEXT) IS NULL OR s.source IN (SELECT unnest(string_to_array(CAST(:source AS TEXT), ',')))) AND " +
           "(CAST(:className AS TEXT) IS NULL OR " +
           "  s.classes::jsonb @> to_jsonb(CAST(:className AS TEXT)) OR " +
           "  (CAST(:subclass AS TEXT) IS NOT NULL AND s.classes::jsonb @> to_jsonb(CAST(:subclass AS TEXT)))) AND " +
           "(CAST(:conc AS TEXT) IS NULL OR s.concentration = CAST(CAST(:conc AS TEXT) AS BOOLEAN)) AND " +
           "(CAST(:ritual AS TEXT) IS NULL OR s.ritual = CAST(CAST(:ritual AS TEXT) AS BOOLEAN))",
           nativeQuery = true)
    Page<Spell> searchSpells(
            @Param("name") String name,
            @Param("level") Integer level,
            @Param("school") String school,
            @Param("source") String source,
            @Param("className") String className,
            @Param("subclass") String subclass,
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
