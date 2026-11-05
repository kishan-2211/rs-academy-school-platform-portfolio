package com.educore.api.academics;

import static org.assertj.core.api.Assertions.assertThat;

import com.educore.api.identity.AuthenticatedUser;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;

class TeacherAcademicSnapshotTest {

    @Test
    void copiesAllocationAndPublishedScheduleCollections() {
        UUID schoolId = UUID.randomUUID();
        var groups = new ArrayList<>(List.of(new TeacherAcademicSnapshot.TeachingGroup(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Synthetic session", UUID.randomUUID(), "VI", "A", "Synthetic VI A",
                UUID.randomUUID(), "Synthetic Mathematics", "Maths", 5, "PRIMARY",
                LocalDate.of(2026, 4, 1), null)));
        var entries = new ArrayList<>(List.of(new TeacherAcademicSnapshot.PublishedScheduleEntry(
                UUID.randomUUID(), UUID.randomUUID(), "Synthetic published timetable",
                Instant.parse("2026-08-22T08:00:00Z"), UUID.randomUUID(), "Synthetic Campus",
                UUID.randomUUID(), "Synthetic session", null, null, UUID.randomUUID(),
                "VI", "A", "Synthetic VI A", 1, 1, "Period 1",
                LocalTime.of(8, 0), LocalTime.of(8, 40), "Synthetic Mathematics",
                "Maths", "S-1", null)));

        var snapshot = new TeacherAcademicSnapshot(schoolId, "Synthetic school", groups, entries);
        groups.clear();
        entries.clear();

        assertThat(snapshot.schoolId()).isEqualTo(schoolId);
        assertThat(snapshot.teachingGroups()).hasSize(1);
        assertThat(snapshot.scheduleEntries()).hasSize(1);
    }

    @Test
    void controllerRequiresAcademicReadCapabilityForOwnView() throws Exception {
        var method = AcademicController.class.getDeclaredMethod(
                "myAcademics", AuthenticatedUser.class, UUID.class);

        assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly("/me");
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAuthority('CAPABILITY_academics.read')");
        assertThat(method.getReturnType()).isEqualTo(TeacherAcademicSnapshot.class);
    }
}
