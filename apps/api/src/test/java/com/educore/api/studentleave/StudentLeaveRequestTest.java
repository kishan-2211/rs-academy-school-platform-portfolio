package com.educore.api.studentleave;

import static org.assertj.core.api.Assertions.*;
import static com.educore.api.studentleave.StudentLeaveModels.*;
import jakarta.validation.Validation;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StudentLeaveRequestTest {
    @Test void validatesConfiguredLimitsAndRequiredRequestIdentity() {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();
            assertThat(validator.validate(new PolicyRequest(UUID.randomUUID(),UUID.randomUUID(),30,3,"EXCUSED",true,null,"Synthetic policy"))).isEmpty();
            assertThat(validator.validate(new SubmitRequest(UUID.randomUUID(),LocalDate.now(),LocalDate.now().plusDays(2),"PERSONAL","Synthetic reason","leave-key-123"))).isEmpty();
            assertThat(validator.validate(new PolicyRequest(null,null,367,91,"PRESENT",true,-1L,"")))
                    .extracting(x->x.getPropertyPath().toString()).contains("schoolId","sessionId","maxCalendarDays","retrospectiveDays","attendanceState","expectedVersion","reason");
            assertThat(validator.validate(new SubmitRequest(null,null,null,"UNKNOWN"," ","short"))).hasSizeGreaterThanOrEqualTo(6);
            assertThat(validator.validate(new DecisionRequest(UUID.randomUUID(),"DELETED",-1," "))).hasSizeGreaterThanOrEqualTo(3);
        }
    }
    @Test void countsInclusiveCalendarDaysWithoutGuessingWeekendsOrAttendanceCredit() {
        LocalDate today=LocalDate.of(2026,9,15);
        assertThatCode(()->StudentLeaveService.validateDates(today,today.plusDays(2),today,3,0)).doesNotThrowAnyException();
        assertThatThrownBy(()->StudentLeaveService.validateDates(today,today.plusDays(3),today,3,0)).hasMessageContaining("configured limit");
        assertThatThrownBy(()->StudentLeaveService.validateDates(today,today.minusDays(1),today,3,0)).hasMessageContaining("configured limit");
    }
    @Test void observesConfiguredRetrospectiveWindowIncludingItsBoundary() {
        LocalDate today=LocalDate.of(2026,9,15);
        assertThatCode(()->StudentLeaveService.validateDates(today.minusDays(2),today,today,5,2)).doesNotThrowAnyException();
        assertThatThrownBy(()->StudentLeaveService.validateDates(today.minusDays(3),today,today,5,2)).hasMessageContaining("request window");
    }
}
