-- Synthetic browser fixtures ONLY. Run after normal Flyway migration and local
-- DevelopmentBootstrap. No real records or credentials belong in this file.
-- The test accounts reuse the isolated bootstrap account's password hash;
-- provide that test-only password at runtime, never in source or reports.
\set ON_ERROR_STOP on
BEGIN;
DO $$
DECLARE
    org uuid := '10000000-0000-4000-8000-000000000001';
    school uuid := '10000000-0000-4000-8000-000000000002';
    campus uuid := '10000000-0000-4000-8000-000000000003';
    admin_user uuid;
    admin_member uuid;
    test_hash text;
    teacher_user uuid := gen_random_uuid();
    teacher_member uuid := gen_random_uuid();
    parent_user uuid := gen_random_uuid();
    parent_member uuid := gen_random_uuid();
    student_user uuid := gen_random_uuid();
    student_member uuid := gen_random_uuid();
    staff_user uuid := gen_random_uuid();
    staff_member uuid := gen_random_uuid();
    session_id uuid := gen_random_uuid();
    section_id uuid := gen_random_uuid();
    subject_id uuid := gen_random_uuid();
    offering_id uuid := gen_random_uuid();
    guardian_id uuid := gen_random_uuid();
    learner_id uuid;
    application_id uuid;
    enrolment_id uuid;
    consent_id uuid := gen_random_uuid();
    account_id uuid := gen_random_uuid();
    designation_id uuid := gen_random_uuid();
    profile_id uuid := gen_random_uuid();
    i integer;
