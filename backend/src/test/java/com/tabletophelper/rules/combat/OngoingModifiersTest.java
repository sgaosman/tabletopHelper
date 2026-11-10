package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.Exhaustion;
import com.tabletophelper.rules.Rng;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ongoing Modifiers (D184): what spells such as Bless, Haste and Aid do to a creature's numbers and rolls, combined as
 * the 2014 rules have it, and the dice the Resolver adds for them.
 */
class OngoingModifiersTest {

    private static final UUID GOBLIN = UUID.randomUUID();

    private static OngoingModifier of(OngoingModifier.Kind kind, String dice, int flat) {
        return new OngoingModifier(UUID.randomUUID(), kind, null, dice, flat, null, null, null, false, false, false,
                null, false, null, null, null, null);
    }

    private static OngoingModifier mode(OngoingModifier.Kind kind, RollMode mode, String ability) {
        return new OngoingModifier(UUID.randomUUID(), kind, null, null, 0, mode, ability, null, false, false, false,
                null, false, null, null, null, null);
    }

    private static OngoingModifiers.Labelled label(String spell, OngoingModifier modifier) {
        return new OngoingModifiers.Labelled(spell, modifier);
    }

    @Nested
    @DisplayName("Armour Class")
    class ArmourClass {

        @Test
        @DisplayName("Shield of Faith's +2 and Haste's +2 add; Slow's -2 takes away (PHB p.275, p.250, p.277)")
        void bonuses() {
            assertEquals(19, OngoingModifiers.armourClass(15, List.of(of(OngoingModifier.Kind.AC, null, 2),
                    of(OngoingModifier.Kind.AC, null, 2))));
            assertEquals(13, OngoingModifiers.armourClass(15, List.of(of(OngoingModifier.Kind.AC, null, -2))));
        }

        @Test
        @DisplayName("Mage Armor's 13 + DEX replaces a lower AC, never a higher one, and Shield adds to it (PHB p.256, p.275)")
        void mageArmor() {
            OngoingModifier mageArmor = of(OngoingModifier.Kind.AC_BASE, null, 16);
            assertEquals(16, OngoingModifiers.armourClass(13, List.of(mageArmor)));
            assertEquals(18, OngoingModifiers.armourClass(18, List.of(mageArmor)));
            assertEquals(21, OngoingModifiers.armourClass(13, List.of(mageArmor, of(OngoingModifier.Kind.AC, null, 5))));
        }

        @Test
        @DisplayName("Barkskin makes 16 the least the total can be, whatever else applies (PHB p.217)")
        void barkskin() {
            OngoingModifier barkskin = of(OngoingModifier.Kind.AC_FLOOR, null, 16);
            assertEquals(16, OngoingModifiers.armourClass(12, List.of(barkskin)));
            assertEquals(16, OngoingModifiers.armourClass(12, List.of(barkskin, of(OngoingModifier.Kind.AC, null, 2))));
            assertEquals(19, OngoingModifiers.armourClass(17, List.of(barkskin, of(OngoingModifier.Kind.AC, null, 2))));
        }
    }

    @Nested
    @DisplayName("Speed")
    class Speed {

        private Map<String, Integer> walk(int feet) {
            Map<String, Integer> speeds = new LinkedHashMap<>();
            speeds.put("walk", feet);
            speeds.put("fly", 60);
            return speeds;
        }

        @Test
        @DisplayName("Longstrider adds 10 feet, then Haste doubles every speed (PHB p.256, p.250)")
        void addedThenDoubled() {
            Map<String, Integer> speeds = OngoingModifiers.speeds(walk(30), List.of(
                    of(OngoingModifier.Kind.SPEED, null, 10), of(OngoingModifier.Kind.SPEED_DOUBLED, null, 0)));
            assertEquals(80, speeds.get("walk"));
            assertEquals(140, speeds.get("fly"));
        }

