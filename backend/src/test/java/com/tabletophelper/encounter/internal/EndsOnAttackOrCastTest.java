package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.AttackRollRequest;
import com.tabletophelper.encounter.dto.CastSpellRequest;
import com.tabletophelper.encounter.dto.MonsterActionRequest;
import com.tabletophelper.encounter.dto.StandardActionRequest;
import com.tabletophelper.encounter.dto.UndoRequest;
import com.tabletophelper.encounter.dto.UsePoolRequest;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.EndRule;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.reference.SpellTemplate;
import com.tabletophelper.rules.combat.StandardActions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static com.tabletophelper.encounter.internal.CombatFixture.weapon;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Invisibility that ends when its creature attacks or casts a spell: the Invisibility spell's, "until the spell ends
 * for a target that attacks or casts a spell" (PHB p.254), and Cloak of Shadows', "You become visible if you attack or
 * cast a spell" (PHB p.63). It ends once that attack or spell is resolved, and undo brings it back. Wren is a wizard
 * with 2nd- and 3rd-level slots, Vex and Ash fighters carrying a rapier (+2 to hit, 1d8), and Tam a Trickery cleric.
 * The DM acts.
 */
class EndsOnAttackOrCastTest {

    private static final String CLERIC_CD = "class:cleric-channel-divinity";

    private static final String FIRE_BOLT = """
            {"spellName": "Fire Bolt", "spellLevel": 0, "deliveryMethod": "SPELL_ATTACK",
             "spellAttackType": "RANGED", "effects": [{"effectType": "DAMAGE", "damageDice": "1d10",
             "damageType": "fire"}]}""";

    private static final String SCIMITAR = """
            {"actions": [{"name": "Scimitar", "actionType": "ACTION", "deliveryMethod": "ATTACK_ROLL",
              "attackType": "MELEE_WEAPON", "attackBonus": 4, "targetCount": 1,
              "effects": [{"effectType": "DAMAGE", "damageDice": "1d6 + 2", "damageType": "slashing"}]}]}""";

