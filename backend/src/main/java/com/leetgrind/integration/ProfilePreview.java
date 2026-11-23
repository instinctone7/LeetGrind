package com.leetgrind.integration;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.List;
import java.util.StringJoiner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leetgrind.catalog.ProblemCatalog.Difficulty;
import com.leetgrind.catalog.ProblemCatalog.Problem;
import com.leetgrind.platform.ApiErrors.Failure;
import com.leetgrind.platform.FixedWindowRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ProfilePreview {
    private static final int MAX_BYTES = 128 * 1024;

    private static final String PROFILE_QUERY = """
            query UserProfile($username: String!, $limit: Int!) {
              matchedUser(username: $username) {
                username
                profile { realName ranking }
                submitStatsGlobal { acSubmissionNum { difficulty count } }
                userCalendar { streak totalActiveDays submissionCalendar }
              }
              recentAcSubmissionList(username: $username, limit: $limit) {
                title titleSlug timestamp
              }
            }
            """;
    private static final String PROBLEM_QUERY = """
            query ProblemDifficulty($slug: String!) {
              question(titleSlug: $slug) { titleSlug title difficulty }
            }
            """;

    private final LinkService links;
    private final JsonMapper json;
    private final RestClient client;
    private final boolean enabled;
    private final boolean direct;
    private final FixedWindowRateLimiter attempts;

    ProfilePreview(LinkService links, JsonMapper json, Clock clock,
                   @Value("${leetgrind.integration.profile-preview-enabled}") boolean enabled,
                   @Value("${leetgrind.integration.direct-profile-preview:true}") boolean directAllowed,
                   @Value("${leetgrind.integration.profile-service-url:}") String serviceUrl) {
        this.links = links;
        this.json = json;
        this.attempts = new FixedWindowRateLimiter(clock, 5);
        this.enabled = enabled;
        direct = serviceUrl.isBlank();
        if (enabled && direct && !directAllowed) {
            throw new IllegalArgumentException("Hosted profile previews require LEETGRIND_PROFILE_SERVICE_URL.");
        }
        URI uri = URI.create(direct ? "https://leetcode.com" : serviceUrl.strip());
        if ((!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("The configured profile service needs an HTTP(S) base URL.");
        }
        var requests = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        requests.setConnectTimeout(3000);
        requests.setReadTimeout(8000);
        client = RestClient.builder().baseUrl(uri.toString().replaceFirst("/+$", "")).requestFactory(requests)
                .defaultHeader("User-Agent", "LeetGrind-Profile-Preview/1.0").build();
    }

    Snapshot read(long account, String requestedHandle) {
        if (!enabled) return null;
        String handle = requestedHandle == null ? savedHandle(account) : LinkService.profileHandle(requestedHandle);
        if (!attempts.allow(Long.toString(account))) {
            throw new Failure(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Try the profile preview again in a minute.");
        }
        try {
            RestClient.RequestHeadersSpec<?> request = direct
                    ? client.post().uri("/graphql/").contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("query", PROFILE_QUERY, "variables", Map.of("username", handle, "limit", 20)))
                    : client.get().uri("/api/leetcode/user/{handle}", handle);
            Snapshot snapshot = request.accept(MediaType.APPLICATION_JSON).exchange((sent, response) -> {
                if (response.getStatusCode().value() == 404 && !direct) throw notFound();
                if (!response.getStatusCode().is2xxSuccessful()) throw unavailable();
                var body = response.getBody().readNBytes(MAX_BYTES + 1);
                if (body.length > MAX_BYTES) throw unavailable();
                Snapshot preview = direct ? graphqlSnapshot(json.readTree(body)) : json.readValue(body, Snapshot.class);
                if (preview == null || !handle.equalsIgnoreCase(preview.username()) || preview.problemsSolved() == null
                        || preview.heatmap() == null || preview.heatmap().activityDays() == null
                        || preview.heatmap().activityDays().stream().anyMatch(day -> day == null || day.date() == null)
                        || preview.recentSubmissions() == null || preview.recentSubmissions().stream().anyMatch(item ->
                                item == null || item.title() == null || item.timestamp() < 0 || item.timestamp() > 8_640_000_000_000L)) {
                    throw unavailable();
                }
                return preview;
            });
            return direct ? withDifficulties(snapshot) : snapshot;
        } catch (RestClientException | tools.jackson.core.JacksonException | IllegalArgumentException failure) {
            throw unavailable();
        }
    }

    private Snapshot withDifficulties(Snapshot snapshot) {
        var slugs = snapshot.recentSubmissions().stream().map(Submission::titleSlug)
                .filter(slug -> slug != null && slug.matches("[a-z0-9-]{1,128}"))
                .distinct().limit(20).toList();
        var difficulties = new HashMap<String, Difficulty>();
        for (String slug : slugs) {
            Difficulty known = links.knownDifficulty(slug);
            if (known != null) difficulties.put(slug, known);
        }
        var unknown = slugs.stream().filter(slug -> !difficulties.containsKey(slug)).toList();
        if (!unknown.isEmpty()) difficulties.putAll(fetchDifficulties(unknown));
        var submissions = snapshot.recentSubmissions().stream().map(item -> new Submission(item.title(),
                item.titleSlug(), item.timestamp(), item.lang(), difficulties.get(item.titleSlug()))).toList();
        return new Snapshot(snapshot.username(), snapshot.realName(), snapshot.ranking(),
                snapshot.problemsSolved(), snapshot.heatmap(), submissions);
    }

    private Map<String, Difficulty> fetchDifficulties(List<String> slugs) {
        var variables = new HashMap<String, String>();
        var arguments = new StringJoiner(", ");
        var fields = new StringJoiner("\n");
        for (int index = 0; index < slugs.size(); index++) {
            String variable = "slug" + index;
            variables.put(variable, slugs.get(index));
            arguments.add("$" + variable + ": String!");
            fields.add("p" + index + ": question(titleSlug: $" + variable + ") { titleSlug difficulty }");
        }
        String query = "query RecentProblemDifficulties(" + arguments + ") {\n" + fields + "\n}";
        try {
            return client.post().uri("/graphql/").contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(Map.of("query", query, "variables", variables))
                    .exchange((sent, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) return Map.of();
                        byte[] body = response.getBody().readNBytes(MAX_BYTES + 1);
                        if (body.length > MAX_BYTES) return Map.of();
                        JsonNode data = json.readTree(body).path("data");
                        var difficulties = new HashMap<String, Difficulty>();
                        for (int i = 0; i < slugs.size(); i++) {
                            String slug = slugs.get(i);
                            Difficulty difficulty = readDifficulty(data.path("p" + i), slug);
                            if (difficulty != null) difficulties.put(slug, difficulty);
                        }
                        return difficulties;
                    });
        } catch (RestClientException | tools.jackson.core.JacksonException | IllegalArgumentException failure) {
            return Map.of();
        }
    }

    public Problem duelProblem(String slug) {
        if (!enabled || !slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || slug.length() > 128) return null;
        try {
            RestClient.RequestHeadersSpec<?> request = direct
                    ? client.post().uri("/graphql/").contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("query", PROBLEM_QUERY, "variables", Map.of("slug", slug)))
                    : client.get().uri("/api/leetcode/problem/{slug}", slug);
            return request.accept(MediaType.APPLICATION_JSON).exchange((sent, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) return null;
                        byte[] payload = response.getBody().readNBytes(MAX_BYTES + 1);
                        if (payload.length > MAX_BYTES) return null;
                        JsonNode root = json.readTree(payload);
                        JsonNode question = direct ? root.path("data").path("question") : root;
                        Difficulty difficulty = direct ? questionDifficulty(root, slug)
                                : slug.equals(question.path("titleSlug").asString(null))
                                    ? Difficulty.valueOf(question.path("difficulty").asString("").toUpperCase(Locale.ROOT)) : null;
                        String title = question.path("title").asString("").strip();
                        return difficulty == null || title.isBlank() || title.length() > 160 ? null
                                : new Problem(slug, slug, title, difficulty);
                    });
        } catch (RestClientException | tools.jackson.core.JacksonException | IllegalArgumentException failure) {
            return null;
        }
    }

    static Difficulty questionDifficulty(JsonNode root, String slug) {
        if (!root.isObject() || root.path("errors").size() != 0) return null;
        return readDifficulty(root.path("data").path("question"), slug);
    }

    private static Difficulty readDifficulty(JsonNode question, String slug) {
        if (!slug.equals(question.path("titleSlug").asString(null))) return null;
        return switch (question.path("difficulty").asString("")) {
            case "Easy" -> Difficulty.EASY;
            case "Medium" -> Difficulty.MEDIUM;
            case "Hard" -> Difficulty.HARD;
            default -> null;
        };
    }

    private String savedHandle(long account) {
        var link = links.status(account);
        if (link.handle() == null || link.fixture() || "DISCONNECTED".equals(link.state())) {
            throw new Failure(HttpStatus.CONFLICT, "profile_preview_requires_handle", "Save a LeetCode handle first.");
        }
        return link.handle();
    }

    Snapshot graphqlSnapshot(JsonNode root) {
        if (!root.isObject() || root.path("errors").size() != 0) throw unavailable();
        JsonNode data = root.path("data");
        JsonNode user = data.path("matchedUser");
        if (data.isObject() && user.isNull()) throw notFound();
        JsonNode stats = user.path("submitStatsGlobal").path("acSubmissionNum");
        JsonNode calendar = user.path("userCalendar");
        JsonNode accepted = data.path("recentAcSubmissionList");
        if (!user.isObject() || !stats.isArray() || !calendar.isObject() || !accepted.isArray()) throw unavailable();

        var counts = new HashMap<String, Integer>();
        for (JsonNode row : stats) {
            if (!row.path("count").isIntegralNumber() || row.path("count").intValue() < 0) throw unavailable();
            counts.put(row.path("difficulty").asString("").toUpperCase(Locale.ROOT), row.path("count").intValue());
        }
        if (!counts.keySet().containsAll(List.of("ALL", "EASY", "MEDIUM", "HARD"))) throw unavailable();

        JsonNode reportedDays = json.readTree(calendar.path("submissionCalendar").asString("{}"));
        if (!reportedDays.isObject()) throw unavailable();
        var days = new ArrayList<Day>();
        for (var field : reportedDays.properties()) {
            if (!field.getValue().isIntegralNumber() || field.getValue().intValue() < 0) throw unavailable();
            long seconds = Long.parseLong(field.getKey());
            if (seconds < 0 || seconds > 8_640_000_000_000L) throw unavailable();
            days.add(new Day(Instant.ofEpochSecond(seconds).atOffset(ZoneOffset.UTC).toLocalDate(), field.getValue().intValue()));
        }
        days.sort(Comparator.comparing(Day::date));

        var submissions = new ArrayList<Submission>();
        for (JsonNode item : accepted) {
            JsonNode timestamp = item.path("timestamp");
            submissions.add(new Submission(item.path("title").asString(null), item.path("titleSlug").asString(null),
                    timestamp.isIntegralNumber() ? timestamp.longValue() : Long.parseLong(timestamp.asString("")),
                    item.path("lang").asString(null), null));
        }
        submissions.sort(Comparator.comparingLong(Submission::timestamp).reversed());
        JsonNode profile = user.path("profile");
        return new Snapshot(user.path("username").asString(null), profile.path("realName").asString(null),
                profile.path("ranking").isIntegralNumber() ? profile.path("ranking").intValue() : null,
                new Counts(counts.get("ALL"), counts.get("EASY"), counts.get("MEDIUM"), counts.get("HARD")),
                new Heatmap(calendar.path("streak").asInt(0), calendar.path("totalActiveDays").asInt(0), days), submissions);
    }

    private static Failure unavailable() {
        return new Failure(HttpStatus.BAD_GATEWAY, "profile_preview_unavailable",
                "LeetCode profile data could not be loaded. Try again later.");
    }

    private static Failure notFound() {
        return new Failure(HttpStatus.NOT_FOUND, "profile_preview_not_found", "No public LeetCode profile was found for this handle.");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Snapshot(String username, String realName, Integer ranking, Counts problemsSolved, Heatmap heatmap,
                    List<Submission> recentSubmissions) {}
    record Counts(int all, int easy, int medium, int hard) {}
    record Heatmap(int streak, int totalActiveDays, List<Day> activityDays) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Day(LocalDate date, int submissions) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Submission(String title, String titleSlug, long timestamp, String lang, Difficulty difficulty) {}
}
