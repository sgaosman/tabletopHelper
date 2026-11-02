package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.dto.ResourcePoolEntry;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.CombatLog;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.MonsterActionRequest;
import com.tabletophelper.encounter.dto.MonsterActionResponse;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ForbiddenException;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Monster actions (M12, D160). The old CombatServiceTest had no monster action cases; these cover the legendary,
 * recharge and lair rules it kept, and the monster paths through the EffectApplier, whose rules EffectApplierTest
 * runs on every path. Monster spells moved to SpellcastingServiceTest when they joined the one cast path (D165).
 */
class MonsterActionServiceTest {

    private CombatFixture f;
    private EncounterParticipant dragon;
    private EncounterParticipant fighter;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        fighter = f.player("Fighter", 40, 18);
        dragon = f.monster("Young Dragon", 150, 18);
        dragon.getMonster().setActionTemplates("""
                {"actions": [
                   {"name": "Bite", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 10, "attackType": "MELEE_WEAPON",
                    "effects": [{"effectType": "DAMAGE", "damageDice": "2d10 + 6", "damageType": "piercing"},
                                {"effectType": "DAMAGE", "damageDice": "1d6", "damageType": "fire"}]},
                   {"name": "Claw", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 10,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "10", "damageType": "slashing"}]},
                   {"name": "Multiattack", "isMultiattack": true, "multiattackComponents": ["Claw", "Claw"]},
                   {"name": "Fire Breath", "deliveryMethod": "SAVING_THROW", "saveDC": 17, "saveAbility": "DEX",
                    "halfOnSave": true, "recharge": "5-6",
                    "effects": [{"effectType": "DAMAGE", "damageDice": "40", "damageType": "fire"}]}],
                 "legendaryActions": [
                   {"name": "Wing Attack", "cost": 2, "deliveryMethod": "SAVING_THROW", "saveDC": 15,
                    "saveAbility": "DEX", "effects": [{"effectType": "CONDITION", "condition": "prone"}]}],
                 "lairActions": [
                   {"name": "Tremor", "deliveryMethod": "SAVING_THROW", "saveDC": 15, "saveAbility": "DEX",
                    "effects": [{"effectType": "CONDITION", "condition": "prone"}]}]}""");
        List<ResourcePoolEntry> pools = new ArrayList<>(List.of(
                new ResourcePoolEntry("monster:legendary-actions", "Legendary Actions", "MONSTER", "Young Dragon",
                        3, "3", 3, "turn", null, null, "FREE", "zap", null),
                new ResourcePoolEntry("monster:recharge:fire-breath", "Fire Breath", "MONSTER", "Young Dragon",
                        1, "1", 1, null, null, "1d6>=5", "ACTION", "flame", null)));
        dragon.setResourcePoolsCurrent(pools);
    }

    private MonsterActionResponse act(String action, String source, List<EncounterParticipant> targets, int... rolls) {
        f.dice.script(rolls);
        return f.monsterActions.monsterAction(f.encounterId(), new MonsterActionRequest(dragon.getId(), action, source,
                targets.stream().map(EncounterParticipant::getId).toList(), null, null, null), f.dmUserId);
    }

    private int pool(EncounterParticipant participant, String poolId) {
        return participant.getResourcePoolsCurrent().stream()
                .filter(pool -> pool.poolId().equals(poolId)).findFirst().orElseThrow().currentUses();
    }

    @Test
    @DisplayName("An attack deals every damage part, and the response reports the hit, rolls and damage after defences")
    void attackWithEveryDamagePart() {
        fighter.getCharacter().setDamageResistances("[\"fire\"]");

        // 12 + 10 hits AC 18; 2d10 + 6 is 5 + 5 + 6 = 16 piercing; 1d6 is 5 fire, resisted to 2
        MonsterActionResponse response = act("Bite", "ACTION", List.of(fighter), 12, 5, 5, 5);

        assertEquals(40 - 18, fighter.getHpCurrent());
        MonsterActionResponse.TargetResult result = response.targetResults().getFirst();
        assertEquals("HIT", result.attackOutcome());
        assertEquals(12, result.rollValue());
        assertEquals(22, result.rollTotal());
        assertEquals(18, result.damage());
        assertEquals("piercing", result.damageType());
        assertEquals(18, response.totalDamage());
        assertEquals(List.of("Young Dragon uses Bite.",
                "Young Dragon uses Bite on Fighter: d20(12) + 10 = 22 vs AC 18 — hit, "
                        + "18 damage (16 piercing, 2 fire, halved by resistance)."), f.descriptions());
    }

    @Test
    @DisplayName("A multiattack's components resolve in turn, naming the action and the component")
    void multiattack() {
        MonsterActionResponse response = act("Multiattack", "ACTION", List.of(fighter), 15, 3);

        assertEquals(2, response.targetResults().size());
        assertEquals(List.of("HIT", "MISS"), response.targetResults().stream()
                .map(MonsterActionResponse.TargetResult::attackOutcome).toList());
        assertEquals(30, fighter.getHpCurrent());
        assertTrue(f.descriptions().get(1).startsWith("Young Dragon uses Multiattack (Claw) on Fighter"));
    }

    @Test
    @DisplayName("A target that an earlier component kills isn't attacked again")
    void multiattackStopsAtTheDead() {
        EncounterParticipant goblin = f.monster("Goblin", 7, 12);

        MonsterActionResponse response = act("Multiattack", "ACTION", List.of(goblin), 15);

        assertEquals(LifeState.DEAD, goblin.getLifeState());
        assertEquals(1, response.targetResults().size());
        assertEquals(0, f.dice.remaining());
    }

    @Test
    @DisplayName("A legendary action spends its cost from the pool, and is a 409 without enough left")
    void legendaryActionCost() {
        act("Wing Attack", "LEGENDARY", List.of(fighter), 18);
        assertEquals(1, pool(dragon, "monster:legendary-actions"));

        ConflictException ex = assertThrows(ConflictException.class,
                () -> act("Wing Attack", "LEGENDARY", List.of(fighter), 18));

        assertEquals("Not enough legendary actions remaining (need 2)", ex.getMessage());
        assertEquals(CombatActionType.LEGENDARY_ACTION, f.log.getFirst().getActionType());
    }

    @Test
    @DisplayName("A recharge action spends its pool and is a 409 until it recharges")
    void rechargeAction() {
        act("Fire Breath", "ACTION", List.of(fighter), 3);
        assertEquals(0, pool(dragon, "monster:recharge:fire-breath"));

        ConflictException ex = assertThrows(ConflictException.class,
                () -> act("Fire Breath", "ACTION", List.of(fighter), 3));

        assertEquals("Recharge action 'Fire Breath' is not yet recharged", ex.getMessage());
    }

    @Test
    @DisplayName("A legendary action is a 409 on its monster's own turn, and fine on another's, the Lair's included (D163)")
    void legendaryActionTiming() {
        f.turnOf(dragon);

        ConflictException ownTurn = assertThrows(ConflictException.class,
                () -> act("Wing Attack", "LEGENDARY", List.of(fighter), 18));

        assertEquals("Young Dragon can't use legendary actions on its own turn, only on other creatures' turns",
                ownTurn.getMessage());
        assertEquals(3, pool(dragon, "monster:legendary-actions"));
        assertTrue(f.log.isEmpty());

        f.encounter.setMonstersInLair(List.of(dragon.getId()));
        f.lairTurn();
        act("Wing Attack", "LEGENDARY", List.of(fighter), 18);
        assertEquals(1, pool(dragon, "monster:legendary-actions"));
    }

    @Test
    @DisplayName("A lair action is a 409 outside the Lair's turn, or by a monster not fighting in its lair (D163)")
    void lairActionTiming() {
        ConflictException notTheLairsTurn = assertThrows(ConflictException.class,
                () -> act("Tremor", "LAIR", List.of(fighter), 18));
        f.lairTurn();
        ConflictException notInItsLair = assertThrows(ConflictException.class,
                () -> act("Tremor", "LAIR", List.of(fighter), 18));
        f.encounter.setMonstersInLair(List.of(dragon.getId()));
        CombatFixture.dead(dragon);
        ConflictException dead = assertThrows(ConflictException.class,
                () -> act("Tremor", "LAIR", List.of(fighter), 18));

        assertEquals("Lair actions happen only on the Lair's turn, at initiative 20", notTheLairsTurn.getMessage());
        assertEquals("Young Dragon isn't fighting in its lair", notInItsLair.getMessage());
        assertEquals("Young Dragon isn't fighting in its lair", dead.getMessage());
        assertNull(dragon.getLastLairActionUsed());
        assertTrue(f.log.isEmpty());
    }

    @Test
    @DisplayName("A lair action can't be used two rounds running")
    void lairActionNoRepeat() {
        f.encounter.setMonstersInLair(List.of(dragon.getId()));
        f.lairTurn();
        act("Tremor", "LAIR", List.of(fighter), 18);

        ConflictException ex = assertThrows(ConflictException.class, () -> act("Tremor", "LAIR", List.of(fighter), 18));

        assertEquals("Lair action 'Tremor' cannot be used two rounds in a row", ex.getMessage());
        assertEquals(CombatActionType.LAIR_ACTION, f.log.getFirst().getActionType());
    }

    @Test
    @DisplayName("A failed save by a monster with Legendary Resistance left is offered for undoing (D163)")
    void legendaryResistanceOffered() {
        EncounterParticipant lich = f.monster("Lich", 135, 17);
        lich.setResourcePoolsCurrent(CombatFixture.legendaryResistance(3));

        MonsterActionResponse response = act("Wing Attack", "LEGENDARY", List.of(lich), 2);

        assertTrue(response.legendaryResistanceAvailable());
        assertEquals(3, response.legendaryResistanceRemaining());
        CombatLog entry = f.entriesAbout(lich).getFirst();
        assertTrue(entry.getLegendaryResistanceEligible());
        assertEquals(0, entry.getLrSuccessDamage());
        assertEquals(List.of(lich.getActiveConditions().getFirst().id()), entry.getLrConditionIds());
    }

    @Test
    @DisplayName("An action against the dead is a 409 that spends nothing")
    void deadTargetIsRefused() {
        EncounterParticipant goblin = f.monster("Goblin", 7, 12);
        CombatFixture.dead(goblin);

        assertThrows(ConflictException.class, () -> act("Wing Attack", "LEGENDARY", List.of(goblin)));

        assertEquals(3, pool(dragon, "monster:legendary-actions"));
    }

    @Test
    @DisplayName("An unknown action or target is a 400, and monster actions are the DM's")
    void refusals() {
        UUID playerUserId = f.playerControlling(fighter);

        assertThrows(ValidationException.class, () -> act("Tail Swipe", "ACTION", List.of(fighter)));
        assertThrows(ForbiddenException.class, () -> f.monsterActions.monsterAction(f.encounterId(),
                new MonsterActionRequest(dragon.getId(), "Bite", "ACTION", List.of(fighter.getId()), null, null, null),
                playerUserId));
    }
}
