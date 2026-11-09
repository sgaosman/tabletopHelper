package com.tabletophelper.character;

import com.tabletophelper.character.CombatResults.CharacterResult;
import com.tabletophelper.character.dto.CharacterResponse;
import com.tabletophelper.character.dto.CombatResultPreview;
import com.tabletophelper.character.state.CombatTotals;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.rules.character.CurrentHitPoints;
import com.tabletophelper.rules.character.WriteBack;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.shared.ConflictException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.tabletophelper.character.CharacterUpdates.orEmpty;

/**
 * Write-back (D166): what an encounter changed for each of its player characters, applied to their sheets through
 * rules.character.WriteBack when the DM completes the encounter with its results applied, or removes a character from
 * one under way. The encounter module publishes {@link CombatResults}, and {@link #apply} handles it inside that
 * transaction, so the dependency runs from encounter to character only (D151). The same rules preview the results for
 * the DM's End dialog. A character who died stays dead until the DM revives it, at 1 hit point.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WriteBackService {

    private static final int REVIVED_HIT_POINTS = 1;

    private final CharacterCommands characterCommands;
    private final CharacterRepository characterRepository;

    /**
     * What applying the results would do to each character's sheet as it stands now, in the order the results list
     * the characters; changes nothing. A character the encounter holds twice gets both participants' changes, as
     * applying them would.
     */
    @Transactional(readOnly = true)
    public List<CombatResultPreview> preview(CombatResults results) {
        List<CombatResultPreview> previews = new ArrayList<>();
        byCharacter(results).forEach((characterId, characterResults) ->
                characterRepository.findById(characterId).ifPresent(character -> {
                    Sheet before = Sheet.of(character);
                    previews.add(preview(character, characterResults.getLast().lifeState(), before,
                            before.after(characterResults)));
                }));
        return previews;
    }

    /**
     * Applies an encounter's results to its characters' sheets, inside the transaction that completes the encounter
     * or removes the participant, so a failure here undoes that too. The characters are locked in ID order after the
     * encounter's row, and each sheet is then worked out again as every command's is.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(CombatResults results) {
        Map<UUID, List<CharacterResult>> byCharacter = byCharacter(results);
        for (PlayerCharacter character : characterCommands.beginWriteBack(byCharacter.keySet())) {
            Sheet before = Sheet.of(character);
            Sheet after = before.after(byCharacter.get(character.getId()));
            after.writeTo(character);
            characterCommands.save(character);
            log.info("Encounter {} wrote back to character {}: HP {} to {}, temporary HP {} to {}{}",
                    results.encounterId(), character.getId(), before.hitPoints().current(),
                    after.hitPoints().current(), before.hitPoints().temporary(), after.hitPoints().temporary(),
                    after.dead() && !before.dead() ? ", now dead" : "");
        }
    }

    /**
     * The DM's revival of a character who died, at 1 hit point (D166). Only the DM of its campaign may revive it, as
     * with DM Adjustments, and it isn't refused during an encounter.
     *
     * @throws ConflictException for a character that isn't dead
     */
    @Transactional
    public CharacterResponse revive(UUID characterId, UUID userId) {
        PlayerCharacter character = characterCommands.beginAsDm(characterId, userId, "revive");
        if (!character.isDead()) {
            throw new ConflictException(character.getName() + " isn't dead");
        }
        character.setDead(false);
        character.setHpCurrent(REVIVED_HIT_POINTS);
        character.setHpTemp(0);
        log.info("DM {} revived character {} at {} hit point", userId, characterId, REVIVED_HIT_POINTS);
        return characterCommands.finish(character);
    }

    // Each character's results in the order they come, the first character first
    private static Map<UUID, List<CharacterResult>> byCharacter(CombatResults results) {
        Map<UUID, List<CharacterResult>> byCharacter = new LinkedHashMap<>();
        for (CharacterResult result : results.characters()) {
            byCharacter.computeIfAbsent(result.characterId(), id -> new ArrayList<>()).add(result);
        }
        return byCharacter;
    }

    private static CombatResultPreview preview(PlayerCharacter character, LifeState lifeState, Sheet before,
                                               Sheet after) {
        List<CombatResultPreview.SlotChange> slots = new ArrayList<>();
        if (before.slots() != null && after.slots() != null) {
            before.slots().forEach((key, slot) -> {
                SpellSlot changed = after.slots().get(key);
                if (slot != null && changed != null && changed.used() != slot.used()) {
                    slots.add(new CombatResultPreview.SlotChange(key, slot.max(), slot.used(), changed.used()));
                }
            });
        }
        List<CombatResultPreview.PoolChange> pools = new ArrayList<>();
        for (int i = 0; i < before.pools().size(); i++) {
            ResourcePoolEntry pool = before.pools().get(i);
            ResourcePoolEntry changed = after.pools().get(i);
            if (changed.currentUses() != pool.currentUses()) {
                pools.add(new CombatResultPreview.PoolChange(pool.poolId(), pool.displayName(), pool.maxUses(),
                        pool.currentUses(), changed.currentUses()));
            }
        }
        return new CombatResultPreview(character.getId(), character.getName(), lifeState,
                before.hitPoints().maximum(), before.hitPoints().current(), after.hitPoints().current(),
                before.hitPoints().temporary(), after.hitPoints().temporary(), before.dead(), after.dead(), slots,
                pools);
    }

    /**
     * The totals on a sheet that Write-back changes: its hit points, whether it is dead, its spell slots and its pools.
     * Applying a participant's results gives the totals after them, leaving the character as it is until
     * {@link #writeTo}.
     */
    record Sheet(CurrentHitPoints.Totals hitPoints, boolean dead, Map<String, SpellSlot> slots,
                 List<ResourcePoolEntry> pools) {

        static Sheet of(PlayerCharacter character) {
            return new Sheet(CharacterSheetService.totals(character), character.isDead(), character.getSpellSlots(),
                    orEmpty(character.getResourcePools()).stream().filter(Objects::nonNull).toList());
        }

        /** The totals after each of the character's results in turn. */
        Sheet after(List<CharacterResult> results) {
            Sheet sheet = this;
            for (CharacterResult result : results) {
                sheet = sheet.after(result);
            }
            return sheet;
        }

        /**
         * The totals after one participant's results. Each slot and pool the participant joined and ended with gets
         * its change, within the sheet's maximum; one the sheet or the participant lacks is left as it is. Slots the
         * participant created with Flexible Casting are added to the sheet's (PHB p.101, M18), with the level's
         * maximum raised by them.
         */
        Sheet after(CharacterResult result) {
            CombatTotals joined = result.joinedWith();
            CombatTotals ended = result.endedWith();
            WriteBack.Outcome outcome = WriteBack.hitPoints(hitPoints, dead, joined.hpCurrent(), ended.hpCurrent(),
                    ended.hpTemp(), result.lifeState());

            Map<String, SpellSlot> slotsAfter = null;
            if (slots != null || !ended.slotsCreated().isEmpty()) {
                slotsAfter = new LinkedHashMap<>();
                Map<String, SpellSlot> sheetSlots = new LinkedHashMap<>(slots != null ? slots : Map.of());
                // A level the participant created slots at that the sheet has none of (PHB p.101, M18)
                ended.slotsCreated().forEach((key, created) -> {
                    if (created > joined.slotsCreated().getOrDefault(key, 0) && sheetSlots.get(key) == null) {
                        sheetSlots.put(key, new SpellSlot(0, 0));
                    }
                });
                for (Map.Entry<String, SpellSlot> entry : sheetSlots.entrySet()) {
                    String key = entry.getKey();
                    SpellSlot slot = entry.getValue();
                    Integer joinedUsed = joined.slotsUsed().get(key);
                    Integer endedUsed = ended.slotsUsed().get(key);
                    if (slot == null || endedUsed == null) {
                        slotsAfter.put(key, slot);
                        continue;
                    }
                    // Created slots only come in combat, so the sheet gains those the participant made
                    int created = Math.max(0, slot.created() + ended.slotsCreated().getOrDefault(key, 0)
                            - joined.slotsCreated().getOrDefault(key, 0));
                    int used = WriteBack.total(slot.used(), slot.max() + created,
                            joinedUsed != null ? joinedUsed : 0, endedUsed);
                    slotsAfter.put(key, joinedUsed == null && created == slot.created() ? slot
                            : new SpellSlot(slot.max(), used, created));
                }
            }

            List<ResourcePoolEntry> poolsAfter = new ArrayList<>();
            for (ResourcePoolEntry pool : pools) {
                Integer joinedUses = joined.poolsCurrentUses().get(pool.poolId());
                Integer endedUses = ended.poolsCurrentUses().get(pool.poolId());
                poolsAfter.add(joinedUses == null || endedUses == null ? pool
                        : pool.withCurrentUses(WriteBack.total(pool.currentUses(), pool.maxUses(), joinedUses,
                        endedUses)));
            }
            return new Sheet(outcome.hitPoints(), outcome.dead(), slotsAfter, List.copyOf(poolsAfter));
        }

        void writeTo(PlayerCharacter character) {
            character.setHpCurrent(hitPoints.current());
            character.setHpTemp(hitPoints.temporary());
            character.setDead(dead);
            character.setSpellSlots(slots);
            character.setResourcePools(new ArrayList<>(pools));
        }
    }
}
