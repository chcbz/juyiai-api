package cn.jia.economy.service.impl;

import cn.jia.economy.task.*;
import tools.jackson.databind.ObjectMapper;

/** Validates original source funding after the mapper established its recorded state in the same
 * transaction. Funding proves neither actual usage nor permission to publish aggregate statistics. */
final class ScopedSampleFunding {
    private ScopedSampleFunding() {}
    static boolean matches(ScopedBenchmarkManifest manifest,String manifestSha,String sampleId,String featureSha,
            String quoteJson,String sponsorJson,String sponsorSha,ObjectMapper json){
        if(!ScopedTaskQuoteSnapshot.featureSha(manifest.scopePin()).equals(featureSha))return false;
        if(quoteJson!=null){
            if(sponsorJson!=null||sponsorSha!=null)return false;
            var quote=json.readValue(quoteJson,ScopedTaskQuoteSnapshot.class);
            return manifest.scopePin().equals(quote.scopePin())&&manifest.agentId().equals(quote.binding().agentId());
        }
        if(sponsorJson==null||sponsorSha==null||!sampleId.equals(manifestSha))return false;
        var authority=TaskBenchmarkAdmissionService.decodeSponsorship(sponsorJson,sponsorSha);
        return manifest.task().equals(authority.task())&&manifest.caseId().equals(authority.caseId())
                &&manifestSha.equals(authority.manifestSha256())&&manifest.agentId().equals(authority.agentId());
    }
}
