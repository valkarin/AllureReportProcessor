package dev.allureprocessor;

/** Which output report a test ends up in, decided by the status of its latest attempt. */
public enum Bucket {
    FAILED("failed"),
    PASSED("passed"),
    /** skipped, unknown, or missing status. */
    OTHER("other");

    private final String folderName;

    Bucket(String folderName) {
        this.folderName = folderName;
    }

    public String folderName() {
        return folderName;
    }

    public static Bucket ofStatus(String status) {
        if (status == null) {
            return OTHER;
        }
        switch (status) {
            case "failed":
            case "broken":
                return FAILED;
            case "passed":
                return PASSED;
            default:
                return OTHER;
        }
    }
}
