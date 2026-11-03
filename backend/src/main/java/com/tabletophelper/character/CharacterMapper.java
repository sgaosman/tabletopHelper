package com.tabletophelper.character;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.character.dto.BaseAbilityScores;
import com.tabletophelper.character.dto.CharacterResponse;
import com.tabletophelper.character.internal.CharacterStats;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.reference.Subclass;
import com.tabletophelper.reference.SubclassRepository;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.combat.DamageRules.Defence;
import com.tabletophelper.shared.ConflictException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A character as the API returns it: its sheet, with what each class casts with and the ability score maximums
 * worked out by CharacterStats, since the sheet stores neither (D158, D167), and whether it is in an encounter that
 * is under way and whether it has died (D166). The ability scores, armour class and damage defences include the active
 * DM Adjustments, as the stored Derived Stats do, and the adjustments themselves come with them, the removed ones as
 * the record. So do the Pending Choices its levels still owe (D159).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CharacterMapper {

    private static final TypeReference<Map<String, List<String>>> SPELLS_BY_CLASS_LEVEL = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final SubclassRepository subclassRepository;
    private final CharacterStats characterStats;
    private final LiveEncounters liveEncounters;

    public CharacterResponse toResponse(PlayerCharacter c) {
        return CharacterResponse.builder()
                .id(c.getId())
                .userId(c.getUser().getId())
                .ownerDisplayName(c.getUser().getDisplayName())
                .campaignId(c.getCampaign() != null ? c.getCampaign().getId() : null)
                .raceId(c.getRaceRef() != null ? c.getRaceRef().getId() : null)
                .raceName(c.getRaceRef() != null ? c.getRaceRef().getName() : null)
                .classId(c.getClassRef() != null ? c.getClassRef().getId() : null)
                .className(c.getClassRef() != null ? c.getClassRef().getName() : null)
                .subclassId(c.getSubclassRef() != null ? c.getSubclassRef().getId() : null)
                .subclassName(c.getSubclassRef() != null ? c.getSubclassRef().getName() : null)
                .backgroundId(c.getBackgroundRef() != null ? c.getBackgroundRef().getId() : null)
                .backgroundName(c.getBackgroundRef() != null ? c.getBackgroundRef().getName() : null)
                .name(c.getName())
                .race(c.getRace())
                .characterClass(c.getCharacterClass())
                .subclass(c.getSubclass())
                .level(c.getLevel())
                .experiencePoints(c.getExperiencePoints())
                .background(c.getBackground())
                .alignment(c.getAlignment())
                .strength(DmAdjustments.abilityScore(c, Ability.STR))
                .dexterity(DmAdjustments.abilityScore(c, Ability.DEX))
                .constitution(DmAdjustments.abilityScore(c, Ability.CON))
                .intelligence(DmAdjustments.abilityScore(c, Ability.INT))
                .wisdom(DmAdjustments.abilityScore(c, Ability.WIS))
                .charisma(DmAdjustments.abilityScore(c, Ability.CHA))
                .baseAbilityScores(new BaseAbilityScores(
                        CharacterUpdates.score(c, Ability.STR), CharacterUpdates.score(c, Ability.DEX),
                        CharacterUpdates.score(c, Ability.CON), CharacterUpdates.score(c, Ability.INT),
                        CharacterUpdates.score(c, Ability.WIS), CharacterUpdates.score(c, Ability.CHA)))
                .hpMax(c.getHpMax())
                .hpCurrent(c.getHpCurrent())
                .hpTemp(c.getHpTemp())
                .armourClass(DmAdjustments.armourClass(c))
                .initiativeBonus(c.getInitiativeBonus())
                .speed(c.getSpeed())
                .proficiencyBonus(c.getProficiencyBonus())
                .savingThrowProficiencies(c.getSavingThrowProficiencies())
                .skillProficiencies(c.getSkillProficiencies())
                .skillExpertises(c.getSkillExpertises())
                .armorProficiencies(c.getArmorProficiencies())
                .weaponProficiencies(c.getWeaponProficiencies())
                .toolProficiencies(c.getToolProficiencies())
                .languageProficiencies(c.getLanguageProficiencies())
                .damageResistances(DmAdjustments.damageTypes(c, Defence.RESISTANCE))
                .damageImmunities(DmAdjustments.damageTypes(c, Defence.IMMUNITY))
                .damageVulnerabilities(DmAdjustments.damageTypes(c, Defence.VULNERABILITY))
                .conditionImmunities(c.getConditionImmunities())
                .features(c.getFeatures())
                .spellsKnown(c.getSpellsKnown())
                .spellSlots(c.getSpellSlots())
                .spellSaveDc(c.getSpellSaveDc())
                .spellAttackBonus(c.getSpellAttackBonus())
                .spellcastingAbility(characterStats.mainSpellcastingAbility(c))
                .spellcasting(characterStats.spellcasting(c))
                .abilityScoreMaximums(characterStats.abilityScoreMaximums(c))
                .subclassAlwaysPreparedSpells(buildAllSubclassAlwaysPreparedSpells(c))
                .equipment(c.getEquipment())
                .currency(c.getCurrency())
                .personalityTraits(c.getPersonalityTraits())
                .ideals(c.getIdeals())
                .bonds(c.getBonds())
                .flaws(c.getFlaws())
                .notes(c.getNotes())
                .deathSaveSuccesses(c.getDeathSaveSuccesses())
                .deathSaveFailures(c.getDeathSaveFailures())
                .portraitUrl(c.getPortraitUrl())
                .abilityScoreMethod(c.getAbilityScoreMethod())
                .racialAbilityBonuses(c.getRacialAbilityBonuses())
                .multiclassEntries(c.getMulticlassEntries())
                .hitDice(c.getHitDice())
                .levelHistory(c.getLevelHistory())
                .resourcePools(c.getResourcePools())
                .dmAdjustments(c.getDmAdjustments() != null ? c.getDmAdjustments() : List.of())
                .pendingChoices(c.getPendingChoices() != null ? c.getPendingChoices() : List.of())
                .isActive(c.getIsActive())
                .inLiveEncounter(c.getId() != null && liveEncounters.includes(c.getId()))
                .dead(c.isDead())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    // The always-prepared spells of the primary subclass, then of each other class's subclass; null for none
    private Map<String, Map<String, List<String>>> buildAllSubclassAlwaysPreparedSpells(PlayerCharacter c) {
        Map<String, Map<String, List<String>>> combined = new LinkedHashMap<>();

        if (c.getSubclassRef() != null) {
            alwaysPreparedSpells(c.getSubclassRef()).ifPresent(spells -> combined.put(c.getSubclassRef().getName(), spells));
        }

        if (c.getMulticlassEntries() != null) {
            for (ClassEntry entry : c.getMulticlassEntries()) {
                UUID scId = entry.subclassId();
                if (scId == null) continue;
                if (c.getSubclassRef() != null && scId.equals(c.getSubclassRef().getId())) continue;
                subclassRepository.findById(scId).ifPresent(sc ->
                        alwaysPreparedSpells(sc).ifPresent(spells -> combined.put(sc.getName(), spells)));
            }
        }

        return combined.isEmpty() ? null : combined;
    }

    // A subclass's always-prepared spells by the class level they come at, as the reference data stores them; data
    // that can't be read is a 409 rather than a sheet without them (D155)
    private Optional<Map<String, List<String>>> alwaysPreparedSpells(Subclass subclass) {
        if (subclass.getAlwaysPreparedSpells() == null) return Optional.empty();
        try {
            return Optional.ofNullable(objectMapper.readValue(subclass.getAlwaysPreparedSpells(), SPELLS_BY_CLASS_LEVEL));
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse always-prepared spells for subclass {}", subclass.getName(), e);
            throw new ConflictException("The always-prepared spells of the subclass " + subclass.getName()
                    + " can't be read");
        }
    }
}
