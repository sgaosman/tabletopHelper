package com.tabletophelper.encounter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.character.dto.ResourcePoolEntry;
import com.tabletophelper.encounter.internal.SpellCaster;
import com.tabletophelper.encounter.internal.StoredJson;
import com.tabletophelper.encounter.state.KnownSpell;
import com.tabletophelper.encounter.state.SpellSlot;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import com.tabletophelper.resourcepool.ResourcePoolService;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
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
 * casting through the same {@link SpellCaster} path as player characters, so the
 * EffectApplier makes every change (D160). R5 workstream F replaces it with
 * Spell Sources (D165).
 *
 * <p>Monster spellcasting comes in two flavours:</p>
 * <ul>
 *   <li><b>Slot-based</b> — the monster has spell slots (like a player caster).
 *       Slots are tracked via {@link EncounterParticipant#spellSlotsCurrent}.</li>
 *   <li><b>Innate</b> — the monster has per-day or at-will spells and no spell slots.
 *       Daily-limited spells are tracked via {@link ResourcePoolEntry resource pools};
 *       at-will spells have no resource cost (D165).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MonsterSpellCastService {

    // A daily-spell or spell-level key's leading number, as in "1e" or "3e\day"
    private static final Pattern LEADING_NUMBER = Pattern.compile("^(\\d{1,3}).*");

    private final SpellCaster spellCaster;
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
        // An innate caster has none: populateInnateSpellPools tracks its daily uses (D165)
        if (!isInnate) {
            JsonNode slots = spellcasting.get("slots");
            if (slots != null && !slots.isNull()) {
                participant.setSpellSlotsCurrent(buildEncounterSlots(slots));
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
        participant.setSpellsKnown(parseSpellsKnown(spellcasting, spellRepository));

        log.debug("Populated monster spellcasting for participant {} (innate={})",
                participant.getDisplayName(), isInnate);
    }

    /**
     * Casts a monster's spell during an encounter.
     *
     * <p>Validates that the monster knows the spell, deducts the appropriate
     * resource (slot for slot-based casters, pool usage for innate casters),
     * then casts it through {@link SpellCaster}.</p>
     *
     * @param encounter           the active encounter
     * @param monster             the casting participant
     * @param spellName           the name of the spell being cast
     * @param slotLevel           the level at which the spell is cast (0 for innate/at-will)
     * @param targets             the target participants, already checked
     * @param overrideAttackBonus optional override for spell attack bonus (null = use monster default)
     * @param overrideSaveDC      optional override for spell save DC (null = use monster default)
     * @param advantage           true=advantage, false=disadvantage, null=normal
     * @return what the cast did
     */
    public SpellCaster.CastResult castMonsterSpell(
            Encounter encounter,
            EncounterParticipant monster,
            String spellName,
            int slotLevel,
            List<EncounterParticipant> targets,
            Integer overrideAttackBonus,
            Integer overrideSaveDC,
            Boolean advantage
    ) {
        // Look up the spell
        Spell spell = spellRepository.findByNameIgnoreCase(spellName)
                .orElseThrow(() -> new ValidationException("Spell not found: " + spellName));

        // Verify the monster knows this spell
        if (!monsterKnowsSpell(monster, spell)) {
            throw new ValidationException(
                    "Monster " + monster.getDisplayName() + " does not know spell: " + spellName);
        }

        // Determine if this is innate spellcasting based on the monster's data
        String actionTemplatesJson = monster.getMonster() != null
                ? monster.getMonster().getActionTemplates() : null;
        JsonNode spellcasting = extractSpellcastingNode(actionTemplatesJson);
        boolean isInnate = spellcasting != null && spellcasting.path("innateSpells").asBoolean(false);

        // Deduct resource (slot or pool)
        if (isInnate) {
            // Check if at-will — at-will spells have no pool and no slot cost
            if (!isAtWillSpell(spellcasting, spellName)) {
                String poolId = "innate:" + toKebabCase(spellName);
                boolean spent = resourcePoolService.spendPool(monster, poolId, 1);
                if (!spent) {
                    throw new ConflictException(
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

        return spellCaster.cast(encounter, monster, spellName, slotLevel, targets, attackBonus, saveDC, advantage);
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
     * <p>The pools are set back with
     * {@link EncounterParticipant#setResourcePoolsCurrent(List)}.</p>
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
        List<ResourcePoolEntry> existing = participant.getResourcePoolsCurrent() != null
                ? participant.getResourcePoolsCurrent() : List.of();

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
            if (pools.stream().noneMatch(p -> p.poolId().equals(existingEntry.poolId()))) {
                pools.add(existingEntry);
            }
        }

        participant.setResourcePoolsCurrent(pools);
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
     * Looks up each spell name in the spellcasting block and builds the
     * participant's spells known, in the same shape as a player's:
     * {@code [{"id":"uuid","name":"Fireball","level":3,"source":"monster"},...]}.
     *
     * <p>Reads from {@code spellsByLevel} for slot-based casters, and from
     * both {@code atWillSpells} and {@code dailySpells} for innate casters.</p>
     *
     * @param spellcasting the spellcasting JSON node
     * @return the spells known
     */
    public List<KnownSpell> parseSpellsKnown(JsonNode spellcasting, SpellRepository repository) {
        List<KnownSpell> spellsList = new ArrayList<>();

        if (spellcasting == null) return spellsList;

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
                    addSpell(spellsList, spellName, level, repository);
                }
            });
        }

        // For innate casters, also check atWillSpells and dailySpells
        JsonNode atWillSpells = spellcasting.get("atWillSpells");
        if (atWillSpells != null && !atWillSpells.isNull()) {
            for (JsonNode spellNameNode : atWillSpells) {
                String spellName = spellNameNode.asText("");
                if (spellName.isBlank()) continue;
                addSpell(spellsList, spellName, 0, repository);
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
                    addSpell(spellsList, spellName, level, repository);
                }
            });
        }

        return spellsList;
    }

    // ── Internal helpers ───────────────────────────────────────────

    /**
     * Extracts the {@code spellcasting} node from the monster's action templates JSON.
     * The action templates may be either a JSON object (single monster) or a JSON array
     * (array of monster entries — uses the first entry's spellcasting). Null when there is none.
     */
    private JsonNode extractSpellcastingNode(String actionTemplatesJson) {
        JsonNode root = StoredJson.read(objectMapper, actionTemplatesJson, "The monster's action templates");
        if (root.isArray() && !root.isEmpty()) {
            root = root.get(0);
        }
        JsonNode spellcasting = root.path("spellcasting");
        return spellcasting.isMissingNode() || spellcasting.isNull() ? null : spellcasting;
    }

    /**
     * Converts a simple slots object {@code {"1":4,"2":3}} to the participant's
     * {@code {"1":{"max":4,"used":0},"2":{"max":3,"used":0}}}.
     */
    private Map<String, SpellSlot> buildEncounterSlots(JsonNode slots) {
        Map<String, SpellSlot> result = new LinkedHashMap<>();
        slots.fieldNames().forEachRemaining(level -> result.put(level, new SpellSlot(slots.get(level).asInt(0), 0)));
        return result;
    }

    /**
     * Adds a spell lookup entry to the list. If the spell is found in the
     * repository, its database id, name, level, and source="monster" are
     * recorded. If not found, a best-effort entry is added without an id.
     */
    private void addSpell(List<KnownSpell> spellsList, String spellName,
                          int level, SpellRepository repository) {
        // Avoid duplicates by name
        boolean alreadyPresent = spellsList.stream()
                .anyMatch(s -> spellName.equalsIgnoreCase(s.name()));
        if (alreadyPresent) {
            return;
        }

        // Try to look up the spell for its ID
        UUID id = repository.findByNameIgnoreCase(spellName).map(Spell::getId).orElse(null);
        spellsList.add(KnownSpell.monster(id, spellName, level));
    }

    /**
     * Checks whether the monster participant knows the given spell.
     */
    private boolean monsterKnowsSpell(EncounterParticipant monster, Spell spell) {
        List<KnownSpell> spellsKnown = monster.getSpellsKnown();
        return spellsKnown != null
                && spellsKnown.stream().anyMatch(known -> spell.getName().equalsIgnoreCase(known.name()));
    }

    /**
     * Checks whether a spell is in the monster's at-will list (innate only).
     */
    private static boolean isAtWillSpell(JsonNode spellcasting, String spellName) {
        for (JsonNode node : spellcasting.path("atWillSpells")) {
            if (spellName.equalsIgnoreCase(node.asText())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Deducts one spell slot at the given level from the participant's
     * spellSlotsCurrent.
     */
    private void deductSpellSlot(EncounterParticipant participant, int slotLevel) {
        Map<String, SpellSlot> slots = participant.getSpellSlotsCurrent();
        if (slots == null || slots.isEmpty()) {
            throw new ConflictException("No spell slots available for " + participant.getDisplayName());
        }
        String levelKey = String.valueOf(slotLevel);
        SpellSlot slot = slots.get(levelKey);
        if (slot == null) {
            throw new ConflictException(
                    "No spell slots at level " + slotLevel + " for " + participant.getDisplayName());
        }
        if (slot.remaining() <= 0) {
            throw new ConflictException(
                    "No remaining spell slots at level " + slotLevel + " for " + participant.getDisplayName());
        }

        Map<String, SpellSlot> updatedSlots = new LinkedHashMap<>(slots);
        updatedSlots.put(levelKey, slot.spend());
        participant.setSpellSlotsCurrent(updatedSlots);
    }

    /**
     * Parses the daily spell limit from a key like {@code "1e"}, {@code "1e\day"},
     * {@code "3e\day"}, returning the leading integer. Returns 1 if no number
     * can be parsed (sensible default for unadorned keys).
     */
    private int parseDailySpellLimit(String key) {
        if (key == null || key.isBlank()) return 1;
        Matcher matcher = LEADING_NUMBER.matcher(key.trim());
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : 1;
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
        Matcher matcher = LEADING_NUMBER.matcher(key.trim());
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : 0;
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
