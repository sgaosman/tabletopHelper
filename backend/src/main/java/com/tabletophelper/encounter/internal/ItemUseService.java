package com.tabletophelper.encounter.internal;

import com.tabletophelper.campaign.AccessPolicy;
import com.tabletophelper.character.state.InventoryItem;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterRepository;
import com.tabletophelper.encounter.EncounterService;
import com.tabletophelper.encounter.dto.EncounterResponse;
import com.tabletophelper.encounter.dto.SpellOption;
import com.tabletophelper.encounter.dto.UsableItems;
import com.tabletophelper.encounter.dto.UseItemRequest;
import com.tabletophelper.reference.Item;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.Rng;
import com.tabletophelper.rules.combat.ActionCost;
import com.tabletophelper.rules.combat.Charge;
import com.tabletophelper.rules.combat.Potions;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.NotFoundException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Using an item from a player character's inventory in combat (D210). A potion is drunk, or given to another creature,
 * as an action (PHB p.153): a potion of healing heals its drinker by its dice (DMG p.187), and any other potion is
 * logged with its description for the DM to apply. A magic item's spells and spell scrolls are cast through cast-spell
 * with the sources ITEM and SCROLL ({@link ItemSpells}); usableItems lists all three. A potion used is recorded on the
 * participant, so undo gives it back and Write-back takes it off the sheet when the encounter ends; nobody drinks more
 * than they carry.
 */
@Service
@RequiredArgsConstructor
public class ItemUseService {

    private final CombatAccess access;
    private final AccessPolicy accessPolicy;
    private final ActionEconomyKeeper economy;
    private final EffectApplier effectApplier;
    private final CombatLogWriter combatLog;
    private final ItemSpells itemSpells;
    private final Rng rng;
    private final EncounterRepository encounterRepository;
    private final EncounterService encounterService;

