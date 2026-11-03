package com.tabletophelper.character;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.character.dto.CharacterResponse;
import com.tabletophelper.character.internal.CharacterStats;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.reference.Subclass;
import com.tabletophelper.reference.SubclassRepository;
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
 * is under way (D166).
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
                .strength(c.getStrength())
                .dexterity(c.getDexterity())
                .constitution(c.getConstitution())
                .intelligence(c.getIntelligence())
                .wisdom(c.getWisdom())
                .charisma(c.getCharisma())
                .hpMax(c.getHpMax())
                .hpCurrent(c.getHpCurrent())
                .hpTemp(c.getHpTemp())
                .armourClass(c.getArmourClass())
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
                .damageResistances(c.getDamageResistances())
                .damageImmunities(c.getDamageImmunities())
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
                .isActive(c.getIsActive())
                .inLiveEncounter(c.getId() != null && liveEncounters.includes(c.getId()))
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
