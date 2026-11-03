package com.tabletophelper.character;

import com.tabletophelper.campaign.Campaign;
import com.tabletophelper.character.CombatResults.CharacterResult;
import com.tabletophelper.character.dto.CharacterDetailsRequest;
import com.tabletophelper.character.dto.CharacterResponse;
import com.tabletophelper.character.dto.CombatResultPreview;
import com.tabletophelper.character.dto.HitPointChangeRequest;
import com.tabletophelper.character.state.CombatTotals;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.identity.User;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ForbiddenException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Write-back on the character side (D166): an encounter's results applied to the sheets as they stand, the changes
 * adding up and kept within their limits; the End dialog's preview, which changes nothing; the lock order; and a dead
 * character, refused rests, hit point changes and levels until the DM revives it. The rules themselves are
 * rules.character.WriteBackTest's. The fixture's characters are all Aria: a Fighter 2 with CON 12 has 18 hit points and
 * Action Surge once a short rest, and a Wizard 3 17 hit points and four level 1 and two level 2 slots.
 */
class WriteBackServiceTest {

    private static final String SURGE = "class:fighter-action-surge";

    private final CharacterFixture f = new CharacterFixture();

    @Test
    @DisplayName("Results apply each total's change to the sheet, so two encounters add up, and temporary hit points are what was left")
    void changesAddUp() {
        PlayerCharacter aria = f.create(f.human, CharacterFixture.scores(), f.fighter, 2);
        assertThat(aria.getHpCurrent()).isEqualTo(18);

        f.writeBack.apply(results(new CharacterResult(aria.getId(), totals(18, 0, Map.of(), Map.of(SURGE, 1)),
                totals(11, 3, Map.of(), Map.of(SURGE, 0)), LifeState.ALIVE)));
        assertThat(aria.getHpCurrent()).isEqualTo(11);
        assertThat(aria.getHpTemp()).isEqualTo(3);
        assertThat(aria.getResourcePools().getFirst().currentUses()).isZero();

        // A second encounter Aria joined at 18 with Action Surge to spare, which took 10 and spent nothing more
        f.writeBack.apply(results(new CharacterResult(aria.getId(), totals(18, 0, Map.of(), Map.of(SURGE, 1)),
                totals(8, 0, Map.of(), Map.of(SURGE, 1)), LifeState.ALIVE)));
        assertThat(aria.getHpCurrent()).isEqualTo(1);
        assertThat(aria.getHpTemp()).isZero();
        assertThat(aria.getResourcePools().getFirst().currentUses()).isZero();
        assertThat(aria.isDead()).isFalse();
    }

    @Test
    @DisplayName("Hit points stop at 0 and at the maximum, slots and pools at their limits; a slot the sheet lacks is left out")
    void totalsStayWithinTheirLimits() {
        PlayerCharacter wizard = f.create(f.human, CharacterFixture.scores(), f.wizard, 3);
        assertThat(wizard.getHpMax()).isEqualTo(17);
        wizard.setSpellSlots(Map.of("1", new SpellSlot(4, 3), "2", new SpellSlot(2, 0)));

        f.writeBack.apply(results(new CharacterResult(wizard.getId(),
                totals(10, 0, Map.of("1", 0, "2", 0, "3", 0), Map.of()),
                totals(40, 0, Map.of("1", 3, "2", 1, "3", 1), Map.of()), LifeState.ALIVE)));

        assertThat(wizard.getHpCurrent()).isEqualTo(17);
        assertThat(wizard.getSpellSlots()).isEqualTo(Map.of("1", new SpellSlot(4, 4), "2", new SpellSlot(2, 1)));

        f.writeBack.apply(results(new CharacterResult(wizard.getId(), totals(17, 0, Map.of("1", 4), Map.of()),
                totals(0, 0, Map.of("1", 0), Map.of()), LifeState.ALIVE)));
        assertThat(wizard.getHpCurrent()).isZero();
        assertThat(wizard.getSpellSlots().get("1")).isEqualTo(new SpellSlot(4, 0));
        assertThat(wizard.isDead()).isFalse();
    }

