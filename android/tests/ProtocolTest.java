package com.lifemoments.reader;
public final class ProtocolTest {
    private static int checks=0;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("Check "+checks);}
    private static void invalid(String path){boolean failed=false;try{Protocol.path(path);}catch(IllegalArgumentException e){failed=true;}check(failed);}
    public static void main(String[] args){
        check(Protocol.path("/apps/生活点滴/索引/").equals("/apps/生活点滴/索引"));
        for(String bad:new String[]{"","/","relative/path","/apps/a/../b","/apps//a","/apps/a/./b","/apps/a\\b","/apps/a\n"})invalid(bad);
        check(Protocol.within("/apps/a/one.jpg","/apps/a"));check(!Protocol.within("/apps/ab/one.jpg","/apps/a"));
        for(String good:new String[]{"https://pan.baidu.com/file","https://d.pcs.baidu.com/file","https://c.pcs.baidu.com/file","https://example.baidupcs.com/file","https://a.bcebos.com/file"})check(Protocol.downloadHost(good));
        for(String bad:new String[]{"http://pan.baidu.com/file","https://baidu.com.evil.test/file","https://evil.test/?host=baidu.com","https://user@pan.baidu.com/file","https://pan.baidu.com:8443/file","javascript:alert(1)","https://pan.baidu.com@evil.test/"})check(!Protocol.downloadHost(bad));
        check(!Protocol.digest("A|/2022/x.jpg|100").equals(Protocol.digest("B|/2022/x.jpg|100")));
        check(!Protocol.digest("A|/2022/x.jpg|100").equals(Protocol.digest("A|/2023/x.jpg|100")));
        check(Protocol.message(20012).contains("频控"));check(Protocol.message(20013).contains("权限"));check(Protocol.message(20011).contains("用户"));
        System.out.println("PASS: "+checks+" native protocol checks");
    }
}
