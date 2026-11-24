-- Explicit benchmark-only maintenance migration. One resumable verified inbox transfer per enrolled case.
CREATE TABLE IF NOT EXISTS economy_task_benchmark_closure (
  sample_id CHAR(64) NOT NULL, terminal_fact_id CHAR(64) NOT NULL, after_revision BIGINT NOT NULL, attached_count BIGINT NOT NULL,
  completed BOOLEAN NOT NULL, PRIMARY KEY(sample_id),
  CONSTRAINT fk_benchmark_closure_sample FOREIGN KEY(sample_id) REFERENCES economy_task_cost_sample(sample_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
