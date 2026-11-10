package com.tabletophelper.encounter;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import com.tabletophelper.reference.SpellTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Polymorph family end to end (D192), with the templates as the game data ships them. A DM and one player, whose
 * caster Aria (1st level, spell save DC 14) acts first on 20. The forms are a brown bear (CR 1, 34 HP, AC 11, a Bite), a
 * giant ape (CR 7) and an iron golem (a CR 1 construct here, for Shapechange's refusal); the targets are an ogre (CR 5,
 * 59 HP, AC 11, WIS 7) and, for Mass Polymorph, a kobold (CR 1/8).
 */
class FormSpellsIT extends IntegrationTest {

    private static final List<String> SHIPPED = List.of("Polymorph", "Mass Polymorph", "Shapechange", "True Polymorph",
            "Animal Shapes", "Sacred Flame");
    private static final String LIMITED_MAGIC_IMMUNITY = "[{\"name\": \"Limited Magic Immunity\", \"description\": "
            + "\"The rakshasa can't be affected or detected by spells of 6th level or lower unless it wishes to be. It "
            + "has advantage on saving throws against all other spells and magical effects.\"}]";

    @Autowired private MonsterRepository monsterRepository;
    @Autowired private SpellRepository spellRepository;
    @Autowired private EncounterParticipantRepository participantRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID encounterId;
    private String ariaId;
    private Monster bear;
    private Monster ape;
    private Monster golem;
    private final Map<String, Integer> initiatives = new LinkedHashMap<>();

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        player = register("aria");
        JsonNode campaign = createCampaign(dm.accessToken());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asString()), player.accessToken()));
        UUID character = createCharacter(player.accessToken(), insertRace("Human"), insertClass("Wizard", 6));
        encounterId = createEncounter(dm.accessToken(), UUID.fromString(campaign.get("id").asString()));
        SHIPPED.forEach(this::shipped);
        ariaId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", character, "displayName", "Aria")), "Aria");
        initiatives.clear();
        initiatives.put(ariaId, 20);

        bear = monsterRepository.save(Monster.builder().name("Brown Bear").type("beast").challengeRating("1")
                .source("MM").size("Large").hitPoints(34).armourClass(11).strength(19).dexterity(10)
                .constitution(16).intelligence(2).wisdom(13).charisma(7).speed("{\"walk\": 40, \"climb\": 30}")
                .actionTemplates(MonsterTemplates.parse("""
                        {"actions": [
                           {"name": "Bite", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 6,
                            "attackType": "MELEE_WEAPON",
                            "effects": [{"effectType": "DAMAGE", "damageDice": "1d8 + 4", "damageType": "piercing"}]}
                        ]}"""))
                .build());
        ape = monsterRepository.save(Monster.builder().name("Giant Ape").type("beast").challengeRating("7")
                .source("MM").size("Huge").hitPoints(157).armourClass(12).build());
        golem = monsterRepository.save(Monster.builder().name("Clay Statue").type("construct").challengeRating("1")
                .source("MM").size("Medium").hitPoints(20).armourClass(14).build());
    }

    @Test
    @DisplayName("Polymorph turns an ogre that fails its save into a brown bear: the bear's HP, AC, speed and Bite; "
            + "damage past the bear's 34 carries over when it reverts")
    void polymorphAMonster() {
        String ogre = ogre();
        start();
        // A giant ape is above the ogre's challenge rating 5
        assertThat(castRequest("Polymorph", 4, List.of(ogre), "unwilling", Map.of(ogre, ape.getId().toString()))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // The picker offers the bear and not the ape
        JsonNode options = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/combat/form-options?participantId=" + ariaId + "&spell=Polymorph&targetId=" + ogre, null,
                dm.accessToken()));
        assertThat(options.get("maxChallengeRating").asString()).isEqualTo("5");
        List<String> names = new ArrayList<>();
        options.get("creatures").forEach(creature -> names.add(creature.get("name").asString()));
        assertThat(names).contains("Brown Bear").doesNotContain("Giant Ape", "Clay Statue");

        // The ogre's WIS save of 3 - 2 fails against DC 14
        dice.script(3);
        cast("Polymorph", 4, List.of(ogre), "unwilling", Map.of(ogre, bear.getId().toString()));
        JsonNode state = participant(dmState(), ogre);
        assertThat(state.get("hpCurrent").asInt()).isEqualTo(34);
        assertThat(state.get("hpMax").asInt()).isEqualTo(34);
        assertThat(state.get("armourClass").asInt()).isEqualTo(11);
        assertThat(state.get("formName").asString()).isEqualTo("Brown Bear");
        assertThat(state.get("form").get("spell").asString()).isEqualTo("Polymorph");
        assertThat(state.get("form").get("normalHpCurrent").asInt()).isEqualTo(59);
        assertThat(state.get("speed").get("walk").asInt()).isEqualTo(40);

        // Its actions are the bear's
        JsonNode templates = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/participants/" + ogre + "/actions", null, dm.accessToken()));
        assertThat(templates.get("actions").get(0).get("name").asString()).isEqualTo("Bite");
        nextTurn();
        // Bite: 15 + 6 hits Aria, 1d8 (4) + 4, and her concentration save of 20 holds
        dice.script(15, 4, 20);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/monster-action",
                Map.of("monsterParticipantId", ogre, "actionName", "Bite", "actionSource", "ACTION",
                        "targetParticipantIds", List.of(ariaId)), dm.accessToken()));
        assertThat(log()).anyMatch(line -> line.contains("Bite"));

        // 40 damage: the bear's 34, then 6 carry over to the ogre's 59
        damage(ogre, 40);
        state = participant(dmState(), ogre);
        assertThat(state.get("hpCurrent").asInt()).isEqualTo(53);
        assertThat(state.get("hpMax").asInt()).isEqualTo(59);
        assertThat(state.get("formName").isNull()).isTrue();
        assertThat(condition(ogre, "polymorphed")).isNull();
        assertThat(log()).anyMatch(line -> line.contains("reverts from Brown Bear") && line.contains("6 damage"));
    }

    @Test
    @DisplayName("Polymorph ends with Aria's concentration, giving the ogre back its own HP and AC")
    void endsWithConcentration() {
        String ogre = ogre();
        start();
        dice.script(3);
        cast("Polymorph", 4, List.of(ogre), "unwilling", Map.of(ogre, bear.getId().toString()));
        damage(ogre, 10);
        assertThat(hp(ogre)).isEqualTo(24);

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/concentration",
                Map.of("participantId", ariaId), dm.accessToken()));
        JsonNode state = participant(dmState(), ogre);
        assertThat(state.get("hpCurrent").asInt()).isEqualTo(59);
        assertThat(state.get("armourClass").asInt()).isEqualTo(11);
        assertThat(state.get("form").isNull()).isTrue();
        assertThat(condition(ogre, "polymorphed")).isNull();
    }

    @Test
    @DisplayName("A willing player character skips the save, becomes a bear within its level, and can't cast spells")
    void willingPlayerCharacter() {
        start();
        // Aria is 1st level, so a giant ape is beyond her
        assertThat(castRequest("Polymorph", 4, List.of(ariaId), "willing", Map.of(ariaId, ape.getId().toString()))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // No die is scripted: the willing option rolls no save
        cast("Polymorph", 4, List.of(ariaId), "willing", Map.of(ariaId, bear.getId().toString()));
        JsonNode state = participant(dmState(), ariaId);
        assertThat(state.get("hpCurrent").asInt()).isEqualTo(34);
        assertThat(state.get("formName").asString()).isEqualTo("Brown Bear");
        assertThat(log()).noneMatch(line -> line.contains("WIS save"));

        ResponseEntity<String> again = castRequest("Polymorph", 4, List.of(ariaId), "willing",
                Map.of(ariaId, bear.getId().toString()));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).contains("can't cast spells");
    }

    @Test
    @DisplayName("A Polymorph cast with no form chosen is refused before anything is spent")
    void formRequired() {
        String ogre = ogre();
        start();
        assertThat(castRequest("Polymorph", 4, List.of(ogre), "unwilling", null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(participant(dmState(), ariaId).get("spellSlotsCurrent").get("4").get("used").asInt()).isZero();
    }

    @Test
    @DisplayName("Mass Polymorph limits each creature by its own challenge rating: a CR 1/8 kobold can't become a bear")
    void massPolymorphLimit() {
        String ogre = ogre();
        String kobold = monster("Kobold", "humanoid", "1/8", 5);
        start();
        assertThat(castRequest("Mass Polymorph", 9, List.of(ogre, kobold), "willing",
                Map.of(ogre, bear.getId().toString(), kobold, bear.getId().toString())).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        cast("Mass Polymorph", 9, List.of(ogre), "willing", Map.of(ogre, bear.getId().toString()));
        assertThat(participant(dmState(), ogre).get("formName").asString()).isEqualTo("Brown Bear");
    }

    @Test
    @DisplayName("Undoing Polymorph gives the ogre back its own HP and AC and Aria her slot")
    void undoPolymorph() {
        String ogre = ogre();
        start();
        dice.script(3);
        cast("Polymorph", 4, List.of(ogre), "unwilling", Map.of(ogre, bear.getId().toString()));
        JsonNode after = dmState();
        assertThat(participant(after, ogre).get("hpCurrent").asInt()).isEqualTo(34);

        String commandId = after.get("undo").get("commandId").asString();
        JsonNode restored = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/undo", Map.of("expectedCommandId", commandId), dm.accessToken()));
        JsonNode ogreState = participant(restored, ogre);
        assertThat(ogreState.get("hpCurrent").asInt()).isEqualTo(59);
        assertThat(ogreState.get("hpMax").asInt()).isEqualTo(59);
        assertThat(ogreState.get("form").isNull()).isTrue();
        assertThat(participant(restored, ariaId).get("spellSlotsCurrent").get("4").get("used").asInt()).isZero();
    }

    @Test
    @DisplayName("Shapechange: Aria becomes a bear but not a construct, can still cast, and changes form with an action")
    void shapechange() {
        start();
        assertThat(castRequest("Shapechange", 9, List.of(ariaId), null, Map.of(ariaId, golem.getId().toString()))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        cast("Shapechange", 9, List.of(ariaId), null, Map.of(ariaId, bear.getId().toString()));
        JsonNode state = participant(dmState(), ariaId);
        assertThat(state.get("formName").asString()).isEqualTo("Brown Bear");
        assertThat(state.get("form").get("keepsOwnTraits").asBoolean()).isTrue();
        assertThat(state.get("form").has("wisdom")).isFalse();

        // A new form, a CR 1 creature that isn't a beast, keeps her current HP when it has more
        // 20 damage, and her concentration save of 20 holds
        dice.script(20);
        damage(ariaId, 20);
        Monster hound = monsterRepository.save(Monster.builder().name("Dire Hound").type("monstrosity")
                .challengeRating("1").source("MM").hitPoints(45).armourClass(13).build());
        nextTurn();
        nextTurn();
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/change-form",
                Map.of("participantId", ariaId, "targetId", ariaId, "creatureId", hound.getId()),
                dm.accessToken()));
        state = participant(dmState(), ariaId);
        assertThat(state.get("formName").asString()).isEqualTo("Dire Hound");
        assertThat(state.get("hpCurrent").asInt()).isEqualTo(14);
        assertThat(state.get("armourClass").asInt()).isEqualTo(13);
    }

    @Test
    @DisplayName("Animal Shapes refuses a beast larger than Large and turns a willing creature into a bear")
    void animalShapes() {
        String ogre = ogre();
        start();
        assertThat(castRequest("Animal Shapes", 9, List.of(ogre), null, Map.of(ogre, ape.getId().toString()))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        cast("Animal Shapes", 9, List.of(ogre), null, Map.of(ogre, bear.getId().toString()));
        JsonNode state = participant(dmState(), ogre);
        assertThat(state.get("formName").asString()).isEqualTo("Brown Bear");
        assertThat(condition(ogre, "animal shape")).isNotNull();
    }

    @Test
    @DisplayName("A rakshasa saves against True Polymorph with Limited Magic Immunity's advantage; as a bear it loses "
            + "the trait, so Sacred Flame reaches it and its save has no advantage (D191, D192)")
    void polymorphedRakshasaLosesItsTraits() {
        Monster saved = monsterRepository.save(Monster.builder().name("Rakshasa").type("fiend").challengeRating("13")
                .source("MM").hitPoints(110).armourClass(16).dexterity(16).wisdom(16).charisma(20)
                .traits(LIMITED_MAGIC_IMMUNITY).build());
        String rakshasa = idOf(addParticipant(dm.accessToken(), encounterId, Map.of("participantType", "MONSTER",
                "monsterId", saved.getId(), "displayName", "Rakshasa")), "Rakshasa");
        initiatives.put(rakshasa, 10);
        start();

        // Sacred Flame, a cantrip, is below the immunity's 6th level
        cast("Sacred Flame", 0, List.of(rakshasa), null, null);
        assertThat(hp(rakshasa)).isEqualTo(110);

        nextTurn();
        nextTurn();
        // True Polymorph is 9th level: the WIS save of 2 and 3 (+3), with advantage, fails against DC 14
        dice.script(2, 3);
        cast("True Polymorph", 9, List.of(rakshasa), "unwilling", Map.of(rakshasa, bear.getId().toString()));
        assertThat(log()).anyMatch(line -> line.contains("advantage: Limited Magic Immunity"));
        assertThat(participant(dmState(), rakshasa).get("formName").asString()).isEqualTo("Brown Bear");

        // As a bear: 1d8 (4) radiant, and one DEX save of 5 fails
        nextTurn();
        nextTurn();
        dice.script(4, 5);
        cast("Sacred Flame", 0, List.of(rakshasa), null, null);
        assertThat(hp(rakshasa)).isEqualTo(30);
        assertThat(log().stream().filter(line -> line.contains("Sacred Flame")).toList().getLast())
                .doesNotContain("advantage");
    }

    // ── Helpers ──────────────────────────────────────────────────

    private String ogre() {
        return monster("Ogre", "giant", "5", 59);
    }

    private String monster(String name, String type, String rating, int hp) {
        Monster saved = monsterRepository.save(Monster.builder().name(name).type(type).challengeRating(rating)
                .source("MM").hitPoints(hp).armourClass(11).dexterity(8).constitution(16).wisdom(7).charisma(7)
                .build());
        String id = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", saved.getId(), "displayName", name)), name);
        initiatives.put(id, 19 - initiatives.size());
        return id;
    }

    // Starts the fight, then gives Aria her spells and slots
    private void start() {
        List<Map<String, Object>> order = new ArrayList<>();
        initiatives.forEach((id, value) -> order.add(Map.of("participantId", id, "initiative", value)));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", order), dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
        EncounterParticipant aria = participantRepository.findById(UUID.fromString(ariaId)).orElseThrow();
        aria.setHpMax(30);
        aria.setHpCurrent(30);
        aria.setSpellSlotsCurrent(new HashMap<>(Map.of("4", new SpellSlot(3, 0), "9", new SpellSlot(3, 0))));
        aria.setSpellSaveDc(14);
        aria.setSpellAttackBonus(6);
        aria.setSpellcastingAbility("INT");
        List<KnownSpell> known = new ArrayList<>();
        for (String spell : SHIPPED) {
            int level = spellRepository.findByNameIgnoreCase(spell).orElseThrow().getLevel();
            known.add(new KnownSpell(null, spell, level, "class:Wizard", true, null, null, null, null));
        }
        aria.setSpellsKnown(known);
        participantRepository.save(aria);
    }

    // A spell with its template as the game data ships it
    private void shipped(String name) {
        try (InputStream in = getClass().getResourceAsStream("/data/spell-effects/spell-effect-definitions.json")) {
            for (JsonNode definition : objectMapper.readTree(in)) {
                if (name.equals(definition.path("spellName").asString(null))) {
                    spellRepository.save(Spell.builder().name(name).level(definition.path("spellLevel").asInt())
                            .source("PHB").castingTime("1 action")
                            .concentration(definition.path("concentration").asBoolean(false))
                            .effectTemplate(SpellTemplate.of(definition)).build());
                    return;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        throw new AssertionError(name + " has no shipped template");
    }

    private ResponseEntity<String> castRequest(String spell, int slotLevel, List<String> targetIds, String option,
                                               Map<String, String> forms) {
        Map<String, Object> body = new HashMap<>(Map.of("spellName", spell, "slotLevel", slotLevel,
                "targetIds", targetIds));
        if (option != null) {
            body.put("option", option);
        }
        if (forms != null) {
            body.put("forms", forms);
        }
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/cast-spell?actorId=" + ariaId,
                body, dm.accessToken());
    }

    private void cast(String spell, int slotLevel, List<String> targetIds, String option, Map<String, String> forms) {
        expect(HttpStatus.OK, castRequest(spell, slotLevel, targetIds, option, forms));
    }

    private void damage(String targetId, int amount) {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/damage",
                Map.of("targetId", targetId, "amount", amount), dm.accessToken()));
    }

    private void nextTurn() {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/turn/next", null,
                dm.accessToken()));
    }

    private int hp(String participantId) {
        return participant(dmState(), participantId).get("hpCurrent").asInt();
    }

    // The participant's condition of the name, or null
    private JsonNode condition(String participantId, String name) {
        JsonNode conditions = participant(dmState(), participantId).get("activeConditions");
        if (conditions == null || conditions.isNull()) {
            return null;
        }
        for (JsonNode condition : conditions) {
            if (name.equals(condition.get("name").asString())) {
                return condition;
            }
        }
        return null;
    }

    private List<String> log() {
        JsonNode entries = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/combat/log", null, dm.accessToken()));
        List<String> lines = new ArrayList<>();
        entries.forEach(entry -> lines.add(entry.get("description").asString()));
        return lines;
    }

    private JsonNode dmState() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
                dm.accessToken()));
    }

    private static String idOf(JsonNode encounter, String displayName) {
        for (JsonNode participant : encounter.get("participants")) {
            if (participant.get("displayName").asString().equals(displayName)) {
                return participant.get("id").asString();
            }
        }
        throw new AssertionError(displayName + " is not in the encounter");
    }

    private static JsonNode participant(JsonNode encounter, String participantId) {
        for (JsonNode participant : encounter.get("participants")) {
            if (participant.get("id").asString().equals(participantId)) {
                return participant;
            }
        }
        throw new AssertionError("Participant " + participantId + " is not in the encounter");
    }
}
