/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.frontend.kernel.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;
import org.springframework.session.web.http.SessionRepositoryFilter;

/**
 * Pins the exact {@code Set-Cookie} of the session cookie as Spring Session's {@link
 * SessionRepositoryFilter} writes it with the shipped {@code application.yml} (REQ-SEC-025).
 *
 * <p>The filter comes from Spring Session's own configuration, the same one the Redis-indexed
 * session configuration extends, so the test fails when the configured attributes stop reaching the
 * cookie.
 */
class SessionCookieAttributesTest {

  private static final Pattern PINNED_SET_COOKIE =
      Pattern.compile(
          "__Host-SESSION=[A-Za-z0-9+/=]+; Max-Age=2592000; Expires=[^;]+ GMT; Path=/; Secure;"
              + " HttpOnly; SameSite=Lax");

  private final WebApplicationContextRunner runner =
      new WebApplicationContextRunner()
          .withInitializer(
              context -> context.getEnvironment().getPropertySources().addLast(applicationYml()))
          .withUserConfiguration(InMemorySessionConfig.class);

  @Test
  void theSessionRepositoryFilterWritesThePinnedSessionCookie() {
    runner
        .withUserConfiguration(SessionCookieSerializerConfig.class)
        .run(context -> assertPinned(newSessionSetCookie(context)));
  }

  @Test
  void thePinRejectsTheCookieSpringSessionWritesWithoutTheConfiguredSerializer() {
    runner.run(
        context -> {
          String header = newSessionSetCookie(context);
          assertThatThrownBy(() -> assertPinned(header)).isInstanceOf(AssertionError.class);
        });
  }

  @Test
  void thePinRejectsAStrictSessionCookie() {
    runner
        .withUserConfiguration(SessionCookieSerializerConfig.class)
        .withPropertyValues("server.servlet.session.cookie.same-site=strict")
        .run(
            context -> {
              String header = newSessionSetCookie(context);
              assertThat(header).endsWith("; SameSite=Strict");
              assertThatThrownBy(() -> assertPinned(header)).isInstanceOf(AssertionError.class);
            });
  }

  private static void assertPinned(String header) {
    assertThat(header).as("the session Set-Cookie header").matches(PINNED_SET_COOKIE);
  }

  /**
   * Runs one plain-HTTP request through the context's {@link SessionRepositoryFilter} that creates
   * a session, so every attribute must come from configuration rather than from the request.
   *
   * @param context the started application context
   * @return the single {@code Set-Cookie} header of the response
   * @throws Exception if the filter fails
   */
  private static String newSessionSetCookie(@NotNull ApplicationContext context) throws Exception {
    SessionRepositoryFilter<?> filter = context.getBean(SessionRepositoryFilter.class);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
    request.setSecure(false);
    RawSetCookieRecorder response = new RawSetCookieRecorder(new MockHttpServletResponse());
    filter.doFilter(request, response, new MockFilterChain(new SessionCreatingServlet()));
    assertThat(response.setCookies).hasSize(1);
    return response.setCookies.getFirst();
  }

  private static PropertySource<?> applicationYml() {
    try {
      return new YamlPropertySourceLoader()
          .load("application.yml", new ClassPathResource("application.yml"))
          .getFirst();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** An in-memory Spring Session repository with the server properties bound. */
  @Configuration(proxyBeanMethods = false)
  @EnableSpringHttpSession
  @EnableConfigurationProperties(ServerProperties.class)
  static class InMemorySessionConfig {

    /**
     * Provides the session store the filter writes to.
     *
     * @return an empty in-memory repository
     */
    @Bean
    MapSessionRepository sessionRepository() {
      return new MapSessionRepository(new ConcurrentHashMap<>());
    }
  }

  /**
   * Records each {@code Set-Cookie} header exactly as written, since {@link
   * MockHttpServletResponse} re-renders cookie headers in its own attribute order.
   */
  private static final class RawSetCookieRecorder extends HttpServletResponseWrapper {

    private final List<String> setCookies = new ArrayList<>();

    RawSetCookieRecorder(HttpServletResponse response) {
      super(response);
    }

    @Override
    public void addHeader(String name, String value) {
      if (HttpHeaders.SET_COOKIE.equalsIgnoreCase(name)) {
        setCookies.add(value);
      }
      super.addHeader(name, value);
    }

    @Override
    public void setHeader(String name, String value) {
      if (HttpHeaders.SET_COOKIE.equalsIgnoreCase(name)) {
        setCookies.clear();
        setCookies.add(value);
      }
      super.setHeader(name, value);
    }
  }

  /** A servlet that only creates the HTTP session. */
  private static final class SessionCreatingServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) {
      request.getSession(true);
    }
  }
}
