package com.tabletophelper.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenApiConfigTest {

    private final OpenApiConfig config = new OpenApiConfig();

    @Test
    @DisplayName("A property outside required becomes nullable, and a required one stays non-null")
    void optionalPropertiesBecomeNullable() {
        Schema<?> campaign = new ObjectSchema()
                .addProperty("name", new StringSchema())
                .addProperty("description", new StringSchema())
                .addRequiredItem("name");
        var openApi = new OpenAPI().components(new Components().addSchemas("CampaignResponse", campaign));

        config.nullableUnlessRequired().customise(openApi);

        assertNull(campaign.getProperties().get("name").getNullable());
        assertEquals(Boolean.TRUE, campaign.getProperties().get("description").getNullable());
    }

    @Test
    @DisplayName("An optional reference is wrapped in allOf, because OpenAPI 3.0 ignores nullable beside $ref")
    void optionalReferenceIsWrappedInAllOf() {
        Schema<?> levelUp = new ObjectSchema()
                .addProperty("character", new Schema<>().$ref("#/components/schemas/CharacterResponse"))
                .addProperty("pendingChoices", new Schema<>().$ref("#/components/schemas/PendingChoices"))
                .addRequiredItem("pendingChoices");
        var openApi = new OpenAPI().components(new Components().addSchemas("LevelUpResponse", levelUp));

        config.nullableUnlessRequired().customise(openApi);

        Schema<?> character = levelUp.getProperties().get("character");
        assertNull(character.get$ref());
        assertEquals(Boolean.TRUE, character.getNullable());
        assertEquals("#/components/schemas/CharacterResponse", character.getAllOf().get(0).get$ref());
        assertEquals("#/components/schemas/PendingChoices", levelUp.getProperties().get("pendingChoices").get$ref());
    }

    @Test
    @DisplayName("A schema without properties and a spec without schemas are left as they are")
    void schemasWithoutPropertiesAreLeftAlone() {
        Schema<?> role = new StringSchema()._enum(List.of("DM", "PLAYER"));
        var openApi = new OpenAPI().components(new Components().addSchemas("UserRole", role));

        config.nullableUnlessRequired().customise(openApi);
        config.nullableUnlessRequired().customise(new OpenAPI());

        assertNull(role.getNullable());
    }

    @Test
    @DisplayName("The server URL is relative, so every host and port serves the same spec")
    void serverUrlIsRelative() {
        assertEquals("/", config.openApi().getServers().get(0).getUrl());
    }
}
