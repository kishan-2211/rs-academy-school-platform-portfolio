package com.educore.api.finance;

import com.educore.api.common.api.ApiException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Package-local reader, called only after FinanceService's existing live school scope check. */
final class CollectionRegisterQuery {
    static final int EXPORT_LIMIT = 10000;
    record Snapshot(long total, CollectionRegisterResponse.Summary summary,
            List<CollectionRegisterResponse.Tender> tenderTotals, List<CollectionRegisterResponse.Movement> items) {}
    static final List<String> METHODS = List.of("CASH", "SCHOOL_UPI", "BANK_TRANSFER", "CHEQUE", "ONLINE_PROVIDER");
    record Filter(LocalDate from, LocalDate to, String currencyCode, String paymentMethod,
            String query, int page, int pageSize) {
        Filter {
            if (from == null || to == null || from.getYear() < 1 || to.getYear() > 9999
                    || to.isBefore(from) || ChronoUnit.DAYS.between(from,to) > 365
                    || currencyCode == null || !currencyCode.matches("[A-Z]{3}")
                    || (paymentMethod != null && !METHODS.contains(paymentMethod))
                    || page < 0 || page > 10000 || pageSize < 1 || pageSize > 100
                    || (query != null && (query.length() > 100 || query.codePoints().anyMatch(Character::isISOControl)))) {
                throw new ApiException(HttpStatus.BAD_REQUEST,"COLLECTION_REGISTER_FILTER_INVALID",
                        "Choose a valid posting-date range of at most 366 days, currency, tender and page.");
            }
            query = query == null || query.isBlank() ? null : query.strip();
        }
        String searchPattern() {
            return query == null ? null : "%"+query.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
        }
    }

