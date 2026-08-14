-- App metadata table: setup state, schema version markers, singleton flags.
CREATE TABLE app_meta (
    meta_key   VARCHAR(64) PRIMARY KEY,
    meta_value TEXT         NOT NULL
);

INSERT INTO app_meta (meta_key, meta_value) VALUES ('schema_version', '1');
