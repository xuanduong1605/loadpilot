package dev.loadpilot.integrations;

import io.kubernetes.client.openapi.ApiClient;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/** One destination, fixed DNS resolution, validated TLS and bounded GET responses. */
public final class ReadOnlyHttp implements AutoCloseable {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private final ApiClient api;
    private final OkHttpClient http;
    private final String token;
    public ReadOnlyHttp(EndpointPolicy policy, String base, String ca, String token, boolean tls) throws IOException {
        URI uri = policy.validate(base, tls);
        String host = uri.getHost().replace("[", "").replace("]", "");
        List<InetAddress> addresses = policy.resolve(host);
        this.token = token;
        api = new ApiClient().setBasePath(base.replaceAll("/+$", ""));
        if (!ca.isBlank()) api.setSslCaCert(new ByteArrayInputStream(ca.getBytes(StandardCharsets.US_ASCII)));
        api.setApiKey(token); api.setApiKeyPrefix("Bearer");
        http = api.getHttpClient().newBuilder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(6, TimeUnit.SECONDS).dns(requested -> {
                    if (!host.equalsIgnoreCase(requested)) throw new UnknownHostException("Destination changed");
                    return addresses;
                }).addInterceptor(chain -> {
                    HttpUrl destination = chain.request().url();
                    int port = uri.getPort() < 0 ? "https".equals(uri.getScheme()) ? 443 : 80 : uri.getPort();
                    if (!"GET".equals(chain.request().method()) || !host.equalsIgnoreCase(destination.host())
                            || port != destination.port() || !uri.getScheme().equals(destination.scheme()))
                        throw new IOException("Read-only destination policy rejected request");
                    Response response = chain.proceed(chain.request());
                    if (response.body() == null) return response;
                    try (ResponseBody body = response.body()) {
                        if (body.contentLength() > MAX_BYTES) throw new ResponseLimitException();
                        byte[] data = body.byteStream().readNBytes(MAX_BYTES + 1);
                        if (data.length > MAX_BYTES) throw new ResponseLimitException();
                        return response.newBuilder().body(ResponseBody.create(data, body.contentType())).build();
                    }
                }).build();
        api.setHttpClient(http);
    }
    public ApiClient api() { return api; }
    public record Result(int status, String body) {}
    public Result get(String url) throws IOException {
        Request.Builder request = new Request.Builder().url(url).get().header("Accept", "application/json");
        if (!token.isEmpty()) request.header("Authorization", "Bearer " + token);
        try (Response response = http.newCall(request.build()).execute()) {
            return new Result(response.code(), response.body() == null ? "" : response.body().string());
        }
    }
    @Override public void close() { http.connectionPool().evictAll(); http.dispatcher().executorService().shutdown(); }
    public static final class ResponseLimitException extends IOException {}
}
