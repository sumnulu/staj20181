package com.hivecdn.androidp2p;

import android.content.Context;
import android.os.Looper;
import android.provider.MediaStore;
import android.support.annotation.Nullable;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okio.ByteString;

public class SignalingServerConnection implements MyWebSocketListener {

    final String TAG = SignalingServerConnection.class.getName();
    final long ConnectionFailureReconnectTimeout = 15000;

    public interface SignalingListener {
        void onIdReceived(String ourPeerId, int ourSessionId);
        //void onConnectionEncouraged(String otherId);
        //void onIncomingOffer(String otherId);
        void onNewPeer(WebRtcPeerConnection pc);
        void onPeerDisconnected(WebRtcPeerConnection pc);
    }


    Context context;
    WebSocket socket;
    MyWebSocketProxy proxy;
    OkHttpClient client;
    //VideoPeerConnection.MyInterface vpciface;
    String url;
    String peerId;
    int sessionId;
    SignalingListener sListener;
    Map<String, WeakReference<WebRtcPeerConnection>> peersMap;
    WebRtcPeerConnectionFactoryInterface factory;

    SignalingServerConnection(Context _context, WebRtcPeerConnectionFactoryInterface _factory, SignalingListener _sListener, String _url) {
        context = _context;
        //vpciface = _vpciface;
        factory = _factory;
        sListener = _sListener;
        url = _url;
        proxy = new MyWebSocketProxy(this);
        client = new OkHttpClient.Builder()
                .readTimeout(0,  TimeUnit.MILLISECONDS)
                .build();
        peersMap = new HashMap<>();
        reconnect();
    }

    void reconnect() {
        if (socket != null) {
            socket.close(1000, null);
            socket = null;
        }
        getWebsocketAddress();
    }

    void getWebsocketAddress() {
        Log.v(TAG, "Getting websocket addresss");
        Request request = new Request.Builder()
                .url("https://static.hivecdn.com/host")
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.v(TAG, "Failed to get websocket address.");
                e.printStackTrace();
                setReconnectTimeout();
            }

