package com.tabletophelper.maintenance;

import com.tabletophelper.character.CharacterRepository;
import com.tabletophelper.character.CharacterService;
import com.tabletophelper.character.FeatEligibility;
import com.tabletophelper.character.PendingChoiceService;
import com.tabletophelper.character.DerivedStats;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.dto.AbilityScoreMaximums;
import com.tabletophelper.character.state.DmAdjustment;
import com.tabletophelper.character.state.HitDicePool;
import com.tabletophelper.character.state.ProficienciesGained;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.shared.ConflictException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The recompute-characters job (D167): it compares each character's stored Derived Stats with what CharacterService
 * works out, reports each one that would change with each stat before and after, and writes only with apply.
 */
@ExtendWith(MockitoExtension.class)
class RecomputeCharactersJobTest {

    @Mock CharacterRepository characterRepository;
    @Mock CharacterService characterService;
    @Mock FeatEligibility featEligibility;

    private static final ResourcePoolEntry RAGE = new ResourcePoolEntry("class:barbarian-rage", "Rage", "CLASS", null,
            3, "3", 2, "longRest", null, null, null, null, Map.of());

    private RecomputeCharactersJob job() {
        return new RecomputeCharactersJob(characterRepository, characterService, featEligibility);
    }

    // A Barbarian 6 as stored: 65 hit points, +2 initiative, Rage only
    private static PlayerCharacter barbarian(String name) {
        return PlayerCharacter.builder().id(UUID.randomUUID()).name(name).characterClass("Barbarian").level(6)
                .isActive(false).hpMax(65).hpCurrent(65).proficiencyBonus(3).initiativeBonus(2).speed(30).armourClass(12)
                .hitDice(Map.of("Barbarian", new HitDicePool(12, 6, 6))).resourcePools(List.of(RAGE))
                .build();
    }

    // What the rules give it: the stored stats unless changed
    private static DerivedStats derived(int hpMax, int initiative, List<ResourcePoolEntry> pools, List<String> warnings) {
        return new DerivedStats(3, hpMax, initiative, 30, 12, Map.of(), new AbilityScoreMaximums(20, 20, 20, 20, 20, 20),
                null, Map.of("Barbarian", new HitDicePool(12, 6, 6)), List.of(), null, null, null, pools, warnings);
    }

    @Test
    @DisplayName("A dry run reports each stat that would change, before and after, and writes nothing")
    void dryRunReportsChanges() {
        PlayerCharacter wild = barbarian("TestBarb");
        PlayerCharacter settled = barbarian("asdasd");
        when(characterRepository.findAll()).thenReturn(List.of(wild, settled));
        ResourcePoolEntry innate = ResourcePoolEntry.innate("Faerie Fire", 1, "RACE", "Fairy");
        when(characterService.derivedStats(wild)).thenReturn(derived(65, 3, List.of(RAGE, innate), List.of()));
        when(characterService.derivedStats(settled)).thenReturn(derived(65, 2, List.of(RAGE), List.of()));

        MaintenanceReport report = job().run(false);

        assertThat(report.applied()).isFalse();
        assertThat(report.examined()).isEqualTo(2);
        assertThat(report.changes()).singleElement().asString()
                .startsWith("TestBarb (" + wild.getId() + ", level 6 Barbarian, deleted)")
                .contains("initiative: +2 → +3")
                .contains("pool innate:faerie-fire: none → 1/1 longRest")
                .doesNotContain("hit points");
        assertThat(report.render()).contains("Examined 2 characters; 1 would change.");
        verify(characterService, never()).recalculate(any());
        verify(characterRepository, never()).save(any());
    }

    @Test
    @DisplayName("A higher hit point maximum is reported with current hit points raised by as much")
    void hitPointsBeforeAndAfter() {
        PlayerCharacter hurt = barbarian("Grog");
        hurt.setHpCurrent(40);
        when(characterRepository.findAll()).thenReturn(List.of(hurt));
        when(characterService.derivedStats(hurt)).thenReturn(derived(71, 2, List.of(RAGE), List.of()));

        MaintenanceReport report = job().run(false);

        assertThat(report.changes()).singleElement().asString().contains("hit points: 40/65 → 46/71");
    }

