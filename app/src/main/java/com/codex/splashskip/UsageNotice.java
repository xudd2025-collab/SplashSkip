package com.codex.splashskip;

/** Stable local acknowledgement survives normal app updates. */
final class UsageNotice {
    static final String ACKNOWLEDGED="usage_notice_acknowledged";
    static final String TEXT="本软件通过系统无障碍接口识别控件并模拟点击，不直接修改目标应用的账号或业务数据。AI 识别在手机本地完成；防摇一摇通过授权接口临时暂停运动传感器。\n\n"+
            "自动操作可能误触，或触发目标应用的账号限制、封禁，进而造成财产损失。请遵守目标应用的用户协议，自行评估使用风险。\n\n"+
            "在法律允许的范围内，开发者不承担因使用本软件产生的账号封禁或财产损失责任；依法不得免除的责任除外。";
}
