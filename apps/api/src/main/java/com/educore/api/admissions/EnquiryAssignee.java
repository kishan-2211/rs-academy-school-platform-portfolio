package com.educore.api.admissions;

import java.util.UUID;

public record EnquiryAssignee(UUID membershipId, UUID schoolId, String displayName) {
}
