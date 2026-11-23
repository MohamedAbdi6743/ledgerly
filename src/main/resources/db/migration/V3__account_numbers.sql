-- Account numbers (100200000001, 100200000002, ...) come from a sequence, like member numbers,
-- so two tellers opening accounts at the same moment can never get the same number.
CREATE SEQUENCE account_number_seq START WITH 1;
