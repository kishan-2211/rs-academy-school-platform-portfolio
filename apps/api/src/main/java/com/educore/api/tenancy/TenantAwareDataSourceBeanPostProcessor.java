package com.educore.api.tenancy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.stereotype.Component;

@Component
public class TenantAwareDataSourceBeanPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DataSource dataSource && !(bean instanceof TenantAwareDataSource)) {
            return new TenantAwareDataSource(dataSource);
        }
        return bean;
    }

    static final class TenantAwareDataSource extends DelegatingDataSource {

        TenantAwareDataSource(DataSource targetDataSource) {
            super(targetDataSource);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return scoped(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return scoped(super.getConnection(username, password));
        }

        private Connection scoped(Connection connection) throws SQLException {
            TenantRequestContext.ScopeValue scope = TenantRequestContext.current().orElse(null);
            try {
                apply(connection, scope);
            } catch (SQLException exception) {
                connection.close();
                throw exception;
            }

            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("close") && method.getParameterCount() == 0) {
                            SQLException resetFailure = null;
                            try {
                                apply(connection, null);
                            } catch (SQLException exception) {
                                resetFailure = exception;
                            }
                            try {
                                connection.close();
                            } catch (SQLException closeFailure) {
                                if (resetFailure != null) closeFailure.addSuppressed(resetFailure);
                                throw closeFailure;
                            }
                            if (resetFailure != null) throw resetFailure;
                            return null;
                        }
                        try {
                            Object result = method.invoke(connection, arguments);
                            if (method.getName().equals("prepareStatement") && result instanceof PreparedStatement statement) {
                                return jdbcCompatible(statement);
                            }
                            return result;
                        } catch (InvocationTargetException exception) {
                            throw exception.getCause();
                        }
                    });
        }

        private static PreparedStatement jdbcCompatible(PreparedStatement statement) {
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[] {PreparedStatement.class},
                    (proxy, method, arguments) -> {
                        Object[] compatibleArguments = arguments;
                        if (method.getName().equals("setObject")
                                && arguments != null
                                && arguments.length == 2
                                && arguments[1] instanceof Instant instant) {
                            compatibleArguments = arguments.clone();
                            compatibleArguments[1] = instant.atOffset(ZoneOffset.UTC);
                        }
                        try {
                            return method.invoke(statement, compatibleArguments);
                        } catch (InvocationTargetException exception) {
                            throw exception.getCause();
                        }
                    });
        }

        private static void apply(Connection connection, TenantRequestContext.ScopeValue scope) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT set_config('app.current_user_id', ?, false), set_config('app.current_organisation_id', ?, false)")) {
                statement.setString(1, scope == null || scope.userId() == null ? "" : scope.userId().toString());
                statement.setString(2, scope == null || scope.organisationId() == null ? "" : scope.organisationId().toString());
                statement.execute();
            }
        }
    }
}
