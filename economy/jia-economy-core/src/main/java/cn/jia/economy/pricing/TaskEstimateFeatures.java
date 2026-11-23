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
        vector=List.copyOf(vector);files=List.copyOf(files);
        if(!VERSION.equals(featureVersion)||embeddingIdentity.dimensions()!=1024||vector.size()!=embeddingIdentity.dimensions())throw new IllegalArgumentException("Unexpected task feature identity");
        boolean nonzero=false;for(float f:vector){if(!Float.isFinite(f))throw new IllegalArgumentException("Invalid task vector");nonzero|=f!=0;}
        if(!nonzero)throw new IllegalArgumentException("Undefined cosine");
    }
    /** V1 binary commitment: length-prefixed UTF-8, fixed big-endian integers and IEEE754 float32. No private text or filenames. */
    public String digest(){try{
        var hash=MessageDigest.getInstance("SHA-256");try(var out=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),hash))){
            for(String v:List.of(featureVersion,templateVersion,requirementSha256,inputSnapshotSha256,textSha256,embeddingIdentity.providerConfigVersion(),embeddingIdentity.model()))field(out,v);
            out.writeInt(embeddingIdentity.dimensions());for(float v:vector)out.writeFloat(v);
            out.writeInt(files.size());for(var f:files){field(out,f.purpose());field(out,f.contentMimeType());out.writeLong(f.byteLength());out.writeInt(f.version());}
            out.writeBoolean(requestedDeliverables!=null);if(requestedDeliverables!=null)out.writeInt(requestedDeliverables);
        }return HexFormat.of().formatHex(hash.digest());
    }catch(IOException|NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static void field(DataOutputStream out,String s)throws IOException{var bytes=s.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);}
    @Override public String toString(){return "TaskEstimateFeatures[private]";}
}
