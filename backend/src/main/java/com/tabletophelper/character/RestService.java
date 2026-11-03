package com.tabletophelper.character;

import com.tabletophelper.character.dto.CharacterResponse;
import com.tabletophelper.character.dto.ShortRestRequest;
import com.tabletophelper.character.dto.ShortRestRequest.HitDieSpent;
import com.tabletophelper.character.dto.ShortRestResponse;
import com.tabletophelper.character.dto.ShortRestResponse.HitDieRoll;
import com.tabletophelper.character.state.HitDicePool;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.resourcepool.ResourcePoolService;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Rng;
import com.tabletophelper.rules.character.CurrentHitPoints;
import com.tabletophelper.rules.character.HitDice;
import com.tabletophelper.rules.character.Rests;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.tabletophelper.character.CharacterUpdates.orEmpty;

/**
 * Short and long rests, worked out by the server from the 2014 rules (PHB p.186, D158) in rules.character's Rests and
 * HitDice. Neither is allowed while the character is in an Active or Paused encounter, or once it has died, until the DM
 * revives it (D166).
 * <ul>
 *   <li>A short rest spends the Hit Dice the player names: each heals its roll, entered by the player or rolled
 *       here, plus the Constitution modifier, never less than 0, up to the hit point maximum, and the answer says
 *       what each rolled. The pools that reset on a short rest come back, and so do a warlock's pact slots
 *       (PHB p.107).</li>
 *   <li>A long rest needs at least 1 hit point. It restores every hit point and clears temporary hit points, brings
 *       back spent Hit Dice up to half the character's total, at least one, the largest first, and restores every
 *       spell slot and every pool.</li>
 * </ul>
 * Song of Rest and Arcane Recovery aren't applied.
 */
@Service
@RequiredArgsConstructor
public class RestService {

    private static final String PACT_SLOT = "pact_";

    private final CharacterCommands characterCommands;
    private final ResourcePoolService resourcePoolService;
    private final Rng rng;

    /**
     * A short rest, spending the Hit Dice the request names. It answers with the rested character and each die's roll
     * and healing.
     *
     * @throws ValidationException for a class without Hit Dice, more dice than a class has left, or a roll outside 1
     *                             to the die's size
     * @throws ConflictException   while the character is in an Active or Paused encounter, or dead
     */
    @Transactional
    public ShortRestResponse shortRest(UUID characterId, ShortRestRequest request, UUID userId) {
        PlayerCharacter character = characterCommands.beginOutsideEncounters(characterId, userId,
                "take a short rest");
        CharacterCommands.requireAlive(character, "take a short rest");
        List<HitDieSpent> dice = request != null && request.hitDice() != null ? request.hitDice() : List.of();
        List<HitDieRoll> rolls = spendHitDice(character, dice);
        resetPactSlots(character);
        resetShortRestPools(character);
        return new ShortRestResponse(characterCommands.finish(character), rolls);
    }

    /**
     * A long rest.
     *
     * @throws ConflictException while the character is in an Active or Paused encounter, is dead, or has no hit
     *                           points left
     */
    @Transactional
    public CharacterResponse longRest(UUID characterId, UUID userId) {
        PlayerCharacter character = characterCommands.beginOutsideEncounters(characterId, userId, "take a long rest");
        CharacterCommands.requireAlive(character, "take a long rest");
        // A character must have at least 1 hit point at the start of the rest to gain its benefits (PHB p.186)
        if (character.getHpCurrent() == null || character.getHpCurrent() < 1) {
            throw new ConflictException(character.getName() + " needs at least 1 hit point to benefit from a long rest");
        }
        character.setHpCurrent(CharacterSheetService.totals(character).maximum());
        character.setHpTemp(0);
        recoverHitDice(character);
        resetAllSlots(character);
        resetAllPools(character);
        return characterCommands.finish(character);
    }

    // ── Hit Dice ─────────────────────────────────────────────────

