package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.FlexibleCasting;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.CastSpellRequest;
import com.tabletophelper.encounter.dto.CastSpellResponse;
import com.tabletophelper.encounter.dto.MetamagicChoice;
import com.tabletophelper.rules.combat.Metamagic;
import com.tabletophelper.rules.combat.Metamagic.Option;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.JacksonDefaults;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What each Metamagic option does to a cast (PHB p.102), with the spells' templates as the game data ships them: the
 * M18 pass's resolution checks. Sorin, a sorcerer with CHA 16 (+3), spell save DC 14 and spell attack +6, knows all
 * eight options and has 20 sorcery points; the monsters' abilities are all 10.
 */
class MetamagicResolutionTest {

    private static final String DEFINITIONS = "/data/spell-effects/spell-effect-definitions.json";
    private static final List<String> SPELLS = List.of("Fireball", "Ice Storm", "Ice Knife", "Chaos Bolt",
            "Magic Missile", "Prismatic Spray", "Inflict Wounds", "Toll the Dead", "Haste", "Cure Wounds",
            "Hold Person", "Fire Bolt", "Dispel Magic", "Booming Blade");

    private CombatFixture f;
    private EncounterParticipant sorin;
    private EncounterParticipant ogre;
    private EncounterParticipant goblin;

    @BeforeEach
    void setUp() throws IOException {
        f = new CombatFixture();
        sorin = f.player("Sorin", 40, 12);
        sorin.getCharacter().setCharisma(16);
        sorin.setSpellAttackBonus(6);
        sorin.setSpellSaveDc(14);
        sorin.setSpellcastingAbility("CHA");
        List<Feature> features = new ArrayList<>();
        for (Option option : Option.values()) {
            features.add(new Feature(Metamagic.FEATURE_PREFIX + option.displayName(), null, "Sorcerer"));
        }
        sorin.getCharacter().setFeatures(features);
        Map<String, SpellSlot> slots = new LinkedHashMap<>();
        for (int level = 1; level <= 7; level++) {
            slots.put(String.valueOf(level), new SpellSlot(3, 0));
        }
        sorin.setSpellSlotsCurrent(slots);
        sorin.setResourcePoolsCurrent(new ArrayList<>(List.of(new ResourcePoolEntry(FlexibleCasting.SORCERY_POINTS,
                "Sorcery Points", "CLASS", null, 20, "sorcererLevel", 20, "longRest", null, null, "FREE", "sparkles",
                null))));
        ogre = f.monster("Ogre", 60, 11);
        goblin = f.monster("Goblin", 60, 11);
        try (InputStream in = getClass().getResourceAsStream(DEFINITIONS)) {
            for (JsonNode definition : JacksonDefaults.mapper().readTree(in)) {
                String name = definition.path("spellName").asString();
                if (SPELLS.contains(name)) {
                    f.spell(name, definition.path("spellLevel").asInt(), definition.toString());
                    f.knows(sorin, name);
                }
            }
        }
    }

    private int points() {
        return FlexibleCasting.sorceryPoints(sorin.getResourcePoolsCurrent()).orElseThrow().currentUses();
    }

    private CastSpellResponse cast(String spell, int slotLevel, List<EncounterParticipant> targets, UUID userId,
                                   List<MetamagicChoice> metamagic, int... rolls) {
        CastSpellRequest request = new CastSpellRequest();
        request.setSpellName(spell);
        request.setSlotLevel(slotLevel);
        request.setTargetIds(targets.stream().map(EncounterParticipant::getId).toList());
        request.setMetamagic(metamagic);
        f.dice.script(rolls);
        return f.spellcasting.castSpell(f.encounterId(), request, sorin.getId(), userId);
    }

    private CastSpellResponse cast(String spell, int slotLevel, List<EncounterParticipant> targets,
                                   List<MetamagicChoice> metamagic, int... rolls) {
        return cast(spell, slotLevel, targets, f.dmUserId, metamagic, rolls);
    }

    private static MetamagicChoice choice(Option option, EncounterParticipant... creatures) {
        return new MetamagicChoice(option, creatures.length == 0 ? null
                : List.of(creatures).stream().map(EncounterParticipant::getId).toList());
    }

