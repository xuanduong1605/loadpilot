package dev.loadpilot.integrations.k6;

import io.kubernetes.client.openapi.ApiClient;
import java.net.URI;
import java.time.Duration;
import okhttp3.*;
import okio.*;

/** Shared bounds for exclusive, preflighted worker clients. */
final class KubernetesWorkerTransport {
    private KubernetesWorkerTransport() {}
    static ApiClient bound(ApiClient ownedClient) {
        URI uri=URI.create(ownedClient.getBasePath());
        if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null || !ownedClient.isVerifyingSsl())
            throw new IllegalArgumentException("Worker collection requires a TLS-verified scoped client.");
        ApiClient client=ownedClient;
        client.setDebugging(false);
        client.setHttpClient(client.getHttpClient().newBuilder().callTimeout(Duration.ofSeconds(2)).connectTimeout(Duration.ofSeconds(2)).readTimeout(Duration.ofSeconds(2))
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).addInterceptor(chain->{
                    var response=chain.proceed(chain.request());var body=response.body();
                    if(body==null) return response;
                    if(body.contentLength()>262144) { response.close();throw new java.io.IOException("Worker response too large."); }
                    var source=new ForwardingSource(body.source()) {
                        long consumed;
                        @Override public long read(Buffer sink,long amount) throws java.io.IOException {
                            long count=super.read(sink,Math.min(amount,262145-consumed));
                            if(count>0 && (consumed+=count)>262144) throw new java.io.IOException("Worker response too large.");return count;
                        }
                    };
                    return response.newBuilder().body(new ResponseBody() {
                        final BufferedSource bounded=Okio.buffer(source);
                        @Override public MediaType contentType() { return body.contentType(); }
                        @Override public long contentLength() { return body.contentLength(); }
                        @Override public BufferedSource source() { return bounded; }
                    }).build();
                }).build());
        return client;
    }
}
