package com.educore.api.finance;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Preserves authenticated nanosecond source instants separately from PostgreSQL's
 * microsecond timestamp projection. Never replace source evidence with the
 * projection or infer missing precision in a pre-existing legacy record.
 */
public final class GatewayExactInstant {
    private static final Instant FIRST = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant AFTER_LAST = Instant.parse("+10000-01-01T00:00:00Z");
    private GatewayExactInstant() {}

    /** The unchanged canonical UTC representation of the full source instant. */
    public static String canonical(Instant source) {
        requireSupported(source);
        return source.toString();
    }

    /** Reject noncanonical offsets, fractional padding and malformed source text. */
    public static Instant parseCanonical(String exact) {
        try {
            if (exact == null || exact.length() < 20 || exact.length() > 30) throw invalid();
            Instant source = Instant.parse(exact);
            if (!canonical(source).equals(exact)) throw invalid();
            return source;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    /** Explicit floor, not JDBC's implicit half-up conversion across a day boundary. */
    public static Instant floor(Instant source) {
        requireSupported(source);
        return source.truncatedTo(ChronoUnit.MICROS);
    }

    /** JDBC 4.2 uses the same proleptic ISO calendar as Instant and PostgreSQL. */
    public static OffsetDateTime parameter(Instant source) {
        return floor(source).atOffset(ZoneOffset.UTC);
    }

    /**
     * Return preserved full evidence only after verifying its derivative. NULL
     * source is explicitly legacy: return its stored precision without claiming
     * the original provider instant was known to this precision.
     */
    public static Instant resolve(String exact, Instant projected) {
        requireSupported(projected);
        if (!floor(projected).equals(projected)) throw invalid();
        if (exact == null) return projected;
        Instant source = parseCanonical(exact);
        if (!floor(source).equals(projected)) throw invalid();
        return source;
    }

    /** Date-only/manual absence remains absent; partial or malformed exact evidence fails closed. */
    public static Instant resolveNullable(String exact, Instant projected) {
        if (exact == null && projected == null) return null;
        return resolve(exact, projected);
    }

    public static Instant read(ResultSet row, String exactColumn, String projectionColumn) throws SQLException {
        return resolve(row.getString(exactColumn), projected(row, projectionColumn));
    }

    public static Instant readNullable(ResultSet row, String exactColumn, String projectionColumn) throws SQLException {
        return resolveNullable(row.getString(exactColumn), projected(row, projectionColumn));
    }

    private static Instant projected(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static void requireSupported(Instant source) {
        if (source == null || source.isBefore(FIRST) || !source.isBefore(AFTER_LAST)) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Canonical exact gateway instant and matching microsecond projection required");
    }
}
