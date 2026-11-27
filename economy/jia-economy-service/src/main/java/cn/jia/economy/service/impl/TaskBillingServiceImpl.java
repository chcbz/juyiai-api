package cn.jia.economy.service.impl;

import cn.jia.economy.common.EconomyAccountOwnerType;
import cn.jia.economy.common.EconomyAccountPurpose;
import cn.jia.economy.common.EconomyConstants;
import cn.jia.economy.common.EconomyEscrowType;
import cn.jia.economy.common.EconomyJournalType;
import cn.jia.economy.common.EconomyPrincipalType;
import cn.jia.economy.entity.EconomyAccountEntity;
import cn.jia.economy.entity.EconomyTaskChargeEntity;
import cn.jia.economy.entity.EconomyTaskQuoteEntity;
import cn.jia.economy.mapper.EconomyLedgerMapper;
import cn.jia.economy.mapper.EconomyTaskBillingMapper;
import cn.jia.economy.pricing.ModelTokenPrice;
import cn.jia.economy.pricing.TaskPricingService;
import cn.jia.economy.pricing.TaskWorkload;
import cn.jia.economy.pricing.TaskQuoteCalculator;
import cn.jia.economy.service.EconomyAccountKey;
import cn.jia.economy.service.EconomyEscrowFunding;
import cn.jia.economy.service.EconomyEscrowSettlement;
import cn.jia.economy.service.EconomyPostingCommand;
import cn.jia.economy.service.EconomyPostingLine;
import cn.jia.economy.service.EconomyPostingResult;
import cn.jia.economy.service.EconomyPostingService;
import cn.jia.economy.service.EconomyPrincipal;
import cn.jia.economy.task.TaskBillingException;
import cn.jia.economy.task.TaskBillingService;
import cn.jia.economy.task.ScopedTaskQuoteSnapshot;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Optional;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import static cn.jia.economy.task.TaskBillingException.Reason.*;

