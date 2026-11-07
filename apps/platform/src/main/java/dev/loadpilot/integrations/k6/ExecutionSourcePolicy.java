package dev.loadpilot.integrations.k6;

import dev.loadpilot.experiments.*;
import dev.loadpilot.scenarios.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Exact approved source is checked, never rewritten. Static checks do not certify runtime isolation. */
@Service
public class ExecutionSourcePolicy {
    public static final String VERSION="literal-http-v1";
    private final ScriptParser parser;
    public ExecutionSourcePolicy(ScriptParser parser) {this.parser=parser;}
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void verify(ExperimentSnapshots.Inputs input) {
        var script=input.scenario().script();
        if(!script.approved() || !K6PlanEnvelope.hash(script.source()).equals(script.sha256())) throw new ExperimentDrafts.Problem(409,"Exact approved script integrity failed before execution.");
        ScriptParser.Result result;
        try {result=parser.parseExecution(script.source(),input.target().baseUrl(),input.executionBounds().requestTimeoutMs());}
        catch(ScenarioStore.Problem unavailable) {throw new ExperimentDrafts.Problem(unavailable.status(),"Execution source validation is unavailable. No worker was authorized.");}
        if(!result.syntaxValid() || !result.policyAccepted()) throw new ExperimentDrafts.Problem(422,"Script does not meet execution requirements: use direct HTTP calls on the reviewed origin, literal bounded timeouts and redirects: 0. Dynamic capabilities are unsupported.");
    }
}
