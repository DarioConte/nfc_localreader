package com.dadaops.cardreader.android;

import com.dadaops.cardreader.ApiRouter;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * Server HTTP locale (NanoHTTPD) che espone gli stessi endpoint del server desktop, delegando a
 * {@link ApiRouter}. Cosi' il browser sul tablet chiama http://localhost:8765/... come col desktop.
 */
public class LocalHttpServer extends NanoHTTPD {

    public LocalHttpServer(int port) { super("127.0.0.1", port); }

    @Override public Response serve(IHTTPSession session) {
        String method = session.getMethod().name();
        if ("OPTIONS".equalsIgnoreCase(method))
            return cors(newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", ""));

        String body = "";
        if ("POST".equals(method) || "PUT".equals(method)) {
            Map<String, String> files = new HashMap<>();
            try { session.parseBody(files); } catch (Exception ignored) {}
            body = files.getOrDefault("postData", "");
        }
        String apiKey = session.getHeaders().get("x-api-key");
        ApiRouter.Response r = ApiRouter.route(method, session.getUri(), session.getQueryParameterString(), apiKey, body);

        Response.Status st = Response.Status.lookup(r.status);
        if (st == null) st = Response.Status.OK;
        return cors(newFixedLengthResponse(st, r.contentType, new ByteArrayInputStream(r.body), r.body.length));
    }

    private Response cors(Response r) {
        r.addHeader("Access-Control-Allow-Origin", "*");
        r.addHeader("Access-Control-Allow-Headers", "Content-Type, X-API-Key");
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        return r;
    }
}