    @Test
    @DisplayName("A character still Dying or Stable is written back alive at 0; one who died is dead at 0 with no temporary hit points")
    void dyingStableAndDead() {
        PlayerCharacter aria = f.create(f.human, CharacterFixture.scores(), f.fighter, 2);
        PlayerCharacter brom = f.create(f.human, CharacterFixture.scores(), f.fighter, 2);
        // Healed on the sheet while it fought: still 0, since it ended down
        aria.setHpCurrent(18);

        f.writeBack.apply(results(
                new CharacterResult(aria.getId(), totals(10, 0, Map.of(), Map.of()), totals(0, 0, Map.of(), Map.of()),
                        LifeState.STABLE),
                new CharacterResult(brom.getId(), totals(18, 5, Map.of(), Map.of(SURGE, 1)),
                        totals(0, 2, Map.of(), Map.of(SURGE, 0)), LifeState.DEAD)));

        assertThat(aria.getHpCurrent()).isZero();
        assertThat(aria.isDead()).isFalse();
        assertThat(brom.getHpCurrent()).isZero();
        assertThat(brom.getHpTemp()).isZero();
        assertThat(brom.isDead()).isTrue();
        // What it spent before it died is written back too
        assertThat(brom.getResourcePools().getFirst().currentUses()).isZero();
    }

    @Test
    @DisplayName("The characters are locked in ID order, as PostgreSQL sorts UUIDs, after the lock wait is limited")
    void locksInIdOrder() {
        PlayerCharacter first = f.create(f.human, CharacterFixture.scores(), f.fighter, 1);
        PlayerCharacter second = f.create(f.human, CharacterFixture.scores(), f.fighter, 1);
        List<PlayerCharacter> byId = List.of(first, second).stream()
                .sorted(Comparator.comparing(pc -> pc.getId().toString()))
                .toList();
        CombatTotals unchanged = totals(first.getHpCurrent(), 0, Map.of(), Map.of());
        clearInvocations(f.characterRepository);

        // Listed last first, and the last twice, as a character the encounter holds twice is
        f.writeBack.apply(results(
                new CharacterResult(byId.get(1).getId(), unchanged, unchanged, LifeState.ALIVE),
                new CharacterResult(byId.get(0).getId(), unchanged, unchanged, LifeState.ALIVE),
                new CharacterResult(byId.get(1).getId(), unchanged, unchanged, LifeState.ALIVE)));

        InOrder order = inOrder(f.characterRepository);
        order.verify(f.characterRepository).limitLockWaits();
        order.verify(f.characterRepository).lockById(byId.get(0).getId());
        order.verify(f.characterRepository).lockById(byId.get(1).getId());
        verify(f.characterRepository, times(2)).lockById(any(UUID.class));
    }

    @Test
    @DisplayName("The preview shows hit points before and after and only the slots and pools that change, and changes nothing")
    void previewChangesNothing() {
        PlayerCharacter aria = f.create(f.human, CharacterFixture.scores(), f.fighter, 2);
        clearInvocations(f.characterRepository);

        List<CombatResultPreview> previews = f.writeBack.preview(results(new CharacterResult(aria.getId(),
                totals(18, 0, Map.of(), Map.of(SURGE, 1)), totals(4, 6, Map.of(), Map.of(SURGE, 0)),
                LifeState.ALIVE)));

        assertThat(previews).containsExactly(new CombatResultPreview(aria.getId(), "Aria", LifeState.ALIVE, 18, 18, 4,
                0, 6, false, false, List.of(),
                List.of(new CombatResultPreview.PoolChange(SURGE, "Action Surge", 1, 1, 0))));
        assertThat(aria.getHpCurrent()).isEqualTo(18);
        assertThat(aria.getResourcePools().getFirst().currentUses()).isEqualTo(1);
        verify(f.characterRepository, never()).save(any(PlayerCharacter.class));
    }

