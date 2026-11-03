package com.tabletophelper.character;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.character.dto.ApplyChoicesRequest;
import com.tabletophelper.character.state.AppliedFeatEffects;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.identity.User;
import com.tabletophelper.reference.Feat;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeatEffectResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private SpellRepository spellRepository;

    private FeatEffectResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new FeatEffectResolver(objectMapper, spellRepository);
    }

    private PlayerCharacter baseCharacter() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setDisplayName("Test");

        return PlayerCharacter.builder()
                .id(UUID.randomUUID())
                .user(user)
                .name("Thorin")
                .level(5)
                .strength(15)
                .dexterity(14)
                .constitution(14)
                .intelligence(10)
                .wisdom(12)
                .charisma(8)
                .hpMax(38)
                .hpCurrent(38)
                .hpTemp(0)
                .armourClass(16)
                .initiativeBonus(2)
                .speed(30)
                .proficiencyBonus(3)
                .armorProficiencies(List.of())
                .weaponProficiencies(List.of())
                .toolProficiencies(List.of())
                .skillProficiencies(List.of("Athletics", "Perception"))
                .skillExpertises(List.of())
                .languageProficiencies(List.of())
                .savingThrowProficiencies(List.of("STR", "CON"))
                .damageResistances(List.of())
                .features(List.of())
                .spellsKnown(null)
                .featResources(null)
                .build();
    }

    private Feat buildFeat(String name, String abilityScoreIncrease, String effects, String grantsFeatures) {
        return Feat.builder()
                .id(UUID.randomUUID())
                .name(name)
                .description(name + " feat description")
                .abilityScoreIncrease(abilityScoreIncrease)
                .effects(effects)
                .grantsFeatures(grantsFeatures)
                .build();
    }

    // --- 6.1 Ability score increase: fixed bonus ---

    @Test
    @DisplayName("6.1 Fixed ASI: Heavily Armored grants +1 STR")
    void fixedAsiHeavilyArmored() throws Exception {
        PlayerCharacter c = baseCharacter();
        assertEquals(15, c.getStrength());

        Feat feat = buildFeat("Heavily Armored",
                "[{\"str\": 1}]",
                "{\"armorProficiencies\": [{\"heavy\": true}]}",
                null);

        FeatEffectResolver.AppliedEffects effects = resolver.applyFeat(c, feat, null);

        assertEquals(16, c.getStrength());
        assertEquals(1, effects.abilityIncreases().get("str"));
    }

    // --- 6.2 Half-feat choice: choose one ability ---

    @Test
    @DisplayName("6.2 Half-feat choice: Skill Expert grants +1 to chosen DEX")
    void halfFeatChoiceSkillExpert() throws Exception {
        PlayerCharacter c = baseCharacter();
        assertEquals(14, c.getDexterity());

        Feat feat = buildFeat("Skill Expert",
                "[{\"choose\": {\"count\": 1, \"amount\": 1}}]",
                "{\"skillProficiencies\": [{\"choose\": {\"count\": 1}}], \"expertise\": [{\"anyProficientSkill\": 1}]}",
                null);

        ApplyChoicesRequest.AsiChoice choices = new ApplyChoicesRequest.AsiChoice();
        choices.setFeatAbility("dexterity");
        choices.setSkillProficiencyChoices(List.of("Stealth"));
        choices.setExpertiseSkillChoices(List.of("Perception"));

        FeatEffectResolver.AppliedEffects effects = resolver.applyFeat(c, feat, choices);

        assertEquals(15, c.getDexterity());
        assertEquals(1, effects.abilityIncreases().get("dexterity"));
    }

    // --- 6.3 Proficiency grant ---

    @Test
    @DisplayName("6.3 Proficiency grant: Moderately Armored adds medium armor and shields")
    void proficiencyGrantModeratelyArmored() throws Exception {
        PlayerCharacter c = baseCharacter();

        Feat feat = buildFeat("Moderately Armored",
                "[{\"choose\": {\"count\": 1, \"amount\": 1}}]",
                "{\"armorProficiencies\": [{\"medium\": true, \"shield\": true}]}",
                null);

        ApplyChoicesRequest.AsiChoice choices = new ApplyChoicesRequest.AsiChoice();
        choices.setFeatAbility("dexterity");

        FeatEffectResolver.AppliedEffects effects = resolver.applyFeat(c, feat, choices);

        assertTrue(effects.armorProficienciesAdded().contains("Medium"));
        assertTrue(effects.armorProficienciesAdded().contains("Shield"));
        assertTrue(c.getArmorProficiencies().contains("Medium"));
        assertTrue(c.getArmorProficiencies().contains("Shield"));
    }

    // --- 6.4 Expertise grant ---

    @Test
    @DisplayName("6.4 Expertise grant: Skill Expert adds expertise to Perception")
    void expertiseGrantSkillExpert() throws Exception {
        PlayerCharacter c = baseCharacter();

        Feat feat = buildFeat("Skill Expert",
                null,
                "{\"expertise\": [{\"anyProficientSkill\": 1}]}",
                null);

        ApplyChoicesRequest.AsiChoice choices = new ApplyChoicesRequest.AsiChoice();
        choices.setExpertiseSkillChoices(List.of("Perception"));

        FeatEffectResolver.AppliedEffects effects = resolver.applyFeat(c, feat, choices);

        assertTrue(effects.expertiseAdded().contains("Perception"));
        assertTrue(c.getSkillExpertises().contains("Perception"));
    }

    // --- 6.5 Speed bonus: Mobile ---

    @Test
    @DisplayName("6.5 Speed bonus: Mobile adds +10 speed")
    void speedBonusMobile() throws Exception {
        PlayerCharacter c = baseCharacter();
        assertEquals(30, c.getSpeed());

        Feat feat = buildFeat("Mobile", null, "{\"speedBonus\": 10}", null);

        FeatEffectResolver.AppliedEffects effects = resolver.applyFeat(c, feat, null);

        assertEquals(40, c.getSpeed());
        assertEquals(10, effects.speedBonus());
    }

    // --- 6.6 HP per level: Tough ---

    @Test
    @DisplayName("6.6 HP per level: Tough adds 2 * level HP")
    void hpPerLevelTough() throws Exception {
        PlayerCharacter c = baseCharacter();
        assertEquals(38, c.getHpMax());
        assertEquals(38, c.getHpCurrent());

        Feat feat = buildFeat("Tough", null, "{\"hpPerLevel\": 2}", null);

        FeatEffectResolver.AppliedEffects effects = resolver.applyFeat(c, feat, null);

        assertEquals(48, c.getHpMax());
        assertEquals(48, c.getHpCurrent());
        assertEquals(2, effects.hpPerLevel());
    }

    // --- 6.7 Passive stat bonus: Observant ---

    @Test
    @DisplayName("6.7 Passive stat bonus: Observant adds +5 passive Perception")
    void passiveStatBonusObservant() throws Exception {
        PlayerCharacter c = baseCharacter();

        Feat feat = buildFeat("Observant",
                "[{\"choose\": {\"count\": 1, \"amount\": 1}}]",
                "{\"passivePerceptionBonus\": 5, \"passiveInvestigationBonus\": 5}",
                null);

        ApplyChoicesRequest.AsiChoice choices = new ApplyChoicesRequest.AsiChoice();
        choices.setFeatAbility("wisdom");

        FeatEffectResolver.AppliedEffects effects = resolver.applyFeat(c, feat, choices);

        assertEquals(5, effects.passivePerceptionBonus());
        assertEquals(5, effects.passiveInvestigationBonus());
    }

    // --- 6.8 Initiative bonus: Alert ---

    @Test
    @DisplayName("6.8 Initiative bonus: Alert adds +5 initiative")
    void initiativeBonusAlert() throws Exception {
        PlayerCharacter c = baseCharacter();
        assertEquals(2, c.getInitiativeBonus());

        Feat feat = buildFeat("Alert", null, "{\"initiativeBonus\": 5}", null);

        FeatEffectResolver.AppliedEffects effects = resolver.applyFeat(c, feat, null);

        assertEquals(7, c.getInitiativeBonus());
        assertEquals(5, effects.initiativeBonus());
    }

    // --- 6.9 Resistance grant ---

    @Test
    @DisplayName("6.9 Resistance grant: feat grants fire resistance")
    void resistanceGrant() throws Exception {
        PlayerCharacter c = baseCharacter();

        Feat feat = buildFeat("Dragon Hide", null, "{\"resistances\": [\"fire\"]}", null);

        FeatEffectResolver.AppliedEffects effects = resolver.applyFeat(c, feat, null);

        assertTrue(effects.resistancesAdded().contains("fire"));
        assertTrue(c.getDamageResistances().contains("fire"));
    }

    // --- 6.10 Resource pool: Lucky ---

    @Test
    @DisplayName("6.10 Resource pool: Lucky creates 3 luck points")
    void resourcePoolLucky() throws Exception {
        PlayerCharacter c = baseCharacter();

        Feat feat = buildFeat("Lucky", null,
                "{\"resource\": {\"name\": \"Luck Points\", \"maxUses\": 3, \"resetOn\": \"longRest\"}}",
                null);

        FeatEffectResolver.AppliedEffects effects = resolver.applyFeat(c, feat, null);

        assertNotNull(effects.resource());
        assertEquals("Luck Points", effects.resource().name());
        assertEquals(3, effects.resource().maxUses());
        assertEquals(3, effects.resource().currentUses());
        assertEquals("longRest", effects.resource().resetOn());
        assertNotNull(c.getFeatResources());
        assertTrue(c.getFeatResources().stream().anyMatch(r -> "Luck Points".equals(r.name())));
    }

    // --- 6.11 Spell grant: Magic Initiate ---

    @Test
    @DisplayName("6.11 Spell grant: Magic Initiate adds chosen spells")
    void spellGrantMagicInitiate() throws Exception {
        PlayerCharacter c = baseCharacter();

        UUID cantripId1 = UUID.randomUUID();
        UUID cantripId2 = UUID.randomUUID();
        UUID spellId = UUID.randomUUID();

        Spell cantrip1 = Spell.builder().id(cantripId1).name("Fire Bolt").level(0).build();
        Spell cantrip2 = Spell.builder().id(cantripId2).name("Mage Hand").level(0).build();
        Spell spell1 = Spell.builder().id(spellId).name("Shield").level(1).build();

        when(spellRepository.findById(cantripId1)).thenReturn(Optional.of(cantrip1));
        when(spellRepository.findById(cantripId2)).thenReturn(Optional.of(cantrip2));
        when(spellRepository.findById(spellId)).thenReturn(Optional.of(spell1));

        Feat feat = buildFeat("Magic Initiate", null, null, null);

        ApplyChoicesRequest.AsiChoice choices = new ApplyChoicesRequest.AsiChoice();
        choices.setSpellIds(List.of(cantripId1, cantripId2, spellId));

        FeatEffectResolver.AppliedEffects effects = resolver.applyFeat(c, feat, choices);

        assertEquals(3, effects.spellsAdded().size());
        assertNotNull(c.getSpellsKnown());
        assertTrue(spellNames(c).contains("Fire Bolt"));
        assertTrue(spellNames(c).contains("Mage Hand"));
        assertTrue(spellNames(c).contains("Shield"));
    }

    // --- 6.12 Optional feature: Eldritch Adept ---

    @Test
    @DisplayName("6.12 Optional feature: Eldritch Adept records chosen invocation")
    void optionalFeatureEldritchAdept() throws Exception {
        PlayerCharacter c = baseCharacter();

        UUID featureId = UUID.randomUUID();

        Feat feat = buildFeat("Eldritch Adept", null,
                "{\"optionalFeatureProgression\": true}",
                null);

        ApplyChoicesRequest.AsiChoice choices = new ApplyChoicesRequest.AsiChoice();
        choices.setOptionalFeatureIds(List.of(featureId));

        FeatEffectResolver.AppliedEffects effects = resolver.applyFeat(c, feat, choices);

        assertEquals(1, effects.optionalFeaturesAdded().size());
        assertEquals(featureId.toString(), effects.optionalFeaturesAdded().get(0).get("id"));
    }

    // --- 6.13 Reversal: ability score ---

    @Test
    @DisplayName("6.13 Reversal: ability score increase is reversed")
    void reversalAbilityScore() throws Exception {
        PlayerCharacter c = baseCharacter();
        assertEquals(15, c.getStrength());

        Feat feat = buildFeat("Heavily Armored",
                "[{\"str\": 1}]",
                "{\"armorProficiencies\": [{\"heavy\": true}]}",
                null);

        FeatEffectResolver.AppliedEffects effects = resolver.applyFeat(c, feat, null);
        assertEquals(16, c.getStrength());

        AppliedFeatEffects recorded = CharacterUpdates.recordedEffects(effects, "Heavily Armored");
        resolver.reverseFeatEffects(c, recorded);

        assertEquals(15, c.getStrength());
    }

    // --- 6.14 Reversal: proficiency ---

    @Test
    @DisplayName("6.14 Reversal: proficiency is removed")
    void reversalProficiency() throws Exception {
        PlayerCharacter c = baseCharacter();

        Feat feat = buildFeat("Moderately Armored",
                null,
                "{\"armorProficiencies\": [{\"medium\": true, \"shield\": true}]}",
                null);

        FeatEffectResolver.AppliedEffects effects = resolver.applyFeat(c, feat, null);
        assertTrue(c.getArmorProficiencies().contains("Medium"));

        AppliedFeatEffects recorded = CharacterUpdates.recordedEffects(effects, "Moderately Armored");
        resolver.reverseFeatEffects(c, recorded);

        assertFalse(c.getArmorProficiencies().contains("Medium"));
        assertFalse(c.getArmorProficiencies().contains("Shield"));
    }

    // --- 6.15 Reversal: HP per level: Tough ---

    @Test
    @DisplayName("6.15 Reversal: Tough HP per level is removed")
    void reversalHpPerLevel() throws Exception {
        PlayerCharacter c = baseCharacter();
        assertEquals(38, c.getHpMax());

        Feat feat = buildFeat("Tough", null, "{\"hpPerLevel\": 2}", null);

        resolver.applyFeat(c, feat, null);
        assertEquals(48, c.getHpMax());
        assertEquals(48, c.getHpCurrent());

        AppliedFeatEffects recorded = bonuses("Tough", 0, 0, 2);
        resolver.reverseFeatEffects(c, recorded);

        assertEquals(38, c.getHpMax());
        assertEquals(38, c.getHpCurrent());
    }

    // --- 6.16 Reversal: speed/initiative/passive ---

    @Test
    @DisplayName("6.16 Reversal: speed, initiative, passive stats are reversed")
    void reversalSpeedInitiativePassive() throws Exception {
        PlayerCharacter c = baseCharacter();
        int origSpeed = c.getSpeed();
        int origInit = c.getInitiativeBonus();

        // Apply Mobile
        Feat mobile = buildFeat("Mobile", null, "{\"speedBonus\": 10}", null);
        resolver.applyFeat(c, mobile, null);
        assertEquals(origSpeed + 10, c.getSpeed());

        // Apply Alert
        Feat alert = buildFeat("Alert", null, "{\"initiativeBonus\": 5}", null);
        resolver.applyFeat(c, alert, null);
        assertEquals(origInit + 5, c.getInitiativeBonus());

        // Reverse Mobile
        AppliedFeatEffects mobileRecord = bonuses("Mobile", 10, 0, 0);
        resolver.reverseFeatEffects(c, mobileRecord);
        assertEquals(origSpeed, c.getSpeed());
        // Alert still active
        assertEquals(origInit + 5, c.getInitiativeBonus());

        // Reverse Alert
        AppliedFeatEffects alertRecord = bonuses("Alert", 0, 5, 0);
        resolver.reverseFeatEffects(c, alertRecord);
        assertEquals(origInit, c.getInitiativeBonus());
    }

    // --- 6.17 Reversal: resistance ---

    @Test
    @DisplayName("6.17 Reversal: resistance is removed")
    void reversalResistance() throws Exception {
        PlayerCharacter c = baseCharacter();

        Feat feat = buildFeat("Dragon Hide", null, "{\"resistances\": [\"fire\"]}", null);

        FeatEffectResolver.AppliedEffects effects = resolver.applyFeat(c, feat, null);
        assertTrue(c.getDamageResistances().contains("fire"));

        AppliedFeatEffects recorded = CharacterUpdates.recordedEffects(effects, "Dragon Hide");
        resolver.reverseFeatEffects(c, recorded);

        assertFalse(c.getDamageResistances().contains("fire"));
    }

    // --- 6.18 Reversal: spell grant ---

    @Test
    @DisplayName("6.18 Reversal: spell grant is removed")
    void reversalSpellGrant() throws Exception {
        PlayerCharacter c = baseCharacter();

        UUID cantripId = UUID.randomUUID();
        UUID spellId = UUID.randomUUID();

        Spell cantrip = Spell.builder().id(cantripId).name("Fire Bolt").level(0).build();
        Spell spell = Spell.builder().id(spellId).name("Shield").level(1).build();

        when(spellRepository.findById(cantripId)).thenReturn(Optional.of(cantrip));
        when(spellRepository.findById(spellId)).thenReturn(Optional.of(spell));

        Feat feat = buildFeat("Magic Initiate", null, null, null);

        ApplyChoicesRequest.AsiChoice choices = new ApplyChoicesRequest.AsiChoice();
        choices.setSpellIds(List.of(cantripId, spellId));

        FeatEffectResolver.AppliedEffects effects = resolver.applyFeat(c, feat, choices);
        assertTrue(spellNames(c).contains("Fire Bolt"));
        assertTrue(spellNames(c).contains("Shield"));

        AppliedFeatEffects recorded = CharacterUpdates.recordedEffects(effects, "Magic Initiate");
        resolver.reverseFeatEffects(c, recorded);

        assertFalse(spellNames(c).contains("Fire Bolt"));
        assertFalse(spellNames(c).contains("Shield"));
    }

    // --- 6.19 Reversal symmetry: apply then reverse restores identical state ---

    @Test
    @DisplayName("6.19 Reversal symmetry: apply then reverse restores identical state (Alert)")
    void reversalSymmetryAlert() throws Exception {
        PlayerCharacter c = baseCharacter();
        int origInit = c.getInitiativeBonus();
        int origSpeed = c.getSpeed();
        int origStr = c.getStrength();
        int origHpMax = c.getHpMax();
        int origHpCurrent = c.getHpCurrent();
        List<Feature> origFeatures = c.getFeatures();
        List<String> origDmgRes = c.getDamageResistances();
        List<String> origArmorProfs = c.getArmorProficiencies();

        Feat alert = buildFeat("Alert", null, "{\"initiativeBonus\": 5}", null);
        FeatEffectResolver.AppliedEffects effects = resolver.applyFeat(c, alert, null);

        // Verify something changed
        assertNotEquals(origInit, c.getInitiativeBonus());

        AppliedFeatEffects recorded = CharacterUpdates.recordedEffects(effects, "Alert");
        resolver.reverseFeatEffects(c, recorded);

        assertEquals(origInit, c.getInitiativeBonus());
        assertEquals(origSpeed, c.getSpeed());
        assertEquals(origStr, c.getStrength());
        assertEquals(origHpMax, c.getHpMax());
        assertEquals(origHpCurrent, c.getHpCurrent());
        assertEquals(origDmgRes, c.getDamageResistances());
        assertEquals(origArmorProfs, c.getArmorProficiencies());
    }

    // --- 6.20 Constitution increase: modifiers round down ---

    @Test
    @DisplayName("6.20 Constitution increase: CON 8 to 9 leaves HP alone, and 9 to 10 adds 1 a level, as modifiers round down")
    void constitutionIncreaseRoundsModifiersDown() throws Exception {
        Feat durable = buildFeat("Durable", "[{\"con\": 1}]", null, null);

        PlayerCharacter fromEight = baseCharacter();
        fromEight.setConstitution(8);
        resolver.applyFeat(fromEight, durable, null);
        assertEquals(9, fromEight.getConstitution());
        assertEquals(38, fromEight.getHpMax());

        PlayerCharacter fromNine = baseCharacter();
        fromNine.setConstitution(9);
        FeatEffectResolver.AppliedEffects effects = resolver.applyFeat(fromNine, durable, null);
        assertEquals(10, fromNine.getConstitution());
        assertEquals(43, fromNine.getHpMax());
        assertEquals(43, fromNine.getHpCurrent());

        resolver.reverseFeatEffects(fromNine, CharacterUpdates.recordedEffects(effects, "Durable"));
        assertEquals(9, fromNine.getConstitution());
        assertEquals(38, fromNine.getHpMax());
    }

    // --- Helpers ---

    // The history's record of a feat that changed only speed, initiative or HP per level
    private static AppliedFeatEffects bonuses(String featName, int speedBonus, int initiativeBonus, int hpPerLevel) {
        return new AppliedFeatEffects(null, null, null, null, null, null, null, null, null,
                speedBonus != 0 ? speedBonus : null, initiativeBonus != 0 ? initiativeBonus : null,
                hpPerLevel != 0 ? hpPerLevel : null, null, null, null, null, featName);
    }

    private static List<String> spellNames(PlayerCharacter c) {
        return c.getSpellsKnown().stream().map(KnownSpell::name).toList();
    }
}