    private boolean logged(String text) {
        return f.descriptions().stream().anyMatch(line -> line != null && line.contains(text));
    }

    @Test
    @DisplayName("Careful on an area save: Prismatic Spray's chosen creature succeeds on its save against its ray and takes half")
    void carefulPrismaticSpray() {
        // the goblin's d8 is 1 (red, 10d6 fire), every die a 2: 20, halved to 10, with no save rolled
        cast("Prismatic Spray", 7, List.of(goblin), List.of(choice(Option.CAREFUL, goblin)),
                1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2);

        assertEquals(50, goblin.getHpCurrent());
        assertEquals(0, f.dice.remaining());
        assertEquals(19, points());
    }

    @Test
    @DisplayName("Careful and Heightened reach an attack spell's follow-up save: Ice Knife's burst (D179)")
    void carefulAndHeightenedIceKnife() {
        // 15 hits the ogre for 1d10 6; the burst's 2d6 3 and 3; Careful spares the ogre, the goblin's save 5 fails
        cast("Ice Knife", 1, List.of(ogre, goblin), List.of(choice(Option.CAREFUL, ogre)), 15, 6, 3, 3, 5);
        assertEquals(54, ogre.getHpCurrent());
        assertEquals(54, goblin.getHpCurrent());
        assertEquals(0, f.dice.remaining());

        // Heightened on the goblin: 18 and 2 with disadvantage, the 2 counts; the ogre's 18 succeeds
        CombatFixture.newTurn(sorin);
        cast("Ice Knife", 1, List.of(ogre, goblin), List.of(choice(Option.HEIGHTENED, goblin)), 15, 1, 3, 3, 18, 18,
                2);
        assertEquals(53, ogre.getHpCurrent());
        assertEquals(48, goblin.getHpCurrent());
        assertEquals(16, points());
    }

    @Test
    @DisplayName("Heightened on a multi-target save: only the chosen one of Fireball's creatures rolls with disadvantage")
    void heightenedFireball() {
        // 8d6 all 2s = 16; the ogre saves with 15 for 8; the goblin rolls 18 and 3, the 3 failing for 16
        cast("Fireball", 3, List.of(ogre, goblin), List.of(choice(Option.HEIGHTENED, goblin)),
                2, 2, 2, 2, 2, 2, 2, 2, 15, 18, 3);

        assertEquals(52, ogre.getHpCurrent());
        assertEquals(44, goblin.getHpCurrent());
        assertEquals(17, points());
    }

    @Test
    @DisplayName("Empowered on two damage types: Ice Storm rerolls its three dice furthest below average across both")
    void empoweredIceStorm() {
        // 2d8 1, 8 and 4d6 1, 2, 6, 6; the d8's 1, the d6's 1 and the d6's 2 are rerolled to 8, 6 and 6: 16
        // bludgeoning and 24 cold; the ogre's DEX save 1 fails
        cast("Ice Storm", 4, List.of(ogre), List.of(choice(Option.EMPOWERED)), 1, 8, 1, 2, 6, 6, 8, 6, 6, 1);

        assertEquals(20, ogre.getHpCurrent());
        assertEquals(19, points());
    }

    @Test
    @DisplayName("Empowered on Chaos Bolt rerolls its low dice, and the rerolled d8s give its type and decide the leap")
    void empoweredChaosBolt() {
        // 15 hits; d8s 1 and 1 and d6 1 would be 3 acid and leap; rerolled to 8, 7 and 6: 21 thunder, no leap
        cast("Chaos Bolt", 1, List.of(ogre), List.of(choice(Option.EMPOWERED)), 15, 1, 1, 1, 8, 7, 6);

        assertEquals(39, ogre.getHpCurrent());
        assertTrue(logged("d8s show 8 and 7: thunder damage"));
        assertFalse(logged("leaps"));
    }