    // Rows, whole-filter totals and tenders share ONE PostgreSQL statement snapshot.
    // Original collections survive later reversals; the reversal belongs to its ledger date.
    private static final String SQL = """
            WITH movements AS (
              SELECT payment.id AS movement_id,'COLLECTION'::text AS kind,payment.id AS payment_id,
                payment.received_on AS posting_on,payment.created_at AS recorded_at,payment.amount,NULL::uuid AS refund_request_id
              FROM fee_payments payment
              WHERE payment.organisation_id=:organisationId AND payment.school_id=:schoolId
                AND payment.received_on BETWEEN :fromDate AND :toDate
              UNION ALL
              SELECT reversal.id,'REVERSAL',payment.id,reversal.effective_on,payment.reversed_at,payment.amount,NULL::uuid
              FROM fee_payments payment JOIN fee_ledger_entries reversal ON reversal.id=payment.reversal_ledger_entry_id
                AND reversal.organisation_id=payment.organisation_id AND reversal.school_id=payment.school_id
                AND reversal.learner_fee_account_id=payment.learner_fee_account_id
                AND reversal.entry_type='PAYMENT_REVERSAL' AND reversal.direction='DEBIT'
              WHERE payment.organisation_id=:organisationId AND payment.school_id=:schoolId
                AND reversal.effective_on BETWEEN :fromDate AND :toDate
              UNION ALL
              SELECT completion.id,'REFUND',payment.id,completion.posted_on,completion.posted_at,completion.amount,completion.refund_request_id
              FROM fee_gateway_refund_postings completion JOIN fee_payments payment ON payment.id=completion.fee_payment_id
                AND payment.organisation_id=completion.organisation_id AND payment.school_id=completion.school_id
                AND payment.learner_fee_account_id=completion.learner_fee_account_id
                AND payment.payment_method='ONLINE_PROVIDER'
              WHERE completion.organisation_id=:organisationId AND completion.school_id=:schoolId
                AND completion.posted_on BETWEEN :fromDate AND :toDate
            ), filtered AS MATERIALIZED (
              SELECT movement.*,payment.learner_fee_account_id AS account_id,account.learner_id,
                learner.legal_name AS learner_name,enrolment.admission_number,enrolment.grade_code,
                session.name AS session_name,payment.receipt_number,payment.payment_method,
                payment.status AS receipt_status,
                COALESCE(manual.original_received_on,capture.original_business_date,payment.received_on) AS original_received_on,
                COALESCE(manual.actual_received_at,capture.paid_at) AS actual_received_at,
                capture.paid_at_exact AS actual_received_at_exact,
                COALESCE(manual.received_time_precision,CASE WHEN capture.paid_at IS NOT NULL THEN 'INSTANT' ELSE 'DATE_ONLY' END) AS received_time_precision,
                COALESCE(manual.school_time_zone,attempt.school_time_zone) AS received_time_zone,
                success.processed_on AS refund_processed_on,success.processed_at AS refund_processed_at,
                success.processed_at_exact AS refund_processed_at_exact,success.school_time_zone AS refund_time_zone
              FROM movements movement JOIN fee_payments payment ON payment.id=movement.payment_id
                AND payment.organisation_id=:organisationId AND payment.school_id=:schoolId
              JOIN learner_fee_accounts account ON account.id=payment.learner_fee_account_id
                AND account.organisation_id=payment.organisation_id AND account.school_id=payment.school_id
              JOIN learners learner ON learner.id=account.learner_id
                AND learner.organisation_id=account.organisation_id AND learner.school_id=account.school_id
              JOIN student_enrolments enrolment ON enrolment.id=account.enrolment_id
                AND enrolment.organisation_id=account.organisation_id AND enrolment.school_id=account.school_id
              JOIN academic_sessions session ON session.id=account.academic_session_id
                AND session.organisation_id=account.organisation_id AND session.school_id=account.school_id
              LEFT JOIN fee_manual_collection_evidence manual ON manual.fee_payment_id=payment.id
                AND manual.organisation_id=payment.organisation_id AND manual.school_id=payment.school_id
              LEFT JOIN fee_gateway_captures capture ON capture.id=payment.gateway_capture_id
                AND capture.organisation_id=payment.organisation_id AND capture.school_id=payment.school_id
              LEFT JOIN fee_gateway_attempts attempt ON attempt.id=capture.attempt_id
                AND attempt.organisation_id=payment.organisation_id AND attempt.school_id=payment.school_id
              LEFT JOIN fee_gateway_refund_successes success ON success.refund_request_id=movement.refund_request_id
                AND success.organisation_id=payment.organisation_id AND success.school_id=payment.school_id
              WHERE account.currency_code=:currencyCode
                AND (CAST(:method AS text) IS NULL OR payment.payment_method=:method)
                AND (CAST(:search AS text) IS NULL OR learner.legal_name ILIKE :search ESCAPE '\\'
                  OR enrolment.admission_number ILIKE :search ESCAPE '\\'
                  OR payment.receipt_number ILIKE :search ESCAPE '\\')
            ), summary AS (
              SELECT count(*) AS total,
                count(*) FILTER(WHERE kind='COLLECTION') AS collection_count,
                COALESCE(sum(amount) FILTER(WHERE kind='COLLECTION'),0) AS gross_collections,
                count(*) FILTER(WHERE kind='REVERSAL') AS reversal_count,
                COALESCE(sum(amount) FILTER(WHERE kind='REVERSAL'),0) AS reversal_amount,
                count(*) FILTER(WHERE kind='REFUND') AS refund_count,
                COALESCE(sum(amount) FILTER(WHERE kind='REFUND'),0) AS refund_amount
              FROM filtered
            ), tender_rows AS (
              SELECT payment_method,
                count(*) FILTER(WHERE kind='COLLECTION') AS collections,
                COALESCE(sum(amount) FILTER(WHERE kind='COLLECTION'),0) AS gross,
                count(*) FILTER(WHERE kind='REVERSAL') AS reversals,
                COALESCE(sum(amount) FILTER(WHERE kind='REVERSAL'),0) AS reversed,
                count(*) FILTER(WHERE kind='REFUND') AS refunds,
                COALESCE(sum(amount) FILTER(WHERE kind='REFUND'),0) AS refunded
              FROM filtered GROUP BY payment_method
            ), tenders AS (
              SELECT COALESCE(jsonb_agg(jsonb_build_object('method',payment_method,'collections',collections,
                'gross',gross,'reversals',reversals,'reversed',reversed,'refunds',refunds,'refunded',refunded) ORDER BY payment_method),'[]'::jsonb) AS tender_totals
              FROM tender_rows
            )
            SELECT summary.*,tenders.tender_totals,page.* FROM summary CROSS JOIN tenders
            LEFT JOIN LATERAL(SELECT * FROM filtered
              ORDER BY posting_on DESC,recorded_at DESC,kind,movement_id
              LIMIT :limit OFFSET :offset) page ON true
            ORDER BY page.posting_on DESC,page.recorded_at DESC,page.kind,page.movement_id
            """;

    static CollectionRegisterResponse read(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper,
            FinanceService.Scope scope, Filter filter) {
        var snapshot = readSnapshot(jdbc,mapper,scope,filter,filter.pageSize(),(long)filter.page()*filter.pageSize(),false);
        return new CollectionRegisterResponse(scope.schoolId(),filter.currencyCode(),filter.from(),filter.to(),
                filter.paymentMethod(),filter.query(),filter.page(),filter.pageSize(),snapshot.total(),
                (long)filter.page()*filter.pageSize()+snapshot.items().size()<snapshot.total(),snapshot.summary(),snapshot.tenderTotals(),snapshot.items());
    }

    static Snapshot export(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper,
            FinanceService.Scope scope, Filter filter) {
        return readSnapshot(jdbc,mapper,scope,filter,EXPORT_LIMIT,0,true);
    }

    /** Same dated movement/tender snapshot as the register; no display/export truncation. */
    static Snapshot dayClose(NamedParameterJdbcTemplate jdbc,ObjectMapper mapper,FinanceService.Scope scope,LocalDate date) {
        return readSnapshot(jdbc,mapper,scope,new Filter(date,date,scope.schoolCurrency(),null,null,0,100),Integer.MAX_VALUE,0,false);
    }

