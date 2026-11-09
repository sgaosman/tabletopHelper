package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.AttackRollRequest;
import com.tabletophelper.encounter.dto.BardicInspirationRequest;
import com.tabletophelper.encounter.dto.CastSpellRequest;
import com.tabletophelper.encounter.dto.DamageRequest;
import com.tabletophelper.encounter.dto.UsePoolRequest;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.EndRule;
import com.tabletophelper.encounter.state.HeldDie;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.combat.ChannelDivinity;
import com.tabletophelper.rules.combat.ClassFeatures;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.tabletophelper.encounter.internal.CombatFixture.weapon;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Channel Divinity, Bardic Inspiration and Disciple of Life at the table (M17 workstream C, 2014 PHB): the cleric's
 * options (p.59–63), the paladin's (p.86–88), the bard's die (p.53) and the Life Domain's healing (p.60). Tam is a
 * cleric with Wisdom 16 and +3 proficiency, so a save DC of 14, Aria a paladin with Charisma 16 and +2, a DC of 13, Kai a
 * Bard 5, and Vex a fighter carrying a rapier (+2 to hit, 1d8). The DM acts unless a test says otherwise.
 */
class ChannelDivinityEffectsTest {

    private static final String CLERIC_CD = "class:cleric-channel-divinity";
    private static final String PALADIN_CD = "class:paladin-channel-divinity";
    private static final String INSPIRATION = "class:bard-bardic-inspiration";

