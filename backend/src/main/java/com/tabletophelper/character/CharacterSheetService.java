package com.tabletophelper.character;

import com.tabletophelper.campaign.AccessPolicy;
import com.tabletophelper.campaign.Campaign;
import com.tabletophelper.campaign.CampaignRepository;
import com.tabletophelper.character.dto.CharacterCampaignRequest;
import com.tabletophelper.character.dto.CharacterDetailsRequest;
import com.tabletophelper.character.dto.CharacterResponse;
import com.tabletophelper.character.dto.HitPointChangeRequest;
import com.tabletophelper.character.dto.InventoryRequest;
import com.tabletophelper.character.dto.PoolUseRequest;
import com.tabletophelper.character.state.InventoryItem;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.resourcepool.ResourcePoolService;
import com.tabletophelper.rules.character.CurrentHitPoints;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.NotFoundException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static com.tabletophelper.character.CharacterUpdates.orEmpty;

/**
 * The commands a player gives their character's sheet between rests and levels (D158): its details, its campaign
 * and inventory, damage, healing and temporary hit points, and the spell slots and pool uses it spends and gets
 * back. Each starts in CharacterCommands, which checks that the user owns the character and locks its row, and each
 * answers with the character as the server then works it out. None is refused during an encounter: combat changes the
 * participant, not the sheet, and Write-back applies its changes when the encounter ends (D166). The hit point commands
 * are refused for a character who died, until the DM revives it.
 */
@Service
@RequiredArgsConstructor
public class CharacterSheetService {

    private final CharacterCommands characterCommands;
    private final CampaignRepository campaignRepository;
    private final AccessPolicy accessPolicy;
    private final InventoryChecks inventoryChecks;

    // ── Details, campaign and inventory ──────────────────────────

    /** Sets the details given, which no rule reads; a field left out keeps its value. */
    @Transactional
    public CharacterResponse updateDetails(UUID characterId, CharacterDetailsRequest request, UUID userId) {
        return characterCommands.run(characterId, userId, character -> {
            if (request.name() != null) {
                if (request.name().isBlank()) {
                    throw new ValidationException("A character needs a name");
                }
                character.setName(request.name().trim());
            }
            setIfGiven(request.experiencePoints(), character::setExperiencePoints);
            setIfGiven(request.alignment(), character::setAlignment);
            setIfGiven(request.personalityTraits(), character::setPersonalityTraits);
            setIfGiven(request.ideals(), character::setIdeals);
            setIfGiven(request.bonds(), character::setBonds);
            setIfGiven(request.flaws(), character::setFlaws);
            setIfGiven(request.notes(), character::setNotes);
            setIfGiven(request.portraitUrl(), character::setPortraitUrl);
        });
    }

    /** Puts the character in a campaign its owner has joined, or takes it out of the one it is in. */
    @Transactional
    public CharacterResponse setCampaign(UUID characterId, CharacterCampaignRequest request, UUID userId) {
        return characterCommands.run(characterId, userId, character -> {
            if (request.campaignId() == null) {
                character.setCampaign(null);
                return;
            }
            Campaign campaign = campaignRepository.findById(request.campaignId())
                    .orElseThrow(() -> new ValidationException("Campaign not found"));
            accessPolicy.requireMember(campaign.getId(), userId);
            character.setCampaign(campaign);
        });
    }

    /** Replaces the character's inventory and coins (D164: an entry's item link must name an item). */
    @Transactional
    public CharacterResponse updateInventory(UUID characterId, InventoryRequest request, UUID userId) {
        return characterCommands.run(characterId, userId, character -> {
            List<InventoryItem> equipment = inventoryChecks.checked(request.equipment());
            character.setCurrency(InventoryChecks.checked(request.currency()));
            character.setEquipment(equipment);
        });
    }

    // ── Hit points ───────────────────────────────────────────────

    /**
     * Damage, healing or temporary hit points (PHB p.198): damage comes off the temporary hit points first and stops
     * at 0, healing stops at the maximum, and temporary hit points keep the larger amount. A dead character's hit points
     * stay at 0 until the DM revives it, as combat treats the dead (D161, D166).
     *
     * @throws ConflictException for a dead character
     */
    @Transactional
    public CharacterResponse changeHitPoints(UUID characterId, HitPointChangeRequest request, UUID userId) {
        return characterCommands.run(characterId, userId, character -> {
            CharacterCommands.requireAlive(character, switch (request.kind()) {
                case DAMAGE -> "take damage";
                case HEALING -> "be healed";
                case TEMPORARY -> "gain temporary hit points";
            });
            CurrentHitPoints.Totals totals = totals(character);
            CurrentHitPoints.Totals changed = switch (request.kind()) {
                case DAMAGE -> CurrentHitPoints.damage(totals, request.amount());
                case HEALING -> CurrentHitPoints.heal(totals, request.amount());
                case TEMPORARY -> CurrentHitPoints.gainTemporary(totals, request.amount());
            };
            character.setHpCurrent(changed.current());
            character.setHpTemp(changed.temporary());
        });
    }

