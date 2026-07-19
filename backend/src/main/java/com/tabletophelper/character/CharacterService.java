package com.tabletophelper.character;

import com.tabletophelper.campaign.Campaign;
import com.tabletophelper.campaign.CampaignMemberRepository;
import com.tabletophelper.campaign.CampaignRepository;
import com.tabletophelper.character.dto.CharacterCreateRequest;
import com.tabletophelper.character.dto.CharacterResponse;
import com.tabletophelper.character.dto.CharacterUpdateRequest;
import com.tabletophelper.encounter.EncounterParticipantRepository;
import com.tabletophelper.encounter.EncounterStatus;
import com.tabletophelper.reference.*;
import com.tabletophelper.user.User;
import com.tabletophelper.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CharacterService {

    private final CharacterRepository characterRepository;
    private final UserRepository userRepository;
    private final CampaignRepository campaignRepository;
    private final CampaignMemberRepository campaignMemberRepository;
    private final RaceRepository raceRepository;
    private final CharacterClassRepository characterClassRepository;
    private final SubclassRepository subclassRepository;
    private final BackgroundRepository backgroundRepository;
    private final EncounterParticipantRepository encounterParticipantRepository;

    @Transactional
    public CharacterResponse createCharacter(CharacterCreateRequest request, UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Campaign campaign = null;
        if (request.getCampaignId() != null) {
            campaign = campaignRepository.findById(request.getCampaignId())
                    .orElseThrow(() -> new IllegalArgumentException("Campaign not found"));
        }

        Race raceRef = null;
        if (request.getRaceId() != null) {
            raceRef = raceRepository.findById(request.getRaceId())
                    .orElseThrow(() -> new IllegalArgumentException("Race not found"));
        }

        CharacterClass classRef = null;
        if (request.getClassId() != null) {
            classRef = characterClassRepository.findById(request.getClassId())
                    .orElseThrow(() -> new IllegalArgumentException("Class not found"));
        }

        Subclass subclassRef = null;
        if (request.getSubclassId() != null) {
            subclassRef = subclassRepository.findById(request.getSubclassId())
                    .orElseThrow(() -> new IllegalArgumentException("Subclass not found"));
        }

        Background backgroundRef = null;
        if (request.getBackgroundId() != null) {
            backgroundRef = backgroundRepository.findById(request.getBackgroundId())
                    .orElseThrow(() -> new IllegalArgumentException("Background not found"));
        }

        String raceName = raceRef != null ? raceRef.getName() : request.getRace();
        String className = classRef != null ? classRef.getName() : request.getCharacterClass();
        String subclassName = subclassRef != null ? subclassRef.getName() : request.getSubclass();
        String backgroundName = backgroundRef != null ? backgroundRef.getName() : request.getBackground();

        int level = request.getLevel() != null ? request.getLevel() : 1;
        int profBonus = proficiencyBonusForLevel(level);

        Integer speed = request.getSpeed();
        if (speed == null && raceRef != null) {
            speed = extractWalkSpeed(raceRef.getSpeed());
        }
        if (speed == null) speed = 30;

        PlayerCharacter character = PlayerCharacter.builder()
                .user(user)
                .campaign(campaign)
                .raceRef(raceRef)
                .classRef(classRef)
                .subclassRef(subclassRef)
                .backgroundRef(backgroundRef)
                .name(request.getName())
                .race(raceName)
                .characterClass(className)
                .subclass(subclassName)
                .level(level)
                .background(backgroundName)
                .alignment(request.getAlignment())
                .abilityScoreMethod(request.getAbilityScoreMethod())
                .racialAbilityBonuses(request.getRacialAbilityBonuses())
                .strength(request.getStrength())
                .dexterity(request.getDexterity())
                .constitution(request.getConstitution())
                .intelligence(request.getIntelligence())
                .wisdom(request.getWisdom())
                .charisma(request.getCharisma())
                .hpMax(request.getHpMax())
                .hpCurrent(request.getHpMax())
                .armourClass(request.getArmourClass() != null ? request.getArmourClass() : 10 + abilityMod(request.getDexterity()))
                .initiativeBonus(request.getInitiativeBonus() != null ? request.getInitiativeBonus() : abilityMod(request.getDexterity()))
                .speed(speed)
                .proficiencyBonus(request.getProficiencyBonus() != null ? request.getProficiencyBonus() : profBonus)
                .savingThrowProficiencies(request.getSavingThrowProficiencies())
                .skillProficiencies(request.getSkillProficiencies())
                .armorProficiencies(request.getArmorProficiencies())
                .weaponProficiencies(request.getWeaponProficiencies())
                .toolProficiencies(request.getToolProficiencies())
                .languageProficiencies(request.getLanguageProficiencies())
                .features(request.getFeatures())
                .damageResistances(request.getDamageResistances())
                .spellsKnown(request.getSpellsKnown())
                .spellSlots(request.getSpellSlots())
                .spellSaveDc(request.getSpellSaveDc())
                .spellAttackBonus(request.getSpellAttackBonus())
                .spellcastingAbility(request.getSpellcastingAbility())
                .equipment(request.getEquipment())
                .currency(request.getCurrency() != null ? request.getCurrency() : "{\"cp\":0,\"sp\":0,\"ep\":0,\"gp\":0,\"pp\":0}")
                .hitDiceMap(request.getHitDiceMap())
                .preparedSpells(request.getPreparedSpells())
                .build();

        if (classRef != null && character.getHitDiceTotal() == null) {
            character.setHitDiceTotal(level + "d" + classRef.getHitDice());
            character.setHitDiceRemaining(character.getHitDiceTotal());
        }

        character = characterRepository.save(character);
        return toResponse(character);
    }

    @Transactional
    public CharacterResponse updateCharacter(UUID characterId, CharacterUpdateRequest request, UUID userId) {
        PlayerCharacter character = characterRepository.findById(characterId)
                .orElseThrow(() -> new IllegalArgumentException("Character not found"));

        if (!character.getUser().getId().equals(userId)) {
            throw new IllegalArgumentException("You do not own this character");
        }

        if (request.getName() != null) character.setName(request.getName());
        if (request.getRace() != null) character.setRace(request.getRace());
        if (request.getCharacterClass() != null) character.setCharacterClass(request.getCharacterClass());
        if (request.getSubclass() != null) character.setSubclass(request.getSubclass());
        if (request.getLevel() != null) character.setLevel(request.getLevel());
        if (request.getExperiencePoints() != null) character.setExperiencePoints(request.getExperiencePoints());
        if (request.getBackground() != null) character.setBackground(request.getBackground());
        if (request.getAlignment() != null) character.setAlignment(request.getAlignment());
        if (request.getAbilityScoreMethod() != null) character.setAbilityScoreMethod(request.getAbilityScoreMethod());
        if (request.getRacialAbilityBonuses() != null) character.setRacialAbilityBonuses(request.getRacialAbilityBonuses());

        if (request.getRaceId() != null) {
            Race r = raceRepository.findById(request.getRaceId())
                    .orElseThrow(() -> new IllegalArgumentException("Race not found"));
            character.setRaceRef(r);
            character.setRace(r.getName());
        }
        if (request.getClassId() != null) {
            CharacterClass cc = characterClassRepository.findById(request.getClassId())
                    .orElseThrow(() -> new IllegalArgumentException("Class not found"));
            character.setClassRef(cc);
            character.setCharacterClass(cc.getName());
        }
        if (request.getSubclassId() != null) {
            Subclass sc = subclassRepository.findById(request.getSubclassId())
                    .orElseThrow(() -> new IllegalArgumentException("Subclass not found"));
            character.setSubclassRef(sc);
            character.setSubclass(sc.getName());
        }
        if (request.getBackgroundId() != null) {
            Background bg = backgroundRepository.findById(request.getBackgroundId())
                    .orElseThrow(() -> new IllegalArgumentException("Background not found"));
            character.setBackgroundRef(bg);
            character.setBackground(bg.getName());
        }

        if (request.getStrength() != null) character.setStrength(request.getStrength());
        if (request.getDexterity() != null) character.setDexterity(request.getDexterity());
        if (request.getConstitution() != null) character.setConstitution(request.getConstitution());
        if (request.getIntelligence() != null) character.setIntelligence(request.getIntelligence());
        if (request.getWisdom() != null) character.setWisdom(request.getWisdom());
        if (request.getCharisma() != null) character.setCharisma(request.getCharisma());

        if (request.getHpMax() != null) character.setHpMax(request.getHpMax());
        if (request.getHpCurrent() != null) character.setHpCurrent(request.getHpCurrent());
        if (request.getHpTemp() != null) character.setHpTemp(request.getHpTemp());
        if (request.getHitDiceTotal() != null) character.setHitDiceTotal(request.getHitDiceTotal());
        if (request.getHitDiceRemaining() != null) character.setHitDiceRemaining(request.getHitDiceRemaining());
        if (request.getArmourClass() != null) character.setArmourClass(request.getArmourClass());
        if (request.getInitiativeBonus() != null) character.setInitiativeBonus(request.getInitiativeBonus());
        if (request.getSpeed() != null) character.setSpeed(request.getSpeed());
        if (request.getProficiencyBonus() != null) character.setProficiencyBonus(request.getProficiencyBonus());

        if (request.getSavingThrowProficiencies() != null) character.setSavingThrowProficiencies(request.getSavingThrowProficiencies());
        if (request.getSkillProficiencies() != null) character.setSkillProficiencies(request.getSkillProficiencies());
        if (request.getSkillExpertises() != null) character.setSkillExpertises(request.getSkillExpertises());
        if (request.getArmorProficiencies() != null) character.setArmorProficiencies(request.getArmorProficiencies());
        if (request.getWeaponProficiencies() != null) character.setWeaponProficiencies(request.getWeaponProficiencies());
        if (request.getToolProficiencies() != null) character.setToolProficiencies(request.getToolProficiencies());
        if (request.getLanguageProficiencies() != null) character.setLanguageProficiencies(request.getLanguageProficiencies());
        if (request.getDamageResistances() != null) character.setDamageResistances(request.getDamageResistances());
        if (request.getDamageImmunities() != null) character.setDamageImmunities(request.getDamageImmunities());
        if (request.getConditionImmunities() != null) character.setConditionImmunities(request.getConditionImmunities());

        if (request.getFeatures() != null) character.setFeatures(request.getFeatures());
        if (request.getSpellsKnown() != null) character.setSpellsKnown(request.getSpellsKnown());
        if (request.getSpellSlots() != null) character.setSpellSlots(request.getSpellSlots());
        if (request.getSpellSaveDc() != null) character.setSpellSaveDc(request.getSpellSaveDc());
        if (request.getSpellAttackBonus() != null) character.setSpellAttackBonus(request.getSpellAttackBonus());
        if (request.getSpellcastingAbility() != null) character.setSpellcastingAbility(request.getSpellcastingAbility());

        if (request.getEquipment() != null) character.setEquipment(request.getEquipment());
        if (request.getCurrency() != null) character.setCurrency(request.getCurrency());

        if (request.getPersonalityTraits() != null) character.setPersonalityTraits(request.getPersonalityTraits());
        if (request.getIdeals() != null) character.setIdeals(request.getIdeals());
        if (request.getBonds() != null) character.setBonds(request.getBonds());
        if (request.getFlaws() != null) character.setFlaws(request.getFlaws());
        if (request.getNotes() != null) character.setNotes(request.getNotes());
        if (request.getPortraitUrl() != null) character.setPortraitUrl(request.getPortraitUrl());

        if (request.getMulticlassEntries() != null) character.setMulticlassEntries(request.getMulticlassEntries());
        if (request.getPreparedSpells() != null) character.setPreparedSpells(request.getPreparedSpells());
        if (request.getAttunedItems() != null) character.setAttunedItems(request.getAttunedItems());
        if (request.getEquippedItems() != null) character.setEquippedItems(request.getEquippedItems());
        if (request.getHitDiceMap() != null) character.setHitDiceMap(request.getHitDiceMap());

        if (Boolean.TRUE.equals(request.getClearCampaign())) {
            character.setCampaign(null);
        } else if (request.getCampaignId() != null) {
            Campaign campaign = campaignRepository.findById(request.getCampaignId())
                    .orElseThrow(() -> new IllegalArgumentException("Campaign not found"));
            character.setCampaign(campaign);
        }

        character = characterRepository.save(character);
        return toResponse(character);
    }

    @Transactional
    public void deleteCharacter(UUID characterId, UUID userId) {
        PlayerCharacter character = characterRepository.findById(characterId)
                .orElseThrow(() -> new IllegalArgumentException("Character not found"));

        if (!character.getUser().getId().equals(userId)) {
            throw new IllegalArgumentException("You do not own this character");
        }

        boolean inActiveCombat = encounterParticipantRepository
                .existsByCharacter_IdAndEncounter_StatusIn(characterId,
                        List.of(EncounterStatus.ACTIVE, EncounterStatus.PAUSED, EncounterStatus.PREPARING));

        if (inActiveCombat) {
            throw new IllegalStateException("Cannot delete a character that is in an active encounter");
        }

        character.setIsActive(false);
        characterRepository.save(character);
    }

    @Transactional(readOnly = true)
    public List<CharacterResponse> getMyCharacters(UUID userId) {
        return characterRepository.findByUserIdAndIsActiveTrue(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CharacterResponse> getCharactersInCampaign(UUID campaignId, UUID userId) {
        if (!campaignMemberRepository.existsByCampaignIdAndUserId(campaignId, userId)) {
            throw new IllegalArgumentException("You are not a member of this campaign");
        }
        return characterRepository.findByCampaignIdAndIsActiveTrue(campaignId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public CharacterResponse getCharacter(UUID characterId) {
        PlayerCharacter character = characterRepository.findById(characterId)
                .orElseThrow(() -> new IllegalArgumentException("Character not found"));
        return toResponse(character);
    }

    private CharacterResponse toResponse(PlayerCharacter c) {
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
                .preparedSpells(c.getPreparedSpells())
                .attunedItems(c.getAttunedItems())
                .equippedItems(c.getEquippedItems())
                .hitDiceMap(c.getHitDiceMap())
                .isActive(c.getIsActive())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    static int abilityMod(Integer score) {
        if (score == null) return 0;
        return Math.floorDiv(score - 10, 2);
    }

    static int proficiencyBonusForLevel(int level) {
        if (level <= 4) return 2;
        if (level <= 8) return 3;
        if (level <= 12) return 4;
        if (level <= 16) return 5;
        return 6;
    }

    private Integer extractWalkSpeed(String speedJson) {
        if (speedJson == null) return null;
        try {
            if (speedJson.trim().startsWith("{")) {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var node = mapper.readTree(speedJson);
                if (node.has("walk")) return node.get("walk").asInt();
            }
            return Integer.parseInt(speedJson.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
