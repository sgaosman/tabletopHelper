package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.ParticipantType;
import com.tabletophelper.encounter.dto.CastSpellRequest;
import com.tabletophelper.encounter.dto.CommandSummonsRequest;
import com.tabletophelper.encounter.dto.CompanionSummonRequest;
import com.tabletophelper.encounter.dto.DamageRequest;
import com.tabletophelper.encounter.dto.DismissSummonsRequest;
import com.tabletophelper.encounter.dto.MonsterActionRequest;
import com.tabletophelper.encounter.dto.PlaceSummonRequest;
import com.tabletophelper.encounter.dto.SummonChoice;
import com.tabletophelper.encounter.dto.SummonCreature;
import com.tabletophelper.encounter.dto.UndoRequest;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.encounter.state.SummonSpec;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.rules.combat.SummonRules;
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
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

/**
 * Summoning in combat (D182), through the services wired as in the app: casting a summoning spell brings its creatures
 * as companions, owned by a Spell Effect on the caster, so they go when it ends (concentration lost, the caster at
 * 0 HP, the DM ending it); creatures that disappear at 0 HP do; the DM fills a summoning that waits and places one
 * with no slot spent; a familiar can't attack, Animate Dead's undead act for the player only when commanded; and undo
 * takes the summoning back, creatures and all.
 */
class SummonServiceTest {

    private static final String CONJURE_ANIMALS = """
            {"spellName": "Conjure Animals", "spellLevel": 3, "concentration": true, "durationRounds": 600,
             "deliveryMethod": "NONE", "effects": [{"effectType": "SUMMON"}], "requiresManualResolution": true,
             "manualResolutionReason": "Summons creatures"}""";

    private static final String LESSER_DEMONS = """
            {"spellName": "Summon Lesser Demons", "spellLevel": 3, "concentration": true, "durationRounds": 600,
             "deliveryMethod": "NONE", "effects": [{"effectType": "SUMMON"}], "requiresManualResolution": true}""";

    private static final String SUMMON_BEAST = """
            {"spellName": "Summon Beast", "spellLevel": 2, "concentration": true, "durationRounds": 600,
             "deliveryMethod": "NONE", "effects": [{"effectType": "SUMMON"}], "requiresManualResolution": true}""";

    private static final String SKELETON_TEMPLATES = """
            {"actions": [{"name": "Shortsword", "actionType": "ACTION", "deliveryMethod": "ATTACK_ROLL",
              "attackType": "MELEE_WEAPON", "attackBonus": 4, "targetCount": 1,
              "effects": [{"effectType": "DAMAGE", "damageDice": "1d6 + 2", "damageType": "piercing"}]}]}""";

    private static final String OWL_TEMPLATES = """
            {"actions": [{"name": "Talons", "actionType": "ACTION", "deliveryMethod": "ATTACK_ROLL",
              "attackType": "MELEE_WEAPON", "attackBonus": 3, "targetCount": 1,
              "effects": [{"effectType": "DAMAGE", "damageDice": "1", "damageType": "slashing"}]}]}""";

