package io.github.waleed875.memo.modules.notes.domain;

/** Preserve C# JsonStringEnumConverter spellings in the public contract. */
public enum IndicatorValue {
    Unreviewed, Correct, Incorrect, Missing, Unclear, Incomplete, NotApplicable;

    public static IndicatorValue fromDatabase(short numericValue) {
        IndicatorValue[] values = values();
        if (numericValue < 0 || numericValue >= values.length) {
            throw new IllegalArgumentException("Unsupported indicator code");
        }
        return values[numericValue];
    }
}
