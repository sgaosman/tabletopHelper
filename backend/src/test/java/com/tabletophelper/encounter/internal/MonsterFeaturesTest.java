package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.ParticipantType;
import com.tabletophelper.encounter.dto.DamageRequest;
import com.tabletophelper.encounter.dto.UsePoolRequest;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.SummonLink;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.combat.AttackRules;
import com.tabletophelper.rules.combat.ChannelDivinity;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.RollMode;
import com.tabletophelper.rules.combat.SummonRules;
import com.tabletophelper.shared.JacksonDefaults;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Monster features (D199): Pack Tactics for monsters and summoned creatures alike, Turn Immunity against turning, and
 * Abjure Enemy's hold on its creature's speed.
 */
class MonsterFeaturesTest {

    private static final String PACK_TACTICS = "[{\"name\": \"Pack Tactics\", \"description\": \"The wolf has "
            + "advantage on an attack roll against a creature if at least one of the wolf's allies is within 5 feet of "
            + "the creature and the ally isn't incapacitated.\"}]";
    private static final String TURN_IMMUNITY = "[{\"name\": \"Turn Immunity\", \"description\": \"The revenant is "
            + "immune to effects that turn undead.\"}]";
    private static final String PALADIN_CD = "class:paladin-channel-divinity";
    private static final String CLERIC_CD = "class:cleric-channel-divinity";

