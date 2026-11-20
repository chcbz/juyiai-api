package cn.jia.agent.api;

import cn.jia.agent.position.PositionSkillService;
import cn.jia.agent.service.PositionAuthority.*;
import cn.jia.agent.service.SkillExecutionService;
import cn.jia.agent.skill.*;
import cn.jia.core.entity.JsonRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static cn.jia.agent.position.PositionSkillException.require;

/** Platform marketplace resources. Transport and execution selection do not know product operations. */
@RestController
@RequestMapping("/internal/agent/executions/{e}/runs/{r}/business")
public final class ExecutionSkillProductsController extends ExecutionDomainController {
    private final PositionSkillService positions;private final SkillPackagePublicationService products;
    ExecutionSkillProductsController(SkillExecutionService exec,InstalledDomainAuthority auth,PrivateToolCallReceipts receipts,PositionSkillService positions,SkillPackagePublicationService products){
        super(ExecutionPositionUpgradeController.REQUIRED,exec,auth,receipts);this.positions=positions;this.products=products;
    }
    public record Input(Map<String,Object> payload,String snapshotSha256,Fence fence) implements BoundInput {}
    private record Subject(Scope scope,Grant grant){}
    private Subject subject(Call<?> c,Scope s){return new Subject(s,positions.currentManagementGrant(s,c.actor().agentId()));}
    private static String text(Map<?,?> m,String k){require(m!=null && m.get(k) instanceof String,400,"BAD_REQUEST");return (String)m.get(k);}
    private static Map<String,Object> intent(Input input,String... fields){
        var data=input.payload();require(data!=null,400,"BAD_REQUEST");var selected=new LinkedHashMap<String,Object>();
        for(var f:fields){require(data.containsKey(f) && data.get(f)!=null,400,"BAD_REQUEST");selected.put(f,data.get(f));}return selected;
    }
    private static long number(Map<?,?> m,String k){var v=text(m,k);require(v.matches("0|[1-9][0-9]{0,18}"),400,"BAD_REQUEST");try{return Long.parseLong(v);}catch(NumberFormatException ex){throw new cn.jia.agent.position.PositionSkillException(400,"BAD_REQUEST");}}
    private static int integer(Map<?,?> m,String k){long v=number(m,k);require(v<=Integer.MAX_VALUE,400,"BAD_REQUEST");return (int)v;}
    @PostMapping("/skill-products/lookup") public ResponseEntity<?> lookup(@PathVariable String e,@PathVariable String r,@RequestBody JsonRequest<Input> req,Authentication auth){
        var c=call(auth,e,r,req);var p=c.input().payload();require(p!=null,400,"BAD_REQUEST");return ok(authorized(c,true,true,s->{var g=subject(c,s).grant();return p.containsKey("productId")?products.readProduct(s,g,text(p,"productId")):products.listProducts(s,g,integer(p,"offset"),integer(p,"limit"));}));
    }
    @PostMapping("/skill-package-files/lookup") public ResponseEntity<?> files(@PathVariable String e,@PathVariable String r,@RequestBody JsonRequest<Input> req,Authentication auth){
        var c=call(auth,e,r,req);var p=c.input().payload();var a=authorized(c,true,true,s->subject(c,s));var result=products.readPackage(a.scope(),a.grant(),text(p,"productVersionId"),text(p,"path"));
        return ok(authorized(c,true,true,s->{require(a.equals(subject(c,s)),403,"POSITION_AUTHORITY_CHANGED");return result;}));
    }
    private ResponseEntity<?> metadata(Call<Input> c,boolean receipt){var p=intent(c.input(),"productId","name","description","expectedProductVersion");return change(c,"skill-product-metadata",true,p,receipt,()->s->{
        var v=products.updateMetadata(s,subject(c,s).grant(),new SkillPackagePublicationService.UpdateMetadata(text(p,"productId"),text(p,"name"),text(p,"description"),number(p,"expectedProductVersion")));
        return Map.of("productId",v.productId(),"name",v.name(),"description",v.description(),"productVersion",Long.toString(v.productVersion()),"disposition",v.disposition());});}
    private ResponseEntity<?> retire(Call<Input> c,boolean receipt){var p=intent(c.input(),"productId","expectedProductVersion");return change(c,"skill-product-retirements",true,p,receipt,()->s->{
        var v=products.retireProduct(s,subject(c,s).grant(),new SkillPackagePublicationService.RetireProduct(text(p,"productId"),number(p,"expectedProductVersion")));
        return Map.of("productId",v.productId(),"status",v.status(),"productVersion",Long.toString(v.productVersion()),"disposition",v.disposition());});}
    private ResponseEntity<?> publish(Call<Input> c,boolean receipt){var p=intent(c.input(),"productId","productVersionId","name","description","skillKey","skillVersion","priceWen","approvedPermissionsManifest","expectedProductVersion","files");return change(c,"skill-product-versions",true,p,receipt,()->{
        var a=authorized(c,true,true,s->subject(c,s));require(p.get("files") instanceof List<?>,400,"BAD_REQUEST");var files=new ArrayList<SkillPublicationToolArchive.File>();
        for(var row:(List<?>)p.get("files")){require(row instanceof Map<?,?>,400,"BAD_REQUEST");var f=(Map<?,?>)row;files.add(new SkillPublicationToolArchive.File(text(f,"path"),text(f,"content"),text(f,"encoding")));}
        var bytes=SkillPublicationToolArchive.encode(files);String digest;try{digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        var command=new SkillPackagePublicationService.Publish(text(p,"productId"),text(p,"productVersionId"),text(p,"name"),text(p,"description"),text(p,"skillKey"),text(p,"skillVersion"),number(p,"priceWen"),"NONE",text(p,"approvedPermissionsManifest"),digest,null,number(p,"expectedProductVersion"),null);
        var prepared=products.prepare(a.scope(),a.grant(),command,bytes);return s->{require(prepared.matches(s,subject(c,s).grant()),403,"POSITION_AUTHORITY_CHANGED");var v=products.commitPrepared(prepared);
        return Map.of("productId",v.productId(),"productVersionId",v.productVersionId(),"productVersion",Long.toString(v.productVersion()),"packageSha256",v.packageSha256(),"disposition",v.disposition());};});}
    @ExceptionHandler(SkillMarketplaceException.class) public ResponseEntity<?> marketplaceFailure(SkillMarketplaceException f){return ResponseEntity.status(f.status()).header("Cache-Control","private, no-store").body(cn.jia.core.entity.JsonResult.failure(f.code(),"Marketplace operation unavailable"));}
    @PostMapping("/skill-product-versions/commit") public ResponseEntity<?> publishCommit(@PathVariable String e,@PathVariable String r,@RequestBody JsonRequest<Input> req,Authentication auth){return publish(call(auth,e,r,req),false);}
    @PostMapping("/skill-product-versions/receipt") public ResponseEntity<?> publishReceipt(@PathVariable String e,@PathVariable String r,@RequestBody JsonRequest<Input> req,Authentication auth){return publish(call(auth,e,r,req),true);}
    @PostMapping("/skill-product-metadata/commit") public ResponseEntity<?> metadataCommit(@PathVariable String e,@PathVariable String r,@RequestBody JsonRequest<Input> req,Authentication auth){return metadata(call(auth,e,r,req),false);}
    @PostMapping("/skill-product-metadata/receipt") public ResponseEntity<?> metadataReceipt(@PathVariable String e,@PathVariable String r,@RequestBody JsonRequest<Input> req,Authentication auth){return metadata(call(auth,e,r,req),true);}
    @PostMapping("/skill-product-retirements/commit") public ResponseEntity<?> retireCommit(@PathVariable String e,@PathVariable String r,@RequestBody JsonRequest<Input> req,Authentication auth){return retire(call(auth,e,r,req),false);}
    @PostMapping("/skill-product-retirements/receipt") public ResponseEntity<?> retireReceipt(@PathVariable String e,@PathVariable String r,@RequestBody JsonRequest<Input> req,Authentication auth){return retire(call(auth,e,r,req),true);}
}
