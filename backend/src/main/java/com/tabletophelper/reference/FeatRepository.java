package com.tabletophelper.reference;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FeatRepository extends JpaRepository<Feat, UUID> {

    List<Feat> findAllByOrderByNameAsc();

    // Feat names are unique whatever their case; a background lists its feats as "Magic initiate"
    Optional<Feat> findByNameIgnoreCase(String name);
}