    private CombatFixture f;
    private EncounterParticipant wren;
    private EncounterParticipant vex;
    private EncounterParticipant ash;
    private EncounterParticipant tam;
    private EncounterParticipant goblin;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        f.recordCommands();
        f.started();
        wren = f.player("Wren", 20, 12);
        wren.setSpellAttackBonus(5);
        wren.setSpellSaveDc(13);
        wren.setSpellSlotsCurrent(new HashMap<>(Map.of("2", new SpellSlot(3, 0), "3", new SpellSlot(2, 0))));
        vex = f.player("Vex", 40, 18);
        ash = f.player("Ash", 40, 18);
        tam = f.player("Tam", 30, 16);
        tam.setSpellAttackBonus(5);
        goblin = f.monster("Goblin", 30, 10);
        goblin.getMonster().setActionTemplates(MonsterTemplates.parse(SCIMITAR));
        f.weapons(weapon("Rapier", "Melee Weapon", "martial", "1d8", "piercing", "F"));
        CombatFixture.carries(vex, List.of("simple", "martial"), "Rapier");
        CombatFixture.carries(ash, List.of("simple", "martial"), "Rapier");
        CombatFixture.carries(tam, List.of("simple", "martial"), "Rapier");
        f.spell("Invisibility", 2, definition("Invisibility"));
        f.spell("Fire Bolt", 0, FIRE_BOLT);
    }

    // The spell's definition as the app's data gives it
    private static String definition(String spellName) {
        for (JsonNode node : definitions()) {
            if (spellName.equals(node.path("spellName").asString())) {
                return node.toString();
            }
        }
        throw new AssertionError(spellName + " isn't in the spell effect templates");
    }

    private static JsonNode definitions() {
        try (InputStream in = EndsOnAttackOrCastTest.class.getResourceAsStream(
                "/data/spell-effects/spell-effect-definitions.json")) {
            assertNotNull(in, "the spell effect templates are on the classpath");
            return JsonMapper.builder().build().readTree(in);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private void cast(EncounterParticipant caster, String spell, int slot, List<EncounterParticipant> targets,
                      int... dice) {
        f.knows(caster, spell);
        CastSpellRequest request = new CastSpellRequest();
        request.setSpellName(spell);
        request.setSlotLevel(slot);
        request.setTargetIds(new ArrayList<>(targets.stream().map(EncounterParticipant::getId).toList()));
        f.dice.script(dice);
        f.inTransaction(() -> f.spellcasting.castSpell(f.encounterId(), request, caster.getId(), f.dmUserId));
    }

    private void rapierAttack(EncounterParticipant attacker, int... dice) {
        AttackRollRequest request = new AttackRollRequest();
        request.setTargetId(goblin.getId());
        request.setWeapon("Rapier");
        f.dice.script(dice);
        f.inTransaction(() -> f.attacks.rollAttack(f.encounterId(), request, attacker.getId(), f.dmUserId));
    }

    private void cloakOfShadows() {
        tam.getCharacter().setMulticlassEntries(List.of(new ClassEntry(UUID.randomUUID(), "Cleric",
                UUID.randomUUID(), "Trickery Domain", 6)));
        tam.getCharacter().setLevel(6);
        tam.setResourcePoolsCurrent(new ArrayList<>(List.of(new ResourcePoolEntry(CLERIC_CD, "Channel Divinity",
                "CLASS", null, 2, "2", 2, "shortRest", null, null, "ACTION", "sun",
                Map.of("effect", "channel-divinity")))));
        f.turnOf(tam);
        f.inTransaction(() -> f.poolUse.usePool(f.encounterId(), new UsePoolRequest(tam.getId(), CLERIC_CD, null,
                "cloak-of-shadows", null), f.dmUserId));
        CombatFixture.newTurn(tam);
    }

    private static ConditionEntry invisible(EncounterParticipant participant) {
        return participant.getActiveConditions() == null ? null : participant.getActiveConditions().stream()
                .filter(c -> c.name().equals("invisible")).findFirst().orElse(null);
    }

    @Nested
    @DisplayName("Invisibility (PHB p.254)")
    class InvisibilitySpell {

        @Test
        @DisplayName("The target's invisibility ends when it attacks, after the attack; the caster keeps concentrating")
        void endsWhenTargetAttacks() {
            cast(wren, "Invisibility", 2, List.of(vex));
            ConditionEntry given = invisible(vex);
            assertNotNull(given);
            assertTrue(given.endsWhenAttackingOrCasting());
            assertEquals(EndRule.CONCENTRATION, given.endRule());

            // Invisible as it attacks, so with advantage (PHB p.291)
            rapierAttack(vex, 15, 2, 4);

            assertNull(invisible(vex));
            assertEquals(26, goblin.getHpCurrent());
            assertTrue(f.descriptions().stream().anyMatch(d -> d.startsWith("Vex uses Rapier on Goblin (advantage: "
                    + "attacker invisible): 2d20(15, 2) with advantage")), String.join("; ", f.descriptions()));
            assertTrue(f.descriptions().contains("invisible ends on Vex (it attacks)"));
            assertEquals("Invisibility", wren.getConcentration().spell());
        }

        @Test
        @DisplayName("Upcast, it ends only for the target that attacks")
        void endsOnlyForTheAttacker() {
            cast(wren, "Invisibility", 3, List.of(vex, ash));

            rapierAttack(vex, 2, 2);

            assertNull(invisible(vex));
            assertNotNull(invisible(ash));
        }

        @Test
        @DisplayName("Cast on the caster, it lasts past its own casting, and ends when the caster casts another spell")
        void onTheCaster() {
            cast(wren, "Invisibility", 2, List.of(wren));
            assertNotNull(invisible(wren));

            cast(wren, "Fire Bolt", 0, List.of(goblin), 3, 3);

            assertNull(invisible(wren));
            // Fire Bolt's spell attack is an attack too
            assertTrue(f.descriptions().contains("invisible ends on Wren (it attacks)"));
        }

        @Test
        @DisplayName("Undoing the attack gives the invisibility back")
        void undo() {
            cast(wren, "Invisibility", 2, List.of(vex));
            rapierAttack(vex, 2, 2);
            assertNull(invisible(vex));

            f.inTransaction(() -> f.undoService.undo(f.encounterId(),
                    new UndoRequest(f.commands.getLast().getId(), 1), f.dmUserId));

            assertNotNull(invisible(vex));
            assertTrue(invisible(vex).endsWhenAttackingOrCasting());
        }

        @Test
        @DisplayName("Only Invisibility's data ends its condition on an attack or a spell; Greater Invisibility's doesn't")
        void data() {
            Set<String> flagged = new TreeSet<>();
            for (JsonNode node : definitions()) {
                SpellTemplate template = SpellTemplate.of(node);
                if (template.conditionsInflicted().stream()
                        .anyMatch(SpellTemplate.ConditionInflicted::endsOnAttackOrCast)) {
                    flagged.add(node.path("spellName").asString());
                }
            }
            assertEquals(Set.of("Invisibility"), flagged);
        }
    }

    @Nested
    @DisplayName("Cloak of Shadows (PHB p.63)")
    class CloakOfShadows {

        @Test
        @DisplayName("The cleric becomes visible when it attacks")
        void endsOnAttack() {
            cloakOfShadows();
            ConditionEntry cloak = invisible(tam);
            assertTrue(cloak.endsWhenAttackingOrCasting());
            assertEquals(EndRule.endOfTurn(tam.getId()), cloak.endRule());

            rapierAttack(tam, 2, 2);

            assertNull(invisible(tam));
        }

        @Test
        @DisplayName("The cleric becomes visible when it casts a spell")
        void endsOnCast() {
            cloakOfShadows();

            cast(tam, "Fire Bolt", 0, List.of(goblin), 1, 1);

            assertNull(invisible(tam));
        }

        @Test
        @DisplayName("Using another feature, which is neither an attack nor a spell, keeps it")
        void keptOtherwise() {
            cloakOfShadows();

            f.inTransaction(() -> f.standardActions.standardAction(f.encounterId(),
                    new StandardActionRequest(tam.getId(), StandardActions.Action.DODGE, null, null, null),
                    f.dmUserId));

            assertNotNull(invisible(tam));
        }
    }

    @Test
    @DisplayName("A monster's attack action ends its own such invisibility")
    void monsterAttack() {
        goblin.setActiveConditions(new ArrayList<>(List.of(new ConditionEntry(UUID.randomUUID(), "invisible", 600, 1,
                "Invisibility", wren.getId(), true, null, EndRule.CONCENTRATION, null).endingOnAttackOrCast())));
        f.turnOf(goblin);
        f.dice.script(3, 3);

        f.inTransaction(() -> f.monsterActions.monsterAction(f.encounterId(), new MonsterActionRequest(goblin.getId(),
                "Scimitar", "ACTION", List.of(vex.getId()), null, null, null, null), f.dmUserId));

        assertNull(invisible(goblin));
    }

    @Test
    @DisplayName("Invisibility that attacking doesn't end, such as one the DM adds by hand, stays")
    void otherInvisibilityStays() {
        vex.setActiveConditions(new ArrayList<>(List.of(ConditionEntry.manual("invisible", null, 1))));

        rapierAttack(vex, 2, 2);

        assertNotNull(invisible(vex));
        assertFalse(invisible(vex).endsWhenAttackingOrCasting());
    }
}
