package com.tabletophelper.reference;

import com.tabletophelper.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ClassFeaturePoolRepositoryIT extends IntegrationTest {

    @Autowired private ClassFeaturePoolRepository classFeaturePoolRepository;
    @Autowired private SubclassRepository subclassRepository;
    @Autowired private ResourcePoolDefinitionRepository poolDefinitionRepository;

    private CharacterClass fighter;
    private Subclass battleMaster;

    @BeforeEach
    void insertFighterPools() {
        fighter = characterClassRepository.save(CharacterClass.builder().name("Fighter").source("PHB").hitDice(10).build());
        battleMaster = insertSubclass("Battle Master");
        Subclass echoKnight = insertSubclass("Echo Knight");

        insertPool("class:fighter-second-wind", 1, null);
        insertPool("class:fighter-action-surge", 2, null);
        insertPool("class:fighter-indomitable", 9, null);
        insertPool("subclass:battle-master-superiority-dice", 3, battleMaster);
        insertPool("subclass:echo-knight-unleash-incarnation", 3, echoKnight);
    }

    private Subclass insertSubclass(String name) {
        return subclassRepository.save(Subclass.builder().name(name).source("PHB").characterClass(fighter).build());
    }

    private void insertPool(String poolId, int minLevel, Subclass subclass) {
        ResourcePoolDefinition definition = poolDefinitionRepository.save(ResourcePoolDefinition.builder()
                .poolId(poolId).displayName(poolId).sourceType("CLASS").maxUsesFormula("1").resetOn("shortRest")
                .build());
        classFeaturePoolRepository.save(ClassFeaturePool.builder()
                .characterClass(fighter).subclass(subclass).minLevel(minLevel).poolDefinition(definition)
                .build());
    }

    private List<String> poolIds(int level, UUID subclassId) {
        return classFeaturePoolRepository.findByClassIdAndLevelAndSubclass(fighter.getId(), level, subclassId).stream()
                .map(pool -> pool.getPoolDefinition().getPoolId())
                .toList();
    }

    @Test
    @DisplayName("findByClassIdAndLevelAndSubclass returns base pools up to the level plus that subclass's pools only")
    void returnsBaseAndOwnSubclassPools() {
        assertThat(poolIds(3, battleMaster.getId())).containsExactlyInAnyOrder(
                "class:fighter-second-wind", "class:fighter-action-surge", "subclass:battle-master-superiority-dice");
    }

    @Test
    @DisplayName("findByClassIdAndLevelAndSubclass with no subclass returns only the base pools up to the level")
    void noSubclassReturnsBasePools() {
        assertThat(poolIds(9, null)).containsExactlyInAnyOrder(
                "class:fighter-second-wind", "class:fighter-action-surge", "class:fighter-indomitable");
    }
}
