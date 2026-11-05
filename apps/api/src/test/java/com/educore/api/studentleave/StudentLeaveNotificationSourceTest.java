package com.educore.api.studentleave;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import com.educore.api.tenancy.TenantRequestContext;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class StudentLeaveNotificationSourceTest {
    @Test
    void refusesMissingOrganisationContextBeforeReadingAnySource() {
        var jdbc=mock(NamedParameterJdbcTemplate.class);
        var source=new StudentLeaveNotificationSource(jdbc,Clock.systemUTC());
        assertThatThrownBy(()->source.current(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("organisation context");
        verifyNoInteractions(jdbc);
    }

    @Test
    void refusesMismatchedOrganisationContextBeforeReadingAnySource() {
        var jdbc=mock(NamedParameterJdbcTemplate.class);
        var source=new StudentLeaveNotificationSource(jdbc,Clock.systemUTC());
        try(var ignored=TenantRequestContext.open(null,UUID.randomUUID())) {
            assertThatThrownBy(()->source.current(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("organisation context");
            verifyNoInteractions(jdbc);
        }
    }
}
