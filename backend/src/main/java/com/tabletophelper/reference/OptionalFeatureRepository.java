package com.tabletophelper.reference;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OptionalFeatureRepository extends JpaRepository<OptionalFeature, UUID> {

    List<OptionalFeature> findByFeatureTypeOrderByNameAsc(String featureType);

    List<OptionalFeature> findByFeatureTypeInOrderByNameAsc(List<String> featureTypes);
}
