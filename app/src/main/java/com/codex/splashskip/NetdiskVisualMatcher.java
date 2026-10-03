package com.codex.splashskip;

import static com.codex.splashskip.BilibiliVisualMatcher.*;

/** Match the complete scene; a cross inside an embedded screenshot is never a target. */
final class NetdiskVisualMatcher {
    static final String PROMO = "netdisk-coupon-close", CUSTOMER = "netdisk-screenshot-close";
    private final Template promoCross, couponTitle, customerCross, customerLabel;
    NetdiskVisualMatcher(Template promoCross, Template couponTitle, Template customerCross, Template customerLabel) {
        this.promoCross = promoCross; this.couponTitle = couponTitle;
        this.customerCross = customerCross; this.customerLabel = customerLabel;
    }
    Hit find(Frame f, boolean promo, boolean customer) {
        if (f.height < f.width * 1.65f || f.height > f.width * 2.65f) return null;
        // The screenshot helper may contain a miniature copy of the coupon dialog.
        Hit result = customer ? customer(f) : null;
        return result != null ? result : promo ? promo(f) : null;
    }
    private Hit promo(Frame f) {
        for(float[] close:UiFeatureSearch.all(f,promoCross,.82f)) {
            float[] title=UiFeatureSearch.find(f,couponTitle,0,close[1]+promoCross.height,close[0],Math.min(f.height,close[1]+f.height*.40f),.80f);
            if(title==null || grayAt(f,title[0],title[1]+35)<205)continue;
            return f.hit(PROMO,close[0],close[1],Math.min(close[2],title[2]));
        }
        return null;
    }
    private Hit customer(Frame f) {
        for(float[] close:UiFeatureSearch.all(f,customerCross,.76f)) {
            float[] label=UiFeatureSearch.find(f,customerLabel,close[0]-f.width*.40f,close[1]+customerCross.height,Math.min(f.width,close[0]+customerCross.width),Math.min(f.height,close[1]+f.height*.40f),.78f);
            if(label==null || grayAt(f,label[0],label[1]+23)>70 || grayAt(f,label[0]-customerLabel.width*.75f,label[1])>70)continue;
            return f.hit(CUSTOMER,close[0],close[1],Math.min(close[2],label[2]));
        }
        return null;
    }

}
