package com.tabletophelper.reference;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FeatRepository extends JpaRepository<Feat, UUID> {

    List<Feat> findAllByOrderByNameAsc();
}
