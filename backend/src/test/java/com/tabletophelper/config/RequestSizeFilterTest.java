package com.tabletophelper.config;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/** The 1 MB cap on API request bodies (D221). */
class RequestSizeFilterTest {

    private final RequestSizeFilter filter = new RequestSizeFilter();

    @Test
    @DisplayName("An API body whose Content-Length is over 1 MB is a 413 that never reaches the controller")
    void overTheCapByLength() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/characters/x/inventory");
        request.setContent(new byte[(int) RequestSizeFilter.MAX_BODY_BYTES + 1]);
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertEquals(413, response.getStatus());
        assertEquals("{\"error\":\"The request is too large\"}", response.getContentAsString());
        assertNull(chain.getRequest());
    }

    @Test
    @DisplayName("A body of 1 MB passes, and paths outside /api are left alone")
    void atTheCapAndOutsideApi() throws Exception {
        MockHttpServletRequest atCap = new MockHttpServletRequest("PUT", "/api/characters/x/inventory");
        atCap.setContent(new byte[(int) RequestSizeFilter.MAX_BODY_BYTES]);
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(atCap, new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest());

        MockHttpServletRequest other = new MockHttpServletRequest("POST", "/ws/info");
        other.setContent(new byte[(int) RequestSizeFilter.MAX_BODY_BYTES + 1]);
        MockFilterChain otherChain = new MockFilterChain();
        filter.doFilter(other, new MockHttpServletResponse(), otherChain);
        assertSame(other, otherChain.getRequest());
    }

    @Test
    @DisplayName("A body sent without a length stops with an IOException once past 1 MB")
    void overTheCapWithoutLength() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/campaigns") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(new byte[(int) RequestSizeFilter.MAX_BODY_BYTES + 10]);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        HttpServletRequest passed = (HttpServletRequest) chain.getRequest();
        assertThrows(IOException.class, () -> passed.getInputStream().readAllBytes());
    }
}
