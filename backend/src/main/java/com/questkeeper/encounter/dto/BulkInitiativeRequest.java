package com.questkeeper.encounter.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class BulkInitiativeRequest {

    @NotEmpty
    private List<SetInitiativeRequest> initiatives;
}
