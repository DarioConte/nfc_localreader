package com.dadaops.cardreader;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static com.dadaops.cardreader.Json.jsonBool;
import static com.dadaops.cardreader.Json.jsonField;
import static com.dadaops.cardreader.Json.jsonInt;

/**
 * Routing REST indipendente dal server HTTP: mappa (metodo, path, query, apiKey, body) -> {@link Response}.
 * Lo usa sia il server desktop (com.sun.net.httpserver) sia un'app Android (es. NanoHTTPD), cosi' il
 * contratto degli endpoint e' definito una volta sola. L'adapter HTTP aggiunge CORS e gestisce OPTIONS.
 */
public final class ApiRouter {
    private ApiRouter() {}

    public static final class Response {
        public final int status;
        public final String contentType;
        public final byte[] body;
        Response(int status, String contentType, byte[] body) {
            this.status = status; this.contentType = contentType; this.body = body;
        }
        static Response json(int status, String json) {
            return new Response(status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * @param method  GET/POST/...
     * @param path    es. "/check"
     * @param query   query string grezza (senza '?'), puo' essere null
     * @param apiKey  header X-API-Key (o param apikey)
     * @param body    corpo della richiesta (puo' essere null)
     */
    public static Response route(String method, String path, String query, String apiKey, String body) {
        if (apiKey == null) apiKey = queryParam(query, "apikey");
        switch (path) {
            case "/actuator/health":
                return Response.json(200, "{\"status\":\"UP\"}");
            case "/actuator/readers":
                return Response.json(200, CardReaderApi.readers());
            case "/actuator/status":
                return Response.json(200, CardReaderApi.status(queryParam(query, "reader")));
            case "/openapi.yaml":
                return resource("/openapi.yaml", "application/yaml; charset=utf-8");
            case "/docs":
            case "/docs/":
                return resource("/docs.html", "text/html; charset=utf-8");   // Swagger UI (carica /openapi.yaml)
            case "/":
            case "/console.html":
                if (!AppContext.CONSOLE_ENABLED) return Response.json(403, "{\"errore\":\"Console di debug disabilitata\"}");
                return resource("/console.html", "text/html; charset=utf-8");
            case "/check": {
                if (!"POST".equalsIgnoreCase(method)) return Response.json(405, "{\"errore\":\"Usare POST\"}");
                if (!authOk(apiKey)) return Response.json(401, "{\"errore\":\"API key non valida\"}");
                String reader = orQuery(jsonField(body, "reader"), query, "reader");
                boolean foto = jsonBool(body, "foto") || flag(query, "foto");
                boolean debug = AppContext.CONSOLE_ENABLED && (jsonBool(body, "debug") || flag(query, "debug"));
                return Response.json(200, CardReaderApi.check(extractCan(body, query), reader, foto,
                        jsonField(body, "documentNumber"), jsonField(body, "dateOfBirth"), jsonField(body, "dateOfExpiry"), debug));
            }
            case "/identify": {
                if (!authOk(apiKey)) return Response.json(401, "{\"errore\":\"API key non valida\"}");
                return Response.json(200, CardReaderApi.identify(queryParam(query, "reader")));
            }
            case "/write": {
                if (!"POST".equalsIgnoreCase(method)) return Response.json(405, "{\"errore\":\"Usare POST\"}");
                if (!authOk(apiKey)) return Response.json(401, "{\"errore\":\"API key non valida\"}");
                String reader = orQuery(jsonField(body, "reader"), query, "reader");
                return Response.json(200, CardReaderApi.write(reader, jsonField(body, "text"), jsonField(body, "hex"),
                        jsonInt(body, "page"), jsonInt(body, "block"), jsonField(body, "key"),
                        jsonField(body, "chiaveAnagrafica"), jsonField(body, "codiceSocio"),
                        jsonField(body, "codiceFamiliare"), jsonField(body, "familiari")));
            }
            case "/verify": {
                if (!"POST".equalsIgnoreCase(method)) return Response.json(405, "{\"errore\":\"Usare POST\"}");
                if (!authOk(apiKey)) return Response.json(401, "{\"errore\":\"API key non valida\"}");
                return Response.json(200, CardReaderApi.verify(body));
            }
            default:
                return Response.json(404, "{\"errore\":\"Endpoint sconosciuto\"}");
        }
    }

    private static boolean authOk(String apiKey) {
        return AppContext.API_KEY != null && AppContext.API_KEY.equals(apiKey);
    }

    private static Response resource(String name, String contentType) {
        byte[] b = CardReaderApi.resource(name);
        if (b == null) return Response.json(404, "{\"errore\":" + Json.jstr(name + " non incluso") + "}");
        return new Response(200, contentType, b);
    }

    private static String orQuery(String fromBody, String query, String name) {
        return fromBody != null ? fromBody : queryParam(query, name);
    }

    private static boolean flag(String query, String name) {
        String v = queryParam(query, name);
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    private static String extractCan(String body, String query) {
        String c = jsonField(body, "can");
        if (c != null) return c.trim();
        String q = queryParam(query, "can");
        return q == null ? null : q.trim();
    }

    static String queryParam(String query, String name) {
        if (query == null) return null;
        for (String p : query.split("&")) {
            int i = p.indexOf('=');
            if (i > 0 && p.substring(0, i).equals(name))
                return java.net.URLDecoder.decode(p.substring(i + 1), StandardCharsets.UTF_8);
        }
        return null;
    }
}