            @Override
            public void onResponse(Call call, final Response response) throws IOException {
                if (!response.isSuccessful()) {
                    Log.v(TAG, "Unexpected code while trying to get websocket address:" + response);
                } else {
                    String address;
                    try {
                        JSONObject jObject = new JSONObject(response.body().string());
                        address = jObject.getJSONArray("hosts").getJSONObject(0).getString("address");
                    } catch (JSONException e) {
                        Log.v(TAG, "JSONException");
                        return;
                    }
                    connectWebSocket(address);
                }
            }
        });
    }

    void connectWebSocket(String s)
    {
        final String addr ="wss://"+s+"/ws";
        Log.v(TAG, "Connecting to websocket: " + addr);
        Request request = new Request.Builder()
                .url(addr)
                .addHeader("Origin", "https://hivecdn.com")
                .build();
        client.newWebSocket(request, proxy);
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        socket = webSocket;
        Log.v(TAG, "Websocket connected!");
        sendAuth();
        setPingTimeout();
    }

    void setPingTimeout()
    {
        new Timer().scheduleAtFixedRate(new TimerTask(){
            @Override
            public void run(){
                if (socket != null)
                {
                    socket.send("{\"payload\":{},\"command\":\"bogazici.ping\"}");
                    Log.v(TAG, "Sending ping message");
                }
            }
        },5000,15000);
    }

    void setReconnectTimeout() {
        Log.e(TAG, "Trying to reconnect to the signaling server in " + ConnectionFailureReconnectTimeout + " ms.");
        new android.os.Handler(Looper.getMainLooper()).postDelayed(
                new Runnable() {
                    public void run() {
                        reconnect();
                    }
                },
                ConnectionFailureReconnectTimeout);
    }

    void sendAuth() {
        final String encodedUrl = URLEncoder.encode(url);
        Log.v(TAG, "Sending authentication etc.");
        if (peerId == null)
            socket.send("{\"payload\":{\"siteId\":\"hivecdn-0000-0000-0000\",\"deviceType\":\"androidApp\",\"caps\":{\"webRTCSupport\":true,\"wsSupport\":true}},\"command\":\"bogazici.Authentication\"}");
        else
            socket.send("{\"payload\":{\"peerId\":\""+peerId+"\",\"siteId\":\"hivecdn-0000-0000-0000\",\"deviceType\":\"androidApp\",\"caps\":{\"webRTCSupport\":true,\"wsSupport\":true}},\"command\":\"bogazici.Authentication\"}");
        JSONObject msg = new JSONObject();
        JSONObject payload = new JSONObject();
        try {
            payload.put("url", url);
            msg.put("command", "VideoURLChanged");
            msg.put("payload", payload);
            socket.send(msg.toString());
            msg = new JSONObject();
            payload = new JSONObject();
            payload.put("levelCount", 9);
            payload.put("segmentCount", 0);
            payload.put("url", url);
            payload.put("streamType", "VOD");
            msg.put("payload", payload);
            msg.put("command", "peer.VideoDashMeta");
            socket.send(msg.toString());
            msg = new JSONObject();
            payload = new JSONObject();
            payload.put("videoId", url);
            payload.put("playing", true);
            payload.put("playbackSpeed", 1);
            payload.put("playerPosition", 0);
            payload.put("persist", true);
            msg.put("payload", payload);
            msg.put("command", "PeerPlayerState");
        }
        catch (JSONException e) {
            Log.v(TAG, "Unexpected JSONException");
            return ;
        }
        socket.send(msg.toString());
        socket.send("{\"payload\":{\"state\":\"playing\",\"currentTime\":0,\"timestamp\":1527689052511,\"isMuted\":false,\"playbackSpeed\":1},\"command\":\"PlayerState\"}");
    }

    void authenticationResponse(JSONObject jObject) {
        try {
            jObject = jObject.getJSONObject("payload");
            peerId = jObject.getString("peerId");
            sessionId = jObject.getInt("sessionId");
            sListener.onIdReceived(peerId, sessionId);
        }
        catch(JSONException e) {
            e.printStackTrace();
            Log.v(TAG, "JSONException");
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
        Log.v(TAG, "Inmsg: " + text);

        String command;
        JSONObject jObject;
        try {
            jObject = new JSONObject(text);
            command = jObject.getString("command");
        } catch (JSONException e) {
            Log.v(TAG, "JSONException");
            return;
        }
        if (command.equals("bogazici.sessionToken"))
            sessionTokenReceived(jObject);
        if (command.equals("AuthenticationResponse"))
            authenticationResponse(jObject);
        if (command.equals("peer.MakeConnectionWithPeer"))
            makeConnectionWithPeer(jObject);
        if (command.equals("peer.WebRTCHandshake"))
            incomingHandshake(jObject);
    }

    void sessionTokenReceived(JSONObject res) {
        /*try {
            sessionToken = res.getJSONObject("payload").getString("sessionToken");
        }
        catch (JSONException e) {
            Log.v(TAG, "JSONException");
        }*/
    }

    WebRtcPeerConnection getPCFromSignalId(String signalId) {
        WeakReference ref = peersMap.get(signalId);
        if (ref == null)
            return null;
        WebRtcPeerConnection pc = (WebRtcPeerConnection) ref.get();
        if (pc == null) {
            peersMap.remove(signalId);
            return null;
        }
        Log.v(TAG, "Redirecting incoming candidate/answer to the pertinent WebRtcPeerConnection instance.");
        return pc;
    }

    void incomingCandidate(JSONObject payload) {
        Log.v(TAG, "Incoming candidate.");
        try {
            String signalId = payload.getJSONObject("payload").getString("signalId");
            WebRtcPeerConnection pc = getPCFromSignalId(signalId);
            if (pc != null)
                pc.onIncomingCandidate(payload);
            else
                Log.v(TAG, "An unknown peer sent a candidate. Ignoring.");
        }
        catch (JSONException e) {
            Log.v(TAG, "JSONException");
        }
    }

    void incomingHandshake(JSONObject jObject) {
        try {
            jObject = jObject.getJSONObject("payload");
            if (jObject.getString("type").equals("OFFER")) {
                incomingOffer(jObject);
                return;
            }
            if (jObject.getString("type").equals("ANSWER")) {
                incomingAnswer(jObject);
                return ;
            }
            if (jObject.getString("type").equals("CANDIDATE")) {
                incomingCandidate(jObject);
                return ;
            }
        }
        catch (JSONException e) {
            Log.v(TAG, "JSONException");
        }
    }

    void incomingAnswer(JSONObject payload) {
        Log.v(TAG, "Incoming answer.");
        try {
            String signalId = payload.getJSONObject("payload").getString("signalId");
            WebRtcPeerConnection pc = getPCFromSignalId(signalId);
            if (pc != null)
                pc.onIncomingAnswer(payload); // TODO: To better isolate signaling logic from webrtc logic, do not pass raw json to PC's. Instead, only pass stuff related to webrtc.
            else
                Log.v(TAG, "An unknown peer sent an answer. Ignoring.");
        }
        catch (JSONException e) {
            Log.v(TAG, "JSONException");
        }
    }

    void incomingOffer(JSONObject payload) {
        Log.v(TAG, "Incoming offer");
        if (peerId == null)
            return; // We don't know our peerId yet.
        try {
            int otherSessionId = payload.getInt("otherSessionId");
            String otherPeerId = payload.getString("otherPeerId");
            String signalId = payload.getJSONObject("payload").getString("signalId");
            if (getPCFromSignalId(signalId) != null) {
                Log.v(TAG, "A known peer sent an offer. Ignoring.");
                return ;
            }
            //VideoPeerConnection vpc = new VideoPeerConnection(this, context, url, vpciface, peerId, sessionId, signalId, otherPeerId, otherSessionId, false, payload); // TODO: Do not pass JSON to the PC. It only needs to sdp.
            WebRtcPeerConnection pc = factory.Create(this, context, url, peerId, sessionId, signalId, otherPeerId, otherSessionId, false, payload);

            peersMap.put(signalId, new WeakReference<>(pc));
            //iface.onIncomingOffer(otherPeerId);
        }
        catch (JSONException e) {
            Log.v(TAG, "JSONException");
        }
    }

    void makeConnectionWithPeer(JSONObject res) {
        Log.v(TAG, "makeConnectionWithPeer");
        if (peerId == null)
            return; // We don't know our peerId yet.
        try {
            res = res.getJSONObject("payload");
            String uploaderPeerId = res.getString("uploaderPeerId");
            String downloaderPeerId = res.getString("downloaderPeerId");
            int uploaderSessionId = res.getInt("uploaderSessionId");
            int downloaderSessionId = res.getInt("downloaderSessionId");
            String signalId = UUID.randomUUID().toString();
            String otherPeerId;
            int otherSessionId;
            if (peerId.equals(uploaderPeerId)) {
                otherPeerId = downloaderPeerId;
                otherSessionId = downloaderSessionId;
            } else if (peerId.equals(downloaderPeerId)) {
                otherPeerId = uploaderPeerId;
                otherSessionId = uploaderSessionId;
            }
            else {
                Log.v(TAG, "Got unrelated handshake encouragement.");
                return ;
            }
            if (getPCFromSignalId(signalId) != null) {
                Log.v(TAG, "A known peer sent an offer. Ignoring.");
                return ;
            }
            WebRtcPeerConnection pc = factory.Create(this, context, url, peerId, sessionId, signalId, otherPeerId, otherSessionId, true, res);
            //VideoPeerConnection vpc = new VideoPeerConnection(this, context, url, vpciface, peerId, sessionId, signalId, otherPeerId, otherSessionId, true, res); // TODO: Do not pass JSON to the PC. It only needs to sdp.
            peersMap.put(signalId, new WeakReference<>(pc));
            //iface.onIncomingOffer(otherPeerId);
        } catch (JSONException e) {
            Log.v(TAG, "JSONException");
            return;
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
        Log.v(TAG, "Binary message received");
    }

    @Override
    public void onClosing(WebSocket webSocket, int code, String reason) {

    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
        Log.v(TAG, "Closed");
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, @Nullable Response response) {
        Log.e(TAG, "Websocket error");
        t.printStackTrace();
        setReconnectTimeout();
    }

    public void close() {
        if (socket != null)
            socket.close(1000, null);
    }

    public void onGotSDP(WebRtcPeerConnection pc, SessionDescription sdp) {
        JSONObject message;
        try {
            JSONObject innerPayload = new JSONObject();
            innerPayload.put("remoteVersion", "2.2.7-SNAPSHOT");
            innerPayload.put("sdp", sdp.description);
            if (pc.creatingOffer)
                innerPayload.put("type", "offer");
            else
                innerPayload.put("type", "answer");
            innerPayload.put("signalId", pc.signalId);
            JSONObject payload = new JSONObject();
            payload.put("otherPeerId", pc.otherPeerId);
            payload.put("otherSessionId", pc.otherSessionId);
            if (pc.creatingOffer)
                payload.put("type", "OFFER");
            else
                payload.put("type", "ANSWER");
            payload.put("payload", innerPayload);
            message = new JSONObject();
            message.put("command", "peer.WebRTCHandshake");
            message.put("payload", payload);
        }
        catch (JSONException e) {
            Log.v(TAG, "Unexpected JSONException");
            return ;
        }
        if (pc.creatingOffer)
            Log.v(TAG, "Sending offer.");
        else
            Log.v(TAG, "Sending answer.");
        Log.v(TAG, "Sending: " + message.toString());
        socket.send(message.toString());
    }

    public void onGotIceCandidate(WebRtcPeerConnection pc, IceCandidate iceCandidate) {
        JSONObject message;
        try {
            JSONObject innerPayload = new JSONObject();
            innerPayload.put("remoteVersion", "2.2.7-SNAPSHOT");
            innerPayload.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
            innerPayload.put("sdpMid", iceCandidate.sdpMid);
            innerPayload.put("candidate", iceCandidate.sdp);
            innerPayload.put("signalId", pc.signalId);
            JSONObject payload = new JSONObject();
            payload.put("otherPeerId", pc.otherPeerId);
            payload.put("otherSessionId", pc.otherSessionId);
            payload.put("type", "CANDIDATE");
            payload.put("payload", innerPayload);
            message = new JSONObject();
            message.put("command", "peer.WebRTCHandshake");
            message.put("payload", payload);
        }
        catch (JSONException e) {
            Log.v(TAG, "Unexpected JSONException");
            return ;
        }
        Log.v(TAG, "Sending ice candidate");
        Log.v(TAG, "Sending: " + message.toString());
        socket.send(message.toString());
    }

    void onPeerConnected(WebRtcPeerConnection pc) {
        sListener.onNewPeer(pc);
    }

    void onPeerDisconnected(WebRtcPeerConnection pc) {
        peersMap.remove(pc.signalId);
        sListener.onPeerDisconnected(pc);
    }
}
