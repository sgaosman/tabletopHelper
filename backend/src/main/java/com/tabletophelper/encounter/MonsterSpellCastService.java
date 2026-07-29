package com.tabletophelper.encounter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.character.dto.ResourcePoolEntry;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import com.tabletophelper.resourcepool.ResourcePoolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages monster spellcasting in an encounter context: populating spellcasting
 * metadata on participants, handling slot and innate resource tracking, and
 * delegating spell resolution to {@link SpellResolverEngine}.
 *
 * <p>Monster spellcasting comes in two flavours:</p>
 * <ul>
 *   <li><b>Slot-based</b> — the monster has spell slots (like a player caster).
 *       Slots are tracked via {@link EncounterParticipant#spellSlotsCurrent}.</li>
 *   <li><b>Innate</b> — the monster has per-day or at-will spells.
 *       Daily-limited spells are tracked via {@link ResourcePoolEntry resource pools};
 *       at-will spells have no resource cost.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MonsterSpellCastService {

    private static final Pattern DAILY_SPELL_KEY_PATTERN = Pattern.compile("^(\\d+).*");

    private final SpellResolverEngine spellResolverEngine;
    private final ResourcePoolService resourcePoolService;
    private final ObjectMapper objectMapper;
    private final SpellRepository spellRepository;

    // ── Public API ─────────────────────────────────────────────────

    /**
     * Reads the monster's actionTemplates JSON and populates the participant's
     * spellcasting fields ({@code spellSlotsCurrent}, {@code spellAttackBonus},
     * {@code spellSaveDc}, {@code spellcastingAbility}, {@code spellsKnown}).
     *
     * <p>If the monster has no {@code spellcasting} block, this is a no-op.</p>
     *
     * @param participant       the encounter participant to populate
     * @param actionTemplatesJson the monster's {@code action_templates} JSON
     */
    public void populateMonsterSpellcasting(EncounterParticipant participant, String actionTemplatesJson) {
        JsonNode spellcasting = extractSpellcastingNode(actionTemplatesJson);
        if (spellcasting == null) {
            return;
        }

        // Determine caster type
        boolean isInnate = spellcasting.path("innateSpells").asBoolean(false);

        // -- Spell slots --
        if (!isInnate) {
            // Slot-based caster: convert slots object to encounter slot format
            JsonNode slots = spellcasting.get("slots");
            if (slots != null && !slots.isNull()) {
                try {
                    participant.setSpellSlotsCurrent(buildEncounterSlotJson(slots));
                } catch (Exception e) {
                    log.warn("Failed to serialize spell slots for participant {}", participant.getId(), e);
                }
            }
        } else {
            // Innate caster: set spellSlotsCurrent for daily usage tracking
            JsonNode dailySpells = spellcasting.get("dailySpells");
            if (dailySpells != null && !dailySpells.isNull()) {
                try {
                    participant.setSpellSlotsCurrent(buildInnateSlotJson(dailySpells));
                } catch (Exception e) {
                    log.warn("Failed to build innate slot tracking for participant {}", participant.getId(), e);
                }
            }
        }

        // -- Spell attack bonus --
        participant.setSpellAttackBonus(getDefaultAttackBonus(spellcasting));

        // -- Spell save DC --
        participant.setSpellSaveDc(getDefaultSaveDC(spellcasting));

        // -- Spellcasting ability --
        JsonNode ability = spellcasting.get("ability");
        if (ability != null && !ability.isNull()) {
            participant.setSpellcastingAbility(ability.asText());
        }

        // -- Spells known --
        try {
            participant.setSpellsKnown(buildSpellsKnownJson(spellcasting));
        } catch (Exception e) {
            log.warn("Failed to build spellsKnown for participant {}", participant.getId(), e);
        }

        log.debug("Populated monster spellcasting for participant {} (innate={})",
                participant.getDisplayName(), isInnate);
    }

    /**
     * Resolves a monster casting a spell during an encounter.
     *
     * <p>Validates that the monster knows the spell, deducts the appropriate
     * resource (slot for slot-based casters, pool usage for innate casters),
     * then delegates resolution to {@link SpellResolverEngine}.</p>
     *
     * @param encounter           the active encounter
     * @param monster             the casting participant
     * @param spellName           the name of the spell being cast
     * @param slotLevel           the level at which the spell is cast (0 for innate/at-will)
     * @param targetIds           target participant UUIDs
     * @param overrideAttackBonus optional override for spell attack bonus (null = use monster default)
     * @param overrideSaveDC      optional override for spell save DC (null = use monster default)
     * @param advantage           true=advantage, false=disadvantage, null=normal
     * @return the resolved spell cast result
     */
    public SpellResolverEngine.SpellCastResult castMonsterSpell(
            Encounter encounter,
            EncounterParticipant monster,
            String spellName,
            int slotLevel,
            List<UUID> targetIds,
            Integer overrideAttackBonus,
            Integer overrideSaveDC,
            Boolean advantage
    ) {
        // Look up the spell
        Spell spell = spellRepository.findByNameIgnoreCase(spellName)
                .orElseThrow(() -> new IllegalArgumentException("Spell not found: " + spellName));

        // Verify the monster knows this spell
        if (!monsterKnowsSpell(monster, spell)) {
            throw new IllegalArgumentException(
                    "Monster " + monster.getDisplayName() + " does not know spell: " + spellName);
        }

        // Determine if this is innate spellcasting based on the monster's data
        String actionTemplatesJson = monster.getMonster() != null
                ? monster.getMonster().getActionTemplates() : null;
        boolean isInnate = false;
        if (actionTemplatesJson != null) {
            JsonNode spellcasting = extractSpellcastingNode(actionTemplatesJson);
            if (spellcasting != null) {
                isInnate = spellcasting.path("innateSpells").asBoolean(false);
            }
        }

        // Deduct resource (slot or pool)
        if (isInnate) {
            // Check if at-will — at-will spells have no pool and no slot cost
            boolean isAtWill = isAtWillSpell(actionTemplatesJson, spellName);
            if (!isAtWill) {
                String poolId = "innate:" + toKebabCase(spellName);
                boolean spent = resourcePoolService.spendPool(monster, poolId, 1);
                if (!spent) {
                    throw new IllegalStateException(
                            "No remaining uses of innate spell: " + spellName);
                }
            }
        } else {
            // Slot-based caster: deduct a slot at the given level
            if (slotLevel > 0) {
                deductSpellSlot(monster, slotLevel);
            }
        }

        // Determine attack bonus and save DC (with overrides)
        int attackBonus = overrideAttackBonus != null
                ? overrideAttackBonus
                : (monster.getSpellAttackBonus() != null ? monster.getSpellAttackBonus() : 0);
        int saveDC = overrideSaveDC != null
                ? overrideSaveDC
                : (monster.getSpellSaveDc() != null ? monster.getSpellSaveDc() : 10);

        // Delegate to the spell resolver engine
        return spellResolverEngine.resolveSpell(
                encounter, monster, spellName, slotLevel, targetIds,
                attackBonus, saveDC, advantage);
    }

    /**
     * Creates resource pool entries for innate spell daily limits.
     *
     * <p>For each spell in the monster's {@code dailySpells}, a
     * {@link ResourcePoolEntry} is created with pool ID
     * {@code "innate:[spell-name-kebab-case]"}, matching the daily limit as
     * {@code maxUses}, resetting on {@code "longRest"}.</p>
     *
     * <p>At-will spells ({@code atWillSpells}) are skipped — they require no
     * resource tracking.</p>
     *
     * <p>The pools are serialized back to
     * {@link EncounterParticipant#setResourcePoolsCurrent(String)}.</p>
     *
     * @param participant       the encounter participant
     * @param actionTemplatesJson the monster's {@code action_templates} JSON
     */
    public void populateInnateSpellPools(EncounterParticipant participant, String actionTemplatesJson) {
        JsonNode spellcasting = extractSpellcastingNode(actionTemplatesJson);
        if (spellcasting == null) {
            return;
        }

        boolean isInnate = spellcasting.path("innateSpells").asBoolean(false);
        if (!isInnate) {
            return; // only relevant for innate casters
        }

        JsonNode dailySpells = spellcasting.get("dailySpells");
        if (dailySpells == null || dailySpells.isNull()) {
            return;
        }

        List<ResourcePoolEntry> pools = new ArrayList<>();
        List<ResourcePoolEntry> existing = resourcePoolService.parsePools(participant.getResourcePoolsCurrent());

        // Collect existing pools keyed by poolId to preserve currentUses if already present
        Map<String, ResourcePoolEntry> existingByPoolId = new LinkedHashMap<>();
        for (ResourcePoolEntry e : existing) {
            existingByPoolId.put(e.poolId(), e);
        }

        dailySpells.fieldNames().forEachRemaining(key -> {
            int dailyLimit = parseDailySpellLimit(key);
            if (dailyLimit <= 0) {
                return;
            }

            JsonNode spells = dailySpells.get(key);
            if (spells == null || !spells.isArray()) {
                return;
            }

            for (JsonNode spellNameNode : spells) {
                String spellName = spellNameNode.asText("");
                if (spellName.isBlank()) {
                    continue;
                }

                String poolId = "innate:" + toKebabCase(spellName);

                // Preserve existing currentUses if pool already exists
                ResourcePoolEntry existingEntry = existingByPoolId.get(poolId);
                int currentUses = existingEntry != null ? existingEntry.currentUses() : dailyLimit;

                ResourcePoolEntry entry = new ResourcePoolEntry(
                        poolId,
                        spellName,
                        "MONSTER",
                        null,
                        dailyLimit,
                        String.valueOf(dailyLimit),
                        currentUses,
                        "longRest",
                        null,
                        null,
                        null,
                        null,
                        Map.of()
                );
                pools.add(entry);
            }
        });

        // Also add any existing pools that aren't from dailySpells (e.g. pools from createForMonster)
        for (ResourcePoolEntry existingEntry : existing) {
            if (!pools.stream().anyMatch(p -> p.poolId().equals(existingEntry.poolId()))) {
                pools.add(existingEntry);
            }
        }

        try {
            participant.setResourcePoolsCurrent(objectMapper.writeValueAsString(pools));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize innate spell pools for participant {}", participant.getId(), e);
        }
    }

    // ── Helper methods ─────────────────────────────────────────────

    /**
     * Returns the spell attack bonus from the spellcasting block, or 0 if null.
     */
    public int getDefaultAttackBonus(JsonNode spellcasting) {
        if (spellcasting == null) return 0;
        JsonNode ab = spellcasting.get("attackBonus");
        return (ab != null && !ab.isNull()) ? ab.asInt() : 0;
    }

    /**
     * Returns the spell save DC from the spellcasting block, or 10 if null.
     */
    public int getDefaultSaveDC(JsonNode spellcasting) {
        if (spellcasting == null) return 10;
        JsonNode dc = spellcasting.get("saveDC");
        return (dc != null && !dc.isNull()) ? dc.asInt() : 10;
    }

    /**
     * Parses the {@code slots} object from the spellcasting block into the
     * encounter participant format: {@code {"1":{"remaining":N,"max":N},...}}.
     *
     * @param spellcasting the spellcasting JSON node
     * @return a map of level to slot info, or empty map if no slots present
     */
    public Map<String, Map<String, Integer>> parseSpellSlots(JsonNode spellcasting) {
        Map<String, Map<String, Integer>> slotsMap = new LinkedHashMap<>();
        if (spellcasting == null) return slotsMap;

        JsonNode slots = spellcasting.get("slots");
        if (slots == null || slots.isNull()) return slotsMap;

        slots.fieldNames().forEachRemaining(level -> {
            int max = slots.get(level).asInt(0);
            if (max > 0) {
                Map<String, Integer> slotInfo = new LinkedHashMap<>();
                slotInfo.put("remaining", max);
                slotInfo.put("max", max);
                slotsMap.put(level, slotInfo);
            }
        });

        return slotsMap;
    }

    /**
     * Looks up each spell name in the spellcasting block and builds the
     * spellsKnown array in player-compatible format:
     * {@code [{"id":"uuid","name":"Fireball","level":3,"source":"monster"},...]}.
     *
     * <p>Reads from {@code spellsByLevel} for slot-based casters, and from
     * both {@code atWillSpells} and {@code dailySpells} for innate casters.</p>
     *
     * @param spellcasting the spellcasting JSON node
     * @return the spells known JSON string
     */
    public String parseSpellsKnown(JsonNode spellcasting, SpellRepository repository) {
        List<Map<String, Object>> spellsList = new ArrayList<>();

        if (spellcasting == null) return "[]";

        JsonNode spellsByLevel = spellcasting.get("spellsByLevel");
        if (spellsByLevel != null && !spellsByLevel.isNull()) {
            spellsByLevel.fieldNames().forEachRemaining(levelKey -> {
                JsonNode spells = spellsByLevel.get(levelKey);
                if (spells == null || !spells.isArray()) return;

                // Determine the spell level from the key
                int level = parseSpellLevelKey(levelKey);

                for (JsonNode spellNameNode : spells) {
                    String spellName = spellNameNode.asText("");
                    if (spellName.isBlank()) continue;
                    addSpellToMap(spellsList, spellName, level, repository);
                }
            });
        }

        // For innate casters, also check atWillSpells and dailySpells
        JsonNode atWillSpells = spellcasting.get("atWillSpells");
        if (atWillSpells != null && !atWillSpells.isNull()) {
            for (JsonNode spellNameNode : atWillSpells) {
                String spellName = spellNameNode.asText("");
                if (spellName.isBlank()) continue;
                addSpellToMap(spellsList, spellName, 0, repository);
            }
        }

        JsonNode dailySpells = spellcasting.get("dailySpells");
        if (dailySpells != null && !dailySpells.isNull()) {
            dailySpells.fieldNames().forEachRemaining(key -> {
                JsonNode spells = dailySpells.get(key);
                if (spells == null || !spells.isArray()) return;
                int level = parseSpellLevelKey(key);
                for (JsonNode spellNameNode : spells) {
                    String spellName = spellNameNode.asText("");
                    if (spellName.isBlank()) continue;
                    addSpellToMap(spellsList, spellName, level, repository);
                }
            });
        }

        try {
            return objectMapper.writeValueAsString(spellsList);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize spells known list", e);
            return "[]";
        }
    }

    // ── Internal helpers ───────────────────────────────────────────

    /**
     * Extracts the {@code spellcasting} node from the monster's action templates JSON.
     * The action templates may be either a JSON object (single monster) or a JSON array
     * (array of monster entries — uses the first entry's spellcasting).
     */
    private JsonNode extractSpellcastingNode(String actionTemplatesJson) {
        if (actionTemplatesJson == null || actionTemplatesJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(actionTemplatesJson);
            if (root.isArray() && root.size() > 0) {
                root = root.get(0);
            }
            JsonNode spellcasting = root.get("spellcasting");
            if (spellcasting == null || spellcasting.isNull()) {
                return null;
            }
            return spellcasting;
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse action templates JSON for spellcasting", e);
            return null;
        }
    }

    /**
     * Converts a simple slots object {@code {"1":4,"2":3}} to encounter format
     * {@code {"1":{"remaining":4,"max":4},"2":{"remaining":3,"max":3}}}.
     */
    private String buildEncounterSlotJson(JsonNode slots) throws JsonProcessingException {
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        slots.fieldNames().forEachRemaining(level -> {
            int max = slots.get(level).asInt(0);
            Map<String, Integer> slotInfo = new LinkedHashMap<>();
            slotInfo.put("remaining", max);
            slotInfo.put("max", max);
            result.put(level, slotInfo);
        });
        return objectMapper.writeValueAsString(result);
    }

    /**
     * Builds an innate spell slot-tracking JSON. For innate casters, we set a
     * simple tracking object so the system knows this is an innate caster with
     * its daily limits.
     */
    private String buildInnateSlotJson(JsonNode dailySpells) throws JsonProcessingException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("innate", true);
        result.put("source", "innate");

        // Track total daily spells as metadata
        int totalDailySpells = 0;
        for (var it = dailySpells.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            JsonNode spells = dailySpells.get(key);
            if (spells != null && spells.isArray()) {
                totalDailySpells += spells.size();
            }
        }
        result.put("dailySpellCount", totalDailySpells);
        return objectMapper.writeValueAsString(result);
    }

    /**
     * Builds the spellsKnown JSON string by looking up each spell name from
     * the spellcasting block via {@link #parseSpellsKnown}.
     */
    private String buildSpellsKnownJson(JsonNode spellcasting) {
        return parseSpellsKnown(spellcasting, spellRepository);
    }

    /**
     * Adds a spell lookup entry to the list. If the spell is found in the
     * repository, its database id, name, level, and source="monster" are
     * recorded. If not found, a best-effort entry is added with null id.
     */
    private void addSpellToMap(List<Map<String, Object>> spellsList, String spellName,
                                int level, SpellRepository repository) {
        // Avoid duplicates by name
        boolean alreadyPresent = spellsList.stream()
                .anyMatch(s -> spellName.equalsIgnoreCase((String) s.get("name")));
        if (alreadyPresent) {
            return;
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", spellName);
        entry.put("level", level);
        entry.put("source", "monster");

        // Try to look up the spell for its ID
        repository.findByNameIgnoreCase(spellName).ifPresentOrElse(
                spell -> entry.put("id", spell.getId().toString()),
                () -> entry.put("id", null)
        );

        spellsList.add(entry);
    }

    /**
     * Checks whether the monster participant knows the given spell by
     * parsing its spellsKnown JSON.
     */
    private boolean monsterKnowsSpell(EncounterParticipant monster, Spell spell) {
        String spellsKnown = monster.getSpellsKnown();
        if (spellsKnown == null || spellsKnown.isBlank()) {
            return false;
        }
        try {
            JsonNode spellsArray = objectMapper.readTree(spellsKnown);
            if (spellsArray != null && spellsArray.isArray()) {
                for (JsonNode spellEntry : spellsArray) {
                    JsonNode nameNode = spellEntry.get("name");
                    if (nameNode != null && spell.getName().equalsIgnoreCase(nameNode.asText())) {
                        return true;
                    }
                }
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse spellsKnown for monster {}", monster.getId(), e);
        }
        return false;
    }

    /**
     * Checks whether a spell is in the monster's at-will list (innate only).
     */
    private boolean isAtWillSpell(String actionTemplatesJson, String spellName) {
        if (actionTemplatesJson == null) return false;
        try {
            JsonNode spellcasting = extractSpellcastingNode(actionTemplatesJson);
            if (spellcasting == null) return false;

            JsonNode atWillSpells = spellcasting.get("atWillSpells");
            if (atWillSpells == null || !atWillSpells.isArray()) return false;

            for (JsonNode node : atWillSpells) {
                if (spellName.equalsIgnoreCase(node.asText())) {
                    return true;
                }
            }
        } catch (Exception e) {
            // fall through
        }
        return false;
    }

    /**
     * Deducts one spell slot at the given level from the participant's
     * spellSlotsCurrent JSON.
     */
    private void deductSpellSlot(EncounterParticipant participant, int slotLevel) {
        String slotsJson = participant.getSpellSlotsCurrent();
        if (slotsJson == null || slotsJson.isBlank()) {
            throw new IllegalStateException("No spell slots available for " + participant.getDisplayName());
        }
        try {
            JsonNode slots = objectMapper.readTree(slotsJson);
            String levelKey = String.valueOf(slotLevel);
            JsonNode slotEntry = slots.get(levelKey);
            if (slotEntry == null) {
                throw new IllegalStateException(
                        "No spell slots at level " + slotLevel + " for " + participant.getDisplayName());
            }

            int remaining = slotEntry.get("remaining").asInt(0);
            if (remaining <= 0) {
                throw new IllegalStateException(
                        "No remaining spell slots at level " + slotLevel + " for " + participant.getDisplayName());
            }

            // Build updated JSON with decremented remaining count
            Map<String, Map<String, Integer>> updatedSlots = new LinkedHashMap<>();
            slots.fieldNames().forEachRemaining(level -> {
                JsonNode entry = slots.get(level);
                Map<String, Integer> slotInfo = new LinkedHashMap<>();
                int max = entry.get("max").asInt(0);
                int rem = entry.get("remaining").asInt(0);
                if (level.equals(levelKey)) {
                    rem--;
                }
                slotInfo.put("remaining", Math.max(0, rem));
                slotInfo.put("max", max);
                updatedSlots.put(level, slotInfo);
            });

            participant.setSpellSlotsCurrent(objectMapper.writeValueAsString(updatedSlots));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse spell slots for deduction", e);
        }
    }

    /**
     * Converts the simple monster slot format {@code {"1": 4}} to the encounter
     * participant format used on the participant during slot deduction.
     */
    @SuppressWarnings("unused")
    private Map<String, Map<String, Integer>> parseSlotsMap(JsonNode slots) {
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        if (slots == null || slots.isNull()) return result;
        slots.fieldNames().forEachRemaining(level -> {
            int max = slots.get(level).asInt(0);
            Map<String, Integer> slotInfo = new LinkedHashMap<>();
            slotInfo.put("remaining", max);
            slotInfo.put("max", max);
            result.put(level, slotInfo);
        });
        return result;
    }

    /**
     * Parses the daily spell limit from a key like {@code "1e"}, {@code "1e\day"},
     * {@code "3e\day"}, returning the leading integer. Returns 1 if no number
     * can be parsed (sensible default for unadorned keys).
     */
    private int parseDailySpellLimit(String key) {
        if (key == null || key.isBlank()) return 1;
        Matcher matcher = DAILY_SPELL_KEY_PATTERN.matcher(key.trim());
        if (matcher.matches()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                return 1;
            }
        }
        return 1;
    }

    /**
     * Parses a spells-by-level key to determine the spell level. Handles:
     * <ul>
     *   <li>Numeric keys like {@code "0"}, {@code "1"}, {@code "2"} → parsed level</li>
     *   <li>{@code "at_will"} → 0</li>
     *   <li>Daily spell keys like {@code "1e"}, {@code "1e\day"} → parsed level from leading number</li>
     *   <li>Any other non-numeric → 0</li>
     * </ul>
     */
    private int parseSpellLevelKey(String key) {
        if (key == null || key.isBlank()) return 0;
        String trimmed = key.trim();
        if ("at_will".equals(trimmed)) return 0;
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            // Try to extract a leading number (e.g., "1e" → 1)
            Matcher matcher = DAILY_SPELL_KEY_PATTERN.matcher(trimmed);
            if (matcher.matches()) {
                try {
                    return Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException ex) {
                    return 0;
                }
            }
            return 0;
        }
    }

    /**
     * Converts a spell name to kebab-case for use as a pool ID suffix.
     * Example: {@code "Burning Hands"} → {@code "burning-hands"}.
     */
    private String toKebabCase(String input) {
        if (input == null || input.isBlank()) return "unknown";
        return input.trim()
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }
}
