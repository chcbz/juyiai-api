package cn.jia.economy.usage;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Membership V1 tuple: signed 32-bit big-endian UTF-8 byte length (-1 for null), followed by bytes.
 * Chain in ascending membership revision: SHA256(ASCII(lowerHex(previous) + lowerHex(leaf))).
 * Empty chain = SHA256(empty bytes). Revision is sparse, not a count. */
public final class TaskMembershipDigest {
    private TaskMembershipDigest() {}
    public static final String EMPTY="e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    public static String next(String previous,String leaf){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((previous+leaf).getBytes(StandardCharsets.US_ASCII)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public static String member(String tenant,String client,String owner,String task,String execution,String run,String capture,String admission){
        return tuple("TASK_MEMBER_V1",tenant,client,owner,task,execution,run,capture,admission);
    }
    public static String tuple(String... fields) {
        try {
            var hash=MessageDigest.getInstance("SHA-256");
            for(String field:fields){byte[] data=field==null?null:field.getBytes(StandardCharsets.UTF_8);
                hash.update(ByteBuffer.allocate(4).putInt(data==null?-1:data.length).array());if(data!=null)hash.update(data);}
            return HexFormat.of().formatHex(hash.digest());
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
