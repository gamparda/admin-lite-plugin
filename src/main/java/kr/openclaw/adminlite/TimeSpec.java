package kr.openclaw.adminlite;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

final class TimeSpec {
    private static final Pattern PART = Pattern.compile("(\\d+)([smhdw])", Pattern.CASE_INSENSITIVE);

    // Zero means permanent; finite durations are always positive.
    static long parse(String input) {
        String text = input.toLowerCase(Locale.ROOT);
        if (text.equals("perm") || text.equals("permanent") || text.equals("영구")) return 0;
        var matcher = PART.matcher(text);
        long total = 0;
        int end = 0;
        try {
            while (matcher.find()) {
                if (matcher.start() != end) throw new IllegalArgumentException();
                long multiplier = switch (matcher.group(2)) {
                    case "s" -> 1_000L; case "m" -> 60_000L; case "h" -> 3_600_000L;
                    case "d" -> 86_400_000L; case "w" -> 604_800_000L;
                    default -> throw new IllegalArgumentException();
                };
                total = Math.addExact(total, Math.multiplyExact(Long.parseLong(matcher.group(1)), multiplier));
                end = matcher.end();
            }
            if (end != text.length() || total <= 0 || total > Duration.ofDays(3650).toMillis()) {
                throw new IllegalArgumentException();
            }
            return total;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("기간은 30m, 2h, 1d, 1h30m 또는 perm으로 입력하세요. 최대 10년입니다.");
        }
    }

    static String label(long millis) {
        if (millis == 0) return "영구";
        long seconds = Math.max(1, (millis + 999) / 1000);
        long days = seconds / 86400, hours = seconds % 86400 / 3600, minutes = seconds % 3600 / 60;
        StringBuilder text = new StringBuilder();
        if (days > 0) text.append(days).append("일 ");
        if (hours > 0) text.append(hours).append("시간 ");
        if (minutes > 0) text.append(minutes).append("분 ");
        if (seconds % 60 > 0 || text.isEmpty()) text.append(seconds % 60).append("초");
        return text.toString().trim();
    }
}
