-- Immutable owner operation receipts. No raw key, description, output body or pricing data.
CREATE TABLE agent_task_native_selection_receipt (
 operation_sha CHAR(64) NOT NULL PRIMARY KEY, input_sha CHAR(64) NOT NULL,
 task_version BIGINT NOT NULL, selection_version CHAR(64) NOT NULL, selected_choice VARCHAR(32) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
