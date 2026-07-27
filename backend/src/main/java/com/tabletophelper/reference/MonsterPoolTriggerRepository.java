package com.tabletophelper.reference;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MonsterPoolTriggerRepository extends JpaRepository<MonsterPoolTrigger, UUID> {

    List<MonsterPoolTrigger> findAllByOrderByPriorityAsc();
}
