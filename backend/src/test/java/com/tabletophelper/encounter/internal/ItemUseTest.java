package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.dto.ClassSpellcasting;
import com.tabletophelper.character.state.InventoryItem;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.CastSpellRequest;
import com.tabletophelper.encounter.dto.CastSpellResponse;
import com.tabletophelper.encounter.dto.UndoRequest;
import com.tabletophelper.encounter.dto.UsableItems;
import com.tabletophelper.encounter.dto.UseItemRequest;
import com.tabletophelper.encounter.state.ItemUse;
import com.tabletophelper.encounter.state.SpellSource;
import com.tabletophelper.reference.Item;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;

/**
 * Item use in combat (D210): potions drunk or given as an action, a magic item's spells paid with its charges at its
 * own save DC, and spell scrolls read at the DMG p.200 numbers, with the check a scroll too high for its reader takes.
 */
class ItemUseTest {

    private static final String FIREBALL = """
            {"spellName": "Fireball", "spellLevel": 3, "deliveryMethod": "SAVING_THROW", "saveAbility": "DEX",
             "halfOnSave": true, "concentration": false, "components": {"verbal": true},
             "effects": [{"effectType": "DAMAGE", "damageDice": "8d6", "damageType": "fire",
               "upcastScaling": {"additionalDicePerLevel": "1d6"}}], "conditionsInflicted": null}""";

