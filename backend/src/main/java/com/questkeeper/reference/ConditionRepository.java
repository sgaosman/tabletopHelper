package com.questkeeper.reference;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ConditionRepository extends JpaRepository<Condition, UUID> {
}
