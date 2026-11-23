package com.leetgrind.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;

import com.leetgrind.catalog.ProblemCatalog.Difficulty;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ProfilePreviewTest {
    private final JsonMapper json = new JsonMapper();

    @Test
    void newestAcceptedSubmissionIsSelectedByTimestamp() {
        var preview = new ProfilePreview(null, json, Clock.systemUTC(), true, true, "");
        var snapshot = preview.graphqlSnapshot(json.readTree("""
                {"data":{
                  "matchedUser":{"username":"example","profile":{},"userCalendar":{},
                    "submitStatsGlobal":{"acSubmissionNum":[
                      {"difficulty":"All","count":2},{"difficulty":"Easy","count":1},
                      {"difficulty":"Medium","count":1},{"difficulty":"Hard","count":0}]}},
                  "recentAcSubmissionList":[
                    {"title":"Two Sum","titleSlug":"two-sum","timestamp":"100"},
                    {"title":"Merge Intervals","titleSlug":"merge-intervals","timestamp":"200"}]
                }}
                """));
        assertThat(snapshot.recentSubmissions())
                .extracting(ProfilePreview.Submission::titleSlug)
                .containsExactly("merge-intervals", "two-sum");
    }

    @Test
    void difficultyMustMatchTheRequestedProblemAndBeRecognized() {
        var payload = json.readTree("""
                {"data":{"question":{"titleSlug":"merge-intervals","difficulty":"Medium"}}}
                """);
        assertThat(ProfilePreview.questionDifficulty(payload, "merge-intervals")).isEqualTo(Difficulty.MEDIUM);
        assertThat(ProfilePreview.questionDifficulty(payload, "two-sum")).isNull();
        assertThat(ProfilePreview.questionDifficulty(json.readTree("""
                {"data":{"question":{"titleSlug":"merge-intervals","difficulty":"Unknown"}}}
                """), "merge-intervals")).isNull();
        assertThat(ProfilePreview.questionDifficulty(json.readTree("""
                {"errors":[{"message":"unavailable"}],"data":{"question":null}}
                """), "merge-intervals")).isNull();
    }
}
