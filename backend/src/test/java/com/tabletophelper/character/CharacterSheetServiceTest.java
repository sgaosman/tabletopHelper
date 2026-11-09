package com.tabletophelper.character;

import com.tabletophelper.campaign.Campaign;
import com.tabletophelper.character.dto.CharacterCampaignRequest;
import com.tabletophelper.character.dto.CharacterDetailsRequest;
import com.tabletophelper.character.dto.CharacterResponse;
import com.tabletophelper.character.dto.FontOfMagicRequest;
import com.tabletophelper.character.dto.HitPointChangeRequest;
import com.tabletophelper.character.dto.HitPointChangeRequest.Kind;
import com.tabletophelper.character.dto.InventoryRequest;
import com.tabletophelper.character.dto.PoolUseRequest;
import com.tabletophelper.character.state.Currency;
import com.tabletophelper.character.state.InventoryItem;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.identity.User;
import com.tabletophelper.reference.ClassFeaturePool;
import com.tabletophelper.reference.Item;
import com.tabletophelper.reference.PoolLevelTable;
import com.tabletophelper.reference.ResourcePoolDefinition;
import com.tabletophelper.rules.character.FontOfMagic;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ForbiddenException;
import com.tabletophelper.shared.NotFoundException;
import com.tabletophelper.shared.ValidationException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sheet's intent commands (D158): details, campaign, inventory, hit points, spell slots and pools. They replaced
 * the PUT, whose cases in CharacterServiceTest went as follows:
 * <ul>
 *   <li>15.11, the PUT's constraints, became the constraints of these commands' requests.</li>
 *   <li>"The PUT changes only what is spent" became the slot and pool commands, which spend and get back one use at a
 *       time within the maximum the server works out; Hit Dice are spent only by a short rest (RestServiceTest).</li>
 *   <li>"Joining a campaign through the PUT needs the owner to be a member" is the campaign command's.</li>
 *   <li>15.10, the PUT changing an ability score, has no command: scores change by levelling, and the spell save DC
 *       following an Intelligence increase is now CharacterServiceTest's 15.10.</li>
 * </ul>
 */
class CharacterSheetServiceTest {

    private final CharacterFixture f = new CharacterFixture();
    private final UUID otherUserId = UUID.randomUUID();

    private PlayerCharacter fighter(int level) {
        return f.create(f.human, CharacterFixture.scores(), f.fighter, level);
    }

    private static CharacterDetailsRequest details(String name, Integer experiencePoints, String notes) {
        return new CharacterDetailsRequest(name, experiencePoints, null, null, null, null, null, notes, null);
    }

    private static HitPointChangeRequest hp(Kind kind, int amount) {
        return new HitPointChangeRequest(kind, amount);
    }

    @Nested
    @DisplayName("Details")
    class Details {

        @Test
        @DisplayName("The details given change, the rest keep their values, and the name is trimmed")
        void setsTheDetailsGiven() {
            PlayerCharacter pc = fighter(1);
            pc.setNotes("Owes the guild 10 gp");
            pc.setAlignment("Neutral Good");

            CharacterResponse response = f.sheet.updateDetails(pc.getId(),
                    new CharacterDetailsRequest("  Aria Swiftblade ", 900, null, "Curious", "Freedom", "My sister",
                            "Stubborn", null, "https://example.com/aria.png"), f.userId);

            assertThat(response.getName()).isEqualTo("Aria Swiftblade");
            assertThat(response.getExperiencePoints()).isEqualTo(900);
            assertThat(response.getPersonalityTraits()).isEqualTo("Curious");
            assertThat(response.getIdeals()).isEqualTo("Freedom");
            assertThat(response.getBonds()).isEqualTo("My sister");
            assertThat(response.getFlaws()).isEqualTo("Stubborn");
            assertThat(response.getPortraitUrl()).isEqualTo("https://example.com/aria.png");
            assertThat(response.getNotes()).isEqualTo("Owes the guild 10 gp");
            assertThat(response.getAlignment()).isEqualTo("Neutral Good");
        }

