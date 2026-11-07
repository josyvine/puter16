package com.puter.unofficial.http;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.puter.unofficial.ActionReportLogger;
import com.puter.unofficial.logging.LiveLogManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Embedded Lightweight Local HTTP Server for the Puter-Kiwi Bridge.
 * 
 * Provides an ultra-low latency direct transport alternative to Nostr relays.
 * Allows the Kiwi Browser extension to dispatch scraped payloads directly via HTTP POST.
 * Zero external library dependencies (pure Java Socket implementation).
 */
public class LocalHttpServerManager {

    private static final String TAG = "LocalHttpServer";
    public static final int DEFAULT_PORT = 8765;

    private static volatile LocalHttpServerManager instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    
    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private int currentPort = DEFAULT_PORT;
    private OnScrapedDataReceivedListener dataListener;

    public interface OnScrapedDataReceivedListener {
        void onDataReceived(String rawJsonPayload);
    }

    private LocalHttpServerManager() {}

    public static LocalHttpServerManager getInstance() {
        if (instance == null) {
            synchronized (LocalHttpServerManager.class) {
                if (instance == null) {
                    instance = new LocalHttpServerManager();
                }
            }
        }
        return instance;
    }

    public synchronized void setOnScrapedDataReceivedListener(OnScrapedDataReceivedListener listener) {
        this.dataListener = listener;
    }

    public synchronized boolean startServer(Context context, int port) {
        if (isRunning.get()) {
            if (this.currentPort == port) {
                return true;
            }
            stopServer();
        }

        this.currentPort = port;
        try {
            serverSocket = new ServerSocket(port);
            threadPool = Executors.newCachedThreadPool();
            isRunning.set(true);

            LiveLogManager.getInstance().log("OK", "Local HTTP Bridge Server started on port " + port);
            ActionReportLogger.log("LocalHttpServer", "Started on port: " + port);

            threadPool.execute(this::acceptConnections);
            return true;
        } catch (Exception e) {
            LiveLogManager.getInstance().log("ERROR", "Failed to bind HTTP Server on port " + port + ": " + e.getMessage());
            ActionReportLogger.log("LocalHttpServer", "Bind error: " + e.getMessage());
            stopServer();
            return false;
        }
    }

    public synchronized void stopServer() {
        isRunning.set(false);
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (Exception ignored) {}
        
        if (threadPool != null && !threadPool.isShutdown()) {
            threadPool.shutdownNow();
        }

        serverSocket = null;
        threadPool = null;
        LiveLogManager.getInstance().log("INFO", "Local HTTP Bridge Server stopped.");
        ActionReportLogger.log("LocalHttpServer", "Server stopped.");
    }

    public boolean isRunning() {
        return isRunning.get();
    }

    public int getCurrentPort() {
        return currentPort;
    }

    private void acceptConnections() {
        while (isRunning.get() && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                if (threadPool != null && !threadPool.isShutdown()) {
                    threadPool.execute(() -> handleClientSocket(clientSocket));
                }
            } catch (Exception e) {
                if (!isRunning.get()) break;
            }
        }
    }

    private void handleClientSocket(Socket socket) {
        try (InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String initialLine = reader.readLine();
            if (initialLine == null || initialLine.isEmpty()) return;

            String[] parts = initialLine.split(" ");
            if (parts.length < 2) return;

            String method = parts[0].toUpperCase();
            String path = parts[1];

            int contentLength = 0;
            String headerLine;
            while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                String lower = headerLine.toLowerCase();
                if (lower.startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(headerLine.substring(15).trim());
                    } catch (Exception ignored) {}
                }
            }

            // CORS Preflight Handler (Required for Browser Extensions)
            if ("OPTIONS".equalsIgnoreCase(method)) {
                sendCorsOkResponse(out);
                return;
            }

            // Health check / ping endpoint
            if ("GET".equalsIgnoreCase(method) && (path.equals("/status") || path.equals("/api/status") || path.equals("/"))) {
                JSONObject res = new JSONObject();
                res.put("status", "running");
                res.put("app", "Puter Unofficial");
                res.put("port", currentPort);
                res.put("transport", "http");
                sendJsonResponse(out, 200, res.toString());
                return;
            }

            // Scraped payload ingestion endpoint
            if ("POST".equalsIgnoreCase(method) && (path.equals("/api/scraped-data") || path.equals("/scraped-data"))) {
                if (contentLength <= 0) {
                    sendJsonResponse(out, 400, "{\"error\":\"Missing Content-Length\"}");
                    return;
                }

                char[] bodyBuffer = new char[contentLength];
                int totalRead = 0;
                while (totalRead < contentLength) {
                    int read = reader.read(bodyBuffer, totalRead, contentLength - totalRead);
                    if (read == -1) break;
                    totalRead += read;
                }

                String body = new String(bodyBuffer, 0, totalRead);
                LiveLogManager.getInstance().log("OK", "Direct HTTP payload received (" + totalRead + " bytes)");

                // Dispatch to Main Looper for Native UI & Session processing
                mainHandler.post(() -> {
                    if (dataListener != null) {
                        dataListener.onDataReceived(body);
                    }
                });

                sendJsonResponse(out, 200, "{\"success\":true,\"message\":\"Payload delivered to Puter\"}");
                return;
            }

            sendJsonResponse(out, 404, "{\"error\":\"Endpoint not found\"}");

        } catch (Exception e) {
            LiveLogManager.getInstance().log("WARN", "HTTP Bridge connection error: " + e.getMessage());
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private void sendCorsOkResponse(OutputStream out) throws Exception {
        String res = "HTTP/1.1 204 No Content\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type, Authorization, X-Requested-With\r\n" +
                "Access-Control-Max-Age: 86400\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n";
        out.write(res.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void sendJsonResponse(OutputStream out, int statusCode, String json) throws Exception {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String statusText = (statusCode == 200) ? "OK" : (statusCode == 404 ? "Not Found" : "Error");
        String header = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: " + bytes.length + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    /**
     * Resolves the device's local network IP address (Wi-Fi or hotspot).
     */
    public static String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.isLoopback() || !intf.isUp()) continue;
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }
}