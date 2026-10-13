package com.lifemoments.reader;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/** Calls actual production payload/index methods. Android SDK classes are type references only. */
public final class TransferPayloadTest {
    private static int checks;
    private static final List<String> cases = new ArrayList<>();
    private interface Attempt { void run() throws Exception; }
    private static void check(boolean value, String label) { checks++; if(!value)throw new AssertionError(label); cases.add(label); }
    private static void reject(Attempt action, String label) throws Exception {
        boolean rejected=false;try{action.run();}catch(Exception expected){rejected=true;}check(rejected,label);
    }
    private static JSONObject copy(JSONObject value) { return new JSONObject(value.toString()); }
    private static byte[] zip(byte[] input) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(GZIPOutputStream out=new GZIPOutputStream(bytes)){out.write(input);}return bytes.toByteArray();
    }
    private static byte[] zip(JSONObject input) throws Exception { return zip(input.toString().getBytes(StandardCharsets.UTF_8)); }
    private static byte[] compressedZeros(int size) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] block=new byte[16384];
        try(GZIPOutputStream out=new GZIPOutputStream(bytes)){while(size>0){int next=Math.min(size,block.length);out.write(block,0,next);size-=next;}}
        return bytes.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        JSONObject auth = new JSONObject().put("key","fixture-key").put("secret","fixture-secret").put("folder","/apps/fixture/生活点滴索引")
                .put("root","/照片").put("session","fixture-session").put("accessToken","fixture-access-token")
                .put("refreshToken","fixture-refresh-token").put("expiresAt",1900000000000L);
        JSONObject reader = new JSONObject().put("wifiImages",true).put("thumbnailWarmupAuto",false).put("snapshotSession","fixture-session");
        JSONObject web = new JSONObject().put("schemaVersion",1).put("preferences",new JSONObject().put("sort","name-asc").put("group","month").put("albumSort","count"));
        JSONObject photo = new JSONObject().put("id",1).put("name","合照.jpg").put("cloudPath","/照片/2022/合照.jpg").put("relativePath","2022/合照.jpg").put("width",1000).put("height",700);
        JSONObject library = new JSONObject().put("schemaVersion",2).put("libraryId","fixture-library").put("cloudRoot","/照片").put("photos",new JSONArray().put(photo))
                .put("index",new JSONObject().put("people",new JSONArray().put("人物甲"))
                        .put("albums",new JSONArray().put(new JSONObject().put("name","fixture-album").put("ids",new JSONArray().put("1"))))
                        .put("profiles",new JSONObject().put("人物甲",new JSONObject().put("avatar",new JSONObject().put("id","1").put("rect",new JSONArray().put(.1).put(.2).put(.3).put(.4)))))
                        .put("assignments",new JSONObject().put("1",new JSONArray().put("人物甲"))));
        TransferPayload original = new TransferPayload(reader,web,auth,library), decoded = TransferPayload.decode(original.encode());
        check(decoded.credentials().getString("accessToken").equals("fixture-access-token"),"Token survives payload roundtrip");
        check(decoded.credentials().getString("secret").equals("fixture-secret")&&decoded.credentials().getString("refreshToken").equals("fixture-refresh-token"),"Complete connection survives roundtrip");
        check(decoded.readerSettings().length()==5&&decoded.readerSettings().getString("lastSync").isEmpty()&&!decoded.readerSettings().getBoolean("thumbnailWarmupAuto"),"Exactly five reader fields and defaults");
        check(decoded.webSettings().getJSONObject("preferences").getString("albumSort").equals("count"),"UI preferences survive roundtrip");
        check(decoded.library().getJSONArray("photos").getJSONObject(0).getString("cloudPath").equals("/照片/2022/合照.jpg"),"Full Unicode cloud path preserved");
        check(decoded.library().getJSONObject("index").getJSONObject("profiles").getJSONObject("人物甲").getJSONObject("avatar").getJSONArray("rect").length()==4,"Face avatar metadata preserved");
        JSONObject authCopy=decoded.credentials();authCopy.put("secret","changed");check(decoded.credentials().getString("secret").equals("fixture-secret"),"Credential getter makes defensive copy");
        JSONObject readerCopy=decoded.readerSettings();readerCopy.put("wifiImages",false);check(decoded.readerSettings().getBoolean("wifiImages"),"Reader getter makes defensive copy");
        JSONObject webCopy=decoded.webSettings();webCopy.getJSONObject("preferences").put("sort","time-asc");check(decoded.webSettings().getJSONObject("preferences").getString("sort").equals("name-asc"),"Web getter makes deep defensive copy");
        JSONObject libraryCopy=decoded.library();libraryCopy.getJSONArray("photos").getJSONObject(0).put("cloudPath","/changed");check(decoded.library().getJSONArray("photos").getJSONObject(0).getString("cloudPath").equals("/照片/2022/合照.jpg"),"Library getter makes deep defensive copy");
        auth.put("secret","source-mutated");check(original.credentials().getString("secret").equals("fixture-secret"),"Source mutation cannot alter stored payload");auth.put("secret","fixture-secret");
        check(new TransferPayload(new JSONObject(),new JSONObject(),new JSONObject(),null).library()==null,"Unconnected new device accepts empty connection and no library");
        reject(()->new TransferPayload(new JSONObject().put("webCacheGeneration",4),web,auth,library),"Internal reader preferences rejected");
        reject(()->new TransferPayload(new JSONObject().put("wifiImages","true"),web,auth,library),"Reader boolean type enforced");
        reject(()->new TransferPayload(new JSONObject().put("lastSync",42),web,auth,library),"Reader text type enforced");
        reject(()->new TransferPayload(new JSONObject().put("lastStatus","x".repeat(1025)),web,auth,library),"Reader text size enforced");
        reject(()->new TransferPayload(reader,copy(web).put("schemaVersion",2),auth,library),"Unsupported UI schema rejected");
        reject(()->new TransferPayload(reader,copy(web).put("schemaVersion","1"),auth,library),"UI schema numeric type enforced");
        reject(()->new TransferPayload(reader,copy(web).put("clientSecret","fixture"),auth,library),"Extra web envelope field rejected");
        for(String field:new String[]{"sort","group","albumSort"}){
            JSONObject invalid=copy(web);invalid.getJSONObject("preferences").put(field,"invalid");
            reject(()->new TransferPayload(reader,invalid,auth,library),"Unsupported "+field+" enum rejected");
        }
        JSONObject webExtra=copy(web);webExtra.getJSONObject("preferences").put("script","fixture");reject(()->new TransferPayload(reader,webExtra,auth,library),"Extra UI preference rejected");
        reject(()->new TransferPayload(reader,web,copy(auth).put("unknownSecret","fixture"),library),"Unknown credential field rejected");
        reject(()->new TransferPayload(reader,web,copy(auth).put("secret",false),library),"Credential text type enforced");
        reject(()->new TransferPayload(reader,web,copy(auth).put("expiresAt","later"),library),"Credential expiry number enforced");
        reject(()->new TransferPayload(reader,web,copy(auth).put("expiresAt",1.5),library),"Fractional expiry rejected");
        reject(()->new TransferPayload(reader,web,copy(auth).put("expiresAt",-1),library),"Negative expiry rejected");
        reject(()->new TransferPayload(reader,web,copy(auth).put("accessToken",""),library),"Present access token cannot be empty");
        JSONObject missing=copy(auth);missing.remove("secret");reject(()->new TransferPayload(reader,web,missing,library),"Connected credentials must be complete");
        reject(()->new TransferPayload(reader,web,copy(auth).put("folder","/private/索引"),library),"Index folder must remain inside apps directory");
        reject(()->new TransferPayload(reader,web,copy(auth).put("root","/照片/../other"),library),"Credential directory traversal rejected");
        reject(()->new TransferPayload(reader,web,copy(auth).put("root","/other"),library),"Index and authorized root binding enforced");
        reject(()->new TransferPayload(copy(reader).put("snapshotSession","other-session"),web,auth,library),"Index and credential session binding enforced");
        JSONObject envelope=new JSONObject().put("schemaVersion",1).put("reader",reader).put("web",web).put("credentials",auth).put("library",library);
        reject(()->TransferPayload.decode(zip(copy(envelope).put("schemaVersion",2))),"Transfer schema version enforced");
        reject(()->TransferPayload.decode(zip(copy(envelope).put("schemaVersion","1"))),"Transfer schema numeric type enforced");
        reject(()->TransferPayload.decode(zip(copy(envelope).put("unexpected","fixture"))),"Extra transfer envelope field rejected");
        reject(()->TransferPayload.decode(zip(copy(envelope).put("library","fixture"))),"Library object type enforced");
        reject(()->TransferPayload.decode(new byte[0]),"Empty wire payload rejected");
        reject(()->TransferPayload.decode(new byte[TransferPayload.MAX_WIRE+1]),"Wire limit checked before decompression");
        reject(()->TransferPayload.decode(new byte[]{1,2,3}),"Invalid GZIP rejected");
        reject(()->TransferPayload.inflate(compressedZeros(65537),65536),"Bounded production decompression rejects overflow");
        reject(()->TransferPayload.decode(compressedZeros(TransferPayload.MAX_PLAIN+1)),"Actual transfer decompression limit rejects compression bomb");
        reject(()->LibraryStore.validate(copy(library).put("schemaVersion",1).toString()),"Production index schema enforced");
        reject(()->LibraryStore.validate(copy(library).put("libraryId","").toString()),"Production index identity required");
        JSONObject duplicate=copy(library);duplicate.getJSONArray("photos").put(copy(photo));reject(()->LibraryStore.validate(duplicate.toString()),"Duplicate photo IDs rejected");
        JSONObject invalidPath=copy(library);invalidPath.getJSONArray("photos").getJSONObject(0).put("cloudPath","/照片/../file.jpg");reject(()->LibraryStore.validate(invalidPath.toString()),"Photo path traversal rejected");
        JSONObject badWidth=copy(library);badWidth.getJSONArray("photos").getJSONObject(0).put("width",0);reject(()->LibraryStore.validate(badWidth.toString()),"Invalid photo dimensions rejected");
        JSONObject badPeople=copy(library);badPeople.getJSONObject("index").put("people",new JSONArray().put(42));reject(()->LibraryStore.validate(badPeople.toString()),"People name types enforced");
        JSONObject badAssignments=copy(library);badAssignments.getJSONObject("index").put("assignments","fixture");reject(()->LibraryStore.validate(badAssignments.toString()),"People assignments object type enforced");
        JSONObject remote=copy(library);remote.getJSONArray("photos").getJSONObject(0).put("image","https://example.invalid/image").put("fullImage","file:///fixture").put("path","file:///fixture");
        JSONObject sanitized=LibraryStore.validate(remote.toString()).getJSONArray("photos").getJSONObject(0);
        check(!sanitized.has("image")&&!sanitized.has("fullImage")&&!sanitized.has("path"),"Index untrusted direct image URLs stripped");
        JSONObject rebound=LibraryStore.bindRoot(copy(library),"/数据备份/照片");
        check(rebound.getJSONArray("photos").getJSONObject(0).getString("cloudPath").equals("/数据备份/照片/2022/合照.jpg"),"Cloud directory prefix rebind preserves relative path");
        JSONObject outside=copy(library);outside.getJSONArray("photos").getJSONObject(0).put("cloudPath","/other/file.jpg");reject(()->LibraryStore.bindRoot(outside,"/数据备份/照片"),"Prefix rebind rejects original outside source root");
        reject(()->LibraryStore.validate("x".repeat(LibraryStore.MAX_INDEX+1)),"Actual index size limit checked before JSON parse");
        System.out.println("RESULT: "+new JSONObject().put("status","PASS").put("checks",checks).put("cases",new JSONArray(cases)));
        System.out.println("PASS: "+checks+" transfer payload checks");
    }
}
