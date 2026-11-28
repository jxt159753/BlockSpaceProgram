package io.github.jxt159753.blockspaceprogram.webpreview;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/** Only serves bundled resources and immutable preview values. */
final class WebPreviewHttpServer implements AutoCloseable {
    private static final Gson GSON = new Gson();
    private static final String ROOT = "/assets/blockspaceprogram/web_preview/";
    private static final String LANG = "/assets/blockspaceprogram/lang/";
    private static final String JS = "text/javascript; charset=utf-8";
    private static final String JSON = "application/json; charset=utf-8";
    private static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; "
            + "base-uri 'none'; frame-ancestors 'none'";

    private final HttpServer server;
    private final ExecutorService workers;
    private final Semaphore connections;
    private final WebPreviewFeed feed;
    private final Map<String, Asset> assets;

    WebPreviewHttpServer(InetSocketAddress address, int maxConnections, WebPreviewFeed feed) throws IOException {
        this.feed = feed;
        connections = new Semaphore(maxConnections);
        assets = Map.of(
                "/", load(ROOT + "index.html", "text/html; charset=utf-8"),
                "/style.css", load(ROOT + "style.css", "text/css; charset=utf-8"),
                "/preview.mjs", load(ROOT + "preview.mjs", JS),
                "/kepler.mjs", load(ROOT + "kepler.mjs", JS),
                "/lang/en_us.json", load(LANG + "en_us.json", JSON),
                "/lang/zh_cn.json", load(LANG + "zh_cn.json", JSON),
                "/lang/zh_tw.json", load(LANG + "zh_tw.json", JSON));
        server = HttpServer.create(address, 0);
        workers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(workers);
        server.createContext("/", this::handle);
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            var headers = exchange.getResponseHeaders();
            headers.set("Cache-Control", "no-store");
            headers.set("Content-Security-Policy", CSP);
            headers.set("X-Content-Type-Options", "nosniff");
            if (!"GET".equals(exchange.getRequestMethod())) {
                headers.set("Allow", "GET");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String path = exchange.getRequestURI().getRawPath();
            if ("/events".equals(path)) {
                stream(exchange);
                return;
            }
            var asset = assets.get(path);
            if (asset == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                headers.set("Content-Type", asset.contentType());
                exchange.sendResponseHeaders(200, asset.bytes().length);
                exchange.getResponseBody().write(asset.bytes());
            }
        }
    }

    private void stream(HttpExchange exchange) throws IOException {
        if (!connections.tryAcquire()) {
            exchange.sendResponseHeaders(503, -1);
            return;
        }
        try {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
            exchange.sendResponseHeaders(200, 0);
            var output = exchange.getResponseBody();
            var initial = feed.connect();
            var previous = initial.frame();
            send(output, "snapshot", new WebPreviewFeed.Full(initial.clock(), initial.bodies(), previous.ships()));
            while (!Thread.currentThread().isInterrupted()) {
                var next = feed.awaitUpdate(previous);
                if (next == previous) {
                    output.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                } else {
                    send(output, "delta", next.since(previous));
                    previous = next;
                }
            }
        } catch (InterruptedException exit) {
            Thread.currentThread().interrupt();
        } catch (IOException disconnected) {
            // EventSource reconnects with a fresh snapshot, including after a proxy closes the stream.
        } finally {
            connections.release();
        }
    }

    private static void send(OutputStream output, String event, Object data) throws IOException {
        String frame = "event: " + event + "\ndata: " + GSON.toJson(data) + "\n\n";
        output.write(frame.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static Asset load(String path, String contentType) throws IOException {
        try (var input = WebPreviewHttpServer.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Missing web orbit preview asset: " + path);
            }
            return new Asset(contentType, input.readAllBytes());
        }
    }

    @Override
    public void close() {
        server.stop(0);
        workers.shutdownNow();
    }

    private record Asset(String contentType, byte[] bytes) {}
}
