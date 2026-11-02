package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.shared.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Manual Modifier gate on its own (D164): the DM may always enter them, a player while the campaign allows it,
 * and every use is logged. AttackServiceTest and SpellcastingServiceTest run it on attacks and spells.
 */
class ManualModifiersTest {

    private CombatFixture f;
    private EncounterParticipant wizard;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        wizard = f.player("Wizard", 30, 12);
        wizard.setSpellAttackBonus(5);
        wizard.setSpellSaveDc(13);
    }

    @Test
    @DisplayName("The DM always may; a player unless the campaign turns them off, which a campaign without the setting doesn't")
    void allowed() {
        assertTrue(ManualModifiers.allowed(true, false));
        assertTrue(ManualModifiers.allowed(true, true));
        assertTrue(ManualModifiers.allowed(false, true));
        assertTrue(ManualModifiers.allowed(false, null));
        assertFalse(ManualModifiers.allowed(false, false));
    }

    @Test
    @DisplayName("A spell's entered numbers are each a use, set against the caster's own; nothing entered is none")
    void spellUses() {
        assertEquals(List.of(new ManualModifiers.Use("uses spell attack bonus +9 instead of +5"),
                        new ManualModifiers.Use("uses spell save DC 11 instead of 13")),
                ManualModifiers.forSpell(wizard, 9, 11));
        wizard.setSpellAttackBonus(null);
        assertEquals(List.of(new ManualModifiers.Use("uses spell attack bonus -1")),
                ManualModifiers.forSpell(wizard, -1, null));
        assertTrue(ManualModifiers.forSpell(wizard, null, null).isEmpty());
        assertTrue(ManualModifiers.forAttack(false).isEmpty());
    }

    @Test
    @DisplayName("A player is refused with a 403 only when the campaign disallows them and something was entered")
    void requireForPlayers() {
        UUID playerUserId = f.playerControlling(wizard);
        List<ManualModifiers.Use> crit = ManualModifiers.forAttack(true);

        f.manualModifiers.require(f.encounter, playerUserId, crit);
        f.campaign.setAllowPlayerManualModifiers(false);
        f.manualModifiers.require(f.encounter, playerUserId, List.of());
        f.manualModifiers.require(f.encounter, f.dmUserId, crit);

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> f.manualModifiers.require(f.encounter, playerUserId, crit));
        assertEquals("This campaign doesn't allow players to enter Manual Modifiers", ex.getMessage());
    }

    @Test
    @DisplayName("Each use is logged under the action's type with who entered it, naming the actor and no target")
    void log() {
        UUID playerUserId = f.playerControlling(wizard);

        f.manualModifiers.log(f.encounter, CombatActionType.SPELL_CAST, wizard, "Wizard's Fire Bolt", playerUserId,
                ManualModifiers.forSpell(wizard, 9, 15));

        assertEquals(List.of("Manual Modifier (player): Wizard's Fire Bolt uses spell attack bonus +9 instead of +5.",
                "Manual Modifier (player): Wizard's Fire Bolt uses spell save DC 15 instead of 13."), f.descriptions());
        assertTrue(f.log.stream().allMatch(entry -> entry.getActionType() == CombatActionType.SPELL_CAST
                && wizard.getId().equals(entry.getActorId()) && entry.getTargetId() == null));
    }
}
