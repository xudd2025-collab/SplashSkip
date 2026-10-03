package com.codex.splashskip;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Attribution is bundled with the APK and has no user preference to hide it. */
final class ProjectNotice {
    static final String SOURCE_URL="https://github.com/xudd2025-collab/SplashSkip";
    static final String CAPTION="官方免费发布 · 请勿付费购买";
    static final String SUMMARY="原作者：xudd2025-collab\n官方源码："+SOURCE_URL+
            "\n\n官方免费发布。自 v0.6.32 起，原创部分采用源码可用许可，未经许可禁止售卖；分发时须保留作者、源码入口和许可说明。"+
            "\n\n这不是标准开源许可证。已按 Apache-2.0 发布的旧版和第三方依赖仍适用各自许可。";
    static String license(Context context) {
        try(InputStream in=context.getAssets().open("project_license.txt");ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096];int count;
            while((count=in.read(buffer))!=-1){if(out.size()+count>65536)throw new java.io.IOException("license too large");out.write(buffer,0,count);}
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }catch(java.io.IOException error){return "完整许可未能读取，请在官方源码仓库查看 LICENSE。\n\n"+SUMMARY;}
    }
}