        @Test
        @DisplayName("Haste and Slow together leave a speed whole; Ray of Frost's -10 never goes below 0")
        void cancelAndFloor() {
            assertEquals(35, OngoingModifiers.speeds(walk(35), List.of(of(OngoingModifier.Kind.SPEED_DOUBLED, null, 0),
                    of(OngoingModifier.Kind.SPEED_HALVED, null, 0))).get("walk"));
            assertEquals(15, OngoingModifiers.speeds(walk(30), List.of(of(OngoingModifier.Kind.SPEED_HALVED, null, 0)))
                    .get("walk"));
            assertEquals(0, OngoingModifiers.speeds(walk(5), List.of(of(OngoingModifier.Kind.SPEED, null, -10)))
                    .get("walk"));
        }

        @Test
        @DisplayName("Exhaustion comes after the modifiers: hasted at level 2 is 30, at level 5 still 0 (PHB p.291, D183)")
        void withExhaustion() {
            int hasted = OngoingModifiers.speeds(walk(30), List.of(of(OngoingModifier.Kind.SPEED_DOUBLED, null, 0)))
                    .get("walk");
            assertEquals(30, Exhaustion.speed(hasted, 2));
            assertEquals(0, Exhaustion.speed(hasted, 5));
        }
    }

    @Nested
    @DisplayName("Hit points, defences and immunities")
    class Numbers {

        @Test
        @DisplayName("Aid raises the maximum, and exhaustion 4 halves the raised maximum (PHB p.211, p.291)")
        void aidThenExhaustion() {
            int raised = OngoingModifiers.hitPointMaximum(20, List.of(of(OngoingModifier.Kind.HP_MAX, null, 10)));
            assertEquals(30, raised);
            assertEquals(15, Exhaustion.hitPointMaximum(raised, 4));
        }

        @Test
        @DisplayName("Warding Bond resists every type, Blade Ward only bludgeoning, piercing and slashing")
        void resistances() {
            OngoingModifier all = new OngoingModifier(UUID.randomUUID(), OngoingModifier.Kind.RESISTANCE, null, null,
                    0, null, null, List.of("all"), false, false, false, null, false, null, null, null, null);
            OngoingModifier weapons = new OngoingModifier(UUID.randomUUID(), OngoingModifier.Kind.RESISTANCE, null,
                    null, 0, null, null, List.of("bludgeoning", "piercing", "slashing"), false, false, false, null,
                    false, null, null, null, null);
            assertTrue(OngoingModifiers.resists("fire", List.of(all)));
            assertTrue(OngoingModifiers.resists("Slashing", List.of(weapons)));
            assertFalse(OngoingModifiers.resists("fire", List.of(weapons)));
            assertFalse(OngoingModifiers.resists(null, List.of(all)));
        }

        @Test
        @DisplayName("Heroism's immunity to frightened, and Chill Touch's no healing")
        void immunityAndHealing() {
            OngoingModifier heroism = new OngoingModifier(UUID.randomUUID(), OngoingModifier.Kind.CONDITION_IMMUNITY,
                    null, null, 0, null, null, null, false, false, false, null, false, null, null, "frightened", null);
            assertTrue(OngoingModifiers.immuneTo("Frightened", List.of(heroism)));
            assertFalse(OngoingModifiers.immuneTo("charmed", List.of(heroism)));
            assertTrue(OngoingModifiers.noHealing(List.of(of(OngoingModifier.Kind.NO_HEALING, null, 0))));
        }
    }

    @Nested
    @DisplayName("Saving throws and checks")
    class Saves {

        @Test
        @DisplayName("Bless's d4 covers every save, death saves included; Slow's -2 only Dexterity saves")
        void bonuses() {
            var bless = label("Bless", of(OngoingModifier.Kind.SAVE, "1d4", 0));
            var slow = label("Slow", new OngoingModifier(UUID.randomUUID(), OngoingModifier.Kind.SAVE, null, null, -2,
                    null, "DEX", null, false, false, false, null, false, null, null, null, null));
            assertEquals(2, OngoingModifiers.saveBonuses("DEX", List.of(bless, slow)).size());
            assertEquals(1, OngoingModifiers.saveBonuses("WIS", List.of(bless, slow)).size());
            assertEquals(1, OngoingModifiers.saveBonuses(null, List.of(bless, slow)).size());
            RollModifiers.Bonus minusTwo = OngoingModifiers.saveBonuses("DEX", List.of(slow)).getFirst();
            assertEquals(-1, minusTwo.sign());
            assertEquals(new Dice(0, 0, 2), minusTwo.dice());
        }

