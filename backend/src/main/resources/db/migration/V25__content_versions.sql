-- R7 workstream B (D169): the content each loader last loaded. One row per loader: the SHA-256 of its files and code
-- version, as 64 hex digits, the code version itself, the rows the data gave, and when it loaded. A loader runs again
-- at startup only when that hash changes, or when a loader it depends on ran.
CREATE TABLE IF NOT EXISTS content_versions (
    loader VARCHAR(64) PRIMARY KEY,
    content_hash CHAR(64) NOT NULL,
    loader_version INT NOT NULL,
    row_count INT,
    loaded_at TIMESTAMPTZ NOT NULL
);
