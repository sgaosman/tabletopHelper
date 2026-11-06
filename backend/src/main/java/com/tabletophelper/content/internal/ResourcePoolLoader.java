package com.tabletophelper.content.internal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.CharacterClassRepository;
import com.tabletophelper.reference.ClassFeaturePool;
import com.tabletophelper.reference.ClassFeaturePoolRepository;
import com.tabletophelper.reference.MonsterPoolTrigger;
import com.tabletophelper.reference.MonsterPoolTriggerRepository;
import com.tabletophelper.reference.PoolLevelTable;
import com.tabletophelper.reference.ResourcePoolDefinition;
import com.tabletophelper.reference.ResourcePoolDefinitionRepository;
import com.tabletophelper.reference.Subclass;
import com.tabletophelper.reference.SubclassRepository;
import com.tabletophelper.resourcepool.ResourcePoolService;
import com.tabletophelper.rules.Expression;
import com.tabletophelper.rules.ExpressionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Loads the M24.5 resource pool reference tables from resource-pools.json (D169): the pool definitions, by pool ID;
 * the pools each class and subclass grants, by class, subclass and pool; and the monster pool triggers, by pool and
 * condition. A class or subclass the classes loader didn't write is logged and its pools skipped.
 * <p>
 * Every formula, a level table's included, is checked against a context of zeros with the names a character's pool
 * formulas can read, and every recharge check too: one that doesn't read, or names anything else, fails the load
 * (D169). So does a definition without a maximum, a rest no reset knows, or a level table whose class isn't loaded.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ResourcePoolLoader implements ContentLoader {

    static final String FILE = "data/content/resource-pools.json";

    private final ResourcePoolDefinitionRepository poolDefRepo;
    private final ClassFeaturePoolRepository classFeaturePoolRepo;
    private final MonsterPoolTriggerRepository monsterTriggerRepo;
    private final CharacterClassRepository classRepo;
    private final SubclassRepository subclassRepo;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "resource-pools";
    }

    @Override
    public List<String> files() {
        return List.of(FILE);
    }

    @Override
    public int version() {
        // 2: level tables (V27), and every formula checked (D169)
        return 2;
    }

    @Override
    public List<String> dependsOn() {
        return List.of("classes");
    }

    @Override
    public void load(LoadContext context) throws Exception {
        JsonNode root = context.read(FILE);
        List<CharacterClass> classes = classRepo.findAll();
        Map<String, ResourcePoolDefinition> definitions = context.upsert("resource_pool_definitions", poolDefRepo,
                definitions(root.path("definitions"), classes), ResourcePoolDefinition::getPoolId);
        context.upsert("class_feature_pools", classFeaturePoolRepo,
                classPools(root.path("classPools"), definitions, classes), ResourcePoolLoader::classPoolKey);
        context.upsert("monster_pool_triggers", monsterTriggerRepo,
                monsterTriggers(root.path("monsterTriggers"), definitions), trigger -> triggerKey(context, trigger));
    }

    // ── Pool definitions ─────────────────────────────────────────

    private List<ResourcePoolDefinition> definitions(JsonNode entries, List<CharacterClass> classes)
            throws Exception {
        Set<String> names = ResourcePoolService.formulaNames(classes.stream().map(CharacterClass::getName).toList());
        Set<String> classNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        classes.forEach(cc -> classNames.add(cc.getName()));
        List<ResourcePoolDefinition> defs = new ArrayList<>();
        for (JsonNode entry : entries) {
            JsonNode metadata = entry.path("metadata");
            JsonNode levelTable = entry.path("levelTable");
            ResourcePoolDefinition def = ResourcePoolDefinition.builder()
                    .poolId(required(entry, "poolId"))
                    .displayName(required(entry, "displayName"))
                    .sourceType(required(entry, "sourceType"))
                    .maxUsesFormula(required(entry, "maxUsesFormula"))
                    .resetOn(required(entry, "resetOn"))
                    .resetAmount(text(entry, "resetAmount"))
                    .resetCheck(text(entry, "resetCheck"))
                    .spendActionType(text(entry, "spendActionType"))
                    .description(text(entry, "description"))
                    .icon(text(entry, "icon"))
                    .metadata(metadata.isObject() ? objectMapper.writeValueAsString(metadata) : null)
                    .levelTable(levelTable.isObject() ? objectMapper.treeToValue(levelTable, PoolLevelTable.class)
                            : null)
                    .build();
            check(def, names, classNames);
            defs.add(def);
        }
        return defs;
    }

    private static final Set<String> RESETS = Set.of("shortRest", "longRest", "turn", "dawn", "never");

    // What a use can cost from the turn, which the pool-use command charges (D174)
    private static final Set<String> SPEND_ACTION_TYPES = Set.of("ACTION", "BONUS_ACTION", "REACTION", "FREE",
            "REPLACE_ATTACK");

    // A definition's formulas, rests and level table, as the pools will read them
    private static void check(ResourcePoolDefinition def, Set<String> names, Set<String> classNames) {
        String pool = def.getPoolId();
        formula(pool, "maxUsesFormula", def.getMaxUsesFormula(), names);
        if (def.getResetAmount() != null) formula(pool, "resetAmount", def.getResetAmount(), names);
        if (def.getResetCheck() != null) {
            try {
                Expression.validateRechargeCheck(def.getResetCheck(), names);
            } catch (ExpressionException e) {
                throw new IllegalStateException(FILE + ": " + pool + "'s resetCheck: " + e.getMessage(), e);
            }
        }
        reset(pool, def.getResetOn());
        if (def.getSpendActionType() != null && !SPEND_ACTION_TYPES.contains(def.getSpendActionType())) {
            throw new IllegalStateException(FILE + ": " + pool + " is spent as '" + def.getSpendActionType()
                    + "', which isn't one of " + SPEND_ACTION_TYPES);
        }
        PoolLevelTable table = def.getLevelTable();
        if (table == null) return;
        if (table.levelOf() == null || !classNames.contains(table.levelOf())) {
            throw new IllegalStateException(FILE + ": " + pool + "'s level table reads the level of '"
                    + table.levelOf() + "', which isn't a loaded class");
        }
        if (table.rows().isEmpty()) {
            throw new IllegalStateException(FILE + ": " + pool + "'s level table has no rows");
        }
        for (PoolLevelTable.Row row : table.rows()) {
            if (row.level() < 1 || row.level() > 20) {
                throw new IllegalStateException(FILE + ": " + pool + "'s level table has a row at level " + row.level());
            }
            if (row.maxUses() != null) formula(pool, "level " + row.level() + " maxUses", row.maxUses(), names);
            if (row.resetOn() != null) reset(pool, row.resetOn());
        }
    }

    private static void formula(String pool, String field, String formula, Set<String> names) {
        try {
            Expression.validate(formula, names);
        } catch (ExpressionException e) {
            throw new IllegalStateException(FILE + ": " + pool + "'s " + field + ": " + e.getMessage(), e);
        }
    }

    private static void reset(String pool, String resetOn) {
        if (!RESETS.contains(resetOn)) {
            throw new IllegalStateException(FILE + ": " + pool + " resets on '" + resetOn + "', which isn't one of "
                    + RESETS);
        }
    }

    // ── Class feature pools (junction table) ──────────────────────

    private List<ClassFeaturePool> classPools(JsonNode entries, Map<String, ResourcePoolDefinition> definitions,
                                              List<CharacterClass> loadedClasses) {
        Map<String, CharacterClass> classes = new HashMap<>();
        for (CharacterClass cc : loadedClasses) {
            classes.put(LoadContext.nameAndSource(cc.getName(), cc.getSource()), cc);
        }
        Map<String, Subclass> subclasses = new HashMap<>();
        for (Subclass sc : subclassRepo.findAll()) {
            subclasses.put(CharacterClassLoader.subclassKey(sc), sc);
        }

        List<ClassFeaturePool> mappings = new ArrayList<>();
        for (JsonNode entry : entries) {
            String poolId = required(entry, "poolId");
            String classKey = LoadContext.nameAndSource(required(entry, "class"), text(entry, "classSource"));
            CharacterClass cc = classes.get(classKey);
            if (cc == null) {
                log.warn("Class '{}' not found in database, skipping pool mapping for '{}'", classKey, poolId);
                continue;
            }
            Subclass sc = null;
            String subclassName = text(entry, "subclass");
            if (subclassName != null) {
                String subclassKey = classKey + "|" + LoadContext.nameAndSource(subclassName,
                        text(entry, "subclassSource"));
                sc = subclasses.get(subclassKey);
                if (sc == null) {
                    log.warn("Subclass '{}' not found in database, skipping pool mapping for '{}'", subclassKey, poolId);
                    continue;
                }
            }
            ResourcePoolDefinition def = definitions.get(poolId);
            if (def == null) {
                throw new IllegalStateException(FILE + " maps " + classKey + " to the pool " + poolId
                        + ", which it doesn't define");
            }
            mappings.add(ClassFeaturePool.builder()
                    .characterClass(cc)
                    .subclass(sc)
                    .minLevel(entry.path("minLevel").asInt(0))
                    .poolDefinition(def)
                    .build());
        }
        return mappings;
    }

    /** A class pool's natural key: its class, its subclass if it has one, and its pool. */
    static String classPoolKey(ClassFeaturePool pool) {
        CharacterClass cc = pool.getCharacterClass();
        Subclass sc = pool.getSubclass();
        return LoadContext.nameAndSource(cc.getName(), cc.getSource()) + "|"
                + (sc != null ? LoadContext.nameAndSource(sc.getName(), sc.getSource()) : "") + "|"
                + pool.getPoolDefinition().getPoolId();
    }

    // ── Monster pool triggers ─────────────────────────────────────

    private List<MonsterPoolTrigger> monsterTriggers(JsonNode entries, Map<String, ResourcePoolDefinition> definitions)
            throws Exception {
        List<MonsterPoolTrigger> triggers = new ArrayList<>();
        for (JsonNode entry : entries) {
            String poolId = required(entry, "poolId");
            ResourcePoolDefinition def = definitions.get(poolId);
            if (def == null) {
                throw new IllegalStateException(FILE + " has a monster trigger for the pool " + poolId
                        + ", which it doesn't define");
            }
            triggers.add(MonsterPoolTrigger.builder()
                    .poolDefinition(def)
                    .triggerCondition(objectMapper.writeValueAsString(entry.path("triggerCondition")))
                    .priority(entry.path("priority").asInt(0))
                    .build());
        }
        return triggers;
    }

    // A trigger's natural key: its pool and its condition's JSON with the keys sorted
    private static String triggerKey(LoadContext context, MonsterPoolTrigger trigger) {
        return trigger.getPoolDefinition().getPoolId() + "|" + context.canonicalJson(trigger.getTriggerCondition());
    }

    // ── Helpers ───────────────────────────────────────────────────

    private static String text(JsonNode entry, String field) {
        JsonNode value = entry.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asString("");
    }

    private static String required(JsonNode entry, String field) {
        String value = text(entry, field);
        if (value == null) {
            throw new IllegalStateException(FILE + " has an entry without " + field + ": " + entry);
        }
        return value;
    }
}