    @Test
    @DisplayName("Empowered on Magic Missile shares the CHA modifier's dice among the darts, not the first alone")
    void empoweredMagicMissile() {
        // darts of 1d4 + 1: 1 rerolled to 4, then 4 kept, then 1 rerolled to 3: 5 + 5 + 4
        cast("Magic Missile", 1, List.of(ogre), List.of(choice(Option.EMPOWERED)), 1, 4, 4, 1, 3);

        assertEquals(46, ogre.getHpCurrent());
        assertEquals(0, f.dice.remaining());
    }

    @Test
    @DisplayName("Twinned costs the cast level: Inflict Wounds at 2nd level 2 points, Toll the Dead, a cantrip, 1")
    void twinnedCosts() {
        cast("Inflict Wounds", 2, List.of(ogre, goblin), List.of(choice(Option.TWINNED)),
                15, 1, 1, 1, 1, 15, 1, 1, 1, 1);
        assertEquals(18, points());
        assertEquals(56, ogre.getHpCurrent());
        assertEquals(56, goblin.getHpCurrent());

        CombatFixture.newTurn(sorin);
        cast("Toll the Dead", 0, List.of(ogre, goblin), List.of(choice(Option.TWINNED)), 1, 1, 1, 1, 1, 1, 1, 1);
        assertEquals(17, points());
        assertTrue(ogre.getHpCurrent() < 56 && goblin.getHpCurrent() < 56);
    }

    @Test
    @DisplayName("Twinned Dispel Magic dispels on both its creatures")
    void twinnedDispelMagic() {
        cast("Dispel Magic", 3, List.of(ogre, goblin), List.of(choice(Option.TWINNED)));

        assertEquals(17, points());
        assertTrue(logged("No spell is on Ogre."));
        assertTrue(logged("No spell is on Goblin."));
    }

    @Test
    @DisplayName("Booming Blade, with a range of self (5-foot radius), is refused Twinned and Distant at the cast")
    void bladeNotTwinned() {
        f.spellRepository.findByNameIgnoreCase("Booming Blade").orElseThrow()
                .setRangeDistance("Self (5-foot radius)");

        assertThrows(ValidationException.class, () -> cast("Booming Blade", 0, List.of(ogre, goblin),
                List.of(choice(Option.TWINNED))));
        assertThrows(ValidationException.class, () -> cast("Booming Blade", 0, List.of(ogre),
                List.of(choice(Option.DISTANT))));
        assertEquals(20, points());
    }

    @Test
    @DisplayName("Extended doubles Haste's concentration to 2 minutes")
    void extendedHaste() {
        EncounterParticipant bran = f.player("Bran", 30, 12);
        cast("Haste", 3, List.of(bran), List.of(choice(Option.EXTENDED)));

        assertEquals(20, sorin.getConcentration().durationRounds());
        assertEquals(19, points());
    }

    @Test
    @DisplayName("Distant on a touch spell: Cure Wounds is cast for 1 point and logged for the DM, who applies its 30 feet")
    void distantCureWounds() {
        f.spellRepository.findByNameIgnoreCase("Cure Wounds").orElseThrow().setRangeDistance("Touch");
        EncounterParticipant bran = f.player("Bran", 30, 12);
        bran.setHpCurrent(10);
        String summary = cast("Cure Wounds", 1, List.of(bran), List.of(choice(Option.DISTANT)), 4)
                .getResultSummary();

        assertEquals(19, points());
        // 1d8 4 + the spellcasting modifier, +4 from the +6 spell attack less proficiency
        assertEquals(18, bran.getHpCurrent());
        assertTrue(summary.contains("(Distant Spell, 1 sorcery point)"), summary);
    }

    @Test
    @DisplayName("Quickened after a levelled spell with the action is refused, even for a cantrip (PHB p.202)")
    void quickenedAfterLevelledSpell() {
        UUID player = f.playerControlling(sorin);
        cast("Hold Person", 2, List.of(ogre), player, List.of(), 20);

        ConflictException refused = assertThrows(ConflictException.class, () -> cast("Fire Bolt", 0,
                List.of(goblin), player, List.of(choice(Option.QUICKENED)), 15, 5));
        assertTrue(refused.getMessage().contains("bonus action"), refused.getMessage());
        assertEquals(20, points());
    }
}
