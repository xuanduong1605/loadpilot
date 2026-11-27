package dev.loadpilot.ai;

import dev.loadpilot.ai.AssistantApi.Destination;
import dev.loadpilot.ai.AssistantApi.Link;
import dev.loadpilot.ai.AssistantApi.Task;
import dev.loadpilot.experiments.ExperimentDrafts;
import dev.loadpilot.experiments.ExperimentSnapshots;
import dev.loadpilot.experiments.RunIntents;
import dev.loadpilot.experiments.RunResults;
import dev.loadpilot.scenarios.ScenarioStore;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class AssistantGuide {
    private static final Pattern RECORD_ID = Pattern.compile("(?i)\\b[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\b");
    private final JdbcTemplate db;
    private final ScenarioStore scripts;
    private final ExperimentDrafts.Store drafts;
    private final ExperimentSnapshots.Store snapshots;
    private final RunIntents runs;
    private final RunResults results;

    AssistantGuide(JdbcTemplate db, ScenarioStore scripts, ExperimentDrafts.Store drafts,
                   ExperimentSnapshots.Store snapshots, RunIntents runs, RunResults results) {
        this.db = db;
        this.scripts = scripts;
        this.drafts = drafts;
        this.snapshots = snapshots;
        this.runs = runs;
        this.results = results;
    }

    record Answer(String text, List<Link> links, String facts, boolean offerAdvice) {}

    Answer answer(Task task, UUID selected, String text, String operator) {
        String question = text.toLowerCase(Locale.ROOT);
        Task resolved = task == Task.ASK ? intent(question) : task;
        var matcher = RECORD_ID.matcher(text);
        UUID id = selected != null ? selected : resolved != Task.ASK && matcher.find() ? UUID.fromString(matcher.group()) : null;
        return switch (resolved) {
            case ASK -> faq(question);
            case SCRIPT -> script(id);
            case STATUS -> status(runId(id, operator));
            case START -> start(id, operator);
            case RESULT -> result(runId(id, operator));
            case EDIT -> edit(id, operator);
        };
    }

    private static Task intent(String question) {
        if (matches(question, "status", "progress", "still running", "test finish", "run finish")) return Task.STATUS;
        if (matches(question, "result", "analy", "why did", "what happened")) return Task.RESULT;
        if (matches(question, "start a test", "start the test", "start a run", "start the run", "run a test", "run the test", "run tests", "launch")) return Task.START;
        if (matches(question, "edit", "change", "modify", "adjust", "draft", "write", "create", "update")) {
            if (matches(question, "script", "scenario", "k6")) return Task.SCRIPT;
            if (matches(question, "experiment", "test", "load", "threshold", "slo")) return Task.EDIT;
        }
        return Task.ASK;
    }

    private static Answer faq(String question) {
        if (matches(question, "approv", "validation", "validate", "hash"))
            return answer("Save a script version, run the checks, then approve that exact version and hash. After you edit the script, save and review the new version before selecting it in an experiment.", false, link(Destination.SCRIPTS, "Open scripts"));
        if (matches(question, "unevaluated", "whole-run summary", "partial window", "partial evidence", "insufficient data"))
            return answer("A completed run can still have an unevaluated SLO. The whole-run k6 summary includes warm-up, and a saved partial window covers only part of the run. An SLO verdict requires valid evidence for the required measurement window. Open the run and inspect the evidence availability and window before interpreting its metrics.", false,
                    link(Destination.RUNS, "Choose a run to inspect its evidence"));
        if (matches(question, "warm-up", "warmup", "measurement window"))
            return answer("Run duration includes warm-up. Set warm-up shorter than the duration. The measurement window starts after warm-up. Adaptive trials use the full post-warm-up window. For a ramp or spike, set the window on a constant peak plateau and exclude recovery.", false,
                    link(Destination.SETUP, "Review the load profile"));
        if (matches(question, "iterations/s", "iterations per second", "requests per second", "requests/s", "requested load", "achieved load"))
            return answer("Load settings use k6 iterations per second. An iteration can make multiple HTTP requests, so requests per second measures a different quantity. The load profile shows the requested rate. Use measured evidence to see what the generator achieved.", false,
                    link(Destination.SETUP, "Review load settings"), link(Destination.RUNS, "Choose a run to inspect its evidence"));
        if (matches(question, "run budget", "time budget", "run limits", "repetitions", "generator resources"))
            return answer("Run and time budgets must cover the repetitions at a tested load. Allow for startup, run duration including warm-up, shutdown, and configured retries. Generator CPU and memory limits apply to the load generator. Review the effective attempt limits before finalizing inputs.", false,
                    link(Destination.SETUP, "Review run and generator limits"));
        if (matches(question, "finalized inputs", "input snapshot", "saved input"))
            return answer("Finalized inputs capture the exact saved settings and approved script for a run. To change settings, edit the draft, save it, and review and finalize a new snapshot. Earlier runs retain their original inputs.", false,
                    link(Destination.SETUP, "Open experiment setup"), link(Destination.INPUTS, "Browse finalized inputs"));
        if (matches(question, "connect", "register", "system", "target", "grafana"))
            return answer("Register your application in Systems. Open its settings to check the API, Kubernetes and monitoring connections separately. Use the diagnostics to see which connection needs attention.", false, link(Destination.SYSTEMS, "Open systems"));
        if (matches(question, "percentile", "p95", "p99", "slo", "verdict"))
            return answer("A latency percentile describes the distribution of observed requests. For example, p95 is the latency at or below which about 95% of observations fall. An SLO verdict requires valid evidence from the required measurement window. Open a run to inspect its evidence or Setup to review thresholds.", false, link(Destination.RUNS, "View runs"), link(Destination.SETUP, "Open experiment setup"));
        if (matches(question, "stop", "cancel"))
            return answer("Open the run monitor, select Stop run and confirm the request. Watch the run status for confirmed termination and inspect any retained evidence.", false, link(Destination.RUNS, "Choose a run"));
        return answer("I can help with system registration, script approval, experiment setup, run status and saved results. Ask about a specific step or choose a task to connect a record.", true,
                link(Destination.SCRIPTS, "Open scripts"), link(Destination.RUNS, "Browse experiments"));
    }

    private Answer script(UUID id) {
        if (id == null) return answer("Describe the behavior and checks you want to change, including the intended load pattern. Open the script editor to draft your changes, then save, check and approve the exact version you want to use.", true,
                link(Destination.SCRIPTS, "Choose a saved script"), link(Destination.SCRIPT_EDITOR, "Open a new script"));
        var saved = scripts.latest(id);
        return answer("Open version %d of the selected script. Edit the scenario in the script editor and save a new version, then run validation and review its approval.".formatted(saved.version()), true,
                new Link(Destination.SCRIPT_EDITOR, id, saved.version(), "Open script version " + saved.version()));
    }

    private Answer edit(UUID id, String operator) {
        if (id == null) return answer("Choose a saved draft to change its load profile, thresholds, script or execution limits. Save the changes and review the exact inputs before finalizing a new snapshot.", true,
                link(Destination.RUNS, "Find a saved draft"), link(Destination.SETUP, "Open experiment setup"));
        requireOwned(RecordType.DRAFT, id, operator);
        var draft = drafts.get(id);
        var settings = draft.settings();
        String review = "Open draft revision %d to review the requested changes. Save the updated draft and finalize a new input snapshot when it is ready.".formatted(draft.revision());
        String facts = String.format(Locale.ROOT, "Draft revision %d. Load bounds: %d–%d iterations/s. Run duration: %d s, including %d s of warm-up.",
                draft.revision(), settings.minimumRate(), settings.maximumRate(), settings.durationSeconds(), settings.warmupSeconds());
        return new Answer(review, List.of(new Link(Destination.SETUP, id, null, "Edit saved draft")), facts, true);
    }

    private Answer start(UUID id, String operator) {
        String steps = "To prepare a run, review the finalized inputs and confirm that the runtime and monitoring checks are current. Starting a run requires the operator and the platform’s execution checks.";
        if (id == null) return answer(steps, false, link(Destination.INPUTS, "Choose finalized inputs"));
        requireOwned(RecordType.SNAPSHOT, id, operator);
        var snapshot = snapshots.get(id);
        return answer(steps + " The selected inputs are from draft revision " + snapshot.inputs().draftRevision() + ".", false,
                new Link(Destination.INPUT_REVIEW, id, null, "Review finalized inputs"));
    }

    private Answer status(UUID id) {
        if (id == null) return answer("Your experiment history has no test runs yet. Once a run is recorded, you can check its current status and monitor it here.", false, link(Destination.RUNS, "Open experiment history"));
        var run = runs.get(id);
        String worker = run.workerState() == null ? "awaiting worker observation" : label(run.workerState());
        return answer("Run status is %s. Worker state: %s. SLO verdict: %s.".formatted(label(run.status().name()), worker, label(run.verdict())), false,
                new Link(Destination.RUN_MONITOR, id, null, "Open run monitor"), link(Destination.RUNS, "Choose another run"));
    }

    private Answer result(UUID id) {
        if (id == null) return answer("Your experiment history has no runs to analyze yet. Saved results appear after the platform collects evidence for a run.", false, link(Destination.RUNS, "Open experiment history"));
        var view = results.read(id);
        var evidence = new Link(Destination.RUN_EVIDENCE, id, null, "Open run evidence");
        String state = "Run status: %s. SLO verdict: %s.".formatted(label(view.status()), label(view.verdict()));
        if (view.result() != null && "AVAILABLE".equals(view.resultState())) {
            var saved = view.result().evidence();
            var summary = saved.summary();
            return answer(state + String.format(Locale.ROOT,
                    "\n\nThe k6 whole-run summary includes warm-up. It recorded %,d HTTP requests, p95 HTTP duration of %.2f ms and a %.2f%% k6 HTTP failure rate. The summary covers %s to %s and cannot establish an SLO verdict.",
                    summary.httpRequests(), summary.httpDurationMs().p95(), summary.k6HttpFailureRate() * 100,
                    saved.startedAt(), saved.finishedAt()), true, evidence);
        }
        var window = view.partialHttp();
        String availability = state + " Whole-run evidence: " + label(view.resultState()) + ".";
        if (window != null) return answer(availability + String.format(Locale.ROOT,
                "\n\nThe saved partial window from %s to %s recorded %,d HTTP requests, %.2f requests/s, p95 HTTP duration of %.2f ms and a %.2f%% error ratio. This window is a historical sample and cannot establish an SLO verdict.",
                window.windowFrom(), window.windowUntil(), window.requestCount(), window.requestsPerSecond(), window.p95LatencyMs(), window.errorRatio() * 100), true, evidence);
        return answer(availability + " There is insufficient recorded evidence to analyze performance.", false, evidence);
    }

    RunResults.View ownedRunResult(UUID id, String operator) {
        requireOwned(RecordType.RUN, id, operator);
        return results.read(id);
    }

    ExperimentSnapshots.Inputs ownedRunInputs(UUID id, String operator) {
        requireOwned(RecordType.RUN, id, operator);
        return snapshots.get(runs.get(id).snapshotId()).inputs();
    }

    private enum RecordType {
        RUN("experiment_run"), DRAFT("experiment_draft"), SNAPSHOT("experiment_input_snapshot");
        private final String table;
        RecordType(String table) { this.table = table; }
    }

    private UUID runId(UUID id, String operator) {
        if (id != null) {
            requireOwned(RecordType.RUN, id, operator);
            return id;
        }
        return db.queryForList("SELECT id FROM experiment_run WHERE created_by = ? ORDER BY created_at DESC, id DESC LIMIT 1", UUID.class, operator)
                .stream().findFirst().orElse(null);
    }

    private void requireOwned(RecordType type, UUID id, String operator) {
        if (db.queryForList("SELECT id FROM " + type.table + " WHERE id = ? AND created_by = ?", UUID.class, id, operator).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This record is unavailable in your library.");
    }

    private static Link link(Destination destination, String label) { return new Link(destination, null, null, label); }

    private static Answer answer(String text, boolean offerAdvice, Link... links) {
        return new Answer(text, List.of(links), text, offerAdvice);
    }

    private static String label(String state) { return state.toLowerCase(Locale.ROOT).replace('_', ' '); }

    private static boolean matches(String question, String... terms) {
        for (String term : terms) if (question.contains(term)) return true;
        return false;
    }
}
