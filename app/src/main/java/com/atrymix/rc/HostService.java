package com.atrymix.rc;

import android.app.*;
import android.content.*;
import android.content.res.Resources;
import android.graphics.Point;
import android.media.projection.MediaProjection;
import android.os.*;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONObject;
import org.webrtc.*;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class HostService extends Service {
    public static final String ACTION_START="com.atrymix.rc.START";
    public static final String EXTRA_RESULT_CODE="result_code";
    public static final String EXTRA_DATA="data";
    private static volatile boolean running=false;
    private static HostService instance;
    private SignalSocket ws;
    private HttpServer http;
    private PeerConnectionFactory factory;
    private PeerConnection peer;
    private VideoCapturer capturer;
    private VideoSource videoSource;
    private AudioSource audioSource;
    private MediaProjection.Callback projectionCallback;
    private EglBase eglBase;
    private SurfaceTextureHelper surfaceTextureHelper;
    private int httpPort=8080, wsPort=8765;
    private int width=1280,height=720,fps=30;
    private String localUrl;

    public static boolean isRunning(){return running;}
    public static String getLocalUrl(Context c){
        return instance==null ? null : instance.localUrl;
    }

    @Override public void onCreate(){
        super.onCreate(); instance=this;
        createChannel();
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null && ACTION_START.equals(intent.getAction())){
            Intent data=intent.getParcelableExtra(EXTRA_DATA);
            if(data!=null) startHost(data);
        }
        return START_STICKY;
    }

    private void startHost(Intent projectionData){
        if(running) return;
        startForeground(42,notification("RC is running"));
        localUrl="http://"+localIPv4()+":"+httpPort+"/";
        running=true;
        projectionDataHolder=projectionData;
        try{
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(getApplicationContext()).createInitializationOptions());
            factory=PeerConnectionFactory.builder().createPeerConnectionFactory();
            http=new HttpServer(httpPort); http.start();
            ws=new SignalSocket(new InetSocketAddress(wsPort)); ws.start();
        }catch(Exception e){ stopHost(); }
    }

    private void createPeer(String offerSdp, WebSocket client){
        closePeer();
        PeerConnection.RTCConfiguration cfg=new PeerConnection.RTCConfiguration(new ArrayList<>());
        cfg.sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN;
        cfg.bundlePolicy=PeerConnection.BundlePolicy.MAXBUNDLE;
        peer=factory.createPeerConnection(cfg,new PeerConnection.Observer(){
            public void onSignalingChange(PeerConnection.SignalingState s){}
            public void onIceConnectionChange(PeerConnection.IceConnectionState s){}
            public void onIceConnectionReceivingChange(boolean b){}
            public void onIceGatheringChange(PeerConnection.IceGatheringState s){}
            public void onIceCandidate(IceCandidate c){
                try{ client.send(new JSONObject().put("type","candidate")
                    .put("sdpMid",c.sdpMid).put("sdpMLineIndex",c.sdpMLineIndex).put("candidate",c.sdp).toString()); }catch(Exception ignored){}
            }
            public void onIceCandidatesRemoved(IceCandidate[] c){}
            public void onAddStream(MediaStream s){}
            public void onRemoveStream(MediaStream s){}
            public void onDataChannel(DataChannel dc){
                dc.registerObserver(new DataChannel.Observer(){
                    public void onBufferedAmountChange(long a){}
                    public void onStateChange(){}
                    public void onMessage(DataChannel.Buffer b){
                        byte[] d=new byte[b.data.remaining()]; b.data.get(d);
                        handleInput(new String(d,StandardCharsets.UTF_8));
                    }
                });
            }
            public void onRenegotiationNeeded(){}
            public void onAddTrack(RtpReceiver r,MediaStream[] s){}
            public void onConnectionChange(PeerConnection.PeerConnectionState s){}
            public void onStandardizedIceConnectionChange(PeerConnection.IceConnectionState s){}
            public void onSelectedCandidatePairChanged(CandidatePairChangeEvent e){}
            public void onTrack(RtpTransceiver t){}
        });

        try{
            addScreenTracks(projectionDataHolder);
            addAudioTrack();
            peer.setRemoteDescription(new SimpleSdpObserver(){
                @Override public void onSetSuccess(){
                    peer.createAnswer(new SimpleSdpObserver(){
                        @Override public void onCreateSuccess(SessionDescription a){
                            peer.setLocalDescription(new SimpleSdpObserver(){
                                @Override public void onSetSuccess(){
                                    try{client.send(new JSONObject().put("type","answer")
                                        .put("sdp",a.description).toString());}catch(Exception ignored){}
                                }
                            },a);
                        }
                    },new MediaConstraints());
                }
            },new SessionDescription(SessionDescription.Type.OFFER,offerSdp));
        }catch(Exception ignored){}
    }

    private Intent projectionDataHolder;
    private void addScreenTracks(Intent data){
        projectionDataHolder=data;
        WindowManager wm=(WindowManager)getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm=Resources.getSystem().getDisplayMetrics();
        int sw=dm.widthPixels, sh=dm.heightPixels;
        if(sw>sh){width=Math.min(sw,1920);height=Math.min(sh,1080);}else{height=Math.min(sh,1920);width=Math.min(sw,1080);}
        capturer=new ScreenCapturerAndroid(data,new MediaProjection.Callback(){@Override public void onStop(){}});
        videoSource=factory.createVideoSource(true);
        eglBase=EglBase.create();
        surfaceTextureHelper=SurfaceTextureHelper.create("RC-Capture",eglBase.getEglBaseContext());
        capturer.initialize(surfaceTextureHelper,this,videoSource.getCapturerObserver());
        capturer.startCapture(width,height,fps);
        VideoTrack track=factory.createVideoTrack("rc-screen",videoSource);
        track.setEnabled(true);
        peer.addTrack(track);
    }

    private void addAudioTrack(){
        audioSource=factory.createAudioSource(new MediaConstraints());
        AudioTrack a=factory.createAudioTrack("rc-audio",audioSource);
        a.setEnabled(true);
        peer.addTrack(a);
    }

    private void handleInput(String raw){
        try{
            JSONObject j=new JSONObject(raw);
            String type=j.optString("type");
            RemoteAccessibilityService s=RemoteAccessibilityService.instance;
            if(s==null) return;
            if("tap".equals(type)) s.tap((float)j.getDouble("x"),(float)j.getDouble("y"));
            else if("swipe".equals(type)) s.swipe((float)j.getDouble("x1"),(float)j.getDouble("y1"),
                (float)j.getDouble("x2"),(float)j.getDouble("y2"),j.optLong("duration",250));
            else if("text".equals(type)) s.setText(j.optString("text",""));
            else if("back".equals(type)) s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            else if("home".equals(type)) s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
            else if("recents".equals(type)) s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS);
        }catch(Exception ignored){}
    }

    private void closePeer(){
        if(peer!=null){try{peer.close();}catch(Exception ignored){} peer=null;}
        if(capturer!=null){try{capturer.stopCapture();}catch(Exception ignored){} try{capturer.dispose();}catch(Exception ignored){} capturer=null;}
        if(videoSource!=null){videoSource.dispose();videoSource=null;}
        if(surfaceTextureHelper!=null){surfaceTextureHelper.dispose();surfaceTextureHelper=null;}
        if(eglBase!=null){eglBase.release();eglBase=null;}
        if(audioSource!=null){audioSource.dispose();audioSource=null;}
    }

    private void stopHost(){
        running=false; closePeer();
        if(ws!=null){ws.stop();ws=null;} if(http!=null){http.stopServer();http=null;}
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }

    @Override public void onDestroy(){ stopHost(); instance=null; super.onDestroy(); }
    @Override public android.os.IBinder onBind(Intent i){return null;}

    private void createChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel c=new NotificationChannel("rc","RC",NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }
    private Notification notification(String text){
        return new Notification.Builder(this,"rc").setContentTitle("RC – Remote Control")
            .setContentText(text).setSmallIcon(android.R.drawable.ic_menu_view).build();
    }
    private String localIPv4(){
        try{
            Enumeration<NetworkInterface> es=NetworkInterface.getNetworkInterfaces();
            while(es.hasMoreElements()){
                NetworkInterface n=es.nextElement();
                Enumeration<InetAddress> as=n.getInetAddresses();
                while(as.hasMoreElements()){
                    InetAddress a=as.nextElement();
                    if(!a.isLoopbackAddress() && a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        }catch(Exception ignored){}
        return "127.0.0.1";
    }

    private class SignalSocket extends WebSocketServer{
        SignalSocket(InetSocketAddress a){super(a);}
        public void onOpen(WebSocket c,ClientHandshake h){}
        public void onClose(WebSocket c,int code,String reason,boolean remote){if(peer!=null) closePeer();}
        public void onMessage(WebSocket c,String msg){
            try{
                JSONObject j=new JSONObject(msg); String t=j.optString("type");
                if("offer".equals(t)) createPeer(j.getString("sdp"),c);
                else if("candidate".equals(t) && peer!=null)
                    peer.addIceCandidate(new IceCandidate(j.optString("sdpMid"),j.optInt("sdpMLineIndex"),j.getString("candidate")));
            }catch(Exception ignored){}
        }
        public void onError(WebSocket c,Exception e){}
        public void onStart(){}
    }

    private class HttpServer extends Thread{
        private final int port; private volatile boolean alive=true; private ServerSocket server;
        HttpServer(int p){port=p;}
        public void run(){
            try{
                server=new ServerSocket(port);
                while(alive){
                    Socket s=server.accept();
                    new Thread(()->serve(s)).start();
                }
            }catch(Exception ignored){}
        }
        void serve(Socket s){
            try{
                BufferedReader r=new BufferedReader(new InputStreamReader(s.getInputStream()));
                String line=r.readLine(); if(line==null){s.close();return;}
                OutputStream out=s.getOutputStream();
                String body=loadWeb();
                byte[] b=body.getBytes(StandardCharsets.UTF_8);
                String h="HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "+b.length+"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
                out.write(h.getBytes(StandardCharsets.US_ASCII));out.write(b);out.flush();s.close();
            }catch(Exception ignored){}
        }
        void stopServer(){alive=false;try{if(server!=null)server.close();}catch(Exception ignored){}}
    }

    private static abstract class SimpleSdpObserver implements SdpObserver{
        public void onCreateSuccess(SessionDescription s){}
        public void onSetSuccess(){}
        public void onCreateFailure(String s){}
        public void onSetFailure(String s){}
    }

    private String loadWeb(){
        try(InputStream in=getAssets().open("index.html"); ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[8192]; int n; while((n=in.read(buf))!=-1) out.write(buf,0,n);
            return out.toString(StandardCharsets.UTF_8.name());
        }catch(Exception e){ return "<!doctype html><html><body>RC web client unavailable</body></html>"; }
    }

}
