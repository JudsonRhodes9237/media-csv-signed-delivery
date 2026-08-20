package example.mediaexport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import example.mediaexport.CreatorExportService.ExportDelivery;
import example.mediaexport.CreatorExportService.ExportRejected;
import example.mediaexport.CreatorExportService.ExportRequest;
import example.mediaexport.InfraiStorageClient.InfraiException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.Executors;

public final class MediaExportServer {
    private MediaExportServer() {}

    public static void main(String[] args) throws IOException {
        ExportConfig config = ExportConfig.fromEnvironment();
        CreatorExportService service = new CreatorExportService(new InfraiStorageClient(config.apiKey()), config, Clock.systemUTC());
        HttpServer server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        server.createContext("/exports", exchange -> handle(exchange, service));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("Media CSV export service listening on http://localhost:" + config.port());
    }

    @SuppressWarnings("unchecked")
    private static void handle(HttpExchange exchange, CreatorExportService service) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, Json.object(Map.of("error", "METHOD_NOT_ALLOWED")));
            return;
        }
        try {
            Object decoded = Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (!(decoded instanceof Map<?, ?> raw)) throw new ExportRejected("INVALID_REQUEST", "Expected a JSON object", 400);
            Map<String, Object> body = (Map<String, Object>) raw;
            ExportRequest request = new ExportRequest(required(body, "assetId"), required(body, "creatorId"),
                    required(body, "jobId"), required(body, "processingStatus"), number(body, "durationSeconds"));
            ExportDelivery delivery = service.export(request);
            respond(exchange, 201, Json.object(Map.of("objectKey", delivery.objectKey(), "downloadUrl", delivery.downloadUrl(),
                    "expiresAt", delivery.expiresAt().toString())));
        } catch (ExportRejected e) {
            respond(exchange, e.status(), Json.object(Map.of("error", e.code(), "message", e.getMessage())));
        } catch (InfraiException e) {
            respond(exchange, e.clientStatus(), Json.object(Map.of("error", e.code(), "message", e.getMessage())));
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, Json.object(Map.of("error", "INVALID_JSON", "message", e.getMessage())));
        }
    }

    private static String required(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (!(value instanceof String text) || text.isBlank()) throw new ExportRejected("INVALID_REQUEST", field + " is required", 400);
        return text;
    }

    private static long number(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (!(value instanceof Number number)) throw new ExportRejected("INVALID_REQUEST", field + " must be a number", 400);
        return number.longValue();
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
