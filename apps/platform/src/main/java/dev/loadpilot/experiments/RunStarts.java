package dev.loadpilot.experiments;

import dev.loadpilot.integrations.k6.RuntimeAdmission;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class RunStarts {
    public static class Unavailable extends RuntimeException {}
    private final RunIntents runs;private final StartWorkflows workflows;private final RuntimeAdmission admission;private final boolean monitoring;
    public RunStarts(RunIntents runs,StartWorkflows workflows,RuntimeAdmission admission,@Value("${platform.execution.monitor-enabled:false}") boolean monitoring){this.runs=runs;this.workflows=workflows;this.admission=admission;this.monitoring=monitoring;}
    public boolean available(){return monitoring && admission.available();}
    @Transactional(propagation=Propagation.NEVER) public RunIntents.Run request(RunIntents.Start input,String actor){
        if(runs.findRequest(input.requestKey())!=null)return runs.reserve(input,actor); // Replay only; never adopt internal/legacy work.
        if(!available())throw new Unavailable();
        return workflows.reserve(input,actor); // Run/attempt/intent and queue entry commit together.
    }
}