        @Test
        @DisplayName("Haste's advantage on Dexterity saves; Beacon of Hope's on Wisdom and death saves only")
        void modes() {
            var haste = mode(OngoingModifier.Kind.SAVE_MODE, RollMode.ADVANTAGE, "DEX");
            var beaconDeath = mode(OngoingModifier.Kind.SAVE_MODE, RollMode.ADVANTAGE, OngoingModifier.DEATH);
            assertEquals(List.of(RollMode.ADVANTAGE), OngoingModifiers.saveModes("DEX", List.of(haste, beaconDeath)));
            assertEquals(List.of(RollMode.ADVANTAGE), OngoingModifiers.saveModes(null, List.of(haste, beaconDeath)));
            assertEquals(List.of(), OngoingModifiers.saveModes("WIS", List.of(haste, beaconDeath)));
        }

        @Test
        @DisplayName("Guidance's d4 counts for Hide and initiative, Pass without Trace's +10 only for Stealth, Gift of Alacrity's d8 only for initiative")
        void checks() {
            var guidance = label("Guidance", of(OngoingModifier.Kind.CHECK, "1d4", 0));
            var pass = label("Pass without Trace", new OngoingModifier(UUID.randomUUID(), OngoingModifier.Kind.CHECK,
                    null, null, 10, null, null, null, false, false, false, null, false, null, null, null, "Stealth"));
            var alacrity = label("Gift of Alacrity", of(OngoingModifier.Kind.INITIATIVE, "1d8", 0));
            assertEquals(2, OngoingModifiers.checkBonuses("Stealth", List.of(guidance, pass, alacrity)).size());
            assertEquals(2, OngoingModifiers.checkBonuses(null, List.of(guidance, pass, alacrity)).size());
        }
    }

    @Nested
    @DisplayName("Attacks")
    class Attacks {

        @Test
        @DisplayName("Magic Weapon's +1 counts for weapon attacks only; Bless's d4 for every attack")
        void bonuses() {
            var bless = label("Bless", of(OngoingModifier.Kind.ATTACK_ROLL, "1d4", 0));
            var magicWeapon = label("Magic Weapon", new OngoingModifier(UUID.randomUUID(),
                    OngoingModifier.Kind.ATTACK_ROLL, null, null, 1, null, null, null, true, false, false, null, false,
                    null, null, null, null));
            assertEquals(2, OngoingModifiers.attackBonuses(true, List.of(bless, magicWeapon)).size());
            assertEquals(1, OngoingModifiers.attackBonuses(false, List.of(bless, magicWeapon)).size());
        }

        @Test
        @DisplayName("Elemental Weapon at 5th level adds 2d4 of its type and a flat +2 never; Absorb Elements needs a melee hit")
        void hitDamage() {
            var elemental = label("Elemental Weapon", new OngoingModifier(UUID.randomUUID(),
                    OngoingModifier.Kind.HIT_DAMAGE, null, "2d4", 0, null, null, List.of("fire"), true, false, false,
                    null, false, null, null, null, null));
            var absorb = label("Absorb Elements", new OngoingModifier(UUID.randomUUID(),
                    OngoingModifier.Kind.HIT_DAMAGE, null, "1d6", 0, null, null, List.of("cold"), false, true, true,
                    null, false, null, null, null, null));
            List<RollModifiers.HitDamage> melee = OngoingModifiers.hitDamage(true, true, List.of(elemental, absorb));
            assertEquals(2, melee.size());
            assertEquals("fire", melee.getFirst().type());
            assertEquals(new Dice(2, 4, 0), melee.getFirst().dice());
            assertEquals(1, OngoingModifiers.hitDamage(true, false, List.of(elemental, absorb)).size());
            assertEquals(0, OngoingModifiers.hitDamage(false, false, List.of(elemental, absorb)).size());
        }

