package com.codex.splashskip;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Fixed public routes for this repository only; no account credentials are sent. */
final class UpdateSources {
    private static final String RELEASE = "https://github.com/xudd2025-collab/SplashSkip/releases/download/";
    private static final String METADATA = "https://api.github.com/repos/xudd2025-collab/SplashSkip/releases/latest";
    private static final String[] PROXIES = {"https://gh-proxy.com/", "https://ghfast.top/"};
    static String[] metadata(boolean enabled) {
        return enabled ? new String[]{PROXIES[0]+METADATA,METADATA} : new String[]{METADATA};
    }
    static String[] packages(String api,String apk,boolean enabled) {
        List<String> routes=new ArrayList<>();
        if(enabled && !apk.isEmpty())for(String proxy:PROXIES)routes.add(proxy+apk);
        if(!api.isEmpty())routes.add(api);
        if(!apk.isEmpty())routes.add(apk);
        return routes.toArray(new String[0]);
    }
    static boolean proxy(URL url) {
        if(!"https".equals(url.getProtocol()) || url.getUserInfo()!=null || (url.getPort()!=-1 && url.getPort()!=443) ||
                url.getQuery()!=null || url.getRef()!=null)return false;
        boolean host=false;
        for(String route:PROXIES)if(route.equalsIgnoreCase("https://"+url.getHost()+"/"))host=true;
        if(!host)return false;
        String path=url.getPath();
        if(!path.startsWith("/"+RELEASE))return false;
        String asset=path.substring(RELEASE.length()+1);
        return asset.matches("[vV]?[0-9]+\\.[0-9]+\\.[0-9]+/SplashSkip[A-Za-z0-9._-]*\\.apk");
    }
    static String label(URL url) {
        if(url.getHost().equalsIgnoreCase("gh-proxy.com"))return "加速线路 1";
        if(url.getHost().equalsIgnoreCase("ghfast.top"))return "加速线路 2";
        return "官方直连";
    }
}
