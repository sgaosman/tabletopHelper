package com.tabletophelper.reference;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ItemRepository extends JpaRepository<Item, UUID> {

    @Query(value = "SELECT * FROM items i WHERE " +
           "(CAST(:name AS TEXT) IS NULL OR LOWER(i.name) LIKE LOWER('%' || CAST(:name AS TEXT) || '%')) AND " +
           "(:typeCount = 0 OR i.type IN (:typeList)) AND " +
           "(:rarityCount = 0 OR i.rarity IN (:rarityList)) AND " +
           "(:sourceCount = 0 OR i.source IN (:sourceList))",
           countQuery = "SELECT COUNT(*) FROM items i WHERE " +
           "(CAST(:name AS TEXT) IS NULL OR LOWER(i.name) LIKE LOWER('%' || CAST(:name AS TEXT) || '%')) AND " +
           "(:typeCount = 0 OR i.type IN (:typeList)) AND " +
           "(:rarityCount = 0 OR i.rarity IN (:rarityList)) AND " +
           "(:sourceCount = 0 OR i.source IN (:sourceList))",
           nativeQuery = true)
    Page<Item> searchItems(
            @Param("name") String name,
            @Param("typeCount") int typeCount,
            @Param("typeList") List<String> typeList,
            @Param("rarityCount") int rarityCount,
            @Param("rarityList") List<String> rarityList,
            @Param("sourceCount") int sourceCount,
            @Param("sourceList") List<String> sourceList,
            Pageable pageable);

    /**
     * The mundane weapons a character can carry and attack with (D164): every item with a weapon category and damage,
     * and no rarity, or 5etools' "unknown" for mundane adventure gear. That is the base weapons, less the net, which
     * deals no damage, and two mundane weapons from adventures. Their names are unique.
     */
    @Query("SELECT i FROM Item i WHERE i.subtype IN ('simple', 'martial') AND i.damageDice IS NOT NULL "
            + "AND (i.rarity IS NULL OR i.rarity = 'unknown')")
    List<Item> findMundaneWeapons();

    /**
     * The armour and shields with one of these names, lower case (M13.5): an inventory entry not linked to an item is
     * matched to its armour by name.
     */
    @Query("SELECT i FROM Item i WHERE i.armourCategory IS NOT NULL AND LOWER(i.name) IN :names")
    List<Item> findArmourByLowerNames(@Param("names") Collection<String> names);

    @Query("SELECT DISTINCT i.type FROM Item i WHERE i.type IS NOT NULL ORDER BY i.type")
    List<String> findDistinctTypes();

    @Query("SELECT DISTINCT i.rarity FROM Item i WHERE i.rarity IS NOT NULL AND i.rarity <> 'none' ORDER BY i.rarity")
    List<String> findDistinctRarities();

    @Query("SELECT DISTINCT i.source FROM Item i ORDER BY i.source")
    List<String> findDistinctSources();
}
