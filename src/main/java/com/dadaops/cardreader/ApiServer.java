package com.dadaops.cardreader;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Adapter HTTP desktop (com.sun.net.httpserver): aggiunge CORS, gestisce OPTIONS e delega il
 * routing a {@link ApiRouter}. Su Android si usa lo stesso {@link ApiRouter} con un server diverso
 * (es. NanoHTTPD), quindi il contratto REST e' identico nelle due modalita'.
 */
final class ApiServer {
    private ApiServer() {}

    private static HttpServer server;
    private static ExecutorService executor;

    static void start() throws IOException {
        executor = Executors.newFixedThreadPool(4);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", AppContext.PORT), 0);
        server.createContext("/", ApiServer::handle);   // un solo handler: il routing e' in ApiRouter
        server.setExecutor(executor);
        server.start();
    }

    static void riavvia() {
        new Thread(() -> {
            try {
                if (server != null) server.stop(1);
                if (executor != null) executor.shutdownNow();
            } catch (Exception ignored) {}
            IOException ultimo = null;
            for (int i = 0; i < 10; i++) {
                try { start(); ultimo = null; break; }
                catch (IOException e) { ultimo = e; try { Thread.sleep(300); } catch (InterruptedException ie) { break; } }
            }
            System.out.println(ultimo == null ? "Servizio riavviato sulla porta " + AppContext.PORT
                    : "Riavvio fallito: " + ultimo.getMessage());
        }, "restart").start();
    }

    private static void handle(HttpExchange ex) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", AppContext.ALLOWED_ORIGIN);
        h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Content-Type, X-API-Key");
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); ex.close(); return; }

        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getRawQuery();
        String apiKey = ex.getRequestHeaders().getFirst("X-API-Key");
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        ApiRouter.Response r = ApiRouter.route(ex.getRequestMethod(), path, query, apiKey, body);
        ex.getResponseHeaders().set("Content-Type", r.contentType);
        ex.sendResponseHeaders(r.status, r.body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(r.body); }
    }
}
