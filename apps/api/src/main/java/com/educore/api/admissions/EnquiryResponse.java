package com.educore.api.admissions;

import java.time.Instant;

public record EnquiryResponse(String reference, Instant receivedAt, String status) {
}
