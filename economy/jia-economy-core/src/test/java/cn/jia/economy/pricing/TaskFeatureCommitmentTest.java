package cn.jia.economy.pricing;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskFeatureCommitmentTest {
    @Test void packedBinary32ValidationMatchesFloatOracleEvenForTheLastComponent(){
        var id=new TaskEstimateFeatures.Identity("cfg","model",1024);
        var bytes=new byte[4096];var view=java.nio.ByteBuffer.wrap(bytes);
        // Test both signs, all 256 exponent values, zero and nonzero mantissas. Signed zero fills the prefix.
        for(int i=0;i<1024;i++)view.putInt(i*4,0x80000000);
        for(int sign:new int[]{0,0x80000000})for(int exp=0;exp<256;exp++)for(int mant:new int[]{0,1,0x400000,0x7fffff}){
            int bits=sign|(exp<<23)|mant;view.putInt(4092,bits);float value=Float.intBitsToFloat(bits);
            var vec=TaskEstimateFeatures.vectorFromBytes(bytes);
            java.util.function.Supplier<TaskEstimateFeatures> make=()->new TaskEstimateFeatures(TaskEstimateFeatures.VERSION,"t","r","i","s",id,vec,List.of(),null);
            if(Float.isFinite(value) && value!=0)assertEquals(bits,Float.floatToRawIntBits(make.get().component(1023)));
            else assertThrows(IllegalArgumentException.class,make::get);
        }
        view.putFloat(0,1f);view.putFloat(4092,Float.NEGATIVE_INFINITY);
        assertThrows(IllegalArgumentException.class,()->new TaskEstimateFeatures(TaskEstimateFeatures.VERSION,"t","r","i","s",id,TaskEstimateFeatures.vectorFromBytes(bytes),List.of(),null));
    }
    @Test void bulkCopyReplacesEveryComponentAndDoesNotMutatePackedState(){
        var random=new Random(9091);var boxed=new ArrayList<Float>();var data=java.nio.ByteBuffer.allocate(4096);
        for(int i=0;i<1024;i++){float v=switch(i%5){case 0->-0f;case 1->Float.MIN_VALUE;case 2->-Float.MAX_VALUE;default->random.nextFloat()-.5f;};boxed.add(v);data.putFloat(v);}
        var target=new float[1024];var id=new TaskEstimateFeatures.Identity("cfg","model",1024);
        for(var src:List.of(boxed,TaskEstimateFeatures.vectorFromBytes(data.array()))){
            var f=new TaskEstimateFeatures(TaskEstimateFeatures.VERSION,"v","r","i","t",id,src,List.of(),null);String digest=f.digest();
            for(int pass=0;pass<2;pass++){Arrays.fill(target,Float.NaN);f.copyComponents(target);
                for(int i=0;i<target.length;i++)assertEquals(Float.floatToRawIntBits(boxed.get(i)),Float.floatToRawIntBits(target[i]));}
            Arrays.fill(target,0);assertEquals(digest,f.digest());assertThrows(IllegalArgumentException.class,()->f.copyComponents(new float[1023]));
        }
    }
    @Test void canonicalV1BytesRemainIdenticalForOldCommitments(){
        var vector=new ArrayList<Float>(Collections.nCopies(1024,0f));
        vector.set(0,-0f);vector.set(1,Float.MIN_VALUE);vector.set(2,Float.MAX_VALUE);vector.set(3,-0.5f);
        // Independent golden encoding: big-endian field lengths/ints, UTF-8, IEEE754 bits, signed long and nullable count.
        for(Integer count:Arrays.asList(null,2)){
            var f=new TaskEstimateFeatures(TaskEstimateFeatures.VERSION,"v1","a".repeat(64),"b".repeat(64),"c".repeat(64),new TaskEstimateFeatures.Identity("配置","model",1024),vector,List.of(new TaskEstimateFeatures.File("输入","text/plain",1L<<33,7)),count);
            var bytes=f.vectorBytes();assertEquals(4096,bytes.length);assertEquals("80000000000000017f7fffffbf000000",HexFormat.of().formatHex(bytes,0,16));
            var packed=new TaskEstimateFeatures(f.featureVersion(),f.templateVersion(),f.requirementSha256(),f.inputSnapshotSha256(),f.textSha256(),f.embeddingIdentity(),TaskEstimateFeatures.vectorFromBytes(bytes),f.files(),count);
            assertEquals(f,packed);assertEquals(f.hashCode(),packed.hashCode());assertThrows(UnsupportedOperationException.class,()->packed.vector().set(0,1f));
            var copy=packed.vectorBytes();Arrays.fill(copy,(byte)1);Arrays.fill(bytes,(byte)2);
            assertEquals(f.digest(),packed.digest());assertArrayEquals(f.vectorBytes(),packed.vectorBytes());
            for(int bits:new int[]{0x7fc00001,0x7f800000,0xff800000,0}){
                var bad=new byte[4096];java.nio.ByteBuffer.wrap(bad).putInt(bits);
                assertThrows(IllegalArgumentException.class,()->new TaskEstimateFeatures(f.featureVersion(),f.templateVersion(),f.requirementSha256(),f.inputSnapshotSha256(),f.textSha256(),f.embeddingIdentity(),TaskEstimateFeatures.vectorFromBytes(bad),f.files(),count));
            }
            Arrays.fill(bytes,(byte)42);assertEquals(count==null?"79f9cf90f304bc7f51cb70677f6d45cd1cda848745ddedb110db6803b288fb07":"a91fa35c84b155a1cd96ff6c4948e94b4138150f63b80a115547b6aea71d0207",f.digest());
        }
    }
}
