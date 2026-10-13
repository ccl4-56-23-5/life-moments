package com.lifemoments.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;

/** Shared native surfaces use the same colors, type scale and spacing as the reader. */
final class NativeUi {
    static final int BACKGROUND=Color.rgb(251,251,253), SURFACE=Color.rgb(242,242,247);
    static final int TEXT=Color.rgb(32,33,37), MUTED=Color.rgb(124,125,133);
    static final int BLUE=Color.rgb(0,122,255), SELECTED=Color.rgb(231,241,255), LINE=Color.rgb(231,231,236);
    private NativeUi() { }
    static int dp(Context context,int value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
    static GradientDrawable rounded(Context context,int color,int radius,boolean border){
        GradientDrawable result=new GradientDrawable();result.setColor(color);result.setCornerRadius(dp(context,radius));
        if(border)result.setStroke(dp(context,1),LINE);return result;
    }
    private static RippleDrawable ripple(Context context,int color,int radius,int highlight){
        return new RippleDrawable(ColorStateList.valueOf(highlight),rounded(context,color,radius,false),rounded(context,Color.WHITE,radius,false));
    }
    static AlertDialog.Builder dialog(Activity activity){return new ReaderDialogBuilder(activity);}
    private static final class ReaderDialogBuilder extends AlertDialog.Builder {
        private final Activity activity;
        ReaderDialogBuilder(Activity activity){super(activity,R.style.ReaderDialogTheme);this.activity=activity;}
        @Override public AlertDialog.Builder setTitle(CharSequence title){
            TextView label=text(activity,title,19,TEXT);label.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
            label.setMaxLines(2);label.setEllipsize(TextUtils.TruncateAt.END);label.setPadding(dp(activity,24),dp(activity,22),dp(activity,24),dp(activity,10));
            super.setCustomTitle(label);return this;
        }
        @Override public AlertDialog create(){AlertDialog result=super.create();result.setOnShowListener(ignored->style(activity,result));return result;}
    }
    private static void style(Activity activity,AlertDialog dialog){
        Window window=dialog.getWindow();if(window==null)return;
        window.setBackgroundDrawable(rounded(activity,BACKGROUND,24,true));
        window.setDimAmount(.32f);window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        window.setLayout(dialogWidth(activity),WindowManager.LayoutParams.WRAP_CONTENT);
        View decor=window.getDecorView();if(Build.VERSION.SDK_INT>=29)decor.setForceDarkAllowed(false);decor.addOnLayoutChangeListener((view,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom)->{
            int width=dialogWidth(activity);if(window.getAttributes().width!=width)window.setLayout(width,WindowManager.LayoutParams.WRAP_CONTENT);
        });
        TextView message=dialog.findViewById(android.R.id.message);if(message!=null){message.setTextColor(MUTED);message.setTextSize(14);message.setLineSpacing(dp(activity,3),1.15f);}
        button(activity,dialog.getButton(AlertDialog.BUTTON_POSITIVE),true,false);
        button(activity,dialog.getButton(AlertDialog.BUTTON_NEGATIVE),false,false);
        button(activity,dialog.getButton(AlertDialog.BUTTON_NEUTRAL),false,true);
        Button primary=dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button fallback=primary!=null?primary:dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if(fallback!=null&&fallback.getParent() instanceof ViewGroup){ViewGroup panel=(ViewGroup)fallback.getParent();panel.setPadding(dp(activity,16),dp(activity,8),dp(activity,16),dp(activity,14));}
        ListView list=dialog.getListView();if(list!=null){list.setDivider(new android.graphics.drawable.ColorDrawable(LINE));list.setDividerHeight(dp(activity,1));list.setSelector(new android.graphics.drawable.ColorDrawable(SELECTED));list.setPadding(dp(activity,8),0,dp(activity,8),dp(activity,4));}
    }
    private static int dialogWidth(Activity activity){
        Rect usable=new Rect();activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(usable);
        int width=usable.width();if(width<=0)width=activity.getResources().getDisplayMetrics().widthPixels;
        return Math.min(dp(activity,560),Math.max(dp(activity,240),width-dp(activity,32)));
    }
    private static void button(Context context,Button button,boolean primary,boolean neutral){
        if(button==null)return;button.setAllCaps(false);button.setTextSize(14);button.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        button.setTextColor(primary?Color.WHITE:BLUE);button.setMinHeight(dp(context,44));button.setMinimumHeight(dp(context,44));button.setMinWidth(dp(context,64));
        button.setPadding(dp(context,14),dp(context,9),dp(context,14),dp(context,9));
        button.setBackground(ripple(context,primary?BLUE:neutral?SELECTED:Color.TRANSPARENT,12,primary?0x33ffffff:0x18007aff));
        button.setStateListAnimator(null);
        if(button.getLayoutParams() instanceof ViewGroup.MarginLayoutParams){ViewGroup.MarginLayoutParams p=(ViewGroup.MarginLayoutParams)button.getLayoutParams();p.setMarginStart(dp(context,6));p.height=ViewGroup.LayoutParams.WRAP_CONTENT;button.setLayoutParams(p);}
    }
    static TextView text(Context context,CharSequence value,int size,int color){
        TextView result=new TextView(context);result.setText(value);result.setTextColor(color);result.setTextSize(size);
        result.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));result.setIncludeFontPadding(false);result.setLineSpacing(dp(context,2),1.12f);return result;
    }
    static LinearLayout content(Context context){
        LinearLayout result=new LinearLayout(context);result.setOrientation(LinearLayout.VERTICAL);result.setPadding(dp(context,24),dp(context,4),dp(context,24),dp(context,10));return result;
    }
    static TextView paragraph(Context context,CharSequence value){TextView result=text(context,value,13,MUTED);result.setPadding(0,0,0,dp(context,16));return result;}
    static EditText field(LinearLayout parent,String label,String value,boolean password){
        Context context=parent.getContext();TextView caption=text(context,label,12,MUTED);parent.addView(caption);
        EditText input=input(context,value,password);input.setId(View.generateViewId());caption.setLabelFor(input.getId());LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(context,48));params.topMargin=dp(context,7);params.bottomMargin=dp(context,17);parent.addView(input,params);return input;
    }
    static EditText input(Context context,String value,boolean password){
        EditText input=new EditText(context);input.setSingleLine(true);input.setInputType(InputType.TYPE_CLASS_TEXT|(password?InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        input.setText(value);input.setTextSize(14);input.setTextColor(TEXT);input.setHintTextColor(MUTED);input.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
        input.setBackground(rounded(context,SURFACE,12,true));input.setPadding(dp(context,12),dp(context,8),dp(context,12),dp(context,8));input.setMinHeight(dp(context,48));
        if(password)input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);return input;
    }
    static ScrollView scroll(Activity activity,View content){
        ScrollView result=new BoundedScroll(activity);result.setFillViewport(false);result.setClipToPadding(false);result.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);result.addView(content);return result;
    }
    private static final class BoundedScroll extends ScrollView {
        private final Activity activity;
        private final Rect usable=new Rect();
        BoundedScroll(Activity activity){super(activity);this.activity=activity;}
        @Override protected void onMeasure(int width,int height){
            getWindowVisibleDisplayFrame(usable);int available=usable.height();if(available<=0)available=activity.getResources().getDisplayMetrics().heightPixels;
            int maximum=Math.max(dp(activity,96),Math.min(dp(activity,520),available-dp(activity,170)));
            if(MeasureSpec.getMode(height)!=MeasureSpec.UNSPECIFIED)maximum=Math.min(maximum,MeasureSpec.getSize(height));
            super.onMeasure(width,MeasureSpec.makeMeasureSpec(maximum,MeasureSpec.AT_MOST));
        }
    }
    static ListView deviceList(Context context){return new NestedDeviceList(context);}
    private static final class NestedDeviceList extends ListView {
        NestedDeviceList(Context context){super(context);}
        @Override public boolean dispatchTouchEvent(MotionEvent event){
            if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(event.getActionMasked()!=MotionEvent.ACTION_UP&&event.getActionMasked()!=MotionEvent.ACTION_CANCEL);
            return super.dispatchTouchEvent(event);
        }
    }
    static TextView status(Context context,String value){
        TextView status=text(context,value,13,TEXT);status.setPadding(dp(context,14),dp(context,13),dp(context,14),dp(context,13));status.setBackground(rounded(context,SURFACE,14,true));return status;
    }
    static TextView verification(Context context,String value){
        TextView code=text(context,value,31,BLUE);code.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));code.setLetterSpacing(.16f);code.setGravity(Gravity.CENTER);code.setTextDirection(View.TEXT_DIRECTION_LTR);
        code.setPadding(dp(context,16),dp(context,19),dp(context,16),dp(context,19));code.setBackground(rounded(context,SELECTED,16,false));code.setAutoSizeTextTypeUniformWithConfiguration(18,31,1,TypedValue.COMPLEX_UNIT_SP);return code;
    }
    static Button choice(Context context,String title,String detail){
        SpannableString label=new SpannableString(title+"\n"+detail);label.setSpan(new StyleSpan(Typeface.BOLD),0,title.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);label.setSpan(new ForegroundColorSpan(BLUE),0,title.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);label.setSpan(new RelativeSizeSpan(.86f),title.length()+1,label.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);label.setSpan(new ForegroundColorSpan(MUTED),title.length()+1,label.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        Button result=new Button(context);result.setAllCaps(false);result.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);result.setText(label);result.setTextColor(TEXT);result.setTextSize(14);result.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));result.setMinHeight(dp(context,76));result.setPadding(dp(context,16),dp(context,14),dp(context,16),dp(context,14));result.setBackground(ripple(context,SURFACE,14,0x18007aff));result.setStateListAnimator(null);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,ViewGroup.LayoutParams.WRAP_CONTENT);params.bottomMargin=dp(context,10);result.setLayoutParams(params);return result;
    }
    static ArrayAdapter<String> deviceAdapter(Context context){
        return new ArrayAdapter<String>(context,android.R.layout.simple_list_item_1,new ArrayList<>()){
            @Override public View getView(int position,View convert,ViewGroup parent){
                LinearLayout row=new LinearLayout(context);row.setOrientation(LinearLayout.VERTICAL);row.setPadding(dp(context,14),dp(context,13),dp(context,14),dp(context,13));row.setMinimumHeight(dp(context,68));
                TextView name=text(context,getItem(position),14,TEXT);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);row.addView(name);
                TextView detail=text(context,"选择后比对两台屏幕上的验证码",11,MUTED);detail.setPadding(0,dp(context,3),0,0);row.addView(detail);return row;
            }
        };
    }
}
