package com.llmcascade.service;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Handles genuinely simple, deterministic queries at zero cost: greetings,
// basic arithmetic, and a couple of hardcoded facts. Anything it doesn't
// confidently recognize is NOT guessed at â€” RouterService escalates
// unhandled queries to the local model instead, so a classifier miss (query
// labeled "trivial" that isn't actually simple) never produces a wrong
// answer, just a slightly more expensive correct one.
@Component
public class RuleBasedAnswerService {

    private static final Pattern GREETING = Pattern.compile("\\b(hi|hello|hey|good morning|good afternoon|good evening)\\b");
    private static final Pattern THANKS = Pattern.compile("\\bthank(s| you)\\b");
    private static final Pattern ADD = Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\s*(?:\\+|plus)\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern PERCENT_OF = Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\s*(?:%|percent)\\s*of\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern DIVIDED_BY = Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\s*divided by\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern SQRT = Pattern.compile("square root of\\s*(-?\\d+(?:\\.\\d+)?)");

    public record Result(boolean handled, String answer) {
        static Result handled(String answer) { return new Result(true, answer); }
        static Result unhandled() { return new Result(false, null); }
    }

    public Result tryAnswer(String query) {
        String lower = query.toLowerCase().trim();

        if (lower.contains("days in a week")) {
            return Result.handled("There are 7 days in a week.");
        }
        if (THANKS.matcher(lower).find()) {
            return Result.handled("You're welcome!");
        }
        if (GREETING.matcher(lower).find()) {
            return Result.handled("Hello! How can I help you today?");
        }

        Matcher addMatcher = ADD.matcher(lower);
        if (addMatcher.find()) {
            double a = Double.parseDouble(addMatcher.group(1));
            double b = Double.parseDouble(addMatcher.group(2));
            return Result.handled(format(a + b));
        }

        Matcher pctMatcher = PERCENT_OF.matcher(lower);
        if (pctMatcher.find()) {
            double pct = Double.parseDouble(pctMatcher.group(1));
            double of = Double.parseDouble(pctMatcher.group(2));
            return Result.handled(format(pct / 100 * of));
        }

        Matcher divMatcher = DIVIDED_BY.matcher(lower);
        if (divMatcher.find()) {
            double a = Double.parseDouble(divMatcher.group(1));
            double b = Double.parseDouble(divMatcher.group(2));
            if (b != 0) return Result.handled(format(a / b));
        }

        Matcher sqrtMatcher = SQRT.matcher(lower);
        if (sqrtMatcher.find()) {
            double a = Double.parseDouble(sqrtMatcher.group(1));
            return Result.handled(format(Math.sqrt(a)));
        }

        return Result.unhandled();
    }

    private String format(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }
}

