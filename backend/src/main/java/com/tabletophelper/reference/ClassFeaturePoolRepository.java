package com.tabletophelper.reference;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ClassFeaturePoolRepository extends JpaRepository<ClassFeaturePool, UUID> {

    List<ClassFeaturePool> findByCharacterClassId(UUID classId);

    List<ClassFeaturePool> findByCharacterClassIdAndMinLevelLessThanEqual(UUID classId, int level);

    /** Base class pools only — no subclass requirement. */
    List<ClassFeaturePool> findByCharacterClassIdAndSubclassIsNullAndMinLevelLessThanEqual(UUID classId, int level);

    /**
     * Finds class feature pools for a character — both base class pools
     * (subclass IS NULL) and subclass-specific pools matching the given subclass.
     */
    @Query("""
        SELECT cfp FROM ClassFeaturePool cfp
        WHERE cfp.characterClass.id = :classId
          AND cfp.minLevel <= :level
          AND (cfp.subclass IS NULL OR cfp.subclass.id = :subclassId)
        """)
    List<ClassFeaturePool> findByClassIdAndLevelAndSubclass(
            @Param("classId") UUID classId,
            @Param("level") int level,
            @Param("subclassId") UUID subclassId);
}
