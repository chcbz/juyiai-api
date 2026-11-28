package cn.jia.economy.service.impl;

import cn.jia.core.util.CanonicalValueEncoding;
import cn.jia.economy.pricing.CostRouteReference;
import cn.jia.economy.task.TaskCalibrationObservations.Frozen;
import cn.jia.economy.task.TaskHistoryCatalog.Revision;
import cn.jia.economy.usage.BenchmarkSampleEvidence.Pin;
import cn.jia.economy.usage.ConfirmedSampleEvidence;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/** Private whole-task observation; no prices, provider bodies, new approval or publication rights.
 * TRAIN/HOLDOUT and scope/configuration remain inside the pinned original, not CLI-selected. */
record FrozenScopedBenchmarkObservation(String schema,Revision source,Pin pin,long featuresAt,long observedBy,
        String manifest,String review,ConfirmedSampleEvidence.Assessment assessment,List<Row> operations) {
    static final String SCHEMA="scoped-benchmark-observation-v1";
    record Row(CostRouteReference resource,ProjectedTaskOperation operation) {}
    FrozenScopedBenchmarkObservation { operations=List.copyOf(operations); }
    @Override public String toString(){return "FrozenScopedBenchmarkObservation[private]";}
    Frozen seal(ObjectMapper json){String raw=json.writeValueAsString(this);return new Frozen(CanonicalValueEncoding.sha256(CanonicalValueEncoding.utf8(raw)),raw);}
}
