package com.tabletophelper.encounter;

import com.tabletophelper.IntegrationTest;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.monster.MonsterRepository;
import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.CharacterClassRepository;
import com.tabletophelper.reference.Item;
import com.tabletophelper.reference.ItemRepository;
import com.tabletophelper.reference.Race;
import com.tabletophelper.reference.RaceRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Item use in combat end to end (D210): a player's fighter, Kriv, drinks potions of healing as actions, undo gives one
 * back and Write-back takes the rest off the sheet; a Wand of Fireballs he is attuned to brings its charges into the
 * encounter and casts Fireball at 4th level for 2 charges at its own DC 15; and a wizard, Ilse, reads a scroll of
 * Fireball, a level above what she can cast, after a DC 13 Intelligence check. A mage counters her scroll's Fireball,
 * and her scroll of Counterspell, read with a failed check, counters nothing. The inventory waits while the encounter is
 * under way, and the wand's spent charges stay spent through unattuning it.
 */
class ItemUseIT extends IntegrationTest {

    private static final String WAND_POOL = "item:wand-of-fireballs";
    private static final String FIREBALL = """
            {"spellName": "Fireball", "spellLevel": 3, "deliveryMethod": "SAVING_THROW", "saveAbility": "DEX",
             "halfOnSave": true, "concentration": false, "components": {"verbal": true},
             "effects": [{"effectType": "DAMAGE", "damageDice": "8d6", "damageType": "fire",
               "upcastScaling": {"additionalDicePerLevel": "1d6"}}], "conditionsInflicted": null}""";

    @Autowired private CharacterClassRepository characterClassRepository;
    @Autowired private RaceRepository raceRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private SpellRepository spellRepository;
    @Autowired private MonsterRepository monsterRepository;

    private RegisteredUser dm;
    private RegisteredUser player;
    private UUID campaignId;
    private String kriv;
    private String ilse;
    private Item wand;
    private Item scroll;
    private UUID encounterId;
    private String krivId;
    private String ilseId;
    private String ogreId;
    private String mageId;

