package com.kuronami.meanwhile.harness;

/** Outcome of one comparison between the simulated and caught-up arms. */
public record Verdict(boolean passed, String summary, String detail) {

    public static Verdict pass(String summary) {
        return new Verdict(true, summary, "");
    }

    public static Verdict fail(String summary, String detail) {
        return new Verdict(false, summary, detail);
    }

    public String describe() {
        return (passed ? "PASS " : "FAIL ") + summary + (detail.isEmpty() ? "" : " | " + detail);
    }
}
