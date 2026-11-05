package com.educore.api.accounting;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.tenancy.TenantRequestContext;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Accounting-owned human read projection, not financial-period or posting admission. */
final class GatewaySettlementBankEvidenceAccounts {
    private final NamedParameterJdbcTemplate jdbc;
    GatewaySettlementBankEvidenceAccounts(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}
    List<AccountingModuleBridge.SettlementBankAccount> read(AuthenticatedUser user,UUID schoolId){
        var context=TenantRequestContext.current().orElse(null);
        if(user==null||user.userId()==null||user.activeOrganisationId()==null||user.membershipIds().isEmpty()||schoolId==null||context==null
                ||!user.userId().equals(context.userId())||!user.activeOrganisationId().equals(context.organisationId())
                ||!TransactionSynchronizationManager.isActualTransactionActive())throw denied();
        var p=new MapSqlParameterSource().addValue("organisationId",user.activeOrganisationId()).addValue("schoolId",schoolId)
                .addValue("userId",user.userId()).addValue("memberships",user.membershipIds());
        if(!Boolean.TRUE.equals(jdbc.queryForObject("""
                /* settlement-bank-account-live-reader */
                SELECT current_app_organisation_id()=:organisationId AND current_app_user_id()=:userId
                  AND EXISTS(SELECT 1 FROM memberships member JOIN user_accounts actor ON actor.id=member.user_id AND actor.status='ACTIVE'
                    JOIN schools school ON school.id=member.school_id AND school.organisation_id=member.organisation_id AND school.status='ACTIVE'
                    JOIN organisations organisation ON organisation.id=member.organisation_id AND organisation.status='ACTIVE'
                    WHERE member.user_id=:userId AND member.organisation_id=:organisationId AND member.school_id=:schoolId
                      AND member.id IN(:memberships) AND member.status='ACTIVE' AND member.valid_from<=clock_timestamp()
                      AND (member.valid_until IS NULL OR member.valid_until>clock_timestamp())
                      AND EXISTS(SELECT 1 FROM membership_roles assigned JOIN roles role ON role.id=assigned.role_id
                        JOIN role_capabilities capability ON capability.role_id=role.id WHERE assigned.membership_id=member.id
                          AND (role.organisation_id IS NULL OR role.organisation_id=member.organisation_id) AND capability.capability_code='accounting.read'))
                """,p,Boolean.class)))throw denied();
        var accounts=jdbc.query("""
                /* settlement-bank-account-options */
                WITH current_book AS MATERIALIZED (
                  SELECT year.currency_code FROM accounting_financial_years year JOIN schools school
                    ON school.id=year.school_id AND school.organisation_id=year.organisation_id
                  WHERE year.organisation_id=:organisationId AND year.school_id=:schoolId AND year.status='OPEN'
                    AND gateway_refund_school_date(to_char(clock_timestamp() AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS"Z"'),school.time_zone)
                      BETWEEN year.starts_on AND year.ends_on
                )
                SELECT account.id,account.code,account.display_name,book.currency_code FROM accounting_accounts account
                CROSS JOIN current_book book
                WHERE account.organisation_id=:organisationId AND account.school_id=:schoolId
                  AND account.account_type='ASSET' AND account.category='BANK' AND account.status='ACTIVE' AND account.allow_direct_posting
                  AND book.currency_code='INR' AND (SELECT count(*) FROM current_book)=1
                ORDER BY account.code,account.id LIMIT 1001
                """,p,(row,n)->new AccountingModuleBridge.SettlementBankAccount(row.getObject("id",UUID.class),row.getString("code"),row.getString("display_name"),row.getString("currency_code")));
        if(accounts.size()>1000)throw new ApiException(HttpStatus.CONFLICT,"GATEWAY_SETTLEMENT_BANK_ACCOUNT_UNAVAILABLE","The scoped bank-account context requires review.");
        return List.copyOf(accounts);
    }
    private static ApiException denied(){return new ApiException(HttpStatus.FORBIDDEN,"ACCOUNTING_SCOPE_DENIED","Bank evidence requires current school-scoped accounting read authority.");}
}
