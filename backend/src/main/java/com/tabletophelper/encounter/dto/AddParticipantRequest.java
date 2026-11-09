package com.tabletophelper.encounter.dto;

import com.tabletophelper.encounter.ParticipantType;
import com.tabletophelper.rules.combat.SummonRules;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class AddParticipantRequest {

    @NotBlank
    @Schema(implementation = ParticipantType.class)
    private String participantType;

    private UUID characterId;

    private UUID monsterId;

    // Required for a MONSTER or a plain COMPANION, the base of numbered names when there are several; a PLAYER takes
    // its character's name, and a summoned COMPANION its stat block's when this is left out (D182)
    @Size(max = 200)
    private String displayName;

    private Integer initiativeModifier;

    private Integer hpMax;

    private Integer armourClass;

    private UUID controlledByUserId;

    private Integer quantity;

    // A COMPANION a summoning spell brought (D182): the caster's participant, and the spell, slot level, option,
    // spirit variant, initiative mode, familiar's or steed's type and concentration link as POST
    // /combat/summons/companion takes them, with monsterId and quantity as its one kind of creature. Left out, a
    // COMPANION is a creature on the party's side with the monster's statistics, controlled by controlledByUserId
    private UUID summonerParticipantId;

    private String spellName;

    @Min(1)
    @Max(9)
    private Integer slotLevel;

    private String optionId;

    private String variant;

    private SummonRules.InitiativeMode initiativeMode;

    private String creatureType;

    private Boolean concentrationLinked;
}
