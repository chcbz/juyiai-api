-- Terminal authority decisions: a stale quote must never become valid again by flipping a flag back.
CREATE TRIGGER economy_benchmark_review_terminal BEFORE UPDATE ON economy_task_benchmark_review
FOR EACH ROW SET NEW.withdrawn = OLD.withdrawn OR NEW.withdrawn;
CREATE TRIGGER economy_benchmark_case_terminal BEFORE UPDATE ON economy_task_benchmark_case
FOR EACH ROW SET NEW.retired = OLD.retired OR NEW.retired;
