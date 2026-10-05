package com.codex.splashskip;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.*;
import java.util.*;

/** Full height, local-only viewer of frozen controls and their click relationships. */
final class NativeBoundsReview extends Dialog {
    private static final int INK=Color.rgb(40,43,60),MUTED=Color.rgb(104,111,131),ACCENT=NativeBoundsView.CONTROL_COLOR;
    private final JSONObject frame;
    private final JSONArray data;
    private final NativeBoundsInspector model;
    private final NativeBoundsView preview;
    private final TextView detail,summary,parent,children,overlap;
    private final ScrollView detailsScroll;
    private final TextView[] filters=new TextView[3];
    NativeBoundsReview(Context context,JSONObject frame,String appName) {
        super(context);this.frame=frame;requestWindowFeature(Window.FEATURE_NO_TITLE);
        JSONArray list=frame.optJSONArray("tree_nodes");
        if(frame.optBoolean("scope_only") && frame.optJSONObject("ad_scope")!=null)
            list=frame.optJSONObject("ad_scope").optJSONArray("tree_nodes");
        data=list==null?new JSONArray():list;
        List<ControlTree.Node> nodes=new ArrayList<>();
        for(int i=0;i<data.length();i++) {
            JSONObject n=data.optJSONObject(i);if(n==null)n=new JSONObject();
            JSONArray b=n.optJSONArray("bounds");int[] box=new int[4];
            if(b!=null)for(int j=0;j<4;j++)box[j]=b.optInt(j);
            nodes.add(new ControlTree.Node(n.optInt("parent",-1),n.optInt("child_count"),box,n.optBoolean("clickable"),
                n.optString("role"),n.optString("identity"),n.optBoolean("visible",true),n.optString("class"),n.optString("id"),
                n.optBoolean("skip_countdown"),nullable(n,"enabled"),nullable(n,"declared_clickable"),nullable(n,"on_screen"),
                n.optBoolean("explicit_skip_ad"),n.optBoolean("navigation_disclosure")));
        }
        model=new NativeBoundsInspector(new ControlTree.Snapshot(frame.optString("package"),frame.optLong("epoch"),frame.optLong("captured_uptime"),
            Math.max(1,frame.optInt("width")),Math.max(1,frame.optInt("height")),nodes,
            NativeBoundsInspector.displayedComplete(frame.optBoolean("scope_only"),frame.optBoolean("tree_complete"),frame.optBoolean("scope_complete"))));
        LinearLayout body=column();body.setPadding(dp(14),dp(10),dp(14),dp(10));body.setBackgroundColor(Color.WHITE);
        LinearLayout header=row();TextView title=text(appName+" · 控件边框",17,INK);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1));header.addView(button("返回",this::dismiss),new LinearLayout.LayoutParams(dp(60),dp(40)));body.addView(header);
        summary=text(frameSummary(),11,MUTED);summary.setPadding(0,dp(5),0,dp(6));body.addView(summary);
        JSONObject scope=frame.optJSONObject("ad_scope");
        if(!frame.optBoolean("scope_only") && scope!=null && scope.optJSONArray("tree_nodes")!=null) {
            body.addView(button("查看单独保存的广告区域（"+scope.optJSONArray("tree_nodes").length()+" 控件）",()->{
                try {
                    JSONObject scoped=new JSONObject(frame.toString());scoped.remove("ad_scope");
                    JSONArray scopeReasons=new JSONArray(NativeBoundsInspector.scopeReasons(scope.optBoolean("source_tree_complete"),scope.optBoolean("archive_truncated")));
                    scoped.put("tree_nodes",scope.optJSONArray("tree_nodes")).put("scope_only",true)
                        .put("width",scope.optInt("width",frame.optInt("width"))).put("height",scope.optInt("height",frame.optInt("height")))
                        .put("scope_complete",scope.optBoolean("tree_complete")).put("tree_complete",false)
                        .put("truncation_reasons",scopeReasons);
                    new NativeBoundsReview(getContext(),scoped,appName+" · 广告区域").show();
                }catch(JSONException ignored){/* Malformed archive cannot create a derived viewer. */}
            }),new LinearLayout.LayoutParams(-1,dp(34)));
        }
        TextView legend=text("绿色：可点击  紫色：关闭/跳过  橙色：广告线索\n红色：选中  蓝色虚线：可点击父节点",11,MUTED);body.addView(legend);
        LinearLayout filterRow=row();String[] names={"全部边框","仅可点击","关闭/跳过"};
        preview=new NativeBoundsView(context,model,this::selected);
        for(int i=0;i<3;i++){final int mode=i;filters[i]=button(names[i],()->{preview.setFilter(mode);updateFilters();});filterRow.addView(filters[i],new LinearLayout.LayoutParams(0,dp(38),1));}
        body.addView(filterRow);updateFilters();
        LinearLayout tools=row();
        tools.addView(button("节点列表",()->showNodes(-1)),new LinearLayout.LayoutParams(0,dp(36),1));
        tools.addView(button("放大选中",()->{if(preview.selection()>=0)preview.focusSelection();}),new LinearLayout.LayoutParams(0,dp(36),1));
        tools.addView(button("复位",preview::reset),new LinearLayout.LayoutParams(0,dp(36),1));
        TextView expand=button("展开图",null);tools.addView(expand,new LinearLayout.LayoutParams(0,dp(36),1));body.addView(tools);
        body.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        TextView gesture=text("点框选控件 · 双指缩放 · 放大后拖动",10,MUTED);gesture.setGravity(Gravity.CENTER);body.addView(gesture);
        LinearLayout navigation=row();
        parent=button("父节点",()->{int at=preview.selection();if(at>=0)preview.selectIndex(model.tree.nodes.get(at).parent,false);});
        children=button("子节点",()->showNodes(preview.selection()));overlap=button("重叠框",preview::nextOverlap);
        navigation.addView(parent,new LinearLayout.LayoutParams(0,dp(38),1));navigation.addView(children,new LinearLayout.LayoutParams(0,dp(38),1));navigation.addView(overlap,new LinearLayout.LayoutParams(0,dp(38),1));body.addView(navigation);
        detail=text("请选择一个边框，或从节点列表查看可点击控件。\n这里展示保存时的状态；可点击属性与广告自动关闭条件分别显示。",12,INK);
        detail.setTextIsSelectable(true);detail.setLineSpacing(dp(2),1);detail.setPadding(dp(5),dp(8),dp(5),dp(12));
        detailsScroll=new ScrollView(context);detailsScroll.addView(detail,new ScrollView.LayoutParams(-1,-2));
        int detailHeight=Math.min(dp(185),(int)(getContext().getResources().getDisplayMetrics().heightPixels*.25));
        body.addView(detailsScroll,new LinearLayout.LayoutParams(-1,detailHeight));
        expand.setOnClickListener(v->{boolean expanded=detailsScroll.getVisibility()==View.VISIBLE;
            detailsScroll.setVisibility(expanded?View.GONE:View.VISIBLE);summary.setVisibility(expanded?View.GONE:View.VISIBLE);expand.setText(expanded?"收起图":"展开图");});
        setContentView(body);setCanceledOnTouchOutside(false);
        Window window=getWindow();
        if(window!=null){window.setBackgroundDrawableResource(android.R.color.white);window.setStatusBarColor(Color.WHITE);window.setNavigationBarColor(Color.WHITE);
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            if(Build.VERSION.SDK_INT>=30)window.setDecorFitsSystemWindows(true);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);}
        parent.setEnabled(false);children.setEnabled(false);overlap.setEnabled(false);
    }
    @Override protected void onStart(){super.onStart();if(getWindow()!=null)getWindow().setLayout(-1,-1);}
    private void updateFilters(){for(int i=0;i<filters.length;i++)if(filters[i]!=null){filters[i].setTextColor(i==preview.filter()?Color.WHITE:ACCENT);filters[i].setBackgroundColor(i==preview.filter()?ACCENT:Color.rgb(244,241,252));}}
    private String frameSummary() {
        String time=new java.text.SimpleDateFormat("MM-dd HH:mm:ss",Locale.getDefault()).format(new Date(frame.optLong("captured_wall_time")));
        int clickable=0,controls=0;for(ControlTree.Node n:model.tree.nodes){if(n.clickable)clickable++;if(UiControlPolicy.isControl(n.role))controls++;}
        String scope=frame.optBoolean("scope_only")?"广告区域 · "+(frame.optBoolean("scope_complete")?"完整":"部分"):
            "全页 · "+(frame.optBoolean("tree_complete")?"完整":"未读全");
        StringBuilder s=new StringBuilder(time+" · "+scope+" · "+data.length()+" 控件\n可点击 "+clickable+" · 动作候选 "+controls+" · "+frame.optInt("width")+"×"+frame.optInt("height"));
        s.append("\n本次扫描：").append(NativeBoundsInspector.result(frame.optString("result")));
        JSONArray reasons=frame.optJSONArray("truncation_reasons");
        if(reasons!=null && reasons.length()>0){s.append("\n记录范围：");for(int i=0;i<reasons.length();i++){if(i>0)s.append("、");s.append(NativeBoundsInspector.reason(reasons.optString(i)));}}
        return s.toString();
    }
    private void selected(int at) {
        if(at<0){detail.setText("请选择当前筛选中的边框，或从节点列表查看控件。");parent.setEnabled(false);children.setEnabled(false);
            overlap.setText("重叠框 0");overlap.setEnabled(false);return;}
        ControlTree.Node n=model.tree.nodes.get(at);JSONObject raw=data.optJSONObject(at);
        int nearest=model.nearestClickable(at),compact=model.compactTarget(at);
        StringBuilder s=new StringBuilder("编号 #"+at+" · "+role(n.role)+"\n有效可点击："+(n.clickable?"是":"否"));
        s.append(" · 屏幕可见：").append(flag(n.onScreen)).append("\n系统声明可点击：").append(flag(n.declaredClickable)).append(" · 已启用：").append(flag(n.enabled));
        if(nearest==at)s.append("\n点击关系：该节点自身可点击");
        else if(nearest>=0)s.append("\n点击关系：父级 #").append(nearest).append(" 可点击（图中蓝色虚线）");
        else s.append("\n点击关系：已保存层级中未找到可点击父节点");
        if(UiControlPolicy.isControl(n.role)) {
            s.append("\n自动关闭条件：动作候选");
            if(compact<0)s.append("；未找到符合小按钮范围的可点击目标，触摸还需独立广告校验");
            else s.append("；小按钮点击关系匹配 #").append(compact).append("，仍需广告场景与实时校验");
            if(!model.tree.complete)s.append("；当前展示的记录不完整");
        }else s.append("\n自动关闭条件：未识别为明确跳过/关闭标签");
        if(n.skipCountdown)s.append("\n倒计时：明确跳过标签带倒计时");
        if(n.explicitSkipAd)s.append("\n广告语义：明确的“跳过广告”整句");
        if(n.navigationDisclosure)s.append("\n广告语义：明确的详情页或第三方跳转提示");
        List<Integer> descendants=model.children(at);
        s.append("\n父节点：").append(n.parent<0?"无":"#"+n.parent).append(" · 子节点：已保存 ").append(descendants.size()).append(" / 声明 ").append(n.children);
        s.append("\n层级：");List<Integer> path=model.path(at);for(int i=0;i<path.size();i++){if(i>0)s.append(" ← ");s.append('#').append(path.get(i));}
        s.append("\n边框：").append(Arrays.toString(n.box)).append(" · 尺寸 ").append(n.box[2]-n.box[0]).append('×').append(n.box[3]-n.box[1]);
        s.append("\n类名：").append(n.className.isEmpty()?"未保存":n.className).append("\n资源 ID：").append(n.viewId.isEmpty()?"应用未提供":n.viewId);
        if(raw!=null && !raw.optString("label").isEmpty())s.append("\n标签：").append(raw.optString("label"));
        s.append("\n原始角色：").append(n.role.isEmpty()?"无":n.role).append("\n原始扫描结果：").append(frame.optString("result"));
        detail.setText(s);detailsScroll.scrollTo(0,0);
        parent.setEnabled(n.parent>=0 && n.parent<model.tree.nodes.size());children.setEnabled(!descendants.isEmpty());
        overlap.setText("重叠框 "+preview.overlapCount());overlap.setEnabled(preview.overlapCount()>1);
    }
    private void showNodes(int parentIndex) {
        if(parentIndex<0 && preview.selection()<0 && data.length()==0)return;
        List<Integer> indices=parentIndex<0?new ArrayList<>():model.children(parentIndex);
        if(parentIndex<0) {
            for(int i=0;i<data.length();i++)if(preview.filter()==NativeBoundsInspector.ALL || model.shown(i,preview.filter()))indices.add(i);
            indices.sort(Comparator.comparingInt((Integer i)->UiControlPolicy.isControl(model.tree.nodes.get(i).role)?0:model.tree.nodes.get(i).clickable?1:2).thenComparingInt(i->i));
        }
        if(indices.isEmpty()){new AppDialog.Builder(getContext()).setTitle("控件列表").setMessage("当前筛选没有控件，可切换到全部边框。").setPositiveButton("返回",null).show();return;}
        String[] labels=new String[indices.size()];for(int i=0;i<indices.size();i++) {
            int at=indices.get(i);ControlTree.Node n=model.tree.nodes.get(at);JSONObject raw=data.optJSONObject(at);
            String caption=raw==null?"":raw.optString("label");if(caption.isEmpty())caption=n.viewId.isEmpty()?n.className:n.viewId;
            labels[i]="#"+at+" · "+(n.clickable?"可点击 · ":"")+role(n.role)+(n.visible?"":" · 隐藏或未启用")+"\n"+caption;
        }
        new AppDialog.Builder(getContext()).setTitle((parentIndex<0?"控件列表":"#"+parentIndex+" 的子节点")+" · "+indices.size()+" 个")
            .setItems(labels,(dialog,which)->preview.selectIndex(indices.get(which),true)).setPositiveButton("返回",null).show();
    }
    private static Boolean nullable(JSONObject n,String key){return !n.has(key)||n.isNull(key)?null:n.optBoolean(key);}
    private static String flag(Boolean value){return value==null?"未保存（旧记录）":value?"是":"否";}
    private static String role(String role){return UiControlPolicy.SKIP.equals(role)?"跳过候选":UiControlPolicy.CLOSE.equals(role)?"关闭候选":
        UiControlPolicy.CLOSE_AD.equals(role)?"关闭广告候选":"ad".equals(role)?"广告线索":"prompt".equals(role)?"提示线索":"nav".equals(role)?"页面导航":"普通控件";}
    private LinearLayout column(){LinearLayout v=new LinearLayout(getContext());v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(getContext());v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private TextView text(String value,int size,int color){TextView v=new TextView(getContext());v.setText(value);v.setTextSize(size);v.setTextColor(color);return v;}
    private TextView button(String value,Runnable action){TextView v=text(value,12,ACCENT);v.setGravity(Gravity.CENTER);v.setFocusable(true);v.setBackgroundColor(Color.rgb(244,241,252));if(action!=null)v.setOnClickListener(w->action.run());return v;}
    private int dp(int value){return (int)(value*getContext().getResources().getDisplayMetrics().density+.5f);}
}
