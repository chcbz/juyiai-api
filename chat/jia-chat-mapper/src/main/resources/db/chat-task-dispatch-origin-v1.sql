-- Apply before enabling the new Chat relay. No backfill from current devices or ACKs.
CREATE TABLE IF NOT EXISTS chat_task_dispatch_origin (
  event_id VARCHAR(64) NOT NULL PRIMARY KEY,
  context_hash VARCHAR(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  installation_id VARCHAR(100) NOT NULL,host_id VARCHAR(100) NOT NULL,
  runtime_instance_id VARCHAR(100) NOT NULL,session_generation BIGINT NOT NULL,
  mixed_origin BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT fk_chat_task_origin_dispatch FOREIGN KEY (event_id) REFERENCES chat_dispatch_outbox(event_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
