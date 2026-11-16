package com.tabletophelper.character;

import com.tabletophelper.campaign.AccessPolicy;
import com.tabletophelper.rules.Exhaustion;
import com.tabletophelper.character.dto.CharacterResponse;
import com.tabletophelper.character.dto.ShortRestRequest;
import com.tabletophelper.character.dto.ShortRestRequest.HitDieSpent;
import com.tabletophelper.character.dto.ShortRestRequest.SongOfRest;
import com.tabletophelper.character.dto.ShortRestResponse;
import com.tabletophelper.character.dto.ShortRestResponse.HitDieRoll;
import com.tabletophelper.character.dto.ShortRestResponse.SongOfRestRoll;
import com.tabletophelper.character.dto.SongOfRestResponse;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.HitDicePool;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.resourcepool.ResourcePoolService;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Rng;
import com.tabletophelper.rules.character.CurrentHitPoints;
import com.tabletophelper.rules.character.HitDice;
import com.tabletophelper.rules.character.Rests;
import com.tabletophelper.rules.character.Rests.SlotRecovery;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.NotFoundException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
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
 *       (PHB p.107). A wizard may use Arcane Recovery as it finishes (PHB p.115): expended spell slots of 5th level or
 *       lower whose levels add up to half its wizard level, rounded up, once a day, which its
 *       {@value #ARCANE_RECOVERY} pool counts until its next long rest. A Circle of the Land druid's Natural Recovery
 *       (PHB p.68) follows the same rule with its druid level, once until a long rest, which its
 *       {@value #NATURAL_RECOVERY} pool counts (D222); both on one rest never recover the same slot twice. When a bard
 *       performed Song of Rest during it (PHB p.54, D223), a character that regains hit points by spending Hit Dice
 *       regains the bard's die more: a d6 from Bard 2, a d8 at 9th, a d10 at 13th and a d12 at 17th. The rest is the
 *       character's own, so the request names the die; the server offers the best a bard of its campaign has.</li>
 *   <li>A long rest needs at least 1 hit point. It restores every hit point and clears temporary hit points, brings
 *       back spent Hit Dice up to half the character's total, at least one, the largest first, and restores every
 *       spell slot and every pool, and ends the spell slots a sorcerer created with Flexible Casting (PHB p.101).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class RestService {

    private static final String PACT_SLOT = "pact_";

    /** The pool that counts Arcane Recovery's one use a day (M17). */
    public static final String ARCANE_RECOVERY = "class:wizard-arcane-recovery";

    /** The pool that counts a Circle of the Land druid's Natural Recovery, once until a long rest (PHB p.68, D222). */
    public static final String NATURAL_RECOVERY = "subclass:land-natural-recovery";

    private static final String WIZARD = "Wizard";
    private static final String DRUID = "Druid";
    private static final String BARD = "Bard";

    private final CharacterCommands characterCommands;
    private final ResourcePoolService resourcePoolService;
    private final Rng rng;
    private final CharacterRepository characterRepository;
    private final AccessPolicy accessPolicy;

    /**
     * A short rest, spending the Hit Dice the request names. It answers with the rested character and each die's roll
     * and healing.
     *
     * @throws ValidationException for a class without Hit Dice, more dice than a class has left, a roll outside 1
     *                             to the die's size, or Arcane Recovery's slots refused: a character without wizard
     *                             levels, levels adding up to more than it recovers, a slot above 5th or one not
     *                             expended
     * @throws ConflictException   while the character is in an Active or Paused encounter, or dead, or when Arcane
     *                             Recovery was used since the last long rest
     */
    @Transactional
    public ShortRestResponse shortRest(UUID characterId, ShortRestRequest request, UUID userId) {
        PlayerCharacter character = characterCommands.beginOutsideEncounters(characterId, userId,
                "take a short rest");
        CharacterCommands.requireAlive(character, "take a short rest");
        List<HitDieSpent> dice = request != null && request.hitDice() != null ? request.hitDice() : List.of();
        List<Integer> arcane = request != null && request.arcaneRecovery() != null ? request.arcaneRecovery()
                : List.of();
        List<Integer> natural = request != null && request.naturalRecovery() != null ? request.naturalRecovery()
                : List.of();
        SongOfRest song = request != null ? request.songOfRest() : null;
        if (song != null) {
            requireSongOfRest(song);
        }
        // Arcane Recovery and Natural Recovery are checked before anything changes, so a refused rest changes nothing
        if (!arcane.isEmpty() || !natural.isEmpty()) {
            requireSlotRecovery(character, arcane, natural);
        }
        int hpBefore = character.getHpCurrent() != null ? character.getHpCurrent() : 0;
        List<HitDieRoll> rolls = spendHitDice(character, dice);
        int hpFromHitDice = (character.getHpCurrent() != null ? character.getHpCurrent() : 0) - hpBefore;
        SongOfRestRoll songRoll = song != null && Rests.earnsSongOfRest(rolls.size(), hpFromHitDice)
                ? songOfRest(character, song) : null;
        resetPactSlots(character);
        resetShortRestPools(character);
        if (!arcane.isEmpty()) {
            recoverSlots(character, arcane, ARCANE_RECOVERY);
        }
        if (!natural.isEmpty()) {
            recoverSlots(character, natural, NATURAL_RECOVERY);
        }
        // The short-rest pools and pact slots a level-down kept spent are restored now too (D222)
        LevelDownSpending.afterShortRest(character);
        return new ShortRestResponse(characterCommands.finish(character), rolls, songRoll);
    }

    /**
     * The best Song of Rest the character could hear on a short rest (PHB p.54, D223): that of the living bard of 2nd
     * level or higher in its campaign with the highest Bard level, the character itself included, or its own when it is
     * in no campaign. The short-rest dialog offers it; whether the bard performed is the player's to say.
     *
     * @throws NotFoundException for an unknown or deleted character
     * @throws com.tabletophelper.shared.ForbiddenException for anyone but its owner and its campaign's DM
     */
    @Transactional(readOnly = true)
    public SongOfRestResponse songOfRestOffered(UUID characterId, UUID userId) {
        PlayerCharacter character = characterRepository.findById(characterId)
                .filter(found -> !Boolean.FALSE.equals(found.getIsActive()))
                .orElseThrow(() -> new NotFoundException("Character not found"));
        UUID campaignId = character.getCampaign() != null ? character.getCampaign().getId() : null;
        accessPolicy.requireOwnerOrDm(character.getUser().getId(), campaignId, userId);
        List<PlayerCharacter> party = campaignId != null
                ? characterRepository.findByCampaignIdAndIsActiveTrue(campaignId) : List.of(character);
        return party.stream()
                .filter(member -> !member.isDead())
                .filter(member -> Rests.songOfRestDie(classLevel(member, BARD)).isPresent())
                .max(Comparator.comparingInt(member -> classLevel(member, BARD)))
                .map(bard -> new SongOfRestResponse(Rests.songOfRestDie(classLevel(bard, BARD)).orElseThrow(),
                        bard.getName(), classLevel(bard, BARD)))
                .orElse(SongOfRestResponse.NONE);
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
        // Finishing a long rest lowers exhaustion by one for a creature that has had food and drink, which the sheet
        // takes it has (PHB p.186, p.291, D183); the hit points then fill to the maximum that level leaves
        LevelDownSpending.Before beforeRest = LevelDownSpending.Before.of(character);
        character.setExhaustionLevel(Exhaustion.afterLongRest(character.getExhaustionLevel()));
        character.setHpCurrent(CharacterSheetService.totals(character).maximum());
        character.setHpTemp(0);
        recoverHitDice(character);
        resetAllSlots(character);
        resetAllPools(character);
        // What a level-down kept spent is restored as the rest would restore it at the character's full level: the
        // slots, hit points and filled pools, but only half the full level's Hit Dice, and a pool that resets to an
        // amount only to that amount (D234, amending D222, which restored everything)
        Map<String, Integer> context = resourcePoolService.buildCharacterContext(character);
        LevelDownSpending.afterLongRest(character, beforeRest, pool ->
                resourcePoolService.resetPools(List.of(pool), "longRest", context).pools().getFirst().currentUses());
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

    // ── Song of Rest (D223) ──────────────────────────────────────

    // A die Song of Rest doesn't have, or a roll outside it, is refused before anything changes
    private static void requireSongOfRest(SongOfRest song) {
        if (!Rests.SONG_OF_REST_DICE.contains(song.dieSize())) {
            throw new ValidationException("Song of Rest heals with a d6, d8, d10 or d12, not a d" + song.dieSize());
        }
        if (song.roll() != null && (song.roll() < 1 || song.roll() > song.dieSize())) {
            throw new ValidationException("A d" + song.dieSize() + " rolls 1 to " + song.dieSize() + ", not "
                    + song.roll());
        }
    }

    // The extra hit points Song of Rest gives (PHB p.54: "regain an extra 1d6 hit points"): the die's roll alone,
    // entered or rolled here, up to the maximum
    private SongOfRestRoll songOfRest(PlayerCharacter character, SongOfRest song) {
        boolean rolledByServer = song.roll() == null;
        int roll = rolledByServer ? rng.roll(song.dieSize()) : song.roll();
        character.setHpCurrent(CurrentHitPoints.heal(CharacterSheetService.totals(character), roll).current());
        return new SongOfRestRoll(song.dieSize(), roll, rolledByServer, roll);
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

    // ── Arcane Recovery and Natural Recovery ─────────────────────

    // Arcane Recovery's slots are checked first and Natural Recovery's against the slots still expended after them, so
    // a wizard and Circle of the Land druid can use both on one rest but never recover one slot twice (D222). The
    // character's pools are brought up to date before the uses are checked, so a wizard made before Arcane Recovery
    // had a pool has one, and a Circle of the Land druid made before Natural Recovery's pool has it
    private void requireSlotRecovery(PlayerCharacter character, List<Integer> arcane, List<Integer> natural) {
        Map<Integer, Integer> expended = expendedSlots(character);
        if (!arcane.isEmpty()) {
            Rests.slotRecoveryRefusal(SlotRecovery.ARCANE_RECOVERY, classLevel(character, WIZARD), arcane, expended)
                    .ifPresent(reason -> {
                        throw new ValidationException(reason);
                    });
            arcane.forEach(level -> expended.merge(level, -1, Integer::sum));
        }
        character.setResourcePools(resourcePoolService.reconciledPools(character,
                resourcePoolService.buildCharacterContext(character)));
        if (!arcane.isEmpty()) {
            requireUse(character, ARCANE_RECOVERY, SlotRecovery.ARCANE_RECOVERY);
        }
        if (!natural.isEmpty()) {
            // Only a Circle of the Land druid from 2nd level has the pool, so without it the druid level counts as 0
            boolean hasPool = pool(character, NATURAL_RECOVERY) != null;
            Rests.slotRecoveryRefusal(SlotRecovery.NATURAL_RECOVERY, hasPool ? classLevel(character, DRUID) : 0,
                    natural, expended).ifPresent(reason -> {
                        throw new ValidationException(reason);
                    });
            requireUse(character, NATURAL_RECOVERY, SlotRecovery.NATURAL_RECOVERY);
        }
    }

    // A feature used since the last long rest is a 409; a wizard without the Arcane Recovery pool may still use it
    private static void requireUse(PlayerCharacter character, String poolId, SlotRecovery feature) {
        ResourcePoolEntry pool = pool(character, poolId);
        if (pool != null && !pool.unlimited() && pool.currentUses() < 1) {
            throw new ConflictException(character.getName() + " has used " + feature.featureName()
                    + " since their last long rest");
        }
    }

    private static ResourcePoolEntry pool(PlayerCharacter character, String poolId) {
        return orEmpty(character.getResourcePools()).stream()
                .filter(entry -> poolId.equals(entry.poolId()))
                .findFirst()
                .orElse(null);
    }

    // How many slots of each level are expended, pact slots aside
    private static Map<Integer, Integer> expendedSlots(PlayerCharacter character) {
        Map<Integer, Integer> expended = new LinkedHashMap<>();
        orEmptyMap(character.getSpellSlots()).forEach((key, slot) -> {
            if (slot != null && !key.startsWith(PACT_SLOT) && slot.used() > 0) {
                try {
                    expended.put(Integer.parseInt(key), slot.used());
                } catch (NumberFormatException e) {
                    // A key that names no level has nothing to recover
                }
            }
        });
        return expended;
    }

    // Each slot named is no longer expended, and the feature's use until the next long rest is spent
    private static void recoverSlots(PlayerCharacter character, List<Integer> slotLevels, String poolId) {
        Map<String, SpellSlot> slots = new LinkedHashMap<>(orEmptyMap(character.getSpellSlots()));
        for (Integer level : slotLevels) {
            String key = String.valueOf(level);
            slots.put(key, slots.get(key).restore());
        }
        character.setSpellSlots(slots);
        if (pool(character, poolId) != null) {
            character.setResourcePools(new ArrayList<>(ResourcePoolService.spend(character.getResourcePools(),
                    poolId, 1, character.getName())));
        }
    }

    private static int classLevel(PlayerCharacter character, String className) {
        List<ClassEntry> entries = orEmpty(character.getMulticlassEntries());
        if (!entries.isEmpty()) {
            return entries.stream()
                    .filter(entry -> entry != null && className.equalsIgnoreCase(entry.className()))
                    .mapToInt(ClassEntry::level)
                    .sum();
        }
        return className.equalsIgnoreCase(character.getCharacterClass()) && character.getLevel() != null
                ? character.getLevel() : 0;
    }

    // ── Spell slots ──────────────────────────────────────────────

    private static void resetPactSlots(PlayerCharacter character) {
        resetSlots(character, true);
    }

    private static void resetAllSlots(PlayerCharacter character) {
        resetSlots(character, false);
    }

    // Each slot's used count goes back to 0, or only the pact slots'. A long rest also ends the slots Flexible Casting
    // created (PHB p.101, M18), and a level that held only those goes
    private static void resetSlots(PlayerCharacter character, boolean pactOnly) {
        if (character.getSpellSlots() == null) {
            return;
        }
        Map<String, SpellSlot> slots = new LinkedHashMap<>();
        character.getSpellSlots().forEach((key, slot) -> {
            if (slot == null || (pactOnly && !key.startsWith(PACT_SLOT))) {
                slots.put(key, slot);
            } else if (pactOnly || slot.max() > 0) {
                slots.put(key, new SpellSlot(slot.max(), 0));
            }
        });
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

    // Every pool comes back in full, innate and feat uses included (D127, D158, D165), but a magic item's charges come
    // back by its own rule, its dice of them or none for one that never recharges (D210); so do the spent charges an
    // inventory entry records for an item without a pool, as one unattuned. A long-rest pool with a reset amount is set
    // to that amount instead, as the War Wizard's Power Surge resets to one whatever it held (XGE p.60, D230); a
    // short-rest pool's reset amount is for short rests only, and a long rest still fills it
    private void resetAllPools(PlayerCharacter character) {
        List<ResourcePoolEntry> pools = orEmpty(character.getResourcePools());
        Map<String, Integer> context = resourcePoolService.buildCharacterContext(character);
        List<ResourcePoolEntry> byRule = pools.stream().filter(RestService::resetsByRule).toList();
        Map<String, ResourcePoolEntry> reset = new LinkedHashMap<>();
        if (!byRule.isEmpty()) {
            resourcePoolService.resetPools(byRule, "longRest", context).pools()
                    .forEach(pool -> reset.put(pool.poolId(), pool));
        }
        character.setResourcePools(new ArrayList<>(pools.stream()
                .map(pool -> resetsByRule(pool) ? reset.getOrDefault(pool.poolId(), pool)
                        : pool.withCurrentUses(pool.maxUses()))
                .toList()));
        character.setEquipment(resourcePoolService.afterLongRest(character, context));
    }

    // A pool a long rest resets by its own rule rather than filling: a magic item's, or a long-rest pool with a reset
    // amount (D210, D230)
    private static boolean resetsByRule(ResourcePoolEntry pool) {
        return magicItem(pool) || pool != null && LONG_REST.equals(pool.resetOn()) && pool.resetAmount() != null
                && !pool.resetAmount().isBlank();
    }

    private static final String LONG_REST = "longRest";

    private static boolean magicItem(ResourcePoolEntry pool) {
        return pool != null && "MAGIC_ITEM".equals(pool.sourceType());
    }

    private static <K, V> Map<K, V> orEmptyMap(Map<K, V> map) {
        return map != null ? map : Map.of();
    }
}
