package com.educore.api.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TenantAwareDataSourceTest {

    @Test
    @SuppressWarnings("try")
    void appliesVerifiedScopeAndResetsItBeforeReturningTheConnection() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection physicalConnection = mock(Connection.class);
        PreparedStatement scopeStatement = mock(PreparedStatement.class);
        when(delegate.getConnection()).thenReturn(physicalConnection);
        when(physicalConnection.prepareStatement(anyString())).thenReturn(scopeStatement);
        var dataSource = new TenantAwareDataSourceBeanPostProcessor.TenantAwareDataSource(delegate);
        UUID userId = UUID.randomUUID();
        UUID organisationId = UUID.randomUUID();

        try (TenantRequestContext.Scope scope = TenantRequestContext.open(userId, organisationId)) {
            Connection scoped = dataSource.getConnection();
            assertThat(scoped).isNotSameAs(physicalConnection);
            scoped.close();
        }

        ArgumentCaptor<String> userValues = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> organisationValues = ArgumentCaptor.forClass(String.class);
        verify(scopeStatement, times(2)).setString(org.mockito.ArgumentMatchers.eq(1), userValues.capture());
        verify(scopeStatement, times(2)).setString(org.mockito.ArgumentMatchers.eq(2), organisationValues.capture());
        assertThat(userValues.getAllValues()).containsExactly(userId.toString(), "");
        assertThat(organisationValues.getAllValues()).containsExactly(organisationId.toString(), "");
        verify(scopeStatement, times(2)).execute();
        verify(scopeStatement, atLeastOnce()).close();
        verify(physicalConnection).close();
    }

    @Test
    void convertsInstantParametersToThePostgresqlSupportedUtcOffsetType() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection physicalConnection = mock(Connection.class);
        PreparedStatement scopeStatement = mock(PreparedStatement.class);
        PreparedStatement queryStatement = mock(PreparedStatement.class);
        when(delegate.getConnection()).thenReturn(physicalConnection);
        when(physicalConnection.prepareStatement(anyString())).thenAnswer(invocation ->
                "SELECT ?".equals(invocation.getArgument(0)) ? queryStatement : scopeStatement);
        var dataSource = new TenantAwareDataSourceBeanPostProcessor.TenantAwareDataSource(delegate);
        Instant instant = Instant.parse("2026-08-27T08:00:00Z");

        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT ?")) {
            statement.setObject(1, instant);
        }

        verify(queryStatement).setObject(1, instant.atOffset(ZoneOffset.UTC));
        verify(queryStatement).close();
    }
}
