package dev.loadpilot.fixtures;

import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;

/** Isolated protocol fixture, not evidence of a real Kubernetes cluster. No keys written to disk. */
public final class InspectionFixture implements AutoCloseable {
    private final HttpsServer tls;
    private final HttpServer plain;
    private final ExecutorService workers = Executors.newFixedThreadPool(4);
    public final String ca;
    public volatile String mode = "normal";
    public final List<String> requests = new CopyOnWriteArrayList<>();
    public InspectionFixture(int tlsPort, int apiPort) throws IOException {
        var certificate = new HeldCertificate.Builder().commonName("Loadpilot isolated fixture")
                .addSubjectAlternativeName("127.0.0.1").addSubjectAlternativeName("localhost").certificateAuthority(0).build();
        ca = certificate.certificatePem();
        var identity = new HandshakeCertificates.Builder().heldCertificate(certificate).build();
        tls = HttpsServer.create(new InetSocketAddress("127.0.0.1", tlsPort), 0);
        tls.setHttpsConfigurator(new HttpsConfigurator(identity.sslContext()));
        plain = HttpServer.create(new InetSocketAddress("127.0.0.1", apiPort), 0);
        for (HttpServer server : List.of(tls, plain)) { server.createContext("/", this::handle); server.setExecutor(workers); server.start(); }
    }
    public String origin() { return "https://127.0.0.1:" + tls.getAddress().getPort(); }
    public String apiOrigin() { return "http://127.0.0.1:" + plain.getAddress().getPort(); }
    private void handle(HttpExchange request) throws IOException {
        String modeFile = System.getProperty("fixture.mode.path");
        if (modeFile != null && Files.exists(Path.of(modeFile))) mode = Files.readString(Path.of(modeFile)).trim();
        String path = request.getRequestURI().getPath();
        requests.add(request.getRequestMethod() + " " + path);
        if (!"GET".equals(request.getRequestMethod())) { send(request,405,"{}"); return; }
        if (path.equals("/health")) {
            if (mode.equals("redirect")) { request.getResponseHeaders().set("Location", apiOrigin()+"/trap"); send(request,302,""); }
            else if (mode.equals("oversize")) send(request,200,"x".repeat(2*1024*1024+1));
            else if (mode.equals("timeout")) {
                try { Thread.sleep(6500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                send(request,200,"{}");
            } else send(request,200,"{\"status\":\"UP\"}");
            return;
        }
        if (!"Bearer fixture-readonly-token".equals(request.getRequestHeaders().getFirst("Authorization"))) { send(request,401,"{\"message\":\"private upstream detail\"}"); return; }
        if (mode.equals("forbidden") && path.startsWith("/apis/apps/")) { send(request,403,"{\"message\":\"private upstream detail\"}"); return; }
        if (path.equals("/-/ready")) { send(request,200,"ready"); return; }
        if (path.equals("/api/v1/query")) { send(request,200,"{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}"); return; }
        if (path.equals("/apis/apps/v1/namespaces/fixture/deployments/fixture-api")) { send(request,200,deployment()); return; }
        if (path.equals("/apis/apps/v1/namespaces/fixture/replicasets")) {
            send(request,200,"{\"apiVersion\":\"apps/v1\",\"kind\":\"ReplicaSetList\",\"metadata\":{},\"items\":[{\"metadata\":{\"name\":\"fixture-rs\",\"namespace\":\"fixture\",\"uid\":\"rs-1\",\"ownerReferences\":[{\"apiVersion\":\"apps/v1\",\"kind\":\"Deployment\",\"uid\":\"deployment-1\",\"name\":\"fixture-api\",\"controller\":true}]}}]}"); return;
        }
        if (path.equals("/api/v1/namespaces/fixture/pods")) {
            send(request,200,"{\"apiVersion\":\"v1\",\"kind\":\"PodList\",\"metadata\":{"+(mode.equals("truncated")?"\"continue\":\"more\"":"")+"},\"items\":["+pod("fixture-pod","rs-1")+","+pod("unrelated-pod","unrelated-rs")+"]}"); return;
        }
        if (path.equals("/apis/metrics.k8s.io/v1beta1/namespaces/fixture/pods")) {
            if (mode.equals("missing-metrics")) { send(request,404,"{}"); return; }
            if (mode.equals("malformed-metrics")) { send(request,200,"not-json"); return; }
            Instant timestamp = mode.equals("stale") ? Instant.now().minusSeconds(300) : Instant.now();
            send(request,200,"{\"kind\":\"PodMetricsList\",\"apiVersion\":\"metrics.k8s.io/v1beta1\",\"items\":[{\"metadata\":{\"name\":\"fixture-pod\",\"namespace\":\"fixture\"},\"timestamp\":\""+timestamp+"\",\"window\":\"30s\",\"containers\":[{\"name\":\"api\",\"usage\":{\"cpu\":\"125000000n\",\"memory\":\"32Mi\"}}]}]}"); return;
        }
        send(request,404,"{}");
    }
    private String deployment() {
        return "{\"apiVersion\":\"apps/v1\",\"kind\":\"Deployment\",\"metadata\":{\"name\":\"fixture-api\",\"namespace\":\"fixture\",\"uid\":\"deployment-1\",\"generation\":2},\"spec\":{\"replicas\":1,\"selector\":{\"matchLabels\":{\"app\":\"fixture\"}},\"template\":{\"metadata\":{\"labels\":{\"app\":\"fixture\"}},\"spec\":{\"containers\":["+container()+"]}}},\"status\":{\"observedGeneration\":2,\"replicas\":1,\"readyReplicas\":1,\"updatedReplicas\":1,\"availableReplicas\":1}}";
    }
    private String container() { return "{\"name\":\"api\",\"image\":\"fixture:local\",\"resources\":{\"requests\":{\"cpu\":\"100m\",\"memory\":\"64Mi\"},\"limits\":{\"cpu\":\"500m\",\"memory\":\"128Mi\"}}}"; }
    private String pod(String name, String owner) {
        return "{\"apiVersion\":\"v1\",\"kind\":\"Pod\",\"metadata\":{\"name\":\""+name+"\",\"namespace\":\"fixture\",\"uid\":\""+name+"\",\"labels\":{\"app\":\"fixture\"},\"ownerReferences\":[{\"apiVersion\":\"apps/v1\",\"kind\":\"ReplicaSet\",\"name\":\"fixture-rs\",\"uid\":\""+owner+"\",\"controller\":true}]},\"spec\":{\"containers\":["+container()+"]},\"status\":{\"phase\":\"Running\",\"containerStatuses\":[{\"name\":\"api\",\"image\":\"fixture:local\",\"imageID\":\"fixture\",\"ready\":true,\"restartCount\":2}]}}";
    }
    private void send(HttpExchange request, int status, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        request.getResponseHeaders().set("Content-Type", "application/json");
        try { request.sendResponseHeaders(status,data.length); request.getResponseBody().write(data); } finally { request.close(); }
    }
    @Override public void close() { tls.stop(0); plain.stop(0); workers.shutdownNow(); }
    public static void main(String[] args) throws Exception {
        var fixture = new InspectionFixture(18443,18080);
        Files.writeString(Path.of(System.getProperty("fixture.ca.path")),fixture.ca);
        Runtime.getRuntime().addShutdownHook(new Thread(fixture::close));
        System.out.println("Isolated inspection fixture ready on loopback18443/18080");
        new CountDownLatch(1).await();
    }
}
