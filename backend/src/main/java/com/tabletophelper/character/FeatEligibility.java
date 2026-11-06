package com.tabletophelper.character;

import com.tabletophelper.campaign.AccessPolicy;
import com.tabletophelper.character.dto.ClassSpellcasting;
import com.tabletophelper.character.dto.FeatOptionResponse;
import com.tabletophelper.character.internal.CharacterStats;
import com.tabletophelper.character.state.AbilityScoreImprovement;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.DmAdjustment;
import com.tabletophelper.character.state.FeatTaken;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.reference.Feat;
import com.tabletophelper.reference.FeatEffects;
import com.tabletophelper.reference.FeatPrerequisite;
import com.tabletophelper.reference.FeatRepository;
import com.tabletophelper.reference.Race;
import com.tabletophelper.rules.character.FeatPrerequisites;
import com.tabletophelper.rules.character.FeatPrerequisites.Facts;
import com.tabletophelper.shared.NotFoundException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Whether a character meets each feat's prerequisite (D158, D159), through rules/character/FeatPrerequisites, with
 * what the sheet shows: the ability scores with the DM's adjustments, the race, the spells it can cast, a race's and
 * a feat's included, its classes' Spellcasting or Pact Magic, its armour and weapon proficiencies, its feats, its
 * levels and its background. A player's feat choice that doesn't meet it is refused; a DM's grant isn't. A feat
 * already taken is never taken away because a score or a level drops later.
 */
@Component
@RequiredArgsConstructor
public class FeatEligibility {

    private final CharacterStats characterStats;
    private final FeatRepository featRepository;
    private final CharacterRepository characterRepository;
    private final AccessPolicy accessPolicy;

    /**
     * Every feat as the character could take it, by name: whether it meets each one's prerequisite, what it misses,
     * and whether it has the feat already. For its owner or the DM of its campaign.
     *
     * @throws NotFoundException for a character that doesn't exist
     */
    @Transactional(readOnly = true)
    public List<FeatOptionResponse> options(UUID characterId, UUID userId) {
        PlayerCharacter character = characterRepository.findById(characterId)
                .orElseThrow(() -> new NotFoundException("Character not found"));
        UUID campaignId = character.getCampaign() != null ? character.getCampaign().getId() : null;
        accessPolicy.requireOwnerOrDm(character.getUser().getId(), campaignId, userId);

        Facts facts = facts(character);
        List<FeatOptionResponse> options = new ArrayList<>();
        for (Feat feat : featRepository.findAllByOrderByNameAsc()) {
            List<FeatPrerequisites.PrerequisiteAlternative> prerequisite = FeatPrerequisite.readable(feat);
            List<String> unmet = FeatPrerequisites.unmet(prerequisite, facts);
            options.add(new FeatOptionResponse(feat.getId(), feat.getName(), feat.getSource(), unmet.isEmpty(), unmet,
                    FeatPrerequisites.describe(prerequisite), DmAdjustments.hasFeat(character, feat.getName()),
                    FeatEffects.readable(feat).repeatable()));
        }
        return options;
    }

    /**
     * Refuses a feat a player chooses whose prerequisite the character doesn't meet, naming what it misses (D159).
     *
     * @throws ValidationException when it doesn't meet it
     */
    public void requireMet(PlayerCharacter character, Feat feat) {
        List<String> unmet = unmet(character, feat);
        if (!unmet.isEmpty()) {
            throw new ValidationException(character.getName() + " doesn't meet the prerequisite of " + feat.getName()
                    + ": " + String.join("; ", unmet));
        }
    }

    /** What the character doesn't meet of the feat's prerequisite, in words; empty when it meets it. */
    public List<String> unmet(PlayerCharacter character, Feat feat) {
        return FeatPrerequisites.unmet(FeatPrerequisite.readable(feat), facts(character));
    }

