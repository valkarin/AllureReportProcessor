package dev.allureprocessor;

/** Which output report a test ends up in, decided by the status of its latest attempt. */
public enum Bucket {
    /** Latest attempt passed. */
    PASSED("passed"),
    /** Everything else: failed, broken, skipped, unknown, or missing status. */
    NOT_PASSED("not-passed");

    private final String folderName;

    Bucket(String folderName) {
        this.folderName = folderName;
    }

    public String folderName() {
        return folderName;
    }

    public static Bucket ofStatus(String status) {
        return "passed".equals(status) ? PASSED : NOT_PASSED;
    }
}
