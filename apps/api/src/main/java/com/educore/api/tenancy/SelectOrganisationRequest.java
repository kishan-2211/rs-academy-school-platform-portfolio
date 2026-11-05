package com.educore.api.tenancy;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record SelectOrganisationRequest(@NotNull UUID organisationId) {
}
