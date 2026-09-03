-- Corrects the IMPORT_STATEMENT_READY copy, which was factually wrong.
--
-- V127 seeded "we finished processing your {{bank}} statement and imported it successfully. You
-- can view your transactions in Fynora now." On the asynchronous path that is not what has
-- happened. ImportJob.Status.COMPLETED means the statement is STAGED and waiting for the user to
-- review it -- ImportJobWorker.LAST_STAGE_THIS_WORKER_RUNS is ANALYZING, and its own comment says
-- "everything after staging is still the user's review". No transaction exists until the user
-- opens the review screen and confirms. The old wording sent someone to look at transactions that
-- were not there yet.
--
-- The held-for-review feature's governing constraint is that the message stays true, so this is a
-- correctness fix, not a wording preference.
--
-- The dash below is a real em dash (U+2014), not the "--" this codebase uses for one in comments
-- and Javadoc. That convention is for source we read; this is a string a customer reads, and V127
-- shipped "Good news -- we finished" into it, which renders as two stray hyphens in an inbox.

-- UPDATE in place rather than the retire-and-insert dance idx_notification_templates_active exists
-- to support. That convention protects ATTRIBUTION: a retired row keeps past renders explainable
-- by the copy they actually used. There are no past renders to explain here -- V127 seeded these
-- rows and nothing ever requested this NotificationType, which had no caller anywhere in the
-- codebase until the held-import worker added the first one in this same branch. Deactivating a
-- row that never rendered anything would leave a permanent dead row asserting a history that does
-- not exist.
UPDATE notification_templates
   SET title_template = 'Your {{bank}} statement is ready',
       body_template  = 'Good news — we finished the additional checks on your {{bank}} '
                        'statement. It''s ready for you to review and import in Fynora.'
 WHERE type = 'IMPORT_STATEMENT_READY'
   AND channel = 'EMAIL'
   AND active = true;

-- Push stays terse, per the channel's own column comment, and gains the verb the email now
-- carries: "ready to review" rather than a bare "imported", for the same accuracy reason.
UPDATE notification_templates
   SET title_template = 'Statement ready',
       body_template  = 'Your {{bank}} statement is ready to review.'
 WHERE type = 'IMPORT_STATEMENT_READY'
   AND channel = 'PUSH'
   AND active = true;
