-- tickets.business_impact becomes a constrained value instead of 50 characters of free text.
--
-- WHY
-- The column was an open string with a placeholder suggesting "Department-level". At the time of
-- this migration the table held four tickets, all meaning the same thing, spelled two ways:
--
--   Organization-Level   2
--   Organization Level   2
--
-- Nothing is wrong with either phrasing. The problem is that impact can then never be counted,
-- filtered or grouped - which is the only reason to record it. Two spellings in four rows is what a
-- free-text taxonomy does at any scale; it just takes longer to notice in a bigger table.
--
-- THE VALUES, AND WHY THEY DESCRIBE SCOPE
-- SINGLE_USER, TEAM, DEPARTMENT, MULTIPLE_DEPARTMENTS, COMPANY_WIDE - ascending, from one person to
-- everybody. They answer "how many does it affect", because `priority` already answers "how soon".
-- An impact scale of LOW/MEDIUM/HIGH would have been a second priority column under another name.
-- See enums/BusinessImpact.java.
--
-- IN PLACE, WITH NO COLUMN JUGGLING
-- The column is already VARCHAR(50) and the enum is mapped @Enumerated(EnumType.STRING), so the
-- longest constant (MULTIPLE_DEPARTMENTS, 20 characters) fits what is already there. Nothing is rewritten
-- except the rows that hold a value, so this is an UPDATE and a constraint rather than a table
-- rebuild - and it stays nullable, because impact was optional and still is.

--------------------------------------------------------------------------------------------------
-- 1. Refuse to run rather than silently discard what somebody wrote.
--------------------------------------------------------------------------------------------------
-- The mapping below is deliberately generous, but it cannot be exhaustive: it is matching free text.
-- Anything it does not recognise is a sentence a person typed about the impact of their problem, and
-- dropping it to fit a new column would be destroying data to make a schema change look tidy.
--
-- So this migration stops instead, naming the values it could not place. Flyway runs each migration
-- in a transaction and PostgreSQL's DDL is transactional, so a failure here leaves the table exactly
-- as it was - the deploy fails, nothing is damaged, and somebody decides what those tickets meant.
--
-- Verified against the database this was written for: zero unmapped rows, so it passes there.
DO
$$
    DECLARE
        unmapped TEXT;
    BEGIN
        SELECT string_agg(DISTINCT quote_literal(business_impact), ', ' ORDER BY quote_literal(business_impact))
        INTO unmapped
        FROM tickets
        WHERE business_impact IS NOT NULL
          AND business_impact <> ''
          AND lower(business_impact) NOT LIKE '%organi%'
          AND lower(business_impact) NOT LIKE '%compan%'
          AND lower(business_impact) NOT LIKE '%enterprise%'
          AND lower(business_impact) NOT LIKE '%depart%'
          AND lower(business_impact) NOT LIKE '%division%'
          AND lower(business_impact) NOT LIKE '%function%'
          AND lower(business_impact) NOT LIKE '%team%'
          AND lower(business_impact) NOT LIKE '%shift%'
          AND lower(business_impact) NOT LIKE '%group%'
          AND lower(business_impact) NOT LIKE '%user%'
          AND lower(business_impact) NOT LIKE '%individual%'
          AND lower(business_impact) NOT LIKE '%single%'
          AND lower(business_impact) NOT LIKE '%person%'
          AND business_impact NOT IN
              ('SINGLE_USER', 'TEAM', 'DEPARTMENT', 'MULTIPLE_DEPARTMENTS', 'COMPANY_WIDE');

        IF unmapped IS NOT NULL THEN
            RAISE EXCEPTION
                'V23 will not convert tickets.business_impact: these values map to no BusinessImpact constant: %. '
                    'Decide what each one meant and either add it to the mapping in this migration or set those rows '
                    'by hand, then migrate again. Nothing has been changed.', unmapped;
        END IF;
    END
$$;

--------------------------------------------------------------------------------------------------
-- 2. Map what is there.
--------------------------------------------------------------------------------------------------
-- Widest match wins, which is the safe direction for a scale like this. "Organization-wide, affects
-- the payroll department" is company-wide whichever noun it happens to mention second, and reading
-- it as DEPARTMENT would understate it - whereas overstating is visible to whoever picks the ticket
-- up. The branches are therefore in descending order of scope, and the first one that matches wins.
--
-- MULTIPLE_DEPARTMENTS needs two signals, a count word and a department word, because neither means
-- it alone: "multiple users" is not it and "department" on its own is the narrower constant. It sits
-- above DEPARTMENT so that "several departments" cannot be read as one.
--
-- Blank strings become NULL: an empty string was never an answer.
UPDATE tickets
SET business_impact =
        CASE
            WHEN business_impact IN
                 ('SINGLE_USER', 'TEAM', 'DEPARTMENT', 'MULTIPLE_DEPARTMENTS', 'COMPANY_WIDE')
                THEN business_impact
            WHEN business_impact = '' THEN NULL
            WHEN lower(business_impact) LIKE '%organi%'
                OR lower(business_impact) LIKE '%compan%'
                OR lower(business_impact) LIKE '%enterprise%' THEN 'COMPANY_WIDE'
            WHEN (lower(business_impact) LIKE '%depart%'
                OR lower(business_impact) LIKE '%division%'
                OR lower(business_impact) LIKE '%function%')
                AND (lower(business_impact) LIKE '%multi%'
                OR lower(business_impact) LIKE '%several%'
                OR lower(business_impact) LIKE '%cross%'
                OR lower(business_impact) LIKE '%various%'
                OR lower(business_impact) LIKE '%more than one%') THEN 'MULTIPLE_DEPARTMENTS'
            WHEN lower(business_impact) LIKE '%depart%'
                OR lower(business_impact) LIKE '%division%'
                OR lower(business_impact) LIKE '%function%' THEN 'DEPARTMENT'
            WHEN lower(business_impact) LIKE '%team%'
                OR lower(business_impact) LIKE '%shift%'
                OR lower(business_impact) LIKE '%group%' THEN 'TEAM'
            ELSE 'SINGLE_USER'
        END
WHERE business_impact IS NOT NULL;

--------------------------------------------------------------------------------------------------
-- 3. Stop the next free-text value getting in.
--------------------------------------------------------------------------------------------------
-- The application maps this with @Enumerated(EnumType.STRING), which refuses an unknown constant on
-- the way out of the database but writes whatever the enum contains on the way in. That is enough
-- for traffic through the API; it is not enough for a SQL console, a data fix or a future import,
-- and those are exactly how a column grows a twenty-fifth distinct spelling.
--
-- NOT VALID is not used: every existing row has just been mapped above, so the constraint can be
-- validated immediately and the table is small enough for the brief lock that costs.
--
-- A sixth constant means a new migration that drops and re-adds this constraint. That is the cost of
-- having it, and it is the right trade: the alternative is a column whose set of legal values is
-- enforced only by whichever application happens to be writing.
ALTER TABLE tickets
    ADD CONSTRAINT ck_tickets_business_impact
        CHECK (business_impact IN
               ('SINGLE_USER', 'TEAM', 'DEPARTMENT', 'MULTIPLE_DEPARTMENTS', 'COMPANY_WIDE'));

COMMENT ON COLUMN tickets.business_impact IS
    'Scope of the problem: SINGLE_USER, TEAM, DEPARTMENT, MULTIPLE_DEPARTMENTS or COMPANY_WIDE. Optional. Urgency lives in priority.';
