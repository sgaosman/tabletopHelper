package com.tabletophelper.character.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The proficiencies a level added to the sheet, as its level history entry records them so that levelling down
 * removes exactly those (D167): the armour, weapon and tool proficiencies multiclassing into a class grants (PHB
 * p.164), less any the character already had. A kind with none added is left out.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProficienciesGained(
        List<String> armor,
        List<String> weapons,
        List<String> tools
) {
}
