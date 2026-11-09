package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.AttackRollPreview;
import com.tabletophelper.encounter.dto.AttackRollRequest;
import com.tabletophelper.encounter.dto.CastSpellRequest;
import com.tabletophelper.encounter.dto.MonsterActionRequest;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.rules.combat.RollMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.tabletophelper.encounter.internal.CombatFixture.weapon;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The attacker's and the target's conditions and senses on every attack path (PHB appendix A, p.290-292): a weapon
 * attack, a spell attack and a monster's attack action, and the preview the attack form shows. Vex is a fighter with a
 * rapier (+2 to hit, 1d8), Wren a caster with +5 to hit and Fire Bolt, and the goblin has a +4 scimitar and AC 10. The
 * DM acts.
 */
class ConditionAttackRollsTest {

    private static final String FIRE_BOLT = """
            {"spellName": "Fire Bolt", "spellLevel": 0, "deliveryMethod": "SPELL_ATTACK",
             "spellAttackType": "RANGED", "effects": [{"effectType": "DAMAGE", "damageDice": "1d10",
             "damageType": "fire"}]}""";

    private static final String SCIMITAR = """
            {"actions": [{"name": "Scimitar", "actionType": "ACTION", "deliveryMethod": "ATTACK_ROLL",
              "attackType": "MELEE_WEAPON", "attackBonus": 4, "targetCount": 1,
              "effects": [{"effectType": "DAMAGE", "damageDice": "1d6 + 2", "damageType": "slashing"}]}]}""";

