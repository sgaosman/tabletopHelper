package com.questkeeper.reference;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ItemRepository extends JpaRepository<Item, UUID> {

    @Query(value = "SELECT * FROM items i WHERE " +
           "(CAST(:name AS TEXT) IS NULL OR LOWER(i.name) LIKE LOWER('%' || CAST(:name AS TEXT) || '%')) AND " +
           "(CAST(:type AS TEXT) IS NULL OR i.type = CAST(:type AS TEXT)) AND " +
           "(CAST(:rarity AS TEXT) IS NULL OR i.rarity = CAST(:rarity AS TEXT)) AND " +
           "(CAST(:source AS TEXT) IS NULL OR i.source = CAST(:source AS TEXT))",
           countQuery = "SELECT COUNT(*) FROM items i WHERE " +
           "(CAST(:name AS TEXT) IS NULL OR LOWER(i.name) LIKE LOWER('%' || CAST(:name AS TEXT) || '%')) AND " +
           "(CAST(:type AS TEXT) IS NULL OR i.type = CAST(:type AS TEXT)) AND " +
           "(CAST(:rarity AS TEXT) IS NULL OR i.rarity = CAST(:rarity AS TEXT)) AND " +
           "(CAST(:source AS TEXT) IS NULL OR i.source = CAST(:source AS TEXT))",
           nativeQuery = true)
    Page<Item> searchItems(
            @Param("name") String name,
            @Param("type") String type,
            @Param("rarity") String rarity,
            @Param("source") String source,
            Pageable pageable);

    @Query("SELECT DISTINCT i.type FROM Item i WHERE i.type IS NOT NULL ORDER BY i.type")
    List<String> findDistinctTypes();

    @Query("SELECT DISTINCT i.rarity FROM Item i WHERE i.rarity IS NOT NULL AND i.rarity <> 'none' ORDER BY i.rarity")
    List<String> findDistinctRarities();

    @Query("SELECT DISTINCT i.source FROM Item i ORDER BY i.source")
    List<String> findDistinctSources();
}
