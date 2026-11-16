package cn.jia.chat.service;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatMaterialsImageContractTest {
    private static Map<String,Object> source(String mime) {
        return Map.of("sourceRefId","source_1","sourceKind","TASK_WORKSPACE_FILE",
                "contentHash","a".repeat(64),"byteLength","123","contentMimeType",mime);
    }
    private static Map<String,Object> registration(String mime) {
        return Map.of("materialsInput",Map.of("schemaVersion",1,"supportedInputs",List.of(
                Map.of("mediaKind","image","mimeType",mime,"carrier","LOCAL_IMAGE",
                        "carrierContractDigest","sha256:"+"b".repeat(64)))));
    }
    @Test void supportedImagesKeepOriginalHashAndMime() {
        for (String mime:List.of("image/jpeg","image/png","image/webp","image/gif","image/bmp",
                "image/tiff","image/heic","image/heif","image/avif")) {
            var result=ChatMaterialsInputContract.candidates(registration(mime),List.of(source(mime)));
            var inputs=(List<?>)result.get("sources");assertEquals(1,inputs.size());
            var input=(Map<?,?>)inputs.getFirst();assertEquals(mime,input.get("mimeType"));
            assertEquals("a".repeat(64),input.get("sha256"));
        }
    }
    @Test void missingNativeCarrierDoesNotRejectOrMutateTheAuthorizedCatalogue() {
        for (String mime:List.of("image/jpeg", "image/png", "audio/mpeg", "application/x-custom")) {
            var catalog=List.of(source(mime));
            var result=ChatMaterialsInputContract.candidates(Map.of(),catalog);
            assertEquals(List.of(),result.get("sources")); // no fabricated delivery receipt
            assertEquals(List.of(source(mime)),catalog); // still available to native file execution
            assertNull(ChatMaterialsInputContract.bind(result,"tenant","owner","client","conversation",1,"task","request","turn","agent"));
        }
        assertEquals(List.of(),ChatMaterialsInputContract.candidates(registration("image/png"),List.of(source("image/jpeg"))).get("sources"));
    }
    @Test void harmlessDeclarationMetadataIsProjectedNotUsedAsAuthority() {
        var format=new java.util.LinkedHashMap<String,Object>(Map.of("mediaKind","image","mimeType","image/png",
                "carrier","LOCAL_IMAGE","carrierContractDigest","sha256:"+"b".repeat(64)));
        format.put("displayName","image adapter");format.put("downloadUrl","https://untrusted.invalid");
        var parsed=ChatMaterialsInputContract.declaration(Map.of("schemaVersion",1,"supportedInputs",List.of(format),"description","extra metadata"));
        assertEquals(registration("image/png").get("materialsInput"),parsed);
        format.put("carrier","UNKNOWN");
        assertThrows(IllegalArgumentException.class,()->ChatMaterialsInputContract.declaration(Map.of("schemaVersion",1,"supportedInputs",List.of(format))));
    }
    @Test void noMaterialsDoesNotNeedAnImageCapability() {
        assertEquals(List.of(),ChatMaterialsInputContract.candidates(Map.of(),List.of()).get("sources"));
    }
}
