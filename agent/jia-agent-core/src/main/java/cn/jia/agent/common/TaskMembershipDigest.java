package cn.jia.agent.common;

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
    public static String tuple(String... fields) {
        try {
            var hash=MessageDigest.getInstance("SHA-256");
            for(String field:fields){byte[] data=field==null?null:field.getBytes(StandardCharsets.UTF_8);
                hash.update(ByteBuffer.allocate(4).putInt(data==null?-1:data.length).array());if(data!=null)hash.update(data);}
            return HexFormat.of().formatHex(hash.digest());
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