        @Test
        @DisplayName("Faerie Fire's advantage needs an attacker that can see; Blur's disadvantage fails against blindsight; Protection from Evil and Good only against the types it names")
        void targetSources() {
            var faerieFire = label("Faerie Fire", new OngoingModifier(UUID.randomUUID(),
                    OngoingModifier.Kind.ATTACKED_MODE, null, null, 0, RollMode.ADVANTAGE, null, null, false, false,
                    false, null, false, "SEEN", null, null, null));
            var blur = label("Blur", new OngoingModifier(UUID.randomUUID(), OngoingModifier.Kind.ATTACKED_MODE, null,
                    null, 0, RollMode.DISADVANTAGE, null, null, false, false, false, null, false, "SIGHTED", null, null,
                    null));
            var protection = label("Protection from Evil and Good", new OngoingModifier(UUID.randomUUID(),
                    OngoingModifier.Kind.ATTACKED_MODE, null, null, 0, RollMode.DISADVANTAGE, null, null, false, false,
                    false, null, false, null, List.of("undead", "fiend"), null, null));
            AttackRules.Side seeing = AttackRules.Side.NONE;
            AttackRules.Side blind = AttackRules.Side.of(List.of("blinded"));
            AttackRules.Side bat = new AttackRules.Side(List.of(), new AttackRules.Senses(true, false));

            assertEquals(List.of(new AttackRules.Source(RollMode.ADVANTAGE, "Faerie Fire")),
                    OngoingModifiers.targetSources(List.of(faerieFire), seeing, AttackRules.Side.NONE, null));
            assertEquals(List.of(), OngoingModifiers.targetSources(List.of(faerieFire), blind, AttackRules.Side.NONE,
                    null));
            assertEquals(1, OngoingModifiers.targetSources(List.of(blur), seeing, AttackRules.Side.NONE, null).size());
            assertEquals(0, OngoingModifiers.targetSources(List.of(blur), bat, AttackRules.Side.NONE, null).size());
            assertEquals(1, OngoingModifiers.targetSources(List.of(protection), seeing, AttackRules.Side.NONE,
                    "Undead").size());
            assertEquals(0, OngoingModifiers.targetSources(List.of(protection), seeing, AttackRules.Side.NONE,
                    "humanoid").size());
            assertEquals(0, OngoingModifiers.targetSources(List.of(protection), seeing, AttackRules.Side.NONE,
                    null).size());
        }
    }

    @Nested
    @DisplayName("Rolled by the Resolver")
    class Resolved {

        private Effect longsword(boolean weapon) {
            return new Effect("Longsword", new Effect.Attack(5, RollMode.NORMAL, true, false).withWeapon(weapon),
                    List.of(new Effect.DamagePart(Dice.parse("1d8"), "slashing")), null, List.of(), false, null);
        }

        @Test
        @DisplayName("Bless's d4 turns a miss into a hit and is named with its roll")
        void blessHits() {
            RollModifiers bless = new RollModifiers(List.of(), List.of(new RollModifiers.Bonus("Bless",
                    Dice.parse("1d4"), 1, null, false)), List.of(), List.of(), Set.of(), false);
            var dice = Rng.scripted(9, 3, 5);
            var outcome = new Resolver(dice).resolve(longsword(true),
                    List.of(new Resolver.Target(GOBLIN, 17, 0, List.of()).withModifiers(bless))).getFirst();
            assertEquals(Outcome.HIT, outcome.outcome());
            assertEquals(17, outcome.total());
            assertEquals("+1d4 (3) Bless", outcome.bonuses().getFirst().describe());
            assertEquals(0, dice.remaining());
        }

        @Test
        @DisplayName("Hunter's Mark's d6 takes the weapon's type and doubles on a critical hit; a one-shot source is spent")
        void huntersMarkCritical() {
            UUID mark = UUID.randomUUID();
            UUID mockery = UUID.randomUUID();
            RollModifiers modifiers = new RollModifiers(List.of(), List.of(), List.of(new RollModifiers.HitDamage(
                    "Hunter's Mark", Dice.parse("1d6"), 1, null, mark, false)), List.of(), Set.of(mockery), false);
            var dice = Rng.scripted(20, 4, 4, 6, 6);
            var outcome = new Resolver(dice).resolve(longsword(true),
                    List.of(new Resolver.Target(GOBLIN, 15, 0, List.of()).withModifiers(modifiers))).getFirst();
            assertEquals(Outcome.CRITICAL, outcome.outcome());
            assertEquals(List.of(new Resolver.Damage("slashing", 20)), outcome.damage());
            assertEquals("Hunter's Mark 2d6 (12)", outcome.riders().getFirst().describe());
            assertEquals(Set.of(mockery), outcome.spentOneShots());
        }

