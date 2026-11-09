package com.tabletophelper.character.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The proficiencies a level added to the sheet, as its level history entry records them so that levelling down
 * removes exactly those (D167): the armour, weapon and tool proficiencies multiclassing into a class grants (PHB
 * p.164), and the bonus proficiencies a subclass gives at its subclass level, such as a Life Domain's heavy armour
 * (PHB p.60), each less any the character already had. A kind with none added is left out.
 *
 * @param armor     armour and shield proficiencies, such as "heavy"
 * @param weapons   weapon proficiencies, such as "martial"
 * @param tools     tool proficiencies, such as "smith's tools"
 * @param skills    skill proficiencies a subclass gives, such as "Arcana"
 * @param languages languages a subclass teaches, such as "Giant"
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProficienciesGained(
        List<String> armor,
        List<String> weapons,
        List<String> tools,
        List<String> skills,
        List<String> languages
) {

    /** Armour, weapon and tool proficiencies only, as multiclassing grants them. */
    public ProficienciesGained(List<String> armor, List<String> weapons, List<String> tools) {
        this(armor, weapons, tools, null, null);
    }
}
