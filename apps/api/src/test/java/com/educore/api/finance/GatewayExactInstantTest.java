package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class GatewayExactInstantTest {
    private static final String ERROR = "Canonical exact gateway instant and matching microsecond projection required";

    @Test void unchangedSourcePreservesAllNanosecondsWhileProjectionFloorsRatherThanRounds() {
        for (String source : new String[] {"2026-10-13T18:29:59.123456499Z", "2026-10-13T18:29:59.123456500Z",
                "2026-10-13T18:29:59.123456999Z"}) {
            Instant instant = Instant.parse(source);
            assertThat(GatewayExactInstant.canonical(instant)).isEqualTo(source);
            assertThat(GatewayExactInstant.parseCanonical(source)).isEqualTo(instant);
            assertThat(GatewayExactInstant.floor(instant)).isEqualTo(Instant.parse("2026-10-13T18:29:59.123456Z"));
            assertThat(GatewayExactInstant.parameter(instant).toInstant()).isEqualTo(GatewayExactInstant.floor(instant));
            assertThat(GatewayExactInstant.resolve(source, GatewayExactInstant.floor(instant))).isEqualTo(instant);
        }
    }

    @Test void floorCannotCrossMidnightAtEitherUtcOrFrozenSchoolDayBoundary() {
        for (String source : new String[] {"2026-10-13T23:59:59.999999500Z", "2026-10-13T18:29:59.999999999Z"}) {
            Instant instant = Instant.parse(source);
            assertThat(GatewayExactInstant.floor(instant).getEpochSecond()).isEqualTo(instant.getEpochSecond());
            assertThat(GatewayExactInstant.floor(instant).getNano()).isEqualTo(999999000);
        }
    }

    @Test void preEpochFloorMovesTowardEarlierTimeNotTowardZero() {
        Instant instant = Instant.parse("1969-12-31T23:59:59.999999500Z");
        assertThat(GatewayExactInstant.floor(instant)).isEqualTo(Instant.parse("1969-12-31T23:59:59.999999Z"));
        assertThat(GatewayExactInstant.floor(instant)).isBefore(instant);
        assertThat(GatewayExactInstant.canonical(instant)).isEqualTo("1969-12-31T23:59:59.999999500Z");
    }

    @Test void canonicalWholeMillisecondAndMicrosecondInstantsRemainUnchanged() {
        for (String source : new String[] {"2026-10-13T12:00:00Z", "2026-10-13T12:00:00.123Z", "2026-10-13T12:00:00.123456Z"}) {
            Instant instant = Instant.parse(source);
            assertThat(GatewayExactInstant.canonical(instant)).isEqualTo(source);
            assertThat(GatewayExactInstant.floor(instant)).isEqualTo(instant);
        }
    }

    @Test void noncanonicalAndMalformedSourceErrorsNeverEchoSuppliedPayloadOrCause() {
        for (String source : new String[] {"2026-10-13T12:00:00.000Z", "2026-10-13T12:00:00.1234560Z",
                "2026-10-13T17:30:00+05:30", "2026-10-13T12:00:60Z", "synthetic-private-provider-payload", "", null}) {
            assertThatThrownBy(() -> GatewayExactInstant.parseCanonical(source))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage(ERROR).hasNoCause();
        }
    }

    @Test void projectionMismatchAndSubmicrosecondProjectionAreRejectedWithoutGuessing() {
        Instant source = Instant.parse("2026-10-13T12:00:00.999999500Z");
        assertThatThrownBy(() -> GatewayExactInstant.resolve(source.toString(), Instant.parse("2026-10-13T12:00:01Z")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(ERROR).hasNoCause();
        assertThatThrownBy(() -> GatewayExactInstant.resolve(source.toString(), source))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(ERROR).hasNoCause();
        assertThatThrownBy(() -> GatewayExactInstant.resolve(null, source))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(ERROR).hasNoCause();
    }

    @Test void nullLegacySourceReturnsOnlyExistingStoredPrecisionNotInventedExactSource() {
        Instant legacy = Instant.parse("2026-10-13T12:00:01Z");
        assertThat(GatewayExactInstant.resolve(null, legacy)).isEqualTo(legacy);
        // Unknown legacy precision is not reconstructed as the likely prior nanosecond value.
        assertThat(GatewayExactInstant.resolve(null, legacy)).isNotEqualTo(Instant.parse("2026-10-13T12:00:00.999999500Z"));
    }

    @Test void nullableReceiptTimePreservesAbsenceButNeverIgnoresPartialExactEvidence() {
        assertThat(GatewayExactInstant.resolveNullable(null, null)).isNull();
        Instant projection = Instant.parse("2026-10-13T12:00:00.123456Z");
        assertThat(GatewayExactInstant.resolveNullable(null, projection)).isEqualTo(projection);
        assertThat(GatewayExactInstant.resolveNullable("2026-10-13T12:00:00.123456500Z", projection))
                .isEqualTo(Instant.parse("2026-10-13T12:00:00.123456500Z"));
        assertThatThrownBy(() -> GatewayExactInstant.resolveNullable("2026-10-13T12:00:00.123456500Z", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(ERROR).hasNoCause();
    }

    @Test void postgresCompatibleFiniteCalendarBoundsAreExplicitAndSanitized() {
        assertThat(GatewayExactInstant.canonical(Instant.parse("0001-01-01T00:00:00Z"))).isEqualTo("0001-01-01T00:00:00Z");
        assertThat(GatewayExactInstant.canonical(Instant.parse("9999-12-31T23:59:59.999999999Z")))
                .isEqualTo("9999-12-31T23:59:59.999999999Z");
        for (Instant value : new Instant[] {Instant.parse("0000-12-31T23:59:59Z"), Instant.parse("+10000-01-01T00:00:00Z"), null}) {
            assertThatThrownBy(() -> GatewayExactInstant.canonical(value)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(ERROR).hasNoCause();
        }
    }

    @Test void jdbcParameterAndTypedReadersKeepTheSameIsoCalendarAsTheOriginalSource() throws Exception {
        Instant source=Instant.parse("0001-01-01T00:00:00.123456500Z");
        assertThat(GatewayExactInstant.parameter(source).toInstant()).isEqualTo(Instant.parse("0001-01-01T00:00:00.123456Z"));
        var row=org.mockito.Mockito.mock(java.sql.ResultSet.class);
        org.mockito.Mockito.when(row.getString("exact")).thenReturn(source.toString());
        org.mockito.Mockito.when(row.getObject("projected",java.time.OffsetDateTime.class)).thenReturn(GatewayExactInstant.parameter(source));
        assertThat(GatewayExactInstant.read(row,"exact","projected")).isEqualTo(source);
        org.mockito.Mockito.verify(row,org.mockito.Mockito.never()).getTimestamp(org.mockito.ArgumentMatchers.anyString());
    }
}