    @BeforeEach
    void setUpTable() {
        dm = register("dungeonmaster");
        player = register("kriv");
        JsonNode campaign = createCampaign(dm.accessToken());
        campaignId = UUID.fromString(campaign.get("id").asString());
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/campaigns/join",
                Map.of("inviteCode", campaign.get("inviteCode").asString()), player.accessToken()));

        Race human = raceRepository.save(Race.builder().name("Human").source("PHB").speed("{\"walk\":30}").build());
        CharacterClass fighter = characterClassRepository.save(CharacterClass.builder().name("Fighter").source("PHB")
                .hitDice(10).subclassLevel(3).isSpellcaster(false).isPactMagic(false).build());
        CharacterClass wizard = characterClassRepository.save(CharacterClass.builder().name("Wizard").source("PHB")
                .hitDice(6).subclassLevel(2).isSpellcaster(true).isPactMagic(false).spellcastingAbility("INT").build());
        spellRepository.save(Spell.builder().name("Fireball").level(3).source("PHB").castingTime("1 action")
                .classes("[\"Sorcerer\", \"Wizard\"]").effectTemplate(SpellTemplate.parse(FIREBALL)).build());
        // As the item loader writes them (V38)
        wand = itemRepository.save(Item.builder().name("Wand of Fireballs").source("DMG").type("Wand").rarity("rare")
                .requiresAttunement(true).charges(7).recharge("dawn").rechargeAmount("1d6 + 1").spellSaveDc(15)
                .attachedSpells("{\"charges\": {\"1\": [\"fireball\"]}}")
                .description("While holding it, you can use an action to expend 1 or more of its charges to cast the "
                        + "fireball spell (save DC 15) from it. For 1 charge, you cast the 3rd-level version of the "
                        + "spell. You can increase the spell slot level by one for each additional charge you expend.")
                .build());
        scroll = itemRepository.save(Item.builder().name("Spell Scroll (3rd Level)").source("DMG").type("Scroll")
                .spellScrollLevel(3).build());
        spellRepository.save(Spell.builder().name("Counterspell").level(3).source("PHB").castingTime("1 reaction")
                .classes("[\"Sorcerer\", \"Warlock\", \"Wizard\"]").effectTemplate(shipped("Counterspell")).build());

        kriv = create("Kriv", human.getId(), fighter.getId(), 5, 16, 8);
        ilse = create("Ilse", human.getId(), wizard.getId(), 3, 8, 16);
        inventory(kriv, List.of(entry("Potion of Healing", 2, null, null, null),
                entry("Wand of Fireballs", 1, wand.getId(), true, null)));
        inventory(ilse, List.of(entry("Spell Scroll (3rd Level)", 1, scroll.getId(), null, "fireball"),
                entry("Spell Scroll (3rd Level)", 1, scroll.getId(), null, "counterspell")));
        Monster ogre = monsterRepository.save(Monster.builder().name("Ogre").type("Giant").challengeRating("2")
                .source("MM").hitPoints(59).armourClass(11).dexterity(8).build());

        encounterId = createEncounter(dm.accessToken(), campaignId);
        krivId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", kriv, "displayName", "Kriv")), "Kriv");
        ilseId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "PLAYER", "characterId", ilse, "displayName", "Ilse")), "Ilse");
        ogreId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", ogre.getId(), "displayName", "Ogre")), "Ogre");
        Monster mage = monsterRepository.save(Monster.builder().name("Mage").type("Humanoid").challengeRating("6")
                .source("MM").hitPoints(40).armourClass(12).dexterity(14).intelligence(17)
                .actionTemplates(MonsterTemplates.parse("""
                        {"spellcasting": {"innateSpells": true, "ability": "INT", "saveDC": 14,
                         "atWillSpells": ["fireball", "counterspell"]}}""")).build());
        mageId = idOf(addParticipant(dm.accessToken(), encounterId,
                Map.of("participantType", "MONSTER", "monsterId", mage.getId(), "displayName", "Mage")), "Mage");
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/initiatives",
                Map.of("initiatives", List.of(
                        Map.of("participantId", krivId, "initiative", 20),
                        Map.of("participantId", ilseId, "initiative", 15),
                        Map.of("participantId", mageId, "initiative", 10),
                        Map.of("participantId", ogreId, "initiative", 5))),
                dm.accessToken()));
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/start", null,
                dm.accessToken()));
    }

    @Test
    @DisplayName("A potion of healing heals 2d4 + 2 as an action; undo gives it back; nobody drinks more than they "
            + "carry; and Write-back takes both drunk off the sheet")
    void potions() {
        int hpMax = participant(dmView(), krivId).get("hpMax").asInt();
        damage(krivId, 20);

        dice.script(3, 4);
        JsonNode drunk = useItem("Potion of Healing", null, player);
        assertThat(participant(drunk, krivId).get("hpCurrent").asInt()).isEqualTo(hpMax - 20 + 9);
        assertThat(participant(drunk, krivId).get("turnEconomy").get("actionsUsed").asInt()).isEqualTo(1);
        assertThat(usable(krivId).get("potions").get(0).get("left").asInt()).isEqualTo(1);

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/undo",
                Map.of("expectedCommandId", dmView().get("undo").get("commandId").asString()), dm.accessToken()));
        assertThat(participant(dmView(), krivId).get("hpCurrent").asInt()).isEqualTo(hpMax - 20);
        assertThat(usable(krivId).get("potions").get(0).get("left").asInt()).isEqualTo(2);

        dice.script(1, 1);
        useItem("Potion of Healing", null, player);
        // The action is spent, so the player's second is refused; the DM isn't (D174)
        assertThat(useItemResponse("Potion of Healing", player).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        dice.script(1, 1);
        useItem("Potion of Healing", null, dm);
        ResponseEntity<String> none = useItemResponse("Potion of Healing", dm);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(none.getBody()).contains("Kriv has no Potion of Healing left");

        JsonNode preview = expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId + "/results",
                null, dm.accessToken()));
        assertThat(preview.get(0).get("items").get(0).get("quantityAfter").asInt()).isZero();
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/end",
                Map.of("applyResults", true), dm.accessToken()));
        assertThat(names(sheet(kriv).get("equipment"))).containsExactly("Wand of Fireballs");
    }

    @Test
    @DisplayName("An attuned Wand of Fireballs brings 7 charges; Fireball at 4th level costs 2 at the wand's DC 15, and "
            + "unattuning it after the fight drops the pool")
    void wandOfFireballs() {
        assertThat(pool(sheet(kriv), WAND_POOL).get("sourceType").asString()).isEqualTo("MAGIC_ITEM");
        JsonNode listed = usable(krivId).get("chargedItems").get(0);
        assertThat(listed.get("saveDc").asInt()).isEqualTo(15);
        assertThat(listed.get("spells").get(0).get("sources").get(0).get("chargeCosts").get("4").asInt()).isEqualTo(2);

        // 9d6 fire, then the ogre's DEX save: 14 - 1 = 13 against the wand's 15
        dice.script(1, 1, 1, 1, 1, 1, 1, 1, 1, 14);
        JsonNode cast = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/cast-spell?actorId=" + krivId, Map.of("spellName", "Fireball", "spellSource", "ITEM",
                "itemName", "Wand of Fireballs", "slotLevel", 4, "targetIds", List.of(ogreId)), player.accessToken()));

        assertThat(cast.get("slotLevelUsed").asInt()).isEqualTo(4);
        assertThat(expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId + "/combat/log",
                null, dm.accessToken())).toString()).contains("DC 15").contains("expends 2 charges of Wand of Fireballs");
        assertThat(participant(dmView(), ogreId).get("hpCurrent").asInt()).isEqualTo(59 - 9);
        assertThat(pool(participant(dmView(), krivId), WAND_POOL).get("currentUses").asInt()).isEqualTo(5);

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/end",
                Map.of("applyResults", true), dm.accessToken()));
        assertThat(pool(sheet(kriv), WAND_POOL).get("currentUses").asInt()).isEqualTo(5);

        inventory(kriv, List.of(entry("Wand of Fireballs", 1, wand.getId(), false, null)));
        JsonNode unattuned = sheet(kriv);
        assertThat(unattuned.get("resourcePools").toString()).doesNotContain(WAND_POOL);
        assertThat(unattuned.get("equipment").get(0).get("chargesSpent").asInt()).isEqualTo(2);

        // Attuned again, as the sheet sends its entries back, the wand has the charges it had, not 7
        inventory(kriv, List.of(with(unattuned.get("equipment").get(0), "attuned", true)));
        assertThat(pool(sheet(kriv), WAND_POOL).get("currentUses").asInt()).isEqualTo(5);

        // A long rest gives back 1d6 + 1 of the 2 spent, up to 7
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/characters/" + kriv + "/long-rest", null,
                player.accessToken()));
        assertThat(pool(sheet(kriv), WAND_POOL).get("currentUses").asInt()).isEqualTo(7);
    }

    @Test
    @DisplayName("A 3rd-level wizard reads a scroll of Fireball after a DC 13 Intelligence check, at the scroll's DC 15, "
            + "and the scroll is used up")
    void scroll() {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/turn/next", null,
                dm.accessToken()));
        JsonNode listed = usable(ilseId).get("scrolls").get(0);
        assertThat(listed.get("checkDc").asInt()).isEqualTo(13);
        assertThat(listed.get("saveDc").asInt()).isEqualTo(15);

        // The check, d20 12 + INT 3 = 15; then 8d6 and the ogre's save of 10 - 1 = 9 against 15
        dice.script(12, 1, 1, 1, 1, 1, 1, 1, 1, 10);
        JsonNode cast = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/cast-spell?actorId=" + ilseId, Map.of("spellName", "Fireball", "spellSource", "SCROLL",
                "itemName", "Spell Scroll (3rd Level)", "slotLevel", 3, "targetIds", List.of(ogreId)),
                player.accessToken()));

        assertThat(cast.get("spellSource").asString()).isEqualTo("SCROLL");
        assertThat(participant(dmView(), ogreId).get("hpCurrent").asInt()).isEqualTo(59 - 8);
        assertThat(scrollSpells(ilseId)).containsExactly("Counterspell");

        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/end",
                Map.of("applyResults", true), dm.accessToken()));
        assertThat(sheet(ilse).get("equipment")).hasSize(1);
        assertThat(sheet(ilse).get("equipment").get(0).get("spell").asString()).isEqualTo("Counterspell");
    }

    @Test
    @DisplayName("Counterspell read from a scroll with a failed check counters nothing: the mage's Fireball stands and "
            + "the scroll is lost (DMG p.200)")
    void counterspellScrollFailedCheck() {
        nextTurn();
        nextTurn();
        int hp = participant(dmView(), krivId).get("hpCurrent").asInt();
        // 8d6 of 3s = 24 fire, and Kriv's DEX save of 1 + 1 fails against 14
        dice.script(3, 3, 3, 3, 3, 3, 3, 3, 1);
        castBy(mageId, "Fireball", krivId);
        assertThat(participant(dmView(), krivId).get("hpCurrent").asInt()).isEqualTo(hp - 24);

        // The check, d20 2 + INT 3 = 5 against 13, fails
        dice.script(2);
        JsonNode cast = expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/cast-spell?actorId=" + ilseId, Map.of("spellName", "Counterspell", "spellSource", "SCROLL",
                "itemName", "Spell Scroll (3rd Level)", "slotLevel", 3, "targetIds", List.of(mageId)),
                dm.accessToken()));

        assertThat(cast.get("resultSummary").asString()).contains("the spell disappears from the scroll with no effect");
        assertThat(participant(dmView(), krivId).get("hpCurrent").asInt()).isEqualTo(hp - 24);
        assertThat(log()).noneMatch(line -> line.contains("counters"));
        assertThat(scrollSpells(ilseId)).containsExactly("Fireball");
    }

    @Test
    @DisplayName("A mage countering the Fireball Ilse read from a scroll takes the Fireball back but not the scroll, "
            + "which stays used up (PHB p.228, DMG p.200)")
    void counteredScrollStaysUsed() {
        nextTurn();
        dice.script(12, 1, 1, 1, 1, 1, 1, 1, 1, 10);
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId
                + "/combat/cast-spell?actorId=" + ilseId, Map.of("spellName", "Fireball", "spellSource", "SCROLL",
                "itemName", "Spell Scroll (3rd Level)", "slotLevel", 3, "targetIds", List.of(ogreId)),
                player.accessToken()));
        assertThat(participant(dmView(), ogreId).get("hpCurrent").asInt()).isEqualTo(59 - 8);

        castBy(mageId, "Counterspell", ilseId);

        assertThat(participant(dmView(), ogreId).get("hpCurrent").asInt()).isEqualTo(59);
        assertThat(log()).anyMatch(line -> line.startsWith("Mage counters Ilse's Fireball"));
        assertThat(scrollSpells(ilseId)).containsExactly("Counterspell");
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/end",
                Map.of("applyResults", true), dm.accessToken()));
        assertThat(sheet(ilse).get("equipment")).hasSize(1);
        assertThat(sheet(ilse).get("equipment").get(0).get("spell").asString()).isEqualTo("Counterspell");
    }

    @Test
    @DisplayName("The inventory can't change while the encounter is under way, which reads it: a 409 that changes "
            + "nothing (D210)")
    void inventoryWaitsForTheEncounter() {
        ResponseEntity<String> refused = exchange(HttpMethod.PUT, "/api/characters/" + kriv + "/inventory",
                Map.of("equipment", List.of(entry("Potion of Healing", 9, null, null, null)),
                        "currency", Map.of("cp", 0, "sp", 0, "ep", 0, "gp", 0, "pp", 0)), player.accessToken());

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("Kriv is in an encounter that is under way, so can't change their "
                + "inventory until it ends");
        assertThat(names(sheet(kriv).get("equipment"))).containsExactly("Potion of Healing", "Wand of Fireballs");
        assertThat(usable(krivId).get("potions").get(0).get("left").asInt()).isEqualTo(2);
    }

    // ── Helpers ──────────────────────────────────────────────────

    // The entry as the sheet gave it, with one field changed, as the inventory tab sends it back
    @SuppressWarnings("unchecked")
    private Map<String, Object> with(JsonNode entry, String field, Object value) {
        Map<String, Object> changed = new LinkedHashMap<>(objectMapper.convertValue(entry, Map.class));
        changed.put(field, value);
        return changed;
    }

    private void nextTurn() {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/turn/next", null,
                dm.accessToken()));
    }

    // A monster's innate spell, cast by the DM
    private void castBy(String monsterId, String spell, String targetId) {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/cast-spell?actorId="
                + monsterId, Map.of("spellName", spell, "slotLevel", 0, "targetIds", List.of(targetId)),
                dm.accessToken()));
    }

    private List<String> scrollSpells(String participantId) {
        List<String> spells = new ArrayList<>();
        usable(participantId).get("scrolls").forEach(scroll -> spells.add(scroll.get("spell").get("name").asString()));
        return spells;
    }

    private List<String> log() {
        List<String> lines = new ArrayList<>();
        expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId + "/combat/log", null,
                dm.accessToken())).forEach(entry -> lines.add(entry.get("description").asString()));
        return lines;
    }

    // A spell's template as the game data ships it
    private SpellTemplate shipped(String name) {
        try (InputStream in = getClass().getResourceAsStream("/data/spell-effects/spell-effect-definitions.json")) {
            for (JsonNode definition : objectMapper.readTree(in)) {
                if (name.equals(definition.path("spellName").asString(null))) {
                    return SpellTemplate.of(definition);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        throw new AssertionError(name + " has no shipped template");
    }

    private String create(String name, UUID race, UUID classId, int level, int strength, int intelligence) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("name", name, "raceId", race, "classId", classId,
                "level", level, "strength", strength, "dexterity", 12, "constitution", 14,
                "intelligence", intelligence, "wisdom", 10, "charisma", 10));
        body.put("campaignId", campaignId);
        return expect(HttpStatus.CREATED, exchange(HttpMethod.POST, "/api/characters", body, player.accessToken()))
                .get("id").asString();
    }

    private static Map<String, Object> entry(String name, int quantity, UUID itemId, Boolean attuned, String spell) {
        Map<String, Object> entry = new LinkedHashMap<>(Map.of("name", name, "quantity", quantity));
        if (itemId != null) entry.put("itemId", itemId);
        if (attuned != null) entry.put("attuned", attuned);
        if (spell != null) entry.put("spell", spell);
        return entry;
    }

    private void inventory(String character, List<Map<String, Object>> equipment) {
        expect(HttpStatus.OK, exchange(HttpMethod.PUT, "/api/characters/" + character + "/inventory",
                Map.of("equipment", equipment, "currency", Map.of("cp", 0, "sp", 0, "ep", 0, "gp", 0, "pp", 0)),
                player.accessToken()));
    }

    private JsonNode useItem(String itemName, String targetId, RegisteredUser as) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("participantId", krivId, "itemName", itemName));
        if (targetId != null) body.put("targetId", targetId);
        return expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/use-item",
                body, as.accessToken()));
    }

    private ResponseEntity<String> useItemResponse(String itemName, RegisteredUser as) {
        return exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/use-item",
                Map.of("participantId", krivId, "itemName", itemName), as.accessToken());
    }

    private JsonNode usable(String participantId) {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId
                + "/combat/usable-items?participantId=" + participantId, null, player.accessToken()));
    }

    private void damage(String targetId, int amount) {
        expect(HttpStatus.OK, exchange(HttpMethod.POST, "/api/encounters/" + encounterId + "/combat/damage",
                Map.of("targetId", targetId, "amount", amount), dm.accessToken()));
    }

    private JsonNode dmView() {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/encounters/" + encounterId, null,
                dm.accessToken()));
    }

    private JsonNode sheet(String character) {
        return expect(HttpStatus.OK, exchange(HttpMethod.GET, "/api/characters/" + character, null,
                player.accessToken()));
    }

    private static List<String> names(JsonNode equipment) {
        List<String> names = new ArrayList<>();
        equipment.forEach(entry -> names.add(entry.get("name").asString()));
        return names;
    }

    private static JsonNode pool(JsonNode owner, String poolId) {
        JsonNode pools = owner.has("resourcePools") ? owner.get("resourcePools") : owner.get("resourcePoolsCurrent");
        for (JsonNode pool : pools) {
            if (pool.get("poolId").asString().equals(poolId)) {
                return pool;
            }
        }
        throw new AssertionError("No pool " + poolId);
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
        throw new AssertionError(participantId + " is not in the encounter");
    }
}
