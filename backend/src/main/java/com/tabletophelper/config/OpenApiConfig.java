package com.tabletophelper.config;

import com.tabletophelper.monster.MonsterTemplates;
import com.tabletophelper.reference.FeatAbilityIncrease;
import com.tabletophelper.reference.FeatEffects;
import com.tabletophelper.reference.FeatPrerequisite;
import com.tabletophelper.reference.FeatSpells;
import com.tabletophelper.reference.SpellTemplate;
import com.tabletophelper.rules.character.FeatPrerequisites.PrerequisiteAlternative;
import tools.jackson.databind.JsonNode;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * The OpenAPI spec the frontend generates its API types from (D171). The prod profile turns it off
 * (D154).
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    // The template columns are typed records on the server, but the API sends their stored JSON as it is, with
    // whatever keys it has (D168), so the spec describes them as open objects
    static {
        SpringDocUtils.getConfig()
                .replaceWithSchema(MonsterTemplates.class, storedJson("The monster's action templates as seeded (D030)"))
                .replaceWithSchema(SpellTemplate.class, storedJson("The spell's effect template as seeded (D030)"))
                // A feat's columns are typed records too (D168): the prerequisite is the alternatives FeatLoader
                // writes, and the others keep the 5etools shapes, described here as far as the frontend reads them
                .replaceWithClass(FeatPrerequisite.class, PrerequisiteAlternative[].class)
                .replaceWithSchema(FeatAbilityIncrease.class, featAbilityIncrease())
                .replaceWithSchema(FeatEffects.class, featEffects())
                .replaceWithSchema(FeatSpells.class, featSpells());
    }

    // [{"CON": 1}, {"choose": {"from": ["STR", "DEX"], "amount": 1}}]
    private static Schema<?> featAbilityIncrease() {
        ObjectSchema choose = new ObjectSchema();
        choose.addProperty("from", new ArraySchema().items(new StringSchema()));
        choose.addProperty("amount", new IntegerSchema());
        choose.required(List.of("from", "amount"));
        ObjectSchema entry = new ObjectSchema();
        entry.addProperty("choose", choose);
        entry.additionalProperties(true);
        entry.description("Fixed increases by ability, such as {\"CON\": 1}, or a choice");
        return new ArraySchema().items(entry)
                .description("The feat's ability score increases, each fixed or a choice");
    }

    private static Schema<?> featEffects() {
        ObjectSchema effects = new ObjectSchema();
        for (String list : List.of("resistances", "expertise", "armorProficiencies", "weaponProficiencies",
                "toolProficiencies", "skillProficiencies", "languageProficiencies", "savingThrowProficiencies",
                "skillToolLanguageProficiencies")) {
            effects.addProperty(list, new ArraySchema().items(stringOrObject()));
        }
        ObjectSchema progression = new ObjectSchema();
        progression.addProperty("name", new StringSchema());
        progression.addProperty("featureType", new ArraySchema().items(new StringSchema()));
        progression.addProperty("progression", new ObjectSchema().additionalProperties(new IntegerSchema()));
        effects.addProperty("optionalFeatureProgression", new ArraySchema().items(progression));
        for (String bonus : List.of("speedBonus", "initiativeBonus", "hpPerLevel", "passivePerceptionBonus",
                "passiveInvestigationBonus")) {
            effects.addProperty(bonus, new IntegerSchema());
        }
        ObjectSchema resource = new ObjectSchema();
        resource.addProperty("name", new StringSchema());
        resource.addProperty("maxUses", new IntegerSchema());
        resource.addProperty("resetOn", new StringSchema());
        resource.required(List.of("name", "maxUses", "resetOn"));
        effects.addProperty("resource", resource);
        effects.addProperty("repeatable", new BooleanSchema());
        effects.additionalProperties(true);
        effects.description("The feat's effects: the 5etools lists, whose entries are a string or an object, and "
                + "the bonuses feat-effects.json adds (D169)");
        return effects;
    }

    // A 5etools list entry: a name, or an object such as {"light": true} or {"choose": {...}}
    private static Schema<?> stringOrObject() {
        ObjectSchema object = new ObjectSchema();
        object.additionalProperties(true);
        return new ComposedSchema().addOneOfItem(new StringSchema()).addOneOfItem(object);
    }

    // The 5etools additionalSpells: one option per spell list, each spell named or chosen
    private static Schema<?> featSpells() {
        ObjectSchema chooseFrom = new ObjectSchema();
        chooseFrom.addProperty("from", new ArraySchema().items(new StringSchema()));
        chooseFrom.addProperty("count", new IntegerSchema());
        chooseFrom.required(List.of("from"));
        ObjectSchema choice = new ObjectSchema();
        choice.addProperty("choose", new ComposedSchema().addOneOfItem(new StringSchema()).addOneOfItem(chooseFrom));
        choice.addProperty("count", new IntegerSchema());
        choice.required(List.of("choose"));
        Schema<?> spell = new ComposedSchema().addOneOfItem(new StringSchema()).addOneOfItem(choice);
        Schema<?> spells = new ArraySchema().items(spell);
        ObjectSchema byUses = new ObjectSchema();
        byUses.additionalProperties(spells);
        ObjectSchema casting = new ObjectSchema();
        casting.addProperty("daily", byUses);
        casting.addProperty("rest", byUses);
        casting.addProperty("will", spells);
        casting.addProperty("ritual", spells);
        ObjectSchema option = new ObjectSchema();
        option.addProperty("name", new StringSchema());
        ObjectSchema chooseAbility = new ObjectSchema();
        chooseAbility.addProperty("choose", new ArraySchema().items(new StringSchema()));
        option.addProperty("ability", new ComposedSchema().addOneOfItem(new StringSchema()).addOneOfItem(chooseAbility));
        option.addProperty("known", new ObjectSchema().additionalProperties(spells));
        option.addProperty("innate", new ObjectSchema().additionalProperties(casting));
        return new ArraySchema().items(option).description("The spells the feat grants, as the 5etools data "
                + "writes them: one option per spell list");
    }

    private static ObjectSchema storedJson(String description) {
        ObjectSchema schema = new ObjectSchema();
        schema.additionalProperties(true);
        schema.description(description);
        return schema;
    }

    /**
     * The quick reference answers with a JsonNode, any JSON. The OpenAPI library knows only Jackson 2's JsonNode as
     * an open object and would describe Jackson 3's by its getters, so its schema is set back to an open object.
     */
    @Bean
    OpenApiCustomizer jsonNodeIsAnyObject() {
        return openApi -> {
            if (openApi.getComponents() != null && openApi.getComponents().getSchemas() != null) {
                openApi.getComponents().getSchemas().computeIfPresent(JsonNode.class.getSimpleName(),
                        (name, schema) -> new ObjectSchema());
            }
        };
    }

    /** A relative server URL, so the spec doesn't depend on the host or port that served it. */
    @Bean
    OpenAPI openApi() {
        return new OpenAPI().servers(List.of(new Server().url("/")));
    }

    /**
     * Jackson writes null fields, so every property is nullable unless its DTO marks it
     * {@code @Schema(requiredMode = REQUIRED)}.
     */
    @Bean
    OpenApiCustomizer nullableUnlessRequired() {
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            openApi.getComponents().getSchemas().values().forEach(OpenApiConfig::markOptionalPropertiesNullable);
        };
    }

    @SuppressWarnings("rawtypes")
    private static void markOptionalPropertiesNullable(Schema<?> schema) {
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null) {
            return;
        }
        List<String> required = schema.getRequired() != null ? schema.getRequired() : List.of();
        properties.replaceAll((name, property) -> required.contains(name) ? property : nullable(property));
    }

    // OpenAPI 3.0 ignores every keyword beside a $ref, so a nullable reference is wrapped in allOf
    @SuppressWarnings("rawtypes")
    private static Schema nullable(Schema property) {
        if (property.get$ref() == null) {
            return property.nullable(true);
        }
        return new Schema<>()
                .addAllOfItem(new Schema<>().$ref(property.get$ref()))
                .description(property.getDescription())
                .nullable(true);
    }
}
