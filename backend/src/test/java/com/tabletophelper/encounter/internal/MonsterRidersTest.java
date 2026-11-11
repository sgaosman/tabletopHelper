package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.MonsterActionRequest;
import com.tabletophelper.encounter.dto.MonsterActionResponse;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.EndRule;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.Resolver;
import com.tabletophelper.rules.combat.RollMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A monster hit's riders at the table (D202): damage a save guards, half or none on a success and not doubled on a
 * critical hit; a condition's size limit; damage that waits on the target's condition or creature type, the
 * attacker's advantage, form or hit points; and a condition that ends at the end of the user's next turn. The fighter
 * has every ability at 10 (save +0), is Medium and humanoid, and has AC 18.
 */
class MonsterRidersTest {

    private CombatFixture f;
    private EncounterParticipant render;
    private EncounterParticipant fighter;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        fighter = f.player("Fighter", 60, 18);
        render = f.monster("Render", 100, 15);
        render.getMonster().setActionTemplates(MonsterTemplates.parse("""
                {"actions": [
                   {"name": "Poison Bite", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 5,
                    "attackType": "MELEE_WEAPON",
                    "effects": [{"effectType": "DAMAGE", "damageDice": "1d10 + 2", "damageType": "piercing"},
                                {"effectType": "DAMAGE", "damageDice": "4d8", "damageType": "poison",
                                 "saveDC": 11, "saveAbility": "CON", "halfOnSave": true}]},
                   {"name": "Sting", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 5,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "1", "damageType": "piercing",
                                 "flatDamage": true},
                                {"effectType": "DAMAGE", "damageDice": "1d8", "damageType": "poison",
                                 "saveDC": 9, "saveAbility": "CON", "halfOnSave": false}]},
                   {"name": "Crush", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 5,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "2d6 + 3", "damageType": "bludgeoning"},
                                {"effectType": "CONDITION", "condition": "grappled", "escapeDC": 13,
                                 "maxTargetSize": "Large"},
                                {"effectType": "CONDITION", "condition": "prone", "saveDC": 13,
                                 "saveAbility": "STR", "maxTargetSize": "Medium"}]},
                   {"name": "Claw", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 5,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "2d8 + 4", "damageType": "slashing"},
                                {"effectType": "DAMAGE", "damageDice": "3d6", "damageType": "bludgeoning",
                                 "onlyIf": {"targetCondition": "prone", "text": "if the target is prone"}}]},
                   {"name": "Smite", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 5,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "1d6", "damageType": "slashing"},
                                {"effectType": "DAMAGE", "damageDice": "2d8", "damageType": "radiant",
                                 "onlyIf": {"targetType": ["fiend", "undead"],
                                            "text": "if the target is a fiend or undead"}}]},
                   {"name": "Ambush", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 5,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "1d4", "damageType": "piercing"},
                                {"effectType": "DAMAGE", "damageDice": "2d6", "damageType": "piercing",
                                 "onlyIf": {"attackerAdvantage": true,
                                            "text": "if the render had advantage on the attack roll"}}]},
                   {"name": "War Pick", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 5,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "1d8 + 2", "damageType": "piercing"},
                                {"effectType": "DAMAGE", "damageDice": "2d8 + 2", "damageType": "piercing",
                                 "onlyIf": {"attackerForm": "enlarged", "text": "while enlarged"},
                                 "replacesDamage": true}]},
                   {"name": "Bites", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 5,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "4d6", "damageType": "piercing"},
                                {"effectType": "DAMAGE", "damageDice": "2d6", "damageType": "piercing",
                                 "onlyIf": {"attackerBloodied": true,
                                            "text": "if the swarm has half of its hit points or fewer"},
                                 "replacesDamage": true}]},
                   {"name": "Stomp", "deliveryMethod": "SAVING_THROW", "saveDC": 15, "saveAbility": "STR",
                    "effects": [{"effectType": "DAMAGE", "damageDice": "10", "damageType": "bludgeoning"},
                                {"effectType": "CONDITION", "condition": "prone", "maxTargetSize": "Large"}]},
                   {"name": "Glare", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 5,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "1d4", "damageType": "psychic"},
                                {"effectType": "CONDITION", "condition": "blinded", "durationRounds": 1,
                                 "endOfUsersTurn": true}]}]}"""));
    }

    private MonsterActionResponse act(String action, EncounterParticipant target, Boolean advantage, int... rolls) {
        f.dice.script(rolls);
        MonsterActionResponse response = f.monsterActions.monsterAction(f.encounterId(),
                new MonsterActionRequest(render.getId(), action, "ACTION", List.of(target.getId()), null, null,
                        advantage, null, null), f.dmUserId);
        assertEquals(0, f.dice.remaining(), "every scripted roll is used");
        return response;
    }

    private MonsterActionResponse act(String action, EncounterParticipant target, int... rolls) {
        return act(action, target, null, rolls);
    }

    private EncounterParticipant giant(String size) {
        EncounterParticipant giant = f.monster("Giant", 100, 10);
        giant.getMonster().setSize(size);
        return giant;
    }

    private static void give(EncounterParticipant participant, String condition) {
        participant.setActiveConditions(new ArrayList<>(List.of(ConditionEntry.manual(condition, null, 1))));
    }

    @Test
    @DisplayName("Damage a failed save deals: the hit's piercing stands and the 4d8 poison comes in full")
    void guardedDamageOnAFailedSave() {
        // d20 15 + 5 hits; 1d10 5 + 2 = 7 piercing; CON save 4 < 11 fails; 4d8 = 12 poison
        MonsterActionResponse response = act("Poison Bite", fighter, 15, 5, 4, 3, 3, 3, 3);

        assertEquals(60 - 19, fighter.getHpCurrent());
        assertFalse(response.targetResults().getFirst().savedSuccessfully());
        assertTrue(f.descriptions().getLast().contains("CON save d20(4) + 0 = 4 vs DC 11 fails"),
                f.descriptions().getLast());
    }

    @Test
    @DisplayName("A successful save halves the poison, and the log names what the save guarded")
    void guardedDamageHalvedOnASuccess() {
        // 7 piercing; CON save 18 succeeds; 4d8 = 16, halved to 8
        act("Poison Bite", fighter, 15, 5, 18, 4, 4, 4, 4);

        assertEquals(60 - 15, fighter.getHpCurrent());
        assertTrue(f.descriptions().getLast().contains("succeeds against 4d8 poison damage (half taken)"),
                f.descriptions().getLast());
    }

    @Test
    @DisplayName("Damage a success prevents isn't rolled on a success, and comes in full on a failure")
    void guardedDamageNoneOnASuccess() {
        // The flat 1 piercing rolls nothing; CON save 18 succeeds, so the 1d8 isn't rolled
        act("Sting", fighter, 15, 18);
        assertEquals(59, fighter.getHpCurrent());

        // CON save 2 fails; 1d8 6 poison
        act("Sting", fighter, 15, 2, 6);
        assertEquals(59 - 7, fighter.getHpCurrent());
    }

    @Test
    @DisplayName("A critical hit doubles the hit's own dice but not the damage its save guards (PHB p.196)")
    void criticalHitDoesNotDoubleGuardedDamage() {
        // d20 20 is a critical hit: 2d10 5 + 5 + 2 = 12 piercing; CON save fails; 4d8, not 8d8, = 4 poison
        act("Poison Bite", fighter, 20, 5, 5, 2, 1, 1, 1, 1);

        assertEquals(60 - 16, fighter.getHpCurrent());
    }

    @Test
    @DisplayName("A Huge target is too big for a Large-or-smaller grapple and a Medium-or-smaller prone: no save, "
            + "no conditions, and the log says why")
    void sizeLimitsKeepConditionsFromABigTarget() {
        EncounterParticipant huge = giant("Huge");

        // 15 hits; 2d6 3 + 3 + 3 = 9; no save is rolled, since the prone it would guard is out of reach
        act("Crush", huge, 15, 3, 3);

        assertEquals(91, huge.getHpCurrent());
        assertEquals(List.of(), CombatFixture.conditionNames(huge));
        List<String> log = f.descriptions();
        assertTrue(log.contains("Giant is Huge, too big to be grappled by Crush (Large or smaller)."), log::toString);
        assertTrue(log.contains("Giant is Huge, too big to be prone by Crush (Medium or smaller)."), log::toString);
    }

    @Test
    @DisplayName("A Large target is grappled but too big for the prone, and a Medium one gets both on a failed save")
    void sizeLimitsByCondition() {
        EncounterParticipant large = giant("Large");
        act("Crush", large, 15, 3, 3);
        assertEquals(List.of("grappled"), CombatFixture.conditionNames(large));

        // The Medium fighter: 15 hits, 2d6 3 + 3, STR save 2 fails
        act("Crush", fighter, 15, 3, 3, 2);
        assertEquals(List.of("grappled", "prone"), CombatFixture.conditionNames(fighter));
    }

    @Test
    @DisplayName("A save action's condition with a size limit doesn't reach a bigger creature that fails the save")
    void saveActionSizeLimit() {
        EncounterParticipant huge = giant("Huge");
        // STR save 2 fails: 10 bludgeoning, but a Huge creature isn't knocked prone
        act("Stomp", huge, 2);

        assertEquals(90, huge.getHpCurrent());
        assertEquals(List.of(), CombatFixture.conditionNames(huge));
        assertTrue(f.descriptions().contains("Giant is Huge, too big to be prone by Stomp (Large or smaller)."));
    }

    @Test
    @DisplayName("Extra damage against a prone target is dealt only while the target is prone")
    void extraDamageAgainstAProneTarget() {
        // Not prone: 15 hits, 2d8 4 + 4 + 4 = 12; the 3d6 is skipped and logged
        act("Claw", fighter, 15, 4, 4);
        assertEquals(48, fighter.getHpCurrent());
        assertTrue(f.descriptions().contains("Claw's 3d6 bludgeoning (if the target is prone) doesn't apply to "
                + "Fighter."), f.descriptions()::toString);

        // Prone: the melee attack has advantage (d20s 15 and 3), 12 slashing and 3d6 2 + 2 + 2 = 6 bludgeoning
        give(fighter, "prone");
        act("Claw", fighter, 15, 3, 4, 4, 2, 2, 2);
        assertEquals(48 - 18, fighter.getHpCurrent());
    }

    @Test
    @DisplayName("Extra damage against fiends and undead reaches an undead target but not a humanoid one")
    void extraDamageByCreatureType() {
        EncounterParticipant zombie = f.monster("Zombie", 30, 8);
        zombie.getMonster().setType("undead");
        act("Smite", zombie, 15, 3, 5, 5);
        assertEquals(30 - 13, zombie.getHpCurrent());

        act("Smite", fighter, 15, 3);
        assertEquals(57, fighter.getHpCurrent());
    }

    @Test
    @DisplayName("Extra damage for advantage on the attack roll comes only when the roll had advantage")
    void extraDamageWithAdvantage() {
        // With advantage: d20s 15 and 12, 1d4 2, 2d6 3 + 3
        act("Ambush", fighter, true, 15, 12, 2, 3, 3);
        assertEquals(52, fighter.getHpCurrent());

        // Without: 1d4 2 only
        act("Ambush", fighter, 15, 2);
        assertEquals(50, fighter.getHpCurrent());
    }

    @Test
    @DisplayName("Damage in a form the app can't see the attacker in is left to the DM; enlarged, it replaces the "
            + "usual damage")
    void damageInAForm() {
        act("War Pick", fighter, 15, 4);
        assertEquals(54, fighter.getHpCurrent());
        assertTrue(f.descriptions().contains("War Pick's 2d8 + 2 piercing (while enlarged) is left to the DM: Render "
                + "isn't known to be enlarged."), f.descriptions()::toString);

        give(render, "enlarged");
        act("War Pick", fighter, 15, 4, 4);
        assertEquals(54 - 10, fighter.getHpCurrent());
    }

    @Test
    @DisplayName("A swarm at half its hit points or fewer deals its smaller damage instead")
    void bloodiedSwarm() {
        act("Bites", fighter, 15, 1, 1, 1, 1);
        assertEquals(56, fighter.getHpCurrent());

        render.setHpCurrent(50);
        act("Bites", fighter, 15, 1, 1);
        assertEquals(54, fighter.getHpCurrent());
    }

    @Test
    @DisplayName("A condition until the end of the user's next turn ends at the end of the user's turn")
    void endsAtTheEndOfTheUsersTurn() {
        act("Glare", fighter, 15, 2);

        ConditionEntry blinded = fighter.getActiveConditions().getFirst();
        assertEquals("blinded", blinded.name());
        assertEquals(EndRule.endOfTurn(render.getId()), blinded.endRule());
    }

    @Test
    @DisplayName("On a failed hit save, what a success would have left is the hit's damage and half the guarded "
            + "damage, which Legendary Resistance applies instead (D163)")
    void successDamageForLegendaryResistance() {
        Effect.HitSave save = new Effect.HitSave(Ability.CON, 11, List.of(),
                List.of(new Effect.DamagePart(Dice.parse("4d8"), "poison")), true);
        Effect effect = new Effect("Bite", new Effect.Attack(5, RollMode.NORMAL,
                true, false, save), List.of(new Effect.DamagePart(Dice.parse("1d10 + 2"),
                "piercing")), null, List.of(), false, null);
        f.dice.script(15, 5, 2, 4, 4, 4, 4);
        Resolver.TargetOutcome outcome = new Resolver(
                f.dice).resolve(effect, List.of(new Resolver.Target(UUID.randomUUID(),
                12, 0, List.of()))).getFirst();

        assertEquals(List.of(new Resolver.Damage("piercing", 7),
                new Resolver.Damage("poison", 16)), outcome.damage());
        assertEquals(List.of(new Resolver.Damage("piercing", 7),
                new Resolver.Damage("poison", 8)), outcome.successDamage());
    }

    @Test
    @DisplayName("The plan keeps guarded damage on the hit's save and conditional damage as a rider")
    void planShape() {
        MonsterTemplates templates = MonsterTemplates.of(render.getMonster());
        MonsterEffects.MonsterAction bite = f.monsterEffects.plan(templates.find("ACTION", "Poison Bite")
                .orElseThrow(), templates, List.of(UUID.randomUUID()), MonsterEffects.Overrides.NONE);
        Effect effect = bite.steps().getFirst().effect();
        Effect.Attack attack = assertInstanceOf(Effect.Attack.class, effect.delivery());
        assertEquals(1, effect.damage().size());
        assertEquals(Ability.CON, attack.hitSave().ability());
        assertEquals(List.of(), attack.hitSave().conditions());
        assertEquals("poison", attack.hitSave().damage().getFirst().type());
        assertTrue(attack.hitSave().halfDamageOnSuccess());

        MonsterEffects.MonsterAction claw = f.monsterEffects.plan(templates.find("ACTION", "Claw").orElseThrow(),
                templates, List.of(UUID.randomUUID()), MonsterEffects.Overrides.NONE);
        assertEquals(1, claw.steps().getFirst().effect().damage().size());
        assertEquals("prone", claw.steps().getFirst().riders().conditionalDamage().getFirst().onlyIf()
                .targetCondition());
    }
}
