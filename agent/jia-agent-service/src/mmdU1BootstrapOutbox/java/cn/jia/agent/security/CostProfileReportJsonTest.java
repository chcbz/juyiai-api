package cn.jia.agent.security;

import cn.jia.agent.skill.quote.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CostProfileReportJsonTest {
    private static final String H="a".repeat(64);
    private static CostProfileReport report() {
        var slots=List.of(new CostProfile.Slot(0,"text.summary","1",H,List.of(),"summary","1",H,"install"));
        var routes=List.of(new CostProfile.Route("MODEL","OUTPUT","channel","model","default","TOKENS_V1"));
        return new CostProfileReport(1,List.of(new CostProfile("summary",1,0,slots,"native-1","context-1",H,H,"capture-1",routes)));
    }
    @Test void strictWireRoundtripAndSequenceIndependentConfigurationIdentity() {
        var report=report();assertEquals(report,CostProfileReportJson.read(CostProfileReportJson.write(report)));
        assertEquals(report,CostProfileReportJson.readWire(report.wire()));
        assertEquals(report.sha256(),new CostProfileReport(2,report.profiles()).sha256());
        assertEquals("CostProfileReport[private]",report.toString());
    }
    @Test void declarationMatchesIndependentCanonicalEncodingVector() {
        var report=report();assertEquals("8065776bbe21d93d2c0b4f7547921b7dc34238bcda64c12078273ee0e427a432",report.profiles().getFirst().configSha());
        assertEquals("4d5508e8c8cf0e02cfabbad11422e7a747f9a15e0e12dba90db17bc66721fed1",report.sha256());
        assertEquals(1051,cn.jia.core.util.CanonicalValueEncoding.encode(report.configuration()).length);
    }
    @Test void emptyProfilesAreAnExplicitWithdrawalNotAnImaginaryExecutableProfile() {
        var empty=new CostProfileReport(2,List.of());assertEquals(empty,CostProfileReportJson.read(CostProfileReportJson.write(empty)));
        assertNotEquals(report().sha256(),empty.sha256());
    }
    @Test void rejectsUnknownSchemaFieldsCoercionAndUnboundOrTamperedDigests() {
        String raw=CostProfileReportJson.write(report());
        for(String bad:List.of(raw.replace("COST_PROFILES_V1","NATIVE_PRICING_PROFILES_V1"),
                raw.replace("\"sequence\":\"1\"","\"sequence\":1"),raw.replace("\"selectionPriority\":\"0\"","\"selectionPriority\":0"),
                raw.replace("\"sequence\":\"1\"","\"sequence\":\"01\""),raw.replace("\"sequence\":\"1\"","\"sequence\":\"9223372036854775808\""),
                raw.replace("\"installId\":\"install\"","\"installId\":\"rebound\""),raw.replace("\"modelOrTool\":\"model\"","\"modelOrTool\":\"other\""),
                raw.replace("\"revision\":\"1\"","\"revision\":\"0\""),raw.replace("\"profileId\":\"summary\"","\"profileId\":\"summary\",\"url\":\"secret\"")))
            assertThrows(IllegalArgumentException.class,()->CostProfileReportJson.read(bad));
    }
    @Test void rejectsDuplicatesTrailingDataMalformedUnicodeAndOversizedPayloads() {
        String raw=CostProfileReportJson.write(report());
        for(String bad:List.of(raw.replace("\"sequence\":\"1\"","\"sequence\":\"1\",\"sequence\":\"2\""),raw+" {}",
                raw.replace("\"installId\":\"install\"","\"installId\":\"\\ud800\"")," ".repeat(CostProfileReportJson.MAX_BYTES+1)))
            assertThrows(IllegalArgumentException.class,()->CostProfileReportJson.read(bad));
    }
}
