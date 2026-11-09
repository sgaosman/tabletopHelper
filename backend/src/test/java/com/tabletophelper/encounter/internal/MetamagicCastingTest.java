package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.FlexibleCasting;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.CastSpellRequest;
import com.tabletophelper.encounter.dto.CastSpellResponse;
import com.tabletophelper.encounter.dto.FontOfMagicUseRequest;
import com.tabletophelper.encounter.dto.MetamagicChoice;
import com.tabletophelper.encounter.dto.MetamagicOption;
import com.tabletophelper.encounter.dto.SpellOption;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.rules.character.FontOfMagic;
import com.tabletophelper.rules.combat.Metamagic;
import com.tabletophelper.rules.combat.Metamagic.Option;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A sorcerer's Metamagic on a cast and Flexible Casting at the table (PHB p.101–102, M18), through SpellcastingService
 * and FontOfMagicService over scripted dice: what each option does, what it costs, which spells it can change, and the
 * bonus action Quickened Spell and Flexible Casting take.
 */
class MetamagicCastingTest {

    private static final String FIREBALL = """
            {"spellName": "Fireball", "spellLevel": 3, "castingTime": "ACTION", "deliveryMethod": "SAVING_THROW",
             "saveAbility": "DEX", "halfOnSave": true, "targetType": "AOE_SPHERE", "durationText": "Instantaneous",
             "components": {"verbal": true, "somatic": true},
             "effects": [{"effectType": "DAMAGE", "damageDice": "8d6", "damageType": "fire",
               "upcastScaling": {"additionalDicePerLevel": "1d6"}}], "conditionsInflicted": null}""";

    private static final String HOLD_PERSON = """
            {"spellName": "Hold Person", "spellLevel": 2, "castingTime": "ACTION", "deliveryMethod": "SAVING_THROW",
             "saveAbility": "WIS", "halfOnSave": false, "concentration": true, "durationRounds": 10,
             "durationText": "Concentration, up to 1 minute", "targetType": "SINGLE_TARGET", "targetCount": 1,
             "targetCountUpcastScaling": {"additionalTargetsPerLevel": 1}, "selfOnly": false,
             "components": {"verbal": true, "somatic": true}, "effects": [],
             "conditionsInflicted": [{"condition": "paralyzed", "durationRounds": 10}]}""";

    private static final String FIRE_BOLT = """
            {"spellName": "Fire Bolt", "spellLevel": 0, "castingTime": "ACTION", "deliveryMethod": "SPELL_ATTACK",
             "spellAttackType": "RANGED", "targetType": "SINGLE_TARGET", "targetCount": 1, "selfOnly": false,
             "durationText": "Instantaneous", "components": {"verbal": true, "somatic": true},
             "effects": [{"effectType": "DAMAGE", "damageDice": "1d10", "damageType": "fire"}],
             "conditionsInflicted": null}""";