    private static Snapshot readSnapshot(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper,
            FinanceService.Scope scope, Filter filter, int limit, long offset, boolean fullRange) {
        var parameters = scope.params().addValue("fromDate",filter.from()).addValue("toDate",filter.to())
                .addValue("currencyCode",filter.currencyCode()).addValue("method",filter.paymentMethod())
                .addValue("search",filter.searchPattern()).addValue("limit",limit).addValue("offset",offset);
        return jdbc.query(SQL,parameters,(ResultSetExtractor<Snapshot>) result -> {
            if (!result.next()) throw new IllegalStateException("Collection aggregate is unavailable");
            long total=result.getLong("total");
            requireExportBound(total,fullRange);
            var summary=summary(result.getLong("collection_count"),result.getBigDecimal("gross_collections"),
                    result.getLong("reversal_count"),result.getBigDecimal("reversal_amount"),result.getLong("refund_count"),result.getBigDecimal("refund_amount"));
            var tenderTotals=new ArrayList<CollectionRegisterResponse.Tender>();
            try {
                var root=mapper.readTree(result.getString("tender_totals"));
                if (!root.isArray() || root.size()>METHODS.size()) throw new IllegalStateException("Invalid collection aggregate");
                var seen=new java.util.HashSet<String>();
                for (var item:root) {
                    String method=item.path("method").asText();
                    if (!METHODS.contains(method) || !seen.add(method)
                            || !item.path("collections").isIntegralNumber() || !item.path("gross").isNumber()
                            || !item.path("reversals").isIntegralNumber() || !item.path("reversed").isNumber()
                            || !item.path("refunds").isIntegralNumber() || !item.path("refunded").isNumber())
                        throw new IllegalStateException("Invalid collection aggregate");
                    tenderTotals.add(new CollectionRegisterResponse.Tender(method,summary(item.path("collections").longValue(),
                            item.path("gross").decimalValue(),item.path("reversals").longValue(),item.path("reversed").decimalValue(),
                            item.path("refunds").longValue(),item.path("refunded").decimalValue())));
                }
                for (String method:METHODS) if (!seen.contains(method)) tenderTotals.add(new CollectionRegisterResponse.Tender(method,
                        summary(0,BigDecimal.ZERO,0,BigDecimal.ZERO,0,BigDecimal.ZERO)));
            } catch (JacksonException invalid) { throw new IllegalStateException("Collection aggregate is unavailable"); }
            var items=new ArrayList<CollectionRegisterResponse.Movement>();
            do { if (result.getObject("movement_id")!=null) items.add(movement(result)); } while(result.next());
            tenderTotals.sort(java.util.Comparator.comparingInt(item->METHODS.indexOf(item.paymentMethod())));
            if (fullRange && items.size()!=total) throw new IllegalStateException("Incomplete collection export");
            return new Snapshot(total,summary,List.copyOf(tenderTotals),List.copyOf(items));
        });
    }

    static void requireExportBound(long total, boolean fullRange) {
        if (fullRange && total>EXPORT_LIMIT) throw new ApiException(HttpStatus.BAD_REQUEST,
                "COLLECTION_REGISTER_EXPORT_TOO_LARGE","Narrow the collection range to at most 10,000 movements before downloading or printing.");
    }

    static CollectionRegisterResponse.Summary summary(long collections, BigDecimal gross, long reversals, BigDecimal reversed,
            long refunds, BigDecimal refunded) {
        return new CollectionRegisterResponse.Summary(collections,gross.setScale(2),reversals,reversed.setScale(2),
                gross.subtract(reversed).subtract(refunded).setScale(2),refunds,refunded.setScale(2));
    }
    private static CollectionRegisterResponse.Movement movement(ResultSet result) throws SQLException {
        return new CollectionRegisterResponse.Movement(result.getObject("movement_id",UUID.class),result.getString("kind"),
                result.getObject("payment_id",UUID.class),result.getObject("account_id",UUID.class),result.getObject("learner_id",UUID.class),
                result.getString("learner_name"),result.getString("admission_number"),result.getString("grade_code"),result.getString("session_name"),
                result.getString("receipt_number"),result.getString("payment_method"),result.getString("receipt_status"),result.getBigDecimal("amount"),
                result.getObject("posting_on",LocalDate.class),result.getTimestamp("recorded_at").toInstant(),
                new FinanceSnapshot.ReceiptTiming(result.getObject("original_received_on",LocalDate.class),
                        GatewayExactInstant.readNullable(result,"actual_received_at_exact","actual_received_at"),
                        result.getString("received_time_precision"),result.getString("received_time_zone")),
                "REFUND".equals(result.getString("kind")) ? new CollectionRegisterResponse.RefundTiming(
                        result.getObject("refund_processed_on",LocalDate.class),
                        GatewayExactInstant.readNullable(result,"refund_processed_at_exact","refund_processed_at"),
                        result.getString("refund_time_zone")) : null);
    }
}
