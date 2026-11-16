package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.AttackRollRequest;
import com.tabletophelper.encounter.dto.CastSpellRequest;
import com.tabletophelper.encounter.dto.HealRequest;
import com.tabletophelper.encounter.dto.MonsterActionRequest;
import com.tabletophelper.encounter.state.ConcentrationInstance;
import com.tabletophelper.encounter.state.ItemUse;
import com.tabletophelper.encounter.state.ReactionOffer;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellTemplate;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.JacksonDefaults;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Answering reaction offers (D216), after review: Shield takes a hit back only when that is exact, and otherwise
 * leaves it to the DM; an answer naming an offer that has gone is a 409 before anything is spent; the attacker keeps
 * what its attack spent; offers last past other creatures' commands until the turn changes; and a reactor's known
 * spells are looked up once a command. An ogre (Claw +4 for 3, Bite +10 for 30, a DC 13 Spit for 4 acid) attacks a
 * wizard (20 HP, AC 12) who knows Shield and has two level 1 slots.
 */
class ReactionAnswersTest {

    private CombatFixture f;
    private EncounterParticipant ogre;
    private EncounterParticipant wizard;
    private EncounterParticipant cleric;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        f.offerReactions();
        f.recordCommands();
        f.started();
        ogre = f.monster("Ogre", 60, 13);
        ogre.getMonster().setActionTemplates(MonsterTemplates.parse("""
                {"actions": [
                   {"name": "Claw", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 4,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "3", "damageType": "slashing"}]},
                   {"name": "Bite", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 10,
                    "effects": [{"effectType": "DAMAGE", "damageDice": "30", "damageType": "piercing"}]},
                   {"name": "Spit", "deliveryMethod": "SAVING_THROW", "saveDC": 13, "saveAbility": "DEX",
                    "effects": [{"effectType": "DAMAGE", "damageDice": "4", "damageType": "acid"}]},
                   {"name": "Claw and Bite", "isMultiattack": true, "multiattackComponents": ["Claw", "Bite"]},
                   {"name": "Claw and Spit", "isMultiattack": true, "multiattackComponents": ["Claw", "Spit"]},
                   {"name": "Spit and Claw", "isMultiattack": true, "multiattackComponents": ["Spit", "Claw"]}]}"""));
        wizard = f.player("Wizard", 20, 12);
        wizard.setSpellcastingAbility("INT");
        wizard.setSpellAttackBonus(5);
        wizard.setSpellSaveDc(13);
        wizard.setSpellSlotsCurrent(new HashMap<>(Map.of("1", new SpellSlot(2, 0))));
        f.knows(wizard, new KnownSpell(null, "Shield", 1, "class:Wizard", true, null, null, null, null));
        cleric = f.player("Cleric", 20, 15);
        shieldSpell();
    }

    // Shield as the game data ships it, cast as a reaction
    private void shieldSpell() {
        try (InputStream in = getClass().getResourceAsStream("/data/spell-effects/spell-effect-definitions.json")) {
            for (JsonNode definition : JacksonDefaults.mapper().readTree(in)) {
                if ("Shield".equals(definition.path("spellName").asString(null))) {
                    Spell spell = Spell.builder().id(UUID.randomUUID()).name("Shield").level(1)
                            .castingTime("1 reaction, which you take when you are hit by an attack")
                            .effectTemplate(SpellTemplate.of(definition)).build();
                    lenient().when(f.spellRepository.findByNameIgnoreCase(argThat("Shield"::equalsIgnoreCase)))
                            .thenReturn(Optional.of(spell));
                    return;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        throw new AssertionError("Shield has no shipped template");
    }

    private void ogreAct(String action, EncounterParticipant target, int... rolls) {
        f.dice.script(rolls);
        f.inTransaction(() -> f.monsterActions.monsterAction(f.encounterId(), new MonsterActionRequest(ogre.getId(),
                action, "ACTION", List.of(target.getId()), null, null, null, null), f.dmUserId));
    }

    private void shield(UUID offerId) {
        CastSpellRequest request = new CastSpellRequest();
        request.setSpellName("Shield");
        request.setSlotLevel(1);
        request.setTargetIds(List.of(wizard.getId()));
        request.setReactionOfferId(offerId);
        f.inTransaction(() -> f.spellcasting.castSpell(f.encounterId(), request, wizard.getId(), f.dmUserId));
    }

    private List<ReactionOffer> offers() {
        return f.encounter.getReactionOffers();
    }

    private ReactionOffer offerFor(EncounterParticipant reactor) {
        return offers().stream().filter(offer -> offer.reactorId().equals(reactor.getId())).findFirst()
                .orElseThrow(() -> new AssertionError("No offer for " + reactor.getDisplayName() + ": " + offers()));
    }

    private static ReactionOffer.ReactionOption shieldOption(ReactionOffer offer) {
        return offer.options().stream().filter(ReactionOffers::isShield).findFirst().orElseThrow();
    }

    private int slotsUsed() {
        return wizard.getSpellSlotsCurrent().get("1").used();
    }

    @Nested
    @DisplayName("Shield's take-back")
    class TakeBack {

        @Test
        @DisplayName("takes back a single claw that +5 AC turns into a miss, and the ogre keeps its spent action")
        void singleHit() {
            // d20 10 + 4 = 14 hits AC 12 but not 17
            ogreAct("Claw", wizard, 10);
            assertThat(wizard.getHpCurrent()).isEqualTo(17);
            assertThat(shieldOption(offerFor(wizard)).resolves()).isTrue();

            shield(offerFor(wizard).id());

            assertThat(wizard.getHpCurrent()).isEqualTo(20);
            assertThat(slotsUsed()).isEqualTo(1);
            assertThat(ogre.getTurnEconomy().actionsUsed()).isEqualTo(1);
            assertThat(f.descriptions()).contains("Shield raises Wizard's AC to 17 against Ogre's attack roll of 14, "
                    + "which misses: the hit is taken back.");
        }

        @Test
        @DisplayName("doesn't take back a claw when the same Multiattack's bite still hits and drops the wizard to 0")
        void clawThenBite() {
            // Claw 10 + 4 = 14 (a miss against 17); Bite 10 + 10 = 20 still hits, for 30
            ogreAct("Claw and Bite", wizard, 10, 10);
            assertThat(wizard.getHpCurrent()).isZero();
            ReactionOffer claw = offerFor(wizard);
            assertThat(claw.attackTotal()).isEqualTo(14);
            assertThat(shieldOption(claw).resolves()).isFalse();
            assertThat(shieldOption(claw).note()).contains("did more to it after it");

            shield(claw.id());

            // Nothing is reverted: the bite's damage stands, and the Shield is spent for its +5 AC
            assertThat(wizard.getHpCurrent()).isZero();
            assertThat(slotsUsed()).isEqualTo(1);
            assertThat(f.commands.stream().filter(command -> command.getUndoneAt() != null)).isEmpty();
            assertThat(f.descriptions()).anyMatch(line -> line.startsWith("Shield raises Wizard's AC to 17 against "
                    + "Ogre's attack roll of 14, which misses, but the hit can't be taken back here (the same action "
                    + "did more to Wizard than this hit)"));
        }

        @Test
        @DisplayName("doesn't take back a claw when the same command's save damage then hits the wizard")
        void saveDamageInTheSameCommand() {
            // Claw 14 hits for 3; Spit: the wizard's DEX save of 2 fails, 4 acid
            ogreAct("Claw and Spit", wizard, 10, 2);
            assertThat(wizard.getHpCurrent()).isEqualTo(13);
            ReactionOffer claw = offers().stream().filter(o -> o.trigger() == ReactionOffer.Trigger.ATTACK_HIT)
                    .findFirst().orElseThrow();
            assertThat(shieldOption(claw).resolves()).isFalse();

            shield(claw.id());
            assertThat(wizard.getHpCurrent()).isEqualTo(13);
            assertThat(f.commands.stream().filter(command -> command.getUndoneAt() != null)).isEmpty();
        }

        @Test
        @DisplayName("offers Shield only for its +5 AC against a claw that follows save damage in the same command")
        void saveDamageFirst() {
            ogreAct("Spit and Claw", wizard, 2, 10);
            ReactionOffer later = offers().stream().filter(o -> o.trigger() == ReactionOffer.Trigger.ATTACK_HIT)
                    .findFirst().orElseThrow();
            assertThat(shieldOption(later).resolves()).isFalse();
            assertThat(shieldOption(later).note()).contains("did more to it before it");
        }

        @Test
        @DisplayName("the attacker keeps the scroll it read, the one-shot effect it spent and the concentration it "
                + "ended with the attack")
        void attackerKeepsWhatItSpent() {
            SpellEffectEntry trueStrike = SpellEffectEntry.start("True Strike", 0, null, 1, 2, null)
                    .withAffected(List.of(ogre.getId()));
            ConcentrationInstance blur = ConcentrationInstance.start("Blur", 2, 1, 10);
            ogre.setActiveEffects(new ArrayList<>(List.of(trueStrike)));
            ogre.setConcentration(blur);
            f.dice.script(10);
            f.inTransaction(() -> {
                f.monsterActions.monsterAction(f.encounterId(), new MonsterActionRequest(ogre.getId(), "Claw",
                        "ACTION", List.of(wizard.getId()), null, null, null, null), f.dmUserId);
                // As the same attack would: a scroll read, the one-shot spent, concentration ended by the casting
                ogre.setItemsUsed(new ArrayList<>(List.of(new ItemUse("Spell Scroll (Cantrip)", "True Strike"))));
                ogre.setActiveEffects(new ArrayList<>());
                ogre.setConcentration(null);
                return null;
            });

            shield(offerFor(wizard).id());

            assertThat(wizard.getHpCurrent()).isEqualTo(20);
            assertThat(ogre.getItemsUsed()).extracting(ItemUse::spell).containsExactly("True Strike");
            assertThat(ogre.getActiveEffects()).isEmpty();
            assertThat(ogre.getConcentration()).isNull();
        }

        @Test
        @DisplayName("doesn't take back a hit when the same command then changed the wizard without harming it")
        void laterChangeInTheSameCommand() {
            f.dice.script(10);
            f.inTransaction(() -> {
                f.monsterActions.monsterAction(f.encounterId(), new MonsterActionRequest(ogre.getId(), "Claw",
                        "ACTION", List.of(wizard.getId()), null, null, null, null), f.dmUserId);
                // As a later part of the same action that reaches the wizard without damage would
                wizard.setActiveConditions(new ArrayList<>(List.of(
                        com.tabletophelper.encounter.state.ConditionEntry.manual("frightened", null, 1, ogre.getId()))));
                return null;
            });
            assertThat(shieldOption(offerFor(wizard)).resolves()).isFalse();

            shield(offerFor(wizard).id());

            assertThat(wizard.getHpCurrent()).isEqualTo(17);
            assertThat(CombatFixture.conditionNames(wizard)).contains("frightened");
        }

        @Test
        @DisplayName("doesn't take back a hit that left an effect of the attacker's on the wizard")
        void effectLeftOnTheWizard() {
            f.dice.script(10);
            f.inTransaction(() -> {
                f.monsterActions.monsterAction(f.encounterId(), new MonsterActionRequest(ogre.getId(), "Claw",
                        "ACTION", List.of(wizard.getId()), null, null, null, null), f.dmUserId);
                ogre.setActiveEffects(new ArrayList<>(List.of(SpellEffectEntry.start("Hex", 1, null, 1, 10, null)
                        .withAffected(List.of(wizard.getId())))));
                return null;
            });

            shield(offerFor(wizard).id());

            assertThat(wizard.getHpCurrent()).isEqualTo(17);
            assertThat(f.descriptions()).anyMatch(line -> line.contains("the attack left something of Ogre's on "
                    + "Wizard"));
        }
    }

    @Nested
    @DisplayName("Answering an offer that has gone")
    class Gone {

        @Test
        @DisplayName("is a 409 before anything is spent, for an unknown offer or another creature's")
        void unknownOrOthers() {
            ogreAct("Claw", wizard, 10);
            UUID wizards = offerFor(wizard).id();

            assertThatThrownBy(() -> shield(UUID.randomUUID()))
                    .isInstanceOf(ConflictException.class).hasMessage(ReactionOfferService.GONE);
            CastSpellRequest asCleric = new CastSpellRequest();
            asCleric.setSpellName("Shield");
            asCleric.setSlotLevel(1);
            asCleric.setTargetIds(List.of(cleric.getId()));
            asCleric.setReactionOfferId(wizards);
            assertThatThrownBy(() -> f.inTransaction(() -> f.spellcasting.castSpell(f.encounterId(), asCleric,
                    cleric.getId(), f.dmUserId)))
                    .isInstanceOf(ConflictException.class).hasMessage(ReactionOfferService.GONE);

            assertThat(slotsUsed()).isZero();
            assertThat(wizard.getTurnEconomy() == null || !wizard.getTurnEconomy().reactionUsed()).isTrue();
            assertThat(wizard.getHpCurrent()).isEqualTo(17);
        }

        @Test
        @DisplayName("is a 409 once the offer has been answered")
        void answered() {
            ogreAct("Claw", wizard, 10);
            UUID offer = offerFor(wizard).id();
            shield(offer);

            assertThatThrownBy(() -> shield(offer))
                    .isInstanceOf(ConflictException.class).hasMessage(ReactionOfferService.GONE);
            assertThat(slotsUsed()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("How long an offer lasts")
    class Lasting {

        @Test
        @DisplayName("past another creature's command, as an Extra Attack at someone else; Shield then still takes "
                + "the first hit back, leaving the second")
        void extraAttack() {
            f.turnOf(ogre);
            ogreAct("Claw", wizard, 10);
            UUID offer = offerFor(wizard).id();

            // A second, separate attack at the cleric: 18 + 4 hits AC 15 for 3
            AttackRollRequest second = new AttackRollRequest();
            second.setTargetId(cleric.getId());
            second.setAttackBonus(4);
            second.setDamageDice("3");
            f.dice.script(18);
            f.inTransaction(() -> f.attacks.rollAttack(f.encounterId(), second, ogre.getId(), f.dmUserId));
            assertThat(cleric.getHpCurrent()).isEqualTo(17);
            assertThat(offers()).extracting(ReactionOffer::id).contains(offer);
            var economy = ogre.getTurnEconomy();

            shield(offer);

            assertThat(wizard.getHpCurrent()).isEqualTo(20);
            assertThat(cleric.getHpCurrent()).isEqualTo(17);
            // Both attacks stay made
            assertThat(ogre.getTurnEconomy()).isEqualTo(economy);
        }

        @Test
        @DisplayName("but a hit on the wizard can't be taken back once a later command has changed the wizard")
        void wizardChangedSince() {
            ogreAct("Claw", wizard, 10);
            UUID offer = offerFor(wizard).id();
            HealRequest heal = new HealRequest();
            heal.setTargetId(wizard.getId());
            heal.setAmount(1);
            f.inTransaction(() -> f.hitPoints.applyHealing(f.encounterId(), heal, null, f.dmUserId));

            shield(offer);

            assertThat(wizard.getHpCurrent()).isEqualTo(18);
            assertThat(f.descriptions()).anyMatch(line -> line.contains("later actions have changed Wizard"));
        }

        @Test
        @DisplayName("until the turn changes")
        void turnChange() {
            ogreAct("Claw", wizard, 10);
            assertThat(offers()).hasSize(1);

            f.inTransaction(() -> f.turns.advanceTurn(f.encounterId(), f.dmUserId));

            assertThat(offers()).isEmpty();
        }

        @Test
        @DisplayName("until the reactor's reaction is spent some other way, when it isn't shown and goes")
        void reactionSpent() {
            ogreAct("Claw", wizard, 10);
            ReactionOffer offer = offerFor(wizard);
            // Spent outside a reaction, as the DM's edit of its turn would
            var economy = com.tabletophelper.encounter.state.TurnEconomy.fresh();
            wizard.setTurnEconomy(economy.withBudget(com.tabletophelper.rules.combat.ActionEconomy.spend(
                    economy.budget(), com.tabletophelper.rules.combat.Charge.of(
                            com.tabletophelper.rules.combat.ActionCost.REACTION, false))));
            assertThat(ReactionOffers.open(f.encounter, offer)).isFalse();

            HealRequest heal = new HealRequest();
            heal.setTargetId(cleric.getId());
            heal.setAmount(1);
            f.inTransaction(() -> f.hitPoints.applyHealing(f.encounterId(), heal, null, f.dmUserId));
            assertThat(offers()).isEmpty();
        }

        @Test
        @DisplayName("a Counterspell offer only while its spell is the newest command; answering it after is a 409")
        void counterspellOnlyWhileNewest() {
            ReactionOffer counter = new ReactionOffer(UUID.randomUUID(), f.commands.getLast().getId(),
                    ogre.getId(), "Ogre", ReactionOffer.Trigger.SPELL_CAST, wizard.getId(), "Wizard", null, null, false,
                    null, null, "Fire Bolt", 0, List.of(ReactionOffer.ReactionOption.spell("Counterspell",
                            "Counterspell", null, List.of(3), null, List.of(wizard.getId()), true, null)));
            f.encounter.setReactionOffers(new ArrayList<>(List.of(counter)));

            HealRequest heal = new HealRequest();
            heal.setTargetId(cleric.getId());
            heal.setAmount(1);
            f.inTransaction(() -> f.hitPoints.applyHealing(f.encounterId(), heal, null, f.dmUserId));

            assertThat(offers()).isEmpty();
        }
    }

    @Test
    @DisplayName("A reactor's known spells are looked up once a command, however many offers the command raises")
    void knownSpellsOncePerCommand() {
        f.knows(wizard, new KnownSpell(null, "Absorb Elements", 1, "class:Wizard", true, null, null, null, null));
        f.knows(wizard, new KnownSpell(null, "Fire Bolt", 0, "class:Wizard", true, null, null, null, null));
        clearInvocations(f.spellRepository);

        // Claw 14 hits for 3, then the spit's 4 acid on a failed save of 2: two offers, several spells each
        ogreAct("Claw and Spit", wizard, 10, 2);

        assertThat(offers()).hasSize(2);
        assertThat(offers().getLast().options()).extracting(ReactionOffer.ReactionOption::name)
                .contains("Absorb Elements (acid)");
        // One lookup per known spell, not one per spell per offer and option
        verify(f.spellRepository, times(1)).findByNameIgnoreCase(argThat("Absorb Elements"::equalsIgnoreCase));
        verify(f.spellRepository, times(1)).findByNameIgnoreCase(argThat("Fire Bolt"::equalsIgnoreCase));
        verify(f.spellRepository, times(3)).findByNameIgnoreCase(any());
    }
}
