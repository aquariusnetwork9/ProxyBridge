package com.aquarius.proxybridge.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Client for a remote pearl bot's HTTP command API (the ZenithProxy-style {@code POST {url}/command} endpoint,
 * authorized by a per-bot token the bot's owner shares). Ported from rfresh2/ZenithProxyMod's WebAPI. This is how a
 * bot the player is NOT connected to is reached out-of-band — no key, no access.
 */
public class WebAPI {
    public static final WebAPI INSTANCE = new WebAPI();

    private final Gson gson = new GsonBuilder().create();

    public CommandResponse execute(String command, String ip, String token) throws IOException, InterruptedException {
        String commandJson = gson.toJson(new CommandRequest(command));
        try (var client = buildHttpClient()) {
            String url = "";
            if (!(ip.startsWith("http://") || ip.startsWith("https://"))) {
                url = "http://";
            }
            url += ip + "/command";
            HttpRequest request = buildBaseRequest(url, token)
                .POST(HttpRequest.BodyPublishers.ofString(commandJson))
                .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return gson.fromJson(response.body(), CommandResponse.class);
        }
    }

    /**
     * Report the player's live position to a bot's {@code POST /position} endpoint (token-authorized; the proxy
     * attributes it to the token's UUID). Fire-and-forget — the response body is discarded. Lets the bot's
     * WhisperControl {@code come}/{@code follow} target the player out of the bot's render distance.
     */
    public void reportPosition(double x, double y, double z, String dimension, String ip, String token)
            throws IOException, InterruptedException {
        String json = gson.toJson(new PositionRequest(x, y, z, dimension));
        try (var client = buildHttpClient()) {
            String url = "";
            if (!(ip.startsWith("http://") || ip.startsWith("https://"))) {
                url = "http://";
            }
            url += ip + "/position";
            HttpRequest request = buildBaseRequest(url, token)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
            client.send(request, HttpResponse.BodyHandlers.discarding());
        }
    }

    protected HttpClient buildHttpClient() {
        return HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    }

    protected HttpRequest.Builder buildBaseRequest(final String uri, String token) {
        String version = FabricLoader.getInstance().getModContainer("proxybridge")
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("dev");
        return HttpRequest.newBuilder()
            .uri(URI.create(uri))
            .header("User-Agent", "ProxyBridge/" + version)
            .header("Accept", "application/json")
            .header("Authorization", token)
            .timeout(Duration.ofSeconds(15));
    }
}
