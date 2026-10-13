package com.lifemoments.reader;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.view.animation.LinearInterpolator;

/** Native counterpart of desktop LoadingScreen: throw, three diminishing bounces, logo expansion. */
final class LaunchScreen extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable logo;
    private final ValueAnimator timeline=ValueAnimator.ofFloat(0,1.42f);
    private float seconds=0;
    private boolean ready=false,finished=false,closing=false;
    private String status="正在读取本机索引…";
    private final boolean animated=ValueAnimator.areAnimatorsEnabled();
    LaunchScreen(Context context){
        super(context);logo=context.getDrawable(R.drawable.app_icon);setClickable(true);setFocusable(true);
        setContentDescription("生活点滴正在启动");
        timeline.setDuration(1420);timeline.setInterpolator(new LinearInterpolator());
        timeline.addUpdateListener(value->{seconds=(float)value.getAnimatedValue();invalidate();if(seconds>=1.42f){finished=true;finishIfReady();}});
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();if(animated)timeline.start();else{seconds=1.42f;finished=true;invalidate();finishIfReady();}}
    void complete(){ready=true;status="图库已就绪";finishIfReady();}
    void status(String text){status=text;invalidate();}
    private void finishIfReady(){
        if(!ready||!finished||closing)return;closing=true;
        if(!animated){setVisibility(GONE);return;}
        animate().alpha(0).setDuration(160).setInterpolator(new PathInterpolator(.22f,1,.36f,1)).withEndAction(()->setVisibility(GONE)).start();
    }
    @Override protected void onDetachedFromWindow(){timeline.cancel();animate().cancel();super.onDetachedFromWindow();}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);renderAt(canvas,seconds);}
    private static float clamp(float value){return Math.max(0,Math.min(1,value));}
    private static float ease(float value){return 1-(float)Math.pow(1-clamp(value),3);}
    private static float lerp(float a,float b,float t){return a+(b-a)*t;}
    void renderAt(Canvas canvas,float time){
        float d=getResources().getDisplayMetrics().density,cx=getWidth()/2f,cy=getHeight()*.41f,ground=cy+34*d;
        canvas.drawColor(Color.rgb(245,245,247));float x=cx,y=ground,r=13*d,morph=clamp((time-1.13f)/.29f);
        if(time<.48f){float t=clamp(time/.48f);x=lerp(Math.min(76*d,getWidth()*.09f),cx,t);y=lerp(Math.max(62*d,getHeight()*.11f),ground,t)-4*Math.min(112*d,getHeight()*.19f)*t*(1-t);r=lerp(8*d,13*d,t);}
        else if(time<1.13f){float start,end,h;if(time<.76f){start=.48f;end=.76f;h=100*d;}else if(time<.97f){start=.76f;end=.97f;h=48*d;}else{start=.97f;end=1.13f;h=19*d;}float t=clamp((time-start)/(end-start));y=ground-4*h*t*(1-t);}
        if(morph>0)y=lerp(ground,cy,ease(morph));float height=Math.max(0,ground-y),shadowScale=1/(1+height/(130*d)),shadowWidth=lerp(36*d*shadowScale,94*d,ease(morph));
        paint.setColor(Color.argb(18,33,63,111));canvas.drawOval(x-shadowWidth/2,ground+20*d,x+shadowWidth/2,ground+28*d,paint);
        if(morph<1){float contact=Math.max(0,1-height/(14*d)),squash=time>=.48f&&time<1.13f?contact*.16f:0;paint.setColor(Color.argb(Math.round(255*(1-ease(morph))),37,113,255));canvas.drawOval(x-r*(1+squash),y-r*(1-squash),x+r*(1+squash),y+r*(1-squash),paint);}
        if(morph>0){float u=morph-1,back=1+2.70158f*u*u*u+1.70158f*u*u,side=lerp(26*d,116*d,back),ly=lerp(ground,cy,ease(morph));logo.setAlpha(Math.round(255*ease(morph)));logo.setBounds(Math.round(cx-side/2),Math.round(ly-side/2),Math.round(cx+side/2),Math.round(ly+side/2));logo.draw(canvas);}
        paint.setTextAlign(Paint.Align.CENTER);paint.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));paint.setTextSize(28*d);paint.setColor(Color.argb(Math.round(255*ease(morph)),29,29,31));canvas.drawText("生活点滴",cx,cy+96*d,paint);
        paint.setColor(Color.argb(Math.round(255*ease(morph)),227,238,255));canvas.drawRoundRect(cx-25*d,cy+110*d,cx+25*d,cy+132*d,11*d,11*d,paint);
        paint.setTextSize(12*d);paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));paint.setColor(Color.argb(Math.round(255*ease(morph)),0,106,231));canvas.drawText("Beta",cx,cy+125*d,paint);
        paint.setColor(Color.rgb(110,110,115));canvas.drawText(status,cx,cy+166*d,paint);
    }
}
