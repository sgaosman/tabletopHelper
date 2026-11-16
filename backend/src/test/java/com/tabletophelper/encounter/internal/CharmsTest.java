package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.AttackRollRequest;
import com.tabletophelper.encounter.dto.CastSpellRequest;
import com.tabletophelper.encounter.dto.ConditionRequest;
import com.tabletophelper.encounter.dto.MonsterActionRequest;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.rules.combat.Charm;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The charmed condition's limit (PHB p.290, D224): a charmed creature can't attack its charmer or target it with harmful
 * abilities or magical effects. A player is refused with a 409 and nothing happens; the DM is warned in the log and the
 * command goes ahead (D174). A charm with no charmer recorded limits nothing.
 */
class CharmsTest {

    private static final String FIRE_BOLT = """
            {"spellName": "Fire Bolt", "spellLevel": 0, "deliveryMethod": "SPELL_ATTACK", "spellAttackType": "RANGED",
             "components": {"verbal": true, "somatic": true},
             "effects": [{"effectType": "DAMAGE", "damageDice": "1d10", "damageType": "fire"}],
             "conditionsInflicted": null}""";

    private static final String CURE_WOUNDS = """
            {"spellName": "Cure Wounds", "spellLevel": 1, "deliveryMethod": "AUTO_HIT", "effects": [],
             "healing": {"healingDice": "1d8+MOD", "upcastScaling": {"additionalDicePerLevel": "1d8"}}}""";

    private CombatFixture f;
    private EncounterParticipant rogue;
    private EncounterParticipant vampire;
    private EncounterParticipant goblin;
    private UUID player;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        rogue = f.player("Rogue", 30, 14);
        vampire = f.monster("Vampire", 60, 10);
        goblin = f.monster("Goblin", 20, 10);
        player = f.playerControlling(rogue);
        f.turnOf(rogue);
    }

    private void charm(EncounterParticipant creature, EncounterParticipant charmer) {
        f.effectApplier.addCondition(f.encounter, creature, Charm.CHARMED, null, charmer);
    }

    private static AttackRollRequest attack(EncounterParticipant target) {
        AttackRollRequest request = new AttackRollRequest();
        request.setTargetId(target.getId());
        request.setAttackBonus(5);
        request.setDamageDice("6");
        return request;
    }

    private CastSpellRequest cast(String spell, int slotLevel, EncounterParticipant target) {
        f.knows(rogue, spell);
        CastSpellRequest request = new CastSpellRequest();
        request.setSpellName(spell);
        request.setSlotLevel(slotLevel);
        request.setTargetIds(List.of(target.getId()));
        return request;
    }

    @Test
    @DisplayName("A charmed player character can't attack its charmer: a 409 and nothing rolls; another creature it can")
    void playerAttackRefused() {
        charm(rogue, vampire);

        ConflictException refused = assertThrows(ConflictException.class,
                () -> f.attacks.rollAttack(f.encounterId(), attack(vampire), rogue.getId(), player));
        f.dice.script(15);
        f.attacks.rollAttack(f.encounterId(), attack(goblin), rogue.getId(), player);

        assertEquals("Rogue is charmed by Vampire, so it can't attack Vampire or target it with harmful abilities or "
                + "magical effects (PHB p.290)", refused.getMessage());
        assertEquals(60, vampire.getHpCurrent());
        assertEquals(14, goblin.getHpCurrent());
    }

    @Test
    @DisplayName("The DM's attack on the charmer goes ahead, with a warning in the log")
    void dmWarned() {
        charm(rogue, vampire);

        f.dice.script(15);
        f.attacks.rollAttack(f.encounterId(), attack(vampire), rogue.getId(), f.dmUserId);

        assertEquals(54, vampire.getHpCurrent());
        assertTrue(f.descriptions().contains("Charmed: Rogue is charmed by Vampire, so it can't attack Vampire or "
                + "target it with harmful abilities or magical effects (PHB p.290); the DM's command goes ahead."),
                f.descriptions().toString());
    }

    @Test
    @DisplayName("A charm with no charmer recorded, or another creature's charm, limits nothing")
    void noRecordedCharmer() {
        f.effectApplier.addCondition(f.encounter, rogue, Charm.CHARMED, null);

        f.dice.script(15);
        f.attacks.rollAttack(f.encounterId(), attack(vampire), rogue.getId(), player);

        assertEquals(54, vampire.getHpCurrent());
    }

    @Test
    @DisplayName("A harmful spell at the charmer is refused for a player and spends nothing; a heal is fine")
    void harmfulSpell() {
        f.spell("Fire Bolt", 0, FIRE_BOLT);
        f.spell("Cure Wounds", 1, CURE_WOUNDS);
        rogue.setSpellSlotsCurrent(Map.of("1", new SpellSlot(2, 0)));
        EncounterParticipant mage = f.player("Mage", 20, 12);
        mage.setHpCurrent(5);
        charm(rogue, mage);

        assertThrows(ConflictException.class,
                () -> f.spellcasting.castSpell(f.encounterId(), cast("Fire Bolt", 0, mage), rogue.getId(), player));
        assertEquals(5, mage.getHpCurrent());
        f.dice.script(4);
        f.spellcasting.castSpell(f.encounterId(), cast("Cure Wounds", 1, mage), rogue.getId(), player);

        assertTrue(mage.getHpCurrent() > 5);
    }

    @Test
    @DisplayName("A charmed monster's action at its charmer warns the DM and goes ahead")
    void monsterAction() {
        goblin.getMonster().setActionTemplates(MonsterTemplates.parse("""
                {"actions": [{"name": "Slam", "deliveryMethod": "AUTO_DAMAGE",
                              "effects": [{"effectType": "DAMAGE", "damageDice": "5", "damageType": "bludgeoning"}]}]}"""));
        charm(goblin, rogue);
        f.turnOf(goblin);

        f.monsterActions.monsterAction(f.encounterId(), new MonsterActionRequest(goblin.getId(), "Slam", "ACTION",
                List.of(rogue.getId()), null, null, null, null), f.dmUserId);

        assertEquals(25, rogue.getHpCurrent());
        assertTrue(f.descriptions().stream().anyMatch(d -> d.startsWith("Charmed: Goblin is charmed by Rogue")),
                f.descriptions().toString());
    }

    @Test
    @DisplayName("The DM may name the charmer when adding charmed; any other condition but frightened still can't")
    void charmerByHand() {
        ConditionRequest request = new ConditionRequest();
        request.setTargetId(rogue.getId());
        request.setCondition("charmed");
        request.setSourceParticipantId(vampire.getId());
        f.conditionService.addCondition(f.encounterId(), request, f.dmUserId);

        assertEquals(vampire.getId(), rogue.getActiveConditions().getFirst().sourceParticipantId());
        assertEquals(List.of(vampire), Charms.charmersAmong(rogue, List.of(vampire, goblin)));

        request.setCondition("prone");
        assertThrows(ValidationException.class,
                () -> f.conditionService.addCondition(f.encounterId(), request, f.dmUserId));
    }
}
