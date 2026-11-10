package com.tabletophelper.encounter;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
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
 * A rakshasa's Limited Magic Immunity "unless it wishes to be" affected (MM p.257, D198), end to end: the standing
 * choice of which spells it lets through, set by whoever controls it, and a cast's own choice. A DM and one player,
 * whose cleric Aria (save DC 14, WIS spellcasting) acts first; the spells' templates are the shipped ones.
 */
class SpellConsentIT extends IntegrationTest {

    private static final String LIMITED_MAGIC_IMMUNITY = """
            [{"name": "Limited Magic Immunity", "description": "The rakshasa can't be affected or detected by spells \
            of 6th level or lower unless it wishes to be. It has advantage on saving throws against all other spells \
            and magical effects."}]""";
    private static final List<String> SHIPPED = List.of("Cure Wounds", "Fireball");

    @Autowired private MonsterRepository monsterRepository;
    @Autowired private SpellRepository spellRepository;
    @Autowired private EncounterParticipantRepository participantRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID encounterId;
    private String ariaId;
    private String rakshasaId;
    private String ogreId;
    private String companionId;
    private final Map<String, Integer> initiatives = new LinkedHashMap<>();

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        player = register("aria");
        JsonNode campaign = createCampaign(dm.accessToken());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asString()), player.accessToken()));
        UUID character = createCharacter(player.accessToken(), insertRace("Human"), insertClass("Cleric", 8));
        UUID campaignId = UUID.fromString(campaign.get("id").asString());
        encounterId = createEncounter(dm.accessToken(), campaignId);
        SHIPPED.forEach(this::shipped);
        ariaId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", character, "displayName", "Aria")), "Aria");
        initiatives.clear();
        initiatives.put(ariaId, 20);
        Monster rakshasa = monsterRepository.save(Monster.builder().name("Rakshasa").type("fiend").source("MM")
                .challengeRating("13").hitPoints(110).armourClass(16).dexterity(16).wisdom(16)
                .traits(LIMITED_MAGIC_IMMUNITY).build());
        rakshasaId = add(Map.of("participantType", "MONSTER", "monsterId", rakshasa.getId(),
                "displayName", "Rakshasa"), "Rakshasa");
        companionId = add(Map.of("participantType", "COMPANION", "monsterId", rakshasa.getId(),
                "displayName", "Tame Rakshasa", "controlledByUserId", player.id()), "Tame Rakshasa");
        Monster ogre = monsterRepository.save(Monster.builder().name("Ogre").type("giant").source("MM")
                .challengeRating("2").hitPoints(59).armourClass(11).dexterity(8).build());
        ogreId = add(Map.of("participantType", "MONSTER", "monsterId", ogre.getId(), "displayName", "Ogre"), "Ogre");
    }

    @Test
    @DisplayName("The DM View shows the trait and its choice, helpful only at first; players see it only on the "
            + "creature a player controls")
    void views() {
        JsonNode rakshasa = participant(dmState(), rakshasaId);
        assertThat(rakshasa.get("spellConsentTrait").asString()).isEqualTo("Limited Magic Immunity");
        assertThat(rakshasa.get("spellConsent").asString()).isEqualTo("HELPFUL");
        assertThat(participant(dmState(), ogreId).get("spellConsent").isNull()).isTrue();

        JsonNode playerView = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
                player.accessToken()));
        assertThat(participant(playerView, rakshasaId).get("spellConsent").isNull()).isTrue();
        assertThat(participant(playerView, companionId).get("spellConsent").asString()).isEqualTo("HELPFUL");
    }

    @Test
    @DisplayName("Whoever controls the creature sets its choice: the DM the monster's, the player their companion's; "
            + "anyone else is refused, and a creature without the trait is a 400")
    void settingTheChoice() {
        expect(HttpStatus.OK, setConsent(rakshasaId, "NEVER", dm));
        assertThat(participant(dmState(), rakshasaId).get("spellConsent").asString()).isEqualTo("NEVER");
        expect(HttpStatus.OK, setConsent(companionId, "ALWAYS", player));
        assertThat(participant(dmState(), companionId).get("spellConsent").asString()).isEqualTo("ALWAYS");

        expect(HttpStatus.FORBIDDEN, setConsent(rakshasaId, "ALWAYS", player));
        expect(HttpStatus.BAD_REQUEST, setConsent(ogreId, "ALWAYS", dm));
        assertThat(participant(dmState(), rakshasaId).get("spellConsent").asString()).isEqualTo("NEVER");
    }

    @Test
    @DisplayName("Helpful only: Aria's Cure Wounds heals the rakshasa and her Fireball doesn't touch it; never keeps "
            + "out Cure Wounds too")
    void standingChoice() {
        start();
        hurt(rakshasaId, 50);
        // Cure Wounds' 1d8 of 5 plus WIS 0
        dice.script(5);
        cast("Cure Wounds", 1, List.of(rakshasaId), Map.of());
        assertThat(hp(rakshasaId)).isGreaterThan(50);
        assertThat(log()).anyMatch(line -> line.equals("Rakshasa lets Cure Wounds affect it (Limited Magic "
                + "Immunity)."));

        int before = hp(rakshasaId);
        // The Fireball's 8d6 are rolled with no creature to save (D177)
        dice.script(6, 6, 6, 6, 6, 6, 6, 6);
        cast("Fireball", 3, List.of(rakshasaId), Map.of());
        assertThat(hp(rakshasaId)).isEqualTo(before);

        expect(HttpStatus.OK, setConsent(rakshasaId, "NEVER", dm));
        dice.script(5);
        cast("Cure Wounds", 1, List.of(rakshasaId), Map.of());
        assertThat(hp(rakshasaId)).isEqualTo(before);
    }

    @Test
    @DisplayName("The cast's own choice overrides the standing one, made only by whoever controls the target: the "
            + "player may choose for their companion, not for the DM's rakshasa")
    void castChoice() {
        start();
        // 8d6 of 3s = 24 fire, and the companion's DEX save of 1 fails
        dice.script(3, 3, 3, 3, 3, 3, 3, 3, 1);
        expect(HttpStatus.OK, castRequest("Fireball", 3, List.of(companionId), Map.of(companionId, true), player));
        assertThat(hp(companionId)).isLessThan(110);

        expect(HttpStatus.FORBIDDEN, castRequest("Fireball", 3, List.of(rakshasaId), Map.of(rakshasaId, true),
                player));
        expect(HttpStatus.BAD_REQUEST, castRequest("Fireball", 3, List.of(ogreId), Map.of(ogreId, true), dm));
        expect(HttpStatus.BAD_REQUEST, castRequest("Fireball", 3, List.of(ogreId), Map.of(rakshasaId, true), dm));
        assertThat(hp(rakshasaId)).isEqualTo(110);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private String add(Map<String, Object> request, String name) {
        String id = idOf(addParticipant(dm.accessToken(), encounterId, request), name);
        initiatives.put(id, 19 - initiatives.size());
        return id;
    }

    // Starts the fight, then gives Aria her spells, which the start would otherwise copy from her sheet again (D166)
    private void start() {
        List<Map<String, Object>> order = new ArrayList<>();
        initiatives.forEach((id, value) -> order.add(Map.of("participantId", id, "initiative", value)));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", order), dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
        EncounterParticipant aria = participantRepository.findById(UUID.fromString(ariaId)).orElseThrow();
        aria.setSpellSlotsCurrent(new HashMap<>(Map.of("1", new SpellSlot(3, 0), "3", new SpellSlot(3, 0))));
        aria.setSpellSaveDc(14);
        aria.setSpellAttackBonus(6);
        aria.setSpellcastingAbility("WIS");
        List<KnownSpell> known = new ArrayList<>();
        for (String spell : SHIPPED) {
            int level = spellRepository.findByNameIgnoreCase(spell).orElseThrow().getLevel();
            known.add(new KnownSpell(null, spell, level, "class:Cleric", true, null, null, null, null));
        }
        aria.setSpellsKnown(known);
        participantRepository.save(aria);
    }

    private void hurt(String participantId, int hp) {
        EncounterParticipant creature = participantRepository.findById(UUID.fromString(participantId)).orElseThrow();
        creature.setHpCurrent(hp);
        participantRepository.save(creature);
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

    private ResponseEntity<String> setConsent(String participantId, String consent, RegisteredUser user) {
        return exchange(HttpMethod.PUT, "/api/encounters/" + encounterId + "/combat/spell-consent",
                Map.of("participantId", participantId, "consent", consent), user.accessToken());
    }

    private ResponseEntity<String> castRequest(String spell, int slotLevel, List<String> targetIds,
                                               Map<String, Boolean> consents, RegisteredUser user) {
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/cast-spell?actorId=" + ariaId,
                Map.of("spellName", spell, "slotLevel", slotLevel, "targetIds", targetIds, "spellConsents", consents),
                user.accessToken());
    }

    private void cast(String spell, int slotLevel, List<String> targetIds, Map<String, Boolean> consents) {
        expect(HttpStatus.OK, castRequest(spell, slotLevel, targetIds, consents, dm));
    }

    private int hp(String participantId) {
        return participant(dmState(), participantId).get("hpCurrent").asInt();
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