    /** The sheet's hit points, its current ones kept within the maximum. */
    static CurrentHitPoints.Totals totals(PlayerCharacter character) {
        int maximum = Math.max(1, character.getHpMax() != null ? character.getHpMax() : 1);
        int current = character.getHpCurrent() != null ? character.getHpCurrent() : maximum;
        int temporary = character.getHpTemp() != null ? character.getHpTemp() : 0;
        return new CurrentHitPoints.Totals(Math.max(0, Math.min(current, maximum)), maximum, Math.max(0, temporary));
    }

    // ── Spell slots ──────────────────────────────────────────────

    /**
     * Spends one of a spell level's slots, "1" to "9", or a pact slot, "pact_N".
     *
     * @throws NotFoundException for a slot the character doesn't have
     * @throws ConflictException when none of those slots is left
     */
    @Transactional
    public CharacterResponse spendSlot(UUID characterId, String slot, UUID userId) {
        return characterCommands.run(characterId, userId, character -> {
            SpellSlot slots = slot(character, slot);
            if (slots.remaining() <= 0) {
                throw new ConflictException(character.getName() + " has no " + slotName(slot) + " left");
            }
            putSlot(character, slot, slots.spend());
        });
    }

    /**
     * Gets back one spent slot of a spell level, or a pact slot.
     *
     * @throws NotFoundException for a slot the character doesn't have
     * @throws ConflictException when none of those slots has been spent
     */
    @Transactional
    public CharacterResponse restoreSlot(UUID characterId, String slot, UUID userId) {
        return characterCommands.run(characterId, userId, character -> {
            SpellSlot slots = slot(character, slot);
            if (slots.used() <= 0) {
                throw new ConflictException(character.getName() + " has spent no " + slotName(slot));
            }
            putSlot(character, slot, slots.restore());
        });
    }

    private static SpellSlot slot(PlayerCharacter character, String slot) {
        SpellSlot slots = character.getSpellSlots() != null ? character.getSpellSlots().get(slot) : null;
        if (slots == null) {
            throw new NotFoundException(character.getName() + " has no " + slotName(slot));
        }
        return slots;
    }

    private static void putSlot(PlayerCharacter character, String slot, SpellSlot changed) {
        Map<String, SpellSlot> slots = new LinkedHashMap<>(character.getSpellSlots());
        slots.put(slot, changed);
        character.setSpellSlots(slots);
    }

    // "level 3 spell slots" or "pact slots", as a key names them
    private static String slotName(String slot) {
        return slot.startsWith("pact_") ? "pact slots" : "level " + slot + " spell slots";
    }

    // ── Resource pools ───────────────────────────────────────────

    /**
     * Spends uses of one of the character's pools, one unless the request says more. An unlimited pool, such as Rage
     * at Barbarian 20, is never refused and keeps its uses. A pool that recovers another, such as a Psi Warrior's
     * psionic recovery, gives that one a use back for each it spends (D169). The rule is
     * {@link ResourcePoolService#spend}, which an encounter participant's pools follow too.
     *
     * @throws ValidationException for a pool the character doesn't have
     * @throws ConflictException when the pool hasn't that many uses left, or the pool it recovers hasn't that many
     *                           spent
     */
    @Transactional
    public CharacterResponse spendPool(UUID characterId, PoolUseRequest request, UUID userId) {
        int uses = uses(request);
        return characterCommands.run(characterId, userId, character -> character.setResourcePools(
                ResourcePoolService.spend(character.getResourcePools(), request.poolId(), uses, character.getName())));
    }

    /**
     * Gets back spent uses of one of the character's pools, one unless the request says more.
     *
     * @throws ValidationException for a pool the character doesn't have
     * @throws ConflictException when the pool hasn't spent that many uses
     */
    @Transactional
    public CharacterResponse recoverPool(UUID characterId, PoolUseRequest request, UUID userId) {
        int uses = uses(request);
        return characterCommands.run(characterId, userId, character -> changePool(character, request.poolId(), pool -> {
            if (pool.unlimited()) return pool;
            if (pool.currentUses() + uses > pool.maxUses()) {
                throw new ConflictException(pool.displayName() + " has " + pool.currentUses() + " of "
                        + pool.maxUses() + " uses left, so can't get " + uses + " back");
            }
            return pool.withCurrentUses(pool.currentUses() + uses);
        }));
    }

    private static int uses(PoolUseRequest request) {
        return request.amount() != null ? request.amount() : 1;
    }

    private static void changePool(PlayerCharacter character, String poolId,
                                    UnaryOperator<ResourcePoolEntry> change) {
        List<ResourcePoolEntry> pools = new ArrayList<>(orEmpty(character.getResourcePools()));
        for (int i = 0; i < pools.size(); i++) {
            if (poolId.equals(pools.get(i).poolId())) {
                pools.set(i, change.apply(pools.get(i)));
                character.setResourcePools(pools);
                return;
            }
        }
        throw new ValidationException(character.getName() + " has no pool " + poolId);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private static <T> void setIfGiven(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
