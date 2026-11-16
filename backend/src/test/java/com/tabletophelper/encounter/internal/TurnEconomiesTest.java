package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.state.SummonLink;
import com.tabletophelper.rules.combat.SummonRules;
import com.tabletophelper.encounter.ParticipantType;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.reference.Race;
import com.tabletophelper.shared.JacksonDefaults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TurnEconomiesTest {

    private final ObjectMapper objectMapper = JacksonDefaults.mapper();

    private static EncounterParticipant pc(PlayerCharacter character) {
        return EncounterParticipant.builder().participantType(ParticipantType.PLAYER).displayName(character.getName())
                .character(character).hpMax(10).hpCurrent(10).armourClass(15).build();
    }

    private static EncounterParticipant monster(String speed) {
        Monster monster = Monster.builder().id(UUID.randomUUID()).name("Wyvern").speed(speed).build();
        return EncounterParticipant.builder().participantType(ParticipantType.MONSTER).displayName("Wyvern")
                .monster(monster).hpMax(110).hpCurrent(110).armourClass(13).build();
    }

    private static PlayerCharacter fighter(int level, String... features) {
        List<Feature> sheet = new ArrayList<>();
        for (String name : features) {
            sheet.add(new Feature(name, null, "Fighter"));
        }
        return PlayerCharacter.builder().name("Gimli").speed(25).features(sheet)
                .multiclassEntries(List.of(new ClassEntry(UUID.randomUUID(), "Fighter", null, "Battle Master", level)))
                .build();
    }

    @Test
    @DisplayName("A player character's attacks per action come from its sheet's features and class levels")
    void playerAttacksPerAction() {
        assertEquals(1, TurnEconomies.attacksPerAction(pc(fighter(4, "Second Wind", "Action Surge"))));
        assertEquals(2, TurnEconomies.attacksPerAction(pc(fighter(9, "Extra Attack"))));
        assertEquals(3, TurnEconomies.attacksPerAction(pc(fighter(11, "Extra Attack", "Extra Attack (2)"))));
        assertEquals(1, TurnEconomies.attacksPerAction(pc(PlayerCharacter.builder().name("Blank").build())));
    }

    @Test
    @DisplayName("A monster attacks once per Attack action: Multiattack is an action of its own")
    void monsterAttacksPerAction() {
        assertEquals(1, TurnEconomies.attacksPerAction(monster("{\"walk\": 20, \"fly\": 80}")));
    }

    @Test
    @DisplayName("A player character's speed is its sheet's walking speed")
    void playerSpeed() {
        assertEquals(Map.of("walk", 25), TurnEconomies.speed(pc(fighter(1)), objectMapper));
    }

    @Test
    @DisplayName("A player character keeps its race's other speeds (D230): a number as it is, true as the sheet's walking speed, canHover left out")
    void playerRaceSpeeds() {
        PlayerCharacter aarakocra = fighter(1);
        aarakocra.setSpeed(25);
        aarakocra.setRaceRef(Race.builder().id(UUID.randomUUID()).name("Aarakocra")
                .speed("{\"walk\": 25, \"fly\": 50}").build());
        PlayerCharacter tabaxi = fighter(1);
        // A Monk 2 Tabaxi: Unarmored Movement raised the sheet's walking speed to 40, and its climb follows it
        tabaxi.setSpeed(40);
        tabaxi.setRaceRef(Race.builder().id(UUID.randomUUID()).name("Tabaxi")
                .speed("{\"walk\": 30, \"climb\": true, \"canHover\": true}").build());
        PlayerCharacter triton = fighter(1);
        triton.setSpeed(40);
        triton.setRaceRef(Race.builder().id(UUID.randomUUID()).name("Triton").speed("{\"walk\": 30, \"swim\": 30}")
                .build());

        assertEquals(Map.of("walk", 25, "fly", 50), TurnEconomies.speed(pc(aarakocra), objectMapper));
        assertEquals(Map.of("walk", 40, "climb", 40), TurnEconomies.speed(pc(tabaxi), objectMapper));
        // A numbered swim speed stays the race's: Unarmored Movement raises only the walking speed
        assertEquals(Map.of("walk", 40, "swim", 30), TurnEconomies.speed(pc(triton), objectMapper));
    }

    @Test
    @DisplayName("A monster's speeds come from its stat block, walking first, a conditional speed by its number")
    void monsterSpeed() {
        var speeds = TurnEconomies.speed(monster(
                "{\"fly\": {\"number\": 60, \"condition\": \"(hover)\"}, \"swim\": 30, \"walk\": 20, \"canHover\": true}"),
                objectMapper);

        assertEquals(Map.of("walk", 20, "fly", 60, "swim", 30), speeds);
        assertEquals("walk", speeds.keySet().iterator().next());
    }

    @Test
    @DisplayName("A summoned spirit moves only as its form allows: the Land Bestial Spirit walks and climbs (TCE p.109)")
    void spiritSpeedByForm() {
        EncounterParticipant spirit = monster("{\"walk\": 30, \"climb\": {\"number\": 30, \"condition\": \"(land only)\"}, "
                + "\"fly\": {\"number\": 60, \"condition\": \"(air only)\"}, "
                + "\"swim\": {\"number\": 30, \"condition\": \"(water only)\"}}");
        spirit.setSummon(new SummonLink(UUID.randomUUID(), "Summon Beast", 4, SummonRules.Kind.SPIRIT,
                SummonRules.InitiativeMode.FOLLOWS_CASTER, null, "Land", null, true, false, false, null, null, null, null,
                null));

        assertEquals(Map.of("walk", 30, "climb", 30), TurnEconomies.speed(spirit, objectMapper));

        spirit.setSummon(null);
        assertEquals(Map.of("walk", 30, "climb", 30, "fly", 60, "swim", 30), TurnEconomies.speed(spirit, objectMapper));
    }

    @Test
    @DisplayName("Exhaustion halves every speed from level 2, rounding down, and makes them 0 from level 5 (PHB p.291)")
    void exhaustion() {
        EncounterParticipant gimli = pc(fighter(1));
        gimli.setExhaustionLevel(2);
        assertEquals(Map.of("walk", 12), TurnEconomies.speed(gimli, objectMapper));
        EncounterParticipant wyvern = monster("{\"walk\": 20, \"fly\": 80}");
        wyvern.setExhaustionLevel(1);
        assertEquals(Map.of("walk", 20, "fly", 80), TurnEconomies.speed(wyvern, objectMapper));
        wyvern.setExhaustionLevel(3);
        assertEquals(Map.of("walk", 10, "fly", 40), TurnEconomies.speed(wyvern, objectMapper));
        wyvern.setExhaustionLevel(5);
        assertEquals(Map.of("walk", 0, "fly", 0), TurnEconomies.speed(wyvern, objectMapper));
    }

    @Test
    @DisplayName("A monster without a speed, or with one that can't be read, has none")
    void noSpeed() {
        assertEquals(Map.of(), TurnEconomies.speed(monster(null), objectMapper));
        assertEquals(Map.of(), TurnEconomies.speed(monster("not json"), objectMapper));
        assertEquals(Map.of(), TurnEconomies.speed(monster("[30]"), objectMapper));
    }
}
