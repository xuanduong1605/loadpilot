package dev.loadpilot;

import com.sun.net.httpserver.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.loadpilot.integrations.k6.KubernetesWorkerStopper;
import io.kubernetes.client.openapi.ApiClient;
import okhttp3.tls.*;
import java.net.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class KubernetesWorkerStopperTest {
    final KubernetesWorkerStopper.Command command=new KubernetesWorkerStopper.Command("runners","lp-fixture","fixture-uid",UUID.randomUUID());
    class Fixture implements AutoCloseable {
        final HttpsServer server;final ExecutorService executor=Executors.newFixedThreadPool(2);final String ca;
        final List<String> requests=new CopyOnWriteArrayList<>(),bodies=new CopyOnWriteArrayList<>();volatile int status=200;
        Fixture() throws Exception {
            var cert=new HeldCertificate.Builder().commonName("stop-fixture").addSubjectAlternativeName("127.0.0.1").certificateAuthority(0).build();ca=cert.certificatePem();
            var identity=new HandshakeCertificates.Builder().heldCertificate(cert).build();server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setHttpsConfigurator(new HttpsConfigurator(identity.sslContext()));server.setExecutor(executor);
            server.createContext("/",exchange->{requests.add(exchange.getRequestMethod()+" "+exchange.getRequestURI());bodies.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                if(status==302) exchange.getResponseHeaders().set("Location",origin()+"/trap");
                byte[] body=(status==200?"{\"apiVersion\":\"v1\",\"kind\":\"Status\",\"status\":\"Success\",\"code\":200}":"{\"message\":\"private upstream detail\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        }
        String origin(){return "https://127.0.0.1:"+server.getAddress().getPort();}
        ApiClient client(){return new ApiClient().setBasePath(origin()).setSslCaCert(new ByteArrayInputStream(ca.getBytes(StandardCharsets.UTF_8)));}
        @Override public void close(){server.stop(0);executor.shutdownNow();}
    }
    @Test void sendsForegroundDeleteWithExactUidAndNeverClaimsTermination() throws Exception {
        try(var fixture=new Fixture();var stopper=new KubernetesWorkerStopper(fixture.client())) {
            assertThat(stopper.request(command)).isEqualTo(KubernetesWorkerStopper.Acknowledgement.ACCEPTED);
            assertThat(fixture.requests).containsExactly("DELETE /apis/batch/v1/namespaces/runners/jobs/lp-fixture");
            var body=new ObjectMapper().readTree(fixture.bodies.getFirst());assertThat(body.path("preconditions").path("uid").asText()).isEqualTo("fixture-uid");assertThat(body.path("propagationPolicy").asText()).isEqualTo("Foreground");
            assertThat(body.has("gracePeriodSeconds")).isFalse();
            fixture.status=404;assertThat(stopper.request(command)).isEqualTo(KubernetesWorkerStopper.Acknowledgement.NOT_FOUND);
        }
    }
    @Test void conflictsPermissionsAndRedirectsAreNotRetriedOrExposed() throws Exception {
        try(var fixture=new Fixture();var stopper=new KubernetesWorkerStopper(fixture.client())) {
            for(int status:List.of(409,403,302,500)) {
                fixture.status=status;fixture.requests.clear();
                assertThatThrownBy(()->stopper.request(command)).isInstanceOf(KubernetesWorkerStopper.Problem.class).hasMessageNotContaining("private upstream detail");
                assertThat(fixture.requests).hasSize(1).noneMatch(request->request.contains("/trap"));
            }
            fixture.requests.clear();
            assertThatThrownBy(()->stopper.request(new KubernetesWorkerStopper.Command("runners","lp-fixture",null,command.stopOperationId()))).isInstanceOf(IllegalArgumentException.class);
            assertThat(fixture.requests).isEmpty();
        }
    }
}
