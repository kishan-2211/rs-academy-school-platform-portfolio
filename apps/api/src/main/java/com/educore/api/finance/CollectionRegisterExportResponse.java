package com.educore.api.finance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Bounded complete-filter snapshot shared by CSV and print; never an assembly of live pages. */
public record CollectionRegisterExportResponse(UUID schoolId, String schoolName, String currencyCode,
        LocalDate from, LocalDate to, String paymentMethod, String query, Instant generatedAt,
        long total, CollectionRegisterResponse.Summary summary,
        List<CollectionRegisterResponse.Tender> tenderTotals, List<CollectionRegisterResponse.Movement> items) {
    public CollectionRegisterExportResponse {
        tenderTotals=List.copyOf(tenderTotals);
        items=List.copyOf(items);
    }
}
