package com.dbadmin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Asks GitHub for the newest published release of the app (the same downloads as the Releases page).
 * Only reads; sends nothing but the request itself. Any failure (offline, blocked, GitHub down) means
 * "no update found": the app never waits on or complains about it.
 */
public final class UpdateCheck {

    /** The page to send people to: always the newest release. */
    public static final String RELEASES_PAGE = "https://github.com/hafizosina/DBArbiter/releases/latest";
    private static final URI LATEST = URI.create("https://api.github.com/repos/hafizosina/DBArbiter/releases/latest");
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** A published release: its version ("0.1.20") and its page. */
    public record Release(String version, String page) {
    }

    private UpdateCheck() {
    }

    /** The newest release if it is newer than {@code current} (e.g. "0.1.18"); empty otherwise or on any failure. */
    public static Optional<Release> newerThan(String current) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest request = HttpRequest.newBuilder(LATEST).timeout(TIMEOUT)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "DBArbiter/" + current)
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return Optional.empty();
            JsonNode json = new ObjectMapper().readTree(response.body());
            String tag = json.path("tag_name").asText("");
            String version = tag.startsWith("v") ? tag.substring(1) : tag;
            String page = json.path("html_url").asText(RELEASES_PAGE);
            return isNewer(version, current) ? Optional.of(new Release(version, page)) : Optional.empty();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** "0.1.20" is newer than "0.1.9" (compared number by number); anything not like 1.2.3 is never newer. */
    static boolean isNewer(String candidate, String current) {
        int[] a = numbers(candidate);
        int[] b = numbers(current);
        if (a == null || b == null) return false;
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] numbers(String version) {
        if (version == null || !version.matches("\\d+(\\.\\d+)*")) return null;
        String[] parts = version.split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) result[i] = Integer.parseInt(parts[i]);
        return result;
    }
}