    /**
     * Each feat the character has whose prerequisite it wouldn't meet now, with what it misses, as "Grappler (level 4
     * Ability Score Improvement): Strength 13 (you have 12)". It keeps them (D159); the recompute-characters job lists
     * them. A feat the data no longer has is left out.
     */
    public List<String> heldFeatsUnmet(PlayerCharacter character) {
        Facts facts = facts(character);
        List<String> unmet = new ArrayList<>();
        for (HeldFeat held : heldFeats(character)) {
            Optional<Feat> feat = held.featId() != null ? featRepository.findById(held.featId()) : Optional.empty();
            if (feat.isEmpty() && held.name() != null) {
                feat = featRepository.findByNameIgnoreCase(held.name().trim());
            }
            if (feat.isEmpty()) continue;
            List<String> missing = FeatPrerequisites.unmet(FeatPrerequisite.readable(feat.get()), facts);
            if (!missing.isEmpty()) {
                unmet.add(feat.get().getName() + " (" + held.how() + "): " + String.join("; ", missing));
            }
        }
        return unmet;
    }

    // ── The facts ────────────────────────────────────────────────

    /** What the character brings to a feat's prerequisite now. */
    public Facts facts(PlayerCharacter character) {
        DerivedStats derived = characterStats.derive(character);
        Race race = character.getRaceRef();
        int level = character.getLevel() != null ? character.getLevel() : 1;
        boolean classCasts = derived.spellcasting().stream().anyMatch(FeatEligibility::casts);
        boolean otherSpell = CharacterUpdates.orEmpty(character.getSpellsKnown()).stream()
                .anyMatch(spell -> available(spell, level));
        Map<String, Integer> classLevels = new LinkedHashMap<>();
        for (ClassEntry entry : CharacterUpdates.orEmpty(character.getMulticlassEntries())) {
            if (entry.className() != null) {
                classLevels.merge(entry.className(), entry.level(), Integer::sum);
            }
        }
        if (classLevels.isEmpty() && character.getCharacterClass() != null) {
            classLevels.put(character.getCharacterClass(), level);
        }
        String background = character.getBackgroundRef() != null ? character.getBackgroundRef().getName()
                : character.getBackground();
        return new Facts(derived.abilityScores(),
                race != null ? race.getName() : character.getRace(),
                race != null ? race.getBaseRaceName() : null,
                race != null ? race.getSize() : null,
                classCasts || otherSpell, classCasts,
                character.getArmorProficiencies(), character.getWeaponProficiencies(),
                DmAdjustments.featNames(character), level, classLevels, background);
    }

    // A class's casting gives the Spellcasting or Pact Magic feature once it has a spell or cantrip at its level, so
    // a 1st-level Paladin's doesn't yet
    private static boolean casts(ClassSpellcasting casting) {
        return casting.highestSpellLevel() > 0 || casting.cantripsKnown() > 0;
    }

    // A spell the character can cast now: a race's spell that unlocks at a later level can't be yet
    private static boolean available(KnownSpell spell, int level) {
        return spell.unlocksAtLevel() == null || spell.unlocksAtLevel() <= level;
    }

    // ── The feats held ───────────────────────────────────────────

    private record HeldFeat(UUID featId, String name, String how) {
    }

    // Each feat the level history records and each active DM grant, in that order
    private static List<HeldFeat> heldFeats(PlayerCharacter character) {
        List<HeldFeat> held = new ArrayList<>();
        for (LevelHistoryEntry entry : CharacterUpdates.orEmpty(character.getLevelHistory())) {
            if (entry.choices() == null) continue;
            for (FeatTaken feat : CharacterUpdates.orEmpty(entry.choices().feats())) {
                held.add(new HeldFeat(feat.featId(), feat.featName(), "level " + entry.characterLevel()
                        + " race or background feat"));
            }
            AbilityScoreImprovement asi = entry.choices().asi();
            if (asi != null && asi.featName() != null) {
                held.add(new HeldFeat(asi.featId(), asi.featName(), "level " + entry.characterLevel()
                        + " Ability Score Improvement"));
            }
        }
        for (DmAdjustment adjustment : DmAdjustments.active(character)) {
            if (adjustment.kind() == DmAdjustment.Kind.FEAT) {
                held.add(new HeldFeat(adjustment.featId(), adjustment.target(), "DM grant"));
            }
        }
        return held;
    }
}
