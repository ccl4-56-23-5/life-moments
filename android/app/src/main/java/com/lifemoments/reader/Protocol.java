package com.lifemoments.reader;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Pure protocol rules, shared by the reader and JVM regression tests. */
public final class Protocol {
    public static String path(String input) {
        if (input == null || !input.startsWith("/") || input.length() > 4096
                || input.indexOf('\\') >= 0 || input.indexOf('\0') >= 0
                || input.indexOf('\r') >= 0 || input.indexOf('\n') >= 0)
            throw new IllegalArgumentException("请输入以 / 开头的完整网盘路径。");
        String normalized = input.length() > 1 && input.endsWith("/") ? input.substring(0, input.length()-1) : input;
        for (String part : normalized.substring(1).split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals(".."))
                throw new IllegalArgumentException("网盘路径不能含空目录、. 或 ..。");
        return normalized;
    }
    public static boolean within(String file, String folder) {
        return file.equals(folder) || file.startsWith(folder + "/");
    }
    public static boolean downloadHost(String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(java.util.Locale.ROOT);
            for (String domain : new String[]{"baidu.com", "baidupcs.com", "bcebos.com", "bdstatic.com"})
                if (host.equals(domain) || host.endsWith("." + domain)) return true;
        } catch (Exception ignored) { }
        return false;
    }
    public static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format("%02x", b & 255));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static String message(int errno) {
        switch (errno) {
            case 20011: return "应用的授权用户数已达限额，请检查百度应用配额。";
            case 20012: return "百度接口已触发频控，请稍后再试或申请配额。";
            case 20013: return "应用没有这个接口的访问权限，请检查百度控制台。";
            case 20020: return "云端目录不在百度允许的访问范围内，请检查目录授权。";
            case -6: case 111: return "百度授权无效或过期，请重新连接。";
            case -9: case 31066: return "云端文件不存在，请检查备份和完整路径。";
            default: return "百度接口返回错误（" + errno + "），请检查目录授权及应用权限。";
        }
    }
}
