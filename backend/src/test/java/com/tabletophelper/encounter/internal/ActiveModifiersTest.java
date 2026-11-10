package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.rules.combat.OngoingModifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which Ongoing Modifiers are in force on a participant (D184), worked out from every Spell Effect in its encounter:
 * the same spell counted once, and the scopes of the caster's attacks against a marked creature.
 */
class ActiveModifiersTest {

    private CombatFixture f;
    private EncounterParticipant cleric;
    private EncounterParticipant paladin;
    private EncounterParticipant fighter;
    private EncounterParticipant goblin;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        cleric = f.player("Cleric", 20, 12);
        paladin = f.player("Paladin", 20, 18);
        fighter = f.player("Fighter", 30, 16);
        goblin = f.monster("Goblin", 7, 15);
    }

    private static OngoingModifier modifier(OngoingModifier.Kind kind, OngoingModifier.Scope scope, String dice,
                                            int flat) {
        return new OngoingModifier(UUID.randomUUID(), kind, scope, dice, flat, null, null, null, false, false, false,
                null, false, null, null, null, null);
    }

    private static SpellEffectEntry give(EncounterParticipant caster, String spell, int slotLevel,
                                         List<EncounterParticipant> affected, OngoingModifier... modifiers) {
        SpellEffectEntry effect = SpellEffectEntry.start(spell, slotLevel, null, 1, 10, null)
                .withModifiers(affected.stream().map(EncounterParticipant::getId).toList(), List.of(modifiers), null,
                        null, null, true);
        List<SpellEffectEntry> effects = new ArrayList<>(EffectApplier.effects(caster));
        effects.add(effect);
        caster.setActiveEffects(effects);
        return effect;
    }

    @Test
    @DisplayName("Two clerics' Bless on one fighter count once: the higher slot's (PHB p.205)")
    void sameSpellCountsOnce() {
        give(cleric, "Bless", 1, List.of(fighter), modifier(OngoingModifier.Kind.ATTACK_ROLL, null, "1d4", 0));
        SpellEffectEntry stronger = give(paladin, "Bless", 2, List.of(fighter),
                modifier(OngoingModifier.Kind.ATTACK_ROLL, null, "1d4", 0));

        List<ActiveModifiers.Found> found = ActiveModifiers.on(fighter);
        assertEquals(1, found.size());
        assertEquals(stronger.id(), found.getFirst().effect().id());
    }

    @Test
    @DisplayName("Different spells add up: Shield of Faith and Haste on one creature give AC 20 from 16")
    void differentSpellsAdd() {
        give(cleric, "Shield of Faith", 1, List.of(fighter), modifier(OngoingModifier.Kind.AC, null, null, 2));
        give(paladin, "Haste", 3, List.of(fighter), modifier(OngoingModifier.Kind.AC, null, null, 2));
        assertEquals(20, CreatureStats.armourClass(fighter));
        assertEquals(18, CreatureStats.armourClass(paladin));
    }

    @Test
    @DisplayName("Hunter's Mark is the caster's against its mark only: not another's, and not against anyone else")
    void markedCreature() {
        give(paladin, "Hunter's Mark", 1, List.of(goblin),
                modifier(OngoingModifier.Kind.HIT_DAMAGE, OngoingModifier.Scope.CASTER_VS_AFFECTED, "1d6", 0));

        assertEquals(1, ActiveModifiers.attacking(paladin, goblin).size());
        assertTrue(ActiveModifiers.attacking(fighter, goblin).isEmpty());
        assertTrue(ActiveModifiers.attacking(paladin, fighter).isEmpty());
        // The mark isn't on the goblin's own rolls
        assertTrue(ActiveModifiers.on(goblin).isEmpty());
    }

    @Test
    @DisplayName("Compelled Duel hinders the target's attacks on anyone but its caster")
    void compelledDuel() {
        give(paladin, "Compelled Duel", 1, List.of(goblin),
                modifier(OngoingModifier.Kind.ATTACK_MODE, OngoingModifier.Scope.AFFECTED_VS_OTHERS, null, 0));

        assertTrue(ActiveModifiers.attacking(goblin, paladin).isEmpty());
        assertEquals(1, ActiveModifiers.attacking(goblin, fighter).size());
    }

    @Test
    @DisplayName("A dismissed creature's Spell Effects hold no modifiers")
    void dismissedHolder() {
        give(cleric, "Shield of Faith", 1, List.of(fighter), modifier(OngoingModifier.Kind.AC, null, null, 2));
        cleric.setDismissed(true);
        assertEquals(16, CreatureStats.armourClass(fighter));
    }
}