    @Test
    @DisplayName("An armour class the armour worn changes is reported before and after, and an AC adjustment whose reason names armour is a warning, by ID (M13.5)")
    void armourClass() {
        PlayerCharacter knight = barbarian("Sir Plate");
        DmAdjustment plate = acAdjustment(6, "Plate armour", null);
        DmAdjustment ring = acAdjustment(1, "Ring of Protection", null);
        DmAdjustment removedShield = acAdjustment(2, "Shield", Instant.parse("2026-11-04T10:00:00Z"));
        knight.setDmAdjustments(List.of(plate, ring, removedShield));
        when(characterRepository.findAll()).thenReturn(List.of(knight));
        DerivedStats armoured = new DerivedStats(3, 65, 2, 30, 18, Map.of(), new AbilityScoreMaximums(20, 20, 20, 20,
                20, 20), null, Map.of("Barbarian", new HitDicePool(12, 6, 6)), List.of(), null, null, null,
                List.of(RAGE), List.of("has Leather Armor equipped too; only the first armour and shield in the "
                        + "inventory count"));
        when(characterService.derivedStats(knight)).thenReturn(armoured);

        MaintenanceReport report = job().run(false);

        assertThat(report.changes()).singleElement().asString().contains("armour class: 12 → 18");
        String who = "Sir Plate (" + knight.getId() + ", level 6 Barbarian, deleted)";
        assertThat(report.warnings()).containsExactly(
                who + ": has Leather Armor equipped too; only the first armour and shield in the inventory count; "
                        + "left unchanged",
                who + ": has an AC adjustment of +6 (" + plate.id() + ") whose reason names armour or the Defense "
                        + "style, which the armour class now counts, so it may count twice; kept");
    }

    private static DmAdjustment acAdjustment(int amount, String reason, Instant removedAt) {
        return new DmAdjustment(UUID.randomUUID(), DmAdjustment.Kind.STAT, "ARMOUR_CLASS", amount, null, null, null,
                reason, UUID.randomUUID(), Instant.parse("2026-11-03T10:00:00Z"), removedAt != null
                ? UUID.randomUUID() : null, removedAt);
    }

    @Test
    @DisplayName("A dead character's dry run shows it staying at 0 hit points when its maximum rises, as the real run does")
    void theDeadStayAtZero() {
        PlayerCharacter fallen = barbarian("Grog");
        fallen.setHpCurrent(0);
        fallen.setDead(true);
        when(characterRepository.findAll()).thenReturn(List.of(fallen));
        when(characterService.derivedStats(fallen)).thenReturn(derived(71, 2, List.of(RAGE), List.of()));

        MaintenanceReport report = job().run(false);

        assertThat(report.changes()).singleElement().asString().contains("hit points: 0/65 → 0/71");
    }

    @Test
    @DisplayName("A character whose derivation fails in any way is skipped with a warning naming the failure, and the others are still reported")
    void anyFailureSkipsOnlyThatCharacter() {
        PlayerCharacter broken = barbarian("Broken");
        PlayerCharacter hurt = barbarian("Grog");
        hurt.setHpCurrent(40);
        when(characterRepository.findAll()).thenReturn(List.of(broken, hurt));
        when(characterService.derivedStats(broken)).thenThrow(new IllegalStateException("No class data"));
        when(characterService.derivedStats(hurt)).thenReturn(derived(71, 2, List.of(RAGE), List.of()));

        MaintenanceReport report = job().run(true);

        assertThat(report.warnings()).containsExactly("Broken (" + broken.getId()
                + ", level 6 Barbarian, deleted): skipped (IllegalStateException: No class data)");
        assertThat(report.changes()).singleElement().asString().contains("hit points: 40/65 → 46/71");
        verify(characterService, never()).recalculate(broken);
        verify(characterService).recalculate(hurt);
    }

