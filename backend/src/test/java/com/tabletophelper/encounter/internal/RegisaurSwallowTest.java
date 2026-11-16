package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.MonsterActionRequest;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.JacksonDefaults;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Regisaur's (BGG) Swallow (D209), with its generated actions from the data: a bonus-action attack against a Huge
 * or smaller creature it grapples, which swallows it and ends the grapple; the swallowed creature is blinded and
 * restrained, takes 2d6 acid at the start of each of its own turns, two fit at a time, and 25 damage from inside in one
 * turn calls for a DC 16 Constitution save or the regisaur regurgitates them prone within 5 feet. The creatures have
 * every ability at 10 (saves +0) and AC 10; the regisaur's Constitution save is +0 too.
 */
class RegisaurSwallowTest {

    private static String regisaur;

    private CombatFixture f;
    private EncounterParticipant fighter;
    private EncounterParticipant monster;

    @BeforeEach
    void setUp() throws IOException {
        if (regisaur == null) {
            try (InputStream in = new ClassPathResource("data/monster-actions/monster-action-definitions.json")
                    .getInputStream()) {
                for (JsonNode definition : JacksonDefaults.mapper().readTree(in)) {
                    if ("Regisaur".equals(definition.path("monsterName").asString())) {
                        regisaur = definition.toString();
                    }
                }
            }
        }
        f = new CombatFixture();
        fighter = f.player("Fighter", 200, 10);
        monster = f.monster("Regisaur", 300, 17);
        monster.getMonster().setSize("Gargantuan");
        monster.getMonster().setActionTemplates(MonsterTemplates.parse(regisaur));
    }

    private void act(String action, String type, EncounterParticipant target, int... rolls) {
        f.dice.script(rolls);
        f.monsterActions.monsterAction(f.encounterId(), new MonsterActionRequest(monster.getId(), action, type,
                List.of(target.getId()), null, null, null, null, null), f.dmUserId);
        assertEquals(0, f.dice.remaining(), "every scripted roll is used");
    }

    // A Bite that hits: d20 15, 6d12 of 1s, so 6 + 8 = 14 piercing
    private void bite(EncounterParticipant target) {
        act("Bite", "ACTION", target, 15, 1, 1, 1, 1, 1, 1);
    }

    // A Swallow against a creature restrained by the Bite's grapple: advantage, both d20s 15
    private void swallow(EncounterParticipant target) {
        act("Swallow", "BONUS", target, 15, 15);
    }

    private boolean logged(String text) {
        return f.descriptions().stream().anyMatch(line -> line.contains(text));
    }

    private static Optional<ConditionEntry> condition(EncounterParticipant creature, String name) {
        return (creature.getActiveConditions() != null ? creature.getActiveConditions() : List.<ConditionEntry>of())
                .stream().filter(c -> c.name().equals(name)).findFirst();
    }

    private List<SpellEffectEntry> gullets() {
        return EffectApplier.effects(monster).stream()
                .filter(e -> e.monsterRider() != null
                        && SpellEffectEntry.MonsterRider.SWALLOW.equals(e.monsterRider().kind()))
                .toList();
    }

    @Test
    @DisplayName("The Bite grapples and restrains while the grapple lasts")
    void biteGrapples() {
        bite(fighter);
        assertEquals(186, fighter.getHpCurrent());
        ConditionEntry grappled = condition(fighter, "grappled").orElseThrow();
        assertEquals(monster.getId(), grappled.sourceParticipantId());
        assertEquals(grappled.id(), condition(fighter, "restrained").orElseThrow().linkedTo());
    }

    @Test
    @DisplayName("Swallow on a creature it isn't grappling is refused (409)")
    void ungrappledRefused() {
        ConflictException refused = assertThrows(ConflictException.class,
                () -> act("Swallow", "BONUS", fighter));
        assertEquals("Regisaur's Swallow can be used only on a creature it is grappling, and it isn't grappling "
                + "Fighter", refused.getMessage());
    }

    @Test
    @DisplayName("A Swallow hit swallows the grappled fighter and ends the grapple: blinded and restrained while "
            + "swallowed, no damage on the hit")
    void swallowEndsTheGrapple() {
        bite(fighter);
        swallow(fighter);
        ConditionEntry swallowed = condition(fighter, "swallowed").orElseThrow();
        assertEquals(monster.getId(), swallowed.sourceParticipantId());
        assertTrue(condition(fighter, "grappled").isEmpty());
        assertEquals(swallowed.id(), condition(fighter, "blinded").orElseThrow().linkedTo());
        assertEquals(swallowed.id(), condition(fighter, "restrained").orElseThrow().linkedTo(),
                "the grapple's restraint gives way to the swallow's");
        assertEquals(186, fighter.getHpCurrent());
        assertEquals(List.of(fighter.getId()), gullets().getFirst().affected());
        assertEquals(monster.getId(), CombatAccess.swallowedBy(fighter));
    }

    @Test
    @DisplayName("Acid 2d6 at the start of the swallowed fighter's own turns, none at the start of the regisaur's")
    void acidOnItsOwnTurn() {
        bite(fighter);
        swallow(fighter);
        f.effectApplier.effectsAtTurnStart(f.encounter, monster);
        assertEquals(186, fighter.getHpCurrent());
        f.dice.script(3, 4);
        f.effectApplier.effectsAtTurnStart(f.encounter, fighter);
        assertEquals(179, fighter.getHpCurrent());
    }

    @Test
    @DisplayName("The Bite holds one creature at a time, but a swallow frees it to bite again: a second creature is "
            + "swallowed, and a third is hit but not swallowed, the gullet holding two")
    void twoAtATime() {
        EncounterParticipant rogue = f.player("Rogue", 200, 10);
        EncounterParticipant cleric = f.player("Cleric", 200, 10);
        bite(fighter);
        ConflictException held = assertThrows(ConflictException.class, () -> act("Bite", "ACTION", rogue));
        assertTrue(held.getMessage().contains("already holds Fighter"), held.getMessage());
        swallow(fighter);
        bite(rogue);
        swallow(rogue);
        assertTrue(condition(rogue, "swallowed").isPresent());
        assertEquals(List.of(fighter.getId(), rogue.getId()), gullets().getFirst().affected());

        bite(cleric);
        swallow(cleric);
        assertTrue(condition(cleric, "swallowed").isEmpty());
        assertTrue(condition(cleric, "blinded").isEmpty());
        assertTrue(logged("Regisaur already holds 2 creatures swallowed, so Cleric isn't swallowed."),
                f.descriptions().toString());
        assertEquals(List.of(fighter.getId(), rogue.getId()), gullets().getFirst().affected());
    }

    @Test
    @DisplayName("25 damage from inside in one turn: the regisaur fails its DC 16 Constitution save and regurgitates "
            + "the fighter prone within 5 feet, no longer blinded or restrained")
    void regurgitatesWithinFiveFeet() {
        bite(fighter);
        swallow(fighter);
        f.effectApplier.damage(f.encounter, fighter, "Fighter", monster, 25, "slashing");
        f.dice.script(9);
        f.effectApplier.effectsAtTurnEnd(f.encounter, fighter);
        assertTrue(condition(fighter, "swallowed").isEmpty());
        assertTrue(condition(fighter, "blinded").isEmpty());
        assertTrue(condition(fighter, "restrained").isEmpty());
        assertTrue(condition(fighter, "prone").isPresent());
        assertTrue(logged("vs DC 16 — fails."), f.descriptions().toString());
        assertTrue(logged("Regisaur regurgitates Fighter, which lands within 5 feet of it; it is prone."),
                f.descriptions().toString());
    }
}