/** Fixed task price lifecycle. The caller holds the task root lock in this same transaction.
 * Account locks and journal integrity remain owned by the existing posting service.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
public class TaskBillingServiceImpl implements TaskBillingService {
    private final EconomyTaskBillingMapper mapper;
    private final EconomyLedgerMapper ledger;
    private final EconomyPostingService posting;
    private final ObjectMapper json;
    private final TaskPricingService pricing;

    public TaskBillingServiceImpl(EconomyTaskBillingMapper mapper, EconomyLedgerMapper ledger,
            EconomyPostingService posting, ObjectMapper json, TaskPricingService pricing) {
        this(mapper,ledger,posting,json,pricing,false);
    }

    private final boolean sharedPreparationWrites;
    private final ObservationRegistry observations;
    public TaskBillingServiceImpl(EconomyTaskBillingMapper mapper, EconomyLedgerMapper ledger,
            EconomyPostingService posting,ObjectMapper json,TaskPricingService pricing,boolean sharedPreparationWrites) {
        this(mapper,ledger,posting,json,pricing,sharedPreparationWrites,Optional.empty());
    }
    @org.springframework.beans.factory.annotation.Autowired
    public TaskBillingServiceImpl(EconomyTaskBillingMapper mapper, EconomyLedgerMapper ledger,
            EconomyPostingService posting, ObjectMapper json, TaskPricingService pricing,
            @org.springframework.beans.factory.annotation.Value("${jia.economy.task-quotes.shared-preparation-writes:false}") boolean sharedPreparationWrites, Optional<ObservationRegistry> registry) {
        this.observations=registry.orElse(ObservationRegistry.NOOP);
        this.sharedPreparationWrites=sharedPreparationWrites;
        this.mapper = mapper;
        this.ledger = ledger;
        this.posting = posting;
        this.json = json;
        this.pricing = pricing;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class,
            noRollbackFor = TaskPricingService.Unavailable.class)
    public Quote quote(Task task, Binding binding, String payerUserId, TaskWorkload workload, String reportedModel) {
        Objects.requireNonNull(payerUserId, "payerUserId");
        String bindingHash = bindingHash(binding);
        EconomyTaskQuoteEntity stored = mapper.findQuoteByBinding(task.scope().tenantId(), task.scope().clientId(),
                task.ownerJiacn(), task.taskId(), bindingHash);
        if (stored != null) {
            if (!payerUserId.equals(stored.getPayerUserId())) throw new TaskBillingException(CHARGE_CONFLICT);
            return receipt(stored);
        }
        // Replay above precedes all current price lookup, including a now-removed configuration entry.
        var prepared = pricing.prepare(task.scope(), workload, reportedModel);
        workload = prepared.workload();
        var estimate = prepared.estimate();
        var modelPrice = prepared.modelPrice();
        TaskQuoteCalculator.Quote calculated;
        try { calculated = TaskQuoteCalculator.calculate(estimate, modelPrice, prepared.toolPrices()); }
        catch (ArithmeticException overflow) {
            throw new TaskPricingService.Unavailable(TaskPricingService.Unavailable.Reason.QUOTE_AMOUNT_OVERFLOW);
        }
        // Structured tool keys, not JSON object keys: the immutable snapshot can be read without guessing.
        List<ToolEstimate> tools = estimate.toolUnits().entrySet().stream()
                .map(entry -> new ToolEstimate(entry.getKey(), entry.getValue(), calculated.toolPrices().get(entry.getKey())))
                .toList();
        String snapshot = encode(new QuoteSnapshot(binding,
                new WorkloadSnapshot(workload.kind(), workload.inputTokens(), workload.outputTokens(), workload.deliverables()),
                estimate.ruleId(), estimate.ruleVersion(),
                estimate.tokens(), modelPrice, tools, calculated.estimatedYuan(), calculated.amountWen()));
        return insertQuote("tq_" + UUID.randomUUID(), task, bindingHash, payerUserId, calculated.amountWen(), snapshot);
    }

    private static final String MEASURED_PREFIX = "tq_m1_";
    private static final String MEASURED_SCHEMA = "MEASURED_TASK_QUOTE_V1";
    // Only locally encoded, typed preparation may be embedded as JSON. Never accept raw JSON from the caller.
    private record FrozenPreparation(String featureSha, String groupSha, String json) {}
    private record MeasuredSnapshot(String schema, Binding binding, String featureSha256, String historyGroupSha256,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String initialIntentSha256,
            @com.fasterxml.jackson.annotation.JsonRawValue String preparation) {}

    private FrozenPreparation freeze(MeasuredPreparation p) {
        return new FrozenPreparation(p.features().digest(),
                cn.jia.economy.task.TaskHistoryCatalog.Group.of(p.group(),p.features()).digest(), encode(p));
    }

    private EconomyTaskQuoteEntity measuredRow(Task task, String payer, MeasuredWrite write,
            FrozenPreparation frozen, String preparationJson) {
        var p = write.prepared(); var binding = write.binding();
        if (!binding.requirementSha256().equals(p.features().requirementSha256())
                || !binding.inputSnapshotDigest().equals(p.features().inputSnapshotSha256()))
            throw new TaskBillingException(QUOTE_BINDING_CHANGED);
        var snapshot = new MeasuredSnapshot(MEASURED_SCHEMA, binding, frozen.featureSha(), frozen.groupSha(), write.initialIntentSha256(), preparationJson);
        return quoteRow(MEASURED_PREFIX + UUID.randomUUID(), task, bindingHash(binding), payer, p.estimate().wen(), encode(snapshot));
    }

    @Override public InitialCommitment heldInitialCommitment(Task task,String agentId){
        var row=mapper.heldInitialCommitment(task.scope().tenantId(),task.scope().clientId(),task.ownerJiacn(),task.taskId(),agentId);
        return row==null?null:new InitialCommitment(row.intent(),json.readValue(row.groupJson(),MeasuredGroup.class));
    }

    @Override public Quote storeMeasured(Task task, Binding binding, String payer, MeasuredPreparation p) {
        return observedWrite(()->storeSingleRow(task,binding,payer,p));
    }
    private Quote storeSingleRow(Task task,Binding binding,String payer,MeasuredPreparation p) {
        var frozen = freeze(p);
        var row = measuredRow(task, Objects.requireNonNull(payer), new MeasuredWrite(binding,p), frozen, frozen.json());
        mapper.insertQuote(row);
        return receipt(row);
    }

    // Bound multi-value statement size; a single pre-existing large quote is sent alone, never truncated.
    private static final int CHUNK_ROWS = 32, CHUNK_SNAPSHOT_BYTES = 1024 * 1024;
    @Override public List<StoredQuote> storeMeasuredBatch(Task task, String payer, List<MeasuredWrite> writes) {
        Objects.requireNonNull(payer);
        if(writes.isEmpty()) return List.of();
        return observedWrite(()->storeRows(task,payer,writes));
    }
    // Statements including snapshot serialization; the caller's enclosing transaction can still roll back.
    private <T> T observedWrite(java.util.function.Supplier<T> write) {
        var span=Observation.start("cyf.task.quote.persist",observations);String outcome="error";
        try {var result=write.get();outcome="written";return result;}
        finally {span.lowCardinalityKeyValue("outcome",outcome).stop();}
    }
    private List<StoredQuote> storeRows(Task task,String payer,List<MeasuredWrite> writes) {
        var cache = new java.util.IdentityHashMap<MeasuredPreparation,FrozenPreparation>();
        // Task and payer are fixed by this call. Never group by amount, agent, or feature hash.
        // Exact text equality (including numeric representation) precedes digest addressing in the DB.
        record Content(String batch,String json) {}
        var counts = new java.util.LinkedHashMap<Content,Integer>();
        if(sharedPreparationWrites && writes.size()>1) for(var write:writes){
            var body=cache.computeIfAbsent(write.prepared(),this::freeze).json();
            counts.merge(new Content(write.binding().recommendationId(),body),1,Integer::sum);
        }
        var references = new java.util.HashMap<Content,String>();
        for(var entry:counts.entrySet()) if(entry.getValue()>1){
            var key=entry.getKey();String sha=HexFormat.of().formatHex(digest(key.json().getBytes(StandardCharsets.UTF_8)));
            // Plain insert only: a conflicting retained key cannot adopt someone else's evidence.
            requireTransition(mapper.insertPreparation(task,payer,key.batch(),sha,key.json()));
            references.put(key,encode(sha));
        }
        return persistChunked(writes,write -> {
            var frozen=cache.computeIfAbsent(write.prepared(),this::freeze);
            String payload=references.isEmpty()?frozen.json():references.getOrDefault(new Content(write.binding().recommendationId(),frozen.json()),frozen.json());
            return measuredRow(task,payer,write,frozen,payload);
        });
    }
    private <T> List<StoredQuote> persistChunked(List<T> writes,java.util.function.Function<T,EconomyTaskQuoteEntity> rowFor) {
        var result = new java.util.ArrayList<StoredQuote>(writes.size());
        var chunk = new java.util.ArrayList<EconomyTaskQuoteEntity>(CHUNK_ROWS);
        long bytes = 0;
        for(var write:writes) {
            var row=rowFor.apply(write);
            int size = row.getSnapshotJson().getBytes(StandardCharsets.UTF_8).length;
            if (!chunk.isEmpty() && (chunk.size() >= CHUNK_ROWS || bytes + size > CHUNK_SNAPSHOT_BYTES)) {
                mapper.insertQuotes(chunk); chunk.clear(); bytes = 0;
            }
            chunk.add(row); bytes += size;
            result.add(new StoredQuote(row.getQuoteId(),row.getAmountWen()));
        }
        if (!chunk.isEmpty()) mapper.insertQuotes(chunk);
        return List.copyOf(result);
    }

    private static final String GROUP_PREFIX="tq_g1_", GROUP_SCHEMA="GROUP_MEAN_TASK_QUOTE_V1";
    @Override public List<StoredQuote> storeOffersBatch(Task task,String payer,List<OfferWrite> writes) {
        Objects.requireNonNull(payer);
        return observedWrite(() -> persistChunked(writes,write -> {
            if(write instanceof ScopedWrite scoped){
                var snapshot=scoped.snapshot();
                return quoteRow("tq_s1_"+UUID.randomUUID(),task,bindingHash(snapshot.binding()),payer,snapshot.amountWen(),encode(snapshot));
            }
            if(write instanceof FixedWrite fixed)return fixedRow(task,payer,fixed.binding(),fixed.price().reason());
            var mean=(MeanWrite)write; var pin=mean.price().pin(); var binding=mean.binding(); var facts=mean.facts();
            var digest=cn.jia.economy.pricing.GroupMeanPricing.featureDigest(facts.version(),facts.operation(),facts.bucketId(),binding.requirementSha256(),binding.inputSnapshotDigest());
            var data=new java.util.LinkedHashMap<String,Object>(); data.put("schema",GROUP_SCHEMA); data.put("binding",binding);
            data.put("basis",Map.of("method","GROUP_MEAN","version","1","groupSha",pin.key().groupSha(),"tariffSha",pin.tariffSha(),
                    "policySha",pin.policySha(),"revision",pin.revision(),"generation",pin.generation(),"sampleCount",pin.n()));
            data.put("featureSha256",digest); data.put("facts",facts); data.put("preparation",Map.of("group",mean.observedGroup()));
            if(mean.initialIntentSha256()!=null)data.put("initialIntentSha256",mean.initialIntentSha256());
            return quoteRow(GROUP_PREFIX+UUID.randomUUID(),task,bindingHash(binding),payer,mean.price().amountWen(),encode(data));
        }));
    }
    private EconomyTaskQuoteEntity fixedRow(Task task,String payer,Binding binding,cn.jia.economy.task.TaskQuoteBatches.Reason reason) {
        return quoteRow("tq_f1_"+UUID.randomUUID(),task,bindingHash(binding),Objects.requireNonNull(payer),1L,
            encode(Map.of("schema","FIXED_FALLBACK_V1","binding",binding,"reason",Objects.requireNonNull(reason),"amountWen",1L)));
    }
    @Override public StoredQuote storeFixedFallback(Task task, Binding binding, String payer, cn.jia.economy.task.TaskQuoteBatches.Reason reason) {
        var row=fixedRow(task,payer,binding,reason); mapper.insertQuote(row); return new StoredQuote(row.getQuoteId(),row.getAmountWen());
    }

    private Quote insertQuote(String id, Task task, String bindingHash, String payerUserId, long amountWen, String snapshot) {
        var row = quoteRow(id,task,bindingHash,payerUserId,amountWen,snapshot);
        mapper.insertQuote(row);
        return receipt(row);
    }
    private EconomyTaskQuoteEntity quoteRow(String id, Task task, String bindingHash, String payerUserId, long amountWen, String snapshot) {
        return new EconomyTaskQuoteEntity().setQuoteId(id)
                .setBindingSha256(bindingHash).setTenantId(task.scope().tenantId()).setClientId(task.scope().clientId())
                .setOwnerJiacn(task.ownerJiacn()).setTaskId(task.taskId()).setPayerUserId(payerUserId)
                .setAmountWen(amountWen).setSnapshotJson(snapshot).setCreatedAt(System.currentTimeMillis());
    }

    @Override
    public Charge reserve(Task task, String quoteId, Binding currentBinding, String payerUserId) {
        return reserve(task,quoteId,currentBinding,payerUserId,null);
    }

    @Override
    public Charge reserve(Task task,String quoteId,Binding currentBinding,String payerUserId,
            cn.jia.economy.task.TaskBenchmarkAdmission.ScopedEnrollment enrollment) {
        EconomyTaskQuoteEntity quote = mapper.findQuote(task.scope().tenantId(), task.scope().clientId(),
                task.ownerJiacn(), task.taskId(), quoteId);
        if (quote == null) throw new TaskBillingException(QUOTE_NOT_FOUND);
        requireSnapshot(quote);
        // The task owner is a business identity, not the wallet principal. Check the immutable
        // payer before any write (and before returning an existing reservation).
        if (!quote.getPayerUserId().equals(payerUserId)) {
            throw new TaskBillingException(QUOTE_PAYER_MISMATCH);
        }
        if (!quote.getBindingSha256().equals(bindingHash(currentBinding))) {
            throw new TaskBillingException(QUOTE_BINDING_CHANGED);
        }
        ScopedTaskQuoteSnapshot enrollmentQuote=null;
        if(enrollment!=null){
            if(!ScopedTaskQuoteSnapshot.SCHEMA.equals(json.readTree(quote.getSnapshotJson()).path("schema").asText(null)))
                throw new cn.jia.economy.task.TaskBenchmarkAdmission.NotAdmitted();
            enrollmentQuote=json.readValue(quote.getSnapshotJson(),ScopedTaskQuoteSnapshot.class);
            enrollment.requireQuote(task,enrollmentQuote);
        }
        long now = System.currentTimeMillis();
        EconomyTaskChargeEntity row = new EconomyTaskChargeEntity()
                .setTenantId(task.scope().tenantId()).setClientId(task.scope().clientId())
                .setOwnerJiacn(task.ownerJiacn()).setTaskId(task.taskId()).setQuoteId(quoteId)
                .setPayerUserId(quote.getPayerUserId()).setAmountWen(quote.getAmountWen())
                .setState("RESERVING").setCreatedAt(now).setUpdatedAt(now);
        // Insert first: locking an absent charge then inserting would take overlapping InnoDB
        // gap locks across unrelated tasks. The upsert never changes an existing charge.
        mapper.insertChargeIfAbsent(row);
        row = lock(task);
        if (!quoteId.equals(row.getQuoteId())) throw new TaskBillingException(CHARGE_CONFLICT);
        if (State.HELD.name().equals(row.getState())) return receipt(row);
        if (!"RESERVING".equals(row.getState())) throw new TaskBillingException(CHARGE_CONFLICT);
        if (row.getAmountWen() > 0) {
            EconomyAccountKey payer = payer(row);
            EconomyAccountKey escrow = escrow(task);
            List.of(payer, escrow).stream().sorted(EconomyAccountKey.LOCK_ORDER)
                    .forEach(account -> ensureAccount(task, account, now));
            EconomyPostingResult result = post(task, row, EconomyJournalType.RESERVE_BOUNTY, payer, escrow,
                    new EconomyEscrowFunding(EconomyEscrowType.BOUNTY, payer, escrow, row.getAmountWen(), null), null);
            row.setReserveTransactionId(result.transactionId()).setEscrowVersion(result.escrow().escrowVersion());
        }
        row.setState(State.HELD.name());
        requireTransition(mapper.markHeld(row));
        // Namespace comes from the validated immutable DB quote, not an unchecked client ID.
        // Old snapshots do not contain initial features. Do not invent them at assignment time.
        String schema=json.readTree(quote.getSnapshotJson()).path("schema").asText(null);
        boolean groupMean=GROUP_SCHEMA.equals(schema), scoped=ScopedTaskQuoteSnapshot.SCHEMA.equals(schema);
        if (groupMean || scoped || MEASURED_SCHEMA.equals(schema)) {
            String sampleId = HexFormat.of().formatHex(digest(encode(List.of("TASK_LIFECYCLE_V1",task.scope().tenantId(),
                    task.scope().clientId(),task.ownerJiacn(),task.taskId(),quote.getQuoteId())).getBytes(StandardCharsets.UTF_8)));
            if(enrollment!=null)enrollment.recordPaidSample(task,quoteId,sampleId,enrollmentQuote);
            else requireTransition(scoped ? mapper.confirmScopedSample(quote,sampleId,json.readValue(quote.getSnapshotJson(),ScopedTaskQuoteSnapshot.class).featureSha())
                    : groupMean ? mapper.confirmGroupMeanSample(quote,sampleId) : mapper.confirmMeasuredSample(quote, sampleId));
        }
        return receipt(row);
    }

    @Override
    public Charge reservation(Task task, String quoteId, String payerUserId, String recommendationId) {
        EconomyTaskChargeEntity row = lock(task);
        if (row == null) throw new TaskBillingException(RESERVATION_REQUIRED);
        if (!Objects.equals(row.getPayerUserId(), payerUserId) || payerUserId == null)
            throw new TaskBillingException(QUOTE_PAYER_MISMATCH);
        if (!Objects.equals(row.getQuoteId(), quoteId) || quoteId == null)
            throw new TaskBillingException(CHARGE_CONFLICT);
        EconomyTaskQuoteEntity quote = mapper.findQuote(task.scope().tenantId(), task.scope().clientId(),
                task.ownerJiacn(), task.taskId(), quoteId);
        if (quote == null) throw new TaskBillingException(QUOTE_NOT_FOUND);
        requireSnapshot(quote);
        String originalRecommendation = json.readTree(quote.getSnapshotJson())
                .at("/binding/recommendationId").asText(null);
        if (recommendationId == null || !recommendationId.equals(originalRecommendation))
            throw new TaskBillingException(QUOTE_BINDING_CHANGED);
        return receipt(row);
    }

    @Override
    public Charge capture(Task task) {
        return settle(task, State.CAPTURED, true);
    }

    @Override
    public Charge release(Task task, boolean reservationRequired) {
        return settle(task, State.RELEASED, reservationRequired);
    }

    private Charge settle(Task task, State terminal, boolean required) {
        EconomyTaskChargeEntity row = lock(task);
        if (row == null) {
            if (required) throw new TaskBillingException(RESERVATION_REQUIRED);
            return null; // explicitly unassigned cancellation, never inferred free acceptance
        }
        State state = State.valueOf(row.getState());
        if (state == terminal || state == State.LEGACY_FREE) return receipt(row);
        if (state != State.HELD) throw new TaskBillingException(CHARGE_CONFLICT);
        if (row.getAmountWen() > 0) {
            EconomyAccountKey escrow = escrow(task);
            boolean capture = terminal == State.CAPTURED;
            EconomyAccountKey destination = capture
                    ? new EconomyAccountKey(EconomyConstants.CURRENCY_WEN, EconomyAccountOwnerType.SYSTEM,
                            "TASK_FEE", EconomyAccountPurpose.TASK_FEE)
                    : payer(row);
            if (capture) ensureAccount(task, destination, System.currentTimeMillis());
            EconomyPostingResult result = post(task, row,
                    capture ? EconomyJournalType.CAPTURE_TASK : EconomyJournalType.REFUND_BOUNTY,
                    escrow, destination, null, new EconomyEscrowSettlement(EconomyEscrowType.BOUNTY,
                            escrow, destination, row.getAmountWen(), row.getEscrowVersion(), row.getReserveTransactionId()));
            row.setTerminalTransactionId(result.transactionId()).setEscrowVersion(Math.addExact(row.getEscrowVersion(), 1L));
        }
        row.setState(terminal.name()).setUpdatedAt(System.currentTimeMillis());
        requireTransition(mapper.markTerminal(row));
        return receipt(row);
    }

    private EconomyPostingResult post(Task task, EconomyTaskChargeEntity row, EconomyJournalType type,
            EconomyAccountKey debit, EconomyAccountKey credit, EconomyEscrowFunding funding,
            EconomyEscrowSettlement settlement) {
        byte[] identity = encode(List.of("task-charge-v1", task.scope().tenantId(), task.scope().clientId(),
                task.ownerJiacn(), task.taskId(), row.getQuoteId(), type.name(), row.getPayerUserId(), row.getAmountWen()))
                .getBytes(StandardCharsets.UTF_8);
        return posting.post(new EconomyPostingCommand(task.scope(),
                new EconomyPrincipal(EconomyPrincipalType.USER, row.getPayerUserId()),
                UUID.nameUUIDFromBytes(identity).toString(), digest(identity), type, task.taskId(),
                List.of(new EconomyPostingLine(debit, -row.getAmountWen()),
                        new EconomyPostingLine(credit, row.getAmountWen())), funding, settlement));
    }

    private void ensureAccount(Task task, EconomyAccountKey key, long now) {
        String id = "task_billing_" + HexFormat.of().formatHex(digest(encode(List.of(task.scope().tenantId(),
                task.scope().clientId(), key.currency(), key.ownerType().name(), key.ownerId(), key.purpose().name()))
                .getBytes(StandardCharsets.UTF_8)));
        ledger.insertAccountIfAbsentAndLock(new EconomyAccountEntity().setAccountId(id)
                .setTenantId(task.scope().tenantId()).setClientId(task.scope().clientId())
                .setOwnerType(key.ownerType().name()).setOwnerId(key.ownerId()).setPurpose(key.purpose().name())
                .setCurrency(key.currency()).setBalanceWen(0L).setAllowNegative(0).setStatus("ACTIVE")
                .setVersion(0L).setCreateTime(now).setUpdateTime(now));
    }

    private EconomyTaskChargeEntity lock(Task task) {
        return mapper.lockCharge(task.scope().tenantId(), task.scope().clientId(), task.ownerJiacn(), task.taskId());
    }

    private static EconomyAccountKey payer(EconomyTaskChargeEntity row) {
        return new EconomyAccountKey(EconomyConstants.CURRENCY_WEN, EconomyAccountOwnerType.USER,
                row.getPayerUserId(), EconomyAccountPurpose.AVAILABLE);
    }

    private static EconomyAccountKey escrow(Task task) {
        return new EconomyAccountKey(EconomyConstants.CURRENCY_WEN, EconomyAccountOwnerType.TASK,
                task.taskId(), EconomyAccountPurpose.ESCROW);
    }

    private String bindingHash(Binding binding) {
        return HexFormat.of().formatHex(digest(encode(List.of(binding.requirementRevision(),
                binding.requirementSha256(), binding.inputSnapshotDigest(), binding.agentId(), binding.recommendationId()))
                .getBytes(StandardCharsets.UTF_8)));
    }

    private String encode(Object value) {
        return json.writeValueAsString(value);
    }

    private static byte[] digest(byte[] input) {
        try { return MessageDigest.getInstance("SHA-256").digest(input); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static void requireTransition(int changed) {
        if (changed != 1) throw new TaskBillingException(CHARGE_CONFLICT);
    }

    private void requireSnapshot(EconomyTaskQuoteEntity row) {
        if (row.getSnapshotJson() == null) throw new IllegalStateException("Unresolvable frozen quote preparation");
        var root=json.readTree(row.getSnapshotJson());
        String schema=root.path("schema").asText(null);
        // A stored new-format identity losing its schema is corruption, not a historical quote.
        // Selection still follows the stored schema; this does not grant semantics to a client prefix.
        if(row.getQuoteId().startsWith("tq_s1_")&&!ScopedTaskQuoteSnapshot.SCHEMA.equals(schema))
            throw new IllegalStateException("Scoped quote schema is missing or changed");
        if(ScopedTaskQuoteSnapshot.SCHEMA.equals(schema)){
            final ScopedTaskQuoteSnapshot scoped;
            try {scoped=json.readValue(row.getSnapshotJson(),ScopedTaskQuoteSnapshot.class);}
            catch(RuntimeException corrupt){throw new IllegalStateException("Invalid scoped quote snapshot",corrupt);}
            if(row.getAmountWen()!=scoped.amountWen() || !row.getBindingSha256().equals(bindingHash(scoped.binding())))
                throw new IllegalStateException("Scoped quote storage differs from snapshot");
        }
    }

    private Quote receipt(EconomyTaskQuoteEntity row) {
        requireSnapshot(row);
        return new Quote(row.getQuoteId(), row.getAmountWen(), row.getSnapshotJson());
    }

    private static Charge receipt(EconomyTaskChargeEntity row) {
        return new Charge(row.getQuoteId(), row.getAmountWen(), State.valueOf(row.getState()),
                row.getReserveTransactionId(), row.getTerminalTransactionId());
    }

    private record ToolEstimate(TaskQuoteCalculator.ToolKey key, BigDecimal units, TaskQuoteCalculator.ToolPrice price) { }
    private record WorkloadSnapshot(String kind, long inputTokens, long outputTokens, long deliverables) { }
    private record QuoteSnapshot(Binding binding, WorkloadSnapshot workload, String ruleId, String ruleVersion,
            Map<ModelTokenPrice.Category, Long> tokens, ModelTokenPrice modelPrice, List<ToolEstimate> tools,
            BigDecimal estimatedYuan, long amountWen) { }
}
