package cn.jia.agent.security;

import cn.jia.agent.skill.quote.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CostProfileReportJsonTest {
    private static final String H="a".repeat(64);
    private static CostProfileReport report() {
        var slots=List.of(new MeasuredCostProfile.Slot(0,"text.summary","1",H,List.of(),"summary","1",H,"install"));
        var routes=List.of(new cn.jia.economy.pricing.CostRouteReference("MODEL","OUTPUT","namespace","model","TOKENS_V1"));
        return new CostProfileReport(1,List.of(new MeasuredCostProfile("summary",1,0,slots,"native-1","context-1",H,H,"capture-1",routes)));
    }
    @Test void consumesSameMeasuredWireFixtureAsRuntimeWithoutAnyTariffAuthority() throws Exception {
        try(var source=getClass().getResourceAsStream("/cost-profile-report.json")){
            assertNotNull(source);String raw=new String(source.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(report(),CostProfileReportJson.read(raw));
            assertFalse(raw.contains("channel"));assertFalse(raw.contains("tier"));assertFalse(raw.contains("configSha"));
            assertThrows(IllegalArgumentException.class,()->CostProfileReportJson.read(raw.replace("\"namespace\":\"namespace\"","\"namespace\":\"namespace\",\"channel\":\"forged\"")));
        }
    }
    @Test void strictWireRoundtripAndSequenceIndependentConfigurationIdentity() {
        var report=report();assertEquals(report,CostProfileReportJson.read(CostProfileReportJson.write(report)));
        assertEquals(report,CostProfileReportJson.readWire(report.wire()));
        assertEquals(report.sha256(),new CostProfileReport(2,report.profiles()).sha256());
        assertEquals("CostProfileReport[private]",report.toString());
    }
    @Test void declarationMatchesIndependentCanonicalEncodingVector() {
        var report=report();assertEquals("bb6aff8ed243ccf9e146640c6a10f37138af8c82f1014c1c7cdb24b255c2f220",report.profiles().getFirst().measurementSha());
        assertEquals("729eff1e98d5f9fdbf2a0249d8b81a676da8a4936fefe47b1d718750b8fb6068",report.sha256());
        assertEquals(1042,cn.jia.core.util.CanonicalValueEncoding.encode(report.configuration()).length);
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
