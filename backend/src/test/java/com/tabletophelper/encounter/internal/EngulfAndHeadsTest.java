package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.MonsterActionRequest;
import com.tabletophelper.encounter.dto.MonsterActionResponse;
import com.tabletophelper.encounter.dto.RepeatSaveRequest;
import com.tabletophelper.encounter.dto.UsePoolRequest;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A shambling mound's Engulf and a hydra's heads (D206). The mound's Engulf takes no save: it is used only on a Medium
 * or smaller creature the mound grapples, one at a time, which is then engulfed while the grapple lasts, blinded and
 * restrained while engulfed, with no cover, and makes a DC 14 Constitution save at the start of each of the mound's
 * turns or takes 2d8 + 4 bludgeoning (MM p.270); its multiattack grapples and engulfs a creature both Slams hit. A
 * hydra's multiattack makes a Bite for each head its Heads pool holds, the stat block's five without one (MM p.190),
 * and the DM changes the pool as heads die or regrow. The fighter has every ability at 10 (saves and checks +0), 60
 * hit points and AC 10.
 */
class EngulfAndHeadsTest {

    private static final String MOUND = """
            {"actions": [
               {"name": "Multiattack", "isMultiattack": true, "multiattackComponents": ["Slam", "Slam"],
                "onAllHit": {"attack": "Slam",
                             "effects": [{"effectType": "CONDITION", "condition": "grappled", "escapeDC": 14,
                                          "maxTargetSize": "Medium"}],
                             "uses": "Engulf",
                             "text": "If both attacks hit a Medium or smaller target, the target is grappled (escape DC 14), and the shambling mound uses its Engulf on it."}},
               {"name": "Slam", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 7,
                "effects": [{"effectType": "DAMAGE", "damageDice": "1", "damageType": "bludgeoning",
                             "flatDamage": true}]},
               {"name": "Engulf", "deliveryMethod": "AUTO_HIT", "targetCount": 1, "targetGrappled": true,
                "effects": [{"effectType": "CONDITION", "condition": "engulfed", "maxTargetSize": "Medium",
                             "swallow": {"limit": 1, "keepsGrapple": true, "cover": false}, "endsWith": "grappled"},
                            {"effectType": "CONDITION", "condition": "blinded", "endsWith": "engulfed"},
                            {"effectType": "CONDITION", "condition": "restrained", "endsWith": "engulfed"},
                            {"effectType": "ONGOING_DAMAGE", "damageDice": "2d8 + 4", "damageType": "bludgeoning",
                             "endsWith": "engulfed", "turnOf": "USER",
                             "turnSave": {"saveDC": 14, "saveAbility": "CON", "halfOnSave": false}}],
                "dmNote": "Applied by hand: the target is unable to breathe. If the mound moves, the engulfed target moves with it."}]}""";

    private static final String HYDRA = """
            {"actions": [
               {"name": "Multiattack", "isMultiattack": true, "multiattackComponents": [],
                "heads": {"count": 5, "attack": "Bite",
                          "rules": "Whenever the hydra takes 25 or more damage in a single turn, one of its heads dies."}},
               {"name": "Bite", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 8,
                "effects": [{"effectType": "DAMAGE", "damageDice": "1", "damageType": "piercing",
                             "flatDamage": true}]}]}""";

