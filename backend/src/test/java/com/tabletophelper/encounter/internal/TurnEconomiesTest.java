package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.state.SummonLink;
import com.tabletophelper.rules.combat.SummonRules;
import com.tabletophelper.encounter.ParticipantType;
import com.tabletophelper.monster.Monster;
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
                SummonRules.InitiativeMode.FOLLOWS_CASTER, null, "Land", null, true, false, false, null));

        assertEquals(Map.of("walk", 30, "climb", 30), TurnEconomies.speed(spirit, objectMapper));

        spirit.setSummon(null);
        assertEquals(Map.of("walk", 30, "climb", 30, "fly", 60, "swim", 30), TurnEconomies.speed(spirit, objectMapper));
    }

    @Test
    @DisplayName("A monster without a speed, or with one that can't be read, has none")
    void noSpeed() {
        assertEquals(Map.of(), TurnEconomies.speed(monster(null), objectMapper));
        assertEquals(Map.of(), TurnEconomies.speed(monster("not json"), objectMapper));
        assertEquals(Map.of(), TurnEconomies.speed(monster("[30]"), objectMapper));
    }
}
