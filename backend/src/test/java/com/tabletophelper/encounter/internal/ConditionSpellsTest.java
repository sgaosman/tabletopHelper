package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.AttackRollRequest;
import com.tabletophelper.encounter.dto.RepeatSpellEffectRequest;
import com.tabletophelper.encounter.dto.UsePoolRequest;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.EndRule;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.reference.SpellTemplate;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.rules.combat.OngoingModifier;
import com.tabletophelper.rules.combat.RollMode;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.JacksonDefaults;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The summoning, condition and hit-point pool spells of D199, cast with their templates as the game data ships them: a
 * wizard (spell save DC 15, spell attack +7) against monsters whose abilities are all 10.
 */
class ConditionSpellsTest {

    private CombatFixture f;
    private EncounterParticipant wizard;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        wizard = f.player("Wizard", 30, 12);
        wizard.setSpellSaveDc(15);
        wizard.setSpellAttackBonus(7);
        wizard.setSpellcastingAbility("INT");
        f.effectApplier.setSpellRepository(f.spellRepository);
    }

    // The spell with its shipped template
    private void shipped(String name) {
        try (InputStream in = getClass().getResourceAsStream("/data/spell-effects/spell-effect-definitions.json")) {
            for (JsonNode definition : JacksonDefaults.mapper().readTree(in)) {
                if (name.equals(definition.path("spellName").asString(null))) {
                    f.spell(name, definition.path("spellLevel").asInt(), definition.toString());
                    return;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        throw new AssertionError(name + " has no shipped template");
    }

    private SpellCaster.CastResult cast(String spell, int slotLevel, List<EncounterParticipant> targets) {
        return cast(spell, slotLevel, targets, null);
    }

    private SpellCaster.CastResult cast(String spell, int slotLevel, List<EncounterParticipant> targets,
                                        String option) {
        shipped(spell);
        return f.spellCaster.cast(f.encounter, wizard, spell, slotLevel, targets, "INT", 7, 15, null, "",
                MetamagicCasting.Plan.NONE, null, option);
    }

    private boolean logged(String text) {
        return f.log.stream().anyMatch(entry -> entry.getDescription() != null && entry.getDescription().contains(text));
    }

    private static Optional<ConditionEntry> condition(EncounterParticipant creature, String name) {
        return creature.getActiveConditions() == null ? Optional.empty() : creature.getActiveConditions().stream()
                .filter(entry -> entry.name().equalsIgnoreCase(name)).findFirst();
    }

    private Optional<SpellEffectEntry> effect(String spell) {
        return EffectApplier.effects(wizard).stream().filter(effect -> effect.spell().equals(spell)).findFirst();
    }

    private static EncounterParticipant undead(EncounterParticipant monster) {
        monster.getMonster().setType("undead");
        return monster;
    }

    @Nested
    @DisplayName("Hit-point pools (Sleep, Color Spray)")
    class HitPointPools {

        @Test
        @DisplayName("The pool reaches creatures lowest first while their hit points fit what is left, passing over "
                + "those at 0 (PHB p.276)")
        void poolReach() {
            EncounterParticipant seven = f.monster("Seven", 7, 10);
            EncounterParticipant five = f.monster("Five", 5, 10);
            EncounterParticipant twenty = f.monster("Twenty", 20, 10);
            EncounterParticipant down = f.monster("Down", 3, 10);
            down.setHpCurrent(0);
            assertThat(SpellCaster.poolReach(15, List.of(seven, five, twenty, down))).containsExactly(five, seven);
            assertThat(SpellCaster.poolReach(4, List.of(seven, five))).isEmpty();
            assertThat(SpellCaster.poolReach(12, List.of(seven, five))).containsExactly(five, seven);
        }

        @Test
        @DisplayName("Sleep: 5d8 puts the goblins to sleep lowest first, ends on damage, and leaves an undead and the "
                + "ogre beyond the pool awake")
        void sleep() {
            EncounterParticipant big = f.monster("Goblin Boss", 7, 13);
            EncounterParticipant small = f.monster("Goblin", 5, 13);
            EncounterParticipant ogre = f.monster("Ogre", 20, 11);
            EncounterParticipant skeleton = undead(f.monster("Skeleton", 3, 13));
            // 5d8 of 3s = 15: the goblin (5), then the boss (7), 3 left, short of the ogre's 20
            f.dice.script(3, 3, 3, 3, 3);
            cast("Sleep", 1, List.of(big, small, ogre, skeleton));

            assertThat(condition(small, "unconscious")).isPresent();
            assertThat(condition(big, "unconscious")).get()
                    .satisfies(entry -> assertThat(entry.endRule().type()).isEqualTo(EndRule.Type.ON_DAMAGE))
                    .satisfies(entry -> assertThat(entry.duration()).isEqualTo(10));
            assertThat(condition(ogre, "unconscious")).isEmpty();
            assertThat(condition(skeleton, "unconscious")).isEmpty();
            assertThat(logged("5d8 (3, 3, 3, 3, 3) = 15 hit points")).isTrue();
            assertThat(logged("Ogre (20 HP) is beyond it")).isTrue();
            assertThat(f.dice.remaining()).isZero();
        }

        @Test
        @DisplayName("Sleep at 2nd level rolls 7d8, and passes over a creature already unconscious")
        void sleepUpcastAndUnconscious() {
            EncounterParticipant ogre = f.monster("Ogre", 20, 11);
            EncounterParticipant sleeper = f.monster("Sleeper", 2, 10);
            sleeper.setActiveConditions(new java.util.ArrayList<>(List.of(ConditionEntry.manual("unconscious", null,
                    1))));
            // 7d8 of 3s = 21 reaches the ogre
            f.dice.script(3, 3, 3, 3, 3, 3, 3);
            cast("Sleep", 2, List.of(ogre, sleeper));
            assertThat(condition(ogre, "unconscious")).isPresent();
            assertThat(logged("7d8")).isTrue();
        }

        @Test
        @DisplayName("Color Spray blinds until the end of the caster's next turn, skipping a creature already blinded "
                + "(PHB p.222)")
        void colorSpray() {
            EncounterParticipant goblin = f.monster("Goblin", 7, 13);
            EncounterParticipant blind = f.monster("Blind Goblin", 1, 13);
            blind.setActiveConditions(new java.util.ArrayList<>(List.of(ConditionEntry.manual("blinded", null, 1))));
            // 6d10 of 2s = 12
            f.dice.script(2, 2, 2, 2, 2, 2);
            cast("Color Spray", 1, List.of(goblin, blind));
            assertThat(condition(goblin, "blinded")).get().satisfies(entry -> {
                assertThat(entry.endRule()).isEqualTo(EndRule.endOfTurn(wizard.getId()));
                assertThat(entry.duration()).isEqualTo(1);
            });
            assertThat(blind.getActiveConditions()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Options and conditions")
    class OptionsAndConditions {

        @Test
        @DisplayName("Command: Grovel leaves a failed save prone with no end of its own; Halt gives no condition; an "
                + "undead is unaffected (PHB p.223)")
        void command() {
            EncounterParticipant goblin = f.monster("Goblin", 7, 13);
            f.dice.script(2);
            cast("Command", 1, List.of(goblin), "grovel");
            assertThat(condition(goblin, "prone")).get()
                    .satisfies(entry -> assertThat(entry.duration()).isNull());

            EncounterParticipant orc = f.monster("Orc", 15, 13);
            f.dice.script(2);
            cast("Command", 1, List.of(orc), "halt");
            assertThat(orc.getActiveConditions() == null || orc.getActiveConditions().isEmpty()).isTrue();

            // no save is rolled for the undead: the dice have nothing scripted
            EncounterParticipant zombie = undead(f.monster("Zombie", 22, 8));
            cast("Command", 1, List.of(zombie), "grovel");
            assertThat(condition(zombie, "prone")).isEmpty();
        }

        @Test
        @DisplayName("Pyrotechnics: Fireworks blinds a failed CON save until the end of the caster's next turn; Smoke "
                + "rolls no save (XGE p.163)")
        void pyrotechnics() {
            EncounterParticipant goblin = f.monster("Goblin", 7, 13);
            f.dice.script(2);
            cast("Pyrotechnics", 2, List.of(goblin), "fireworks");
            assertThat(condition(goblin, "blinded")).get()
                    .satisfies(entry -> assertThat(entry.endRule().type()).isEqualTo(EndRule.Type.END_OF_TURN));

            EncounterParticipant orc = f.monster("Orc", 15, 13);
            cast("Pyrotechnics", 2, List.of(orc), "smoke");
            assertThat(condition(orc, "blinded")).isEmpty();
        }

        @Test
        @DisplayName("Bestow Curse: concentration for a minute at 3rd, none and 8 hours at 5th, until dispelled at "
                + "9th; the ability's curse gives disadvantage on its saves (PHB p.218)")
        void bestowCurse() {
            EncounterParticipant ogre = f.monster("Ogre", 59, 11);
            f.dice.script(2);
            cast("Bestow Curse", 3, List.of(ogre), "strength");
            assertThat(wizard.getConcentration()).isNotNull();
            assertThat(effect("Bestow Curse")).get().satisfies(curse -> {
                assertThat(curse.durationRounds()).isEqualTo(10);
                assertThat(curse.modifiers()).singleElement().satisfies(modifier -> {
                    assertThat(modifier.kind()).isEqualTo(OngoingModifier.Kind.SAVE_MODE);
                    assertThat(modifier.mode()).isEqualTo(RollMode.DISADVANTAGE);
                    assertThat(modifier.ability()).isEqualTo("STR");
                });
            });

            wizard.setConcentration(null);
            wizard.setActiveEffects(null);
            f.dice.script(2);
            cast("Bestow Curse", 5, List.of(ogre), "necrotic");
            assertThat(wizard.getConcentration()).isNull();
            assertThat(effect("Bestow Curse")).get().satisfies(curse -> {
                assertThat(curse.durationRounds()).isEqualTo(4800);
                assertThat(curse.modifiers()).singleElement().satisfies(modifier -> {
                    assertThat(modifier.kind()).isEqualTo(OngoingModifier.Kind.HIT_DAMAGE);
                    assertThat(modifier.scope()).isEqualTo(OngoingModifier.Scope.CASTER_VS_AFFECTED);
                });
            });

            wizard.setActiveEffects(null);
            f.dice.script(2);
            cast("Bestow Curse", 9, List.of(ogre), "wasted action");
            assertThat(effect("Bestow Curse")).get().satisfies(curse -> {
                assertThat(curse.durationRounds()).isNull();
                assertThat(curse.option()).isEqualTo("wasted action");
                assertThat(curse.affected()).containsExactly(ogre.getId());
            });
        }

        @Test
        @DisplayName("Enemies Abound: a creature immune to being frightened succeeds; another fails into a condition "
                + "that its damage lets it save against (XGE p.155)")
        void enemiesAbound() {
            EncounterParticipant ogre = f.monster("Ogre", 59, 11);
            f.dice.script(2);
            cast("Enemies Abound", 3, List.of(ogre));
            assertThat(condition(ogre, "enemies abound")).get()
                    .satisfies(entry -> assertThat(entry.saveOnDamage()).isNotNull());
        }

        @Test
        @DisplayName("Crown of Madness targets only a humanoid, and reminds its caster each turn (PHB p.229)")
        void crownOfMadness() {
            shipped("Crown of Madness");
            EncounterParticipant zombie = undead(f.monster("Zombie", 22, 8));
            assertThatThrownBy(() -> f.spellCaster.requireTargetTypes("Crown of Madness", List.of(zombie)))
                    .hasMessageContaining("humanoid");
            EncounterParticipant thug = f.monster("Thug", 32, 11);
            thug.getMonster().setType("humanoid");
            f.dice.script(2);
            cast("Crown of Madness", 2, List.of(thug));
            assertThat(condition(thug, "charmed")).get()
                    .satisfies(entry -> assertThat(entry.repeatSave()).isNotNull());
            assertThat(effect("Crown of Madness")).get()
                    .satisfies(crown -> assertThat(crown.turnReminder()).contains("use your action"));
        }

        @Test
        @DisplayName("Crusader's Mantle gives each creature chosen 1d4 radiant on its weapon hits (PHB p.230)")
        void crusadersMantle() {
            EncounterParticipant ally = f.player("Fighter", 40, 18);
            cast("Crusader's Mantle", 3, List.of(wizard, ally));
            assertThat(effect("Crusader's Mantle")).get().satisfies(mantle -> {
                assertThat(mantle.affected()).containsExactlyInAnyOrder(wizard.getId(), ally.getId());
                assertThat(mantle.modifiers()).singleElement().satisfies(modifier -> {
                    assertThat(modifier.kind()).isEqualTo(OngoingModifier.Kind.HIT_DAMAGE);
                    assertThat(modifier.weaponOnly()).isTrue();
                    assertThat(modifier.damageTypes()).containsExactly("radiant");
                });
            });
        }

        @Test
        @DisplayName("Armor of Agathys at 2nd: 10 temporary hit points; a melee hit takes 10 cold back while they "
                + "last, and the spell ends when they are gone (PHB p.215)")
        void armorOfAgathys() {
            EncounterParticipant ogre = f.monster("Ogre", 59, 11);
            cast("Armor of Agathys", 2, List.of());
            assertThat(wizard.getHpTemp()).isEqualTo(10);

            // 15 + 4 hits AC 12; 1d6 rolls 6: the temporary hit points take it, 4 left; the cold is 10
            f.turnOf(ogre);
            melee(ogre, 15, 6);
            assertThat(wizard.getHpTemp()).isEqualTo(4);
            assertThat(ogre.getHpCurrent()).isEqualTo(59 - 10);

            // the hit that takes the last of them still strikes back, and the spell ends
            CombatFixture.newTurn(ogre);
            melee(ogre, 15, 6);
            assertThat(wizard.getHpTemp()).isZero();
            assertThat(ogre.getHpCurrent()).isEqualTo(59 - 20);
            assertThat(effect("Armor of Agathys")).isEmpty();

            CombatFixture.newTurn(ogre);
            melee(ogre, 15, 6);
            assertThat(ogre.getHpCurrent()).isEqualTo(59 - 20);
        }

        private void melee(EncounterParticipant attacker, int... rolls) {
            AttackRollRequest request = new AttackRollRequest();
            request.setTargetId(wizard.getId());
            request.setAttackBonus(4);
            request.setDamageDice("1d6");
            request.setDamageType("slashing");
            request.setWithin5Ft(true);
            f.dice.script(rolls);
            f.attacks.rollAttack(f.encounterId(), request, attacker.getId(), f.dmUserId);
        }

        @Test
        @DisplayName("Gaseous Form: resistance to nonmagical damage, advantage on STR, DEX and CON saves, and no "
                + "attacks or spells (PHB p.242)")
        void gaseousForm() {
            EncounterParticipant ally = f.player("Rogue", 30, 14);
            cast("Gaseous Form", 3, List.of(ally));
            assertThat(ActiveModifiers.barredFromAttacking(ally)).isEqualTo("Gaseous Form");
            assertThat(ActiveModifiers.modifiersOn(ally)).extracting(OngoingModifier::kind)
                    .contains(OngoingModifier.Kind.RESISTANCE, OngoingModifier.Kind.SAVE_MODE);
            assertThatThrownBy(() -> f.spellCaster.requireCanCast(ally)).isInstanceOf(ConflictException.class)
                    .hasMessageContaining("Gaseous Form");
            EncounterParticipant goblin = f.monster("Goblin", 7, 13);
            AttackRollRequest request = new AttackRollRequest();
            request.setTargetId(goblin.getId());
            request.setAttackBonus(4);
            request.setDamageDice("1d6");
            request.setDamageType("piercing");
            f.turnOf(ally);
            assertThatThrownBy(() -> f.attacks.rollAttack(f.encounterId(), request, ally.getId(), f.dmUserId))
                    .isInstanceOf(ConflictException.class).hasMessageContaining("can't attack");
        }

        @Test
        @DisplayName("Dragon's Breath: the touched creature exhales with its own action, 4d6 fire at 3rd with the "
                + "caster's DC, half on a DEX save (XGE p.154)")
        void dragonsBreath() {
            EncounterParticipant ally = f.player("Fighter", 40, 18);
            EncounterParticipant goblin = f.monster("Goblin", 30, 13);
            EncounterParticipant orc = f.monster("Orc", 30, 13);
            cast("Dragon's Breath", 3, List.of(ally), "fire");
            assertThat(effect("Dragon's Breath")).get().satisfies(breath -> {
                assertThat(breath.option()).isEqualTo("fire");
                assertThat(breath.affected()).containsExactly(ally.getId());
            });

            f.turnOf(ally);
            RepeatSpellEffectRequest request = new RepeatSpellEffectRequest();
            request.setTargetIds(List.of(goblin.getId(), orc.getId()));
            // 4d6 of 3s = 12 fire; the goblin fails (2), the orc succeeds (19) for half
            f.dice.script(3, 3, 3, 3, 2, 19);
            f.spellcasting.repeatSpellEffect(f.encounterId(), request, ally.getId(), f.dmUserId);
            assertThat(goblin.getHpCurrent()).isEqualTo(30 - 12);
            assertThat(orc.getHpCurrent()).isEqualTo(30 - 6);
            assertThat(logged("fire")).isTrue();
            assertThat(ally.getTurnEconomy().actionsUsed()).isEqualTo(1);
        }

        @Test
        @DisplayName("Alter Self's Natural Weapons give a 1d6 natural weapon of the type chosen, +1 to attack and "
                + "damage (PHB p.211)")
        void alterSelf() {
            cast("Alter Self", 2, List.of(), "natural weapons (slashing)");
            assertThat(AlterSelf.held(wizard)).get().satisfies(held -> {
                assertThat(held.weapon().damageType()).isEqualTo("slashing");
                assertThat(held.proficient()).isTrue();
            });
            assertThat(AlterSelf.bonus(wizard, "natural weapon")).isEqualTo(1);
            assertThat(AlterSelf.damageType("aquatic adaptation")).isEmpty();
            assertThat(AlterSelf.damageType("Natural Weapons (piercing)")).contains("piercing");
        }
    }

    @Nested
    @DisplayName("Revivify and Goodberry")
    class Healing {

        @Test
        @DisplayName("Revivify returns a dead creature with 1 hit point and refuses a living one (PHB p.272)")
        void revivify() {
            EncounterParticipant ally = f.player("Fighter", 40, 18);
            assertThatThrownBy(() -> cast("Revivify", 3, List.of(ally))).isInstanceOf(ConflictException.class)
                    .hasMessageContaining("isn't dead");
            CombatFixture.dead(ally);
            cast("Revivify", 3, List.of(ally));
            assertThat(ally.getLifeState()).isEqualTo(LifeState.ALIVE);
            assertThat(ally.getHpCurrent()).isEqualTo(1);
            assertThat(logged("Wizard's Revivify returns Fighter to life with 1 HP")).isTrue();
        }

        @Test
        @DisplayName("Goodberry gives ten berries, each eaten with an action for 1 hit point (PHB p.246)")
        void goodberry() {
            cast("Goodberry", 1, List.of());
            ResourcePoolEntry berries = wizard.getResourcePoolsCurrent().getFirst();
            assertThat(berries.currentUses()).isEqualTo(10);
            assertThat(berries.spendActionType()).isEqualTo("ACTION");

            wizard.setHpCurrent(20);
            f.poolUse.usePool(f.encounterId(), new UsePoolRequest(wizard.getId(), berries.poolId(), 1, null, null,
                    null, null), f.dmUserId);
            assertThat(wizard.getHpCurrent()).isEqualTo(21);
            assertThat(wizard.getResourcePoolsCurrent().getFirst().currentUses()).isEqualTo(9);

            cast("Goodberry", 1, List.of());
            assertThat(wizard.getResourcePoolsCurrent()).singleElement()
                    .satisfies(pool -> assertThat(pool.currentUses()).isEqualTo(19));
        }
    }

    @Nested
    @DisplayName("Lasting effects the DM applies")
    class Lasting {

        @Test
        @DisplayName("Fog Cloud blinds the creatures chosen while its concentration lasts, an hour (PHB p.243)")
        void fogCloud() {
            EncounterParticipant goblin = f.monster("Goblin", 7, 13);
            cast("Fog Cloud", 1, List.of(goblin));
            assertThat(condition(goblin, "blinded")).get()
                    .satisfies(entry -> assertThat(entry.concentrationInstanceId())
                            .isEqualTo(wizard.getConcentration().id()));
            assertThat(effect("Fog Cloud")).get().satisfies(fog -> assertThat(fog.durationRounds()).isEqualTo(600));
        }

        @Test
        @DisplayName("Magic Circle against fiends: a fiend has disadvantage on attacks against the creatures inside, "
                + "for an hour more for each slot level above 3rd (PHB p.256)")
        void magicCircle() {
            EncounterParticipant ally = f.player("Fighter", 40, 18);
            cast("Magic Circle", 4, List.of(ally), "fiend");
            assertThat(effect("Magic Circle")).get().satisfies(circle -> {
                assertThat(circle.durationRounds()).isEqualTo(1200);
                assertThat(circle.modifiers()).singleElement().satisfies(modifier -> {
                    assertThat(modifier.kind()).isEqualTo(OngoingModifier.Kind.ATTACKED_MODE);
                    assertThat(modifier.attackerTypes()).containsExactly("fiend");
                });
            });
        }

        @Test
        @DisplayName("Leomund's Tiny Hut stays the DM's but leaves its 8-hour Spell Effect and logs its reminder")
        void tinyHut() {
            SpellCaster.CastResult result = cast("Leomund's Tiny Hut", 3, List.of());
            assertThat(result.autoResolved()).isFalse();
            assertThat(effect("Leomund's Tiny Hut")).get()
                    .satisfies(hut -> assertThat(hut.durationRounds()).isEqualTo(4800));
            assertThat(logged("10-foot dome")).isTrue();
        }

        @Test
        @DisplayName("Wall of Sand keeps its concentration and leaves its Spell Effect linked to it")
        void wallOfSand() {
            cast("Wall of Sand", 3, List.of());
            assertThat(effect("Wall of Sand")).get().satisfies(wall -> {
                assertThat(wall.durationRounds()).isEqualTo(100);
                assertThat(wall.concentrationInstanceId()).isEqualTo(wizard.getConcentration().id());
            });
        }
    }

    @Nested
    @DisplayName("Templates")
    class Templates {

        @Test
        @DisplayName("The new keys read back, and a bad one makes the template unreadable")
        void keys() {
            SpellTemplate template = SpellTemplate.parse("""
                    {"spellLevel": 3, "concentration": true, "noConcentrationFromSlot": 5,
                     "hpPool": {"dice": "5d8", "upcastDice": "2d8"}, "keepsEffect": true, "usedByTarget": true,
                     "revivesDead": 1, "spellPool": {"name": "Goodberries", "uses": 10, "heals": 1},
                     "tempHitPoints": {"flat": 5, "perSlotLevel": 5},
                     "durationBySlot": {"3": 10, "9": null}, "durationRounds": 10}""");
            assertThat(template.unreadable()).isNull();
            assertThat(template.hpPool()).isEqualTo(new SpellTemplate.HpPool("5d8", "2d8"));
            assertThat(template.keepsEffect()).isTrue();
            assertThat(template.usedByTarget()).isTrue();
            assertThat(template.revivesDead()).isEqualTo(1);
            assertThat(template.spellPool()).isEqualTo(new SpellTemplate.SpellPool("Goodberries", 10, 1));
            assertThat(template.concentrationAt(4)).isTrue();
            assertThat(template.concentrationAt(5)).isFalse();
            assertThat(template.tempHitPoints(null, 3)).isEqualTo(5);
            assertThat(template.durationRounds(8)).isEqualTo(10);
            assertThat(template.durationRounds(9)).isNull();

            assertThat(SpellTemplate.parse("{\"hpPool\": {\"dice\": \"lots\"}}").unreadable()).isNotNull();
            assertThat(SpellTemplate.parse("{\"revivesDead\": 0}").unreadable()).isNotNull();
            assertThat(SpellTemplate.parse("{\"spellPool\": {\"name\": \"x\"}}").unreadable()).isNotNull();
        }
    }
}