BEGIN
    IF current_database() <> 'educore_browser_acceptance_20260927' THEN
        RAISE EXCEPTION 'Refusing: this fixture is restricted to its named isolated acceptance database';
    END IF;
    IF (SELECT count(*) FROM user_accounts) <> 1
       OR EXISTS (SELECT 1 FROM learners)
       OR EXISTS (SELECT 1 FROM academic_sessions) THEN
        RAISE EXCEPTION 'Refusing: fixture requires a fresh migrated database with only its bootstrap administrator';
    END IF;
    SELECT id,password_hash INTO STRICT admin_user,test_hash
    FROM user_accounts WHERE login_identifier_normalized='synthetic-admin@example.test';
    SELECT id INTO STRICT admin_member FROM memberships
    WHERE user_id=admin_user AND school_id=school AND campus_id=campus;
    UPDATE user_accounts SET display_name='Synthetic Acceptance Administrator' WHERE id=admin_user;

    INSERT INTO user_accounts(id,login_identifier_normalized,email_normalized,display_name,password_hash,status,created_at,updated_at)
    VALUES
      (teacher_user,'synthetic-teacher@example.test','synthetic-teacher@example.test','Synthetic Teacher',test_hash,'ACTIVE',now(),now()),
      (parent_user,'synthetic-parent@example.test','synthetic-parent@example.test','Synthetic Parent',test_hash,'ACTIVE',now(),now()),
      (student_user,'synthetic-student',NULL,'Synthetic Student',test_hash,'ACTIVE',now(),now()),
      (staff_user,'synthetic-staff@example.test','synthetic-staff@example.test','Synthetic Office Staff',test_hash,'ACTIVE',now(),now());
    INSERT INTO memberships(id,user_id,organisation_id,school_id,campus_id,status,valid_from,created_at,updated_at)
    VALUES
      (teacher_member,teacher_user,org,school,campus,'ACTIVE',now(),now(),now()),
      (parent_member,parent_user,org,school,campus,'ACTIVE',now(),now(),now()),
      (student_member,student_user,org,school,campus,'ACTIVE',now(),now(),now()),
      (staff_member,staff_user,org,school,campus,'ACTIVE',now(),now(),now());
    INSERT INTO membership_roles(membership_id,role_id) VALUES
      (teacher_member,'00000000-0000-4000-8000-000000000003'),
      (parent_member,'00000000-0000-4000-8000-000000000006'),
      (student_member,'00000000-0000-4000-8000-000000000010'),
      (staff_member,'00000000-0000-4000-8000-000000000008');

    INSERT INTO academic_sessions(id,organisation_id,school_id,name,starts_on,ends_on,status,created_at,updated_at)
    VALUES(session_id,org,school,'Synthetic acceptance 2026-2027','2026-04-01','2027-03-31','ACTIVE',now(),now());
    INSERT INTO class_sections(id,organisation_id,school_id,campus_id,academic_session_id,grade_code,code,display_name,status,created_at,updated_at)
    VALUES(section_id,org,school,campus,session_id,'VI','SYN-A','Synthetic Class VI A','ACTIVE',now(),now());
    INSERT INTO school_subjects(id,organisation_id,school_id,code,display_name,short_name,subject_category,status,created_at,updated_at)
    VALUES(subject_id,org,school,'SYN-MATH','Synthetic Mathematics','Maths','CORE','ACTIVE',now(),now());
    INSERT INTO section_subject_offerings(id,organisation_id,school_id,class_section_id,subject_id,weekly_target_periods,status,created_at,updated_at)
    VALUES(offering_id,org,school,section_id,subject_id,5,'ACTIVE',now(),now());
    INSERT INTO teaching_allocations(id,organisation_id,school_id,section_subject_offering_id,teacher_membership_id,allocation_role,starts_on,status,created_at,updated_at)
    VALUES(gen_random_uuid(),org,school,offering_id,teacher_member,'PRIMARY','2026-04-01','ACTIVE',now(),now());
    INSERT INTO staff_designations(id,organisation_id,school_id,code,display_name,staff_category,cbse_staff_category,teaching_role,leadership_role,display_order,status,created_by_membership_id,created_at,updated_at)
    VALUES(designation_id,org,school,'SYN_TEACHER','Synthetic Teacher','TEACHING','OTHER_TEACHING',true,false,0,'ACTIVE',admin_member,now(),now());
    INSERT INTO staff_profiles(id,organisation_id,school_id,employee_code,legal_name,display_name,primary_language,work_email_normalized,user_account_id,joined_on,status,created_by_membership_id,created_at,updated_at)
    VALUES(profile_id,org,school,'SYN-T-001','Synthetic Teacher','Synthetic Teacher','EN','synthetic-teacher@example.test',teacher_user,'2026-04-01','ACTIVE',admin_member,now(),now());
    INSERT INTO staff_assignments(id,organisation_id,school_id,staff_profile_id,staff_designation_id,campus_id,employment_type,employer_kind,primary_assignment,starts_on,status,created_by_membership_id,created_at,updated_at)
    VALUES(gen_random_uuid(),org,school,profile_id,designation_id,campus,'FULL_TIME','SCHOOL',true,'2026-04-01','ACTIVE',admin_member,now(),now());
    INSERT INTO guardians(id,organisation_id,school_id,display_name,email_normalized,status,user_account_id,created_at,updated_at)
    VALUES(guardian_id,org,school,'Synthetic Parent','synthetic-parent@example.test','ACTIVE',parent_user,now(),now());

    FOR i IN 1..3 LOOP
      application_id:=gen_random_uuid(); learner_id:=gen_random_uuid(); enrolment_id:=gen_random_uuid();
      INSERT INTO admission_applications(id,organisation_id,school_id,idempotency_key,request_fingerprint,intake_session_label,target_grade_code,applicant_legal_name,birth_date,gender_code,status,created_by_membership_id,created_at,updated_at)
      VALUES(application_id,org,school,'synthetic-browser-'||i,repeat('1',64),'Synthetic acceptance 2026-2027','VI','Synthetic Learner '||i,'2015-01-01','NOT_STATED','DRAFT',admin_member,now(),now());
      INSERT INTO learners(id,organisation_id,school_id,origin_application_id,legal_name,birth_date,gender_code,status,created_at,updated_at)
      VALUES(learner_id,org,school,application_id,'Synthetic Learner '||i,'2015-01-01','NOT_STATED','ACTIVE',now(),now());
      INSERT INTO student_enrolments(id,organisation_id,school_id,campus_id,academic_session_id,learner_id,origin_application_id,grade_code,admission_number,status,placement_status,starts_on,created_at,updated_at)
      VALUES(enrolment_id,org,school,campus,session_id,learner_id,application_id,'VI','SYN-ACCEPT-'||i,'ACTIVE','PLACED','2026-04-01',now(),now());
      INSERT INTO student_section_placements(id,organisation_id,school_id,enrolment_id,learner_id,class_section_id,roll_number,starts_on,status,placed_by_membership_id,placement_reason,created_at,updated_at)
      VALUES(gen_random_uuid(),org,school,enrolment_id,learner_id,section_id,i::text,'2026-04-01','ACTIVE',admin_member,'Synthetic browser acceptance fixture',now(),now());
      IF i<=2 THEN
        INSERT INTO learner_guardian_relationships(learner_id,guardian_id,organisation_id,school_id,relationship_code,primary_contact,pickup_authorized,portal_access_status,created_at,updated_at)
        VALUES(learner_id,guardian_id,org,school,'PARENT_GUARDIAN',true,true,'ACTIVE',now(),now());
      END IF;
      IF i=1 THEN
        INSERT INTO student_portal_consent_requests(id,organisation_id,school_id,learner_id,guardian_id,proposed_login_identifier_normalized,privacy_notice_version,purpose_summary,adult_verification_method,adult_verification_reference,adult_verified_at,adult_verified_by_membership_id,preparation_reason,status,expires_at,created_by_membership_id,created_at,updated_at)
        VALUES(consent_id,org,school,learner_id,guardian_id,'synthetic-student','child-account-v1','Synthetic test-only own-record portal acceptance fixture.','SCHOOL_RECORD','school-record:synthetic-browser-001',now(),admin_member,'Synthetic test-only verification','PENDING',now()+interval '14 days',admin_member,now(),now());
        UPDATE student_portal_consent_requests SET status='APPROVED',guardian_decided_by_user_id=parent_user,guardian_decided_by_membership_id=parent_member,guardian_decided_at=now(),guardian_decision_reason='Synthetic fixture approval, not a real consent',approved_user_account_id=student_user,updated_at=now(),row_version=row_version+1 WHERE id=consent_id;
        INSERT INTO learner_portal_accounts(id,organisation_id,school_id,learner_id,user_account_id,current_consent_request_id,status,activated_at,created_at,updated_at)
        VALUES(account_id,org,school,learner_id,student_user,consent_id,'ACTIVE',now(),now(),now());
        INSERT INTO learner_portal_account_history(id,organisation_id,school_id,learner_portal_account_id,event_type,new_status,consent_request_id,reason,actor_user_id,actor_membership_id,occurred_at)
        VALUES(gen_random_uuid(),org,school,account_id,'ACTIVATED','ACTIVE',consent_id,'Synthetic fixture activation',parent_user,parent_member,now());
      END IF;
    END LOOP;
    -- No marks are seeded: attendance must be recorded through the real UI.
    INSERT INTO attendance_policies(id,organisation_id,school_id,academic_session_id,policy_name,register_mode,enabled_state_codes,state_weights,lock_after_hours,guardian_explanation_days,warning_threshold_percent,status,created_at,updated_at)
    VALUES(gen_random_uuid(),org,school,session_id,'Synthetic acceptance attendance','DAILY',ARRAY['PRESENT','ABSENT','LATE','EXCUSED'],'{"PRESENT":1,"ABSENT":0,"LATE":1,"EXCUSED":0}',0,7,75,'ACTIVE',now(),now());
    INSERT INTO school_calendar_days(id,organisation_id,school_id,campus_id,academic_session_id,calendar_date,day_type,display_name,attendance_required,status,created_at,updated_at)
    VALUES(gen_random_uuid(),org,school,campus,session_id,CURRENT_DATE,'INSTRUCTIONAL','Synthetic test day (not the real school calendar)',true,'ACTIVE',now(),now());
END $$;
COMMIT;