    private CombatFixture f;
    private EncounterParticipant fighter;
    private EncounterParticipant mound;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        fighter = f.player("Fighter", 60, 10);
        mound = f.monster("Shambling Mound", 136, 15);
        mound.getMonster().setSize("Large");
        mound.getMonster().setActionTemplates(MonsterTemplates.parse(MOUND));
    }

    private MonsterActionResponse act(EncounterParticipant monster, String action, EncounterParticipant target,
                                      int... rolls) {
        f.dice.script(rolls);
        MonsterActionResponse response = f.monsterActions.monsterAction(f.encounterId(),
                new MonsterActionRequest(monster.getId(), action, "ACTION", List.of(target.getId()), null, null, null,
                        null, null), f.dmUserId);
        assertEquals(0, f.dice.remaining(), "every scripted roll is used");
        return response;
    }

    private boolean logged(String text) {
        return f.descriptions().stream().anyMatch(line -> line.contains(text));
    }

    private static Optional<ConditionEntry> condition(EncounterParticipant creature, String name) {
        return (creature.getActiveConditions() != null ? creature.getActiveConditions() : List.<ConditionEntry>of())
                .stream().filter(c -> c.name().equals(name)).findFirst();
    }

    private List<SpellEffectEntry> riders(String kind) {
        return EffectApplier.effects(mound).stream()
                .filter(e -> e.monsterRider() != null && kind.equals(e.monsterRider().kind()))
                .toList();
    }

    // The mound grapples the creature by hand, as the DM would
    private void grappledByMound(EncounterParticipant creature) {
        List<ConditionEntry> conditions = new ArrayList<>(creature.getActiveConditions() != null
                ? creature.getActiveConditions() : List.of());
        conditions.add(ConditionEntry.fromEffect("grappled", null, 1, mound.getDisplayName(), mound.getId(), null));
        creature.setActiveConditions(conditions);
    }

    @Nested
    @DisplayName("The shambling mound's Engulf (MM p.270)")
    class Engulf {

        @Test
        @DisplayName("Both Slams hit: the fighter is grappled (escape DC 14) and engulfed at once, blinded and "
                + "restrained while engulfed, which lasts while the grapple does")
        void slamSlamEngulf() {
            act(mound, "Multiattack", fighter, 15, 15);
            ConditionEntry grappled = condition(fighter, "grappled").orElseThrow();
            assertEquals(mound.getId(), grappled.sourceParticipantId());
            assertTrue(grappled.repeatSave().escapes());
            ConditionEntry engulfed = condition(fighter, "engulfed").orElseThrow();
            assertEquals(grappled.id(), engulfed.linkedTo());
            assertEquals(engulfed.id(), condition(fighter, "blinded").orElseThrow().linkedTo());
            assertEquals(engulfed.id(), condition(fighter, "restrained").orElseThrow().linkedTo());
            assertEquals(58, fighter.getHpCurrent(), "the two Slams' 1 each, and nothing from the engulfing");
            SpellEffectEntry.MonsterRider gullet = riders(SpellEffectEntry.MonsterRider.SWALLOW).getFirst()
                    .monsterRider();
            assertFalse(gullet.swallow().covers());
            SpellEffectEntry.TurnDamage turn = riders(SpellEffectEntry.MonsterRider.TURN_DAMAGE).getFirst()
                    .monsterRider().turnDamage();
            assertEquals(new SpellEffectEntry.EffectSave("CON", 14), turn.save());
            assertTrue(logged("Both Slam attacks hit Fighter: If both attacks hit a Medium or smaller target"),
                    f.descriptions()::toString);
            assertTrue(logged("Fighter is engulfed by Shambling Mound (Engulf): it has no cover, so others can still "
                    + "reach it, and it lasts while Shambling Mound grapples it."), f.descriptions()::toString);
            assertTrue(logged("unless it succeeds on a DC 14 Constitution saving throw made each time"),
                    f.descriptions()::toString);
            assertTrue(logged("Engulf: Applied by hand: the target is unable to breathe."), f.descriptions()::toString);
        }

        @Test
        @DisplayName("One Slam misses: no grapple and no engulfing")
        void oneMiss() {
            act(mound, "Multiattack", fighter, 15, 2);
            assertTrue(CombatFixture.conditionNames(fighter).isEmpty(), CombatFixture.conditionNames(fighter)::toString);
            assertTrue(riders(SpellEffectEntry.MonsterRider.SWALLOW).isEmpty());
        }

        @Test
        @DisplayName("Both Slams hit a Large creature: not grappled, so not engulfed, and the log says why")
        void largeNotGrappled() {
            EncounterParticipant ogre = f.monster("Ogre", 59, 11);
            ogre.getMonster().setSize("Large");
            act(mound, "Multiattack", ogre, 15, 15);
            assertTrue(CombatFixture.conditionNames(ogre).isEmpty(), CombatFixture.conditionNames(ogre)::toString);
            assertTrue(logged("Ogre is Large, too big to be grappled"), f.descriptions()::toString);
            assertTrue(logged("Shambling Mound doesn't use Engulf: Ogre isn't grappled by Shambling Mound."),
                    f.descriptions()::toString);
        }

        @Test
        @DisplayName("At the start of each of the mound's turns a failed DC 14 Constitution save takes 2d8 + 4 "
                + "bludgeoning, a success none; nothing on the fighter's own turn")
        void startOfTurnSave() {
            act(mound, "Multiattack", fighter, 15, 15);
            // 2d8 5 + 6 + 4 = 15, then the save: d20 8 + 0 fails
            f.dice.script(5, 6, 8);
            f.effectApplier.effectsAtTurnStart(f.encounter, mound);
            assertEquals(43, fighter.getHpCurrent());
            // 2d8 rolled, then d20 16 succeeds: no damage
            f.dice.script(7, 7, 16);
            f.effectApplier.effectsAtTurnStart(f.encounter, mound);
            assertEquals(43, fighter.getHpCurrent());
            assertEquals(0, f.dice.remaining());
            f.effectApplier.effectsAtTurnStart(f.encounter, fighter);
            assertEquals(43, fighter.getHpCurrent());
        }

        @Test
        @DisplayName("Refused with a 409 on a creature the mound isn't grappling")
        void notGrappled() {
            ConflictException refused = assertThrows(ConflictException.class, () -> act(mound, "Engulf", fighter));
            assertTrue(refused.getMessage().contains("can be used only on a creature it is grappling"),
                    refused.getMessage());
            assertTrue(CombatFixture.conditionNames(fighter).isEmpty());
        }

        @Test
        @DisplayName("Refused with a 409 on a grappled Large creature, too big to engulf")
        void tooBig() {
            EncounterParticipant ogre = f.monster("Ogre", 59, 11);
            ogre.getMonster().setSize("Large");
            grappledByMound(ogre);
            ConflictException refused = assertThrows(ConflictException.class, () -> act(mound, "Engulf", ogre));
            assertTrue(refused.getMessage().contains("Shambling Mound's Engulf can't be used on Ogre: Ogre is Large, "
                    + "too big to be engulfed"), refused.getMessage());
            assertEquals(List.of("grappled"), CombatFixture.conditionNames(ogre));
        }

        @Test
        @DisplayName("One at a time: a second grappled creature is refused with a 409 while the first is engulfed")
        void oneAtATime() {
            EncounterParticipant rogue = f.player("Rogue", 30, 10);
            act(mound, "Multiattack", fighter, 15, 15);
            grappledByMound(rogue);
            ConflictException refused = assertThrows(ConflictException.class, () -> act(mound, "Engulf", rogue));
            assertTrue(refused.getMessage().contains("already holds 1 creature engulfed"), refused.getMessage());
            assertTrue(condition(rogue, "engulfed").isEmpty());
        }

        @Test
        @DisplayName("No cover: others reach the engulfed fighter, and it reaches them")
        void noCover() {
            EncounterParticipant cleric = f.player("Cleric", 40, 14);
            act(mound, "Multiattack", fighter, 15, 15);
            assertDoesNotThrow(() -> CombatAccess.requireReachable(cleric, List.of(fighter)));
            assertDoesNotThrow(() -> CombatAccess.requireReachable(fighter, List.of(cleric)));
            assertEquals(mound.getId(), CombatAccess.swallowedBy(fighter));
        }

        @Test
        @DisplayName("Escaping the grapple (escape DC 14) ends the engulfing, its blindness and restraint and the "
                + "damage each turn")
        void escapeEndsIt() {
            act(mound, "Multiattack", fighter, 15, 15);
            ConditionEntry grappled = condition(fighter, "grappled").orElseThrow();
            f.turnOf(fighter);
            CombatFixture.newTurn(fighter);
            f.dice.script(18);
            f.conditionService.rollActionSave(f.encounterId(), new RepeatSaveRequest(fighter.getId(), grappled.id()),
                    f.dmUserId);
            assertTrue(CombatFixture.conditionNames(fighter).isEmpty(), CombatFixture.conditionNames(fighter)::toString);
            assertTrue(riders(SpellEffectEntry.MonsterRider.TURN_DAMAGE).isEmpty());
            assertTrue(riders(SpellEffectEntry.MonsterRider.SWALLOW).isEmpty());
        }

        @Test
        @DisplayName("Engulf used on its own on a creature the mound grapples engulfs it")
        void engulfOnGrappled() {
            grappledByMound(fighter);
            act(mound, "Engulf", fighter);
            assertTrue(condition(fighter, "engulfed").isPresent());
            assertTrue(condition(fighter, "grappled").isPresent(), "the grapple goes on");
        }
    }

    @Nested
    @DisplayName("A hydra's heads (MM p.190)")
    class Heads {

        private EncounterParticipant hydra;

        @BeforeEach
        void hydra() {
            hydra = f.monster("Hydra", 172, 15);
            hydra.getMonster().setActionTemplates(MonsterTemplates.parse(HYDRA));
        }

        private void heads(int count) {
            List<ResourcePoolEntry> pools = new ArrayList<>(hydra.getResourcePoolsCurrent() != null
                    ? hydra.getResourcePoolsCurrent() : List.of());
            pools.add(MonsterPools.headsPool("Hydra", count));
            hydra.setResourcePoolsCurrent(pools);
        }

        private ResourcePoolEntry pool() {
            return hydra.getResourcePoolsCurrent().stream().filter(p -> MonsterPools.HEADS.equals(p.poolId()))
                    .findFirst().orElseThrow();
        }

        @Test
        @DisplayName("Without a Heads pool the multiattack makes the stat block's five Bites, and the log reminds the "
                + "DM of the head rules")
        void defaultFive() {
            act(hydra, "Multiattack", fighter, 15, 15, 15, 15, 15);
            assertEquals(55, fighter.getHpCurrent());
            assertTrue(logged("With 5 heads it makes 5 Bite attacks. Multiple Heads (the DM changes its Heads pool as "
                    + "heads die or regrow): Whenever the hydra takes 25 or more damage"), f.descriptions()::toString);
        }

        @Test
        @DisplayName("With three heads, three Bites; with one, one")
        void chosenHeads() {
            heads(3);
            act(hydra, "Multiattack", fighter, 15, 15, 15);
            assertEquals(57, fighter.getHpCurrent());
            f.poolUse.usePool(f.encounterId(), new UsePoolRequest(hydra.getId(), MonsterPools.HEADS, 2, "lose",
                    null), f.dmUserId);
            CombatFixture.newTurn(hydra);
            act(hydra, "Multiattack", fighter, 15);
            assertEquals(56, fighter.getHpCurrent());
        }

        @Test
        @DisplayName("The multiattack's attack options list a Bite for each head")
        void optionsPerHead() {
            heads(2);
            MonsterEffects.Multiattack multiattack = f.monsterEffects.multiattack(
                    MonsterTemplates.parse(HYDRA).find("ACTION", "Multiattack").orElseThrow(),
                    MonsterTemplates.parse(HYDRA), MonsterEffects.Overrides.NONE, 2);
            assertEquals(List.of("Bite", "Bite"), multiattack.components().stream()
                    .map(MonsterEffects.MultiattackComponent::name).toList());
        }

        @Test
        @DisplayName("Heads grow past the start, raising the pool's maximum, and die again")
        void growAndLose() {
            heads(5);
            f.poolUse.usePool(f.encounterId(), new UsePoolRequest(hydra.getId(), MonsterPools.HEADS, 1, "lose",
                    null), f.dmUserId);
            f.poolUse.usePool(f.encounterId(), new UsePoolRequest(hydra.getId(), MonsterPools.HEADS, 2, "grow",
                    null), f.dmUserId);
            assertEquals(6, pool().currentUses());
            assertEquals(6, pool().maxUses());
            assertTrue(logged("Hydra grows 2 heads: 6 heads now"), f.descriptions()::toString);
            assertThrows(ConflictException.class, () -> f.poolUse.usePool(f.encounterId(),
                    new UsePoolRequest(hydra.getId(), MonsterPools.HEADS, 7, "lose", null), f.dmUserId));
            assertThrows(ValidationException.class, () -> f.poolUse.usePool(f.encounterId(),
                    new UsePoolRequest(hydra.getId(), MonsterPools.HEADS, 1, "chop", null), f.dmUserId));
        }

        @Test
        @DisplayName("When its last head dies the hydra dies, by its trait, and makes no more Bites")
        void lastHead() {
            heads(1);
            f.poolUse.usePool(f.encounterId(), new UsePoolRequest(hydra.getId(), MonsterPools.HEADS, 1, "lose",
                    null), f.dmUserId);
            assertEquals(0, pool().currentUses());
            assertEquals(LifeState.DEAD, hydra.getLifeState());
            assertTrue(logged("Hydra is destroyed (all its heads have died, and by its Multiple Heads trait it dies "
                    + "with them)"), f.descriptions()::toString);
        }

        @Test
        @DisplayName("At 0 heads the multiattack is refused with a 409")
        void noHeads() {
            heads(1);
            hydra.setResourcePoolsCurrent(List.of(MonsterPools.headsPool("Hydra", 1).withCurrentUses(0)));
            ConflictException refused = assertThrows(ConflictException.class,
                    () -> act(hydra, "Multiattack", fighter));
            assertTrue(refused.getMessage().contains("Hydra has no heads left"), refused.getMessage());
        }
    }
}