        @Test
        @DisplayName("Reduce's -1d4 never takes a hit's damage below 1 (PHB p.237)")
        void reduce() {
            RollModifiers reduce = new RollModifiers(List.of(), List.of(), List.of(new RollModifiers.HitDamage(
                    "Reduce", Dice.parse("1d4"), -1, null, null, false)), List.of(), Set.of(), false);
            var outcome = new Resolver(Rng.scripted(15, 2, 4)).resolve(longsword(true),
                    List.of(new Resolver.Target(GOBLIN, 15, 0, List.of()).withModifiers(reduce))).getFirst();
            assertEquals(List.of(new Resolver.Damage("slashing", 1)), outcome.damage());
        }

        @Test
        @DisplayName("Bane's d4 on a save is taken away, and Resistance's one-shot d4 is spent once rolled")
        void saves() {
            UUID resistance = UUID.randomUUID();
            RollModifiers modifiers = RollModifiers.saves(List.of(
                    new RollModifiers.Bonus("Bane", Dice.parse("1d4"), -1, null, false),
                    new RollModifiers.Bonus("Resistance", Dice.parse("1d4"), 1, resistance, true)));
            Effect fireball = new Effect("Fireball", new Effect.Save(Ability.DEX, 13, true),
                    List.of(new Effect.DamagePart(Dice.parse("1d6"), "fire")), null, List.of(), false, null);
            var outcome = new Resolver(Rng.scripted(6, 12, 3, 2)).resolve(fireball,
                    List.of(new Resolver.Target(GOBLIN, 15, 2, List.of()).withModifiers(modifiers))).getFirst();
            assertEquals(13, outcome.total());
            assertEquals(Outcome.SAVE_SUCCESS, outcome.outcome());
            assertEquals(Set.of(resistance), outcome.spentOneShots());
        }

        @Test
        @DisplayName("A Faerie Fire target gets no disadvantage from its invisibility")
        void revealed() {
            RollModifiers revealed = new RollModifiers(List.of(), List.of(), List.of(), List.of(), Set.of(), true);
            var outcome = new Resolver(Rng.scripted(12, 5)).resolve(longsword(true),
                    List.of(new Resolver.Target(GOBLIN, 15, 0, List.of("invisible")).withModifiers(revealed)))
                    .getFirst();
            assertEquals(RollMode.NORMAL, outcome.rollMode());
            assertEquals(1, outcome.d20s().size());
        }
    }

    @Test
    @DisplayName("A death save's total with Bless counts, a natural 1 still fails twice and a natural 20 still revives (PHB p.197)")
    void deathSaves() {
        Vitals dying = new Vitals(0, 20, 0, LifeState.DYING, 0, 0, true);
        assertEquals(1, DamageRules.deathSave(dying, 8, 11).vitals().deathSaveSuccesses());
        assertEquals(2, DamageRules.deathSave(dying, 1, 5).vitals().deathSaveFailures());
        assertEquals(LifeState.ALIVE, DamageRules.deathSave(dying, 20, 17).vitals().lifeState());
        assertEquals(1, DamageRules.deathSave(dying, 11, 9).vitals().deathSaveFailures());
    }

    @Test
    @DisplayName("A modifier says what it does in words")
    void describe() {
        assertEquals("+1d4 to attack rolls", of(OngoingModifier.Kind.ATTACK_ROLL, "1d4", 0).describe());
        assertEquals("-1d4 to saving throws", of(OngoingModifier.Kind.SAVE, "-1d4", 0).describe());
        assertEquals("AC +2", of(OngoingModifier.Kind.AC, null, 2).describe());
        assertEquals("speed doubled", of(OngoingModifier.Kind.SPEED_DOUBLED, null, 0).describe());
        assertEquals("advantage on DEX saving throws",
                mode(OngoingModifier.Kind.SAVE_MODE, RollMode.ADVANTAGE, "DEX").describe());
    }
}
