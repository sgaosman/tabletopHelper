package com.tabletophelper.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Caps every API request body at {@link #MAX_BODY_BYTES} (D221). Tomcat's maxPostSize and Spring's multipart limits
 * don't cover a JSON body, and the largest the app takes (a character's notes and inventory) is far smaller. A body
 * whose Content-Length is over the cap is a 413 before anything reads it; one sent without a length (chunked) is cut
 * off at the cap, which the JSON reader answers as an unreadable body, a 400. Caddy caps bodies at the edge too.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestSizeFilter extends OncePerRequestFilter {

    static final long MAX_BODY_BYTES = 1024 * 1024;

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith(request.getContextPath() + "/api/");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            log.warn("A body of {} bytes at {} from {}; refused", request.getContentLengthLong(),
                    request.getRequestURI(), request.getRemoteAddr());
            response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"error\":\"The request is too large\"}");
            return;
        }
        filterChain.doFilter(request.getContentLengthLong() < 0 ? new CappedRequest(request) : request, response);
    }

    /** A request whose body, sent without a length, stops with an IOException once past the cap. */
    static final class CappedRequest extends HttpServletRequestWrapper {

        private ServletInputStream capped;

        CappedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (capped == null) {
                capped = new CappedInputStream(super.getInputStream());
            }
            return capped;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class CappedInputStream extends ServletInputStream {

        private final ServletInputStream in;
        private long read;

        CappedInputStream(ServletInputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) count(1);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) count(n);
            return n;
        }

        private void count(int n) throws IOException {
            read += n;
            if (read > MAX_BODY_BYTES) {
                throw new IOException("The request body is over " + MAX_BODY_BYTES + " bytes");
            }
        }

        @Override
        public boolean isFinished() {
            return in.isFinished();
        }

        @Override
        public boolean isReady() {
            return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            in.setReadListener(listener);
        }
    }
}
