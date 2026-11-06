package com.tabletophelper.monster;

import tools.jackson.databind.JsonNode;
import com.tabletophelper.IntegrationTest;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import com.tabletophelper.reference.SpellTemplate;
import com.tabletophelper.shared.ConflictException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The typed template columns (D168): {@code monsters.action_templates} as MonsterTemplates and
 * {@code spells.effect_template} as SpellTemplate. Each keeps its JSON whole, keys the records don't name and explicit
 * nulls included, so a row reads back exactly as it was written, loading it writes nothing, and the API returns the
 * same JSON it did when the columns were text.
 */
class TemplateJsonMappingIT extends IntegrationTest {

    private static final String TEMPLATES = """
            {"source": "MM", "monsterName": "Kraken", "challengeRating": "23", "legendaryActionCount": 3,
             "notes": ["a key the records don't name"],
             "actions": [{"name": "Bite", "reach": 5, "attackBonus": 17, "targetCount": 1,
                          "deliveryMethod": "ATTACK_ROLL", "attackType": "MELEE_WEAPON",
                          "effects": [{"effectType": "DAMAGE", "damageDice": "3d8+10", "damageType": "piercing",
                                       "durationText": null}],
                          "dmNote": "Swallowing is applied by hand."},
                         {"name": "Tentacle", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 17,
                          "effects": [{"effectType": "CONDITION", "condition": "grappled", "escapeDC": 18}]}],
             "spellcasting": {"ability": "INT", "innateSpells": true, "atWillSpells": []}}""";

    private static final String FIREBALL = """
            {"spellName": "Fireball", "spellLevel": 3, "deliveryMethod": "SAVING_THROW", "saveAbility": "DEX",
             "halfOnSave": true, "concentration": false, "aoeShape": "SPHERE", "aoeSize": 20, "notes": null,
             "components": {"verbal": true, "somatic": true, "material": "a tiny ball of bat guano and sulfur"},
             "effects": [{"effectType": "DAMAGE", "damageDice": "8d6", "damageType": "fire", "cantripScaling": null,
                          "upcastScaling": {"additionalDicePerLevel": "1d6", "additionalFlatPerLevel": 0}}],
             "conditionsInflicted": null, "repeatEffect": true}""";

    @Autowired private MonsterRepository monsterRepository;
    @Autowired private SpellRepository spellRepository;

    private Monster saveKraken(String templates) {
        return monsterRepository.save(Monster.builder().name("Kraken").source("MM").challengeRating("23")
                .hitPoints(472).armourClass(18).strength(30).dexterity(11).constitution(25).intelligence(22)
                .wisdom(18).charisma(20).experiencePoints(50000).actionTemplates(MonsterTemplates.parse(templates))
                .build());
    }

