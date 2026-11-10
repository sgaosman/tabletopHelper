package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.AttackRollRequest;
import com.tabletophelper.encounter.dto.CastSpellRequest;
import com.tabletophelper.encounter.dto.RepeatSaveRequest;
import com.tabletophelper.encounter.dto.RepeatSpellEffectRequest;
import com.tabletophelper.encounter.dto.SpellOption;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.RepeatSave;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.reference.SpellTemplate;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.combat.OngoingModifier;
import com.tabletophelper.rules.combat.OngoingModifiers;
import com.tabletophelper.rules.combat.Resolver;
import com.tabletophelper.rules.combat.RollModifiers;
import com.tabletophelper.shared.JacksonDefaults;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.tabletophelper.encounter.internal.CombatFixture.versatileWeapon;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * The weapon riders with a follow-up, the weapon-attack cantrips and the spells of several or split rolls (D199), with
 * their templates as the game data ships them. The DM acts for Aria, a level 5 paladin and sorcerer (spell save DC 13,
 * spell attack +5, CHA 16), who has the turn, against an ogre (AC 11, 60 HP) and a goblin (AC 11, 20 HP), whose
 * abilities are all 10.
 */
class RiderAndMissileSpellsTest {

    private static final String DEFINITIONS = "/data/spell-effects/spell-effect-definitions.json";

