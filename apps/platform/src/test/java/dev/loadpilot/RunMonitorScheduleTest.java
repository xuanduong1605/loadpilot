package dev.loadpilot;

import dev.loadpilot.experiments.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RunMonitorScheduleTest {
    @Test void disabledByDefaultDoesNotStartBackgroundPolling() {
        var monitor=mock(RunMonitor.class);
        new ApplicationContextRunner().withUserConfiguration(RunMonitorSchedule.class).withBean(RunMonitor.class,()->monitor).run(context->{
            assertThat(context).doesNotHaveBean(RunMonitorSchedule.class);verifyNoInteractions(monitor);
        });
    }
    @Test void enabledPollingRunsWithoutBrowserAndRetriesAfterQueueOutage() {
        var monitor=mock(RunMonitor.class);var calls=new AtomicInteger();var retried=new CountDownLatch(1);
        when(monitor.tick()).thenAnswer(call->{if(calls.incrementAndGet()==1)throw new IllegalStateException("private DB details");retried.countDown();return false;});
        new ApplicationContextRunner().withUserConfiguration(RunMonitorSchedule.class).withBean(RunMonitor.class,()->monitor)
                .withPropertyValues("platform.execution.monitor-enabled=true").run(context->{
                    assertThat(context).hasSingleBean(RunMonitorSchedule.class);assertThat(retried.await(5,TimeUnit.SECONDS)).isTrue();
                });
    }
}