    @Test
    @DisplayName("What the sheet has beyond the rules is a warning, and a sheet that can't be worked out is skipped")
    void warnings() {
        PlayerCharacter cleric = barbarian("TestPeace");
        PlayerCharacter broken = barbarian("Broken");
        when(characterRepository.findAll()).thenReturn(List.of(cleric, broken));
        when(characterService.derivedStats(cleric)).thenReturn(derived(65, 2, List.of(RAGE),
                List.of("knows 5 Cleric cantrips, but a level 9 Cleric knows 4")));
        when(characterService.derivedStats(broken)).thenThrow(new ConflictException("Broken has no level history"));

        MaintenanceReport report = job().run(false);

        assertThat(report.changes()).isEmpty();
        assertThat(report.warnings()).containsExactly(
                "Broken (" + broken.getId() + ", level 6 Barbarian, deleted): skipped (Broken has no level history)",
                "TestPeace (" + cleric.getId() + ", level 6 Barbarian, deleted): knows 5 Cleric cantrips, but a level 9 "
                        + "Cleric knows 4; left unchanged");
    }

    @Test
    @DisplayName("Apply works the changed characters out again and saves them, and leaves the rest alone")
    void applyWritesChangedCharacters() {
        PlayerCharacter wild = barbarian("TestBarb");
        PlayerCharacter settled = barbarian("asdasd");
        wild.setSpellSlots(Map.of("1", new SpellSlot(2, 0)));
        when(characterRepository.findAll()).thenReturn(List.of(wild, settled));
        when(characterService.derivedStats(wild)).thenReturn(derived(65, 2, List.of(RAGE), List.of()));
        when(characterService.derivedStats(settled)).thenReturn(derived(65, 2, List.of(RAGE), List.of()));

        MaintenanceReport report = job().run(true);

        assertThat(report.applied()).isTrue();
        assertThat(report.changes()).singleElement().asString().contains("slots 1: 2/2 → none");
        verify(characterService).recalculate(wild);
        verify(characterRepository).save(wild);
        verify(characterService, never()).recalculate(settled);
    }

    @Test
    @DisplayName("A character with no stored spellcasting ability whose classes cast gains its first casting class's, "
            + "reported with the level it is recorded on, or why it isn't, and stored only with apply (D159)")
    void backfillsTheSpellcastingAbility() {
        PlayerCharacter recorded = barbarian("Multi");
        PlayerCharacter unrecorded = barbarian("Old");
        when(characterRepository.findAll()).thenReturn(List.of(recorded, unrecorded));
        when(characterService.derivedStats(any())).thenReturn(derived(65, 2, List.of(RAGE), List.of()));
        when(characterService.missingSpellcastingAbility(recorded)).thenReturn(Optional.of(
                new CharacterService.MissingSpellcastingAbility("INT", "Wizard", 1, true)));
        when(characterService.missingSpellcastingAbility(unrecorded)).thenReturn(Optional.of(
                new CharacterService.MissingSpellcastingAbility("WIS", "Cleric", 1, false)));

        MaintenanceReport dryRun = job().run(false);

        assertThat(dryRun.changes()).hasSize(2);
        assertThat(dryRun.changes().get(0)).contains("spellcasting ability: none → INT (Wizard's, recorded on Wizard 1)");
        assertThat(dryRun.changes().get(1)).contains("spellcasting ability: none → WIS (Cleric's, not recorded: the "
                + "level history has no Cleric 1, so levelling down won't clear it)");
        verify(characterService, never()).storeMissingSpellcastingAbility(any());

        job().run(true);

        verify(characterService).storeMissingSpellcastingAbility(recorded);
        verify(characterService).storeMissingSpellcastingAbility(unrecorded);
        verify(characterRepository).save(recorded);
    }

