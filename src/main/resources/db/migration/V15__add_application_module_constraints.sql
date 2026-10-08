-- Integrity constraints for the application/module catalogue.
--
-- WHAT WAS WRONG. `applications` is a catalogue of application-and-module pairs - the development data
-- is HRMS/attendance, HRMS/short term loan, HRMS/long term loan, HRMS/leaves - but nothing said so:
--   * module_name was nullable, while CreateApplicationRequest marks it @NotBlank. The API was
--     stricter than the column, which means the rule existed in one layer only and a direct insert or
--     a future endpoint could bypass it.
--   * there was no uniqueness at all, so the same app_name/module_name pair could be inserted any
--     number of times. Duplicate catalogue rows are not a cosmetic problem here: a ticket references
--     one application_id, so two rows for the same real module silently split that module's tickets
--     into two queues that no report joins back together.
--
-- VERIFIED BEFORE WRITING THIS. Both statements would fail on bad data rather than corrupt it, so the
-- development database was checked first:
--   select app_name, module_name, count(*) from applications group by 1,2 having count(*) > 1;  -> 0 rows
--   select count(*) from applications where module_name is null or btrim(module_name) = '';     -> 0
-- Run both against any other environment before applying this.
--
-- NOT A PERFORMANCE INDEX. V14 measured and rejected an index on applications for sorting: 500 rows
-- occupy 5 pages, so a sequential scan already wins and an index would be write overhead for nothing.
-- That conclusion stands. The index this constraint creates exists to enforce integrity, which a
-- sequential scan cannot do, and the planner is free to ignore it for reads.
--
-- CASE SENSITIVITY. The constraint is exact, so 'Attendance' and 'attendance' would both be allowed.
-- A case-insensitive constraint would need a functional unique index on (lower(app_name),
-- lower(module_name)), which is a stronger claim about what counts as the same module than this
-- codebase makes anywhere else - the service layer normalizes a ticket's module against the
-- catalogue's own spelling instead. If duplicate-by-case ever appears in real data, revisit.

ALTER TABLE applications
    ALTER COLUMN module_name SET NOT NULL;

ALTER TABLE applications
    ADD CONSTRAINT uq_applications_app_name_module_name UNIQUE (app_name, module_name);
