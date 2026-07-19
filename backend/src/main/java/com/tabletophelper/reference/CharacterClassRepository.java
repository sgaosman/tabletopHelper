package com.tabletophelper.reference;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CharacterClassRepository extends JpaRepository<CharacterClass, UUID> {

    List<CharacterClass> findAllByOrderByNameAsc();

    Optional<CharacterClass> findByNameAndSource(String name, String source);
}
