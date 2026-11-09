package com.tabletophelper.encounter.internal;

import com.tabletophelper.monster.MonsterTemplates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** The ways the monster data writes an action's limited uses (MM p.11), read into a count and what brings it back. */
class LimitedUsageTest {

    private static MonsterTemplates.Action action(String json) {
        return MonsterTemplates.parse("{\"actions\": [" + json + "]}").actions().getFirst();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
            Force Wave (1/Day)                                          | 1 | longRest
            Change Shape (5/Day)                                        | 5 | longRest
            Hellish Rebuke (1st-Level Spell; 2/Day)                     | 2 | longRest
            Orb of Dragon's Breath (3/Day each)                         | 3 | longRest
            Channel Divinity: Turn Undead (1/Short Rest)                | 1 | shortRest
            Breath Weapon (Recharges after a Short or Long Rest)        | 1 | shortRest
            Illusory Self (Recharges on a Short or Long Rest)           | 1 | shortRest
            Heal Self (Recharges after a Long Rest)                     | 1 | longRest
            Fey Presence (Recharges after a Short Rest)                 | 1 | shortRest
            Mass Telekinesis (Gem Awakening Only; Recharges after a Short or Long Rest) | 1 | shortRest""")
    @DisplayName("A limit in the action's name, as the stat block writes it")
    void fromTheName(String name, int uses, String resetOn) {
        assertEquals(Optional.of(new LimitedUsage(uses, resetOn)),
                LimitedUsage.of(action("{\"name\": \"" + name.replace("\"", "\\\"") + "\"}")));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            {"name": "Enslave", "usesPerDay": 3}                                  | 3 | longRest
            {"name": "Darkness Aura", "recharge": "1/DAY"}                        | 1 | longRest
            {"name": "Pacifying Spores", "recharge": "3/DAY"}                     | 3 | longRest
            {"name": "Wail", "uses": 1, "recharge": "LONG_REST"}                  | 1 | longRest
            {"name": "Healing Touch", "uses": 3, "recharge": "LONG_REST"}         | 3 | longRest
            {"name": "Mind Bolt", "uses": 3, "usesRecharge": "LONG_REST"}         | 3 | longRest
            {"name": "Unsettling Visage", "uses": 1, "rechargeType": "SHORT_REST"} | 1 | shortRest
            {"name": "Leadership", "recharge": "SHORT_OR_LONG_REST"}              | 1 | shortRest
            {"name": "Leadership", "recharge": "SHORT_REST"}                      | 1 | shortRest
            {"name": "Healing Touch", "recharge": "short or long rest"}           | 1 | shortRest
            {"name": "Revivify", "recharge": "DAWN"}                              | 1 | dawn
            {"name": "Summon Devil", "recharge": "9_DAYS"}                        | 1 | never""")
    @DisplayName("A limit in the action's fields, when its name gives none")
    void fromTheFields(String json, int uses, String resetOn) {
        assertEquals(Optional.of(new LimitedUsage(uses, resetOn)), LimitedUsage.of(action(json)));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "{\"name\": \"Bite\"}",
            "{\"name\": \"Fire Breath\", \"recharge\": \"5-6\"}",
            "{\"name\": \"Grim Harvest (1/Turn)\"}",
            "{\"name\": \"Instinctive Charm (Recharges after the Enchanter Casts an Enchantment Spell of 1st level or Higher)\"}",
            "{\"name\": \"Sneak Attack\", \"recharge\": \"PER_TURN\"}",
            "{\"name\": \"Fling Limb\", \"uses\": 3}"})
    @DisplayName("No limit for an action used at will, on a d6 recharge, once a turn or recharging on an event")
    void noLimit(String json) {
        assertEquals(Optional.empty(), LimitedUsage.of(action(json)));
    }

    @Test
    @DisplayName("The name's limit wins over the fields', and the pool is named after the action")
    void nameFirstAndPool() {
        MonsterTemplates.Action spores = action("{\"name\": \"Spores (2/Day)\", \"usesPerDay\": 1}");

        assertEquals(Optional.of(new LimitedUsage(2, "longRest")), LimitedUsage.of(spores));
        var pool = LimitedUsage.pool(spores, "Myconid", "ACTION").orElseThrow();
        assertEquals("monster:limited:spores-2-day", pool.poolId());
        assertEquals("Spores (2/Day)", pool.displayName());
        assertEquals(2, pool.maxUses());
        assertEquals(2, pool.currentUses());
        assertEquals("ACTION", pool.spendActionType());
        assertNull(pool.resetCheck());
    }
}
