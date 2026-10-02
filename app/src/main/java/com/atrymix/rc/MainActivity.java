package com.atrymix.rc;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

public class MainActivity extends Activity {
    private static final int CAPTURE_REQUEST = 501;
    private TextView status, url;
    private ImageView qr;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        status=findViewById(R.id.status); url=findViewById(R.id.url); qr=findViewById(R.id.qr);
        Button start=findViewById(R.id.start);
        Button accessibility=findViewById(R.id.accessibility);

        start.setOnClickListener(v -> requestCapture());
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        refresh();
    }

    private void requestCapture() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 700);
        MediaProjectionManager m=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(m.createScreenCaptureIntent(), CAPTURE_REQUEST);
    }

    @Override protected void onActivityResult(int req,int result,Intent data) {
        super.onActivityResult(req,result,data);
        if(req==CAPTURE_REQUEST && result==RESULT_OK && data!=null) {
            Intent i=new Intent(this,HostService.class);
            i.setAction(HostService.ACTION_START);
            i.putExtra(HostService.EXTRA_RESULT_CODE,result);
            i.putExtra(HostService.EXTRA_DATA,data);
            if(android.os.Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i);
            refresh();
        }
    }

    @Override protected void onResume(){ super.onResume(); refresh(); }

    private void refresh() {
        String u=HostService.getLocalUrl(this);
        boolean running=HostService.isRunning();
        status.setText(running ? "SERVER AKTIV · iPad wartet" : "SERVER AUS");
        url.setText(u == null ? "Noch keine lokale Adresse" : u);
        if(u!=null) generateQr(u);
    }

    private void generateQr(String value) {
        try {
            BitMatrix m=new MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 210, 210);
            Bitmap b=Bitmap.createBitmap(210,210,Bitmap.Config.ARGB_8888);
            for(int x=0;x<210;x++) for(int y=0;y<210;y++) b.setPixel(x,y,m.get(x,y)?Color.BLACK:Color.WHITE);
            qr.setImageBitmap(b);
        } catch(Exception ignored) {}
    }
}
