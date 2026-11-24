package cn.jia.economy.pricing;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Private, quote-time features only. Never a browser DTO. Files are the assignment catalogue's fixed versions, not inferred token usage. */
public record TaskEstimateFeatures(String featureVersion,String templateVersion,String requirementSha256,String inputSnapshotSha256,
        String textSha256,Identity embeddingIdentity,List<Float> vector,List<File> files,Integer requestedDeliverables) {
    public static final String VERSION="task-estimate-features-v1";
    public record Identity(String providerConfigVersion,String model,int dimensions){}
    public record File(String purpose,String contentMimeType,long byteLength,int version){}
    public TaskEstimateFeatures {
        if(!(vector instanceof PackedVector))vector=List.copyOf(vector);
        files=List.copyOf(files);
        if(!VERSION.equals(featureVersion)||embeddingIdentity.dimensions()!=1024||vector.size()!=embeddingIdentity.dimensions())throw new IllegalArgumentException("Unexpected task feature identity");
        boolean nonzero=false;
        if(vector instanceof PackedVector packed)nonzero=packed.validateAndFindNonzero();
        else for(float f:vector){if(!Float.isFinite(f))throw new IllegalArgumentException("Invalid task vector");nonzero|=f!=0;}
        if(!nonzero)throw new IllegalArgumentException("Undefined cosine");
    }
    /** Immutable list view of an owned V1 byte block; keep the JSON/list contract at the boundary. */
    private static final class PackedVector extends AbstractList<Float> implements RandomAccess {
        private final byte[] bytes;
        private final java.nio.ByteBuffer view;
        private PackedVector(byte[] source){bytes=source.clone();view=java.nio.ByteBuffer.wrap(bytes);}
        /** For big-endian binary32, exponent bits are the low seven of byte 0 and the high bit of byte 1.
         * Ignore the sign for zero, but check the exponent of every component even after a nonzero is found. */
        private boolean validateAndFindNonzero(){
            boolean nonzero=false;
            for(int i=0;i<bytes.length;i+=Float.BYTES){
                int high=bytes[i]&0x7f;
                if(high==0x7f && bytes[i+1]<0)throw new IllegalArgumentException("Invalid task vector");
                if(!nonzero)nonzero=(high|bytes[i+1]|bytes[i+2]|bytes[i+3])!=0;
            }
            return nonzero;
        }
        @Override public int size(){return bytes.length/Float.BYTES;}
        @Override public Float get(int index){return value(index);}
        private float value(int index){return view.getFloat(Objects.checkIndex(index,size())*Float.BYTES);}
    }
    public static List<Float> vectorFromBytes(byte[] bytes){
        if(bytes==null || bytes.length!=1024*Float.BYTES)throw new IllegalArgumentException("Invalid feature vector length");
        return new PackedVector(bytes);
    }
    private static float component(List<Float> values,int index){return values instanceof PackedVector packed?packed.value(index):values.get(index);}
    float component(int index){return component(vector,index);}
    /** Detached canonical bytes; never expose mutable storage from this immutable feature. */
    public byte[] vectorBytes(){
        if(vector instanceof PackedVector packed)return packed.bytes.clone();
        var bytes=java.nio.ByteBuffer.allocate(vector.size()*Float.BYTES);
        for(float v:vector)bytes.putFloat(v);
        return bytes.array();
    }
    /** V1 binary commitment: length-prefixed UTF-8, fixed big-endian integers and IEEE754 float32. No private text or filenames. */
    public String digest(){try{
        var hash=MessageDigest.getInstance("SHA-256");try(var out=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),hash))){
            for(String v:List.of(featureVersion,templateVersion,requirementSha256,inputSnapshotSha256,textSha256,embeddingIdentity.providerConfigVersion(),embeddingIdentity.model()))field(out,v);
            out.writeInt(embeddingIdentity.dimensions());out.write(vector instanceof PackedVector packed?packed.bytes:vectorBytes());
            out.writeInt(files.size());for(var f:files){field(out,f.purpose());field(out,f.contentMimeType());out.writeLong(f.byteLength());out.writeInt(f.version());}
            out.writeBoolean(requestedDeliverables!=null);if(requestedDeliverables!=null)out.writeInt(requestedDeliverables);
        }return HexFormat.of().formatHex(hash.digest());
    }catch(IOException|NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static void field(DataOutputStream out,String s)throws IOException{var bytes=s.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);}
    @Override public String toString(){return "TaskEstimateFeatures[private]";}
}