    private CombatFixture f;
    private EncounterParticipant tam;
    private EncounterParticipant aria;
    private EncounterParticipant kai;
    private EncounterParticipant vex;
    private EncounterParticipant goblin;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        tam = f.player("Tam", 30, 16);
        tam.getCharacter().setWisdom(16);
        tam.getCharacter().setProficiencyBonus(3);
        aria = f.player("Aria", 40, 18);
        aria.getCharacter().setCharisma(16);
        kai = f.player("Kai", 30, 14);
        classes(kai, "Bard", 5, null);
        kai.setResourcePoolsCurrent(pools(pool(INSPIRATION, "Bardic Inspiration", "BONUS_ACTION", 3,
                "bardic-inspiration")));
        vex = f.player("Vex", 40, 18);
        goblin = f.monster("Goblin", 30, 15);
        goblin.getMonster().setType("humanoid");
        f.weapons(weapon("Rapier", "Melee Weapon", "martial", "1d8", "piercing", "F"));
        CombatFixture.carries(vex, List.of("simple", "martial"), "Rapier");
        CombatFixture.carries(aria, List.of("simple", "martial"), "Rapier");
    }

    private static void classes(EncounterParticipant pc, String className, int level, String subclass) {
        pc.getCharacter().setMulticlassEntries(List.of(new ClassEntry(UUID.randomUUID(), className,
                subclass != null ? UUID.randomUUID() : null, subclass, level)));
        pc.getCharacter().setLevel(level);
    }

    private static ResourcePoolEntry pool(String poolId, String name, String cost, int max, String effect) {
        return new ResourcePoolEntry(poolId, name, "CLASS", null, max, String.valueOf(max), max, "shortRest", null,
                null, cost, "sun", Map.of("effect", effect));
    }

    private static List<ResourcePoolEntry> pools(ResourcePoolEntry... entries) {
        return new ArrayList<>(List.of(entries));
    }

    private void cleric(String domain, int level) {
        classes(tam, "Cleric", level, domain);
        tam.setResourcePoolsCurrent(pools(pool(CLERIC_CD, "Channel Divinity", "ACTION", 2, "channel-divinity")));
    }

    private void paladin(String oath) {
        classes(aria, "Paladin", 3, oath);
        aria.setResourcePoolsCurrent(pools(pool(PALADIN_CD, "Channel Divinity", "ACTION", 1, "channel-divinity")));
    }

    private EncounterParticipant undead(String name, String rating, int wisdom) {
        EncounterParticipant monster = f.monster(name, 22, 8);
        monster.getMonster().setType("undead");
        monster.getMonster().setChallengeRating(rating);
        monster.getMonster().setWisdom(wisdom);
        return monster;
    }

    private void channel(EncounterParticipant user, String poolId, String option, EncounterParticipant... targets) {
        f.poolUse.usePool(f.encounterId(), new UsePoolRequest(user.getId(), poolId, null, option,
                targets.length == 0 ? null : List.of(targets).stream().map(EncounterParticipant::getId).toList()),
                f.dmUserId);
    }

    private void preserveLife(List<EncounterParticipant> targets, List<Integer> amounts) {
        f.poolUse.usePool(f.encounterId(), new UsePoolRequest(tam.getId(), CLERIC_CD, null, "preserve-life",
                targets.stream().map(EncounterParticipant::getId).toList(), amounts), f.dmUserId);
    }

    private static int usesLeft(EncounterParticipant participant, String poolId) {
        return participant.getResourcePoolsCurrent().stream().filter(p -> p.poolId().equals(poolId)).findFirst()
                .orElseThrow().currentUses();
    }

    private List<String> descriptions(CombatActionType type) {
        return f.entries(type).stream().map(entry -> entry.getDescription()).toList();
    }

    private static ConditionEntry entry(EncounterParticipant participant, String name) {
        return participant.getActiveConditions() == null ? null : participant.getActiveConditions().stream()
                .filter(c -> c.name().equals(name)).findFirst().orElse(null);
    }

    private void damage(EncounterParticipant target, int amount) {
        DamageRequest request = new DamageRequest();
        request.setTargetId(target.getId());
        request.setAmount(amount);
        f.hitPoints.applyDamage(f.encounterId(), request, null, f.dmUserId);
    }

    private AttackRollRequest rapierAt(EncounterParticipant target) {
        AttackRollRequest request = new AttackRollRequest();
        request.setTargetId(target.getId());
        request.setWeapon("Rapier");
        return request;
    }

    private void attack(EncounterParticipant attacker, AttackRollRequest request, int... rolls) {
        f.dice.script(rolls);
        f.attacks.rollAttack(f.encounterId(), request, attacker.getId(), f.dmUserId);
    }

    @Nested
    @DisplayName("Turn Undead and Destroy Undead (PHB p.59)")
    class TurnUndead {

        @BeforeEach
        void tamsTurn() {
            cleric("Life Domain", 5);
            f.turnOf(tam);
        }

        @Test
        @DisplayName("A Cleric 5's failed saves destroy a CR 1/4 zombie and turn a CR 1 ghoul until it takes damage")
        void turnsAndDestroys() {
            EncounterParticipant zombie = undead("Zombie", "1/4", 6);
            EncounterParticipant ghoul = undead("Ghoul", "1", 10);
            f.dice.script(5, 3);

            channel(tam, CLERIC_CD, "turn-undead", zombie, ghoul);

            assertEquals(LifeState.DEAD, zombie.getLifeState());
            assertNull(entry(zombie, ChannelDivinity.TURNED));
            assertTrue(descriptions(CombatActionType.KILL).contains("Zombie is destroyed (Destroy Undead)"));
            ConditionEntry turned = entry(ghoul, ChannelDivinity.TURNED);
            assertNotNull(turned);
            assertEquals(EndRule.Type.ON_DAMAGE, turned.endRule().type());
            assertEquals(ChannelDivinity.MINUTE_ROUNDS, turned.duration());
            assertEquals(tam.getId(), turned.sourceParticipantId());
            assertEquals(1, usesLeft(tam, CLERIC_CD));
            assertTrue(tam.getTurnEconomy().actionsUsed() > 0);
            assertTrue(descriptions(CombatActionType.POOL_USE).getFirst().startsWith("Tam uses Channel Divinity for "
                    + "Turn Undead (WIS save DC 14): each undead that fails a Wisdom save is turned"));

            damage(ghoul, 3);

            assertNull(entry(ghoul, ChannelDivinity.TURNED));
            assertTrue(descriptions(CombatActionType.CONDITION_REMOVE).contains("turned ends on Ghoul (it takes damage)"));
        }

        @Test
        @DisplayName("A Cleric 2 turns but doesn't destroy, and a save of 14 succeeds")
        void lowLevelAndSuccess() {
            cleric("Life Domain", 2);
            EncounterParticipant zombie = undead("Zombie", "1/4", 6);
            EncounterParticipant ghoul = undead("Ghoul", "1", 10);
            f.dice.script(5, 14);

            channel(tam, CLERIC_CD, "turn-undead", zombie, ghoul);

            assertEquals(LifeState.ALIVE, zombie.getLifeState());
            assertNotNull(entry(zombie, ChannelDivinity.TURNED));
            assertNull(entry(ghoul, ChannelDivinity.TURNED));
        }

        @Test
        @DisplayName("Turn Resistance gives advantage on the save")
        void turnResistance() {
            EncounterParticipant spawn = undead("Vampire Spawn", "5", 10);
            spawn.getMonster().setTraits("[{\"name\": \"Turn Resistance\"}]");
            f.dice.script(4, 18);

            channel(tam, CLERIC_CD, "turn-undead", spawn);

            assertNull(entry(spawn, ChannelDivinity.TURNED));
            assertTrue(descriptions(CombatActionType.POOL_USE).stream()
                    .anyMatch(d -> d.contains("2d20(4, 18) with advantage → 18")));
        }

        @Test
        @DisplayName("A creature that isn't undead, or no creature at all, is a 400 that spends nothing")
        void refusals() {
            ValidationException humanoid = assertThrows(ValidationException.class,
                    () -> channel(tam, CLERIC_CD, "turn-undead", goblin));
            assertEquals("Turn Undead affects only undead creatures, and Goblin is humanoid", humanoid.getMessage());
            assertThrows(ValidationException.class, () -> channel(tam, CLERIC_CD, "turn-undead"));
            assertThrows(ValidationException.class, () -> channel(tam, CLERIC_CD, "sacred-weapon"));
            assertEquals(2, usesLeft(tam, CLERIC_CD));
        }

        @Test
        @DisplayName("Without an option Channel Divinity only spends, for options the app doesn't model")
        void plainSpend() {
            channel(tam, CLERIC_CD, null);

            assertEquals(1, usesLeft(tam, CLERIC_CD));
            assertEquals(List.of("Tam uses Channel Divinity; 1/2 left"), descriptions(CombatActionType.POOL_USE));
        }
    }

    @Nested
    @DisplayName("The cleric's domains (PHB p.59–63)")
    class Domains {

        @BeforeEach
        void tamsTurn() {
            f.turnOf(tam);
        }

        @Test
        @DisplayName("Preserve Life splits 5 × level, never past half a creature's maximum, and refuses undead")
        void preserveLifeLimits() {
            cleric("Life Domain", 2);
            vex.setHpCurrent(10);
            aria.setHpCurrent(18);

            ConflictException pastHalf = assertThrows(ConflictException.class,
                    () -> preserveLife(List.of(vex, aria), List.of(6, 4)));
            assertEquals("Preserve Life can't restore Aria above half its hit point maximum (20 HP): it has 18, so at "
                    + "most 2", pastHalf.getMessage());
            vex.setHpCurrent(2);
            ConflictException tooMuch = assertThrows(ConflictException.class,
                    () -> preserveLife(List.of(vex, aria), List.of(9, 2)));
            assertEquals("Preserve Life restores 10 hit points at most (5 × cleric level 2), not 11",
                    tooMuch.getMessage());
            EncounterParticipant zombie = undead("Zombie", "1/4", 6);
            zombie.setHpCurrent(1);
            assertThrows(ConflictException.class, () -> preserveLife(List.of(zombie), List.of(1)));
            assertThrows(ValidationException.class, () -> preserveLife(List.of(vex, aria), List.of(5)));
            assertEquals(2, usesLeft(tam, CLERIC_CD));

            preserveLife(List.of(vex, aria), List.of(8, 2));

            assertEquals(10, vex.getHpCurrent());
            assertEquals(20, aria.getHpCurrent());
            assertEquals(List.of("Tam heals Vex for 8 HP (Preserve Life)", "Tam heals Aria for 2 HP (Preserve Life)"),
                    descriptions(CombatActionType.HEAL));
        }

        @Test
        @DisplayName("A domain's option needs that domain: a War cleric can't Preserve Life")
        void subclassGated() {
            cleric("War Domain", 6);
            ConflictException refused = assertThrows(ConflictException.class,
                    () -> preserveLife(List.of(vex), List.of(1)));
            assertEquals("Preserve Life needs Cleric level 2 and the Life Domain, and Tam doesn't have it",
                    refused.getMessage());
        }

        @Test
        @DisplayName("Radiance of the Dawn: 2d10 + level radiant, half on a successful CON save")
        void radianceOfTheDawn() {
            cleric("Light Domain", 5);
            EncounterParticipant orc = f.monster("Orc", 30, 13);
            f.dice.script(4, 6, 10, 18);

            channel(tam, CLERIC_CD, "radiance-of-the-dawn", goblin, orc);

            assertEquals(15, goblin.getHpCurrent());
            assertEquals(23, orc.getHpCurrent());
        }

        @Test
        @DisplayName("Charm Animals and Plants charms a beast on a failed save, until it takes damage")
        void charmAnimals() {
            cleric("Nature Domain", 2);
            EncounterParticipant wolf = f.monster("Wolf", 11, 13);
            wolf.getMonster().setType("beast");
            f.dice.script(2);

            channel(tam, CLERIC_CD, "charm-animals-and-plants", wolf);

            assertEquals(EndRule.Type.ON_DAMAGE, entry(wolf, "charmed").endRule().type());
            assertThrows(ValidationException.class,
                    () -> channel(tam, CLERIC_CD, "charm-animals-and-plants", goblin));
        }

        @Test
        @DisplayName("Cloak of Shadows makes the cleric invisible until the end of its next turn")
        void cloakOfShadows() {
            cleric("Trickery Domain", 6);

            channel(tam, CLERIC_CD, "cloak-of-shadows");

            ConditionEntry invisible = entry(tam, "invisible");
            assertEquals(EndRule.endOfTurn(tam.getId()), invisible.endRule());
            assertEquals(1, invisible.duration());
        }

        @Test
        @DisplayName("Log-only options spend the use and say what they do")
        void logged() {
            cleric("Tempest Domain", 2);

            channel(tam, CLERIC_CD, "destructive-wrath");

            assertEquals(List.of("Tam uses Channel Divinity for Destructive Wrath: deals maximum damage with its "
                    + "lightning or thunder damage roll instead of rolling; the DM sets the damage to the dice's "
                    + "maximum; 1/2 left"), descriptions(CombatActionType.POOL_USE));
            assertFalse(tam.getTurnEconomy().actionsUsed() > 0);
        }
    }

    @Nested
    @DisplayName("War Domain: Guided Strike and War God's Blessing (PHB p.63)")
    class War {

        @BeforeEach
        void warCleric() {
            cleric("War Domain", 6);
            CombatFixture.carries(tam, List.of("simple", "martial"), "Rapier");
            f.turnOf(tam);
        }

        @Test
        @DisplayName("Guided Strike adds +10 and spends the use only when that turns a miss into a hit")
        void guidedStrike() {
            AttackRollRequest request = rapierAt(goblin);
            request.setGuidedStrike(true);

            // 14 + 3 = 17 hits without it
            attack(tam, request, 14, 4);
            assertEquals(2, usesLeft(tam, CLERIC_CD));
            // 1 + 3 + 10 = 14 would still miss AC 15
            CombatFixture.newTurn(tam);
            attack(tam, request, 1);
            assertEquals(2, usesLeft(tam, CLERIC_CD));
            // 5 + 3 = 8 misses, + 10 = 18 hits
            CombatFixture.newTurn(tam);
            attack(tam, request, 5, 4);

            assertEquals(1, usesLeft(tam, CLERIC_CD));
            assertTrue(descriptions(CombatActionType.ATTACK).getLast().contains("d20(5) + 3 + Guided Strike 10 = 18"));
            assertTrue(descriptions(CombatActionType.POOL_USE).getLast()
                    .startsWith("Tam uses Channel Divinity for Guided Strike"));
        }

        @Test
        @DisplayName("Guided Strike needs the War Domain")
        void guidedStrikeGated() {
            cleric("Life Domain", 6);
            AttackRollRequest request = rapierAt(goblin);
            request.setGuidedStrike(true);
            assertThrows(ValidationException.class, () -> attack(tam, request));
        }

        @Test
        @DisplayName("War God's Blessing is a reaction giving another creature +10 on its next attack roll")
        void warGodsBlessing() {
            channel(tam, CLERIC_CD, "war-gods-blessing", vex);

            assertTrue(tam.getTurnEconomy().reactionUsed());
            assertNotNull(entry(vex, ChannelDivinity.WAR_GODS_BLESSING));

            f.turnOf(vex);
            attack(vex, rapierAt(goblin), 3, 4);

            assertTrue(descriptions(CombatActionType.ATTACK).getLast().contains("d20(3) + 12 = 15"));
            assertNull(entry(vex, ChannelDivinity.WAR_GODS_BLESSING));
        }
    }

    @Nested
    @DisplayName("Sacred Oaths (PHB p.86–88)")
    class Oaths {

        @BeforeEach
        void ariasTurn() {
            f.turnOf(aria);
        }

        @Test
        @DisplayName("Sacred Weapon adds the Charisma modifier to weapon attack rolls for 1 minute")
        void sacredWeapon() {
            paladin("Oath of Devotion");

            channel(aria, PALADIN_CD, "sacred-weapon");

            ConditionEntry marker = entry(aria, ChannelDivinity.SACRED_WEAPON);
            assertEquals(ChannelDivinity.MINUTE_ROUNDS, marker.duration());
            attack(aria, rapierAt(goblin), 10, 4);
            String attackEntry = descriptions(CombatActionType.ATTACK).getLast();
            assertTrue(attackEntry.contains("Sacred Weapon +3"));
            assertTrue(attackEntry.contains("d20(10) + 5 = 15"));
        }

        @Test
        @DisplayName("Vow of Enmity gives the paladin advantage on attacks against the creature vowed against")
        void vowOfEnmity() {
            paladin("Oath of Vengeance");

            channel(aria, PALADIN_CD, "vow-of-enmity", goblin);

            assertTrue(aria.getTurnEconomy().bonusActionUsed());
            assertEquals(aria.getId(), entry(goblin, ChannelDivinity.VOW_OF_ENMITY).sourceParticipantId());
            attack(aria, rapierAt(goblin), 3, 15, 4);
            assertTrue(descriptions(CombatActionType.ATTACK).getLast().contains("2d20(3, 15) with advantage → 15"));
        }

        @Test
        @DisplayName("Turn the Unholy turns fiends and undead, not humanoids")
        void turnTheUnholy() {
            paladin("Oath of Devotion");
            EncounterParticipant imp = f.monster("Imp", 10, 13);
            imp.getMonster().setType("fiend (devil, shapechanger)");
            f.dice.script(4);

            channel(aria, PALADIN_CD, "turn-the-unholy", imp);

            assertNotNull(entry(imp, ChannelDivinity.TURNED));
        }

        @Test
        @DisplayName("Nature's Wrath: the better of STR and DEX, restrained with a repeat save on a failure")
        void naturesWrath() {
            paladin("Oath of the Ancients");
            goblin.getMonster().setDexterity(14);
            f.dice.script(5);

            channel(aria, PALADIN_CD, "natures-wrath", goblin);

            ConditionEntry restrained = entry(goblin, "restrained");
            assertEquals(Ability.DEX, restrained.repeatSave().ability());
            assertEquals(13, restrained.repeatSave().dc());
        }

        @Test
        @DisplayName("Abjure Enemy: an undead saves with disadvantage, and is frightened until it takes damage")
        void abjureEnemy() {
            paladin("Oath of Vengeance");
            EncounterParticipant ghoul = undead("Ghoul", "1", 10);
            f.dice.script(15, 4);

            channel(aria, PALADIN_CD, "abjure-enemy", ghoul);

            assertEquals(EndRule.Type.ON_DAMAGE, entry(ghoul, "frightened").endRule().type());
            assertTrue(descriptions(CombatActionType.POOL_USE).stream()
                    .anyMatch(d -> d.contains("2d20(15, 4) with disadvantage → 4")));
        }
    }

    @Nested
    @DisplayName("Bardic Inspiration (PHB p.53–54)")
    class BardicInspiration {

        @BeforeEach
        void kaisTurn() {
            f.turnOf(kai);
        }

        private void inspire(EncounterParticipant target) {
            f.poolUse.usePool(f.encounterId(), new UsePoolRequest(kai.getId(), INSPIRATION, null, null,
                    List.of(target.getId())), f.dmUserId);
        }

        private void holderUses(EncounterParticipant holder, BardicInspirationRequest.Use use) {
            f.bardicInspiration.use(f.encounterId(), new BardicInspirationRequest(holder.getId(), use), f.dmUserId);
        }

        @Test
        @DisplayName("A bonus action gives another creature a d8 at Bard 5, one at a time, never the bard itself")
        void gives() {
            inspire(vex);

            ConditionEntry held = entry(vex, ClassFeatures.INSPIRED);
            assertEquals(new HeldDie(8, false), held.heldDie());
            assertEquals(ClassFeatures.INSPIRATION_ROUNDS, held.duration());
            assertTrue(kai.getTurnEconomy().bonusActionUsed());
            assertEquals(2, usesLeft(kai, INSPIRATION));
            CombatFixture.newTurn(kai);
            assertThrows(ConflictException.class, () -> inspire(vex));
            assertThrows(ValidationException.class, () -> inspire(kai));
            assertEquals(2, usesLeft(kai, INSPIRATION));
        }

        @Test
        @DisplayName("An attack asking for it rolls the die only when it would miss; a hit keeps it")
        void onAttack() {
            inspire(vex);
            f.turnOf(vex);
            AttackRollRequest request = rapierAt(goblin);
            request.setUseInspiration(true);

            attack(vex, request, 14, 4);
            assertNotNull(entry(vex, ClassFeatures.INSPIRED));

            CombatFixture.newTurn(vex);
            attack(vex, request, 9, 6, 4);
            assertNull(entry(vex, ClassFeatures.INSPIRED));
            assertTrue(descriptions(CombatActionType.ATTACK).getLast()
                    .contains("d20(9) + 2 + Bardic Inspiration d8(6) = 17 vs AC 15 — hit"));
            assertThrows(ConflictException.class, () -> attack(vex, request));
        }

        @Test
        @DisplayName("Kept for a failed save, the die turns a failure into a success and is spent")
        void onFailedSave() {
            inspire(vex);
            holderUses(vex, BardicInspirationRequest.Use.ON_FAILED_SAVE);
            assertTrue(entry(vex, ClassFeatures.INSPIRED).heldDie().onFailedSave());
            paladin("Oath of Vengeance");
            f.turnOf(aria);
            f.dice.script(10, 5);

            channel(aria, PALADIN_CD, "abjure-enemy", vex);

            assertNull(entry(vex, "frightened"));
            assertNull(entry(vex, ClassFeatures.INSPIRED));
            assertTrue(descriptions(CombatActionType.POOL_USE).stream()
                    .anyMatch(d -> d.contains("d20(10) + 0 + Bardic Inspiration d8(5) = 15 vs DC 13 — succeeds")));
        }

        @Test
        @DisplayName("Rolled by hand for a check, the die is logged and spent; kept, it stays")
        void rolledByHand() {
            inspire(vex);
            holderUses(vex, BardicInspirationRequest.Use.KEEP);
            assertFalse(entry(vex, ClassFeatures.INSPIRED).heldDie().onFailedSave());
            f.dice.script(7);

            holderUses(vex, BardicInspirationRequest.Use.ROLL);

            assertNull(entry(vex, ClassFeatures.INSPIRED));
            assertTrue(descriptions(CombatActionType.POOL_USE).getLast()
                    .startsWith("Vex rolls their Bardic Inspiration die: d8(7)"));
            assertThrows(ConflictException.class, () -> holderUses(vex, BardicInspirationRequest.Use.ROLL));
        }
    }

    @Test
    @DisplayName("Disciple of Life adds 2 + the slot's level to a Life cleric's healing spell (PHB p.60)")
    void discipleOfLife() {
        classes(tam, "Cleric", 1, "Life Domain");
        tam.getCharacter().setFeatures(List.of(new Feature(ClassFeatures.DISCIPLE_OF_LIFE, null, "Life Domain")));
        tam.setSpellSlotsCurrent(new HashMap<>(Map.of("1", new SpellSlot(2, 0), "2", new SpellSlot(1, 0))));
        f.spell("Cure Wounds", 1, """
                {"spellName": "Cure Wounds", "spellLevel": 1, "deliveryMethod": "AUTO_HIT", "effects": [],
                 "healing": {"healingDice": "1d8", "upcastScaling": {"additionalDicePerLevel": "1d8"}}}""");
        f.knows(tam, new KnownSpell(null, "Cure Wounds", 1, "class:Cleric", true, null, null, null, null));
        vex.setHpCurrent(5);
        f.turnOf(tam);
        CastSpellRequest request = new CastSpellRequest();
        request.setSpellName("Cure Wounds");
        request.setSlotLevel(2);
        request.setTargetIds(List.of(vex.getId()));
        f.dice.script(3, 4);

        f.spellcasting.castSpell(f.encounterId(), request, tam.getId(), f.dmUserId);

        // 2d8 (3 + 4) + 2 + 2
        assertEquals(16, vex.getHpCurrent());
        assertTrue(descriptions(CombatActionType.SPELL_CAST).getFirst().contains("(Disciple of Life +4)"));
    }
}
