package com.syrnnik.syrradio.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.syrnnik.syrradio.SyrRadio;
import com.syrnnik.syrradio.config.ServerConfig;
import net.minecraft.network.chat.Component;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** HTTP-клиент к bridge. Все методы асинхронные и не трогают состояние игры. */
final class BridgeClient {
    record Meta(String source, String title, String artist, long durationMs) {}

    record Audio(byte[] data, long durationMs) {}

    /** Ошибка, которую можно показать игроку. */
    static final class BridgeException extends RuntimeException {
        final Component text;

        BridgeException(Component text) {
            super(text.getString());
            this.text = text;
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    CompletableFuture<Meta> resolve(String url) {
        return post("/resolve", url, Duration.ofSeconds(60), HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> {
                JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
                return new Meta(
                    str(o, "source"),
                    str(o, "title"),
                    str(o, "artist"),
                    o.has("duration_ms") ? o.get("duration_ms").getAsLong() : 0);
            });
    }

    CompletableFuture<Audio> audio(String url) {
        // Первый запрос качает и перекодирует трек — это может занять полминуты.
        return post("/audio", url, Duration.ofMinutes(5), HttpResponse.BodyHandlers.ofByteArray())
            .thenApply(resp -> {
                long duration = resp.headers().firstValue("X-Duration-Ms").map(Long::parseLong).orElse(0L);
                return new Audio(resp.body(), duration);
            });
    }

    void played(String url, double seconds) {
        JsonObject body = new JsonObject();
        body.addProperty("url", url);
        body.addProperty("seconds", seconds);
        send("/played", body, Duration.ofSeconds(15), HttpResponse.BodyHandlers.discarding())
            .exceptionally(e -> {
                SyrRadio.LOG.debug("SyrRadio: /played не прошёл: {}", e.toString());
                return null;
            });
    }

    private <T> CompletableFuture<HttpResponse<T>> post(String path, String url, Duration timeout, HttpResponse.BodyHandler<T> handler) {
        JsonObject body = new JsonObject();
        body.addProperty("url", url);
        return send(path, body, timeout, handler);
    }

    private <T> CompletableFuture<HttpResponse<T>> send(String path, JsonObject body, Duration timeout, HttpResponse.BodyHandler<T> handler) {
        HttpRequest req;
        try {
            String base = ServerConfig.BRIDGE_URL.get().replaceAll("/+$", "");
            req = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + ServerConfig.BRIDGE_TOKEN.get())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(new BridgeException(
                Component.translatableWithFallback("syrradio.error.bridge_config", "Bridge address is misconfigured")));
        }
        // Ответ с ошибкой читаем как строку, а успешный — тем обработчиком,
        // что попросили: поэтому сначала смотрим на код ответа.
        return http.sendAsync(req, info -> info.statusCode() == 200
                ? handler.apply(info)
                : HttpResponse.BodySubscribers.mapping(
                    HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8),
                    s -> { throw new BridgeException(errorText(info.statusCode(), s)); }))
            .handle((resp, err) -> {
                if (err != null) {
                    throw new CompletionException(translate(err));
                }
                return resp;
            });
    }

    private static Component errorText(int status, String body) {
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            if (o.has("error")) {
                return Component.literal(o.get("error").getAsString());
            }
        } catch (RuntimeException ignored) {
            // не JSON — покажем код
        }
        return Component.translatableWithFallback("syrradio.error.bridge_http", "Bridge answered HTTP %s", status);
    }

    private static Throwable translate(Throwable err) {
        Throwable t = err;
        while ((t instanceof CompletionException || t instanceof java.io.UncheckedIOException) && t.getCause() != null) {
            t = t.getCause();
        }
        if (t instanceof BridgeException) {
            return t;
        }
        if (t instanceof ConnectException) {
            return new BridgeException(Component.translatableWithFallback("syrradio.error.bridge_down", "Track downloader is unavailable"));
        }
        if (t instanceof HttpTimeoutException) {
            return new BridgeException(Component.translatableWithFallback("syrradio.error.bridge_timeout", "Track downloader took too long"));
        }
        SyrRadio.LOG.warn("SyrRadio: запрос к bridge упал", t);
        return new BridgeException(Component.translatableWithFallback("syrradio.error.bridge_other", "Track downloader error: %s", t.toString()));
    }

    static Component message(Throwable err) {
        Throwable t = err;
        while (t instanceof CompletionException && t.getCause() != null) {
            t = t.getCause();
        }
        return t instanceof BridgeException b ? b.text : Component.literal(String.valueOf(t));
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
