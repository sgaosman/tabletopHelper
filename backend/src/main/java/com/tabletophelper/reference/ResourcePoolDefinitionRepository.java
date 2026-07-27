package com.tabletophelper.reference;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ResourcePoolDefinitionRepository extends JpaRepository<ResourcePoolDefinition, String> {

    List<ResourcePoolDefinition> findBySourceType(String sourceType);
}
