package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.SpellConsent;
import com.tabletophelper.encounter.state.RepeatSave;
import com.tabletophelper.reference.Race;
import com.tabletophelper.reference.SpellTemplate;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.Resolver;
import com.tabletophelper.rules.combat.RollMode;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Monster and race traits that change how spells work (D191): Magic Resistance and Gnome Cunning on every save a spell
 * forces, Limited Magic Immunity keeping low-level spells out, and stat-block immunities against spell damage and spell
 * conditions.
 */
class SpellTraitsTest {

    private static final String MAGIC_RESISTANCE = """
            [{"name": "Magic Resistance",
              "description": "The golem has advantage on saving throws against spells and other magical effects."}]""";
    private static final String LIMITED_MAGIC_IMMUNITY = """
            [{"name": "Limited Magic Immunity",
              "description": "The rakshasa can't be affected or detected by spells of 6th level or lower unless it \
            wishes to be. It has advantage on saving throws against all other spells and magical effects."}]""";
    private static final String GNOME_CUNNING = """
            [{"name": "Gnome Cunning",
              "description": "You have advantage on all Intelligence, Wisdom, and Charisma saving throws against \
            magic."}]""";

    private static final String FIREBOLT = """
            {"spellLevel": 5, "deliveryMethod": "SAVING_THROW", "saveAbility": "DEX", "halfOnSave": true,
             "effects": [{"effectType": "DAMAGE", "damageDice": "4d6", "damageType": "fire"}]}""";

