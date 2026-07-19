package com.tabletophelper.reference;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BackgroundRepository extends JpaRepository<Background, UUID> {

    List<Background> findAllByOrderByNameAsc();
}