    private JsonNode stored(String sql, UUID id) {
        try {
            return objectMapper.readTree(jdbcTemplate.queryForObject(sql, String.class, id));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("A monster's templates round-trip with every key they had, and read into the records")
    void monsterTemplatesRoundTrip() throws Exception {
        UUID id = saveKraken(TEMPLATES).getId();

        assertThat(stored("SELECT action_templates::text FROM monsters WHERE id = ?", id))
                .isEqualTo(objectMapper.readTree(TEMPLATES));
        MonsterTemplates reloaded = monsterRepository.findById(id).orElseThrow().getActionTemplates();
        assertThat(reloaded).isEqualTo(MonsterTemplates.parse(TEMPLATES));
        assertThat(reloaded.json()).isEqualTo(objectMapper.readTree(TEMPLATES));
        assertThat(reloaded.legendaryActionCount()).isEqualTo(3);
        assertThat(reloaded.actions()).extracting(MonsterTemplates.Action::name).containsExactly("Bite", "Tentacle");
        assertThat(reloaded.actions().get(0).dmNote()).isEqualTo("Swallowing is applied by hand.");
        assertThat(reloaded.actions().get(1).effects().get(0).escapeDc()).isEqualTo(18);
        assertThat(reloaded.spellcasting().innateSpells().asBoolean()).isTrue();
    }

    @Test
    @DisplayName("A spell's effect template round-trips with every key it had, and reads into the records")
    void spellTemplateRoundTrips() throws Exception {
        UUID id = spellRepository.save(Spell.builder().name("Fireball").level(3).source("PHB")
                .effectTemplate(SpellTemplate.parse(FIREBALL)).build()).getId();

        assertThat(stored("SELECT effect_template::text FROM spells WHERE id = ?", id))
                .isEqualTo(objectMapper.readTree(FIREBALL));
        SpellTemplate reloaded = spellRepository.findById(id).orElseThrow().getEffectTemplate();
        assertThat(reloaded).isEqualTo(SpellTemplate.parse(FIREBALL));
        assertThat(reloaded.level()).isEqualTo(3);
        assertThat(reloaded.verbal()).isTrue();
        assertThat(reloaded.firstDamageEffect().orElseThrow().upcastScaling().additionalDicePerLevel()).isEqualTo("1d6");
        assertThat(reloaded.repeatEffect().replaces()).isFalse();
        assertThat(reloaded.conditionsInflicted()).isEmpty();
    }

    @Test
    @DisplayName("Loading and saving a monster or a spell unchanged writes nothing")
    void loadingWritesNothing() {
        UUID monsterId = saveKraken(TEMPLATES).getId();
        UUID spellId = spellRepository.save(Spell.builder().name("Fireball").level(3).source("PHB")
                .effectTemplate(SpellTemplate.parse(FIREBALL)).build()).getId();
        Timestamp monsterUpdated = jdbcTemplate.queryForObject("SELECT updated_at FROM monsters WHERE id = ?",
                Timestamp.class, monsterId);
        Timestamp spellUpdated = jdbcTemplate.queryForObject("SELECT updated_at FROM spells WHERE id = ?",
                Timestamp.class, spellId);

        monsterRepository.saveAndFlush(monsterRepository.findById(monsterId).orElseThrow());
        spellRepository.saveAndFlush(spellRepository.findById(spellId).orElseThrow());

        assertThat(jdbcTemplate.queryForObject("SELECT updated_at FROM monsters WHERE id = ?", Timestamp.class,
                monsterId)).isEqualTo(monsterUpdated);
        assertThat(jdbcTemplate.queryForObject("SELECT updated_at FROM spells WHERE id = ?", Timestamp.class,
                spellId)).isEqualTo(spellUpdated);
    }

    @Test
    @DisplayName("The API returns each template as the JSON stored, as it did when the columns were text")
    void apiReturnsTheStoredJson() throws Exception {
        String token = register("reader").accessToken();
        UUID monsterId = saveKraken(TEMPLATES).getId();
        UUID spellId = spellRepository.save(Spell.builder().name("Fireball").level(3).source("PHB")
                .effectTemplate(SpellTemplate.parse(FIREBALL)).build()).getId();

        JsonNode monster = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/monsters/" + monsterId, null, token));
        assertThat(monster.get("actionTemplates")).isEqualTo(objectMapper.readTree(TEMPLATES));
        JsonNode spell = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/reference/spells/" + spellId, null,
                token));
        assertThat(spell.get("effectTemplate")).isEqualTo(objectMapper.readTree(FIREBALL));
    }

    @Test
    @DisplayName("Templates that don't fit the records still load and are returned, and only reading them is a 409")
    void unreadableTemplatesStillLoad() throws Exception {
        String broken = "{\"actions\": [{\"name\": \"Bite\", \"attackBonus\": {\"melee\": 4}}]}";
        UUID id = saveKraken(broken).getId();
        String token = register("reader").accessToken();

        Monster reloaded = monsterRepository.findById(id).orElseThrow();
        assertThat(reloaded.getActionTemplates().unreadable()).isNotNull();
        assertThatThrownBy(() -> MonsterTemplates.of(reloaded)).isInstanceOf(ConflictException.class)
                .hasMessage("Kraken's action templates can't be read");
        JsonNode monster = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/monsters/" + id, null, token));
        assertThat(monster.get("actionTemplates")).isEqualTo(objectMapper.readTree(broken));
    }
}
