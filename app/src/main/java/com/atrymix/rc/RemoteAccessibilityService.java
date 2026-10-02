package com.atrymix.rc;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public class RemoteAccessibilityService extends AccessibilityService {
    public static RemoteAccessibilityService instance;
    @Override public void onServiceConnected(){ instance=this; }
    @Override public void onDestroy(){ if(instance==this) instance=null; super.onDestroy(); }
    @Override public void onAccessibilityEvent(AccessibilityEvent e){}
    @Override public void onInterrupt(){}

    public void tap(float x,float y){
        Path p=new Path(); p.moveTo(x,y);
        GestureDescription g=new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(p,0,60)).build();
        dispatchGesture(g,null,null);
    }
    public void swipe(float x1,float y1,float x2,float y2,long duration){
        Path p=new Path(); p.moveTo(x1,y1); p.lineTo(x2,y2);
        dispatchGesture(new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(p,0,Math.max(80,duration))).build(),null,null);
    }
    public void setText(String text){
        AccessibilityNodeInfo n=findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if(n!=null){
            Bundle b=new Bundle();
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);
            n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b);
            n.recycle();
        }
    }
}
