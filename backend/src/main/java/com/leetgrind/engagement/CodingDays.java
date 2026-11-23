package com.leetgrind.engagement;

import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class CodingDays {
    private final JdbcClient jdbc;

    CodingDays(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Activity activity(long account, String provider, LocalDate today) {
        List<LocalDate> dates = jdbc.sql("""
                SELECT coding_date FROM coding_days
                WHERE account_id = :account AND coding_date <= :today
                  AND provider = :provider
                ORDER BY coding_date
                """).param("account", account).param("provider", provider).param("today", today).query(LocalDate.class).list();
        return summarize(dates, today);
    }

    Activity loginActivity(long account, LocalDate today) {
        var dates = jdbc.sql("""
                SELECT DISTINCT DATE(effective_at) AS login_date FROM xp_entries
                WHERE account_id = ? AND reason = 'DAILY_LOGIN' AND effective_at < ?
                ORDER BY login_date
                """).params(account, today.plusDays(1).atStartOfDay()).query(LocalDate.class).list();
        return summarize(dates, today);
    }

    private static Activity summarize(List<LocalDate> dates, LocalDate today) {
        LocalDate previous = null;
        int run = 0;
        int longest = 0;
        for (LocalDate date : dates) {
            run = previous != null && previous.plusDays(1).equals(date) ? run + 1 : 1;
            longest = Math.max(longest, run);
            previous = date;
        }
        int current = previous != null && !previous.isBefore(today.minusDays(1)) ? run : 0;
        LocalDate calendarStart = today.minusDays(27);
        return new Activity(current, longest, calendarStart, today,
                dates.stream().filter(date -> !date.isBefore(calendarStart)).toList());
    }

    public record Activity(int currentStreak, int longestStreak, LocalDate calendarStart, LocalDate calendarEnd,
                    List<LocalDate> codingDates) {}
}