        @Test
        @DisplayName("An empty text clears a detail, but a character can't lose its name")
        void clearsButKeepsTheName() {
            PlayerCharacter pc = fighter(1);
            pc.setNotes("Owes the guild 10 gp");

            assertThat(f.sheet.updateDetails(pc.getId(), details(null, null, ""), f.userId).getNotes()).isEmpty();
            assertThatThrownBy(() -> f.sheet.updateDetails(pc.getId(), details(" ", null, null), f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("A character needs a name");
            assertThat(pc.getName()).isEqualTo("Aria");
        }

        @Test
        @DisplayName("15.11 The commands' requests refuse impossible values: negative experience, no hit points, no uses")
        void requestsRefuseImpossibleValues() {
            Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

            assertThat(violations(validator, details("A".repeat(201), -5, null)))
                    .contains("name", "experiencePoints");
            assertThat(violations(validator, new HitPointChangeRequest(null, 0))).contains("kind", "amount");
            assertThat(violations(validator, new PoolUseRequest(" ", 0))).contains("poolId", "amount");
            assertThat(violations(validator, new InventoryRequest(null, null))).contains("equipment", "currency");
        }

        private static <T> Set<String> violations(Validator validator, T request) {
            Set<String> paths = new HashSet<>();
            for (ConstraintViolation<T> violation : validator.validate(request)) {
                paths.add(violation.getPropertyPath().toString());
            }
            return paths;
        }

        @Test
        @DisplayName("Only the character's owner changes its sheet, and a stranger never locks its row (D155)")
        void onlyTheOwner() {
            PlayerCharacter pc = fighter(1);

            assertThatThrownBy(() -> f.sheet.updateDetails(pc.getId(), details("Bram", null, null), otherUserId))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("You do not own this character");
            assertThatThrownBy(() -> f.sheet.updateDetails(UUID.randomUUID(), details("Bram", null, null), f.userId))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Character not found");
            verify(f.characterRepository, never()).lockById(any());
            assertThat(pc.getName()).isEqualTo("Aria");
        }
    }

    @Nested
    @DisplayName("Campaign")
    class CampaignCommand {

        private final Campaign campaign = Campaign.builder().id(UUID.randomUUID()).name("Lost Mine")
                .dm(User.builder().id(UUID.randomUUID()).build()).inviteCode("ABCD2345").build();

        @Test
        @DisplayName("Joining a campaign needs the owner to be a member")
        void campaignNeedsMembership() {
            PlayerCharacter pc = fighter(1);
            when(f.campaignRepository.findById(campaign.getId())).thenReturn(Optional.of(campaign));

            assertThatThrownBy(() -> f.sheet.setCampaign(pc.getId(), new CharacterCampaignRequest(campaign.getId()),
                    f.userId))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("You are not a member of this campaign");
            assertThat(pc.getCampaign()).isNull();
        }

        @Test
        @DisplayName("A member's character joins the campaign, and leaves it with no campaign named")
        void joinsAndLeaves() {
            PlayerCharacter pc = fighter(1);
            when(f.campaignRepository.findById(campaign.getId())).thenReturn(Optional.of(campaign));
            when(f.campaignMemberRepository.existsByCampaignIdAndUserId(campaign.getId(), f.userId)).thenReturn(true);

            assertThat(f.sheet.setCampaign(pc.getId(), new CharacterCampaignRequest(campaign.getId()), f.userId)
                    .getCampaignId()).isEqualTo(campaign.getId());
            assertThat(f.sheet.setCampaign(pc.getId(), new CharacterCampaignRequest(null), f.userId).getCampaignId())
                    .isNull();
        }

        @Test
        @DisplayName("A campaign that doesn't exist is a 400")
        void unknownCampaignIs400() {
            PlayerCharacter pc = fighter(1);

            assertThatThrownBy(() -> f.sheet.setCampaign(pc.getId(), new CharacterCampaignRequest(UUID.randomUUID()),
                    f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Campaign not found");
        }
    }

    @Nested
    @DisplayName("Inventory")
    class Inventory {

        @Test
        @DisplayName("The inventory and coins are replaced, the names trimmed, and an entry linked to an item keeps the link")
        void replacesTheInventory() {
            PlayerCharacter pc = fighter(1);
            pc.setEquipment(List.of(new InventoryItem("Rope", 1, null, null, null, null)));
            UUID longsword = UUID.randomUUID();
            when(f.itemRepository.existsById(longsword)).thenReturn(true);

            CharacterResponse response = f.sheet.updateInventory(pc.getId(), new InventoryRequest(List.of(
                    new InventoryItem(" Longsword ", 1, "Family heirloom", longsword, true, null),
                    new InventoryItem("Torch", 0, null, null, null, null)), new Currency(0, 5, 0, 12, 1)), f.userId);

            assertThat(response.getEquipment()).containsExactly(
                    new InventoryItem("Longsword", 1, "Family heirloom", longsword, true, null),
                    new InventoryItem("Torch", 0, null, null, null, null));
            assertThat(response.getCurrency()).isEqualTo(new Currency(0, 5, 0, 12, 1));
        }

        @Test
        @DisplayName("An entry without a name, fewer than 0 of something, a link to no item or negative coins are a 400 (D164)")
        void refusesImpossibleEntries() {
            PlayerCharacter pc = fighter(1);
            UUID missing = UUID.randomUUID();

            assertThatThrownBy(() -> f.sheet.updateInventory(pc.getId(), inventory(
                    new InventoryItem(" ", 1, null, null, null, null)), f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Every inventory entry needs a name");
            assertThatThrownBy(() -> f.sheet.updateInventory(pc.getId(), inventory(
                    new InventoryItem("Arrows", -1, null, null, null, null)), f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("There can't be fewer than 0 of Arrows");
            assertThatThrownBy(() -> f.sheet.updateInventory(pc.getId(), inventory(
                    new InventoryItem("Vorpal Sword", 1, null, missing, null, null)), f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("The item Vorpal Sword is linked to doesn't exist");
            assertThatThrownBy(() -> f.sheet.updateInventory(pc.getId(),
                    new InventoryRequest(List.of(), new Currency(0, 0, 0, -1, 0)), f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("A character can't carry fewer than 0 coins of any kind");
            assertThat(pc.getEquipment()).isNull();
        }

        @Test
        @DisplayName("Equipping a second body armour or a second shield is a 400 that changes nothing; one of each, and armour carried unequipped, are fine (M13.5)")
        void oneArmourAndOneShield() {
            PlayerCharacter pc = fighter(1);
            Item chainMail = armour("Chain Mail", Item.ArmourCategory.HEAVY, 16);
            Item leather = armour("Leather Armor", Item.ArmourCategory.LIGHT, 11);
            Item shield = armour("Shield", Item.ArmourCategory.SHIELD, 2);
            Item sentinel = armour("Sentinel Shield", Item.ArmourCategory.SHIELD, 2);
            for (Item item : List.of(chainMail, leather, shield, sentinel)) {
                when(f.itemRepository.existsById(item.getId())).thenReturn(true);
                when(f.itemRepository.findById(item.getId())).thenReturn(Optional.of(item));
            }

            assertThatThrownBy(() -> f.sheet.updateInventory(pc.getId(), inventory(
                    linked(chainMail, true), linked(leather, true)), f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Only one suit of armour can be worn at a time: unequip Chain Mail before equipping "
                            + "Leather Armor");
            assertThatThrownBy(() -> f.sheet.updateInventory(pc.getId(), inventory(
                    linked(shield, true), linked(sentinel, true)), f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Only one shield can be equipped at a time: unequip Shield before equipping "
                            + "Sentinel Shield");
            assertThat(pc.getEquipment()).isNull();

            CharacterResponse response = f.sheet.updateInventory(pc.getId(), inventory(linked(chainMail, true),
                    linked(leather, false), linked(shield, true)), f.userId);
            assertThat(response.getEquipment()).hasSize(3);
        }

        private static Item armour(String name, Item.ArmourCategory category, int base) {
            return Item.builder().id(UUID.randomUUID()).name(name).source("PHB").armourCategory(category)
                    .armourClassBase(base).build();
        }

        private static InventoryItem linked(Item item, boolean equipped) {
            return new InventoryItem(item.getName(), 1, null, item.getId(), equipped, null);
        }

        private static InventoryRequest inventory(InventoryItem... entries) {
            return new InventoryRequest(List.of(entries), Currency.NONE);
        }
    }

    @Nested
    @DisplayName("Hit points")
    class HitPoints {

        @Test
        @DisplayName("Damage comes off temporary hit points first, and the rest off hit points, stopping at 0")
        void damage() {
            // Fighter 3 with CON 12: 11 + 2 × 7 = 25 hit points
            PlayerCharacter pc = fighter(3);
            pc.setHpTemp(5);

            CharacterResponse absorbed = f.sheet.changeHitPoints(pc.getId(), hp(Kind.DAMAGE, 3), f.userId);
            assertThat(absorbed.getHpCurrent()).isEqualTo(25);
            assertThat(absorbed.getHpTemp()).isEqualTo(2);

            CharacterResponse through = f.sheet.changeHitPoints(pc.getId(), hp(Kind.DAMAGE, 10), f.userId);
            assertThat(through.getHpCurrent()).isEqualTo(17);
            assertThat(through.getHpTemp()).isZero();

            assertThat(f.sheet.changeHitPoints(pc.getId(), hp(Kind.DAMAGE, 99), f.userId).getHpCurrent()).isZero();
        }

        @Test
        @DisplayName("Healing stops at the maximum and leaves temporary hit points alone")
        void healing() {
            PlayerCharacter pc = fighter(3);
            pc.setHpCurrent(10);
            pc.setHpTemp(4);

            CharacterResponse response = f.sheet.changeHitPoints(pc.getId(), hp(Kind.HEALING, 50), f.userId);

            assertThat(response.getHpCurrent()).isEqualTo(25);
            assertThat(response.getHpTemp()).isEqualTo(4);
        }

        @Test
        @DisplayName("New temporary hit points keep the larger of the old and new amounts (PHB p.198)")
        void temporaryHitPoints() {
            PlayerCharacter pc = fighter(3);
            pc.setHpTemp(6);

            assertThat(f.sheet.changeHitPoints(pc.getId(), hp(Kind.TEMPORARY, 4), f.userId).getHpTemp()).isEqualTo(6);
            assertThat(f.sheet.changeHitPoints(pc.getId(), hp(Kind.TEMPORARY, 9), f.userId).getHpTemp()).isEqualTo(9);
            assertThat(pc.getHpCurrent()).isEqualTo(25);
        }
    }

    @Nested
    @DisplayName("Spell slots")
    class SpellSlots {

        @Test
        @DisplayName("A slot is spent and got back one at a time, within the maximum the server works out")
        void spendsAndRestores() {
            // Wizard 3: four 1st-level slots and two 2nd
            PlayerCharacter pc = f.create(f.human, CharacterFixture.scores(), f.wizard, 3);

            assertThat(f.sheet.spendSlot(pc.getId(), "1", f.userId).getSpellSlots().get("1"))
                    .isEqualTo(new SpellSlot(4, 1));
            assertThat(f.sheet.spendSlot(pc.getId(), "1", f.userId).getSpellSlots().get("1"))
                    .isEqualTo(new SpellSlot(4, 2));
            assertThat(f.sheet.restoreSlot(pc.getId(), "1", f.userId).getSpellSlots())
                    .isEqualTo(Map.of("1", new SpellSlot(4, 1), "2", new SpellSlot(2, 0)));
        }

        @Test
        @DisplayName("Spending a slot with none left, or getting one back with none spent, is a 409")
        void noneLeftOrNoneSpent() {
            PlayerCharacter pc = f.create(f.human, CharacterFixture.scores(), f.warlock, 3);
            Map<String, SpellSlot> slots = new LinkedHashMap<>(pc.getSpellSlots());
            slots.put("pact_2", new SpellSlot(2, 2));
            pc.setSpellSlots(slots);

            assertThatThrownBy(() -> f.sheet.spendSlot(pc.getId(), "pact_2", f.userId))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Aria has no pact slots left");
            f.sheet.restoreSlot(pc.getId(), "pact_2", f.userId);
            f.sheet.restoreSlot(pc.getId(), "pact_2", f.userId);
            assertThatThrownBy(() -> f.sheet.restoreSlot(pc.getId(), "pact_2", f.userId))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Aria has spent no pact slots");
        }

        @Test
        @DisplayName("Flexible Casting out of combat: a 2nd-level slot for 2 points, then 5 points for a 3rd-level slot, which can be spent (PHB p.101)")
        void flexibleCasting() {
            PlayerCharacter noPoints = fighter(1);
            // Sorcerer 5: four 1st-level slots, three 2nd and two 3rd; 5 sorcery points, 3 left
            ResourcePoolDefinition points = ResourcePoolDefinition.builder().poolId(FlexibleCasting.SORCERY_POINTS)
                    .displayName("Sorcery Points").sourceType("CLASS").maxUsesFormula("sorcererLevel")
                    .resetOn("longRest").spendActionType("FREE").build();
            when(f.featurePoolRepository.findByCharacterClassIdAndSubclassIsNullAndMinLevelLessThanEqual(
                    any(UUID.class), anyInt())).thenReturn(List.of(ClassFeaturePool.builder()
                    .characterClass(f.sorcerer).minLevel(2).poolDefinition(points).build()));
            PlayerCharacter pc = f.create(f.human, CharacterFixture.scores(), f.sorcerer, 5);
            pc.setResourcePools(new ArrayList<>(List.of(pc.getResourcePools().getFirst().withCurrentUses(3))));

            CharacterResponse converted = f.sheet.fontOfMagic(pc.getId(),
                    new FontOfMagicRequest(FontOfMagic.Direction.SLOT_TO_POINTS, 2), f.userId);
            assertThat(converted.getSpellSlots().get("2")).isEqualTo(new SpellSlot(3, 1));
            assertThat(converted.getResourcePools().getFirst().currentUses()).isEqualTo(5);

            CharacterResponse created = f.sheet.fontOfMagic(pc.getId(),
                    new FontOfMagicRequest(FontOfMagic.Direction.POINTS_TO_SLOT, 3), f.userId);
            assertThat(created.getSpellSlots().get("3")).isEqualTo(new SpellSlot(2, 0, 1));
            assertThat(created.getResourcePools().getFirst().currentUses()).isZero();

            assertThat(f.sheet.spendSlot(pc.getId(), "3", f.userId).getSpellSlots().get("3"))
                    .isEqualTo(new SpellSlot(2, 1, 1));
            assertThatThrownBy(() -> f.sheet.fontOfMagic(pc.getId(),
                    new FontOfMagicRequest(FontOfMagic.Direction.POINTS_TO_SLOT, 1), f.userId))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Aria has 0 sorcery points, and a level 1 spell slot costs 2");
            assertThatThrownBy(() -> f.sheet.fontOfMagic(pc.getId(),
                    new FontOfMagicRequest(FontOfMagic.Direction.POINTS_TO_SLOT, 6), f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Flexible Casting creates spell slots of 1st to 5th level, not level 6");
            assertThatThrownBy(() -> f.sheet.fontOfMagic(noPoints.getId(),
                    new FontOfMagicRequest(FontOfMagic.Direction.SLOT_TO_POINTS, 1), f.userId))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Aria has no sorcery points (Font of Magic, Sorcerer 2)");
        }

        @Test
        @DisplayName("A slot the character doesn't have is a 404")
        void unknownSlotIs404() {
            PlayerCharacter pc = f.create(f.human, CharacterFixture.scores(), f.wizard, 1);

            assertThatThrownBy(() -> f.sheet.spendSlot(pc.getId(), "3", f.userId))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Aria has no level 3 spell slots");
            assertThatThrownBy(() -> f.sheet.spendSlot(fighter(1).getId(), "1", f.userId))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Aria has no level 1 spell slots");
        }
    }

    @Nested
    @DisplayName("Pools")
    class Pools {

        private PlayerCharacter withUses(PlayerCharacter pc, String poolId, int uses) {
            List<ResourcePoolEntry> pools = new ArrayList<>();
            for (ResourcePoolEntry pool : pc.getResourcePools()) {
                pools.add(pool.poolId().equals(poolId) ? pool.withCurrentUses(uses) : pool);
            }
            pc.setResourcePools(pools);
            return pc;
        }

        @Test
        @DisplayName("A pool's uses are spent and got back, one unless the request says more")
        void spendsAndRecovers() {
            // Fighter 2 has Action Surge's pool of one use
            PlayerCharacter pc = fighter(2);

            assertThat(f.sheet.spendPool(pc.getId(), new PoolUseRequest("class:fighter-action-surge", null), f.userId)
                    .getResourcePools().getFirst().currentUses()).isZero();
            assertThat(f.sheet.recoverPool(pc.getId(), new PoolUseRequest("class:fighter-action-surge", 1), f.userId)
                    .getResourcePools().getFirst().currentUses()).isEqualTo(1);
        }

        @Test
        @DisplayName("Spending more uses than are left, or getting back more than were spent, is a 409")
        void noMoreThanThePoolHas() {
            PlayerCharacter pc = withUses(fighter(2), "class:fighter-action-surge", 0);

            assertThatThrownBy(() -> f.sheet.spendPool(pc.getId(),
                    new PoolUseRequest("class:fighter-action-surge", 1), f.userId))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Action Surge has 0 of 1 uses left, not 1");
            assertThatThrownBy(() -> f.sheet.recoverPool(pc.getId(),
                    new PoolUseRequest("class:fighter-action-surge", 2), f.userId))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Action Surge has 0 of 1 uses left, so can't get 2 back");
        }

        @Test
        @DisplayName("A pool the character doesn't have is a 400")
        void unknownPoolIs400() {
            PlayerCharacter pc = fighter(2);

            assertThatThrownBy(() -> f.sheet.spendPool(pc.getId(), new PoolUseRequest("class:monk-ki", 1), f.userId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Aria has no pool class:monk-ki");
        }

        // A fighter whose class grants Psionic Energy dice, their recovery and an unlimited pool, as a Psi Warrior's
        // and Rage at Barbarian 20 are (D169)
        private PlayerCharacter psionicFighter() {
            ResourcePoolDefinition dice = ResourcePoolDefinition.builder().poolId("subclass:psi-warrior-psionic-energy")
                    .displayName("Psionic Energy Dice").sourceType("CLASS").maxUsesFormula("2*proficiencyBonus")
                    .resetOn("longRest").build();
            ResourcePoolDefinition recovery = ResourcePoolDefinition.builder()
                    .poolId("subclass:psi-warrior-psionic-recovery").displayName("Psionic Recovery")
                    .sourceType("CLASS").maxUsesFormula("1").resetOn("shortRest").spendActionType("BONUS_ACTION")
                    .metadata("{\"recovers\": \"subclass:psi-warrior-psionic-energy\"}").build();
            ResourcePoolDefinition unlimited = ResourcePoolDefinition.builder().poolId("class:test-unlimited")
                    .displayName("Unlimited").sourceType("CLASS").maxUsesFormula("6").resetOn("longRest")
                    .levelTable(new PoolLevelTable("Fighter", List.of(new PoolLevelTable.Row(1, null, null, null, true))))
                    .build();
            List<ClassFeaturePool> pools = List.of(dice, recovery, unlimited).stream()
                    .map(def -> ClassFeaturePool.builder().characterClass(f.fighter).minLevel(1).poolDefinition(def)
                            .build())
                    .toList();
            when(f.featurePoolRepository.findByCharacterClassIdAndSubclassIsNullAndMinLevelLessThanEqual(
                    any(UUID.class), anyInt())).thenReturn(pools);
            return fighter(3);
        }

        private static ResourcePoolEntry pool(CharacterResponse response, String poolId) {
            return response.getResourcePools().stream().filter(pool -> pool.poolId().equals(poolId)).findFirst()
                    .orElseThrow();
        }

        @Test
        @DisplayName("Spending the psionic recovery regains one expended Psionic Energy die, and is a 409 with none expended")
        void psionicRecoveryRegainsADie() {
            PlayerCharacter pc = withUses(psionicFighter(), "subclass:psi-warrior-psionic-energy", 1);

            CharacterResponse response = f.sheet.spendPool(pc.getId(),
                    new PoolUseRequest("subclass:psi-warrior-psionic-recovery", null), f.userId);

            assertThat(pool(response, "subclass:psi-warrior-psionic-energy").currentUses()).isEqualTo(2);
            assertThat(pool(response, "subclass:psi-warrior-psionic-recovery").currentUses()).isZero();

            PlayerCharacter full = psionicFighter();
            assertThatThrownBy(() -> f.sheet.spendPool(full.getId(),
                    new PoolUseRequest("subclass:psi-warrior-psionic-recovery", null), f.userId))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Psionic Energy Dice has 4 of 4 left, so Psionic Recovery has nothing to regain");
            assertThat(f.stored(full.getId()).getResourcePools()).allMatch(p -> p.currentUses() == p.maxUses());
        }

        @Test
        @DisplayName("An unlimited pool is never refused and keeps its uses, spent or recovered")
        void unlimitedPoolIsNeverRefused() {
            PlayerCharacter pc = psionicFighter();

            for (int i = 0; i < 10; i++) {
                f.sheet.spendPool(pc.getId(), new PoolUseRequest("class:test-unlimited", 3), f.userId);
            }
            CharacterResponse response = f.sheet.recoverPool(pc.getId(),
                    new PoolUseRequest("class:test-unlimited", 5), f.userId);

            assertThat(pool(response, "class:test-unlimited").currentUses()).isEqualTo(6);
            assertThat(pool(response, "class:test-unlimited").unlimited()).isTrue();
        }
    }
}
