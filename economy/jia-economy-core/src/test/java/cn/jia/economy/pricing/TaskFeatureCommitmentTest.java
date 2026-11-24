package cn.jia.economy.pricing;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskFeatureCommitmentTest {
    @Test void canonicalV1BytesRemainIdenticalForOldCommitments(){
        var vector=new ArrayList<Float>(Collections.nCopies(1024,0f));
        vector.set(0,-0f);vector.set(1,Float.MIN_VALUE);vector.set(2,Float.MAX_VALUE);vector.set(3,-0.5f);
        // Independent golden encoding: big-endian field lengths/ints, UTF-8, IEEE754 bits, signed long and nullable count.
        for(Integer count:Arrays.asList(null,2)){
            var f=new TaskEstimateFeatures(TaskEstimateFeatures.VERSION,"v1","a".repeat(64),"b".repeat(64),"c".repeat(64),new TaskEstimateFeatures.Identity("配置","model",1024),vector,List.of(new TaskEstimateFeatures.File("输入","text/plain",1L<<33,7)),count);
            var bytes=f.vectorBytes();assertEquals(4096,bytes.length);assertEquals("80000000000000017f7fffffbf000000",HexFormat.of().formatHex(bytes,0,16));
            Arrays.fill(bytes,(byte)42);assertEquals(count==null?"79f9cf90f304bc7f51cb70677f6d45cd1cda848745ddedb110db6803b288fb07":"a91fa35c84b155a1cd96ff6c4948e94b4138150f63b80a115547b6aea71d0207",f.digest());
        }
    }
}
