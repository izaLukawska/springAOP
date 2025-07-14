package org.lukawska.springaop.versioning;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class ApiVersionExtractor {

    private static final Pattern ACCEPT_VERSION_PATTERN = Pattern.compile("application/vnd\\.myapi\\.(v\\d+)\\+json");

    private static final Pattern URL_VERSION_PATTERN = Pattern.compile("/v(\\d+)/");

    public String extractRequestedApiVersion(HttpServletRequest request) {
        String version;

        version = extractVersionFromAcceptHeader(request);
        if (version != null) {
            return version;
        }

        version = extractVersionFromHeader(request);
        if (version != null) {
            return version;
        }

        version = extractVersionFromUrlPath(request);
        if (version != null) {
            return version;
        }

        version = extractVersionFromQueryParameter(request);
        if (version != null) {
            return version;
        }

        log.debug("No API version found in any source for URI: {}", request.getRequestURI());
        return null;
    }

    private String extractVersionFromAcceptHeader(HttpServletRequest request) {
        String acceptHeader = request.getHeader("Accept");
        if (acceptHeader != null) {
            Matcher matcher = ACCEPT_VERSION_PATTERN.matcher(acceptHeader);
            if (matcher.find()) {
                String version = matcher.group(1);
                if (version != null && !version.isEmpty()) {
                    log.debug("Extracted API Version from Accept header: {}", version);
                    return version;
                }
            }
        }

        log.debug("API Version not found in Accept header.");
        return null;
    }

    private String extractVersionFromHeader(HttpServletRequest request) {
        String version = request.getHeader("X-API-Version");

        if (version != null && !version.isEmpty()) {
            log.debug("Extracted API Version from X-API-Version header: {}", version);
            return version;
        }

        log.debug("API Version not found in X-API-Version header.");
        return null;
    }

    private String extractVersionFromUrlPath(HttpServletRequest request) {
        String requestURI = request.getRequestURI();

        Matcher uriMatcher = URL_VERSION_PATTERN.matcher(requestURI);
        if (uriMatcher.find()) {
            String version = uriMatcher.group(0).replaceAll("/", "");
            if (!version.isEmpty()) {
                log.debug("Extracted API Version from URL path: {}", version);
                return version;
            }
        }

        log.debug("API Version not found in URL path.");
        return null;
    }

    private String extractVersionFromQueryParameter(HttpServletRequest request) {
        String version = request.getParameter("version");
        if (version != null && !version.isEmpty()) {
            log.debug("Extracted API Version from query parameter: {}", version);
            return version;
        }

        log.debug("API Version not found in query parameter.");
        return null;
    }
}
