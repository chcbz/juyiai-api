-- Explicit maintenance only. No review writer or automatic approval. Original review bytes are private.
CREATE TABLE IF NOT EXISTS economy_task_benchmark_review (
  sample_id CHAR(64) NOT NULL, review_sha CHAR(64) NOT NULL, review_json LONGTEXT NOT NULL,
  withdrawn BOOLEAN NOT NULL DEFAULT FALSE, PRIMARY KEY(sample_id,review_sha),
  CONSTRAINT fk_benchmark_review_closure FOREIGN KEY(sample_id) REFERENCES economy_task_benchmark_closure(sample_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
