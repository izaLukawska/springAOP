package org.lukawska.springaop.versioning;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lukawska.springaop.versioning.extractor.ApiVersionExtractor;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class ApiVersionInterceptor implements HandlerInterceptor {

    private final ApiVersionExtractor apiVersionExtractor;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws Exception {

        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return handleNonHandlerMethod(request, handler);
        }

        String controllerName = handlerMethod.getBeanType().getSimpleName();
        String methodName = handlerMethod.getMethod().getName();
        log.info("Request to {}.{} - URI: {}, Method: {}",
            controllerName, methodName, request.getRequestURI(), request.getMethod());

        ApiVersion apiVersionAnnotation = getApiVersionAnnotation(handlerMethod);
        if (apiVersionAnnotation == null) {
            log.info("Skipping version check (no @ApiVersion found) for {}.{}",
                controllerName, methodName);
            return true;
        }

        String requestedVersion = apiVersionExtractor.extractRequestedApiVersion(request);
        String finalVersionToUse = resolveApiVersion(response, apiVersionAnnotation,
            requestedVersion, controllerName, methodName);

        if (finalVersionToUse == null) {
            return false;
        }

        if (!validateAndHandleVersion(response, apiVersionAnnotation,
            finalVersionToUse, controllerName, methodName)) {
            return false;
        }

        request.setAttribute("startTime", System.currentTimeMillis());
        request.setAttribute("api.version.used", finalVersionToUse);

        response.setHeader("X-API-Version", finalVersionToUse);
        log.debug("Setting X-API-Version header {}", finalVersionToUse);

        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request,
                                @NonNull HttpServletResponse response,
                                @NonNull Object handler,
                                Exception ex) {
        Long startTime = (Long) request.getAttribute("startTime");
        if (startTime != null) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.info("Request completed in {} ms, Status: {}",
                executionTime, response.getStatus());
        }

        if (ex != null) {
            log.error("Request failed with exception: {}", ex.getMessage());
        }
    }

    private String resolveApiVersion(HttpServletResponse response,
                                     ApiVersion apiVersionAnnotation,
                                     String requestedVersion,
                                     String controllerName,
                                     String methodName) throws Exception {

        if (requestedVersion != null && !requestedVersion.isEmpty()) {
            return requestedVersion.trim();
        }

        if (apiVersionAnnotation.versions().length == 0) {
            log.error("No supported versions defined for: {}.{}", controllerName, methodName);

            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "API versioning misconfiguration.");
            return null;
        }

        if (apiVersionAnnotation.requiresVersion()) {
            log.warn("API version required but not provided for {}.{}.", controllerName, methodName);
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "API version is required.");
            return null;
        }

        String defaultFallbackVersion = apiVersionAnnotation.versions()[0].trim();
        log.info("Using default API version '{}' for {}.{}",
            defaultFallbackVersion, controllerName, methodName);

        return defaultFallbackVersion;
    }

    private boolean validateAndHandleVersion(HttpServletResponse response,
                                             ApiVersion apiVersionAnnotation,
                                             String finalVersionToUse,
                                             String controllerName,
                                             String methodName) throws Exception {

        Set<String> supportedVersions = Arrays.stream(apiVersionAnnotation.versions())
            .map(String::trim)
            .collect(Collectors.toSet());

        if (!supportedVersions.contains(finalVersionToUse)) {
            log.warn("Unsupported API version '{}' for {}.{}. Supported: {}",
                finalVersionToUse, controllerName, methodName, supportedVersions);
            response.sendError(HttpServletResponse.SC_NOT_FOUND,
                "API version not supported: " + finalVersionToUse);
            return false;
        }

        handleDeprecatedVersion(response, apiVersionAnnotation,
            finalVersionToUse, controllerName, methodName);

        return true;
    }

    private void handleDeprecatedVersion(@NonNull HttpServletResponse response,
                                         @NonNull ApiVersion apiVersionAnnotation,
                                         @NonNull String requestedVersion,
                                         String controllerName, String methodName) {
        if (!apiVersionAnnotation.deprecated().isEmpty()
            && requestedVersion.equals(apiVersionAnnotation.deprecated())) {

            response.addHeader("Warning", "299 - This API version is deprecated (future remove)");
            log.warn("Accessed deprecated API version '{}' for {}.{}",
                requestedVersion, controllerName, methodName);
        }
    }

    private boolean handleNonHandlerMethod(@NonNull HttpServletRequest request, @NonNull Object handler) {
        log.debug("Skipping check (not a handler method): {}", handler.getClass().getName());
        request.setAttribute("startTime", System.currentTimeMillis());
        return true;
    }

    private ApiVersion getApiVersionAnnotation(HandlerMethod handlerMethod) {
        ApiVersion annotation = AnnotationUtils.findAnnotation(handlerMethod.getMethod(), ApiVersion.class);
        if (annotation != null) {
            return annotation;
        }

        return AnnotationUtils.findAnnotation(handlerMethod.getBeanType(), ApiVersion.class);
    }
}
