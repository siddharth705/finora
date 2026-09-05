-- Seeds copy for NotificationType.IMPORT_STATEMENT_HELD -- see that enum value's own comment.
--
-- Until now, a user whose statement was held for review (parser gap or trust review) was told
-- nothing at the moment it happened; ImportJobWorker's own comment said so explicitly ("A held
-- import is not finished, so it announces nothing"), and the only email that went out was the
-- internal admin alert (HeldItemAdminAlertService, V990-era work). This closes that gap.
--
-- Wording reuses the frontend's own already-approved held-state copy verbatim
-- (frontend/src/lib/importJob.ts's detail()) rather than authoring new customer-facing text --
-- the same two deliberate rules that copy already follows apply here: no ETA (triage is manual
-- and volume-dependent), and no suggestion the statement's authenticity is in question.
INSERT INTO notification_templates (id, type, channel, title_template, body_template) VALUES
    (gen_random_uuid(), 'IMPORT_STATEMENT_HELD', 'EMAIL',
     'We''re checking your statement',
     'We need to run some additional checks on your statement before we can complete the '
     'import. We''ll notify you once it''s ready -- no action needed from you right now.'),
    (gen_random_uuid(), 'IMPORT_STATEMENT_HELD', 'PUSH',
     'Statement under review',
     'We''re running some additional checks on your statement. No action needed.');
