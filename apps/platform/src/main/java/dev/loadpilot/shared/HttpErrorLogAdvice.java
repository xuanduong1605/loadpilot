package dev.loadpilot.shared;

import java.util.Map;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Adds the API's machine-readable error code to the request log. */
@RestControllerAdvice
public final class HttpErrorLogAdvice implements ResponseBodyAdvice<Object> {
    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (response instanceof ServletServerHttpResponse servlet && servlet.getServletResponse().getStatus() >= 400
                && body instanceof Map<?, ?> error && error.get("code") instanceof String code) {
            HttpRequestLog.recordErrorCode(code);
        }
        return body;
    }
}
