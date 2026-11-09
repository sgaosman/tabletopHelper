package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.CombatLog;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.MonsterActionRequest;
import com.tabletophelper.encounter.dto.MonsterActionResponse;
import com.tabletophelper.encounter.dto.MultiattackOption;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ForbiddenException;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Monster actions (M12, D160). The old CombatServiceTest had no monster action cases; these cover the legendary,
 * recharge and lair rules it kept, the monster paths through the EffectApplier, whose rules EffectApplierTest runs on
 * every path, a multiattack's target per attack (D165), and the save a hit calls for against its conditions, with
 * Legendary Resistance on it (D163). Monster spells moved to SpellcastingServiceTest when they joined the one cast
 * path (D165).
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
        dragon.getMonster().setActionTemplates(MonsterTemplates.parse("""
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
                 "bonusActions": [
                   {"name": "Tail Flick", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 10,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "5", "damageType": "bludgeoning"}]},
                   {"name": "Smoke Burst", "deliveryMethod": "SAVING_THROW", "saveDC": 15, "saveAbility": "CON",
                    "recharge": "5-6", "effects": [{"effectType": "DAMAGE", "damageDice": "4", "damageType": "fire"}]},
                   {"name": "Second Wind", "recharge": "SHORT_REST", "automatable": false,
                    "description": "The dragon regains 10 hit points."}],
                 "reactions": [
                   {"name": "Fiery Rebuke", "deliveryMethod": "SAVING_THROW", "saveDC": 15, "saveAbility": "DEX",
                    "effects": [{"effectType": "DAMAGE", "damageDice": "10", "damageType": "fire"}]},
                   {"name": "Parry", "automatable": false,
                    "description": "The dragon adds 3 to its AC against one melee attack that would hit it.",
                    "dmNote": "+3 AC against one melee hit: if it now misses, the DM restores its damage by hand."}],
                 "lairActions": [
                   {"name": "Tremor", "deliveryMethod": "SAVING_THROW", "saveDC": 15, "saveAbility": "DEX",
                    "effects": [{"effectType": "CONDITION", "condition": "prone"}]}]}"""));
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
                targets.stream().map(EncounterParticipant::getId).toList(), null, null, null, null), f.dmUserId);
    }

    // The dragon's multiattack with a list of targets for each of its attacks (D165)
    private MonsterActionResponse multiattack(List<List<EncounterParticipant>> targets, int... rolls) {
        f.dice.script(rolls);
        return f.monsterActions.monsterAction(f.encounterId(), new MonsterActionRequest(dragon.getId(), "Multiattack",
                "ACTION", List.of(), null, null, null,
                targets.stream().map(each -> each.stream().map(EncounterParticipant::getId).toList()).toList()),
                f.dmUserId);
    }

    private int pool(EncounterParticipant participant, String poolId) {
        return participant.getResourcePoolsCurrent().stream()
                .filter(pool -> pool.poolId().equals(poolId)).findFirst().orElseThrow().currentUses();
    }

    @Test
    @DisplayName("An attack deals every damage part, and the response reports the hit, rolls and damage after defences")
    void attackWithEveryDamagePart() {
        fighter.getCharacter().setDamageResistances(List.of("fire"));

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
    @DisplayName("A multiattack's attacks each go to the target chosen for them, each logged on its own entry (D165)")
    void multiattackSplitAcrossTargets() {
        EncounterParticipant goblin = f.monster("Goblin", 30, 12);

        // Each claw hits for a flat 10: 15 + 10 against AC 18, then against AC 12
        MonsterActionResponse response = multiattack(List.of(List.of(fighter), List.of(goblin)), 15, 15);

        assertEquals(30, fighter.getHpCurrent());
        assertEquals(20, goblin.getHpCurrent());
        assertEquals(List.of(fighter.getId(), goblin.getId()), response.targetResults().stream()
                .map(MonsterActionResponse.TargetResult::targetId).toList());
        assertEquals(List.of("Young Dragon uses Multiattack.",
                "Young Dragon uses Multiattack (Claw) on Fighter: d20(15) + 10 = 25 vs AC 18 — hit, 10 slashing damage.",
                "Young Dragon uses Multiattack (Claw) on Goblin: d20(15) + 10 = 25 vs AC 12 — hit, 10 slashing damage."),
                f.descriptions());
    }

    @Test
    @DisplayName("A later attack skips a creature an earlier one killed, rolling nothing, and an empty list skips an attack")
    void multiattackSkipsTheKilled() {
        EncounterParticipant goblin = f.monster("Goblin", 7, 12);

        MonsterActionResponse first = multiattack(List.of(List.of(goblin), List.of(goblin)), 15);
        assertEquals(LifeState.DEAD, goblin.getLifeState());
        assertEquals(1, first.targetResults().size());
        assertEquals(0, f.dice.remaining());

        MonsterActionResponse second = multiattack(List.of(List.of(), List.of(fighter)), 3);
        assertEquals(List.of("MISS"), second.targetResults().stream()
                .map(MonsterActionResponse.TargetResult::attackOutcome).toList());
    }

    @Test
    @DisplayName("Targets per attack are a 400 unless one list each, within what each attack takes, with a target in "
            + "all; more than one target for the whole multiattack is a 400 too, and nothing is spent or logged")
    void multiattackTargetRefusals() {
        EncounterParticipant goblin = f.monster("Goblin", 30, 12);
        UUID fighterId = fighter.getId();
        UUID goblinId = goblin.getId();

        ValidationException tooFew = assertThrows(ValidationException.class,
                () -> multiattack(List.of(List.of(fighter))));
        ValidationException tooMany = assertThrows(ValidationException.class,
                () -> multiattack(List.of(List.of(fighter, goblin), List.of())));
        ValidationException none = assertThrows(ValidationException.class,
                () -> multiattack(List.of(List.of(), List.of())));
        ValidationException notMultiattack = assertThrows(ValidationException.class, () -> f.monsterActions
                .monsterAction(f.encounterId(), new MonsterActionRequest(dragon.getId(), "Bite", "ACTION", List.of(),
                        null, null, null, List.of(List.of(fighterId))), f.dmUserId));
        ValidationException both = assertThrows(ValidationException.class, () -> f.monsterActions
                .monsterAction(f.encounterId(), new MonsterActionRequest(dragon.getId(), "Multiattack", "ACTION",
                        List.of(fighterId), null, null, null, List.of(List.of(fighterId), List.of(goblinId))),
                        f.dmUserId));
        ValidationException everyAttackAtEveryone = assertThrows(ValidationException.class,
                () -> act("Multiattack", "ACTION", List.of(fighter, goblin)));

        assertEquals("Multiattack makes 2 attacks, but targets were given for 1", tooFew.getMessage());
        assertEquals("Claw can target 1 creature, but 2 were chosen", tooMany.getMessage());
        assertEquals("Choose a target for at least one of Multiattack's attacks", none.getMessage());
        assertEquals("Only a multiattack takes a target for each attack, and Bite isn't one", notMultiattack.getMessage());
        assertEquals("Give Multiattack's targets attack by attack or for the whole action, not both", both.getMessage());
        assertEquals("Multiattack makes its attacks one target at a time: choose a target for each attack",
                everyAttackAtEveryone.getMessage());
        assertEquals(40, fighter.getHpCurrent());
        assertTrue(f.log.isEmpty());
    }

    @Test
    @DisplayName("A multiattack the stat block doesn't let the server read is the DM's, and takes no targets per attack")
    void unreadableMultiattack() {
        dragon.getMonster().setActionTemplates(MonsterTemplates.parse("""
                {"actions": [{"name": "Multiattack", "isMultiattack": true, "multiattackComponents": [],
                  "multiattackDescription": "The dragon makes two melee attacks."}]}"""));

        MonsterActionResponse response = act("Multiattack", "ACTION", List.of(fighter));
        ValidationException perAttack = assertThrows(ValidationException.class,
                () -> multiattack(List.of(List.of(fighter))));

        assertTrue(response.requiresManualResolution());
        assertEquals("Multiattack has no components defined", response.manualResolutionReason());
        assertEquals(List.of("Young Dragon uses Multiattack — Multiattack has no components defined"), f.descriptions());
        assertEquals("Multiattack has no components defined, so its attacks can't take targets one by one",
                perAttack.getMessage());
    }

    @Test
    @DisplayName("The DM reads each multiattack's attacks for a target picker per attack; a player can't (D157)")
    void multiattackOptions() {
        UUID playerUserId = f.playerControlling(fighter);

        List<MultiattackOption> options = f.monsterActions.multiattackOptions(f.encounterId(), dragon.getId(),
                f.dmUserId);

        assertEquals(List.of(new MultiattackOption("Multiattack", null, List.of(
                new MultiattackOption.ComponentOption("Claw", true, null, 1),
                new MultiattackOption.ComponentOption("Claw", true, null, 1)), null)), options);
        assertTrue(f.monsterActions.multiattackOptions(f.encounterId(), fighter.getId(), f.dmUserId).isEmpty());
        assertThrows(ForbiddenException.class,
                () -> f.monsterActions.multiattackOptions(f.encounterId(), dragon.getId(), playerUserId));
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

    // ── Saves a hit calls for against its conditions ─────────────

    // The Dire Wolf's bite as seeded (MM): its prone needs a failed DC 13 Strength save; and a grell-like tentacle,
    // whose grapple comes with the hit and whose poison needs a failed DC 11 Constitution save
    private static final String WOLF_AND_GRELL = """
            {"actions": [
               {"name": "Bite", "reach": 5, "effects": [
                  {"damageDice": "2d6 + 3", "damageType": "piercing", "effectType": "DAMAGE"},
                  {"saveDC": 13, "condition": "prone", "effectType": "CONDITION", "saveAbility": "STR"}],
                "actionType": "ACTION", "attackType": "MELEE_WEAPON", "attackBonus": 5, "targetCount": 1,
                "deliveryMethod": "ATTACK_ROLL"},
               {"name": "Tentacle", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 5, "effects": [
                  {"damageDice": "4", "damageType": "piercing", "effectType": "DAMAGE"},
                  {"escapeDC": 15, "condition": "grappled", "effectType": "CONDITION"},
                  {"saveDC": 11, "condition": "poisoned", "effectType": "CONDITION", "saveAbility": "CON"}]}]}""";

    private MonsterActionResponse actionBy(EncounterParticipant monster, String action, EncounterParticipant target,
                                           int... rolls) {
        f.dice.script(rolls);
        return f.monsterActions.monsterAction(f.encounterId(), new MonsterActionRequest(monster.getId(), action,
                "ACTION", List.of(target.getId()), null, null, null, null), f.dmUserId);
    }

    private EncounterParticipant direWolf() {
        EncounterParticipant wolf = f.monster("Dire Wolf", 37, 14);
        wolf.getMonster().setActionTemplates(MonsterTemplates.parse(WOLF_AND_GRELL));
        return wolf;
    }

    @Test
    @DisplayName("A hit's failed save knocks the target prone, and its entry logs the save")
    void hitSaveFails() {
        EncounterParticipant wolf = direWolf();

        // 15 + 5 hits AC 18 for 4 + 3 + 3; the Fighter's STR save of 5 + 0 misses DC 13
        MonsterActionResponse response = actionBy(wolf, "Bite", fighter, 15, 4, 3, 5);

        assertEquals(30, fighter.getHpCurrent());
        assertEquals(List.of("prone"), CombatFixture.conditionNames(fighter));
        MonsterActionResponse.TargetResult result = response.targetResults().getFirst();
        assertEquals("HIT", result.attackOutcome());
        assertFalse(result.savedSuccessfully());
        assertEquals(List.of("prone"), result.conditionsApplied());
        assertEquals("Dire Wolf uses Bite on Fighter: d20(15) + 5 = 20 vs AC 18 — hit, 10 piercing damage, "
                + "STR save d20(5) + 0 = 5 vs DC 13 fails, gains prone.", f.entriesAbout(fighter).getFirst()
                .getDescription());
        assertEquals(0, f.dice.remaining());
    }

    @Test
    @DisplayName("A hit's successful save leaves the target standing; the damage still lands")
    void hitSaveSucceeds() {
        EncounterParticipant wolf = direWolf();

        MonsterActionResponse response = actionBy(wolf, "Bite", fighter, 15, 4, 3, 17);

        assertEquals(30, fighter.getHpCurrent());
        assertTrue(CombatFixture.conditionNames(fighter).isEmpty());
        assertTrue(response.targetResults().getFirst().savedSuccessfully());
        assertTrue(response.conditionsInflicted().isEmpty());
        assertEquals("Dire Wolf uses Bite on Fighter: d20(15) + 5 = 20 vs AC 18 — hit, 10 piercing damage, "
                + "STR save d20(17) + 0 = 17 vs DC 13 succeeds against prone.", f.entriesAbout(fighter).getFirst()
                .getDescription());
    }

    @Test
    @DisplayName("A mind flayer's tentacles grapple on every hit, and stun only on a failed Intelligence save (MM)")
    void mindFlayerGrapplesAndStunsOnAFailedSave() {
        EncounterParticipant mindFlayer = f.monster("Mind Flayer", 71, 15);
        mindFlayer.getMonster().setActionTemplates(MonsterTemplates.parse("""
                {"actions": [{"name": "Tentacle", "reach": 5, "effects": [
                   {"damageDice": "2d10+4", "damageType": "psychic", "effectType": "DAMAGE"},
                   {"escapeDC": 15, "condition": "grappled", "effectType": "CONDITION", "durationText": "until escape",
                    "durationRounds": null},
                   {"condition": "stunned", "effectType": "CONDITION"}],
                 "actionType": "ACTION", "attackType": "MELEE_WEAPON", "attackBonus": 7, "targetCount": 1,
                 "deliveryMethod": "ATTACK_ROLL", "secondarySaveDC": 15, "secondarySaveAbility": "INT"}]}"""));
        EncounterParticipant rogue = f.player("Rogue", 30, 14);

        // Each hits: 15 + 7 against AC 18 and 14, for 5 + 5 + 4. The Fighter's INT save of 16 + 0 makes DC 15;
        // the Rogue's 4 + 0 doesn't
        actionBy(mindFlayer, "Tentacle", fighter, 15, 5, 5, 16);
        actionBy(mindFlayer, "Tentacle", rogue, 15, 5, 5, 4);

        assertEquals(List.of("grappled"), CombatFixture.conditionNames(fighter));
        assertEquals(List.of("grappled", "stunned"), CombatFixture.conditionNames(rogue));
        assertEquals("Mind Flayer uses Tentacle on Fighter: d20(15) + 7 = 22 vs AC 18 — hit, 14 psychic damage, "
                + "INT save d20(16) + 0 = 16 vs DC 15 succeeds against stunned, gains grappled (escape DC 15).",
                f.entriesAbout(fighter).getFirst().getDescription());
        assertEquals("Mind Flayer uses Tentacle on Rogue: d20(15) + 7 = 22 vs AC 14 — hit, 14 psychic damage, "
                + "INT save d20(4) + 0 = 4 vs DC 15 fails, gains grappled (escape DC 15), stunned.",
                f.entriesAbout(rogue).getFirst().getDescription());
        assertEquals(0, f.dice.remaining());
    }

    @Test
    @DisplayName("A hit's failed save by a creature with Legendary Resistance left is offered for undoing: it "
            + "removes only the conditions the save gave, and the damage stands (D163)")
    void legendaryResistanceOnAHitSave() {
        EncounterParticipant wolf = direWolf();
        EncounterParticipant lich = f.monster("Lich", 135, 17);
        lich.setResourcePoolsCurrent(CombatFixture.legendaryResistance(3));

        // A flat 4 piercing, the grapple, and a CON save of 2 + 0 against DC 11 that fails
        MonsterActionResponse response = actionBy(wolf, "Tentacle", lich, 15, 2);

        assertTrue(response.legendaryResistanceAvailable());
        assertEquals(List.of("grappled", "poisoned"), CombatFixture.conditionNames(lich));
        CombatLog entry = f.entriesAbout(lich).getFirst();
        assertTrue(entry.getLegendaryResistanceEligible());
        assertEquals("poisoned", entry.getLrConditionsApplied());
        assertEquals(List.of(lich.getActiveConditions().get(1).id()), entry.getLrConditionIds());
        assertEquals(4, entry.getLrDamageDealt());
        assertEquals(4, entry.getLrSuccessDamage());

        when(f.combatLogRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        f.legendaryResistance.useLegendaryResistance(f.encounterId(), entry.getId(), f.dmUserId);

        assertEquals(List.of("grappled"), CombatFixture.conditionNames(lich));
        assertEquals(131, lich.getHpCurrent());
        assertEquals(2, pool(lich, EffectApplier.LEGENDARY_RESISTANCE_POOL));
        assertEquals("Lich uses Legendary Resistance and succeeds on the save instead: loses poisoned (2 remaining)",
                f.log.getLast().getDescription());
    }

    @Test
    @DisplayName("Legendary Resistance isn't offered on a hit's successful save, or on a failed one by a creature "
            + "the hit killed")
    void legendaryResistanceNotOfferedOnAHitSave() {
        EncounterParticipant wolf = direWolf();
        EncounterParticipant lich = f.monster("Lich", 135, 17);
        lich.setResourcePoolsCurrent(CombatFixture.legendaryResistance(3));
        EncounterParticipant imp = f.monster("Imp", 3, 13);
        imp.setResourcePoolsCurrent(CombatFixture.legendaryResistance(3));

        MonsterActionResponse saved = actionBy(wolf, "Bite", lich, 15, 4, 3, 18);
        MonsterActionResponse killed = actionBy(wolf, "Bite", imp, 15, 4, 3, 2);

        assertFalse(saved.legendaryResistanceAvailable());
        assertNull(f.entriesAbout(lich).getFirst().getLegendaryResistanceEligible());
        assertEquals(LifeState.DEAD, imp.getLifeState());
        assertFalse(killed.legendaryResistanceAvailable());
        assertNull(f.entriesAbout(imp).getFirst().getLegendaryResistanceEligible());
        assertTrue(CombatFixture.conditionNames(imp).isEmpty());
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
                new MonsterActionRequest(dragon.getId(), "Bite", "ACTION", List.of(fighter.getId()), null, null, null,
                        null), playerUserId));
    }

    @Test
    @DisplayName("A monster's action, a Multiattack included, costs its action; legendary actions cost none, and the "
            + "DM's monster over budget isn't logged as over (D174)")
    void actionEconomy() {
        f.turnOf(dragon);
        multiattack(List.of(List.of(fighter), List.of(fighter)), 5, 5);
        assertEquals(1, dragon.getTurnEconomy().actionsUsed());

        act("Claw", "ACTION", List.of(fighter), 5);
        assertEquals(2, dragon.getTurnEconomy().actionsUsed());
        assertTrue(f.descriptions().stream().noneMatch(entry -> entry.contains("over the action economy")));

        f.turnOf(fighter);
        act("Wing Attack", "LEGENDARY", List.of(fighter), 15);
        assertEquals(2, dragon.getTurnEconomy().actionsUsed());
    }

    @Test
    @DisplayName("A bonus action resolves like an action and costs the monster's bonus action on its turn, not its "
            + "action; a second goes over without being refused or logged as over (D138, D174)")
    void bonusAction() {
        f.turnOf(dragon);

        // 15 + 10 hits AC 18 for a flat 5
        act("Tail Flick", "BONUS", List.of(fighter), 15);
        act("Tail Flick", "BONUS", List.of(fighter), 15);

        assertTrue(dragon.getTurnEconomy().bonusActionUsed());
        assertEquals(0, dragon.getTurnEconomy().actionsUsed());
        assertEquals(30, fighter.getHpCurrent());
        assertEquals("Young Dragon uses Tail Flick (bonus action).", f.descriptions().getFirst());
        assertTrue(f.descriptions().get(1).startsWith("Young Dragon uses Tail Flick (bonus action) on Fighter: "));
        assertTrue(f.descriptions().stream().noneMatch(entry -> entry.contains("over the action economy")));
        assertTrue(f.log.stream().allMatch(entry -> entry.getActionType() == CombatActionType.MONSTER_ACTION));
    }

    @Test
    @DisplayName("A reaction is taken on another creature's turn and the Lair's, and costs the monster's reaction; "
            + "the DM's second isn't refused or logged as over (D138, D174)")
    void reactionOffTurn() {
        f.turnOf(fighter);

        // The fighter rolls 5 + its DEX save against DC 15 and fails, taking a flat 10 fire
        act("Fiery Rebuke", "REACTION", List.of(fighter), 5);

        assertTrue(dragon.getTurnEconomy().reactionUsed());
        assertEquals(0, dragon.getTurnEconomy().actionsUsed());
        assertFalse(dragon.getTurnEconomy().bonusActionUsed());
        assertEquals(30, fighter.getHpCurrent());
        assertEquals("Young Dragon uses Fiery Rebuke (reaction).", f.descriptions().getFirst());
        assertTrue(f.log.stream().allMatch(entry -> entry.getActionType() == CombatActionType.REACTION_TAKEN));

        f.lairTurn();
        act("Parry", "REACTION", List.of());

        assertTrue(dragon.getTurnEconomy().reactionUsed());
        assertTrue(f.descriptions().stream().noneMatch(entry -> entry.contains("over the action economy")));
    }

    @Test
    @DisplayName("A reaction that changes a hit already resolved, such as Parry, is logged with its note for the DM, "
            + "and changes nothing (Q4)")
    void parryIsLoggedWithItsNote() {
        f.turnOf(fighter);

        MonsterActionResponse response = act("Parry", "REACTION", List.of());

        assertFalse(response.resolved());
        assertTrue(response.requiresManualResolution());
        assertEquals(List.of("Young Dragon uses Parry (reaction) — +3 AC against one melee hit: if it now misses, the DM "
                + "restores its damage by hand."), f.descriptions());
        assertEquals(40, fighter.getHpCurrent());
        assertTrue(dragon.getTurnEconomy().reactionUsed());
        assertEquals(CombatActionType.REACTION_TAKEN, f.log.getFirst().getActionType());
    }

    @Test
    @DisplayName("A bonus action with a d6 recharge spends its pool, started charged for a monster that joined "
            + "without it, and is a 409 until it recharges (D174)")
    void bonusActionRecharge() {
        f.turnOf(dragon);

        // The fighter's CON save of 2 fails against DC 15; the flat 4 fire needs no roll
        act("Smoke Burst", "BONUS", List.of(fighter), 2);

        assertEquals(0, pool(dragon, "monster:recharge:smoke-burst"));
        ResourcePoolEntry started = dragon.getResourcePoolsCurrent().stream()
                .filter(pool -> pool.poolId().equals("monster:recharge:smoke-burst")).findFirst().orElseThrow();
        assertEquals("1d6>=5", started.resetCheck());
        assertEquals("BONUS_ACTION", started.spendActionType());
        ConflictException ex = assertThrows(ConflictException.class,
                () -> act("Smoke Burst", "BONUS", List.of(fighter), 2));
        assertEquals("Recharge action 'Smoke Burst' is not yet recharged", ex.getMessage());
    }

    @Test
    @DisplayName("A bonus action whose use comes back after a rest spends a pool, started full for a monster that "
            + "joined without it, and is a 409 once spent, as nothing refills it mid-encounter (MM p.11)")
    void restLimitedBonusAction() {
        f.turnOf(dragon);

        act("Second Wind", "BONUS", List.of());

        ResourcePoolEntry started = dragon.getResourcePoolsCurrent().stream()
                .filter(pool -> pool.poolId().equals("monster:limited:second-wind")).findFirst().orElseThrow();
        assertEquals(1, started.maxUses());
        assertEquals(0, started.currentUses());
        assertEquals("shortRest", started.resetOn());
        assertEquals("BONUS_ACTION", started.spendActionType());
        ConflictException ex = assertThrows(ConflictException.class, () -> act("Second Wind", "BONUS", List.of()));
        assertEquals("Young Dragon has no uses of Second Wind left: its use comes back after a short or long rest, "
                + "and rests don't happen during an encounter", ex.getMessage());
        assertEquals(1, f.descriptions().stream().filter(entry -> entry.contains("uses Second Wind")).count());
    }

    @Test
    @DisplayName("An action, reaction or legendary action used so many times a day spends one use each time and is a "
            + "409 once they are gone; a legendary one also spends its legendary actions")
    void dailyLimits() {
        dragon.getMonster().setActionTemplates(MonsterTemplates.parse("""
                {"actions": [
                   {"name": "Frightful Roar (2/Day)", "automatable": false, "description": "The dragon roars."}],
                 "reactions": [
                   {"name": "Negate Spell", "usesPerDay": 1, "automatable": false,
                    "description": "The dragon negates a spell."}],
                 "legendaryActions": [
                   {"name": "Drain Life (1/Day)", "cost": 1, "automatable": false,
                    "description": "The dragon drains life."}]}"""));
        f.turnOf(dragon);

        act("Frightful Roar (2/Day)", "ACTION", List.of());
        assertEquals(1, pool(dragon, "monster:limited:frightful-roar-2-day"));
        act("Frightful Roar (2/Day)", "ACTION", List.of());
        assertEquals(0, pool(dragon, "monster:limited:frightful-roar-2-day"));
        ConflictException roar = assertThrows(ConflictException.class,
                () -> act("Frightful Roar (2/Day)", "ACTION", List.of()));
        assertEquals("Young Dragon has no uses of Frightful Roar (2/Day) left: its 2 uses come back after a long rest, "
                + "and rests don't happen during an encounter", roar.getMessage());

        act("Negate Spell", "REACTION", List.of());
        assertEquals(0, pool(dragon, "monster:limited:negate-spell"));
        assertThrows(ConflictException.class, () -> act("Negate Spell", "REACTION", List.of()));

        f.turnOf(fighter);
        act("Drain Life (1/Day)", "LEGENDARY", List.of());
        assertEquals(0, pool(dragon, "monster:limited:drain-life-1-day"));
        assertEquals(2, pool(dragon, "monster:legendary-actions"));
        assertEquals("FREE", dragon.getResourcePoolsCurrent().stream()
                .filter(pool -> pool.poolId().equals("monster:limited:drain-life-1-day")).findFirst().orElseThrow()
                .spendActionType());
        assertThrows(ConflictException.class, () -> act("Drain Life (1/Day)", "LEGENDARY", List.of()));
    }

    @Test
    @DisplayName("A bonus action or reaction is found only in its own list")
    void sourcesFindTheirOwnLists() {
        ValidationException biteAsBonus = assertThrows(ValidationException.class,
                () -> act("Bite", "BONUS", List.of(fighter)));
        ValidationException parryAsAction = assertThrows(ValidationException.class,
                () -> act("Parry", "ACTION", List.of()));

        assertEquals("Action 'Bite' not found in monster's action templates (source: BONUS)", biteAsBonus.getMessage());
        assertEquals("Action 'Parry' not found in monster's action templates (source: ACTION)",
                parryAsAction.getMessage());
        assertTrue(f.log.isEmpty());
    }
}