    private CombatFixture f;
    private EncounterParticipant vex;
    private EncounterParticipant wren;
    private EncounterParticipant goblin;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        f.recordCommands();
        f.started();
        vex = f.player("Vex", 40, 18);
        wren = f.player("Wren", 20, 12);
        wren.setSpellAttackBonus(5);
        goblin = f.monster("Goblin", 30, 10);
        goblin.getMonster().setActionTemplates(MonsterTemplates.parse(SCIMITAR));
        f.weapons(weapon("Rapier", "Melee Weapon", "martial", "1d8", "piercing", "F"));
        CombatFixture.carries(vex, List.of("simple", "martial"), "Rapier");
        f.spell("Fire Bolt", 0, FIRE_BOLT);
    }

    private static void has(EncounterParticipant participant, String... conditions) {
        List<ConditionEntry> entries = new ArrayList<>();
        for (String condition : conditions) {
            entries.add(ConditionEntry.manual(condition, null, 1));
        }
        participant.setActiveConditions(entries);
    }

    private void rapierAttack(EncounterParticipant target, int... dice) {
        AttackRollRequest request = new AttackRollRequest();
        request.setTargetId(target.getId());
        request.setWeapon("Rapier");
        f.dice.script(dice);
        f.inTransaction(() -> f.attacks.rollAttack(f.encounterId(), request, vex.getId(), f.dmUserId));
    }

    private void scimitar(EncounterParticipant target, int... dice) {
        f.turnOf(goblin);
        f.dice.script(dice);
        f.inTransaction(() -> f.monsterActions.monsterAction(f.encounterId(), new MonsterActionRequest(goblin.getId(),
                "Scimitar", "ACTION", List.of(target.getId()), null, null, null, null), f.dmUserId));
    }

    private String lastAbout(EncounterParticipant target) {
        return f.entriesAbout(target).getLast().getDescription();
    }

    @Test
    @DisplayName("A weapon attack: a poisoned attacker against a restrained target rolls one d20, naming both")
    void weaponAttackCancels() {
        has(vex, "poisoned");
        has(goblin, "restrained");

        rapierAttack(goblin, 15, 4);

        assertEquals("Vex uses Rapier on Goblin (advantage: target restrained; disadvantage: attacker poisoned): "
                + "d20(15) + 2 = 17 vs AC 10 — hit, 4 piercing damage.", lastAbout(goblin));
    }

    @Test
    @DisplayName("A weapon attack on a stunned target has advantage; the player's own disadvantage cancels it")
    void weaponAttackWithOwnChoice() {
        has(goblin, "stunned");
        rapierAttack(goblin, 3, 15, 4);
        assertTrue(lastAbout(goblin).startsWith("Vex uses Rapier on Goblin (advantage: target stunned): 2d20(3, 15) "
                + "with advantage → 15"), lastAbout(goblin));

        AttackRollRequest request = new AttackRollRequest();
        request.setTargetId(goblin.getId());
        request.setWeapon("Rapier");
        request.setAdvantage(false);
        f.dice.script(15, 4);
        f.inTransaction(() -> f.attacks.rollAttack(f.encounterId(), request, vex.getId(), f.dmUserId));
        assertTrue(lastAbout(goblin).startsWith("Vex uses Rapier on Goblin (advantage: target stunned): d20(15)"),
                lastAbout(goblin));
    }

    @Test
    @DisplayName("A spell attack by a blinded caster has disadvantage")
    void spellAttack() {
        has(wren, "blinded");
        f.knows(wren, "Fire Bolt");
        CastSpellRequest request = new CastSpellRequest();
        request.setSpellName("Fire Bolt");
        request.setSlotLevel(0);
        request.setTargetIds(new ArrayList<>(List.of(goblin.getId())));
        f.dice.script(15, 3, 5);

        f.inTransaction(() -> f.spellcasting.castSpell(f.encounterId(), request, wren.getId(), f.dmUserId));

        assertTrue(f.descriptions().stream().anyMatch(d -> d.contains("(disadvantage: attacker blinded): "
                + "2d20(15, 3) with disadvantage → 3")), String.join("; ", f.descriptions()));
    }

    @Test
    @DisplayName("A monster's attack: an invisible target gives disadvantage, unless the monster has blindsight")
    void monsterAttackAndBlindsight() {
        has(vex, "invisible");
        scimitar(vex, 15, 3, 4);
        assertTrue(lastAbout(vex).startsWith("Goblin uses Scimitar on Vex (disadvantage: target invisible): "
                + "2d20(15, 3) with disadvantage"), lastAbout(vex));

        goblin.getMonster().setSenses("{\"special\":[\"blindsight 10 ft.\"],\"passive_perception\":9}");
        CombatFixture.newTurn(goblin);
        scimitar(vex, 15, 4);
        assertTrue(lastAbout(vex).startsWith("Goblin uses Scimitar on Vex: d20(15)"), lastAbout(vex));
    }

    @Test
    @DisplayName("A target with truesight denies an invisible attacker its advantage")
    void truesightTarget() {
        has(vex, "invisible");
        goblin.getMonster().setSenses("[\"truesight 120 ft.\"]");

        rapierAttack(goblin, 15, 4);

        assertTrue(lastAbout(goblin).startsWith("Vex uses Rapier on Goblin: d20(15)"), lastAbout(goblin));
    }

    @Test
    @DisplayName("The preview names the sources between attacker and target, prone by distance")
    void preview() {
        has(vex, "invisible");
        has(goblin, "prone");

        AttackRollPreview close = f.attacks.attackRollPreview(f.encounterId(), vex.getId(), goblin.getId(), true,
                f.dmUserId);
        AttackRollPreview far = f.attacks.attackRollPreview(f.encounterId(), vex.getId(), goblin.getId(), false,
                f.dmUserId);

        assertEquals(RollMode.ADVANTAGE, close.rollMode());
        assertEquals(List.of(new AttackRollPreview.RollSource(RollMode.ADVANTAGE, "attacker invisible"),
                new AttackRollPreview.RollSource(RollMode.ADVANTAGE, "target prone within 5 ft")), close.sources());
        assertFalse(close.hitIsCritical());
        assertEquals(RollMode.NORMAL, far.rollMode());
        assertEquals(new AttackRollPreview.RollSource(RollMode.DISADVANTAGE, "target prone beyond 5 ft"),
                far.sources().get(1));
    }

    @Test
    @DisplayName("The preview of an attack on a paralyzed target from within 5 ft says a hit is critical")
    void previewCritical() {
        has(goblin, "paralyzed");

        AttackRollPreview preview = f.attacks.attackRollPreview(f.encounterId(), vex.getId(), goblin.getId(), true,
                f.dmUserId);

        assertEquals(RollMode.ADVANTAGE, preview.rollMode());
        assertTrue(preview.hitIsCritical());
    }
}
