package dev.loadpilot.targets;

public class TargetProblem extends RuntimeException {
    final int status;
    final String code;
    public int status() { return status; }
    public String code() { return code; }
    TargetProblem(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    static TargetProblem invalid(String field) { return new TargetProblem(400, "VALIDATION_FAILED", "Check the " + field + " field."); }
    static TargetProblem conflict() { return new TargetProblem(409, "TARGET_CONFLICT", "This system changed or was archived. Reload it before saving."); }
}