    /**
     * Drinks a potion, or gives it to the creature named, as an action on the user's turn: checked and charged to the
     * turn economy before anything is rolled or spent.
     *
     * @throws ValidationException for a monster, an item the character doesn't carry, or one that isn't a potion
     * @throws ConflictException   when every one carried has been used, the drinker is dead, or a player's turn has
     *                             no action left
     */
    @Transactional
    public EncounterResponse useItem(UUID encounterId, UseItemRequest request, UUID userId) {
        Encounter encounter = access.beginOnTurnForAction(encounterId, request.participantId(), userId);
        EncounterParticipant user = CombatAccess.participant(encounter, request.participantId());
        if (user.getCharacter() == null) {
            throw new ValidationException("Only a player character's items can be used: the DM resolves a monster's");
        }
        String name = request.itemName().trim();
        InventoryItem entry = ItemSpells.entry(user, name, null)
                .orElseThrow(() -> new ValidationException(user.getDisplayName() + " doesn't carry " + name));
        Item item = itemSpells.item(entry).orElse(null);
        Optional<Dice> healing = Potions.healing(entry.name()).or(() -> item != null
                ? Potions.healing(item.getName()) : Optional.empty());
        if (healing.isEmpty() && !potion(entry, item)) {
            throw new ValidationException(entry.name() + " isn't a potion: cast a magic item's spells or read a "
                    + "scroll through Cast Spell, and spend its charges for anything else through its pool");
        }
        if (ItemSpells.left(user, entry.name(), null) == 0) {
            throw new ConflictException(user.getDisplayName() + " has no " + entry.name() + " left");
        }
        EncounterParticipant drinker = request.targetId() != null
                ? CombatAccess.target(encounter, request.targetId(), userId) : user;
        CombatAccess.requireNotDead(List.of(drinker));

        boolean ownTurn = EncounterTurns.isCurrentTurn(encounter, user.getId());
        economy.charge(encounter, user, Charge.of(ActionCost.ACTION, ownTurn), null, userId,
                new ActionEconomyKeeper.Command(CombatActionType.STANDARD_ACTION,
                        user.getDisplayName() + "'s " + entry.name() + " (action)"));

        ItemSpells.useUp(user, entry.name(), null);
        String lead = user.getDisplayName() + (drinker == user ? " drinks " : " gives " + drinker.getDisplayName() + " ")
                + article(entry.name()) + entry.name() + " (action, " + ItemSpells.left(user, entry.name(), null)
                + " left)";
        if (healing.isPresent()) {
            Dice dice = healing.get();
            int rolled = dice.rollDice(rng);
            int amount = dice.total(rolled);
            combatLog.write(encounter, CombatActionType.STANDARD_ACTION, lead + ".", user, drinker);
            effectApplier.heal(encounter, user, drinker, amount, entry.name() + ": " + dice.count() + "d" + dice.sides()
                    + "(" + rolled + ") + " + dice.modifier() + " = " + amount);
        } else {
            String description = item != null && item.getDescription() != null ? item.getDescription()
                    : entry.description();
            combatLog.write(encounter, CombatActionType.STANDARD_ACTION, lead + "; the DM applies its effect"
                    + (description != null && !description.isBlank() ? ": " + shortened(description) : "."), user,
                    drinker);
        }
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    /**
     * The items a participant can use now (D210): the DM may read anyone's, a player only their own character's. It
     * changes nothing, so it needs no lock and any status will do.
     */
    @Transactional(readOnly = true)
    public UsableItems usableItems(UUID encounterId, UUID participantId, UUID userId) {
        Encounter encounter = encounterRepository.findById(encounterId)
                .orElseThrow(() -> new NotFoundException("Encounter not found"));
        UUID campaignId = encounter.getCampaign().getId();
        accessPolicy.requireMember(campaignId, userId);
        EncounterParticipant participant = CombatAccess.target(encounter, participantId, userId);
        accessPolicy.requireControls(campaignId, participant.getControlledByUserId(), userId);
        if (participant.getCharacter() == null) {
            return new UsableItems(List.of(), List.of(), List.of());
        }

        List<UsableItems.Potion> potions = new ArrayList<>();
        for (InventoryItem entry : ItemSpells.inventory(participant)) {
            if (entry.spell() != null || potions.stream().anyMatch(p -> p.name().equalsIgnoreCase(entry.name().trim()))) {
                continue;
            }
            Item item = itemSpells.item(entry).orElse(null);
            Optional<Dice> healing = Potions.healing(entry.name()).or(() -> item != null
                    ? Potions.healing(item.getName()) : Optional.empty());
            int left = ItemSpells.left(participant, entry.name(), null);
            if (left > 0 && (healing.isPresent() || potion(entry, item))) {
                potions.add(new UsableItems.Potion(entry.name().trim(), left, healing.map(ItemUseService::dice)
                        .orElse(null), item != null && item.getDescription() != null ? item.getDescription()
                        : entry.description()));
            }
        }

        List<UsableItems.ChargedItem> charged = new ArrayList<>();
        for (ItemSpells.ItemSpell spell : itemSpells.chargedSpells(participant)) {
            SpellOption option = itemSpells.option(participant, spell);
            UsableItems.ChargedItem existing = charged.stream()
                    .filter(c -> c.name().equalsIgnoreCase(spell.itemName().trim())).findFirst().orElse(null);
            if (existing != null) {
                existing.spells().add(option);
                continue;
            }
            ResourcePoolEntry pool = ItemSpells.pool(participant, spell.item());
            List<SpellOption> spells = new ArrayList<>();
            spells.add(option);
            String reason = itemSpells.unavailable(participant, spell.itemName(), spell.item());
            charged.add(new UsableItems.ChargedItem(spell.itemName().trim(), pool != null ? pool.currentUses() : 0,
                    pool != null ? pool.maxUses() : spell.item().getCharges() != null ? spell.item().getCharges() : 0,
                    spell.item().getSpellSaveDc(), spell.item().getSpellAttackBonus(),
                    reason, spells));
        }

        List<UsableItems.Scroll> scrolls = new ArrayList<>();
        for (ItemSpells.Scroll scroll : itemSpells.scrolls(participant)) {
            int level = scroll.item().getSpellScrollLevel();
            Optional<ItemSpells.ScrollCheck> check = itemSpells.scrollCheck(participant, scroll.spell());
            scrolls.add(new UsableItems.Scroll(scroll.entry().name().trim(), itemSpells.option(participant, scroll),
                    level, ItemSpells.scrollSaveDc(level), ItemSpells.scrollAttackBonus(level),
                    check.map(ItemSpells.ScrollCheck::dc).orElse(null),
                    check.map(ItemSpells.ScrollCheck::ability).orElse(null)));
        }
        return new UsableItems(potions, charged, scrolls);
    }

    // A potion by its item's type, or by its name when the entry isn't linked: "Potion of ..." or "Elixir of ..."
    private static boolean potion(InventoryItem entry, Item item) {
        if (item != null) {
            return "Potion".equalsIgnoreCase(item.getType());
        }
        String name = entry.name().trim().toLowerCase(Locale.ROOT);
        return name.startsWith("potion of ") || name.startsWith("elixir of ");
    }

    private static String dice(Dice dice) {
        return dice.count() + "d" + dice.sides() + (dice.modifier() > 0 ? " + " + dice.modifier() : "");
    }

    private static String article(String name) {
        return name.matches("(?i)^(a|an|the) .*") ? "" : name.matches("(?i)^[aeiou].*") ? "an " : "a ";
    }

    // The log keeps a potion's description to its first 400 characters
    private static String shortened(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 400 ? flat : flat.substring(0, 397) + "...";
    }
}
