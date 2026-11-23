package com.leetgrind.catalog;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProblemCatalog {
    private final JdbcClient jdbc;

    ProblemCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long resolve(String provider, Problem problem) {
        jdbc.sql("""
                INSERT INTO problems (provider, provider_problem_id, slug, title, difficulty, practice_catalog_id)
                VALUES (:provider, :id, :slug, :title, :difficulty, (
                    SELECT id FROM practice_catalog
                    WHERE provider = :provider AND slug = :slug
                )) AS incoming
                ON DUPLICATE KEY UPDATE slug = incoming.slug, title = incoming.title, difficulty = incoming.difficulty,
                    practice_catalog_id = COALESCE(problems.practice_catalog_id, incoming.practice_catalog_id)
                """)
                .param("provider", provider).param("id", problem.id()).param("slug", problem.slug())
                .param("title", problem.title()).param("difficulty", problem.difficulty().name()).update();
        return jdbc.sql("SELECT id FROM problems WHERE provider = ? AND provider_problem_id = ?")
                .params(provider, problem.id()).query(Long.class).single();
    }

    public enum Difficulty { EASY, MEDIUM, HARD }
    public record Problem(String id, String slug, String title, Difficulty difficulty) {}
}