    @Test
    @DisplayName("A character whose subclass grants bonus proficiencies its sheet lacks gains them, reported with the "
            + "level they are recorded on, or why they aren't, and stored only with apply")
    void backfillsSubclassProficiencies() {
        PlayerCharacter life = barbarian("Tam");
        PlayerCharacter old = barbarian("Old");
        PlayerCharacter none = barbarian("None");
        when(characterRepository.findAll()).thenReturn(List.of(none, old, life));
        when(characterService.derivedStats(any())).thenReturn(derived(65, 2, List.of(RAGE), List.of()));
        ProficienciesGained heavy = new ProficienciesGained(List.of("heavy"), null, null);
        ProficienciesGained martial = new ProficienciesGained(List.of("heavy"), List.of("martial"), null);
        when(characterService.missingSubclassProficiencies(life)).thenReturn(List.of(
                new PendingChoiceService.MissingSubclassProficiencies("Life Domain", heavy, "armour heavy", "Cleric",
                        1, 0)));
        when(characterService.missingSubclassProficiencies(old)).thenReturn(List.of(
                new PendingChoiceService.MissingSubclassProficiencies("War Domain", martial,
                        "armour heavy; weapons martial", null, 0, -1)));

        MaintenanceReport dryRun = job().run(false);

        assertThat(dryRun.changes()).hasSize(2);
        assertThat(dryRun.changes().get(0)).startsWith("Old (")
                .contains("subclass proficiencies: + armour heavy; weapons martial (War Domain, not recorded: no level "
                        + "records the subclass, so levelling down won't remove them)");
        assertThat(dryRun.changes().get(1)).startsWith("Tam (")
                .contains("subclass proficiencies: + armour heavy (Life Domain, recorded on Cleric 1)");
        verify(characterService, never()).storeMissingSubclassProficiencies(any());
        verify(characterRepository, never()).save(any());

        job().run(true);

        verify(characterService).storeMissingSubclassProficiencies(life);
        verify(characterService).storeMissingSubclassProficiencies(old);
        verify(characterService, never()).storeMissingSubclassProficiencies(none);
        verify(characterRepository).save(life);
        verify(characterRepository, never()).save(none);
    }

    @Test
    @DisplayName("A subclass that can't be found is a warning, and the character's other changes go ahead")
    void subclassProficienciesSkipped() {
        PlayerCharacter lost = barbarian("Lost");
        when(characterRepository.findAll()).thenReturn(List.of(lost));
        when(characterService.derivedStats(lost)).thenReturn(derived(65, 3, List.of(RAGE), List.of()));
        when(characterService.missingSubclassProficiencies(lost))
                .thenThrow(new ConflictException("The subclass Gone of Lost's Cleric can't be found"));

        MaintenanceReport report = job().run(false);

        assertThat(report.warnings()).containsExactly("Lost (" + lost.getId() + ", level 6 Barbarian, deleted): "
                + "subclass proficiencies skipped (The subclass Gone of Lost's Cleric can't be found)");
        assertThat(report.changes()).singleElement().asString().contains("initiative: +2 → +3");
    }

    @Test
    @DisplayName("A class or subclass feature whose text the class data now gives differently, as the Life Domain's "
            + "with Disciple of Life nested in it (PHB p.60), is reported with both lengths and takes the data's text "
            + "only with apply")
    void refreshesFeatureTexts() {
        PlayerCharacter tam = barbarian("Tam");
        PlayerCharacter settled = barbarian("Settled");
        when(characterRepository.findAll()).thenReturn(List.of(settled, tam));
        when(characterService.derivedStats(any())).thenReturn(derived(65, 2, List.of(RAGE), List.of()));
        when(characterService.outdatedFeatureTexts(tam)).thenReturn(List.of(
                new CharacterService.OutdatedFeatureText("Life Domain", "Life Domain", 1203, 1645)));

        MaintenanceReport dryRun = job().run(false);

        assertThat(dryRun.changes()).singleElement().asString().startsWith("Tam (")
                .contains("feature text: Life Domain (Life Domain), 1203 → 1645 characters");
        verify(characterService, never()).storeFeatureTexts(any());
        verify(characterRepository, never()).save(any());

        job().run(true);

        verify(characterService).storeFeatureTexts(tam);
        verify(characterService, never()).storeFeatureTexts(settled);
        verify(characterRepository).save(tam);
        verify(characterRepository, never()).save(settled);
    }

    @Test
    @DisplayName("Class data that can't be read for the feature texts is a warning, and the other changes go ahead")
    void featureTextsSkipped() {
        PlayerCharacter lost = barbarian("Lost");
        when(characterRepository.findAll()).thenReturn(List.of(lost));
        when(characterService.derivedStats(lost)).thenReturn(derived(65, 3, List.of(RAGE), List.of()));
        when(characterService.outdatedFeatureTexts(lost))
                .thenThrow(new ConflictException("The features of the class Cleric can't be read"));

        MaintenanceReport report = job().run(false);

        assertThat(report.warnings()).containsExactly("Lost (" + lost.getId() + ", level 6 Barbarian, deleted): "
                + "feature texts skipped (The features of the class Cleric can't be read)");
        assertThat(report.changes()).singleElement().asString().contains("initiative: +2 → +3");
    }
}
