package dev.loadpilot.integrations;

import dev.loadpilot.access.AccessResponses;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class ConnectionBodyLimit {
    static final int LIMIT = 32768;
    @Bean FilterRegistrationBean<OncePerRequestFilter> connectionRequestLimit(AccessResponses responses) {
        var filter = new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                boolean scenario = request.getRequestURI().equals("/api/scenarios") || request.getRequestURI().startsWith("/api/scenarios/");
                boolean experiment = request.getRequestURI().startsWith("/api/experiments/");
                if ((!scenario && !experiment && !request.getRequestURI().startsWith("/api/integrations/")) || "GET".equals(request.getMethod())) { chain.doFilter(request,response); return; }
                final int requestLimit = scenario ? 524288 : LIMIT;
                if (request.getContentLengthLong() > requestLimit) {
                    responses.error(response,413,"REQUEST_TOO_LARGE",scenario ? "Scenario request exceeds the 512 KiB limit." : experiment ? "Experiment request exceeds the 32 KiB limit." : "Connection settings exceed the 32 KiB request limit."); return;
                }
                chain.doFilter(new HttpServletRequestWrapper(request) {
                    private ServletInputStream bounded;
                    @Override public ServletInputStream getInputStream() throws IOException {
                        if (bounded != null) return bounded;
                        var source = super.getInputStream();
                        bounded = new ServletInputStream() {
                            int consumed;
                            private void count(int amount) throws IOException { if (amount > 0 && (consumed += amount) > requestLimit) throw new IOException("Request limit exceeded"); }
                            @Override public int read() throws IOException { int value=source.read(); count(value < 0 ? 0 : 1); return value; }
                            @Override public int read(byte[] data,int offset,int length) throws IOException { int amount=source.read(data,offset,Math.min(length,requestLimit-consumed+1)); count(amount); return amount; }
                            @Override public boolean isFinished() { return source.isFinished(); }
                            @Override public boolean isReady() { return source.isReady(); }
                            @Override public void setReadListener(ReadListener listener) { source.setReadListener(listener); }
                            @Override public void close() throws IOException { source.close(); }
                        };
                        return bounded;
                    }
                    @Override public BufferedReader getReader() throws IOException { return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8)); }
                },response);
            }
        };
        var registration = new FilterRegistrationBean<OncePerRequestFilter>(filter);
        registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
