package com.springclaw.service.agent;

/**
 * Model- or heuristic-produced judgment about whether gathered evidence can answer the original goal.
 */
public record ReflectionResult(boolean sufficient,
                               boolean retryable,
                               String problem,
                               String nextQuery,
                               String preferredIntent) {

    public ReflectionResult {
        problem = safe(problem);
        nextQuery = safe(nextQuery);
        preferredIntent = safe(preferredIntent);
    }

    public static ReflectionResult sufficient(String summary, String preferredIntent) {
        return new ReflectionResult(true, false, summary, "", preferredIntent);
    }

    public static ReflectionResult retry(String problem, String nextQuery, String preferredIntent) {
        return new ReflectionResult(false, true, problem, nextQuery, preferredIntent);
    }

    public static ReflectionResult degraded(String problem, String preferredIntent) {
        return new ReflectionResult(false, false, problem, "", preferredIntent);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
