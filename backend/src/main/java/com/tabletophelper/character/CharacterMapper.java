package com.tabletophelper.character;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tabletophelper.character.dto.CharacterResponse;
import com.tabletophelper.reference.SubclassRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class CharacterMapper {

    private final ObjectMapper objectMapper;
    private final SubclassRepository subclassRepository;

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
                .hitDiceTotal(c.getHitDiceTotal())
                .hitDiceRemaining(c.getHitDiceRemaining())
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
                .spellcastingAbility(c.getSpellcastingAbility())
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
                .attunedItems(c.getAttunedItems())
                .equippedItems(c.getEquippedItems())
                .hitDiceMap(c.getHitDiceMap())
                .levelHistory(c.getLevelHistory())
                .featResources(c.getFeatResources())
                .isActive(c.getIsActive())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    @SuppressWarnings("unchecked")
    private String buildAllSubclassAlwaysPreparedSpells(PlayerCharacter c) {
        try {
            ObjectNode combined = objectMapper.createObjectNode();

            if (c.getSubclassRef() != null && c.getSubclassRef().getAlwaysPreparedSpells() != null) {
                combined.set(c.getSubclassRef().getName(),
                        objectMapper.readTree(c.getSubclassRef().getAlwaysPreparedSpells()));
            }

            if (c.getMulticlassEntries() != null) {
                List<Map<String, Object>> entries = objectMapper.readValue(c.getMulticlassEntries(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
                for (Map<String, Object> entry : entries) {
                    String subclassId = (String) entry.get("subclassId");
                    if (subclassId == null) continue;
                    UUID scId = UUID.fromString(subclassId);
                    if (c.getSubclassRef() != null && scId.equals(c.getSubclassRef().getId())) continue;
                    subclassRepository.findById(scId).ifPresent(sc -> {
                        if (sc.getAlwaysPreparedSpells() != null) {
                            try {
                                combined.set(sc.getName(),
                                        objectMapper.readTree(sc.getAlwaysPreparedSpells()));
                            } catch (Exception e) {
                                log.warn("Failed to parse always-prepared spells for subclass {}", sc.getName(), e);
                            }
                        }
                    });
                }
            }

            return combined.isEmpty() ? null : objectMapper.writeValueAsString(combined);
        } catch (Exception e) {
            if (c.getSubclassRef() != null) return c.getSubclassRef().getAlwaysPreparedSpells();
            return null;
        }
    }
}