    private CombatFixture f;
    private EncounterParticipant aria;
    private EncounterParticipant bram;
    private EncounterParticipant ogre;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        f.recordCommands();
        aria = f.player("Aria", 30, 14);
        bram = f.player("Bram", 20, 16);
        ogre = f.monster("Ogre", 59, 11);
        f.started();
    }

    private Item item(Item item) {
        lenient().when(f.itemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        return item;
    }

    private void carry(EncounterParticipant pc, InventoryItem... entries) {
        pc.getCharacter().setEquipment(new ArrayList<>(List.of(entries)));
    }

    private void use(EncounterParticipant user, String itemName, EncounterParticipant drinker) {
        f.inTransaction(() -> f.itemUse.useItem(f.encounterId(), new UseItemRequest(user.getId(), itemName,
                drinker != null ? drinker.getId() : null), f.dmUserId));
    }

    @Nested
    @DisplayName("Potions")
    class Potions {

        @Test
        @DisplayName("Drinking a Potion of Healing takes the action and heals 2d4 + 2 (DMG p.187); the potion is recorded as used")
        void drinks() {
            aria.setHpCurrent(10);
            carry(aria, new InventoryItem("Potion of Healing", 2, null, null, null, null));
            f.dice.script(3, 4);

            use(aria, "Potion of Healing", null);

            assertEquals(19, aria.getHpCurrent());
            assertEquals(1, aria.getTurnEconomy().actionsUsed());
            assertThat(aria.getItemsUsed()).containsExactly(new ItemUse("Potion of Healing", null));
            assertThat(f.descriptions()).contains("Aria drinks a Potion of Healing (action, 1 left).",
                    "Aria regains 9 HP (Potion of Healing: 2d4(7) + 2 = 9)");
        }

        @Test
        @DisplayName("A Potion of Supreme Healing given to a dying ally heals 10d4 + 20 and brings them round")
        void givesToADyingAlly() {
            CombatFixture.downed(bram, LifeState.DYING, 1);
            Item supreme = item(Item.builder().id(UUID.randomUUID()).name("Potion of Supreme Healing").type("Potion")
                    .source("DMG").build());
            carry(aria, new InventoryItem("Potion of Supreme Healing", 1, null, supreme.getId(), null, null));
            f.dice.script(1, 1, 1, 1, 1, 1, 1, 1, 1, 1);

            use(aria, "Potion of Supreme Healing", bram);

            assertEquals(20, bram.getHpCurrent());
            assertEquals(LifeState.ALIVE, bram.getLifeState());
            assertThat(aria.getItemsUsed()).hasSize(1);
            assertThat(f.descriptions()).contains("Aria gives Bram a Potion of Supreme Healing (action, 0 left).");
        }

        @Test
        @DisplayName("Nobody drinks more potions than they carry: the second of one is a 409 that changes nothing")
        void notMoreThanCarried() {
            carry(aria, new InventoryItem("Potion of Healing", 1, null, null, null, null));
            f.dice.script(2, 2);
            use(aria, "Potion of Healing", null);
            CombatFixture.newTurn(aria);

            ConflictException refused = assertThrows(ConflictException.class, () -> use(aria, "potion of healing", null));

            assertEquals("Aria has no Potion of Healing left", refused.getMessage());
            assertThat(aria.getItemsUsed()).hasSize(1);
        }

        @Test
        @DisplayName("Undo gives the potion back with the hit points it healed (D176)")
        void undoGivesItBack() {
            aria.setHpCurrent(10);
            carry(aria, new InventoryItem("Potion of Healing", 1, null, null, null, null));
            f.dice.script(4, 4);
            use(aria, "Potion of Healing", null);
            assertEquals(20, aria.getHpCurrent());

            f.inTransaction(() -> f.undoService.undo(f.encounterId(),
                    new UndoRequest(f.commands.getLast().getId(), null), f.dmUserId));

            assertEquals(10, aria.getHpCurrent());
            assertThat(aria.getItemsUsed()).isEmpty();
            assertEquals(1, ItemSpells.left(aria, "Potion of Healing", null));
        }

        @Test
        @DisplayName("Any other potion is spent and logged with its description for the DM to apply")
        void otherPotion() {
            Item giant = item(Item.builder().id(UUID.randomUUID()).name("Potion of Hill Giant Strength").type("Potion")
                    .description("Your Strength score changes to 21 for 1 hour.").source("DMG").build());
            carry(aria, new InventoryItem("Potion of Hill Giant Strength", 1, null, giant.getId(), null, null));

            use(aria, "Potion of Hill Giant Strength", null);

            assertThat(aria.getItemsUsed()).hasSize(1);
            assertThat(f.descriptions()).contains("Aria drinks a Potion of Hill Giant Strength (action, 0 left); the DM "
                    + "applies its effect: Your Strength score changes to 21 for 1 hour.");
        }

        @Test
        @DisplayName("An item that isn't a potion, one not carried, a monster's and a player's second action are refused")
        void refusals() {
            carry(aria, new InventoryItem("Rope", 1, null, null, null, null),
                    new InventoryItem("Potion of Healing", 2, null, null, null, null));

            assertThat(assertThrows(ValidationException.class, () -> use(aria, "Rope", null)).getMessage())
                    .startsWith("Rope isn't a potion");
            assertEquals("Aria doesn't carry Potion of Flying",
                    assertThrows(ValidationException.class, () -> use(aria, "Potion of Flying", null)).getMessage());
            f.turnOf(ogre);
            assertThrows(ValidationException.class, () -> use(ogre, "Potion of Healing", null));

            f.turnOf(aria);
            UUID player = f.playerControlling(aria);
            f.dice.script(1, 1);
            f.inTransaction(() -> f.itemUse.useItem(f.encounterId(),
                    new UseItemRequest(aria.getId(), "Potion of Healing", null), player));
            assertThrows(ConflictException.class, () -> f.inTransaction(() -> f.itemUse.useItem(f.encounterId(),
                    new UseItemRequest(aria.getId(), "Potion of Healing", null), player)));
            assertThat(aria.getItemsUsed()).hasSize(1);
        }

        @Test
        @DisplayName("The usable items list each potion once, with what is left and a potion of healing's dice")
        void listsPotions() {
            carry(aria, new InventoryItem("Potion of Healing", 2, null, null, null, null),
                    new InventoryItem("Potion of Greater Healing", 1, null, null, null, null),
                    new InventoryItem("Rope", 1, null, null, null, null));
            f.dice.script(1, 1);
            use(aria, "Potion of Healing", null);

            UsableItems usable = f.itemUse.usableItems(f.encounterId(), aria.getId(), f.dmUserId);

            assertThat(usable.potions()).containsExactly(
                    new UsableItems.Potion("Potion of Healing", 1, "2d4 + 2", null),
                    new UsableItems.Potion("Potion of Greater Healing", 1, "4d4 + 4", null));
        }
    }

    @Nested
    @DisplayName("Charged items")
    class ChargedItems {

        private Item wand;

        @BeforeEach
        void wand() {
            f.spell("Fireball", 3, FIREBALL);
            wand = item(Item.builder().id(UUID.randomUUID()).name("Wand of Fireballs").source("DMG").type("Wand")
                    .charges(7).recharge("dawn").rechargeAmount("1d6 + 1").requiresAttunement(true).spellSaveDc(15)
                    .attachedSpells("{\"charges\":{\"1\":[\"fireball\"]}}")
                    .description("This wand has 7 charges. While holding it, you can use an action to expend 1 or more "
                            + "of its charges to cast the fireball spell (save DC 15) from it. For 1 charge, you cast "
                            + "the 3rd-level version of the spell. You can increase the spell slot level by one for "
                            + "each additional charge you expend.")
                    .build());
            carry(aria, new InventoryItem("Wand of Fireballs", 1, null, wand.getId(), null, true));
            aria.setResourcePoolsCurrent(new ArrayList<>(List.of(
                    ResourcePoolEntry.magicItem("Wand of Fireballs", 7, "dawn", "1d6 + 1"))));
            aria.setSpellSaveDc(12);
        }

        private CastSpellResponse cast(int level) {
            CastSpellRequest request = new CastSpellRequest();
            request.setSpellName("Fireball");
            request.setSpellSource(SpellSource.ITEM);
            request.setItemName("Wand of Fireballs");
            request.setSlotLevel(level);
            request.setTargetIds(List.of(ogre.getId()));
            return f.inTransaction(() -> f.spellcasting.castSpell(f.encounterId(), request, aria.getId(), f.dmUserId));
        }

        @Test
        @DisplayName("Fireball from a Wand of Fireballs at 4th level costs 2 charges and uses the wand's DC 15, not a Manual Modifier")
        void castsAtTheWandsDc() {
            // 9d6 of fire, then the ogre's DEX save of 14 against the wand's 15
            f.dice.script(1, 1, 1, 1, 1, 1, 1, 1, 1, 14);

            CastSpellResponse response = cast(4);

            assertEquals(SpellSource.ITEM, response.getSpellSource());
            assertEquals(4, response.getSlotLevelUsed());
            assertEquals(50, ogre.getHpCurrent());
            assertEquals(5, ItemSpells.pool(aria, wand).currentUses());
            assertThat(f.descriptions()).contains("Aria expends 2 charges of Wand of Fireballs (5/7 remaining)");
            assertThat(String.join("\n", f.descriptions())).contains("DC 15").doesNotContain("Manual Modifier");
        }

        @Test
        @DisplayName("A cast its charges can't pay for is a 409, and a level the wand can't reach a 400; neither spends anything")
        void refusals() {
            aria.setResourcePoolsCurrent(new ArrayList<>(List.of(
                    ResourcePoolEntry.magicItem("Wand of Fireballs", 7, "dawn", "1d6 + 1").withCurrentUses(1))));

            assertEquals("Wand of Fireballs has 1 of 7 charges left, and Fireball at level 4 needs 2",
                    assertThrows(ConflictException.class, () -> cast(4)).getMessage());
            assertThat(assertThrows(ValidationException.class, () -> cast(2)).getMessage())
                    .isEqualTo("Wand of Fireballs casts Fireball at level 3 to 9");
            assertEquals(1, ItemSpells.pool(aria, wand).currentUses());
        }

        @Test
        @DisplayName("An item requiring attunement can't be used unattuned, and the usable items say why")
        void needsAttunement() {
            carry(aria, new InventoryItem("Wand of Fireballs", 1, null, wand.getId(), null, null));

            assertEquals("Aria must be attuned to Wand of Fireballs to use its charges",
                    assertThrows(ValidationException.class, () -> cast(3)).getMessage());
            UsableItems.ChargedItem listed = f.itemUse.usableItems(f.encounterId(), aria.getId(), f.dmUserId)
                    .chargedItems().getFirst();
            assertEquals("Aria must be attuned to Wand of Fireballs to use its charges", listed.unavailableReason());
            assertThat(listed.spells().getFirst().sources().getFirst().levels()).isEmpty();
        }

        @Test
        @DisplayName("The usable items list the wand's spell with each level's charge cost and its own DC")
        void listsTheWand() {
            UsableItems.ChargedItem listed = f.itemUse.usableItems(f.encounterId(), aria.getId(), f.dmUserId)
                    .chargedItems().getFirst();

            assertEquals(15, listed.saveDc());
            assertEquals(7, listed.chargesLeft());
            var source = listed.spells().getFirst().sources().getFirst();
            assertEquals(SpellSource.ITEM, source.source());
            assertThat(source.levels()).containsExactly(3, 4, 5, 6, 7, 8, 9);
            assertEquals(2, source.chargeCosts().get("4"));
            assertEquals("Wand of Fireballs", source.itemName());
        }

        @Test
        @DisplayName("A spell the spells table doesn't list is refused cleanly, for the DM to resolve")
        void unknownSpell() {
            Item odd = item(Item.builder().id(UUID.randomUUID()).name("Odd Wand").source("X").charges(3)
                    .attachedSpells("{\"charges\":{\"1\":[\"glitterdust\"]}}").build());
            carry(aria, new InventoryItem("Odd Wand", 1, null, odd.getId(), null, null));
            aria.setResourcePoolsCurrent(new ArrayList<>(List.of(ResourcePoolEntry.magicItem("Odd Wand", 3, null, null))));
            CastSpellRequest request = new CastSpellRequest();
            request.setSpellName("glitterdust");
            request.setSpellSource(SpellSource.ITEM);
            request.setItemName("Odd Wand");
            request.setSlotLevel(0);
            request.setTargetIds(List.of(ogre.getId()));

            assertThat(assertThrows(ValidationException.class, () -> f.inTransaction(() ->
                    f.spellcasting.castSpell(f.encounterId(), request, aria.getId(), f.dmUserId))).getMessage())
                    .isEqualTo("glitterdust isn't in the spells table, so the DM resolves Odd Wand's spell");
        }

        @Test
        @DisplayName("A Wand of Wonder's spell comes from its d100 table (DMG p.212), so the player can't pick Fireball from it: none of its spells is offered or cast, its charges left to its pool for the DM")
        void randomTableItems() {
            Item wonder = item(Item.builder().id(UUID.randomUUID()).name("Wand of Wonder").source("DMG").charges(7)
                    .recharge("dawn").rechargeAmount("1d6 + 1").requiresAttunement(true)
                    .attachedSpells("{\"charges\":{\"1\":[\"fireball\",\"slow\"]}}")
                    .description("This wand has 7 charges. While holding it, you can use an action to expend 1 of its "
                            + "charges and choose a target within 120 feet of you. The target can be a creature, an "
                            + "object, or a point in space. Roll d100 and consult the following table to discover what "
                            + "happens.\n[Table]")
                    .build());
            carry(aria, new InventoryItem("Wand of Wonder", 1, null, wonder.getId(), null, true));
            aria.setResourcePoolsCurrent(new ArrayList<>(List.of(
                    ResourcePoolEntry.magicItem("Wand of Wonder", 7, "dawn", "1d6 + 1"))));
            CastSpellRequest request = new CastSpellRequest();
            request.setSpellName("Fireball");
            request.setSpellSource(SpellSource.ITEM);
            request.setItemName("Wand of Wonder");
            request.setSlotLevel(0);
            request.setTargetIds(List.of(ogre.getId()));

            assertThat(f.itemUse.usableItems(f.encounterId(), aria.getId(), f.dmUserId).chargedItems()).isEmpty();
            assertEquals("Wand of Wonder doesn't cast Fireball", assertThrows(ValidationException.class,
                    () -> f.inTransaction(() -> f.spellcasting.castSpell(f.encounterId(), request, aria.getId(),
                            f.dmUserId))).getMessage());
            assertEquals(7, ItemSpells.pool(aria, wonder).currentUses());
        }

        @Test
        @DisplayName("Chance picks the spell of an item that casts \"at random\" or from a d100 table, and of no ordinary wand")
        void randomSpellDetection() {
            assertThat(ItemSpells.randomSpell(Item.builder().description("While holding it, you can use an action to "
                    + "expend 1 or more of its charges to cast either a green-flamed fireball or a blue lightning bolt "
                    + "at random from it (save DC 15).").build())).isTrue();
            assertThat(ItemSpells.randomSpell(Item.builder().description("Roll d100 and consult the following table "
                    + "to discover what happens.").build())).isTrue();
            assertThat(ItemSpells.randomSpell(wand)).isFalse();
            assertThat(ItemSpells.randomSpell(Item.builder().description("If you have multiple possible targets, you "
                    + "attack one at random.").build())).isFalse();
        }
    }

    @Nested
    @DisplayName("Spell scrolls")
    class Scrolls {

        private Item scroll;

        @BeforeEach
        void scroll() {
            Spell fireball = f.spell("Fireball", 3, FIREBALL);
            fireball.setClasses("[\"Sorcerer\", \"Wizard\"]");
            scroll = item(Item.builder().id(UUID.randomUUID()).name("Spell Scroll (3rd Level)").type("Scroll")
                    .source("DMG").spellScrollLevel(3).build());
            carry(aria, new InventoryItem("Spell Scroll (3rd Level)", 1, null, scroll.getId(), null, null, "Fireball"));
        }

        private void wizard(int highestSpellLevel) {
            lenient().when(f.characterService.spellcasting(aria.getCharacter())).thenReturn(List.of(
                    new ClassSpellcasting("Wizard", null, "Wizard", 3, "INT", 12, 4, highestSpellLevel, 3, null, 6,
                            false)));
        }

        private CastSpellResponse read() {
            CastSpellRequest request = new CastSpellRequest();
            request.setSpellName("Fireball");
            request.setSpellSource(SpellSource.SCROLL);
            request.setItemName("Spell Scroll (3rd Level)");
            request.setSlotLevel(3);
            request.setTargetIds(List.of(ogre.getId()));
            return f.inTransaction(() -> f.spellcasting.castSpell(f.encounterId(), request, aria.getId(), f.dmUserId));
        }

        @Test
        @DisplayName("A wizard who can cast 3rd-level spells reads it at DC 15 (DMG p.200), and the scroll is used up")
        void reads() {
            wizard(3);
            f.dice.script(1, 1, 1, 1, 1, 1, 1, 1, 14);

            CastSpellResponse response = read();

            assertEquals(SpellSource.SCROLL, response.getSpellSource());
            assertEquals(51, ogre.getHpCurrent());
            assertThat(aria.getItemsUsed()).containsExactly(new ItemUse("Spell Scroll (3rd Level)", "Fireball"));
            assertThat(String.join("\n", f.descriptions())).contains("DC 15");
            assertEquals(0, ItemSpells.left(aria, "Spell Scroll (3rd Level)", "Fireball"));
        }

        @Test
        @DisplayName("A 3rd-level wizard reading it rolls a DC 13 Intelligence check: a failure loses the spell and the scroll")
        void failedCheck() {
            wizard(2);
            // INT 10: d20 of 12 + 0 = 12 against 13
            f.dice.script(12);

            CastSpellResponse response = read();

            assertEquals(59, ogre.getHpCurrent());
            assertThat(aria.getItemsUsed()).hasSize(1);
            assertThat(response.getResultSummary()).contains("a DC 13 Intelligence check: d20(12) + 0 = 12, a failure")
                    .contains("the spell disappears from the scroll with no effect");
        }

        @Test
        @DisplayName("Passing the check casts the spell")
        void passedCheck() {
            wizard(2);
            f.dice.script(13, 1, 1, 1, 1, 1, 1, 1, 1, 14);

            read();

            assertEquals(51, ogre.getHpCurrent());
            assertThat(f.descriptions()).anyMatch(d -> d.contains("d20(13) + 0 = 13, a success: they cast it."));
        }

        @Test
        @DisplayName("A character without the spell on a class's list can't read it; the usable items say why (DMG p.200)")
        void notOnTheList() {
            lenient().when(f.characterService.spellcasting(aria.getCharacter())).thenReturn(List.of(
                    new ClassSpellcasting("Cleric", null, "Cleric", 5, "WIS", 13, 5, 3, 4, null, 8, false)));

            assertThat(assertThrows(ValidationException.class, this::read).getMessage())
                    .isEqualTo("Fireball isn't on any of Aria's classes' spell lists, so they can't read the scroll "
                            + "(DMG p.200)");
            assertThat(aria.getItemsUsed()).isEmpty();
            UsableItems.Scroll listed = f.itemUse.usableItems(f.encounterId(), aria.getId(), f.dmUserId)
                    .scrolls().getFirst();
            assertEquals(15, listed.saveDc());
            assertEquals(7, listed.attackBonus());
            assertThat(listed.spell().unavailableReason()).isNotNull();
        }

        @Test
        @DisplayName("The DMG p.200 table: cantrip to 2nd DC 13 +5, 3rd–4th 15 +7, 5th–6th 17 +9, 7th–8th 18 +10, 9th 19 +11")
        void table() {
            int[] dc = {13, 13, 13, 15, 15, 17, 17, 18, 18, 19};
            int[] attack = {5, 5, 5, 7, 7, 9, 9, 10, 10, 11};
            for (int level = 0; level <= 9; level++) {
                assertEquals(dc[level], ItemSpells.scrollSaveDc(level));
                assertEquals(attack[level], ItemSpells.scrollAttackBonus(level));
            }
        }
    }
}
