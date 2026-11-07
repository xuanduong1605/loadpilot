package dev.loadpilot.integrations.k6;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.net.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Execution-only origin-to-address allowlist. Never inherits connection inspection privileges. */
@Component
public class ExecutionDestinationPolicy implements AutoCloseable {
    @FunctionalInterface public interface Resolver { List<InetAddress> resolve(String host) throws Exception; }
    public static final class Problem extends RuntimeException {
        private final int status;private final String code;
        public Problem(int status,String code,String message) { super(message);this.status=status;this.code=code; }
        public int status() { return status; } public String code() { return code; }
    }
    private record Origin(String key,String host,int port) {}
    private final Map<String,Set<String>> allowed;
    private final Resolver resolver;
    private final long timeoutMillis;
    private final ThreadPoolExecutor workers;
    @Autowired public ExecutionDestinationPolicy(@Value("${platform.execution.destinations-json:}") String config,ObjectMapper json) {
        this(parse(config,json),host->Arrays.asList(InetAddress.getAllByName(host)),Duration.ofSeconds(2));
    }
    public ExecutionDestinationPolicy(Map<String,List<String>> config,Resolver resolver,Duration timeout) {
        if(config==null || config.size()>16 || timeout==null || timeout.toMillis()<10 || timeout.toMillis()>2000 || resolver==null) throw invalidConfig();
        Map<String,Set<String>> checked=new TreeMap<>();
        for(var entry:config.entrySet()) {
            Origin origin;
            try {
                var uri=URI.create(entry.getKey());
                if(uri.getRawPath()!=null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/")) throw invalidConfig();
                origin=origin(entry.getKey());
            } catch(RuntimeException invalid) { throw invalidConfig(); }
            var ips=entry.getValue();
            if(ips==null || ips.isEmpty() || ips.size()>16) throw invalidConfig();
            Set<String> normalized=new TreeSet<>();
            for(String ip:ips) {
                var address=literal(ip);
                if(!safe(address)) throw invalidConfig();
                normalized.add(address.getHostAddress());
            }
            if(checked.put(origin.key(),Set.copyOf(normalized))!=null) throw invalidConfig();
        }
        this.allowed=Map.copyOf(checked);this.resolver=resolver;this.timeoutMillis=timeout.toMillis();
        this.workers=new ThreadPoolExecutor(0,2,30,TimeUnit.SECONDS,new SynchronousQueue<>(),task->{var thread=new Thread(task,"execution-dns");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    }
    private static Map<String,List<String>> parse(String config,ObjectMapper json) {
        if(config==null || config.isBlank()) return Map.of();
        if(config.getBytes(StandardCharsets.UTF_8).length>16384) throw invalidConfig();
        try {
            var root=json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(config);
            if(!root.isObject() || root.size()>16) throw invalidConfig();
            Map<String,List<String>> values=new LinkedHashMap<>();
            var fields=root.properties().iterator();
            while(fields.hasNext()) {
                var field=fields.next();var value=field.getValue();
                if(!value.isArray() || value.isEmpty() || value.size()>16) throw invalidConfig();
                List<String> ips=new ArrayList<>();
                for(var ip:value) { if(!ip.isTextual()) throw invalidConfig();ips.add(ip.asText()); }
                values.put(field.getKey(),ips);
            }
            return values;
        } catch(Exception invalid) { throw invalidConfig(); }
    }
    public K6JobPlan.PinnedTarget pin(String url) {
        if(allowed.isEmpty()) throw new Problem(503,"EXECUTION_POLICY_UNCONFIGURED","No execution destinations are configured.");
        var origin=origin(url);var addresses=allowed.get(origin.key());
        if(addresses==null) throw denied(); // Denied origins never reach DNS.
        String host=origin.host().replace("[","").replace("]","");
        List<InetAddress> resolved;
        if(host.contains(":") || host.matches("[0-9.]+")) resolved=List.of(literal(host));
        else resolved=resolve(host);
        if(resolved==null || resolved.isEmpty() || resolved.size()>16) throw denied();
        var pins=new TreeSet<String>();
        for(var address:resolved) {
            if(address==null || !safe(address) || !addresses.contains(address.getHostAddress())) throw denied();
            pins.add(address.getHostAddress());
        }
        return new K6JobPlan.PinnedTarget(origin.host(),pins.first(),origin.port());
    }
    public void verifyPin(String url,K6JobPlan.PinnedTarget expected) {
        if(!pin(url).equals(expected)) throw new Problem(409,"EXECUTION_DESTINATION_CHANGED","The resolved execution destination changed. Prepare a new plan.");
    }
    private List<InetAddress> resolve(String host) {
        Future<List<InetAddress>> task;
        try { task=workers.submit(()->resolver.resolve(host)); }
        catch(RejectedExecutionException busy) { throw new Problem(503,"EXECUTION_DNS_BUSY","Execution destination resolution is busy. Try again later."); }
        try { return task.get(timeoutMillis,TimeUnit.MILLISECONDS); }
        catch(InterruptedException interrupted) { task.cancel(true);Thread.currentThread().interrupt();throw unavailable(); }
        catch(TimeoutException | ExecutionException failed) { task.cancel(true);throw unavailable(); }
    }
    private static Origin origin(String value) {
        try {
            var uri=URI.create(value);String scheme=uri.getScheme()==null?"":uri.getScheme().toLowerCase(Locale.ROOT);
            String host=uri.getHost()==null?"":uri.getHost().toLowerCase(Locale.ROOT);
            if(!(scheme.equals("http") || scheme.equals("https")) || host.isEmpty() || host.endsWith(".") || uri.getUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null || uri.getPort()==0 || uri.getPort()>65535) throw denied();
            String bare=host.replace("[","").replace("]","");
            if(bare.contains(":") || bare.matches("[0-9.]+")) literal(bare);
            else if(bare.length()>253 || !bare.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*") || Arrays.stream(bare.split("\\.")).anyMatch(label->label.length()>63)) throw denied();
            int port=uri.getPort()>0?uri.getPort():scheme.equals("https")?443:80;
            String authority=bare.contains(":")?"["+literal(bare).getHostAddress()+"]":host;
            return new Origin(scheme+"://"+authority+":"+port,host,port);
        } catch(RuntimeException invalid) { throw denied(); }
    }
    private static InetAddress literal(String value) {
        if(value==null || !(value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}") || (value.contains(":") && value.matches("[a-fA-F0-9:]+")))) throw invalidConfig();
        try { return InetAddress.getByName(value); } catch(UnknownHostException invalid) { throw invalidConfig(); }
    }
    private static boolean safe(InetAddress ip) {
        if(ip.isAnyLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress() || ip.isMulticastAddress()) return false;
        byte[] bytes=ip.getAddress();
        if(bytes.length==4 && ((bytes[0]&255)==0 || (bytes[0]&255)>=224)) return false;
        String normalized=ip.getHostAddress();
        return !normalized.equals("100.100.100.200") && !normalized.equals("fd00:ec2:0:0:0:0:0:254");
    }
    private static IllegalArgumentException invalidConfig() { return new IllegalArgumentException("Execution destinations require at most 16 explicit HTTP origins with 1–16 safe literal addresses each."); }
    private static Problem denied() { return new Problem(403,"EXECUTION_DESTINATION_DENIED","This execution origin or resolved address is not allowed."); }
    private static Problem unavailable() { return new Problem(503,"EXECUTION_DNS_UNAVAILABLE","Execution destination resolution did not complete. Try again later."); }
    @Override @PreDestroy public void close() { workers.shutdownNow(); }
}
