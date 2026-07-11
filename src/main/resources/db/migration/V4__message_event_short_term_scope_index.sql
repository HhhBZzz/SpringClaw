CREATE INDEX idx_message_event_short_term_scope_id
    ON message_event (channel, session_key, user_id, event_type, deleted, id);
