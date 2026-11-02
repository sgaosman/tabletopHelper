package com.tabletophelper.encounter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class AttackRollRequest {

    @NotNull
    private UUID targetId;

    @NotNull
    private Integer attackBonus;

    @NotBlank
    private String damageDice;

    private String damageType;

    private Boolean advantage;

    // The DM's override: the attack is a critical hit whatever the roll (D161). Players get Manual
    // Modifiers in R5 workstream F/G.
    private Boolean forceCrit;

    // Whether the attacker is within 5 feet of the target, which makes a hit on an unconscious or paralyzed
    // target a critical hit (D161). There is no map, so leaving it out, or null, counts as true.
    private Boolean within5Ft = true;
}
