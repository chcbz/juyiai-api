-- Acquire the parent before inserting a stop, even under READ COMMITTED. No absent-row lock assumption.
CREATE TRIGGER economy_benchmark_stop_lock BEFORE INSERT ON economy_task_benchmark_use_stop
FOR EACH ROW SET NEW.generation=(SELECT g.generation FROM economy_task_benchmark_use_grant g
 WHERE g.tenant_id=NEW.tenant_id AND g.client_id=NEW.client_id AND g.owner_jiacn=NEW.owner_jiacn
 AND g.group_sha=NEW.group_sha AND g.generation=NEW.generation AND g.sample_id=NEW.sample_id FOR UPDATE);
