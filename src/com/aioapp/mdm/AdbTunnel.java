package com.aioapp.mdm;

import android.util.Base64;
import android.util.Log;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * The device's leg of an adb tunnel: lets an admin reach this device's wireless adb
 * through the MDM server from anywhere (server: internal/adbtunnel).
 *
 * On an {@code adb_tunnel_open} frame the service calls {@link #open}: we dial adbd on
 * 127.0.0.1:port, open a second WebSocket to the server
 * ({@code /api/v1/adb-tunnel/{session}/{stream}}) with the same key as the command
 * socket, and copy bytes both ways as binary frames. One stream per adb host connection;
 * {@code adb_tunnel_close} drops every stream of a session. The server never pushes
 * bytes we did not ask for: a stream exists only after an admin's {@code adb connect}
 * reached the server's listener, which accepts from their address alone.
 *
 * Hand-rolled framing like {@link MdmWebSocketClient} — this is a system app in the AOSP
 * tree with no OkHttp.
 */
public final class AdbTunnel {
    private static final String TAG = "AdbTunnel";
    private static final int MAX_FRAME = 1 << 20; // adb packets are ≤ 256 KB; 1 MB is generous
    private static final SecureRandom RNG = new SecureRandom();

    /** Live streams by session id, so a close can drop them all. */
    private static final Map<String, List<Socket>> SESSIONS = new ConcurrentHashMap<>();

    private AdbTunnel() {}

    public static void open(String apiBaseUrl, String serial, String apiKey,
                            String session, String stream, int port) {
        Thread t = new Thread(() -> run(apiBaseUrl, serial, apiKey, session, stream, port),
                "adb-tunnel-" + stream);
        t.setDaemon(true);
        t.start();
    }

    public static void closeSession(String session) {
        List<Socket> socks = SESSIONS.remove(session);
        if (socks == null) return;
        synchronized (socks) {
            for (Socket s : socks) try { s.close(); } catch (IOException ignored) {}
        }
        Log.i(TAG, "session " + session.substring(0, Math.min(8, session.length())) + " closed");
    }

    private static void track(String session, Socket s) {
        SESSIONS.computeIfAbsent(session, k -> new ArrayList<>());
        List<Socket> socks = SESSIONS.get(session);
        if (socks != null) synchronized (socks) { socks.add(s); }
    }

    private static void untrack(String session, Socket s) {
        List<Socket> socks = SESSIONS.get(session);
        if (socks != null) synchronized (socks) { socks.remove(s); }
    }

    private static void run(String apiBaseUrl, String serial, String apiKey,
                            String session, String stream, int port) {
        Socket ws = null, adb = null;
        try {
            ws = connectWs(apiBaseUrl, serial, apiKey, session, stream);
            track(session, ws);
            OutputStream out = ws.getOutputStream();
            try {
                adb = new Socket();
                adb.connect(new InetSocketAddress("127.0.0.1", port), 3_000);
                adb.setTcpNoDelay(true);
            } catch (IOException e) {
                // Tell the server why, so the page says "adbd not listening" instead of hanging.
                sendFrame(out, 0x1, ("{\"error\":\"adbd not reachable on 127.0.0.1:" + port
                        + " (" + e.getMessage() + ")\"}").getBytes(StandardCharsets.UTF_8));
                sendFrame(out, 0x8, new byte[0]);
                return;
            }
            track(session, adb);
            Log.i(TAG, "stream " + stream + " open on port " + port);

            // adb → server
            final Socket adbF = adb, wsF = ws;
            Thread up = new Thread(() -> {
                byte[] buf = new byte[32 * 1024];
                try {
                    InputStream in = adbF.getInputStream();
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        byte[] chunk = new byte[n];
                        System.arraycopy(buf, 0, chunk, 0, n);
                        sendFrame(out, 0x2, chunk);
                    }
                } catch (IOException ignored) {
                } finally {
                    try { sendFrame(out, 0x8, new byte[0]); } catch (IOException ignored) {}
                    try { wsF.close(); } catch (IOException ignored) {}
                }
            }, "adb-tunnel-up-" + stream);
            up.setDaemon(true);
            up.start();

            // server → adb
            readLoop(ws.getInputStream(), out, adb.getOutputStream());
        } catch (Exception e) {
            Log.w(TAG, "stream " + stream + ": " + e.getMessage());
        } finally {
            if (adb != null) { untrack(session, adb); try { adb.close(); } catch (IOException ignored) {} }
            if (ws != null) { untrack(session, ws); try { ws.close(); } catch (IOException ignored) {} }
            Log.i(TAG, "stream " + stream + " ended");
        }
    }

    private static Socket connectWs(String apiBaseUrl, String serial, String apiKey,
                                    String session, String stream) throws Exception {
        URL url = new URL(apiBaseUrl);
        String host = url.getHost();
        boolean tls = url.getProtocol().equalsIgnoreCase("https");
        int port = url.getPort() == -1 ? (tls ? 443 : 80) : url.getPort();
        Socket s;
        if (tls) {
            SSLSocket ssl = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                javax.net.ssl.SSLParameters params = ssl.getSSLParameters();
                params.setApplicationProtocols(new String[]{"http/1.1"});
                ssl.setSSLParameters(params);
            }
            ssl.connect(new InetSocketAddress(host, port), 10_000);
            ssl.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.getSession())) {
                throw new SSLPeerUnverifiedException("TLS hostname verification failed for " + host);
            }
            s = ssl;
        } else {
            s = new Socket();
            s.connect(new InetSocketAddress(host, port), 10_000);
        }
        s.setTcpNoDelay(true);
        s.setSoTimeout(0);
        byte[] keyBytes = new byte[16];
        RNG.nextBytes(keyBytes);
        boolean defaultPort = (tls && port == 443) || (!tls && port == 80);
        String hostHeader = defaultPort ? host : host + ":" + port;
        String path = "/api/v1/adb-tunnel/" + session + "/" + stream + "?serial=" + URLEncoder.encode(serial, "UTF-8");
        String handshake =
                "GET " + path + " HTTP/1.1\r\n" +
                "Host: " + hostHeader + "\r\n" +
                "User-Agent: AioMDM/" + android.os.Build.VERSION.SDK_INT + "\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: " + Base64.encodeToString(keyBytes, Base64.NO_WRAP) + "\r\n" +
                "Sec-WebSocket-Version: 13\r\n" +
                "X-API-Key: " + apiKey + "\r\n" +
                "\r\n";
        OutputStream out = s.getOutputStream();
        out.write(handshake.getBytes(StandardCharsets.UTF_8));
        out.flush();
        InputStream in = s.getInputStream();
        StringBuilder header = new StringBuilder();
        int p3 = -1, p2 = -1, p1 = -1, b;
        while ((b = in.read()) != -1) {
            header.append((char) b);
            if (p3 == '\r' && p2 == '\n' && p1 == '\r' && b == '\n') break;
            p3 = p2; p2 = p1; p1 = b;
        }
        if (!header.toString().contains(" 101 ")) {
            s.close();
            throw new IOException("tunnel upgrade refused: " + header.toString().split("\r\n")[0]);
        }
        return s;
    }

    /** Reads server frames: binary → adbd, ping → pong, close → return. */
    private static void readLoop(InputStream in, OutputStream wsOut, OutputStream adbOut) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        while (true) {
            int b0 = dis.read(), b1 = dis.read();
            if (b0 == -1 || b1 == -1) return;
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) len = dis.readUnsignedShort();
            else if (len == 127) len = dis.readLong();
            if (len < 0 || len > MAX_FRAME) throw new IOException("frame too large: " + len);
            byte[] mask = masked ? new byte[4] : null;
            if (masked) dis.readFully(mask);
            byte[] payload = new byte[(int) len];
            dis.readFully(payload);
            if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
            switch (opcode) {
                case 0x2:
                case 0x0:
                    adbOut.write(payload);
                    adbOut.flush();
                    break;
                case 0x9:
                    sendFrame(wsOut, 0xA, payload);
                    break;
                case 0x8:
                    return;
                default:
                    break; // text/pong: nothing to do
            }
        }
    }

    /** Masked client→server frame (RFC 6455). */
    private static void sendFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        int len = payload.length;
        int headerLen = 2 + (len < 126 ? 0 : (len < 65536 ? 2 : 8)) + 4;
        byte[] frame = new byte[headerLen + len];
        int pos = 0;
        frame[pos++] = (byte) (0x80 | opcode);
        if (len < 126) {
            frame[pos++] = (byte) (0x80 | len);
        } else if (len < 65536) {
            frame[pos++] = (byte) (0x80 | 126);
            frame[pos++] = (byte) ((len >> 8) & 0xFF);
            frame[pos++] = (byte) (len & 0xFF);
        } else {
            frame[pos++] = (byte) (0x80 | 127);
            long l = len;
            for (int i = 7; i >= 0; i--) frame[pos++] = (byte) ((l >> (i * 8)) & 0xFF);
        }
        byte[] mask = new byte[4];
        RNG.nextBytes(mask);
        System.arraycopy(mask, 0, frame, pos, 4);
        pos += 4;
        for (int i = 0; i < len; i++) frame[pos + i] = (byte) (payload[i] ^ mask[i & 3]);
        synchronized (out) {
            out.write(frame);
            out.flush();
        }
    }
}