    private CombatFixture f;
    private EncounterParticipant druid;
    private EncounterParticipant goblin;
    private UUID player;
    private final Map<String, Monster> monsters = new HashMap<>();

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        f.recordCommands();
        f.started();
        druid = f.player("Druid", 30, 12);
        player = f.playerControlling(druid);
        druid.setInitiative(15);
        druid.setSpellAttackBonus(5);
        druid.setSpellSaveDc(13);
        druid.setSpellSlotsCurrent(new HashMap<>(Map.of("2", new SpellSlot(3, 0), "3", new SpellSlot(3, 0),
                "4", new SpellSlot(3, 0), "5", new SpellSlot(2, 0))));
        goblin = f.monster("Goblin", 7, 15);
        goblin.setInitiative(10);
        f.spell("Conjure Animals", 3, CONJURE_ANIMALS);
        f.spell("Summon Lesser Demons", 3, LESSER_DEMONS);
        f.spell("Summon Beast", 2, SUMMON_BEAST);
        monster("Wolf", "1/4", "beast", null, 11, 13, 15, null, "MM");
        monster("Brown Bear", "1", "beast", null, 34, 11, 10, null, "MM");
        monster("Dretch", "1/4", "fiend", "demon", 18, 11, 11, null, "MM");
        monster("Owl", "0", "beast", null, 1, 11, 13, OWL_TEMPLATES, "MM");
        monster("Skeleton", "1/4", "undead", null, 13, 13, 14, SKELETON_TEMPLATES, "MM");
        monster("Zombie", "1/4", "undead", null, 22, 8, 6, null, "MM");
        monster("Bestial Spirit", null, "beast", null, 0, 10, 11, null, "TCE");
    }

    private Monster monster(String name, String cr, String type, String subtype, int hp, int ac, int dex,
                            String templates, String source) {
        Monster monster = Monster.builder().id(UUID.randomUUID()).name(name).challengeRating(cr).type(type)
                .subtype(subtype).hitPoints(hp).armourClass(ac).source(source)
                .strength(10).dexterity(dex).constitution(10).intelligence(2).wisdom(10).charisma(5)
                .actionTemplates(MonsterTemplates.parse(templates))
                .build();
        monsters.put(name, monster);
        lenient().when(f.monsterRepository.findById(monster.getId())).thenReturn(Optional.of(monster));
        lenient().when(f.monsterRepository.findByNameIgnoreCaseAndSource(name, source))
                .thenReturn(Optional.of(monster));
        return monster;
    }

    private UUID id(String name) {
        return monsters.get(name).getId();
    }

    private CastSpellRequest cast(String spell, int slot, SummonChoice choice) {
        f.knows(druid, spell);
        CastSpellRequest request = new CastSpellRequest();
        request.setSpellName(spell);
        request.setSlotLevel(slot);
        request.setTargetIds(List.of());
        request.setSummon(choice);
        return request;
    }

    private void castAs(UUID user, String spell, int slot, SummonChoice choice, int... dice) {
        CastSpellRequest request = cast(spell, slot, choice);
        f.dice.script(dice);
        f.inTransaction(() -> f.spellcasting.castSpell(f.encounterId(), request, druid.getId(), user));
    }

    private List<EncounterParticipant> summoned() {
        return f.encounter.getParticipants().stream()
                .filter(p -> p.getSummon() != null && !Boolean.TRUE.equals(p.getDismissed()))
                .toList();
    }

    private SpellEffectEntry effect(String spell) {
        return EffectApplier.effects(druid).stream().filter(e -> e.spell().equals(spell)).findFirst().orElseThrow();
    }

    private static SummonChoice wolves(UUID wolfId, String option, int count) {
        return new SummonChoice(option, List.of(new SummonCreature(wolfId, count)), null, null, null);
    }

    @Nested
    @DisplayName("Conjure Animals (PHB p.225)")
    class ConjureAnimals {

        @Test
        @DisplayName("At 5th level, sixteen wolves join as one group on one roll, the druid's, fey, after one slot")
        void fifthLevel_sixteenWolves() {
            castAs(player, "Conjure Animals", 5, wolves(id("Wolf"), "16xCR1/4", 16), 11);

            List<EncounterParticipant> wolves = summoned();
            assertEquals(16, wolves.size());
            EncounterParticipant first = wolves.getFirst();
            assertEquals(ParticipantType.COMPANION, first.getParticipantType());
            assertEquals("Wolf 1", first.getDisplayName());
            assertEquals("Wolf 16", wolves.getLast().getDisplayName());
            // d20 11 + DEX 15's +2, one roll for the group, each its own turn
            assertTrue(wolves.stream().allMatch(w -> w.getInitiative() == 13));
            assertEquals(1, wolves.stream().map(w -> w.getSummon().groupId()).distinct().count());
            assertTrue(wolves.stream().allMatch(w -> player.equals(w.getControlledByUserId())));
            assertEquals("fey", first.getSummon().creatureType());
            assertEquals("fey", CreatureStats.creatureType(first));
            assertEquals(11, first.getHpMax());
            assertEquals(druid.getId(), first.getSummonedByParticipantId());

            SpellEffectEntry effect = effect("Conjure Animals");
            assertEquals(SummonSpec.Status.PLACED, effect.summon().status());
            assertEquals(2, effect.summon().multiplier());
            assertEquals(druid.getConcentration().id(), effect.concentrationInstanceId());
            assertEquals(effect.id(), first.getSummon().effectId());
            assertEquals(1, druid.getSpellSlotsCurrent().get("5").used());
            assertEquals(1, f.entries(CombatActionType.SUMMON).size());
        }

        @Test
        @DisplayName("A beast over the option's challenge rating, or the wrong count, is a 400 that spends nothing")
        void badChoice_spendsNothing() {
            CastSpellRequest tooStrong = cast("Conjure Animals", 3, wolves(id("Brown Bear"), "8xCR1/4", 8));
            assertThrows(ValidationException.class, () -> f.spellcasting.castSpell(f.encounterId(), tooStrong,
                    druid.getId(), player));
            CastSpellRequest tooFew = cast("Conjure Animals", 3, wolves(id("Wolf"), "8xCR1/4", 7));
            assertThrows(ValidationException.class, () -> f.spellcasting.castSpell(f.encounterId(), tooFew,
                    druid.getId(), player));
            assertEquals(0, druid.getSpellSlotsCurrent().get("3").used());
            assertNull(druid.getConcentration());
            assertTrue(summoned().isEmpty());
        }

        @Test
        @DisplayName("With the campaign's setting on, the cast waits for the DM, who chooses the creatures")
        void dmChooses_thenDmPlaces() {
            f.campaign.setDmChoosesConjuredCreatures(true);
            castAs(player, "Conjure Animals", 3, new SummonChoice("4xCR1/2", List.of(), null, null, null));

            SpellEffectEntry waiting = effect("Conjure Animals");
            assertTrue(waiting.summon().awaitingDm());
            assertEquals("4xCR1/2", waiting.summon().optionId());
            assertTrue(summoned().isEmpty());

            f.dice.script(9);
            f.inTransaction(() -> f.summons.place(f.encounterId(), new PlaceSummonRequest(waiting.id(), null,
                    List.of(new SummonCreature(id("Wolf"), 4)), null, null, null), f.dmUserId));
            assertEquals(4, summoned().size());
            assertEquals(SummonSpec.Status.PLACED, effect("Conjure Animals").summon().status());
        }

        @Test
        @DisplayName("Players can't fill a summoning that waits for the DM")
        void place_dmOnly() {
            f.campaign.setDmChoosesConjuredCreatures(true);
            castAs(player, "Conjure Animals", 3, null);
            UUID effectId = effect("Conjure Animals").id();
            assertThrows(com.tabletophelper.shared.ForbiddenException.class, () -> f.summons.place(f.encounterId(),
                    new PlaceSummonRequest(effectId, "8xCR1/4", List.of(new SummonCreature(id("Wolf"), 8)), null,
                            null, null), player));
        }
    }

    @Nested
    @DisplayName("Summon Lesser Demons (XGE p.167)")
    class LesserDemons {

        @Test
        @DisplayName("The cast rolls the d6 and waits; the DM's demons are the DM's, hostile, and replaceable")
        void rollsThenDmPlaces() {
            castAs(player, "Summon Lesser Demons", 3, null, 5);
            SpellEffectEntry waiting = effect("Summon Lesser Demons");
            assertEquals(5, waiting.summon().roll());
            assertEquals("8xCR1/4", waiting.summon().optionId());

            f.dice.script(12);
            f.inTransaction(() -> f.summons.place(f.encounterId(), new PlaceSummonRequest(waiting.id(), null,
                    List.of(new SummonCreature(id("Dretch"), 8)), null, null, null), f.dmUserId));
            List<EncounterParticipant> demons = summoned();
            assertEquals(8, demons.size());
            assertTrue(demons.stream().allMatch(d -> d.getControlledByUserId() == null));

            // The DM replaces them: the old ones disappear, and the summoning goes on
            f.dice.script(4);
            f.inTransaction(() -> f.summons.place(f.encounterId(), new PlaceSummonRequest(waiting.id(), "2xCR1",
                    List.of(new SummonCreature(id("Dretch"), 2)), null, null, null), f.dmUserId));
            assertEquals(2, summoned().size());
            assertEquals(8, f.entries(CombatActionType.SUMMON_END).size());
            assertNotNull(druid.getConcentration());
        }
    }

    @Nested
    @DisplayName("When the summoning ends")
    class Ending {

        @BeforeEach
        void conjure() {
            castAs(player, "Conjure Animals", 3, wolves(id("Wolf"), "8xCR1/4", 8), 11);
        }

        @Test
        @DisplayName("Losing concentration dismisses every creature and logs each going")
        void concentrationLost() {
            f.inTransaction(() -> {
                f.effectApplier.endConcentration(f.encounter, druid, "a failed save");
                return null;
            });
            assertTrue(summoned().isEmpty());
            assertEquals(8, f.entries(CombatActionType.SUMMON_END).size());
            assertTrue(EffectApplier.effects(druid).isEmpty());
        }

        @Test
        @DisplayName("A wolf at 0 HP disappears; the rest stay")
        void creatureAtZero() {
            EncounterParticipant wolf = summoned().getFirst();
            DamageRequest damage = new DamageRequest();
            damage.setTargetId(wolf.getId());
            damage.setAmount(20);
            f.inTransaction(() -> f.hitPoints.applyDamage(f.encounterId(), damage, null, f.dmUserId));
            assertTrue(wolf.getDismissed());
            assertEquals(7, summoned().size());
            assertTrue(f.descriptions().contains("Wolf 1 disappears (drops to 0 hit points)"));
        }

        @Test
        @DisplayName("The caster at 0 HP loses the beasts with its concentration but keeps a familiar")
        void casterAtZero_keepsFamiliar() {
            f.dice.script(10);
            f.inTransaction(() -> f.summons.companion(f.encounterId(), new CompanionSummonRequest(druid.getId(),
                    "Find Familiar", 1, null, List.of(new SummonCreature(id("Owl"), 1)), null, null, "fey", null),
                    f.dmUserId));
            DamageRequest damage = new DamageRequest();
            damage.setTargetId(druid.getId());
            damage.setAmount(30);
            f.inTransaction(() -> f.hitPoints.applyDamage(f.encounterId(), damage, null, f.dmUserId));

            assertEquals(LifeState.DYING, druid.getLifeState());
            List<EncounterParticipant> left = summoned();
            assertEquals(1, left.size());
            assertEquals("Owl", left.getFirst().getDisplayName());
            assertEquals("fey", CreatureStats.creatureType(left.getFirst()));
        }

        @Test
        @DisplayName("The DM's dismissal keeps the summoning; undo brings the creature back")
        void dismiss_andUndo() {
            EncounterParticipant wolf = summoned().getFirst();
            f.inTransaction(() -> f.summons.dismiss(f.encounterId(), new DismissSummonsRequest(List.of(wolf.getId())),
                    f.dmUserId));
            assertTrue(wolf.getDismissed());
            assertNotNull(druid.getConcentration());

            f.inTransaction(() -> f.undoService.undo(f.encounterId(),
                    new UndoRequest(f.commands.getLast().getId(), 1), f.dmUserId));
            assertFalse(wolf.getDismissed());
            assertEquals(8, summoned().size());
        }

        @Test
        @DisplayName("A dismissed creature is no longer a participant commands can name")
        void dismissed_notTargetable() {
            EncounterParticipant wolf = summoned().getFirst();
            f.inTransaction(() -> f.summons.dismiss(f.encounterId(), new DismissSummonsRequest(List.of(wolf.getId())),
                    f.dmUserId));
            assertThrows(ValidationException.class, () -> CombatAccess.participant(f.encounter, wolf.getId()));
        }
    }

    @Nested
    @DisplayName("Undo (M14)")
    class Undo {

        @Test
        @DisplayName("Undoing the cast removes the creatures and gives back the slot and the concentration")
        void undoCast() {
            castAs(player, "Conjure Animals", 3, wolves(id("Wolf"), "8xCR1/4", 8), 11);
            assertEquals(8, summoned().size());
            assertEquals(8, f.commands.getLast().getBeforeState().createdParticipantIds().size());

            f.inTransaction(() -> f.undoService.undo(f.encounterId(),
                    new UndoRequest(f.commands.getLast().getId(), 1), f.dmUserId));
            assertTrue(f.encounter.getParticipants().stream().noneMatch(p -> p.getSummon() != null));
            assertEquals(0, druid.getSpellSlotsCurrent().get("3").used());
            assertNull(druid.getConcentration());
            assertTrue(EffectApplier.effects(druid).isEmpty());
        }
    }

    @Nested
    @DisplayName("Tasha's spirits (TCE p.109)")
    class Spirits {

        @Test
        @DisplayName("A land beast at 4th: AC 15, 40 HP, two Maul attacks at the caster's +5, after the druid's turn")
        void bestialLand_fourthLevel() {
            castAs(player, "Summon Beast", 4, new SummonChoice(null, null, "Land", null, null));
            EncounterParticipant spirit = summoned().getFirst();
            assertEquals("Bestial Spirit (Land)", spirit.getDisplayName());
            assertEquals(15, spirit.getArmourClass());
            assertEquals(40, spirit.getHpMax());
            assertEquals(15, spirit.getInitiative());
            assertEquals(SummonRules.InitiativeMode.FOLLOWS_CASTER, spirit.getSummon().initiativeMode());
            MonsterTemplates templates = spirit.getActionTemplates();
            MonsterTemplates.Action multiattack = templates.find("ACTION", "Multiattack").orElseThrow();
            assertEquals(List.of("Maul", "Maul"), multiattack.multiattackComponents());
            MonsterTemplates.Action maul = templates.find("ACTION", "Maul").orElseThrow();
            assertEquals(5, maul.attackBonus());
            assertEquals("1d8 + 8", maul.effects().getFirst().damageDice());
            // Its turn comes right after the druid's
            List<UUID> order = EncounterTurns.order(f.encounter).stream().map(t -> t.combatant().id()).toList();
            assertEquals(order.indexOf(druid.getId()) + 1, order.indexOf(spirit.getId()));
        }

        @Test
        @DisplayName("The druid's player acts for the spirit on its turn, with its own templates")
        void playerActsForSpirit() {
            castAs(player, "Summon Beast", 2, new SummonChoice(null, null, "Air", null, null));
            EncounterParticipant spirit = summoned().getFirst();
            f.turnOf(spirit);
            // d20 15 + 5 hits AC 15; 1d8 + 6 rolls 4
            f.dice.script(15, 4);
            f.inTransaction(() -> f.monsterActions.monsterAction(f.encounterId(), new MonsterActionRequest(
                    spirit.getId(), "Maul", "ACTION", List.of(goblin.getId()), null, null, null, null), player));
            assertEquals(0, goblin.getHpCurrent());
        }

        @Test
        @DisplayName("A spirit needs its variant")
        void variantRequired() {
            CastSpellRequest request = cast("Summon Beast", 2, new SummonChoice(null, null, "Desert", null, null));
            assertThrows(ValidationException.class, () -> f.spellcasting.castSpell(f.encounterId(), request,
                    druid.getId(), player));
        }
    }

    @Nested
    @DisplayName("Companions the DM places")
    class Companions {

        @Test
        @DisplayName("A familiar can't attack: the player's attack is a 409, the DM's is logged as the DM's call")
        void familiarCantAttack() {
            f.dice.script(10);
            f.inTransaction(() -> f.summons.companion(f.encounterId(), new CompanionSummonRequest(druid.getId(),
                    "Find Familiar", null, null, List.of(new SummonCreature(id("Owl"), 1)), null, null, "celestial",
                    null), f.dmUserId));
            EncounterParticipant owl = summoned().getFirst();
            assertTrue(owl.getSummon().cannotAttack());
            assertEquals(player, owl.getControlledByUserId());
            f.turnOf(owl);
            MonsterActionRequest talons = new MonsterActionRequest(owl.getId(), "Talons", "ACTION",
                    List.of(goblin.getId()), null, null, null, null);
            assertThrows(ConflictException.class, () -> f.monsterActions.monsterAction(f.encounterId(), talons,
                    player));

            f.dice.script(18, 1);
            f.inTransaction(() -> f.monsterActions.monsterAction(f.encounterId(), talons, f.dmUserId));
            assertTrue(f.descriptions().stream().anyMatch(d -> d.contains("though a familiar can't attack")));
        }

        @Test
        @DisplayName("A second familiar replaces the first (PHB p.240)")
        void oneFamiliar() {
            f.dice.script(10);
            f.inTransaction(() -> f.summons.companion(f.encounterId(), new CompanionSummonRequest(druid.getId(),
                    "Find Familiar", null, null, List.of(new SummonCreature(id("Owl"), 1)), null, null, "fey", null),
                    f.dmUserId));
            f.dice.script(12);
            f.inTransaction(() -> f.summons.companion(f.encounterId(), new CompanionSummonRequest(druid.getId(),
                    "Find Familiar", null, null, List.of(new SummonCreature(id("Owl"), 1)), null, null, "fiend",
                    null), f.dmUserId));
            assertEquals(1, summoned().size());
            assertEquals("fiend", summoned().getFirst().getSummon().creatureType());
            assertEquals(1, EffectApplier.effects(druid).size());
        }

        @Test
        @DisplayName("Animate Dead's skeleton acts for the player only once commanded, until its turn ends")
        void skeleton_needsCommand() {
            f.dice.script(7, 9);
            f.inTransaction(() -> f.summons.companion(f.encounterId(), new CompanionSummonRequest(druid.getId(),
                    "Animate Dead", 4, null, List.of(new SummonCreature(id("Skeleton"), 2),
                    new SummonCreature(id("Zombie"), 1)), null, null, null, null), f.dmUserId));
            List<EncounterParticipant> undead = summoned();
            assertEquals(3, undead.size());
            // One roll per kind of identical creature (PHB p.189): d20 7 + 2 for the skeletons, 9 - 2 for the zombie
            assertEquals(9, undead.get(0).getInitiative());
            assertEquals(9, undead.get(1).getInitiative());
            assertEquals(7, undead.get(2).getInitiative());
            EncounterParticipant skeleton = undead.getFirst();
            MonsterActionRequest stab = new MonsterActionRequest(skeleton.getId(), "Shortsword", "ACTION",
                    List.of(goblin.getId()), null, null, null, null);
            f.turnOf(skeleton);
            assertThrows(ConflictException.class, () -> f.monsterActions.monsterAction(f.encounterId(), stab, player));

            f.turnOf(druid);
            f.inTransaction(() -> f.summons.command(f.encounterId(), new CommandSummonsRequest(druid.getId(),
                    List.of(skeleton.getId())), player));
            assertEquals(1, skeleton.getSummon().commandedRound());
            assertTrue(druid.getTurnEconomy().bonusActionUsed());

            f.turnOf(skeleton);
            f.dice.script(2, 3);
            f.inTransaction(() -> f.monsterActions.monsterAction(f.encounterId(), stab, player));
            // Its turn ends, and the command with it
            f.inTransaction(() -> f.turns.advanceTurn(f.encounterId(), f.dmUserId));
            assertNull(skeleton.getSummon().commandedRound());
        }

        @Test
        @DisplayName("Only the caster's own Animate Dead creatures can be commanded")
        void command_ownOnly() {
            assertThrows(ValidationException.class, () -> f.summons.command(f.encounterId(),
                    new CommandSummonsRequest(druid.getId(), List.of(goblin.getId())), player));
        }

        @Test
        @DisplayName("A familiar needs a type")
        void familiar_typeRequired() {
            assertThrows(ValidationException.class, () -> f.summons.companion(f.encounterId(),
                    new CompanionSummonRequest(druid.getId(), "Find Familiar", null, null,
                            List.of(new SummonCreature(id("Owl"), 1)), null, null, null, null), f.dmUserId));
        }
    }

    @Test
    @DisplayName("A non-summoning spell refuses a summon choice")
    void otherSpell_refusesChoice() {
        f.spell("Fire Bolt", 0, """
                {"spellName": "Fire Bolt", "spellLevel": 0, "deliveryMethod": "SPELL_ATTACK",
                 "spellAttackType": "RANGED", "effects": [{"effectType": "DAMAGE", "damageDice": "1d10"}]}""");
        CastSpellRequest request = cast("Fire Bolt", 0, new SummonChoice(null, List.of(), null, null, null));
        request.setTargetIds(new ArrayList<>(List.of(goblin.getId())));
        assertThrows(ValidationException.class, () -> f.spellcasting.castSpell(f.encounterId(), request,
                druid.getId(), player));
    }
}
