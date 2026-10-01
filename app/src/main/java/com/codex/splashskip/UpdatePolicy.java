package com.codex.splashskip;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Public release source and version rules; no account tokens are needed in the APK. */
final class UpdatePolicy {
    static String repository(String input) {
        String value = input.trim();
        if (value.isEmpty()) return "";
        if (value.startsWith("https://")) {
            URI url;
            try { url = URI.create(value); } catch (IllegalArgumentException error) { throw new IllegalArgumentException("请输入有效的 GitHub 仓库地址"); }
            if (!"github.com".equalsIgnoreCase(url.getHost()) || url.getUserInfo() != null || url.getQuery() != null ||
                    url.getFragment() != null || url.getPort() != -1) throw new IllegalArgumentException("只支持 https://github.com/所有者/仓库");
            value = url.getPath().replaceFirst("^/", "");
        }
        value = value.replaceFirst("/$", "").replaceFirst("\\.git$", "");
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}"))
            throw new IllegalArgumentException("格式：所有者/仓库，或完整 GitHub HTTPS 地址");
        return value;
    }
    static int[] version(String input) {
        Matcher matcher = Pattern.compile("^[vV]?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:\\.(0|[1-9][0-9]*))?(?:\\.(0|[1-9][0-9]*))?$").matcher(input.trim());
        if (!matcher.matches()) throw new IllegalArgumentException("发布标签必须为稳定版本号，例如 v0.4.0");
        int[] numbers = new int[4];
        try { for (int i = 0; i < 4; i++) numbers[i] = matcher.group(i + 1) == null ? 0 : Integer.parseInt(matcher.group(i + 1)); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("版本号超出范围"); }
        return numbers;
    }
    static boolean newer(String candidate, String installed) {
        int[] a = version(candidate), b = version(installed);
        for (int i = 0; i < a.length; i++) { if (a[i] != b[i]) return a[i] > b[i]; }
        return false;
    }
    static String releaseUrl(String repo, String value, boolean download) {
        String prefix = "https://github.com/" + repository(repo) + (download ? "/releases/download/" : "/releases/tag/");
        if (!value.startsWith(prefix)) return "";
        try {
            URI url = URI.create(value);
            return url.getUserInfo() == null && url.getPort() == -1 && url.getQuery() == null && url.getFragment() == null ? value : "";
        } catch (IllegalArgumentException error) { return ""; }
    }
}
