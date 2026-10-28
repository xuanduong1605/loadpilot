package dev.loadpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.loadpilot.integrations.*;
import dev.loadpilot.targets.TargetStore;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InspectionCapacityTest {
    @Test void capacityRejectsExcessWorkWithoutQueueAndReleasesAfterFailure() throws Exception {
        var targets=mock(TargetStore.class); var entered=new CountDownLatch(4); var release=new CountDownLatch(1);
        when(targets.get(any())).thenAnswer(call->{entered.countDown();release.await(3,TimeUnit.SECONDS);throw new ConnectionProblem(404,"FIXTURE_STOP","fixture");});
        var inspector=new SystemInspector(targets,mock(ConnectionStore.class),new EndpointPolicy("",""),new ObjectMapper());
        var threads=Executors.newFixedThreadPool(4); var tasks=new ArrayList<Future<?>>();
        try {
            for(int i=0;i<4;i++) tasks.add(threads.submit(()->inspector.inspect(UUID.randomUUID())));
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->inspector.inspect(UUID.randomUUID())).isInstanceOfSatisfying(ConnectionProblem.class,error->assertThat(error.code()).isEqualTo("INSPECTION_BUSY"));
            release.countDown();
            for(var task:tasks) assertThatThrownBy(()->task.get(2,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
            assertThatThrownBy(()->inspector.inspect(UUID.randomUUID())).isInstanceOfSatisfying(ConnectionProblem.class,error->assertThat(error.code()).isEqualTo("FIXTURE_STOP"));
        } finally {release.countDown();threads.shutdownNow();}
    }
}