    private CombatFixture f;
    private EncounterParticipant aria;
    private EncounterParticipant ogre;
    private EncounterParticipant goblin;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        aria = f.player("Aria", 40, 15);
        aria.getCharacter().setLevel(5);
        aria.getCharacter().setCharisma(16);
        aria.setSpellSaveDc(13);
        aria.setSpellAttackBonus(5);
        aria.setSpellcastingAbility("CHA");
        ogre = f.monster("Ogre", 60, 11);
        goblin = f.monster("Goblin", 20, 11);
        for (JsonNode definition : DEFINITIONS_READ) {
            if (SPELLS.contains(definition.path("spellName").asString())) {
                f.spell(definition.path("spellName").asString(), definition.path("spellLevel").asInt(),
                        definition.toString());
            }
        }
    }

    // The spells these tests cast, and Shield, which keeps Magic Missile off
    private static final List<String> SPELLS = List.of("Searing Smite", "Thunderous Smite", "Wrathful Smite",
            "Blinding Smite", "Ensnaring Strike", "Hail of Thorns", "Lightning Arrow", "Booming Blade",
            "Green-Flame Blade", "Magic Missile", "Jim's Magic Missile", "Chaos Bolt", "Ice Knife", "Life Transference",
            "Wither and Bloom", "Thunder Step", "Pulse Wave", "Antagonize", "Shield");

    private static final JsonNode DEFINITIONS_READ = read();

    private static JsonNode read() {
        try (InputStream in = RiderAndMissileSpellsTest.class.getResourceAsStream(DEFINITIONS)) {
            return JacksonDefaults.mapper().readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void cast(String spell, int slotLevel, List<EncounterParticipant> targets) {
        f.spellCaster.cast(f.encounter, aria, spell, slotLevel, targets, "CHA", 5, 13, null, "",
                MetamagicCasting.Plan.NONE, null, null);
    }

    // Aria's attack entered by hand, +5 with 1d8 of the type, from within 5 feet or from range
    private void attack(EncounterParticipant target, boolean withinFiveFeet, int... rolls) {
        AttackRollRequest request = new AttackRollRequest();
        request.setTargetId(target.getId());
        request.setAttackBonus(5);
        request.setDamageDice("1d8");
        request.setDamageType(withinFiveFeet ? "slashing" : "piercing");
        request.setWithin5Ft(withinFiveFeet);
        f.dice.script(rolls);
        f.attacks.rollAttack(f.encounterId(), request, aria.getId(), f.dmUserId);
        CombatFixture.newTurn(aria);
    }

    private void nextTurn(int... rolls) {
        f.dice.script(rolls);
        f.turns.advanceTurn(f.encounterId(), f.dmUserId);
    }

    private boolean logged(String text) {
        return f.descriptions().stream().anyMatch(line -> line != null && line.contains(text));
    }

    private static ConditionEntry condition(EncounterParticipant creature, String name) {
        return creature.getActiveConditions().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    private SpellEffectEntry effect(String spell) {
        return EffectApplier.effects(aria).stream().filter(e -> e.spell().equals(spell)).findFirst().orElseThrow();
    }

    private boolean hasEffect(String spell) {
        return EffectApplier.effects(aria).stream().anyMatch(e -> e.spell().equals(spell));
    }

    @Nested
    @DisplayName("Templates")
    class Templates {

        @Test
        @DisplayName("The new keys read back, and a bad one makes the template unreadable")
        void keys() {
            SpellTemplate template = SpellTemplate.parse("""
                    {"followUpSave": {"ability": "DEX", "on": "ATTACK", "damageDice": "2d8", "damageType": "lightning",
                                      "halfOnSave": true, "includesTarget": false, "alsoPicked": true,
                                      "advantageSizes": ["Large"], "untilSpellEnds": true,
                                      "cantripScaling": {"5": "1d8 + MOD"}},
                     "primaryTargets": 1, "darts": {"count": 3, "perSlotLevel": 1},
                     "backfireOnOne": {"damage": 1, "damageType": "force"}, "selfDamageHeals": 2,
                     "typeDice": {"count": 2, "sides": 2, "types": ["acid", "cold"], "leapsOnDouble": true},
                     "weaponAttack": true, "blockedByShield": true,
                     "actionSave": {"condition": "frightened", "ability": "WIS", "check": true},
                     "effectTriggers": [{"when": ["START_OF_TURN"], "deliveryMethod": "AUTO_HIT", "damageDice": "1d6",
                                         "onConditioned": "Restrained"}],
                     "repeatEffect": {"deliveryMethod": "AUTO_HIT", "damageDice": "1d8",
                                      "cantripScaling": {"5": "2d8"}}}""");
            assertThat(template.unreadable()).isNull();
            SpellTemplate.FollowUpSave save = template.followUpSave();
            assertThat(save.onAttack()).isTrue();
            assertThat(save.onHit()).isTrue();
            assertThat(save.includesTarget()).isFalse();
            assertThat(save.alsoPicked()).isTrue();
            assertThat(save.untilSpellEnds()).isTrue();
            assertThat(save.advantageSizes()).containsExactly("large");
            assertThat(save.cantripScaling()).containsEntry("5", "1d8 + MOD");
            assertThat(template.primaryTargets()).isEqualTo(1);
            assertThat(template.darts().at(1, 3)).isEqualTo(5);
            assertThat(template.backfireOnOne()).isEqualTo(new SpellTemplate.Backfire(1, "force"));
            assertThat(template.selfDamageHeals()).isEqualTo(2);
            assertThat(template.typeDice().types()).containsExactly("acid", "cold");
            assertThat(template.weaponAttack()).isTrue();
            assertThat(template.blockedByShield()).isTrue();
            assertThat(template.actionSave().check()).isTrue();
            assertThat(template.effectTriggers().getFirst().onConditioned()).isEqualTo("restrained");
            assertThat(template.repeatEffect().cantripScaling()).containsEntry("5", "2d8");

            assertThat(SpellTemplate.parse("{\"followUpSave\": {\"on\": \"HIT\"}}").unreadable()).isNotNull();
            assertThat(SpellTemplate.parse("{\"followUpSave\": {\"damageDice\": \"lots\"}}").unreadable()).isNotNull();
            assertThat(SpellTemplate.parse("{\"darts\": {\"count\": 0}}").unreadable()).isNotNull();
            assertThat(SpellTemplate.parse("{\"typeDice\": {\"count\": 2, \"sides\": 8, \"types\": [\"acid\"]}}")
                    .unreadable()).isNotNull();
            assertThat(SpellTemplate.parse("{\"selfDamageHeals\": 0}").unreadable()).isNotNull();
            assertThat(SpellTemplate.parse("{\"primaryTargets\": -1}").unreadable()).isNotNull();
        }

        @Test
        @DisplayName("Every one of the 18 spells is resolved by the app now, none left to the DM by hand")
        void noneManual() {
            for (String spell : List.of("Searing Smite", "Thunderous Smite", "Wrathful Smite", "Blinding Smite",
                    "Ensnaring Strike", "Hail of Thorns", "Lightning Arrow", "Booming Blade", "Green-Flame Blade",
                    "Magic Missile", "Jim's Magic Missile", "Chaos Bolt", "Ice Knife", "Life Transference",
                    "Wither and Bloom", "Thunder Step", "Pulse Wave", "Antagonize")) {
                SpellTemplate template = f.spellRepository.findByNameIgnoreCase(spell).orElseThrow()
                        .getEffectTemplate();
                assertThat(template.unreadable()).as(spell).isNull();
                assertThat(template.requiresManualResolution()).as(spell).isFalse();
            }
        }

        @Test
        @DisplayName("Ranged riders add only from beyond 5 feet; Lightning Arrow's is spent hit or miss; a rider "
                + "without dice marks the hit and adds nothing")
        void rangedRiders() {
            OngoingModifier hail = new OngoingModifier(UUID.randomUUID(), OngoingModifier.Kind.HIT_DAMAGE, null, null,
                    0, null, null, List.of(), true, false, true, null, false, OngoingModifiers.RANGED, List.of(),
                    null, null);
            OngoingModifier arrow = new OngoingModifier(UUID.randomUUID(), OngoingModifier.Kind.HIT_DAMAGE, null,
                    "4d8", 0, null, null, List.of("lightning"), true, false, true, null, false,
                    OngoingModifiers.REPLACES_RANGED, List.of(), null, null);
            List<OngoingModifiers.Labelled> labelled = List.of(new OngoingModifiers.Labelled("Hail of Thorns", hail));
            assertThat(OngoingModifiers.hitDamage(true, true, labelled)).isEmpty();
            assertThat(OngoingModifiers.hitDamage(true, false, labelled))
                    .extracting(RollModifiers.HitDamage::dice).containsExactly(OngoingModifiers.NO_DAMAGE);
            assertThat(OngoingModifiers.spentOnAttack(hail, true, false)).isFalse();
            assertThat(OngoingModifiers.spentOnAttack(arrow, true, false)).isTrue();
            assertThat(OngoingModifiers.spentOnAttack(arrow, true, true)).isFalse();
            assertThat(hail.describe()).isEqualTo("takes effect on its next ranged weapon hit");
            assertThat(arrow.describe()).startsWith("+4d8 lightning damage in place of the weapon's");
        }

        @Test
        @DisplayName("Lightning Arrow's hit keeps only the riders' damage, the weapon's own given way")
        void ridersOnly() {
            List<RollModifiers.RolledDamage> riders = List.of(
                    new RollModifiers.RolledDamage("Lightning Arrow", Dice.parse("4d8"), 1, "lightning", 18),
                    new RollModifiers.RolledDamage("Hunter's Mark", Dice.parse("1d6"), 1, "piercing", 4));
            assertThat(EffectApplier.ridersOnly(riders)).containsExactly(new Resolver.Damage("lightning", 18),
                    new Resolver.Damage("piercing", 4));
        }
    }

    @Nested
    @DisplayName("Smites and other riders (PHB)")
    class Riders {

        @Test
        @DisplayName("Wrathful Smite: 1d6 psychic on the hit, a failed WIS save frightens until the spell ends, and "
                + "a WIS check with the creature's action ends it (PHB p.289)")
        void wrathful() {
            cast("Wrathful Smite", 1, List.of());
            assertThat(aria.getConcentration()).isNotNull();
            // 15 + 5 hits; 1d8 4 and 1d6 3; the WIS save rolls 5 against DC 13
            attack(ogre, true, 15, 4, 3, 5);
            assertThat(ogre.getHpCurrent()).isEqualTo(53);
            ConditionEntry frightened = condition(ogre, "frightened");
            assertThat(frightened.concentrationInstanceId()).isEqualTo(aria.getConcentration().id());
            assertThat(frightened.repeatSave().madeByAction()).isTrue();
            assertThat(frightened.repeatSave().dc()).isEqualTo(13);

            // the ogre's action: a WIS check, with disadvantage for being frightened, 15 and 14 keep 14
            f.turnOf(ogre);
            CombatFixture.newTurn(ogre);
            f.dice.script(15, 14);
            f.conditionService.rollActionSave(f.encounterId(), new RepeatSaveRequest(ogre.getId(), frightened.id()),
                    f.dmUserId);
            assertThat(CombatFixture.conditionNames(ogre)).doesNotContain("frightened");
            assertThat(aria.getConcentration()).isNull();
            assertThat(logged("uses its action on a WIS check against frightened (disadvantage): d20(14)")).isTrue();
        }

        @Test
        @DisplayName("Wrathful Smite: a successful save leaves nothing, so the spell ends with the hit")
        void wrathfulSaved() {
            cast("Wrathful Smite", 1, List.of());
            attack(ogre, true, 15, 4, 3, 18);
            assertThat(CombatFixture.conditionNames(ogre)).doesNotContain("frightened");
            assertThat(aria.getConcentration()).isNull();
            assertThat(hasEffect("Wrathful Smite")).isFalse();
        }

        @Test
        @DisplayName("A smite waits for a melee weapon hit: a miss leaves it ready (PHB p.282)")
        void missWaits() {
            cast("Thunderous Smite", 1, List.of());
            attack(ogre, true, 2);
            assertThat(effect("Thunderous Smite").modifiers()).hasSize(1);
        }

        @Test
        @DisplayName("Thunderous Smite: 2d6 thunder, a failed STR save knocks the target prone, which outlasts the "
                + "spell, and the push is the DM's (PHB p.282)")
        void thunderous() {
            cast("Thunderous Smite", 1, List.of());
            attack(ogre, true, 15, 4, 6, 6, 2);
            assertThat(ogre.getHpCurrent()).isEqualTo(60 - 16);
            assertThat(CombatFixture.conditionNames(ogre)).contains("prone");
            assertThat(aria.getConcentration()).isNull();
            assertThat(logged("pushed 10 feet")).isTrue();
        }

        @Test
        @DisplayName("Searing Smite at 2nd level: 2d6 fire sets the target ablaze; it saves on CON at the start of each "
                + "of its turns, 1d6 fire on a failure, and a success ends the spell (PHB p.274)")
        void searing() {
            cast("Searing Smite", 2, List.of());
            attack(ogre, true, 15, 4, 3, 3);
            assertThat(ogre.getHpCurrent()).isEqualTo(50);
            assertThat(CombatFixture.conditionNames(ogre)).contains("ignited");
            assertThat(effect("Searing Smite").rider().saveDc()).isEqualTo(13);

            // the ogre's turn: 1d6 fire rolls 4, its CON save 5 fails
            nextTurn(4, 5);
            assertThat(ogre.getHpCurrent()).isEqualTo(46);
            assertThat(logged("Searing Smite acts on Ogre at the start of its turn")).isTrue();
            // the goblin's and Aria's turns, then the ogre's again: the 1d6 rolls 2 and the save 18 ends the spell
            nextTurn();
            nextTurn();
            nextTurn(2, 18);
            assertThat(ogre.getHpCurrent()).isEqualTo(46);
            assertThat(CombatFixture.conditionNames(ogre)).doesNotContain("ignited");
            assertThat(aria.getConcentration()).isNull();
        }

        @Test
        @DisplayName("Blinding Smite: 3d8 radiant and a failed CON save blinds, repeating the save at the end of each "
                + "of the target's turns (PHB p.219)")
        void blinding() {
            cast("Blinding Smite", 3, List.of());
            attack(ogre, true, 15, 4, 1, 1, 1, 2);
            assertThat(ogre.getHpCurrent()).isEqualTo(60 - 7);
            ConditionEntry blinded = condition(ogre, "blinded");
            assertThat(blinded.repeatSave().dc()).isEqualTo(13);
            assertThat(blinded.repeatSave().madeByAction()).isFalse();
            assertThat(blinded.concentrationInstanceId()).isNotNull();
        }

        @Test
        @DisplayName("Ensnaring Strike: a Large target saves with advantage, the vines deal 1d6 piercing at the start "
                + "of each of its turns, and a STR check with its action frees it (PHB p.237)")
        void ensnaring() {
            ogre.getMonster().setSize("Large");
            cast("Ensnaring Strike", 2, List.of());
            // the hit deals only the 1d8; advantage on the STR save, 3 and 2 keep 3
            attack(ogre, true, 15, 4, 3, 2);
            assertThat(ogre.getHpCurrent()).isEqualTo(56);
            ConditionEntry restrained = condition(ogre, "restrained");
            assertThat(logged("advantage: Large")).isTrue();

            // the ogre's turn: 2d6 piercing from a 2nd-level slot
            nextTurn(5, 6);
            assertThat(ogre.getHpCurrent()).isEqualTo(45);

            CombatFixture.newTurn(ogre);
            f.dice.script(13);
            f.conditionService.rollActionSave(f.encounterId(), new RepeatSaveRequest(ogre.getId(), restrained.id()),
                    f.dmUserId);
            assertThat(CombatFixture.conditionNames(ogre)).doesNotContain("restrained");
            assertThat(aria.getConcentration()).isNull();
            assertThat(logged("STR check against restrained: d20(13) + 0 = 13 vs DC 13")).isTrue();
        }

        @Test
        @DisplayName("Hail of Thorns: a melee hit doesn't spend it; the next ranged hit makes the target and the "
                + "creatures picked save on DEX against 1d10 piercing, half on a success (PHB p.249)")
        void hailOfThorns() {
            cast("Hail of Thorns", 1, List.of(goblin));
            assertThat(effect("Hail of Thorns").rider().picked()).containsExactly(goblin.getId());
            attack(ogre, true, 15, 4);
            assertThat(hasEffect("Hail of Thorns")).isTrue();

            // 1d8 3; the thorns' 1d10 rolls 8; the ogre's DEX save 5 fails, the goblin's 18 halves
            attack(ogre, false, 15, 3, 8, 5, 18);
            assertThat(ogre.getHpCurrent()).isEqualTo(60 - 4 - 3 - 8);
            assertThat(goblin.getHpCurrent()).isEqualTo(20 - 4);
            assertThat(hasEffect("Hail of Thorns")).isFalse();
            assertThat(aria.getConcentration()).isNull();
        }

        @Test
        @DisplayName("Lightning Arrow: a hit deals 4d8 lightning in place of the weapon's damage, and the creatures "
                + "picked save on DEX against 2d8 lightning (PHB p.255)")
        void lightningArrowHit() {
            cast("Lightning Arrow", 3, List.of(goblin));
            // the 1d8 rolls 7, the 4d8 2s, so 8 lightning and no piercing; the goblin's 2d8 rolls 3 and 3, its save 2
            attack(ogre, false, 15, 7, 2, 2, 2, 2, 3, 3, 2);
            assertThat(ogre.getHpCurrent()).isEqualTo(52);
            assertThat(goblin.getHpCurrent()).isEqualTo(14);
            assertThat(hasEffect("Lightning Arrow")).isFalse();
        }

        @Test
        @DisplayName("Lightning Arrow: a miss deals half the 4d8 to the target, and the burst still strikes the "
                + "others (PHB p.255)")
        void lightningArrowMiss() {
            cast("Lightning Arrow", 4, List.of(goblin));
            // 2 misses; the 5d8 rolls 4s, 20 halved to 10; the goblin's 3d8 rolls 2s, its save 18 halves 6 to 3
            attack(ogre, false, 2, 4, 4, 4, 4, 4, 2, 2, 2, 18);
            assertThat(ogre.getHpCurrent()).isEqualTo(50);
            assertThat(goblin.getHpCurrent()).isEqualTo(17);
            assertThat(hasEffect("Lightning Arrow")).isFalse();
        }
    }

    @Nested
    @DisplayName("Weapon-attack cantrips (TCE)")
    class Blades {

        @BeforeEach
        void longsword() {
            // Aria's sheet casts with Charisma, +3
            lenient().when(f.characterService.mainSpellcastingAbility(any())).thenReturn("CHA");
            f.weapons(versatileWeapon("Longsword", "martial", "1d8", "1d10", "slashing"));
            CombatFixture.carries(aria, List.of("martial"), "Longsword");
            for (String spell : List.of("Booming Blade", "Green-Flame Blade")) {
                f.knows(aria, new KnownSpell(null, spell, 0, "class:Sorcerer", true, null, null, null, null));
            }
        }

        private void castBlade(String spell, List<EncounterParticipant> targets, int... rolls) {
            CastSpellRequest request = new CastSpellRequest();
            request.setSpellName(spell);
            request.setSlotLevel(0);
            request.setTargetIds(targets.stream().map(EncounterParticipant::getId).toList());
            request.setOption("Longsword");
            f.dice.script(rolls);
            f.spellcasting.castSpell(f.encounterId(), request, aria.getId(), f.dmUserId);
        }

        @Test
        @DisplayName("Green-Flame Blade at 5th level: the longsword attack adds 1d8 fire, and 1d8 + CHA fire leaps to "
                + "the second creature (TCE p.107)")
        void greenFlame() {
            // 15 hits; the longsword's 1d8 4 and the fire 1d8 5; the leap's 1d8 2 + 3
            castBlade("Green-Flame Blade", List.of(ogre, goblin), 15, 4, 5, 2);
            assertThat(ogre.getHpCurrent()).isEqualTo(60 - 9);
            assertThat(goblin.getHpCurrent()).isEqualTo(20 - 5);
            assertThat(logged("Longsword (Green-Flame Blade)")).isTrue();
        }

        @Test
        @DisplayName("Booming Blade at 5th level: 1d8 thunder on the hit, then the DM uses its effect when the target "
                + "moves, 2d8 thunder, and the spell ends (TCE p.106)")
        void booming() {
            castBlade("Booming Blade", List.of(ogre), 15, 4, 3);
            assertThat(ogre.getHpCurrent()).isEqualTo(60 - 7);
            SpellEffectEntry sheath = effect("Booming Blade");
            assertThat(sheath.affected()).containsExactly(ogre.getId());
            assertThat(sheath.durationRounds()).isEqualTo(1);

            RepeatSpellEffectRequest use = new RepeatSpellEffectRequest();
            use.setEffectId(sheath.id());
            use.setTargetIds(List.of(ogre.getId()));
            f.dice.script(5, 6);
            f.spellcasting.repeatSpellEffect(f.encounterId(), use, aria.getId(), f.dmUserId);
            assertThat(ogre.getHpCurrent()).isEqualTo(53 - 11);
            assertThat(hasEffect("Booming Blade")).isFalse();
        }

        @Test
        @DisplayName("Booming Blade's miss sheathes nothing")
        void boomingMiss() {
            castBlade("Booming Blade", List.of(ogre), 2);
            assertThat(hasEffect("Booming Blade")).isFalse();
            assertThat(ogre.getHpCurrent()).isEqualTo(60);
        }
    }

    @Nested
    @DisplayName("Several or split rolls")
    class Rolls {

        @Test
        @DisplayName("Magic Missile at 2nd level: four darts go round the two targets, each rolling its own 1d4 + 1 "
                + "(PHB p.257)")
        void magicMissile() {
            f.dice.script(1, 2, 3, 4);
            cast("Magic Missile", 2, List.of(ogre, goblin));
            assertThat(ogre.getHpCurrent()).isEqualTo(60 - 2 - 4);
            assertThat(goblin.getHpCurrent()).isEqualTo(20 - 3 - 5);
            assertThat(logged("4 darts, at Ogre, Goblin, Ogre, Goblin")).isTrue();
        }

        @Test
        @DisplayName("Magic Missile: each dart is its own damage, so a concentrating target checks three times "
                + "(PHB p.203)")
        void eachDartChecks() {
            f.effectApplier.startConcentration(f.encounter, ogre, "Bless", 1, 10);
            // the darts roll first, each 1 + 1, then a CON check of 10 against DC 10 for each
            f.dice.script(1, 1, 1, 10, 10, 10);
            cast("Magic Missile", 1, List.of(ogre));
            assertThat(ogre.getHpCurrent()).isEqualTo(54);
            assertThat(ogre.getConcentration()).isNotNull();
            assertThat(f.dice.remaining()).isZero();
        }

        @Test
        @DisplayName("Magic Missile: a creature under Shield takes none of its darts (PHB p.275)")
        void shieldBlocks() {
            f.spellCaster.cast(f.encounter, goblin, "Shield", 1, List.of(), null, 0, 10, null, "",
                    MetamagicCasting.Plan.NONE, null, null);
            f.dice.script(4, 4);
            cast("Magic Missile", 1, List.of(goblin, ogre, goblin));
            assertThat(goblin.getHpCurrent()).isEqualTo(20);
            assertThat(ogre.getHpCurrent()).isEqualTo(55);
            assertThat(logged("Goblin's Shield blocks a dart of Magic Missile")).isTrue();
        }

        @Test
        @DisplayName("Jim's Magic Missile: one dart's natural 1 makes every dart miss, each dealing 1 force to its "
                + "caster (AcqInc p.75)")
        void jimsBackfire() {
            f.dice.script(15, 2, 2, 1, 15, 2, 2);
            cast("Jim's Magic Missile", 1, List.of(ogre));
            assertThat(ogre.getHpCurrent()).isEqualTo(60);
            assertThat(aria.getHpCurrent()).isEqualTo(37);
        }

        @Test
        @DisplayName("Jim's Magic Missile: a dart's critical hit deals 5d4 instead of doubled dice (AcqInc p.75)")
        void jimsCritical() {
            f.dice.script(20, 1, 1, 1, 1, 1, 15, 2, 2, 3);
            cast("Jim's Magic Missile", 1, List.of(ogre));
            // the 5d4 critical 5, the second dart 4, the third misses AC 11 with 3 + 5
            assertThat(ogre.getHpCurrent()).isEqualTo(60 - 5 - 4);
        }

        @Test
        @DisplayName("Chaos Bolt: the first d8 gives the type, and a double leaps to the creature listed next, with "
                + "a new attack and damage roll (XGE p.151)")
        void chaosBolt() {
            // the ogre: 15 hits, d8s 2 and 2 (cold) and the d6 3; the goblin: 15 hits, d8s 1 and 6 (acid), d6 2
            f.dice.script(15, 2, 2, 3, 15, 1, 6, 2);
            cast("Chaos Bolt", 1, List.of(ogre, goblin));
            assertThat(ogre.getHpCurrent()).isEqualTo(60 - 7);
            assertThat(goblin.getHpCurrent()).isEqualTo(20 - 9);
            assertThat(logged("d8s show 2 and 2: cold damage")).isTrue();
            assertThat(logged("Chaos Bolt leaps from Ogre to Goblin")).isTrue();
            assertThat(logged("d8s show 1 and 6: acid damage")).isTrue();
        }

        @Test
        @DisplayName("Ice Knife: 1d10 piercing on the hit, then hit or miss the target and the creatures listed after it "
                + "save on DEX or take 2d6 cold, none on a success (XGE p.157)")
        void iceKnife() {
            // 15 hits, 1d10 6; the burst's 2d6 3 and 3; the ogre's save 5 fails, the goblin's 18 succeeds
            f.dice.script(15, 6, 3, 3, 5, 18);
            cast("Ice Knife", 1, List.of(ogre, goblin));
            assertThat(ogre.getHpCurrent()).isEqualTo(60 - 12);
            assertThat(goblin.getHpCurrent()).isEqualTo(20);

            // a miss still bursts: 2d6 2 and 2 at 2nd level plus 1d6 2, the ogre's save 5 fails
            f.dice.script(2, 2, 2, 2, 5);
            cast("Ice Knife", 2, List.of(ogre));
            assertThat(ogre.getHpCurrent()).isEqualTo(48 - 6);
        }

        @Test
        @DisplayName("Life Transference at 3rd level: the caster takes 4d8 necrotic, which can't be reduced, and the "
                + "ally regains twice that (XGE p.160)")
        void lifeTransference() {
            EncounterParticipant bran = f.player("Bran", 40, 12);
            bran.setHpCurrent(5);
            f.dice.script(2, 3, 4, 5);
            cast("Life Transference", 3, List.of(bran));
            assertThat(aria.getHpCurrent()).isEqualTo(40 - 14);
            assertThat(bran.getHpCurrent()).isEqualTo(33);
        }

        @Test
        @DisplayName("Wither and Bloom, Thunder Step, Pulse Wave and Antagonize resolve their saves and log the rest")
        void saveSpells() {
            // Thunder Step: 3d10 of 5s = 15, the ogre's CON save 5 fails
            f.dice.script(5, 5, 5, 5);
            cast("Thunder Step", 3, List.of(ogre));
            assertThat(ogre.getHpCurrent()).isEqualTo(45);
            assertThat(logged("teleports")).isTrue();
            // Antagonize: 4d4 of 2s = 8, the goblin's WIS save 18 halves it
            f.dice.script(2, 2, 2, 2, 18);
            cast("Antagonize", 3, List.of(goblin));
            assertThat(goblin.getHpCurrent()).isEqualTo(16);
            assertThat(logged("reaction")).isTrue();
        }
    }

    @Nested
    @DisplayName("What each listed target is, for the cast screen (D200)")
    class TargetRoles {

        @Test
        @DisplayName("The spell options give Ice Knife's main target and 5-foot picks, Hail of Thorns' picks only, "
                + "Magic Missile's darts at each level, and nothing for a spell whose targets are all its own")
        void spellOptions() {
            for (String spell : List.of("Ice Knife", "Hail of Thorns", "Magic Missile", "Thunder Step")) {
                f.knows(aria, spell);
            }
            aria.setSpellSlotsCurrent(Map.of("1", new SpellSlot(4, 0), "3", new SpellSlot(2, 0)));

            Map<String, SpellOption.TargetRoles> roles = new HashMap<>();
            f.spellcasting.spellOptions(f.encounterId(), aria.getId(), f.dmUserId)
                    .forEach(option -> roles.put(option.name(), option.targetRoles()));

            assertThat(roles.get("Ice Knife")).isEqualTo(new SpellOption.TargetRoles(1, 5, null));
            assertThat(roles.get("Hail of Thorns")).isEqualTo(new SpellOption.TargetRoles(0, 5, null));
            assertThat(roles.get("Magic Missile").primaryTargets()).isNull();
            assertThat(roles.get("Magic Missile").darts()).containsEntry("1", 3).containsEntry("3", 5)
                    .containsEntry("9", 11).hasSize(9);
            assertThat(roles).containsEntry("Thunder Step", null);
        }

        @Test
        @DisplayName("pickedWithin reads a whole number of feet and refuses anything else")
        void pickedWithin() {
            assertThat(SpellTemplate.parse("{\"primaryTargets\": 1, \"pickedWithin\": 30}").pickedWithin())
                    .isEqualTo(30);
            assertThat(SpellTemplate.parse("{\"primaryTargets\": 1}").pickedWithin()).isNull();
            assertThat(SpellTemplate.parse("{\"pickedWithin\": 0}").unreadable()).isNotNull();
            assertThat(SpellTemplate.parse("{\"pickedWithin\": \"near\"}").unreadable()).isNotNull();
        }

        @Test
        @DisplayName("A Repeat Save marked as a check keeps its numbers, and one stored before the marks reads without "
                + "them")
        void repeatSaveCheck() throws Exception {
            RepeatSave save = new RepeatSave(Ability.INT, 14, false, true);
            RepeatSave check = save.asCheck("Investigation");
            assertThat(check.ability()).isEqualTo(Ability.INT);
            assertThat(check.dc()).isEqualTo(14);
            assertThat(check.madeByAction()).isTrue();
            assertThat(check.check()).isTrue();
            assertThat(check.skill()).isEqualTo("Investigation");
            assertThat(check.withAwaitingRoll(true).skill()).isEqualTo("Investigation");

            String stored = JacksonDefaults.mapper().writeValueAsString(save);
            assertThat(stored).doesNotContain("check").doesNotContain("skill");
            RepeatSave read = JacksonDefaults.mapper().readValue(stored, RepeatSave.class);
            assertThat(read).isEqualTo(save);
            assertThat(read.check()).isNull();
        }
    }
}
