package dev.loadpilot.integrations.k6;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;

/** No checkbox/configuration can claim selected-image/CNI/runtime certification. Supply a verified adapter. */
@Configuration(proxyBeanMethods=false)
public class RuntimeAdmissionConfiguration {
    @Bean @ConditionalOnMissingBean(RuntimeAdmission.class) RuntimeAdmission runtimeAdmission(
            @Value("${platform.execution.runtime-evidence-directory:}") String directory,
            @Value("${platform.execution.runtime-evidence-keys-json:}") String keys,
            @Value("${platform.execution.runtime-cni-namespace:}") String cniNamespace,
            @Value("${platform.execution.runtime-cni-daemonset:}") String cniDaemonset,
            @Value("${platform.execution.runtime-evidence-max-validity-seconds:900}") int maximumValiditySeconds,
            ObjectMapper json,ExecutionClusterClients clients) {
        var evidence=new SignedRuntimeEvidence(directory,keys,json,maximumValiditySeconds);
        var state=evidence.configured()?new KubernetesRuntimeState(clients,json,cniNamespace,cniDaemonset):null;
        return new SignedRuntimeAdmission(evidence,state,Clock.systemUTC());
    }
}
