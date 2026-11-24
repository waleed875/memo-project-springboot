-- Isolated tests only; production has these columns from the full migration history.
alter table core.notes
    add column deleted_at timestamp,
    add column deleted_by uuid references auth.users(id);