    private CombatFixture f;
    private EncounterParticipant wizard;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        f.effectApplier.setSpellRepository(f.spellRepository);
        wizard = f.player("Wizard", 30, 12);
    }

    private SpellCaster.CastResult cast(EncounterParticipant caster, String spell, int slotLevel,
                                        List<EncounterParticipant> targets) {
        return f.spellCaster.cast(f.encounter, caster, spell, slotLevel, targets, "INT", 7, 15, null, "",
                MetamagicCasting.Plan.NONE, null, null);
    }

    private EncounterParticipant monsterWithTraits(String name, int hp, String traits) {
        EncounterParticipant monster = f.monster(name, hp, 12);
        monster.getMonster().setTraits(traits);
        return monster;
    }

    private EncounterParticipant gnome() {
        EncounterParticipant gnome = f.player("Gnome", 30, 12);
        gnome.getCharacter().setRaceRef(Race.builder().id(UUID.randomUUID()).name("Gnome").traits(GNOME_CUNNING)
                .build());
        return gnome;
    }

    @Nested
    @DisplayName("Reading the traits")
    class Reading {

        @Test
        @DisplayName("Magic Resistance covers every ability; Gnome Cunning only INT, WIS and CHA (MM p.170, PHB p.37)")
        void abilities() {
            EncounterParticipant golem = monsterWithTraits("Golem", 60, MAGIC_RESISTANCE);
            for (Ability ability : Ability.values()) {
                assertEquals("Magic Resistance", f.stats.spellSaveAdvantage(golem, ability));
            }
            EncounterParticipant gnome = gnome();
            assertEquals("Gnome Cunning", f.stats.spellSaveAdvantage(gnome, Ability.INT));
            assertEquals("Gnome Cunning", f.stats.spellSaveAdvantage(gnome, Ability.WIS));
            assertEquals("Gnome Cunning", f.stats.spellSaveAdvantage(gnome, Ability.CHA));
            assertNull(f.stats.spellSaveAdvantage(gnome, Ability.DEX));
            assertNull(f.stats.spellSaveAdvantage(f.monster("Ogre", 60, 11), Ability.DEX));
        }

        @Test
        @DisplayName("A conditional trait, or one against only some spells, gives nothing")
        void conditional() {
            EncounterParticipant isolde = monsterWithTraits("Isolde", 60, """
                    [{"name": "Magic Resistance Aura", "description": "While holding Nepenthe, Isolde creates an aura. \
                    Isolde and all creatures friendly to her in the aura have advantage on saving throws against \
                    spells and other magical effects."}]""");
            EncounterParticipant hunter = monsterWithTraits("Locathah Hunter", 30, """
                    [{"name": "Leviathan Will", "description": "The hunter has advantage on saving throws against \
                    spells and effects that control its actions."}]""");
            assertNull(f.stats.spellSaveAdvantage(isolde, Ability.WIS));
            assertNull(f.stats.spellSaveAdvantage(hunter, Ability.WIS));
        }

        @Test
        @DisplayName("Limited Magic Immunity keeps out spells of 6th level or lower, except the creature's own, and gives "
                + "advantage against higher ones (MM p.257)")
        void limitedMagicImmunity() {
            EncounterParticipant rakshasa = monsterWithTraits("Rakshasa", 110, LIMITED_MAGIC_IMMUNITY);
            assertEquals("Limited Magic Immunity", f.stats.spellImmunityTrait(wizard, rakshasa, 0));
            assertEquals("Limited Magic Immunity", f.stats.spellImmunityTrait(wizard, rakshasa, 6));
            assertNull(f.stats.spellImmunityTrait(wizard, rakshasa, 7));
            assertNull(f.stats.spellImmunityTrait(rakshasa, rakshasa, 3));
            assertEquals("Limited Magic Immunity", f.stats.spellSaveAdvantage(rakshasa, Ability.WIS));
        }
    }

    @Nested
    @DisplayName("Magic Resistance on saves")
    class Saves {

        @Test
        @DisplayName("A spell's save is made with advantage, named in the log: d20s 2 and 18 keep 18, so half damage")
        void castSave() {
            f.spell("Firebolt Storm", 5, FIREBOLT);
            EncounterParticipant golem = monsterWithTraits("Golem", 60, MAGIC_RESISTANCE);
            f.dice.script(5, 5, 5, 5, 2, 18);
            cast(wizard, "Firebolt Storm", 5, List.of(golem));
            assertEquals(50, golem.getHpCurrent());
            assertTrue(f.descriptions().stream().anyMatch(d -> d.contains("DEX save (advantage: Magic Resistance) "
                    + "2d20(2, 18) with advantage → 18")), f.descriptions().toString());
        }

        @Test
        @DisplayName("A monster's action isn't a spell, so it gets no advantage; a spell's Effect does")
        void notAgainstNonmagical() {
            EncounterParticipant golem = monsterWithTraits("Golem", 60, MAGIC_RESISTANCE);
            Effect breath = new Effect("Fire Breath", new Effect.Save(Ability.DEX, 15, true),
                    List.of(new Effect.DamagePart(new Dice(4, 6, 0), "fire")), null, List.of(), false, null);
            Resolver.Target asAction = f.stats.targets(List.of(golem), breath, null).getFirst();
            assertTrue(asAction.saveSources().isEmpty());
            assertTrue(asAction.namedSaveSources().isEmpty());
            Resolver.Target asSpell = f.stats.spellTargets(List.of(golem), breath, null).getFirst();
            assertEquals(List.of(RollMode.ADVANTAGE), asSpell.saveSources());
            assertEquals("Magic Resistance", asSpell.namedSaveSources().getFirst().reason());
        }

        @Test
        @DisplayName("Gnome Cunning: a WIS save against a spell has advantage, a DEX save doesn't")
        void gnomeCunning() {
            EncounterParticipant gnome = gnome();
            Effect wisdom = new Effect("Command", new Effect.Save(Ability.WIS, 15, false), List.of(), null,
                    List.of("prone"), false, null);
            Effect dexterity = new Effect("Fireball", new Effect.Save(Ability.DEX, 15, true), List.of(), null,
                    List.of(), false, null);
            assertEquals(List.of(RollMode.ADVANTAGE),
                    f.stats.spellTargets(List.of(gnome), wisdom, wizard).getFirst().saveSources());
            assertTrue(f.stats.spellTargets(List.of(gnome), dexterity, wizard).getFirst().saveSources().isEmpty());
        }

        @Test
        @DisplayName("The repeated save against a spell's condition has advantage; against a monster's action's it doesn't")
        void repeatSave() {
            f.spell("Hold Monster", 5, """
                    {"spellLevel": 5, "deliveryMethod": "SAVING_THROW", "saveAbility": "WIS",
                     "conditionsInflicted": [{"condition": "paralyzed"}]}""");
            EncounterParticipant golem = monsterWithTraits("Golem", 60, MAGIC_RESISTANCE);
            EncounterParticipant ghoul = f.monster("Ghoul", 22, 12);
            List<ConditionEntry> held = new ArrayList<>();
            held.add(ConditionEntry.fromEffect("paralyzed", 10, 1, "Hold Monster", wizard.getId(), null,
                    new RepeatSave(Ability.WIS, 15, false)));
            held.add(ConditionEntry.fromEffect("poisoned", 10, 1, "Ghoul", ghoul.getId(), null,
                    new RepeatSave(Ability.CON, 15, false)));
            golem.setActiveConditions(held);

            f.dice.script(3, 17);
            f.effectApplier.repeatSave(f.encounter, golem, held.get(0).id());
            assertEquals(List.of("poisoned"), CreatureStats.conditionNames(golem));
            assertTrue(f.descriptions().getLast().contains("against paralyzed (advantage: Magic Resistance): "
                    + "2d20(3, 17)"), f.descriptions().getLast());

            f.dice.script(3);
            f.effectApplier.repeatSave(f.encounter, golem, held.get(1).id());
            assertEquals(List.of("poisoned"), CreatureStats.conditionNames(golem));
            assertTrue(f.descriptions().getLast().contains("against poisoned: d20(3)"), f.descriptions().getLast());
        }

        @Test
        @DisplayName("Counted saves against a spell, as Flesh to Stone's, have advantage too")
        void countedSaves() {
            f.spell("Flesh to Stone", 6, """
                    {"spellLevel": 6, "deliveryMethod": "SAVING_THROW", "saveAbility": "CON", "concentration": true,
                     "durationRounds": 10,
                     "conditionsInflicted": [{"condition": "restrained", "durationRounds": 10}],
                     "countedSaves": {"ability": "CON", "successes": 3, "failures": 3,
                                      "endsConditions": ["restrained"], "failureCondition": "petrified"}}""");
            EncounterParticipant golem = monsterWithTraits("Golem", 60, MAGIC_RESISTANCE);
            f.dice.script(2, 3);
            cast(wizard, "Flesh to Stone", 6, List.of(golem));
            assertEquals(List.of("restrained"), CreatureStats.conditionNames(golem));
            f.dice.script(2, 19);
            f.effectApplier.effectsAtTurnEnd(f.encounter, golem);
            assertTrue(f.descriptions().getLast().contains("(advantage: Magic Resistance): 2d20(2, 19)"),
                    f.descriptions().getLast());
        }
    }

    @Nested
    @DisplayName("Limited Magic Immunity on a cast")
    class Immunity {

        @Test
        @DisplayName("A 5th-level spell has no effect on a rakshasa, even upcast; a 7th-level one does, with advantage")
        void rakshasa() {
            f.spell("Firebolt Storm", 5, FIREBOLT);
            f.spell("Big Storm", 7, FIREBOLT.replace("\"spellLevel\": 5", "\"spellLevel\": 7"));
            EncounterParticipant rakshasa = monsterWithTraits("Rakshasa", 110, LIMITED_MAGIC_IMMUNITY);

            f.dice.script(5, 5, 5, 5, 2);
            SpellCaster.CastResult blocked = cast(wizard, "Firebolt Storm", 9, List.of(rakshasa));
            assertEquals(110, rakshasa.getHpCurrent());
            assertTrue(blocked.summary().contains("has no effect on Rakshasa (Limited Magic Immunity)"),
                    blocked.summary());

            f.dice.script(5, 5, 5, 5, 2, 3);
            cast(wizard, "Big Storm", 7, List.of(rakshasa));
            assertEquals(90, rakshasa.getHpCurrent());
            assertTrue(f.descriptions().stream().anyMatch(d -> d.contains("(advantage: Limited Magic Immunity)")));
        }
    }

    @Nested
    @DisplayName("Choosing to be affected (D198)")
    class Consent {

        private static final String HEAL = """
                {"spellLevel": 6, "deliveryMethod": "AUTO_HIT", "canTargetAllies": true, "canTargetEnemies": true,
                 "effects": [{"effectType": "HEAL", "healingDice": "70"}], "healing": {"healingDice": "70"}}""";

        private EncounterParticipant rakshasa;

        @BeforeEach
        void setUp() {
            f.spell("Heal", 6, HEAL);
            f.spell("Firebolt Storm", 5, FIREBOLT);
            rakshasa = monsterWithTraits("Rakshasa", 110, LIMITED_MAGIC_IMMUNITY);
            rakshasa.setHpCurrent(20);
        }

        private SpellCaster.CastResult cast(String spell, int level, Map<UUID, Boolean> consents) {
            return f.spellCaster.cast(f.encounter, wizard, spell, level, List.of(rakshasa), "INT", 7, 15, null, "",
                    MetamagicCasting.Plan.NONE, null, null, consents);
        }

        @Test
        @DisplayName("By default a rakshasa lets an ally's Heal through and keeps its Fireball out (MM p.257)")
        void helpfulOnly() {
            assertEquals("Limited Magic Immunity", f.stats.spellConsentTrait(rakshasa));
            assertEquals(SpellConsent.HELPFUL, rakshasa.getSpellConsent());
            cast("Heal", 6, Map.of());
            assertEquals(90, rakshasa.getHpCurrent());
            assertTrue(f.descriptions().stream().anyMatch(d -> d.contains("Rakshasa lets Heal affect it (Limited Magic "
                    + "Immunity)")), f.descriptions().toString());

            f.dice.script(5, 5, 5, 5, 2);
            cast("Firebolt Storm", 5, Map.of());
            assertEquals(90, rakshasa.getHpCurrent());
        }

        @Test
        @DisplayName("Set to never, it keeps out even Heal; set to always, it takes the Fireball too")
        void neverAndAlways() {
            rakshasa.setSpellConsent(SpellConsent.NEVER);
            cast("Heal", 6, Map.of());
            assertEquals(20, rakshasa.getHpCurrent());

            rakshasa.setSpellConsent(SpellConsent.ALWAYS);
            rakshasa.setHpCurrent(110);
            // 4d6 (5, 5, 5, 5) = 20; its DEX save of 2 fails
            f.dice.script(5, 5, 5, 5, 2, 2);
            cast("Firebolt Storm", 5, Map.of());
            assertEquals(90, rakshasa.getHpCurrent());
        }

        @Test
        @DisplayName("The choice made for a cast overrides the standing one, either way")
        void castChoice() {
            cast("Heal", 6, Map.of(rakshasa.getId(), false));
            assertEquals(20, rakshasa.getHpCurrent());

            rakshasa.setHpCurrent(110);
            f.dice.script(5, 5, 5, 5, 2, 2);
            cast("Firebolt Storm", 5, Map.of(rakshasa.getId(), true));
            assertEquals(90, rakshasa.getHpCurrent());
        }

        @Test
        @DisplayName("Tiamat's \"unless she wishes\" gives the choice (RoT p.92); the hollyphant's aura gives none, keeping "
                + "a low-level Heal out whatever is chosen (BGDIA p.237)")
        void whoChooses() {
            EncounterParticipant tiamat = monsterWithTraits("Tiamat", 615, """
                    [{"name": "Limited Magic Immunity", "description": "Unless she wishes to be affected, Tiamat is \
                    immune to spells of 6th level or lower."}]""");
            EncounterParticipant mahadi = monsterWithTraits("Mahadi the Rakshasa", 110, """
                    [{"name": "Limited Magic Immunity", "description": "Mahadi can't be affected or detected by \
                    spells of 6th level or lower unless he wishes to be."}]""");
            EncounterParticipant hollyphant = monsterWithTraits("Hollyphant", 36, """
                    [{"name": "Aura of Invulnerability", "description": "An invisible barrier of magical force forms \
                    a 10-foot-radius sphere around the hollyphant for as long as it lives. Any spell of 5th level or \
                    lower cast from outside the barrier can't affect creatures or objects within it."}]""");
            assertEquals("Limited Magic Immunity", f.stats.spellConsentTrait(tiamat));
            assertEquals("Limited Magic Immunity", f.stats.spellConsentTrait(mahadi));
            assertNull(f.stats.spellConsentTrait(hollyphant));

            f.spell("Cure Wounds", 1, HEAL.replace("\"spellLevel\": 6", "\"spellLevel\": 1"));
            hollyphant.setHpCurrent(10);
            hollyphant.setSpellConsent(SpellConsent.ALWAYS);
            f.spellCaster.cast(f.encounter, wizard, "Cure Wounds", 1, List.of(hollyphant), "INT", 7, 15, null, "",
                    MetamagicCasting.Plan.NONE, null, null, Map.of());
            assertEquals(10, hollyphant.getHpCurrent());
        }

        @Test
        @DisplayName("Harmful: a save, an attack, damage, conditions or enemies only, as Hex; Heal, Bless and Invisibility aren't")
        void harmful() {
            assertTrue(template(FIREBOLT).harmful());
            assertTrue(template("""
                    {"deliveryMethod": "AUTO_HIT", "canTargetAllies": false, "canTargetEnemies": true,
                     "effects": [{"effectType": "DEBUFF"}]}""").harmful());
            assertTrue(template("""
                    {"deliveryMethod": "AUTO_HIT", "canTargetAllies": true, "canTargetEnemies": true,
                     "conditionsInflicted": [{"condition": "unconscious"}]}""").harmful());
            assertFalse(template(HEAL).harmful());
            // A spell only for allies is cast on willing creatures, so Invisibility's condition is helpful
            assertFalse(template("""
                    {"deliveryMethod": "AUTO_HIT", "canTargetAllies": true, "canTargetEnemies": false,
                     "conditionsInflicted": [{"condition": "invisible"}]}""").harmful());
            assertFalse(template("""
                    {"deliveryMethod": "AUTO_HIT", "canTargetAllies": true, "canTargetEnemies": false,
                     "effects": [{"effectType": "BUFF"}]}""").harmful());
        }

        private static SpellTemplate template(String json) {
            return SpellTemplate.parse(json);
        }
    }

    @Nested
    @DisplayName("Stat-block immunities against spells")
    class StatBlockImmunities {

        @Test
        @DisplayName("A damage immunity takes none of a spell's damage")
        void damageImmunity() {
            f.spell("Firebolt Storm", 5, FIREBOLT);
            EncounterParticipant elemental = f.monster("Fire Elemental", 60, 13);
            elemental.getMonster().setDamageImmunities("[\"fire\", \"poison\"]");
            f.dice.script(5, 5, 5, 5, 2);
            cast(wizard, "Firebolt Storm", 5, List.of(elemental));
            assertEquals(60, elemental.getHpCurrent());
        }

        @Test
        @DisplayName("A condition immunity stops a spell's condition on a failed save")
        void conditionImmunity() {
            f.spell("Charm Monster", 4, """
                    {"spellLevel": 4, "deliveryMethod": "SAVING_THROW", "saveAbility": "WIS",
                     "conditionsInflicted": [{"condition": "charmed", "durationRounds": 600}]}""");
            EncounterParticipant golem = f.monster("Golem", 60, 13);
            golem.getMonster().setConditionImmunities("[\"charmed\", \"petrified\"]");
            f.dice.script(2);
            cast(wizard, "Charm Monster", 4, List.of(golem));
            assertTrue(CreatureStats.conditionNames(golem).isEmpty());
            assertTrue(f.descriptions().stream().anyMatch(d -> d.contains("immune to charmed")),
                    f.descriptions().toString());
        }

        @Test
        @DisplayName("A condition a spell leaves on its own, as Flesh to Stone's petrification, skips an immune creature")
        void lastingConditionImmunity() {
            EncounterParticipant golem = f.monster("Stone Golem", 178, 17);
            golem.getMonster().setConditionImmunities("[\"petrified\"]");
            f.effectApplier.addLastingCondition(f.encounter, wizard, golem, "petrified", null, "Flesh to Stone");
            assertTrue(CreatureStats.conditionNames(golem).isEmpty());
            assertTrue(f.descriptions().getLast().contains("Stone Golem is immune to petrified"),
                    f.descriptions().getLast());
        }
    }
}
