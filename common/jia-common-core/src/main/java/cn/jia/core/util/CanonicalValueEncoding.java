package cn.jia.core.util;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Versioned contracts explicitly opt into E: null, UTF-8 strings, ordered arrays and string-keyed objects.
 * Numbers/booleans must first become schema-typed canonical strings. Not a JSON serializer or validator.
 */
public final class CanonicalValueEncoding {
    private CanonicalValueEncoding() { }
    public static byte[] encode(Object value) {
        var bytes=new ByteArrayOutputStream();
        try(var output=new DataOutputStream(bytes)) { write(output,value); }
        catch(IOException impossible) { throw new IllegalStateException(impossible); }
        return bytes.toByteArray();
    }
    public static String digest(Object value) { return sha256(encode(value)); }
    public static String sha256(byte[] bytes) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible) {throw new AssertionError(impossible);}
    }
    public static byte[] utf8(String text) {
        try {
            ByteBuffer encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
            byte[] bytes=new byte[encoded.remaining()];encoded.get(bytes);return bytes;
        } catch(CharacterCodingException failure) {throw new IllegalArgumentException("Invalid canonical UTF-8 value");}
    }
    public static int compareUtf8(String left,String right) {return Arrays.compareUnsigned(utf8(left),utf8(right));}
    private static void write(DataOutputStream output,Object value) throws IOException {
        if(value==null) output.writeByte(0x6e);
        else if(value instanceof String text) {
            byte[] bytes=utf8(text);output.writeByte(0x73);output.writeInt(bytes.length);output.write(bytes);
        } else if(value instanceof List<?> list) {
            output.writeByte(0x61);output.writeInt(list.size());for(Object child:list)write(output,child);
        } else if(value instanceof Map<?,?> map) {
            var keys=new ArrayList<String>();
            for(Object key:map.keySet()) {
                if(!(key instanceof String text))throw new IllegalArgumentException("Canonical object keys must be strings");
                keys.add(text);
            }
            keys.sort(CanonicalValueEncoding::compareUtf8);
            output.writeByte(0x6f);output.writeInt(keys.size());
            for(String key:keys) {write(output,key);write(output,map.get(key));}
        } else throw new IllegalArgumentException("Canonical values must be schema-normalized strings");
    }
}
