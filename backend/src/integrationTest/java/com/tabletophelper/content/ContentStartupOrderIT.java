package com.tabletophelper.content;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The content loads before the web server accepts a request (D233): a real embedded Tomcat on a fixed free port, and
 * a ContentStartup whose load records whether the port answers and whether the server has said it started. No
 * database: the loaders are mocked, since only the order is under test. It needs no Docker, but it starts a server,
 * so it is in the integration tier.
 */
class ContentStartupOrderIT {

    private static final List<String> EVENTS = new CopyOnWriteArrayList<>();
    private static int port;

    @Configuration(proxyBeanMethods = false)
    static class WebServerWithContent {

        @Bean
        TomcatServletWebServerFactory webServerFactory() {
            return new TomcatServletWebServerFactory(port);
        }

        @Bean
        ContentStartup contentStartup() {
            ContentLoading loading = mock(ContentLoading.class);
            when(loading.load()).thenAnswer(invocation -> {
                EVENTS.add("content loading, port " + (accepts(port) ? "accepting" : "closed"));
                return List.of();
            });
            return new ContentStartup(loading, "apply", "");
        }

        @Bean
        ApplicationListener<ServletWebServerInitializedEvent> webServerStarted() {
            return event -> EVENTS.add("web server started");
        }
    }

    private static boolean accepts(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    @DisplayName("The content loads while the server's port is still closed, and the server starts after it (D233)")
    void contentLoadsBeforeTheServerAccepts() throws IOException {
        port = freePort();
        EVENTS.clear();
        SpringApplication application = new SpringApplication(WebServerWithContent.class);
        application.setWebApplicationType(WebApplicationType.SERVLET);

        try (ConfigurableApplicationContext context = application.run("--spring.main.banner-mode=off")) {
            assertThat(EVENTS).containsExactly("content loading, port closed", "web server started");
            assertThat(accepts(port)).as("the port accepts once the context has started").isTrue();
        }
    }
}
