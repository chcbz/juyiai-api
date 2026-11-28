package cn.jia.economy.service.impl;

import cn.jia.economy.mapper.GroupBaselineMaintenanceMapper;
import cn.jia.economy.pricing.*;
import cn.jia.economy.task.*;
import cn.jia.economy.usage.*;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

/** Explicit maintenance-only read; no component scanning, imports, policy change or approval creation. */
public class ScopedBenchmarkReferenceExport {
    private final BenchmarkSampleEvidence evidence;
    private final TaskReferencePricing prices;
    private final GroupBaselineMaintenanceMapper db;
    private final ObjectMapper json;
    public ScopedBenchmarkReferenceExport(BenchmarkSampleEvidence evidence,TaskReferencePricing prices,GroupBaselineMaintenanceMapper db,ObjectMapper json){
        this.evidence=evidence;this.prices=prices;this.db=db;this.json=json;
    }
    public record Provenance(String schema,cn.jia.economy.service.EconomyScope scope,String groupSha,String observationSha,
            long bookEpoch,String referenceSha,String tariffSha) {}
    public record Export(TaskCalibrationObservations.Frozen observation,TaskCalibrationObservations.Frozen reference,TaskCalibrationObservations.Frozen provenance) {}
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public Export freeze(TaskBillingService.Task task,TaskHistoryCatalog.Revision source,BenchmarkSampleEvidence.Pin pin,ConfirmedSampleEvidence.Decoder decoder){
        var original=evidence.freezeScopedReviewed(task,source,pin,decoder);
        var observation=json.readValue(original.json(),FrozenScopedBenchmarkObservation.class);
        var manifest=TaskBenchmarkAdmissionService.decodeScoped(observation.manifest(),pin.manifestSha());
        var routes=GroupStudyPolicy.scopedRoutes(manifest.compatibleConfig());
        Long epoch=db.readDomainEpoch(task.scope());if(epoch==null)throw new TaskBudgetEstimator.Stale();
        // The epoch and all tariff reads share this one consistent snapshot. Source eligibility was
        // independently frozen above; this output does not assert continuing online eligibility.
        var book=prices.freezeScoped(task.scope(),"scoped-reference-"+epoch,routes);
        var reference=FrozenScopedGroupStudy.freezeReference(book);
        var provenance=FrozenScopedGroupStudy.freeze(new Provenance("scoped-reference-source-v1",task.scope(),manifest.groupSha(),original.sha256(),epoch,
                reference.sha256(),GroupTariffSnapshot.capture(book).tariffSha()));
        return new Export(original,reference,provenance);
    }
}
