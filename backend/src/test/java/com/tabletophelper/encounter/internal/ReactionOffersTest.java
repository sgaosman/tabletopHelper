package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.encounter.CombatCommand;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.AttackRollRequest;
import com.tabletophelper.encounter.dto.UndoRequest;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.ReactionOffer;
import com.tabletophelper.encounter.state.SpellSource;
import com.tabletophelper.encounter.state.TurnEconomy;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.rules.combat.ActionCost;
import com.tabletophelper.rules.combat.ActionEconomy;
import com.tabletophelper.rules.combat.Charge;
import com.tabletophelper.rules.combat.LifeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reaction prompts (D216): which reactions a hit, damage or a spell cast offers, and to whom; who is offered nothing;
 * and how long an offer lasts. A wizard with Shield, Absorb Elements and Counterspell and slots of levels 1 and 3 is
 * attacked by a goblin; a tiefling warlock knows Hellish Rebuke, a goliath has Stone's Endurance.
 */
class ReactionOffersTest {

    private CombatFixture f;
    private EncounterParticipant goblin;
    private EncounterParticipant wizard;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        f.offerReactions();
        goblin = f.monster("Goblin", 30, 13);
        wizard = f.player("Wizard", 20, 12);
        knows(wizard, Map.of("Shield", 1, "Absorb Elements", 1, "Counterspell", 3));
        wizard.setSpellSlotsCurrent(new HashMap<>(Map.of("1", new SpellSlot(2, 0), "3", new SpellSlot(1, 0))));
    }

    private static void knows(EncounterParticipant caster, Map<String, Integer> spells) {
        List<KnownSpell> known = new ArrayList<>();
        spells.forEach((name, level) -> known.add(new KnownSpell(null, name, level, "class:Wizard", true, null, null,
                null, null)));
        caster.setSpellsKnown(known);
    }

    private static void reactionUsed(EncounterParticipant participant) {
        TurnEconomy economy = TurnEconomy.fresh();
        participant.setTurnEconomy(economy.withBudget(ActionEconomy.spend(economy.budget(),
                Charge.of(ActionCost.REACTION, false))));
    }

    // The goblin hits the wizard: an attack roll of the total against its AC, and the damage taken
    private void hit(EncounterParticipant target, int total, boolean critical, int damage, String type) {
        f.reactionOffers.afterHarm(f.encounter, goblin, goblin.getDisplayName(), target, true, critical, total,
                target.getArmourClass(), ReactionOffers.Before.of(target), damage, type == null ? List.of()
                        : List.of(type));
    }

    private List<ReactionOffer> offers() {
        return f.encounter.getReactionOffers();
    }

    private ReactionOffer.ReactionOption option(String name) {
        return offers().stream().flatMap(offer -> offer.options().stream())
                .filter(option -> option.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + name + " offered: " + offers()));
    }

    private List<String> optionNames() {
        return offers().stream().flatMap(offer -> offer.options().stream()).map(ReactionOffer.ReactionOption::name).toList();
    }

    @Nested
    @DisplayName("Being hit by an attack")
    class Hit {

        @Test
        @DisplayName("offers Shield with the level 1 slot, which takes the hit back when the roll misses AC + 5")
        void shieldTurnsTheHitIntoAMiss() {
            hit(wizard, 15, false, 5, "slashing");

            ReactionOffer offer = offers().getFirst();
            assertThat(offer.trigger()).isEqualTo(ReactionOffer.Trigger.ATTACK_HIT);
            assertThat(offer.reactorId()).isEqualTo(wizard.getId());
            assertThat(offer.triggerActorName()).isEqualTo("Goblin");
            assertThat(offer.attackTotal()).isEqualTo(15);
            assertThat(offer.armourClass()).isEqualTo(12);
            ReactionOffer.ReactionOption shield = option("Shield");
            assertThat(shield.kind()).isEqualTo(ReactionOffer.Kind.SPELL);
            assertThat(shield.spellSource()).isEqualTo(SpellSource.SLOT);
            assertThat(shield.slotLevels()).containsExactly(1, 3);
            assertThat(shield.targetIds()).containsExactly(wizard.getId());
            assertThat(shield.resolves()).isTrue();
            assertThat(shield.note()).contains("AC makes it 17").contains("misses");
        }

        @Test
        @DisplayName("offers Shield as advisory when the roll still hits AC + 5")
        void shieldStillHit() {
            hit(wizard, 17, false, 5, null);

            assertThat(option("Shield").resolves()).isFalse();
            assertThat(option("Shield").note()).contains("still hits");
        }

        @Test
        @DisplayName("offers Shield as advisory against a critical hit, which no AC stops")
        void shieldCritical() {
            hit(wizard, 14, true, 10, null);

            assertThat(option("Shield").resolves()).isFalse();
            assertThat(offers().getFirst().critical()).isTrue();
        }

        @Test
        @DisplayName("offers Shield to a wizard the hit dropped to 0 when it would turn the hit into a miss, judged "
                + "as the attack hit")
        void shieldBeforeTheDamage() {
            ReactionOffers.Before before = ReactionOffers.Before.of(wizard);
            CombatFixture.downed(wizard, LifeState.DYING, 0);
            f.reactionOffers.afterHarm(f.encounter, goblin, "Goblin", wizard, true, false, 15, 12, before, 20,
                    List.of());

            assertThat(optionNames()).containsExactly("Shield");
        }

        @Test
        @DisplayName("offers nothing to a wizard the hit dropped to 0 when Shield wouldn't stop it")
        void noShieldWhenDownAndStillHit() {
            ReactionOffers.Before before = ReactionOffers.Before.of(wizard);
            CombatFixture.downed(wizard, LifeState.DYING, 0);
            f.reactionOffers.afterHarm(f.encounter, goblin, "Goblin", wizard, true, false, 19, 12, before, 20,
                    List.of());

            assertThat(offers()).isEmpty();
        }

        @Test
        @DisplayName("offers nothing once the wizard's reaction is used")
        void reactionUsed() {
            ReactionOffersTest.reactionUsed(wizard);
            hit(wizard, 15, false, 5, "fire");

            assertThat(offers()).isEmpty();
        }

        @Test
        @DisplayName("offers nothing to an incapacitated wizard")
        void incapacitated() {
            wizard.setActiveConditions(new ArrayList<>(List.of(ConditionEntry.manual("stunned", null, 1))));
            hit(wizard, 15, false, 5, "fire");

            assertThat(offers()).isEmpty();
        }

        @Test
        @DisplayName("offers no spell without a slot to cast it with")
        void noSlot() {
            wizard.setSpellSlotsCurrent(new HashMap<>(Map.of("1", new SpellSlot(2, 2), "3", new SpellSlot(1, 1))));
            hit(wizard, 15, false, 5, "fire");

            assertThat(offers()).isEmpty();
        }

        @Test
        @DisplayName("offers no Shield to a creature that doesn't know it")
        void doesNotKnowShield() {
            EncounterParticipant fighter = f.player("Fighter", 30, 16);
            hit(fighter, 17, false, 5, "slashing");

            assertThat(offers()).isEmpty();
        }

        @Test
        @DisplayName("offers nothing when the attack missed and dealt no damage")
        void miss() {
            f.reactionOffers.afterHarm(f.encounter, goblin, "Goblin", wizard, false, false, 5, 12,
                    ReactionOffers.Before.of(wizard), 0, List.of());

            assertThat(offers()).isEmpty();
        }

        @Test
        @DisplayName("offers a monster its stat block's Parry, which the DM applies by hand")
        void monsterParry() {
            goblin.getMonster().setActionTemplates(MonsterTemplates.parse("""
                    {"reactions": [{"name": "Parry", "automatable": false,
                      "description": "The goblin adds 2 to its AC against one melee attack that would hit it."},
                     {"name": "Shock Ward", "automatable": false, "description": "Sparks."}]}"""));
            f.reactionOffers.afterHarm(f.encounter, wizard, "Wizard", goblin, true, false, 14, 13,
                    ReactionOffers.Before.of(goblin), 4, List.of("slashing"));

            assertThat(optionNames()).containsExactly("Parry");
            ReactionOffer.ReactionOption parry = option("Parry");
            assertThat(parry.kind()).isEqualTo(ReactionOffer.Kind.MONSTER_REACTION);
            assertThat(parry.actionName()).isEqualTo("Parry");
            assertThat(parry.resolves()).isFalse();
        }

        @Test
        @DisplayName("comes from a weapon attack entered by hand, with the command's roll and the AC")
        void fromAnAttack() {
            f.turnOf(goblin);
            AttackRollRequest request = new AttackRollRequest();
            request.setTargetId(wizard.getId());
            request.setAttackBonus(4);
            request.setDamageDice("1d6");
            f.dice.script(11, 3);
            f.attacks.rollAttack(f.encounterId(), request, goblin.getId(), f.dmUserId);

            ReactionOffer offer = offers().getFirst();
            assertThat(offer.attackTotal()).isEqualTo(15);
            assertThat(offer.armourClass()).isEqualTo(12);
            assertThat(offer.damageTaken()).isEqualTo(3);
            assertThat(option("Shield").resolves()).isTrue();
        }
    }

    @Nested
    @DisplayName("Taking damage")
    class Damage {

        @Test
        @DisplayName("of an absorbed type offers Absorb Elements with that type, advisory for the damage taken")
        void absorbElements() {
            f.effectApplier.damage(f.encounter, goblin, "Goblin", wizard, 6, "fire");

            ReactionOffer offer = offers().getFirst();
            assertThat(offer.trigger()).isEqualTo(ReactionOffer.Trigger.DAMAGE);
            assertThat(offer.damageTaken()).isEqualTo(6);
            ReactionOffer.ReactionOption absorb = option("Absorb Elements (fire)");
            assertThat(absorb.spellName()).isEqualTo("Absorb Elements");
            assertThat(absorb.castOption()).isEqualTo("fire");
            assertThat(absorb.resolves()).isFalse();
            assertThat(optionNames()).doesNotContain("Shield");
        }

        @Test
        @DisplayName("of another type offers no Absorb Elements")
        void notAbsorbed() {
            f.effectApplier.damage(f.encounter, goblin, "Goblin", wizard, 6, "piercing");

            assertThat(offers()).isEmpty();
        }

        @Test
        @DisplayName("from a creature offers Hellish Rebuke at it, unless the warlock is blinded")
        void hellishRebuke() {
            EncounterParticipant warlock = f.player("Warlock", 20, 12);
            warlock.setSpellsKnown(List.of(new KnownSpell(null, "Hellish Rebuke", 1, "class:Warlock", null, null,
                    null, null, null)));
            warlock.setSpellSlotsCurrent(new HashMap<>(Map.of("pact_2", new SpellSlot(2, 0))));
            f.effectApplier.damage(f.encounter, goblin, "Goblin", warlock, 4, "slashing");

            ReactionOffer.ReactionOption rebuke = option("Hellish Rebuke");
            assertThat(rebuke.spellSource()).isEqualTo(SpellSource.PACT_SLOT);
            assertThat(rebuke.slotLevels()).containsExactly(2);
            assertThat(rebuke.targetIds()).containsExactly(goblin.getId());
            assertThat(rebuke.resolves()).isTrue();

            f.encounter.setReactionOffers(new ArrayList<>());
            warlock.setActiveConditions(new ArrayList<>(List.of(ConditionEntry.manual("blinded", null, 1))));
            f.effectApplier.damage(f.encounter, goblin, "Goblin", warlock, 4, "slashing");
            assertThat(offers()).isEmpty();
        }

        @Test
        @DisplayName("by the DM's hand offers no Hellish Rebuke, which needs a creature to rebuke")
        void noRebukeForTheDm() {
            EncounterParticipant warlock = f.player("Warlock", 20, 12);
            warlock.setSpellsKnown(List.of(new KnownSpell(null, "Hellish Rebuke", 1, "class:Warlock", null, null,
                    null, null, null)));
            warlock.setSpellSlotsCurrent(new HashMap<>(Map.of("pact_2", new SpellSlot(2, 0))));
            f.effectApplier.damage(f.encounter, null, "DM", warlock, 4, "slashing");

            assertThat(offers()).isEmpty();
        }

        @Test
        @DisplayName("offers a goliath Stone's Endurance with the damage taken, while it has a use")
        void stonesEndurance() {
            EncounterParticipant goliath = f.player("Goliath", 30, 14);
            goliath.setResourcePoolsCurrent(new ArrayList<>(List.of(new ResourcePoolEntry("race:stones-endurance",
                    "Stone's Endurance", "RACE", "Goliath", 1, null, 1, "shortRest", null, null, "REACTION", null,
                    Map.of("effect", "stones-endurance")))));
            f.effectApplier.damage(f.encounter, goblin, "Goblin", goliath, 9, "bludgeoning");

            ReactionOffer.ReactionOption endurance = option("Stone's Endurance");
            assertThat(endurance.kind()).isEqualTo(ReactionOffer.Kind.POOL);
            assertThat(endurance.poolId()).isEqualTo("race:stones-endurance");
            assertThat(endurance.amount()).isEqualTo(9);

            f.encounter.setReactionOffers(new ArrayList<>());
            goliath.setResourcePoolsCurrent(new ArrayList<>(List.of(goliath.getResourcePoolsCurrent().getFirst()
                    .withCurrentUses(0))));
            f.effectApplier.damage(f.encounter, goblin, "Goblin", goliath, 9, "bludgeoning");
            assertThat(offers()).isEmpty();
        }
    }

    @Nested
    @DisplayName("A spell cast")
    class SpellCast {

        private EncounterParticipant mage;

        @BeforeEach
        void mage() {
            mage = f.monster("Mage", 40, 12);
        }

        @Test
        @DisplayName("offers Counterspell at the caster to a creature on the other side with a level 3 slot")
        void counterspell() {
            f.reactionOffers.afterCast(f.encounter, mage, "Fireball", 3, false);

            ReactionOffer offer = offers().getFirst();
            assertThat(offer.trigger()).isEqualTo(ReactionOffer.Trigger.SPELL_CAST);
            assertThat(offer.reactorId()).isEqualTo(wizard.getId());
            assertThat(offer.spellName()).isEqualTo("Fireball");
            assertThat(offer.spellLevel()).isEqualTo(3);
            ReactionOffer.ReactionOption counter = option("Counterspell");
            assertThat(counter.targetIds()).containsExactly(mage.getId());
            assertThat(counter.slotLevels()).containsExactly(3);
            assertThat(counter.note()).contains("outright");
        }

        @Test
        @DisplayName("notes the check against a spell above the slot's level")
        void counterspellCheck() {
            f.reactionOffers.afterCast(f.encounter, mage, "Cone of Cold", 5, false);

            assertThat(option("Counterspell").note()).contains("DC 15");
        }

        @Test
        @DisplayName("offers nothing for an ally's spell, a Subtle Spell, an invisible caster or a Counterspell")
        void notOffered() {
            EncounterParticipant cleric = f.player("Cleric", 20, 15);
            f.reactionOffers.afterCast(f.encounter, cleric, "Bless", 1, false);
            f.reactionOffers.afterCast(f.encounter, mage, "Fireball", 3, true);
            f.reactionOffers.afterCast(f.encounter, mage, "Counterspell", 3, false);
            mage.setActiveConditions(new ArrayList<>(List.of(ConditionEntry.manual("invisible", null, 1))));
            f.reactionOffers.afterCast(f.encounter, mage, "Fireball", 3, false);

            assertThat(offers()).isEmpty();
        }

        @Test
        @DisplayName("offers nothing once the reaction is used, or without a slot of level 3")
        void exclusions() {
            ReactionOffersTest.reactionUsed(wizard);
            f.reactionOffers.afterCast(f.encounter, mage, "Fireball", 3, false);
            assertThat(offers()).isEmpty();

            wizard.setTurnEconomy(TurnEconomy.fresh());
            wizard.setSpellSlotsCurrent(new HashMap<>(Map.of("1", new SpellSlot(2, 0), "3", new SpellSlot(1, 1))));
            f.reactionOffers.afterCast(f.encounter, mage, "Fireball", 3, false);
            assertThat(offers()).isEmpty();
        }

        @Test
        @DisplayName("offers a monster that knows Counterspell the chance against a player's spell, for the DM")
        void monsterCounterspell() {
            EncounterParticipant archmage = f.monster("Archmage", 99, 12);
            archmage.getMonster().setActionTemplates(MonsterTemplates.parse("""
                    {"spellcasting": {"ability": "INT", "attackBonus": 9, "saveDC": 17, "slots": {"3": 3},
                     "spellsByLevel": {"3": ["counterspell"]}}}"""));
            archmage.setSpellSlotsCurrent(new HashMap<>(Map.of("3", new SpellSlot(3, 0))));
            f.reactionOffers.afterCast(f.encounter, wizard, "Fireball", 3, false);

            assertThat(offers()).extracting(ReactionOffer::reactorId).containsExactly(archmage.getId());
        }
    }

    @Nested
    @DisplayName("Expiry")
    class Expiry {

        private ReactionOffer offer(EncounterParticipant reactor, UUID commandId) {
            return new ReactionOffer(UUID.randomUUID(), commandId, reactor.getId(), reactor.getDisplayName(),
                    ReactionOffer.Trigger.DAMAGE, goblin.getId(), "Goblin", null, null, false, 3, List.of("fire"),
                    null, null, List.of(ReactionOffer.ReactionOption.pool("Stone's Endurance", "race:stones-endurance", 3,
                            true, null)));
        }

        @BeforeEach
        void commands() {
            f.recordCommands();
        }

        @Test
        @DisplayName("a turn change ends every offer from the commands before it")
        void nextCommand() {
            f.encounter.setReactionOffers(new ArrayList<>(List.of(offer(wizard, UUID.randomUUID()))));

            f.inTransaction(() -> {
                f.turns.advanceTurn(f.encounterId(), f.dmUserId);
                return null;
            });

            assertThat(offers()).isEmpty();
        }

        @Test
        @DisplayName("a reaction keeps the other creatures' offers and ends the reactor's")
        void reactionKeepsOthers() {
            EncounterParticipant cleric = f.player("Cleric", 20, 15);
            ReactionOffer wizards = offer(wizard, UUID.randomUUID());
            ReactionOffer clerics = offer(cleric, wizards.commandId());
            f.encounter.setReactionOffers(new ArrayList<>(List.of(wizards, clerics)));

            f.inTransaction(() -> {
                f.commandRecorder.record(f.encounter, f.dmUserId);
                assertThat(offers()).containsExactly(wizards, clerics);
                f.economy.charge(f.encounter, wizard, Charge.of(ActionCost.REACTION, false), f.dmUserId,
                        new ActionEconomyKeeper.Command(com.tabletophelper.encounter.CombatActionType.POOL_USE,
                                "Wizard's reaction"));
                return null;
            });

            assertThat(offers()).containsExactly(clerics);
        }

        @Test
        @DisplayName("undo ends every offer, and a command taken back inside another ends the offers it triggered")
        void undoAndRevert() {
            f.started();
            f.inTransaction(() -> {
                f.commandRecorder.record(f.encounter, f.dmUserId);
                f.effectApplier.damage(f.encounter, goblin, "Goblin", wizard, 6, "fire");
                return null;
            });
            CombatCommand damage = f.commands.getLast();
            assertThat(offers()).hasSize(1);
            assertThat(offers().getFirst().commandId()).isEqualTo(damage.getId());

            f.inTransaction(() -> {
                f.commandRecorder.record(f.encounter, f.dmUserId);
                f.undoService.revert(f.encounter, damage, f.dmUserId);
                f.economy.charge(f.encounter, goblin, Charge.of(ActionCost.REACTION, false), f.dmUserId,
                        new ActionEconomyKeeper.Command(com.tabletophelper.encounter.CombatActionType.REACTION_TAKEN,
                                "Goblin's reaction"));
                return null;
            });
            assertThat(offers()).isEmpty();

            f.encounter.setReactionOffers(new ArrayList<>(List.of(offer(wizard, f.commands.getLast().getId()))));
            f.inTransaction(() -> f.undoService.undo(f.encounterId(),
                    new UndoRequest(f.commands.getLast().getId(), 1), f.dmUserId));
            assertThat(offers()).isEmpty();
        }
    }
}
