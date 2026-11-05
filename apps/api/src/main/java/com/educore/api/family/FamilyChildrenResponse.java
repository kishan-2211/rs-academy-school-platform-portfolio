package com.educore.api.family;

import java.util.List;

public record FamilyChildrenResponse(List<FamilyChildSummary> children) {
    public FamilyChildrenResponse {
        children = List.copyOf(children);
    }
}
