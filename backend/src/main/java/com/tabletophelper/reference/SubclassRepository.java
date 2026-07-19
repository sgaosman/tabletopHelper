package com.tabletophelper.reference;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SubclassRepository extends JpaRepository<Subclass, UUID> {

    List<Subclass> findByCharacterClassIdOrderByNameAsc(UUID characterClassId);
}