    private CombatFixture f;
    private EncounterParticipant sorin;
    private EncounterParticipant goblin;
    private EncounterParticipant orc;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        sorin = f.player("Sorin", 40, 12);
        sorin.getCharacter().setCharisma(16);
        sorin.setSpellAttackBonus(6);
        sorin.setSpellSaveDc(14);
        List<Feature> features = new ArrayList<>();
        for (Option option : List.of(Option.QUICKENED, Option.TWINNED, Option.HEIGHTENED, Option.CAREFUL,
                Option.EMPOWERED, Option.EXTENDED, Option.SUBTLE)) {
            features.add(new Feature(Metamagic.FEATURE_PREFIX + option.displayName(), null, "Sorcerer"));
        }
        sorin.getCharacter().setFeatures(features);
        sorin.setSpellSlotsCurrent(new LinkedHashMap<>(Map.of("1", new SpellSlot(4, 0), "2", new SpellSlot(3, 0),
                "3", new SpellSlot(3, 0))));
        sorin.setResourcePoolsCurrent(new ArrayList<>(List.of(sorceryPoints(5, 7))));
        goblin = f.monster("Goblin", 30, 13);
        orc = f.monster("Orc", 30, 13);
        f.spell("Fireball", 3, FIREBALL);
        f.spell("Hold Person", 2, HOLD_PERSON);
        f.spell("Fire Bolt", 0, FIRE_BOLT);
        f.knows(sorin, "Fireball");
        f.knows(sorin, "Hold Person");
        f.knows(sorin, "Fire Bolt");
    }

    private static ResourcePoolEntry sorceryPoints(int current, int max) {
        return new ResourcePoolEntry(FlexibleCasting.SORCERY_POINTS, "Sorcery Points", "CLASS", null, max,
                "sorcererLevel", current, "longRest", null, null, "FREE", "sparkles", null);
    }

    private int points() {
        return FlexibleCasting.sorceryPoints(sorin.getResourcePoolsCurrent()).orElseThrow().currentUses();
    }

    private static CastSpellRequest request(String spell, int slotLevel, List<EncounterParticipant> targets,
                                            MetamagicChoice... metamagic) {
        CastSpellRequest request = new CastSpellRequest();
        request.setSpellName(spell);
        request.setSlotLevel(slotLevel);
        request.setTargetIds(targets.stream().map(EncounterParticipant::getId).toList());
        request.setMetamagic(List.of(metamagic));
        return request;
    }

    private static MetamagicChoice choice(Option option, EncounterParticipant... creatures) {
        return new MetamagicChoice(option, creatures.length == 0 ? null
                : List.of(creatures).stream().map(EncounterParticipant::getId).toList());
    }

    private CastSpellResponse cast(CastSpellRequest request, UUID userId, int... rolls) {
        f.dice.script(rolls);
        return f.spellcasting.castSpell(f.encounterId(), request, sorin.getId(), userId);
    }

    @Nested
    @DisplayName("Quickened Spell")
    class Quickened {

        @Test
        @DisplayName("Costs 2 points and a bonus action, leaving the action, which may then cast only a cantrip (PHB p.202)")
        void quickenedFireballTakesTheBonusAction() {
            UUID player = f.playerControlling(sorin);
            String summary = cast(request("Fireball", 3, List.of(goblin), choice(Option.QUICKENED)), player,
                    1, 1, 1, 1, 1, 1, 1, 1, 20).getResultSummary();

            assertEquals(3, points());
            assertTrue(sorin.getTurnEconomy().bonusActionUsed());
            assertEquals(0, sorin.getTurnEconomy().actionsUsed());
            assertTrue(summary.startsWith("Sorin casts Fireball (bonus action) (Quickened Spell, 2 sorcery points)."),
                    summary);

            ConflictException levelled = assertThrows(ConflictException.class,
                    () -> cast(request("Hold Person", 2, List.of(orc)), player, 10));
            assertTrue(levelled.getMessage().contains("cantrip"), levelled.getMessage());
            cast(request("Fire Bolt", 0, List.of(orc)), player, 2);
            assertEquals(1, sorin.getTurnEconomy().actionsUsed());
        }

        @Test
        @DisplayName("Too few sorcery points refuse the cast before anything is spent")
        void tooFewPoints() {
            sorin.setResourcePoolsCurrent(new ArrayList<>(List.of(sorceryPoints(1, 7))));

            assertThrows(ConflictException.class,
                    () -> cast(request("Fireball", 3, List.of(goblin), choice(Option.QUICKENED)), f.dmUserId));

            assertEquals(1, points());
            assertEquals(0, sorin.getSpellSlotsCurrent().get("3").used());
        }
    }

    @Nested
    @DisplayName("Twinned Spell")
    class Twinned {

        @Test
        @DisplayName("Hold Person at 2nd level reaches two creatures for 2 points, both paralyzed on failed saves")
        void twinnedHoldPerson() {
            cast(request("Hold Person", 2, List.of(goblin, orc), choice(Option.TWINNED)), f.dmUserId, 3, 4);

            assertEquals(3, points());
            assertTrue(CombatFixture.conditionNames(goblin).contains("paralyzed"));
            assertTrue(CombatFixture.conditionNames(orc).contains("paralyzed"));
        }

        @Test
        @DisplayName("Fire Bolt, a cantrip, is twinned for 1 point")
        void twinnedCantrip() {
            cast(request("Fire Bolt", 0, List.of(goblin, orc), choice(Option.TWINNED)), f.dmUserId, 15, 5, 15, 6);

            assertEquals(4, points());
            assertEquals(25, goblin.getHpCurrent());
            assertEquals(24, orc.getHpCurrent());
        }

        @Test
        @DisplayName("Hold Person at 3rd level can already target two creatures, so it can't be twinned; Fireball never")
        void notTwinnable() {
            assertThrows(ValidationException.class, () -> cast(request("Hold Person", 3, List.of(goblin, orc),
                    choice(Option.TWINNED)), f.dmUserId));
            assertThrows(ValidationException.class, () -> cast(request("Fireball", 3, List.of(goblin, orc),
                    choice(Option.TWINNED)), f.dmUserId));
            assertEquals(5, points());
        }

        @Test
        @DisplayName("Twinned needs exactly two different targets, and without it a single-target spell takes one")
        void twoTargets() {
            assertThrows(ValidationException.class, () -> cast(request("Hold Person", 2, List.of(goblin),
                    choice(Option.TWINNED)), f.dmUserId));
            assertThrows(ValidationException.class, () -> cast(request("Hold Person", 2, List.of(goblin, orc)),
                    f.dmUserId));
        }
    }

    @Nested
    @DisplayName("Heightened, Careful and Empowered Spell")
    class Saves {

        @Test
        @DisplayName("Heightened: 3 points, the chosen target rolls its first save with disadvantage")
        void heightened() {
            // WIS save +0 against DC 14: 18 and 5 with disadvantage, the 5 counts
            cast(request("Hold Person", 2, List.of(goblin), choice(Option.HEIGHTENED, goblin)), f.dmUserId, 18, 5);

            assertEquals(2, points());
            assertTrue(CombatFixture.conditionNames(goblin).contains("paralyzed"));
        }

        @Test
        @DisplayName("Careful: up to the CHA modifier of the targets succeed automatically and take half of Fireball")
        void careful() {
            // 8d6 = 16 fire; only the orc rolls its DEX save, a 2
            String summary = cast(request("Fireball", 3, List.of(goblin, orc), choice(Option.CAREFUL, goblin)),
                    f.dmUserId, 2, 2, 2, 2, 2, 2, 2, 2, 2).getResultSummary();

            assertEquals(4, points());
            assertEquals(22, goblin.getHpCurrent());
            assertEquals(14, orc.getHpCurrent());
            assertTrue(summary.contains("(Careful Spell, 1 sorcery point)"), summary);
            assertTrue(summary.contains("DEX save vs DC 14 — succeeds automatically, 8 fire damage (half)"), summary);
        }

        @Test
        @DisplayName("Careful protects only the spell's targets, at most the CHA modifier (+3) of them")
        void carefulLimits() {
            EncounterParticipant kobold = f.monster("Kobold", 10, 12);
            EncounterParticipant rat = f.monster("Rat", 5, 10);
            assertThrows(ValidationException.class, () -> cast(request("Fireball", 3, List.of(goblin),
                    choice(Option.CAREFUL, orc)), f.dmUserId));
            assertThrows(ValidationException.class, () -> cast(request("Fireball", 3,
                    List.of(goblin, orc, kobold, rat), choice(Option.CAREFUL, goblin, orc, kobold, rat)),
                    f.dmUserId));
        }

        @Test
        @DisplayName("Empowered joins Heightened for 4 points, rerolling up to 3 dice below their average")
        void empoweredWithHeightened() {
            // 8d6: 1, 1, 1, 6, 6, 6, 6, 6 = 33; the three 1s rerolled to 6s = 48; the goblin's DEX save rolls 20 and
            // 2 with disadvantage, and the 2 counts
            cast(request("Fireball", 3, List.of(goblin), choice(Option.HEIGHTENED, goblin),
                    choice(Option.EMPOWERED)), f.dmUserId, 1, 1, 1, 6, 6, 6, 6, 6, 6, 6, 6, 20, 2);

            assertEquals(1, points());
            assertEquals(0, goblin.getHpCurrent());
        }

        @Test
        @DisplayName("Two options other than Empowered can't go on one spell")
        void onlyOne() {
            assertThrows(ValidationException.class, () -> cast(request("Hold Person", 2, List.of(goblin, orc),
                    choice(Option.TWINNED), choice(Option.QUICKENED)), f.dmUserId));
            assertEquals(5, points());
        }
    }

    @Nested
    @DisplayName("Extended, Subtle and what the caster knows")
    class Others {

        @Test
        @DisplayName("Extended doubles Hold Person's concentration and paralysis to 20 rounds")
        void extended() {
            cast(request("Hold Person", 2, List.of(goblin), choice(Option.EXTENDED)), f.dmUserId, 2);

            assertEquals(20, sorin.getConcentration().durationRounds());
            ConditionEntry paralyzed = goblin.getActiveConditions().stream()
                    .filter(c -> "paralyzed".equals(c.name())).findFirst().orElseThrow();
            assertEquals(20, paralyzed.duration());
        }

        @Test
        @DisplayName("Subtle Spell casts a verbal spell while silenced")
        void subtleWhileSilenced() {
            sorin.setActiveConditions(new ArrayList<>(List.of(ConditionEntry.manual("silenced", null, 1))));
            assertThrows(ConflictException.class, () -> cast(request("Fire Bolt", 0, List.of(goblin)), f.dmUserId));

            cast(request("Fire Bolt", 0, List.of(goblin), choice(Option.SUBTLE)), f.dmUserId, 15, 5);

            assertEquals(4, points());
            assertEquals(25, goblin.getHpCurrent());
        }

        @Test
        @DisplayName("An option the sorcerer doesn't know is a 409, and a monster knows none")
        void unknownOption() {
            sorin.getCharacter().setFeatures(List.of(new Feature("Metamagic: Subtle Spell", null, "Sorcerer")));
            assertThrows(ConflictException.class, () -> cast(request("Fireball", 3, List.of(goblin),
                    choice(Option.QUICKENED)), f.dmUserId));
        }

        @Test
        @DisplayName("Spell options list the known Metamagic with costs and reasons")
        void spellOptions() {
            List<SpellOption> options = f.spellcasting.spellOptions(f.encounterId(), sorin.getId(), f.dmUserId);
            SpellOption holdPerson = options.stream().filter(o -> o.name().equals("Hold Person")).findFirst()
                    .orElseThrow();
            MetamagicOption twinned = holdPerson.metamagic().stream()
                    .filter(o -> o.option() == Option.TWINNED).findFirst().orElseThrow();
            assertNull(twinned.ineligibleReason());
            assertEquals(2, twinned.cost());
            assertTrue(twinned.costIsCastLevel());
            assertEquals(2, twinned.maxCastLevel());
            SpellOption fireBolt = options.stream().filter(o -> o.name().equals("Fire Bolt")).findFirst()
                    .orElseThrow();
            MetamagicOption heightened = fireBolt.metamagic().stream()
                    .filter(o -> o.option() == Option.HEIGHTENED).findFirst().orElseThrow();
            assertNotNull(heightened.ineligibleReason());
            assertEquals(3, heightened.cost());
        }
    }

    @Nested
    @DisplayName("Flexible Casting at the table")
    class FlexibleCastingAtTheTable {

        private void use(FontOfMagic.Direction direction, int level, UUID userId) {
            f.fontOfMagic.use(f.encounterId(), new FontOfMagicUseRequest(sorin.getId(), direction, level), userId);
        }

        @Test
        @DisplayName("Points to a slot: 3 points make a 2nd-level slot, as a bonus action, and a second exchange that turn is refused to a player")
        void createSlot() {
            UUID player = f.playerControlling(sorin);
            use(FontOfMagic.Direction.POINTS_TO_SLOT, 2, player);

            assertEquals(2, points());
            assertEquals(new SpellSlot(3, 0, 1), sorin.getSpellSlotsCurrent().get("2"));
            assertTrue(sorin.getTurnEconomy().bonusActionUsed());
            assertThrows(ConflictException.class, () -> use(FontOfMagic.Direction.SLOT_TO_POINTS, 1, player));
            assertTrue(f.log.getLast().getDescription().contains("creates a level 2 spell slot from 3 sorcery points"));
        }

        @Test
        @DisplayName("A slot to points: a 1st-level slot gives 1 point; a created 4th-level slot is cast like any other")
        void slotToPointsAndCreatedSlotCast() {
            sorin.setResourcePoolsCurrent(new ArrayList<>(List.of(sorceryPoints(6, 7))));
            use(FontOfMagic.Direction.SLOT_TO_POINTS, 1, f.dmUserId);
            assertEquals(7, points());
            assertEquals(1, sorin.getSpellSlotsCurrent().get("1").used());

            use(FontOfMagic.Direction.POINTS_TO_SLOT, 4, f.dmUserId);
            assertEquals(1, points());
            assertEquals(new SpellSlot(0, 0, 1), sorin.getSpellSlotsCurrent().get("4"));
            f.knows(sorin, "Fireball");
            cast(request("Fireball", 4, List.of(goblin)), f.dmUserId, 1, 1, 1, 1, 1, 1, 1, 1, 1, 20);
            assertEquals(new SpellSlot(0, 1, 1), sorin.getSpellSlotsCurrent().get("4"));
        }

        @Test
        @DisplayName("Refused: a slot above 5th, too few points, points already at the maximum, no sorcery points")
        void refusals() {
            assertThrows(ValidationException.class, () -> use(FontOfMagic.Direction.POINTS_TO_SLOT, 6, f.dmUserId));
            sorin.setResourcePoolsCurrent(new ArrayList<>(List.of(sorceryPoints(1, 7))));
            assertThrows(ConflictException.class, () -> use(FontOfMagic.Direction.POINTS_TO_SLOT, 1, f.dmUserId));
            sorin.setResourcePoolsCurrent(new ArrayList<>(List.of(sorceryPoints(7, 7))));
            assertThrows(ConflictException.class, () -> use(FontOfMagic.Direction.SLOT_TO_POINTS, 1, f.dmUserId));
            sorin.setResourcePoolsCurrent(new ArrayList<>());
            assertThrows(ConflictException.class, () -> use(FontOfMagic.Direction.SLOT_TO_POINTS, 1, f.dmUserId));
        }
    }
}