    // Every die is checked before any is rolled, so a refused rest changes nothing. Gives each die's roll and healing,
    // in the order named
    private List<HitDieRoll> spendHitDice(PlayerCharacter character, List<HitDieSpent> dice) {
        if (dice.isEmpty()) {
            return List.of();
        }
        Map<String, HitDicePool> hitDice = new LinkedHashMap<>(orEmptyMap(character.getHitDice()));
        Map<String, Integer> spending = new LinkedHashMap<>();
        for (HitDieSpent die : dice) {
            HitDicePool pool = hitDice.get(die.className());
            if (pool == null) {
                throw new ValidationException(character.getName() + " has no " + die.className() + " Hit Dice");
            }
            if (die.roll() != null && (die.roll() < 1 || die.roll() > pool.dieSize())) {
                throw new ValidationException("A d" + pool.dieSize() + " rolls 1 to " + pool.dieSize() + ", not "
                        + die.roll());
            }
            spending.merge(die.className(), 1, Integer::sum);
        }
        spending.forEach((className, count) -> {
            HitDicePool pool = hitDice.get(className);
            if (count > pool.remaining()) {
                throw new ValidationException(character.getName() + " has " + pool.remaining() + " " + className
                        + " Hit " + (pool.remaining() == 1 ? "Die" : "Dice") + " left, not " + count);
            }
        });

        // With the DM's adjustments to Constitution, as every number worked out from it has (D158)
        int constitutionModifier = Ability.modifier(DmAdjustments.abilityScore(character, Ability.CON));
        List<HitDieRoll> rolls = new ArrayList<>();
        int healing = 0;
        for (HitDieSpent die : dice) {
            int dieSize = hitDice.get(die.className()).dieSize();
            boolean rolledByServer = die.roll() == null;
            int roll = rolledByServer ? rng.roll(dieSize) : die.roll();
            int healed = Rests.hitDieHealing(dieSize, roll, constitutionModifier);
            rolls.add(new HitDieRoll(die.className(), dieSize, roll, rolledByServer, healed));
            healing += healed;
        }
        spending.forEach((className, count) -> {
            HitDicePool pool = hitDice.get(className);
            hitDice.put(className, new HitDicePool(pool.dieSize(), pool.total(), pool.remaining() - count));
        });

        character.setHitDice(hitDice);
        character.setHpCurrent(CurrentHitPoints.heal(CharacterSheetService.totals(character), healing).current());
        return rolls;
    }

    // Half the character's total Hit Dice come back, at least one, the largest first (D158)
    private static void recoverHitDice(PlayerCharacter character) {
        Map<String, HitDicePool> stored = orEmptyMap(character.getHitDice());
        if (stored.isEmpty()) {
            return;
        }
        List<String> classes = new ArrayList<>(stored.keySet());
        List<HitDice.Pool> pools = classes.stream()
                .map(className -> {
                    HitDicePool pool = stored.get(className);
                    return new HitDice.Pool(pool.dieSize(), pool.total(), pool.remaining());
                })
                .toList();
        List<HitDice.Pool> rested = HitDice.afterLongRest(pools);
        Map<String, HitDicePool> hitDice = new LinkedHashMap<>();
        for (int i = 0; i < classes.size(); i++) {
            HitDice.Pool pool = rested.get(i);
            hitDice.put(classes.get(i), new HitDicePool(pool.dieSize(), pool.total(), pool.remaining()));
        }
        character.setHitDice(hitDice);
    }

    // ── Spell slots ──────────────────────────────────────────────

    private static void resetPactSlots(PlayerCharacter character) {
        resetSlots(character, true);
    }

    private static void resetAllSlots(PlayerCharacter character) {
        resetSlots(character, false);
    }

    // Each slot's used count goes back to 0, or only the pact slots'
    private static void resetSlots(PlayerCharacter character, boolean pactOnly) {
        if (character.getSpellSlots() == null) {
            return;
        }
        Map<String, SpellSlot> slots = new LinkedHashMap<>();
        character.getSpellSlots().forEach((key, slot) -> slots.put(key,
                slot != null && (!pactOnly || key.startsWith(PACT_SLOT)) ? new SpellSlot(slot.max(), 0) : slot));
        character.setSpellSlots(slots);
    }

    // ── Pools ────────────────────────────────────────────────────

    // The pools that reset on a short rest, by their reset amount, in full when they have none (D127)
    private void resetShortRestPools(PlayerCharacter character) {
        List<ResourcePoolEntry> pools = orEmpty(character.getResourcePools());
        if (pools.isEmpty()) {
            return;
        }
        Map<String, Integer> context = resourcePoolService.buildCharacterContext(character);
        character.setResourcePools(new ArrayList<>(resourcePoolService.resetPools(pools, "shortRest", context).pools()));
    }

    // Every pool comes back in full, innate and feat uses included (D127, D158, D165)
    private static void resetAllPools(PlayerCharacter character) {
        character.setResourcePools(new ArrayList<>(orEmpty(character.getResourcePools()).stream()
                .map(pool -> pool.withCurrentUses(pool.maxUses()))
                .toList()));
    }

    private static <K, V> Map<K, V> orEmptyMap(Map<K, V> map) {
        return map != null ? map : Map.of();
    }
}