    @Test
    @DisplayName("A dead character can't rest, take hit point changes or change level, though other commands go on")
    void theDeadWait() {
        PlayerCharacter aria = f.create(f.human, CharacterFixture.scores(), f.fighter, 2);
        f.writeBack.apply(results(new CharacterResult(aria.getId(), totals(18, 0, Map.of(), Map.of()),
                totals(0, 0, Map.of(), Map.of()), LifeState.DEAD)));

        assertThatThrownBy(() -> f.rests.shortRest(aria.getId(), null, f.userId))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Aria is dead, so can't take a short rest until the DM revives them");
        assertThatThrownBy(() -> f.rests.longRest(aria.getId(), f.userId))
                .hasMessage("Aria is dead, so can't take a long rest until the DM revives them");
        assertThatThrownBy(() -> f.sheet.changeHitPoints(aria.getId(),
                new HitPointChangeRequest(HitPointChangeRequest.Kind.HEALING, 5), f.userId))
                .hasMessage("Aria is dead, so can't be healed until the DM revives them");
        assertThatThrownBy(() -> f.sheet.changeHitPoints(aria.getId(),
                new HitPointChangeRequest(HitPointChangeRequest.Kind.DAMAGE, 5), f.userId))
                .hasMessage("Aria is dead, so can't take damage until the DM revives them");
        assertThatThrownBy(() -> f.sheet.changeHitPoints(aria.getId(),
                new HitPointChangeRequest(HitPointChangeRequest.Kind.TEMPORARY, 5), f.userId))
                .hasMessage("Aria is dead, so can't gain temporary hit points until the DM revives them");
        assertThatThrownBy(() -> f.levelUp(aria, f.fighter))
                .hasMessage("Aria is dead, so can't level up until the DM revives them");
        assertThatThrownBy(() -> f.levelDown(aria))
                .hasMessage("Aria is dead, so can't level down until the DM revives them");

        CharacterResponse renamed = f.sheet.updateDetails(aria.getId(),
                new CharacterDetailsRequest("Aria the Fallen", null, null, null, null, null, null, null, null), f.userId);
        assertThat(renamed.isDead()).isTrue();
        assertThat(renamed.getHpCurrent()).isZero();
    }

    @Test
    @DisplayName("Only the DM of the character's campaign revives it, at 1 hit point; a character alive can't be revived")
    void theDmRevives() {
        UUID dmId = UUID.randomUUID();
        Campaign campaign = Campaign.builder().id(UUID.randomUUID()).name("Lost Mine")
                .dm(User.builder().id(dmId).username("dm").build()).inviteCode("ABCD2345").build();
        PlayerCharacter aria = f.create(f.human, CharacterFixture.scores(), f.fighter, 2);
        aria.setCampaign(campaign);
        lenient().when(f.campaignRepository.findById(campaign.getId())).thenReturn(Optional.of(campaign));
        when(f.characterRepository.findCampaignOfCharacterById(aria.getId())).thenReturn(Optional.of(
                new CharacterRepository.CampaignOfCharacter() {
                    @Override
                    public UUID getCharacterId() {
                        return aria.getId();
                    }

                    @Override
                    public UUID getCampaignId() {
                        return campaign.getId();
                    }
                }));

        assertThatThrownBy(() -> f.writeBack.revive(aria.getId(), dmId))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Aria isn't dead");
        f.writeBack.apply(results(new CharacterResult(aria.getId(), totals(18, 0, Map.of(), Map.of()),
                totals(0, 0, Map.of(), Map.of()), LifeState.DEAD)));
        assertThatThrownBy(() -> f.writeBack.revive(aria.getId(), f.userId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Only the DM of this character's campaign can adjust it");

        CharacterResponse revived = f.writeBack.revive(aria.getId(), dmId);

        assertThat(revived.isDead()).isFalse();
        assertThat(revived.getHpCurrent()).isEqualTo(1);
        assertThat(revived.getHpMax()).isEqualTo(18);
        assertThat(f.rests.longRest(aria.getId(), f.userId).getHpCurrent()).isEqualTo(18);
    }

    @Test
    @DisplayName("A dead character stays at 0 hit points when its maximum rises")
    void theDeadStayAtZero() {
        PlayerCharacter aria = f.create(f.human, CharacterFixture.scores(), f.fighter, 2);
        f.writeBack.apply(results(new CharacterResult(aria.getId(), totals(18, 0, Map.of(), Map.of()),
                totals(0, 0, Map.of(), Map.of()), LifeState.DEAD)));
        aria.setConstitution(16);

        f.characterStats.apply(aria);

        assertThat(aria.getHpMax()).isEqualTo(22);
        assertThat(aria.getHpCurrent()).isZero();
    }

    private static CombatResults results(CharacterResult... characters) {
        return new CombatResults(UUID.randomUUID(), List.of(characters));
    }

    private static CombatTotals totals(int hp, int temporary, Map<String, Integer> slotsUsed,
                                       Map<String, Integer> poolUses) {
        return new CombatTotals(hp, temporary, slotsUsed, poolUses);
    }
}
