package com.tabletophelper.character;

import com.tabletophelper.character.dto.ApplyChoicesRequest;
import com.tabletophelper.character.dto.CharacterResponse;
import com.tabletophelper.character.dto.DmAdjustmentRequest;
import com.tabletophelper.character.state.AbilityScoreImprovement;
import com.tabletophelper.character.state.DmAdjustment;
import com.tabletophelper.character.state.DmAdjustment.Kind;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.reference.Feat;
import com.tabletophelper.reference.FeatRepository;
import com.tabletophelper.rules.combat.DamageRules;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.NotFoundException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.tabletophelper.character.CharacterUpdates.copy;
import static com.tabletophelper.character.CharacterUpdates.orEmpty;

/**
 * DM Adjustments (D158): the named changes the DM of a character's campaign makes on top of what the rules give the
 * character, each with a reason its player sees. Only that DM may add or remove one: not the owner, and nobody for a
 * character in no campaign. Each starts in CharacterCommands, which checks access and locks the row, and ends with the
 * Derived Stats worked out again. Neither waits for an encounter to end: combat reads a character's defences and
 * ability scores live, so those apply at once, and the stored Derived Stats reach the participants who join afterwards
 * (D161). Every use is logged, with IDs rather than names or the reason.
 * <ul>
 *   <li>A STAT adjustment adds its amount to the armour class, hit point maximum, speed, initiative, every spell save
 *       DC or attack bonus, or an ability score, which CharacterStats then works everything else out from. The hit
 *       points follow the maximum as levelling's do (D167).</li>
 *   <li>A DEFENCE adjustment gives a resistance, immunity or vulnerability to one of the damage types combat knows. One
 *       the character already has counts once.</li>
 *   <li>A FEAT adjustment grants a feat through FeatEffectResolver, with the choices an Ability Score Improvement's
 *       feat takes, and records what it applied, so removing it reverses exactly that. A feat the character already
 *       has is refused unless its text says it can be taken again.</li>
 * </ul>
 * A removed adjustment stays on the sheet, with who removed it and when, as the record.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DmAdjustmentService {

    private final CharacterCommands characterCommands;
    private final FeatRepository featRepository;
    private final FeatEffectResolver featEffectResolver;

    /**
     * Adds an adjustment to the character.
     *
     * @throws ValidationException for a request its kind doesn't fit, or a feat that doesn't exist
     * @throws ConflictException   for a feat the character already has and can't take again
     */
    @Transactional
    public CharacterResponse add(UUID characterId, DmAdjustmentRequest request, UUID userId) {
        PlayerCharacter character = characterCommands.beginAsDm(characterId, userId);
        DmAdjustment adjustment = switch (request.kind()) {
            case STAT -> stat(request, userId);
            case DEFENCE -> defence(request, userId);
            case FEAT -> feat(character, request, userId);
        };
        List<DmAdjustment> adjustments = copy(character.getDmAdjustments());
        adjustments.add(adjustment);
        character.setDmAdjustments(adjustments);
        log.info("DM {} added DM Adjustment {} to character {}: {}", userId, adjustment.id(), characterId,
                logged(adjustment));
        return characterCommands.finish(character);
    }

    /**
     * Removes one of the character's adjustments, keeping it as the record. A feat's effects are reversed.
     *
     * @throws NotFoundException for an adjustment the character doesn't have
     * @throws ConflictException for one already removed
     */
    @Transactional
    public CharacterResponse remove(UUID characterId, UUID adjustmentId, UUID userId) {
        PlayerCharacter character = characterCommands.beginAsDm(characterId, userId);
        List<DmAdjustment> adjustments = copy(character.getDmAdjustments());
        int index = -1;
        for (int i = 0; i < adjustments.size(); i++) {
            if (adjustmentId.equals(adjustments.get(i).id())) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            throw new NotFoundException("DM Adjustment not found");
        }
        DmAdjustment adjustment = adjustments.get(index);
        if (!adjustment.active()) {
            throw new ConflictException("That DM Adjustment has already been removed");
        }
        if (adjustment.kind() == Kind.FEAT) {
            featEffectResolver.reverseFeatEffects(character, adjustment.appliedEffects());
        }
        adjustments.set(index, adjustment.removed(userId, Instant.now()));
        character.setDmAdjustments(adjustments);
        log.info("DM {} removed DM Adjustment {} from character {}: {}", userId, adjustmentId, characterId,
                logged(adjustment));
        return characterCommands.finish(character);
    }

    // ── The kinds ────────────────────────────────────────────────

    private static DmAdjustment stat(DmAdjustmentRequest request, UUID dmId) {
        refuseUnused(request.defence(), "defence", Kind.STAT);
        refuseUnused(request.feat(), "feat", Kind.STAT);
        DmAdjustment.Stat stat = DmAdjustment.Stat.parse(request.target()).orElseThrow(() -> new ValidationException(
                "A STAT adjustment's target is one of " + Arrays.stream(DmAdjustment.Stat.values())
                        .map(Enum::name).collect(Collectors.joining(", "))));
        if (request.amount() == null || request.amount() == 0) {
            throw new ValidationException("A STAT adjustment needs an amount other than 0");
        }
        return new DmAdjustment(UUID.randomUUID(), Kind.STAT, stat.name(), request.amount(), null, null, null,
                reason(request), dmId, Instant.now(), null, null);
    }

    private static DmAdjustment defence(DmAdjustmentRequest request, UUID dmId) {
        refuseUnused(request.amount(), "amount", Kind.DEFENCE);
        refuseUnused(request.feat(), "feat", Kind.DEFENCE);
        if (request.defence() == null) {
            throw new ValidationException("A DEFENCE adjustment needs its defence: RESISTANCE, IMMUNITY or VULNERABILITY");
        }
        String damageType = request.target() != null ? request.target().trim().toLowerCase(Locale.ROOT) : "";
        if (!DamageRules.DAMAGE_TYPES.contains(damageType)) {
            throw new ValidationException("A DEFENCE adjustment's target is a damage type: "
                    + String.join(", ", DamageRules.DAMAGE_TYPES));
        }
        return new DmAdjustment(UUID.randomUUID(), Kind.DEFENCE, damageType, null, request.defence(), null, null,
                reason(request), dmId, Instant.now(), null, null);
    }

    private DmAdjustment feat(PlayerCharacter character, DmAdjustmentRequest request, UUID dmId) {
        refuseUnused(request.target(), "target: the feat names it", Kind.FEAT);
        refuseUnused(request.amount(), "amount", Kind.FEAT);
        refuseUnused(request.defence(), "defence", Kind.FEAT);
        ApplyChoicesRequest.AsiChoice choices = request.feat();
        if (choices == null || choices.getFeatId() == null) {
            throw new ValidationException("A FEAT adjustment needs the feat's ID");
        }
        Feat feat = featRepository.findById(choices.getFeatId())
                .orElseThrow(() -> new ValidationException("Feat not found"));
        if (hasFeat(character, feat.getName()) && !FeatEffectResolver.repeatable(feat)) {
            throw new ConflictException(character.getName() + " already has " + feat.getName()
                    + ", which can't be taken again");
        }
        FeatEffectResolver.AppliedEffects applied = featEffectResolver.applyFeat(character, feat, choices);
        return new DmAdjustment(UUID.randomUUID(), Kind.FEAT, feat.getName(), null, null, feat.getId(),
                CharacterUpdates.recordedEffects(applied, feat.getName()), reason(request), dmId, Instant.now(),
                null, null);
    }

    // ── Helpers ──────────────────────────────────────────────────

    // Whether the character has the feat: a feature a feat added, which an Ability Score Improvement's feat and the
    // DM's grants both add, a level that took it by name, or a grant still active
    private static boolean hasFeat(PlayerCharacter character, String featName) {
        String wanted = featName.trim();
        boolean listed = orEmpty(character.getFeatures()).stream()
                .anyMatch(f -> FeatEffectResolver.FEAT_SOURCE.equals(f.source()) && f.name() != null
                        && f.name().trim().equalsIgnoreCase(wanted));
        boolean taken = false;
        for (LevelHistoryEntry entry : orEmpty(character.getLevelHistory())) {
            AbilityScoreImprovement asi = entry.choices() != null ? entry.choices().asi() : null;
            if (asi != null && asi.featName() != null && asi.featName().trim().equalsIgnoreCase(wanted)) {
                taken = true;
            }
        }
        boolean granted = DmAdjustments.active(character).stream()
                .anyMatch(a -> a.kind() == Kind.FEAT && wanted.equalsIgnoreCase(a.target()));
        return listed || taken || granted;
    }

    // A field another kind uses is refused rather than ignored, so the record holds only what the kind means
    private static void refuseUnused(Object value, String field, Kind kind) {
        if (value != null) {
            throw new ValidationException("A " + kind + " adjustment has no " + field);
        }
    }

    private static String reason(DmAdjustmentRequest request) {
        return request.reason().trim();
    }

    // What the log records of an adjustment: its kind, target and amount, never the reason
    private static String logged(DmAdjustment adjustment) {
        return switch (adjustment.kind()) {
            case STAT -> "STAT " + adjustment.target() + " " + (adjustment.amount() > 0 ? "+" : "") + adjustment.amount();
            case DEFENCE -> "DEFENCE " + adjustment.defence() + " " + adjustment.target();
            case FEAT -> "FEAT " + adjustment.featId() + " (" + adjustment.target() + ")";
        };
    }
}
