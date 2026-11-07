package dev.loadpilot.integrations.k6;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;

/** Internal operator-tool workflow. Prepare intent -> externally reviewed apply -> verified archive; no mutation/signing. */
@Service
public class RuntimeProbeEvidenceService {
    private final RuntimeProbePlan plans;
    private final RuntimeProbeArchive archive;
    private final KubernetesProbeCollector collector;
    private final Clock clock;
    @Autowired public RuntimeProbeEvidenceService(@Value("${platform.execution.runtime-probe-archive-directory:}") String directory,ExecutionClusterClients clients,ObjectMapper json){
        this(new RuntimeProbePlan(json),directory==null || directory.isBlank()?null:new RuntimeProbeArchive(Path.of(directory),json),new KubernetesProbeCollector(clients,json,Clock.systemUTC()),Clock.systemUTC());
    }
    public RuntimeProbeEvidenceService(RuntimeProbePlan plans,RuntimeProbeArchive archive,KubernetesProbeCollector collector,Clock clock){this.plans=plans;this.archive=archive;this.collector=collector;this.clock=clock;}
    public boolean available(){return archive!=null;}
    public RuntimeProbePlan.Prepared prepare(K6PlanEnvelope.Prepared original,UUID id,RuntimeProbePlan.Node node,RuntimeProbePlan.Canary canary,String allowedUrl,RuntimeProbePlan.Phase phase){
        requireArchive();var prepared=plans.construct(original,id,node,canary,allowedUrl,phase,clock.instant());archive.prepare(original,prepared);return prepared;
    }
    public RuntimeProbeArchive.Archived capture(K6PlanEnvelope.Prepared original,RuntimeProbePlan.Prepared prepared){
        requireArchive();archive.requirePrepared(original,prepared);var existing=archive.existing(original,prepared);if(existing.isPresent())return existing.get();
        return archive.capture(original,prepared,collector.collect(original,prepared));
    }
    private void requireArchive(){if(archive==null)throw new IllegalStateException("Fixed runtime probe archiving is not configured.");}
}
