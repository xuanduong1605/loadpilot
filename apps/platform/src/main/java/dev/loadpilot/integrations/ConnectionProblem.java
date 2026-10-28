package dev.loadpilot.integrations;

public class ConnectionProblem extends RuntimeException {
    final int status;
    final String code;
    public ConnectionProblem(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    public String code() { return code; }
    static ConnectionProblem invalid() { return new ConnectionProblem(400, "CONNECTION_INVALID", "Check connection fields and certificate authorities."); }
    static ConnectionProblem unavailable() { return new ConnectionProblem(503, "CONNECTION_UNAVAILABLE", "Connection credentials are unavailable. Ask the platform operator to check key configuration."); }
    static ConnectionProblem conflict() { return new ConnectionProblem(409, "CONNECTION_CONFLICT", "This system or connection changed. Reload it before saving."); }
}
