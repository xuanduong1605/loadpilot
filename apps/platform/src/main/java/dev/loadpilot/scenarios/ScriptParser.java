package dev.loadpilot.scenarios;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Runs only the trusted parse helper. Submitted source is stdin data, never a command/module. */
@Component
public class ScriptParser {
    public record Finding(String code, String message, int line, int column) {}
    public record Result(boolean syntaxValid, boolean policyAccepted, String policyVersion,
                         String parserVersion, int nodeMajor, List<Finding> findings) {}
    private final String executable;
    private final String helper;
    private final ObjectMapper json;
    private final Semaphore capacity = new Semaphore(2);
    public ScriptParser(@Value("${platform.scenarios.node-executable:}") String executable,
                        @Value("${platform.scenarios.validator-script:}") String helper, ObjectMapper json) {
        this.executable = executable; this.helper = helper; this.json = json;
    }
    public Result parse(String source) {
        byte[] input = source.getBytes(StandardCharsets.UTF_8);
        if (input.length > 65536) throw new ScenarioStore.Problem(400, "Script source exceeds the validation limit.");
        return invoke(input,false,"static-review-v1");
    }
    /** Uses the same trusted bounded process; source stays stdin data and is never executed. */
    public Result parseExecution(String source,String targetUrl,int requestTimeoutMs) {
        if(source==null || source.getBytes(StandardCharsets.UTF_8).length>65536 || targetUrl==null || targetUrl.length()>2048 || requestTimeoutMs<1 || requestTimeoutMs>120000)
            throw new ScenarioStore.Problem(400,"Provide bounded execution source, target and timeout.");
        try {return invoke(json.writeValueAsBytes(java.util.Map.of("source",source,"targetUrl",targetUrl,"requestTimeoutMs",requestTimeoutMs)),true,"literal-http-v1");}
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid) {throw new IllegalStateException("Cannot encode execution inputs.");}
    }
    private Result invoke(byte[] input,boolean execution,String policy) {
        if (executable.isBlank() || helper.isBlank() || !Path.of(executable).isAbsolute() || !Path.of(helper).isAbsolute()
                || !Files.isRegularFile(Path.of(executable)) || !Files.isRegularFile(Path.of(helper)))
            throw new ScenarioStore.Problem(503, "The script validator is not configured. Saved source remains available.");
        if (!capacity.tryAcquire()) throw new ScenarioStore.Problem(429, "The script validator is busy. Try again shortly.");
        Process process = null;
        var readers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var arguments=new java.util.ArrayList<>(java.util.List.of(executable, "--max-old-space-size=64", "--disable-proto=throw", "--permission",
                    "--allow-fs-read=" + Path.of(helper).getParent(), helper));
            if(execution) arguments.add("--execution");
            var builder = new ProcessBuilder(arguments);
            String systemRoot = builder.environment().get("SystemRoot");
            builder.environment().clear();
            if (systemRoot != null) builder.environment().put("SystemRoot", systemRoot);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            final Process child = process;
            var writer = readers.submit(() -> {
                try (var stream = child.getOutputStream()) { stream.write(input); }
                return null;
            });
            var output = readers.submit(() -> {
                try (var stream = child.getInputStream()) { return stream.readNBytes(16385); }
            });
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new ScenarioStore.Problem(503, "Script validation timed out. Saved source remains available.");
            }
            byte[] bytes = output.get(1, TimeUnit.SECONDS);
            writer.get(1, TimeUnit.SECONDS);
            if (process.exitValue() != 0 || bytes.length > 16384) throw new IOException("Invalid validator response");
            var result = json.readValue(bytes, Result.class);
            if (result==null || result.nodeMajor() != 24 || !"8.19.0".equals(result.parserVersion()) || !policy.equals(result.policyVersion())
                    || result.findings() == null || result.findings().size() > 16
                    || (result.policyAccepted() && (!result.syntaxValid() || !result.findings().isEmpty())))
                throw new IOException("Validator version or response mismatch");
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ScenarioStore.Problem(503, "Script validation was interrupted. Try again.");
        } catch (IOException | ExecutionException | TimeoutException failed) {
            throw new ScenarioStore.Problem(503, "The script validator is unavailable. Saved source remains available.");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try { process.waitFor(500, TimeUnit.MILLISECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            readers.shutdownNow();
            capacity.release();
        }
    }
}