    private CombatFixture f;
    private EncounterParticipant fighter;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        fighter = f.player("Fighter", 40, 16);
    }

    private static final Effect BITE = new Effect("Bite", new Effect.Attack(4, RollMode.NORMAL, true, true),
            List.of(new Effect.DamagePart(Dice.parse("2d4"), "piercing")), null, List.of(), false, null);

    private List<String> sources(EncounterParticipant attacker, EncounterParticipant target) {
        return f.stats.target(target, BITE, attacker).modifiers().attackSources().stream()
                .map(AttackRules.Source::reason).toList();
    }

    private EncounterParticipant wolf(String name) {
        EncounterParticipant wolf = f.monster(name, 11, 13);
        wolf.getMonster().setType("beast");
        wolf.getMonster().setTraits(PACK_TACTICS);
        return wolf;
    }

    @Nested
    @DisplayName("Pack Tactics (the wolf's, MM p.341)")
    class PackTactics {

        @Test
        @DisplayName("A wolf with another able wolf in the fight has advantage; alone, or with its ally stunned, it "
                + "hasn't")
        void monsters() {
            EncounterParticipant wolf = wolf("Wolf 1");
            assertFalse(sources(wolf, fighter).contains(CreatureStats.PACK_TACTICS));
            EncounterParticipant other = wolf("Wolf 2");
            assertTrue(sources(wolf, fighter).contains(CreatureStats.PACK_TACTICS));
            other.setActiveConditions(new ArrayList<>(List.of(ConditionEntry.manual("stunned", null, 1))));
            assertFalse(sources(wolf, fighter).contains(CreatureStats.PACK_TACTICS));
        }

        @Test
        @DisplayName("The target isn't the attacker's ally, and a creature of the other side isn't either")
        void sides() {
            EncounterParticipant wolf = wolf("Wolf");
            f.player("Cleric", 30, 14);
            assertFalse(sources(wolf, fighter).contains(CreatureStats.PACK_TACTICS));
        }

        @Test
        @DisplayName("A summoned wolf is its summoner's ally, so the wizard's presence gives it the advantage (D182)")
        void summonedWolf() {
            EncounterParticipant wizard = f.player("Wizard", 30, 12);
            EncounterParticipant ogre = f.monster("Ogre", 59, 11);
            EncounterParticipant summoned = wolf("Summoned Wolf");
            summoned.setParticipantType(ParticipantType.COMPANION);
            summoned.setSummonedByParticipantId(wizard.getId());
            assertEquals("party", CreatureStats.allegiance(summoned));
            assertTrue(sources(summoned, ogre).contains(CreatureStats.PACK_TACTICS));
        }

        @Test
        @DisplayName("A Bestial Spirit's Pack Tactics comes from its own templates (TCE p.109)")
        void spirit() {
            EncounterParticipant wizard = f.player("Wizard", 30, 12);
            EncounterParticipant ogre = f.monster("Ogre", 59, 11);
            EncounterParticipant spirit = f.monster("Bestial Spirit", 30, 13);
            spirit.setParticipantType(ParticipantType.COMPANION);
            spirit.setSummonedByParticipantId(wizard.getId());
            spirit.setActionTemplates(MonsterTemplates.of(JacksonDefaults.mapper().readTree(
                    "{\"traits\": [{\"name\": \"Pack Tactics\", \"description\": \"The beast has advantage.\"}]}")));
            assertTrue(sources(spirit, ogre).contains(CreatureStats.PACK_TACTICS));
        }

        @Test
        @DisplayName("Summon Lesser Demons' demons fight on a side of their own (XGE p.167)")
        void lesserDemons() {
            EncounterParticipant wizard = f.player("Wizard", 30, 12);
            EncounterParticipant demon = f.monster("Dretch", 18, 11);
            demon.setParticipantType(ParticipantType.COMPANION);
            demon.setSummonedByParticipantId(wizard.getId());
            UUID effect = UUID.randomUUID();
            demon.setSummon(new SummonLink(effect, "Summon Lesser Demons", 3, SummonRules.Kind.LESSER_DEMONS,
                    SummonRules.InitiativeMode.GROUP, null, null, null, false, false, false, null, List.of(),
                    List.of(), null, null));
            assertEquals("demons:" + effect, CreatureStats.allegiance(demon));
        }
    }

    @Nested
    @DisplayName("Turn Immunity (the revenant's, MM p.259)")
    class TurnImmunity {

        @Test
        @DisplayName("The trait's words, and Lord Soth's Marshal Undead only while he isn't incapacitated")
        void words() {
            assertTrue(ChannelDivinity.immuneToTurning("The revenant is immune to effects that turn undead.", false));
            assertTrue(ChannelDivinity.immuneToTurning("The avatar is immune to features that turn undead.", false));
            String soth = "Unless Soth is incapacitated, he and Undead creatures of his choice within 60 feet of him "
                    + "are immune to features that turn Undead.";
            assertTrue(ChannelDivinity.immuneToTurning(soth, false));
            assertFalse(ChannelDivinity.immuneToTurning(soth, true));
            assertFalse(ChannelDivinity.immuneToTurning("The lich has advantage on saving throws against any effect "
                    + "that turns undead.", false));
        }

        @Test
        @DisplayName("Turn Undead leaves out a revenant, logging why, and turns the zombie beside it")
        void turnUndead() {
            EncounterParticipant cleric = f.player("Tam", 30, 16);
            cleric.getCharacter().setWisdom(16);
            cleric.getCharacter().setMulticlassEntries(List.of(new ClassEntry(UUID.randomUUID(), "Cleric",
                    UUID.randomUUID(), "Life Domain", 2)));
            cleric.getCharacter().setLevel(2);
            cleric.setResourcePoolsCurrent(new ArrayList<>(List.of(pool(CLERIC_CD, 1))));
            f.turnOf(cleric);
            EncounterParticipant revenant = f.monster("Revenant", 136, 13);
            revenant.getMonster().setType("undead");
            revenant.getMonster().setTraits(TURN_IMMUNITY);
            EncounterParticipant zombie = f.monster("Zombie", 22, 8);
            zombie.getMonster().setType("undead");
            // only the zombie rolls: 2 fails
            f.dice.script(2);

            f.poolUse.usePool(f.encounterId(), new UsePoolRequest(cleric.getId(), CLERIC_CD, null, "turn-undead",
                    List.of(revenant.getId(), zombie.getId())), f.dmUserId);

            assertEquals(0, f.dice.remaining());
            assertTrue(f.descriptions().contains("Revenant is immune to being turned (Turn Immunity)."));
            assertFalse(CombatFixture.conditionNames(revenant).contains(ChannelDivinity.TURNED));
            assertTrue(CombatFixture.conditionNames(zombie).contains(ChannelDivinity.TURNED));
        }
    }

    @Nested
    @DisplayName("Abjure Enemy (PHB p.88)")
    class AbjureEnemy {

        private EncounterParticipant paladin;
        private EncounterParticipant ogre;

        @BeforeEach
        void paladinsTurn() {
            paladin = f.player("Aria", 40, 18);
            paladin.getCharacter().setCharisma(16);
            paladin.getCharacter().setMulticlassEntries(List.of(new ClassEntry(UUID.randomUUID(), "Paladin",
                    UUID.randomUUID(), "Oath of Vengeance", 3)));
            paladin.getCharacter().setLevel(3);
            paladin.setResourcePoolsCurrent(new ArrayList<>(List.of(pool(PALADIN_CD, 1))));
            f.turnOf(paladin);
            ogre = f.monster("Ogre", 59, 11);
            ogre.getMonster().setSpeed("{\"walk\": 40}");
        }

        private void abjure() {
            f.poolUse.usePool(f.encounterId(), new UsePoolRequest(paladin.getId(), PALADIN_CD, null, "abjure-enemy",
                    List.of(ogre.getId())), f.dmUserId);
        }

        private int walk() {
            return TurnEconomies.speed(ogre, f.objectMapper).get("walk");
        }

        @Test
        @DisplayName("A failed save leaves it frightened with speed 0, until it takes damage")
        void failed() {
            f.dice.script(2);
            abjure();
            assertTrue(CombatFixture.conditionNames(ogre).containsAll(List.of("frightened", ChannelDivinity.ABJURED)));
            assertEquals(0, walk());

            DamageRequest damage = new DamageRequest();
            damage.setTargetId(ogre.getId());
            damage.setAmount(3);
            f.hitPoints.applyDamage(f.encounterId(), damage, null, f.dmUserId);
            assertFalse(CombatFixture.conditionNames(ogre).contains(ChannelDivinity.ABJURED));
            assertEquals(40, walk());
        }

        @Test
        @DisplayName("A successful save halves its speed for 1 minute or until it takes damage")
        void succeeded() {
            f.dice.script(19);
            abjure();
            assertFalse(CombatFixture.conditionNames(ogre).contains("frightened"));
            ConditionEntry slowed = ogre.getActiveConditions().stream()
                    .filter(c -> c.name().equals(ChannelDivinity.ABJURED_SLOWED)).findFirst().orElse(null);
            assertNotNull(slowed);
            assertEquals(ChannelDivinity.MINUTE_ROUNDS, slowed.duration());
            assertTrue(slowed.endsOnDamage());
            assertEquals(20, walk());
        }

        @Test
        @DisplayName("Without the fear, the speed 0 entry alone holds nothing")
        void needsTheFear() {
            ogre.setActiveConditions(new ArrayList<>(List.of(ConditionEntry.manual(ChannelDivinity.ABJURED, null, 1))));
            assertEquals(40, walk());
            assertNull(ogre.getActiveConditions().stream().filter(c -> c.name().equals("frightened")).findFirst()
                    .orElse(null));
        }
    }

    private static ResourcePoolEntry pool(String poolId, int max) {
        return new ResourcePoolEntry(poolId, "Channel Divinity", "CLASS", null, max, String.valueOf(max), max,
                "shortRest", null, null, "ACTION", "sun", Map.of("effect", "channel-divinity"));
    }
}
