package com.tabletophelper.reference;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface RaceRepository extends JpaRepository<Race, UUID> {

    List<Race> findAllByOrderByNameAsc();

    List<Race> findBySourceOrderByNameAsc(String source);

    @Query("SELECT DISTINCT r.source FROM Race r ORDER BY r.source")
    List<String> findDistinctSources();
}
