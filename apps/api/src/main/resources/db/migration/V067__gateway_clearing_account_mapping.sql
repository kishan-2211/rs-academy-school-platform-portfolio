-- Online captures are clearing assets until a verified settlement reaches the bank.
-- Existing approved BANK mappings are preserved; manual bank tenders stay BANK-only.
-- Do not alter posted journals, published configurations or V030/V066 history.
CREATE OR REPLACE FUNCTION guard_fee_accounting_tender_mapping()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    parent_status VARCHAR(20);
    deposit_row accounting_accounts%ROWTYPE;
BEGIN
    SELECT status INTO parent_status FROM fee_accounting_configurations
      WHERE id=COALESCE(NEW.configuration_id,OLD.configuration_id)
        AND organisation_id=COALESCE(NEW.organisation_id,OLD.organisation_id)
        AND school_id=COALESCE(NEW.school_id,OLD.school_id);
    IF NOT FOUND OR parent_status <> 'DRAFT' THEN
        RAISE EXCEPTION 'Tender accounting mappings are editable only while configuration is draft';
    END IF;
    IF TG_OP <> 'DELETE' THEN
        SELECT * INTO deposit_row FROM accounting_accounts
          WHERE id=NEW.deposit_account_id AND organisation_id=NEW.organisation_id
            AND school_id=NEW.school_id;
        IF NOT FOUND OR deposit_row.account_type <> 'ASSET' OR deposit_row.status <> 'ACTIVE'
           OR NOT deposit_row.allow_direct_posting
           OR (NEW.payment_method='CASH' AND deposit_row.category <> 'CASH')
           OR (NEW.payment_method IN ('BANK_TRANSFER','CHEQUE','SCHOOL_UPI') AND deposit_row.category <> 'BANK')
           OR (NEW.payment_method='ONLINE_PROVIDER' AND deposit_row.category NOT IN ('BANK','OTHER'))
           OR NEW.payment_method NOT IN ('CASH','BANK_TRANSFER','CHEQUE','SCHOOL_UPI','ONLINE_PROVIDER') THEN
            RAISE EXCEPTION 'Tender requires an active posting asset: cash, bank, or online clearing as appropriate';
        END IF;
    END IF;
    RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;
