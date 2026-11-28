package cn.jia.economy.mapper;

import cn.jia.economy.task.TaskBillingService.Task;
import java.util.List;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

/** Only Economy projections; never read Agent task or runtime-state tables. */
public interface ConfirmedSampleMapper {
    @Select("""
        <script>SELECT client_id,sample_id FROM economy_task_cost_sample WHERE tenant_id=#{tenant}
        <if test="client != null"> AND client_id=#{client}</if> AND projection_pending=1 AND sample_id &gt; #{after}
        ORDER BY sample_id LIMIT #{limit}</script>
        """) List<cn.jia.economy.usage.ConfirmedSampleProjection.Pending> pending(@Param("tenant") String tenant,@Param("client") String client,@Param("after") String after,@Param("limit") int limit);
    record Target(String owner,String taskId) {}
    @Select("SELECT owner_jiacn,task_id FROM economy_task_cost_sample WHERE sample_id=#{id} AND tenant_id=#{tenant} AND client_id=#{client} AND projection_pending=1")
    Target target(@Param("tenant") String tenant,@Param("client") String client,@Param("id") String id);
    // Accepted confirmed rows only. A new task may use another agent's same-config history under this owner,
    // but cannot train on its own record, previous READY revisions, benchmark rows or another namespace.
    String ELIGIBLE_HISTORY="""
        s.tenant_id=#{t.scope.tenantId} AND s.client_id=#{t.scope.clientId} AND s.owner_jiacn=#{t.ownerJiacn}
        AND s.history_group_sha256=#{group} AND s.sample_state='READY'
        AND s.lifecycle_state='ACCEPTED' AND s.evidence_revision=s.projection_revision
        AND s.benchmark_case_id IS NULL AND s.sample_definition_version='TASK_LIFECYCLE_V1'
        """;
    String HISTORY=ELIGIBLE_HISTORY+" AND s.task_id<>#{t.taskId} ";
    record CalibrationRow(String quoteId,String agentId,long amountWen,long featuresAt,String requirementSha,String inputSha,String featureSha,String features,String execution,String operations) { }
    // One explicitly selected original task/revision. No fallback to newer evidence, another owner, or an unapproved benchmark.
    @Select("""
        SELECT q.quote_id,q.snapshot_json->>'$.binding.agentId',q.amount_wen,q.created_at,s.initial_requirement_sha256,s.initial_input_snapshot_sha256,s.initial_feature_sha256,
          JSON_EXTRACT(q.snapshot_json,'$.preparation.features'),JSON_EXTRACT(q.snapshot_json,'$.preparation.group'),r.operation_refs
        FROM economy_task_cost_sample s JOIN economy_task_cost_sample_revision r ON r.sample_id=s.sample_id
          AND r.sample_revision=s.projection_revision AND r.sample_state='READY'
        JOIN economy_task_charge ch ON ch.tenant_id=s.tenant_id AND ch.client_id=s.client_id AND ch.owner_jiacn=s.owner_jiacn
          AND ch.task_id=s.task_id AND ch.quote_id=s.quote_id
        JOIN economy_task_quote_resolved q ON q.tenant_id=ch.tenant_id AND q.client_id=ch.client_id AND q.owner_jiacn=ch.owner_jiacn
          AND q.task_id=ch.task_id AND q.quote_id=ch.quote_id AND q.payer_user_id=ch.payer_user_id WHERE
        """+ELIGIBLE_HISTORY+" AND s.task_id=#{t.taskId} AND s.sample_id=#{id} AND s.projection_revision=#{revision}")
    CalibrationRow selectedForCalibration(@Param("t") Task original,@Param("group") String group,@Param("id") String sampleId,@Param("revision") long revision);
    // Separate statement AFTER the evidence read: an observation upper bound, not an invented historical completion time.
    @Select("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)+1") long observedByMillis();
    record HistoryRow(String id,long revision,String taskId,String featureSha,String featuresJson){}
    @Select("""
        SELECT s.sample_id,s.projection_revision,s.task_id,s.initial_feature_sha256,JSON_EXTRACT(q.snapshot_json,'$.preparation.features')
        FROM economy_task_cost_sample s LEFT JOIN economy_task_quote_resolved q ON q.tenant_id=s.tenant_id AND q.client_id=s.client_id
        AND q.owner_jiacn=s.owner_jiacn AND q.task_id=s.task_id AND q.quote_id=s.quote_id WHERE
        """+HISTORY+" AND s.sample_id>#{after} ORDER BY s.sample_id LIMIT #{limit}")
    List<HistoryRow> history(@Param("t") Task query,@Param("group") String group,@Param("after") String after,@Param("limit") int limit);
    record FeatureSource(String quoteId,String rawSha,String resolvedSha,String recommendation,String preparation,String featureSha,String features){
        @Override public String toString(){return "FeatureSource[private]";}
    }
    // One consistent source read before the short projection transaction; no raw vector from clients or another task.
    @Select("""
        SELECT q.quote_id,SHA2(q.snapshot_json,256),SHA2(r.snapshot_json,256),q.snapshot_json->>'$.binding.recommendationId',
          IF(JSON_TYPE(JSON_EXTRACT(q.snapshot_json,'$.preparation'))='STRING',q.snapshot_json->>'$.preparation',NULL),
          s.initial_feature_sha256,JSON_EXTRACT(r.snapshot_json,'$.preparation.features')
        FROM economy_task_cost_sample s JOIN economy_task_quote q ON q.tenant_id=s.tenant_id AND q.client_id=s.client_id
          AND q.owner_jiacn=s.owner_jiacn AND q.task_id=s.task_id AND q.quote_id=s.quote_id
        JOIN economy_task_quote_resolved r ON r.id=q.id WHERE s.tenant_id=#{t.scope.tenantId} AND s.client_id=#{t.scope.clientId}
          AND s.owner_jiacn=#{t.ownerJiacn} AND s.task_id=#{t.taskId} AND s.sample_id=#{id} AND s.evidence_revision=#{rev}
          AND s.lifecycle_state='ACCEPTED' AND s.sample_definition_version='TASK_LIFECYCLE_V1' AND s.benchmark_case_id IS NULL
        """) FeatureSource featureSource(@Param("t") Task task,@Param("id") String id,@Param("rev") long revision);
    // These mutations run only after project() won the scoped revision CAS and holds the sample row lock.
    @org.apache.ibatis.annotations.Delete("DELETE FROM economy_history_feature_projection WHERE sample_id=#{id}")
    void forgetFeature(@Param("id") String id);
    @Insert("""
        INSERT INTO economy_history_feature_projection(sample_id,sample_revision,quote_id,envelope_sha,resolved_sha,
          recommendation_id,preparation_sha,feature_meta,vector_bytes) VALUES(#{id},#{rev},#{source.quoteId},#{source.rawSha},
          #{source.resolvedSha},#{source.recommendation},#{source.preparation},#{meta},#{vector})
        """) void saveFeature(@Param("id") String id,@Param("rev") long revision,@Param("source") FeatureSource source,
                    @Param("meta") String meta,@Param("vector") byte[] vector);
    record CompactRow(String id,long revision,String taskId,String featureSha,String metadata,byte[] vector,Integer sourceValid,String inlineSha){
        @Override public String toString(){return "CompactRow[private]";}
    }
    // This small, fixed binary vector is consumed as bytes. Do not materialize a JDBC Blob
    // just to copy it to a byte array; keep this mapping local to the derived read model.
    @ConstructorArgs({
        @Arg(column="sample_id",javaType=String.class), @Arg(column="projection_revision",javaType=long.class),
        @Arg(column="task_id",javaType=String.class), @Arg(column="initial_feature_sha256",javaType=String.class),
        @Arg(column="feature_meta",javaType=String.class),
        @Arg(column="vector_bytes",javaType=byte[].class,typeHandler=org.apache.ibatis.type.ByteArrayTypeHandler.class),
        @Arg(column="source_valid",javaType=Integer.class), @Arg(column="inline_sha",javaType=String.class)
    })
    // Start at eligible samples, not projection rows. A missing or stale projection must not shorten a keyset page.
    @Select("""
        SELECT s.sample_id,s.projection_revision,s.task_id,s.initial_feature_sha256,x.feature_meta,x.vector_bytes,
          SHA2(q.snapshot_json,256)=x.envelope_sha AND ((x.preparation_sha IS NULL AND x.resolved_sha=x.envelope_sha
          AND CASE WHEN FIND_IN_SET(BINARY x.envelope_sha,#{knownInline})>0 THEN TRUE
                   ELSE JSON_CONTAINS_PATH(q.snapshot_json,'one','$.preparation.features') END)
          OR (x.preparation_sha IS NOT NULL AND q.snapshot_json->>'$.preparation'=x.preparation_sha
          AND q.snapshot_json->>'$.binding.recommendationId'=x.recommendation_id AND p.preparation_sha=SHA2(p.preparation_json,256))) AS source_valid,
          IF(x.preparation_sha IS NULL,x.envelope_sha,NULL) AS inline_sha
        FROM economy_task_cost_sample s LEFT JOIN economy_history_feature_projection x ON x.sample_id=s.sample_id
          AND x.sample_revision=s.projection_revision AND x.quote_id=s.quote_id
        LEFT JOIN economy_task_quote q ON q.tenant_id=s.tenant_id AND q.client_id=s.client_id AND q.owner_jiacn=s.owner_jiacn
          AND q.task_id=s.task_id AND q.quote_id=x.quote_id
        LEFT JOIN economy_task_quote_preparation p ON p.tenant_id=q.tenant_id AND p.client_id=q.client_id AND p.owner_jiacn=q.owner_jiacn
          AND p.task_id=q.task_id AND p.payer_user_id=q.payer_user_id AND p.recommendation_id=x.recommendation_id
          AND p.preparation_sha=x.preparation_sha WHERE
        """+HISTORY+" AND s.sample_id>#{after} ORDER BY s.sample_id LIMIT #{limit}")
    List<CompactRow> compactHistory(@Param("t") Task query,@Param("group") String group,@Param("after") String after,@Param("limit") int limit,@Param("knownInline") String knownInline);
    @Select("""
        SELECT r.operation_refs FROM economy_task_cost_sample s JOIN economy_task_cost_sample_revision r
        ON r.sample_id=s.sample_id AND r.sample_revision=s.projection_revision AND r.sample_state='READY' WHERE
        """+HISTORY+" AND s.sample_id=#{id} AND s.projection_revision=#{revision}")
    String historyQuantities(@Param("t") Task query,@Param("group") String group,@Param("id") String id,@Param("revision") long revision);
    @Select("SELECT s.projection_revision FROM economy_task_cost_sample s WHERE "+HISTORY+" AND s.sample_id=#{id} AND s.projection_revision=#{revision} FOR SHARE")
    Long lockHistory(@Param("t") Task query,@Param("group") String group,@Param("id") String id,@Param("revision") long revision);
    String GRANT_SCOPE="g.tenant_id=#{t.scope.tenantId} AND g.client_id=#{t.scope.clientId} AND g.owner_jiacn=#{t.ownerJiacn} AND g.group_sha=#{p.group} AND g.generation=#{p.generation} AND g.receipt_sha=#{p.receiptSha256} ";
    String BENCHMARK_USE="""
        FROM economy_task_benchmark_use_grant g JOIN economy_task_benchmark_use_projection p
          USING(tenant_id,client_id,owner_jiacn,group_sha,generation,sample_id)
        JOIN economy_task_cost_sample s ON s.sample_id=g.sample_id AND s.tenant_id=g.tenant_id AND s.client_id=g.client_id AND s.owner_jiacn=g.owner_jiacn
          AND s.history_group_sha256=g.group_sha AND s.evidence_revision=p.evidence_revision AND s.lifecycle_state='ACCEPTED' AND s.quote_id IS NULL
        JOIN economy_task_benchmark_case b ON b.tenant_id=s.tenant_id AND b.client_id=s.client_id AND b.owner_jiacn=s.owner_jiacn AND b.task_id=s.task_id
          AND b.manifest_sha=s.sample_id AND b.benchmark_case_id=s.benchmark_case_id AND NOT b.retired
        JOIN economy_task_benchmark_review v ON v.sample_id=s.sample_id AND v.review_sha=p.review_sha AND NOT v.withdrawn
        JOIN economy_task_benchmark_closure x ON x.sample_id=s.sample_id AND x.terminal_fact_id=p.terminal_fact AND x.completed
        LEFT JOIN economy_task_benchmark_use_stop z ON z.tenant_id=g.tenant_id AND z.client_id=g.client_id AND z.owner_jiacn=g.owner_jiacn
          AND z.group_sha=g.group_sha AND z.generation=g.generation AND z.sample_id=g.sample_id
        WHERE z.sample_id IS NULL AND s.task_id<>#{t.taskId} AND NOT EXISTS(SELECT 1 FROM economy_task_charge ch WHERE ch.tenant_id=s.tenant_id
          AND ch.client_id=s.client_id AND ch.owner_jiacn=s.owner_jiacn AND ch.task_id=s.task_id) AND
        """+GRANT_SCOPE;
    @Select("SELECT g.sample_id,p.evidence_revision,s.task_id,s.initial_feature_sha256,JSON_EXTRACT(b.manifest_json,'$.features') "+BENCHMARK_USE+" AND g.sample_id>#{after} ORDER BY g.sample_id LIMIT #{limit}")
    List<HistoryRow> benchmarkPage(@Param("t") Task t,@Param("p") cn.jia.economy.task.TaskCalibrationCatalog.Pin pin,@Param("after") String after,@Param("limit") int limit);
    String EXACT=BENCHMARK_USE+" AND g.sample_id=#{id} AND p.evidence_revision=#{revision} ";
    @Select("SELECT p.operations "+EXACT) String benchmarkQuantities(@Param("t") Task t,@Param("p") cn.jia.economy.task.TaskCalibrationCatalog.Pin pin,@Param("id") String id,@Param("revision") long revision);
    @Select("SELECT g.generation FROM economy_task_benchmark_use_grant g WHERE "+GRANT_SCOPE+" AND g.sample_id=#{id} FOR SHARE")
    Long lockGrant(@Param("t") Task t,@Param("p") cn.jia.economy.task.TaskCalibrationCatalog.Pin pin,@Param("id") String id);
    @Select("SELECT p.evidence_revision "+EXACT+" FOR SHARE") Long lockBenchmark(@Param("t") Task t,@Param("p") cn.jia.economy.task.TaskCalibrationCatalog.Pin pin,@Param("id") String id,@Param("revision") long revision);
    @Select("""
        SELECT sample_id FROM economy_task_cost_sample WHERE tenant_id=#{t.scope.tenantId} AND client_id=#{t.scope.clientId}
          AND owner_jiacn=#{t.ownerJiacn} AND task_id=#{t.taskId} AND quote_id=#{quote} AND initial_feature_sha256=#{feature}
          AND history_group_sha256=#{grp} AND benchmark_case_id IS NULL AND sample_definition_version='TASK_LIFECYCLE_V1'
        """) String outcomeSample(@Param("t") Task task,@Param("quote") String quote,@Param("grp") String group,@Param("feature") String feature);
    record Header(String lifecycle,long evidenceRevision,String fact,String outcome,Boolean verified,Long memberCount,
                  String agent,String groupVersion,String executionConfig,String localRoute,String referenceRoute,String tools,String policy) {}
    @Select("""
        SELECT s.lifecycle_state,s.evidence_revision,r.fact_id,r.outcome,r.membership_verified,r.member_count,
          q.snapshot_json->>'$.binding.agentId',CASE WHEN q.snapshot_json->>'$.schema'='SCOPED_FIXED_TASK_QUOTE_V1' THEN q.snapshot_json->>'$.executionBinding.dispatchGroupVersion' ELSE q.snapshot_json->>'$.preparation.group.dispatchGroupVersion' END,
          CASE WHEN q.snapshot_json->>'$.schema'='SCOPED_FIXED_TASK_QUOTE_V1' THEN q.snapshot_json->>'$.executionBinding.executionConfigSha256' ELSE q.snapshot_json->>'$.preparation.group.executionConfigSha256' END,CASE WHEN q.snapshot_json->>'$.schema'='SCOPED_FIXED_TASK_QUOTE_V1' THEN q.snapshot_json->>'$.executionBinding.localRouteSha256' ELSE q.snapshot_json->>'$.preparation.group.localRouteSha256' END,
          CASE WHEN q.snapshot_json->>'$.schema'='SCOPED_FIXED_TASK_QUOTE_V1' THEN q.snapshot_json->>'$.executionBinding.routeVersion' ELSE q.snapshot_json->>'$.preparation.group.routeVersion' END,CASE WHEN q.snapshot_json->>'$.schema'='SCOPED_FIXED_TASK_QUOTE_V1' THEN q.snapshot_json->>'$.executionBinding.toolConfigSha256' ELSE q.snapshot_json->>'$.preparation.group.toolConfigSha256' END,
          CASE WHEN q.snapshot_json->>'$.schema'='SCOPED_FIXED_TASK_QUOTE_V1' THEN q.snapshot_json->>'$.executionBinding.capturePolicyVersion' ELSE q.snapshot_json->>'$.preparation.group.capturePolicyVersion' END
        FROM economy_task_cost_sample s JOIN economy_task_charge ch ON ch.tenant_id=s.tenant_id AND ch.client_id=s.client_id
          AND ch.owner_jiacn=s.owner_jiacn AND ch.task_id=s.task_id AND ch.quote_id=s.quote_id
        JOIN economy_task_quote_resolved q ON q.tenant_id=ch.tenant_id AND q.client_id=ch.client_id AND q.owner_jiacn=ch.owner_jiacn
          AND q.task_id=ch.task_id AND q.quote_id=ch.quote_id AND q.payer_user_id=ch.payer_user_id
        LEFT JOIN economy_task_membership_receipt r ON r.tenant_id=s.tenant_id AND r.client_id=s.client_id AND r.owner_jiacn=s.owner_jiacn AND r.task_id=s.task_id
        WHERE s.sample_id=#{id} AND s.tenant_id=#{t.scope.tenantId} AND s.client_id=#{t.scope.clientId} AND s.owner_jiacn=#{t.ownerJiacn}
          AND s.task_id=#{t.taskId} AND s.sample_definition_version='TASK_LIFECYCLE_V1' AND s.benchmark_case_id IS NULL
        """) Header confirmed(@Param("t") Task task,@Param("id") String sampleId);
    // Benchmark-only source of the header. Below it, all members and responses use the same actual-use checks as confirmed tasks.
    @Select("""
        SELECT s.lifecycle_state,s.evidence_revision,r.fact_id,r.outcome,r.membership_verified,r.member_count,
          b.manifest_json->>'$.agentId',b.manifest_json->>'$.execution.dispatchGroupVersion',b.manifest_json->>'$.execution.executionConfigSha256',
          b.manifest_json->>'$.execution.localRouteSha256',b.manifest_json->>'$.execution.routeVersion',b.manifest_json->>'$.execution.toolConfigSha256',b.manifest_json->>'$.execution.capturePolicyVersion'
        FROM economy_task_cost_sample s JOIN economy_task_benchmark_case b ON b.tenant_id=s.tenant_id AND b.client_id=s.client_id
          AND b.owner_jiacn=s.owner_jiacn AND b.task_id=s.task_id AND b.benchmark_case_id=s.benchmark_case_id
          AND b.manifest_sha=s.sample_id AND SHA2(b.manifest_json,256)=b.manifest_sha AND NOT b.retired
        JOIN economy_task_benchmark_closure x ON x.sample_id=s.sample_id AND x.completed AND x.terminal_fact_id=#{fact}
        JOIN economy_task_membership_receipt r ON r.tenant_id=s.tenant_id AND r.client_id=s.client_id AND r.owner_jiacn=s.owner_jiacn AND r.task_id=s.task_id
          AND r.fact_id=x.terminal_fact_id AND r.membership_verified AND r.outcome='ACCEPTED' AND r.member_count=x.attached_count AND r.membership_revision=x.after_revision
        JOIN economy_task_benchmark_review v ON v.sample_id=s.sample_id AND v.review_sha=#{review} AND SHA2(v.review_json,256)=v.review_sha AND NOT v.withdrawn
          AND v.review_json->>'$.schema'='benchmark-review-v1' AND v.review_json->>'$.decision'='ACCEPTED'
          AND v.review_json->>'$.caseId'=b.benchmark_case_id AND v.review_json->>'$.manifestSha'=b.manifest_sha
          AND v.review_json->>'$.terminalFactId'=r.fact_id AND v.review_json->>'$.membershipSha'=r.set_digest
          AND JSON_TYPE(JSON_EXTRACT(v.review_json,'$.reviewerRef'))='STRING' AND LENGTH(TRIM(v.review_json->>'$.reviewerRef'))>0
          AND JSON_TYPE(JSON_EXTRACT(v.review_json,'$.deliverableEvidenceRef'))='STRING' AND LENGTH(TRIM(v.review_json->>'$.deliverableEvidenceRef'))>0
        WHERE s.tenant_id=#{t.scope.tenantId} AND s.client_id=#{t.scope.clientId} AND s.owner_jiacn=#{t.ownerJiacn} AND s.task_id=#{t.taskId}
          AND s.sample_id=#{sha} AND s.benchmark_case_id=#{id} AND s.quote_id IS NULL AND s.sample_definition_version='TASK_LIFECYCLE_V1' AND s.lifecycle_state='ACCEPTED'
          AND NOT EXISTS(SELECT 1 FROM economy_task_charge c WHERE c.tenant_id=s.tenant_id AND c.client_id=s.client_id AND c.owner_jiacn=s.owner_jiacn AND c.task_id=s.task_id)
        """) Header reviewedBenchmark(@Param("t") Task task,@Param("id") String id,@Param("sha") String sha,@Param("fact") String fact,@Param("review") String review);
    @Update("""
        UPDATE economy_task_cost_sample SET projection_revision=#{rev},sample_state=#{state}
        WHERE sample_id=#{id} AND tenant_id=#{t.scope.tenantId} AND client_id=#{t.scope.clientId} AND owner_jiacn=#{t.ownerJiacn} AND task_id=#{t.taskId}
          AND evidence_revision=#{rev} AND (projection_revision IS NULL OR projection_revision<>#{rev})
        """) int project(@Param("t") Task task,@Param("id") String sample,@Param("rev") long revision,@Param("state") String state);
    @Insert("""
        INSERT INTO economy_task_cost_sample_revision(sample_id,sample_revision,terminal_fact_id,sample_state,reason_code,member_count,operation_refs)
        VALUES(#{id},#{rev},#{fact},#{state},#{reason},#{count},#{refs})
        """) void append(@Param("id") String sample,@Param("rev") long revision,@Param("fact") String fact,@Param("state") String state,
                          @Param("reason") String reason,@Param("count") long count,@Param("refs") String refs);
    record Originals(String manifest,String review,long featuresAt){}
    // Called only after the scoped review, closure and measurement checks in the very same snapshot.
    @Select("""
        SELECT b.manifest_json,v.review_json,CAST(UNIX_TIMESTAMP(b.recorded_at)*1000 AS UNSIGNED)
        FROM economy_task_benchmark_case b JOIN economy_task_benchmark_review v ON v.sample_id=b.manifest_sha
        WHERE b.tenant_id=#{t.scope.tenantId} AND b.client_id=#{t.scope.clientId} AND b.owner_jiacn=#{t.ownerJiacn} AND b.task_id=#{t.taskId}
          AND b.benchmark_case_id=#{id} AND b.manifest_sha=#{sha} AND v.review_sha=#{review} AND NOT b.retired AND NOT v.withdrawn
        """) Originals benchmarkOriginals(@Param("t") Task task,@Param("id") String id,@Param("sha") String sha,@Param("review") String review);
    @Select("""
        SELECT b.manifest_json,v.review_json,CAST(UNIX_TIMESTAMP(b.recorded_at)*1000 AS UNSIGNED)
        FROM economy_task_benchmark_case b JOIN economy_task_cost_sample s ON s.tenant_id=b.tenant_id AND s.client_id=b.client_id
          AND s.owner_jiacn=b.owner_jiacn AND s.task_id=b.task_id AND s.benchmark_case_id=b.benchmark_case_id AND s.sample_id=#{sample}
        JOIN economy_task_benchmark_review v ON v.sample_id=s.sample_id
        WHERE b.tenant_id=#{t.scope.tenantId} AND b.client_id=#{t.scope.clientId} AND b.owner_jiacn=#{t.ownerJiacn} AND b.task_id=#{t.taskId}
          AND b.benchmark_case_id=#{id} AND b.manifest_sha=#{sha} AND v.review_sha=#{review} AND NOT b.retired AND NOT v.withdrawn
        """) Originals scopedBenchmarkOriginals(@Param("t") Task task,@Param("sample") String sample,@Param("id") String id,@Param("sha") String sha,@Param("review") String review);
    record Member(long revision,String attached,String coverage,String groupVersion,String executionConfig,String route,String tools,String policy) {}
    @Select("""
        SELECT r.membership_revision,m.execution_id,c.coverage_state,g.group_version,g.execution_config_sha256,g.route_sha256,g.tool_config_sha256,g.capture_policy_version
        FROM economy_task_membership_received_run r
        LEFT JOIN economy_task_cost_sample_member m ON m.sample_id=#{id} AND m.execution_id=r.execution_id AND m.run_id=r.run_id AND m.capture_id <=> r.capture_id
        LEFT JOIN economy_usage_capture c ON c.tenant_id=r.tenant_id AND c.client_id=r.client_id AND c.owner_jiacn=#{t.ownerJiacn} AND c.task_id=#{t.taskId}
          AND c.agent_id=#{agent} AND c.execution_id=r.execution_id AND c.run_id=r.run_id AND c.capture_id=m.capture_id
          AND c.input_revision=c.projection_revision AND c.conflict_reason IS NULL
        LEFT JOIN economy_usage_capture_dispatch_group g ON g.capture_pk=c.id AND g.projection_revision=c.projection_revision
        WHERE r.tenant_id=#{t.scope.tenantId} AND r.client_id=#{t.scope.clientId} AND r.fact_id=#{fact} AND r.membership_revision>#{after}
        ORDER BY r.membership_revision LIMIT 64
        """) List<Member> members(@Param("t") Task task,@Param("id") String id,@Param("fact") String fact,@Param("agent") String agent,@Param("after") long after);
    // LEFT JOIN preserves a member with no associated responses. Never treat missing as a free run.
    @Select("""
        SELECT r.membership_revision,c.capture_id,c.input_revision,e.record_id,e.source_revision,e.evidence_sha256,e.evidence_json,c.execution_id,c.run_id,e.kind,COALESCE(g.capture_policy_version='NATIVE_PINNED_IMAGE_V1',FALSE)
        FROM economy_task_membership_received_run r
        JOIN economy_usage_capture c ON c.tenant_id=r.tenant_id AND c.client_id=r.client_id AND c.owner_jiacn=#{t.ownerJiacn} AND c.task_id=#{t.taskId}
          AND c.agent_id=#{agent} AND c.execution_id=r.execution_id AND c.run_id=r.run_id AND c.capture_id=r.capture_id
          AND c.projection_revision=c.input_revision AND c.coverage_state='COMPLETE' AND c.conflict_reason IS NULL
        LEFT JOIN economy_usage_capture_dispatch_group g ON g.capture_pk=c.id AND g.projection_revision=c.projection_revision
        LEFT JOIN economy_usage_capture_record e ON e.capture_pk=c.id AND e.is_associated_head=1
          AND (e.kind='RESPONSE' OR (g.capture_policy_version='NATIVE_PINNED_IMAGE_V1' AND e.kind='CAPTURE_FINAL'))
        WHERE r.tenant_id=#{t.scope.tenantId} AND r.client_id=#{t.scope.clientId} AND r.fact_id=#{fact}
          AND (r.membership_revision>#{rev} OR (r.membership_revision=#{rev} AND e.record_id>#{record}))
        ORDER BY r.membership_revision,e.record_id LIMIT 64
        """) List<cn.jia.economy.usage.ConfirmedSampleEvidence.Response> responses(@Param("t") Task task,@Param("fact") String fact,
            @Param("agent") String agent,@Param("rev") long after,@Param("record") String afterRecord);
    record ReviewedScope(long evidenceRevision,String fact,long memberCount,String agent,String manifest,
            String scopeSha,String shapeSha,String groupSha,String policySha,String requirementSha,String inputSha,String featureSha,String quoteJson) {}
    @Select("""
        SELECT s.evidence_revision,r.fact_id,r.member_count,b.manifest_json->>'$.agentId' AS agent,b.manifest_json AS manifest,
          s.scope_sha256 AS scope_sha,s.scope_shape_sha256 AS shape_sha,s.scoped_group_sha256 AS group_sha,
          s.scoped_policy_sha256 AS policy_sha,s.initial_requirement_sha256 AS requirement_sha,s.initial_input_snapshot_sha256 AS input_sha,
          s.initial_feature_sha256 AS feature_sha,q.snapshot_json AS quote_json
        FROM economy_task_cost_sample s JOIN economy_task_benchmark_case b ON b.tenant_id=s.tenant_id AND b.client_id=s.client_id
          AND b.owner_jiacn=s.owner_jiacn AND b.task_id=s.task_id AND b.benchmark_case_id=s.benchmark_case_id
          AND b.manifest_sha=#{sha} AND SHA2(b.manifest_json,256)=b.manifest_sha AND NOT b.retired
        JOIN economy_task_quote_resolved q ON q.quote_id=s.quote_id AND q.tenant_id=s.tenant_id AND q.client_id=s.client_id
          AND q.owner_jiacn=s.owner_jiacn AND q.task_id=s.task_id
          AND q.snapshot_json->>'$.schema'='SCOPED_FIXED_TASK_QUOTE_V1'
          AND CAST(q.snapshot_json->>'$.scopePin.scopeSha256' AS BINARY)=CAST(s.scope_sha256 AS BINARY)
          AND q.snapshot_json->>'$.binding.agentId'=b.manifest_json->>'$.agentId'
        JOIN economy_task_charge ch ON ch.tenant_id=q.tenant_id AND ch.client_id=q.client_id AND ch.owner_jiacn=q.owner_jiacn
          AND ch.task_id=q.task_id AND ch.quote_id=q.quote_id AND ch.payer_user_id=q.payer_user_id AND ch.state IN ('HELD','CAPTURED')
        JOIN economy_task_benchmark_closure x ON x.sample_id=s.sample_id AND x.completed AND x.terminal_fact_id=#{fact}
        JOIN economy_task_membership_receipt r ON r.tenant_id=s.tenant_id AND r.client_id=s.client_id AND r.owner_jiacn=s.owner_jiacn AND r.task_id=s.task_id
          AND r.fact_id=x.terminal_fact_id AND r.membership_verified AND r.outcome='ACCEPTED' AND r.member_count=x.attached_count AND r.membership_revision=x.after_revision
        JOIN economy_task_benchmark_review v ON v.sample_id=s.sample_id AND v.review_sha=#{review} AND SHA2(v.review_json,256)=v.review_sha AND NOT v.withdrawn
          AND v.review_json->>'$.schema'='benchmark-review-v1' AND v.review_json->>'$.decision'='ACCEPTED'
          AND v.review_json->>'$.caseId'=b.benchmark_case_id AND v.review_json->>'$.manifestSha'=b.manifest_sha
          AND v.review_json->>'$.terminalFactId'=r.fact_id AND v.review_json->>'$.membershipSha'=r.set_digest
          AND JSON_TYPE(JSON_EXTRACT(v.review_json,'$.reviewerRef'))='STRING' AND LENGTH(TRIM(v.review_json->>'$.reviewerRef'))>0
          AND JSON_TYPE(JSON_EXTRACT(v.review_json,'$.deliverableEvidenceRef'))='STRING' AND LENGTH(TRIM(v.review_json->>'$.deliverableEvidenceRef'))>0
        WHERE s.tenant_id=#{t.scope.tenantId} AND s.client_id=#{t.scope.clientId} AND s.owner_jiacn=#{t.ownerJiacn} AND s.task_id=#{t.taskId}
          AND s.sample_id=#{sample} AND s.benchmark_case_id=#{id} AND s.sample_definition_version='TASK_LIFECYCLE_V1' AND s.lifecycle_state='ACCEPTED'
          AND b.manifest_json->>'$.schema'='SCOPED_BENCHMARK_V1'
        """) ReviewedScope reviewedScopedBenchmark(@Param("t") Task task,@Param("sample") String sample,
            @Param("id") String id,@Param("sha") String sha,@Param("fact") String fact,@Param("review") String review);
    record ScopedMember(long revision,String attached,Long capturePk,String captureId,String execution,String run,
            Long captureRevision,String coverage) {}
    @Select("""
        SELECT r.membership_revision,m.execution_id AS attached,c.id AS capture_pk,r.capture_id,r.execution_id AS execution,
          r.run_id AS run,c.input_revision AS capture_revision,c.coverage_state AS coverage
        FROM economy_task_membership_received_run r
        LEFT JOIN economy_task_cost_sample_member m ON m.sample_id=#{id} AND m.execution_id=r.execution_id
          AND m.run_id=r.run_id AND m.capture_id <=> r.capture_id
        LEFT JOIN economy_usage_capture c ON c.tenant_id=r.tenant_id AND c.client_id=r.client_id AND c.owner_jiacn=#{t.ownerJiacn}
          AND c.task_id=#{t.taskId} AND c.agent_id=#{agent} AND c.execution_id=r.execution_id AND c.run_id=r.run_id
          AND c.capture_id=m.capture_id AND c.input_revision=c.projection_revision AND c.conflict_reason IS NULL
        WHERE r.tenant_id=#{t.scope.tenantId} AND r.client_id=#{t.scope.clientId} AND r.fact_id=#{fact} AND r.membership_revision>#{after}
        ORDER BY r.membership_revision LIMIT 64
        """) List<ScopedMember> scopedMembers(@Param("t") Task task,@Param("id") String sample,@Param("fact") String fact,
            @Param("agent") String agent,@Param("after") long after);
    // Same associated-head/revision-prefix semantics as the original projection, but inside the sample MVCC transaction.
    @Select("""
        SELECT g.record_id,g.revisions,h.source_revision,h.evidence_sha256,h.evidence_json FROM (
          SELECT r.capture_pk,r.record_id,COUNT(*) AS revisions FROM economy_usage_capture_record r
          JOIN economy_usage_capture c ON c.id=r.capture_pk AND c.tenant_id=#{t.scope.tenantId} AND c.client_id=#{t.scope.clientId}
            AND c.owner_jiacn=#{t.ownerJiacn} AND c.task_id=#{t.taskId}
          WHERE r.capture_pk=#{pk} AND r.record_id>#{after} GROUP BY r.capture_pk,r.record_id ORDER BY r.record_id LIMIT 64
        ) g LEFT JOIN economy_usage_capture_record h ON h.capture_pk=g.capture_pk AND h.record_id=g.record_id AND h.is_associated_head=1
        ORDER BY g.record_id
        """) List<cn.jia.economy.usage.UsageCaptureProjection.Row> scopedRecords(@Param("t") Task task,@Param("pk") long capturePk,@Param("after") String after);

}
