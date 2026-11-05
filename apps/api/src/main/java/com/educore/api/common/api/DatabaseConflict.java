package com.educore.api.common.api;

import java.sql.SQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

/** Classifies expected integrity rejections without reflecting SQL or row contents to callers. */
public final class DatabaseConflict {
    private DatabaseConflict() {}

    public static boolean isIntegrityRejection(DataAccessException exception) {
        if (exception instanceof DataIntegrityViolationException) return true;
        for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                // P0001 is PostgreSQL RAISE EXCEPTION, used by our forward-only domain guards.
                if ("P0001".equals(state) || (state != null && state.startsWith("23"))) return true;
            }
        }
        return false;
    }
}
