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

package de.greluc.krt.profit.basetool.backend.interceptor;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.backend.annotation.ApiDeprecation;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

class DeprecationInterceptorTest {

  private DeprecationInterceptor interceptor;
  private HttpServletRequest request;
  private MockHttpServletResponse response;

  @BeforeEach
  void setUp() {
    interceptor = new DeprecationInterceptor();
    request = new MockHttpServletRequest();
    response = new MockHttpServletResponse();
  }

  /** No annotations at all → no deprecation headers, and chain continues. */
  @Test
  void preHandle_withCleanHandler_shouldNotAddHeaders() throws Exception {
    // Given
    HandlerMethod handler = handlerMethodOf(SampleController.class, "clean");

    // When
    boolean proceed = interceptor.preHandle(request, response, handler);

    // Then
    assertTrue(proceed);
    assertNull(response.getHeader("Deprecation"));
    assertNull(response.getHeader("Sunset"));
    assertNull(response.getHeader("Link"));
  }

  /** Non-HandlerMethod handler (e.g. ResourceHandler) is ignored. */
  @Test
  void preHandle_withNonHandlerMethod_shouldShortCircuit() throws Exception {
    // Given
    Object handler = new Object();

    // When
    boolean proceed = interceptor.preHandle(request, response, handler);

    // Then
    assertTrue(proceed);
    assertNull(response.getHeader("Deprecation"));
  }

  /** Method-level {@link Deprecated} alone is enough to emit the header. */
  @Test
  void preHandle_withJavaDeprecatedMethod_shouldAddDeprecationTrue() throws Exception {
    // Given
    HandlerMethod handler = handlerMethodOf(SampleController.class, "javaDeprecatedOnly");

    // When
    interceptor.preHandle(request, response, handler);

    // Then
    assertEquals("true", response.getHeader("Deprecation"));
    // No ApiDeprecation → no Sunset/Link
    assertNull(response.getHeader("Sunset"));
    assertNull(response.getHeader("Link"));
  }

  /** Full ApiDeprecation with sunset + replacement emits all three headers. */
  @Test
  void preHandle_withFullApiDeprecation_shouldAddDeprecationSunsetAndLink() throws Exception {
    // Given
    HandlerMethod handler = handlerMethodOf(SampleController.class, "fullyDeprecated");

    // When
    interceptor.preHandle(request, response, handler);

    // Then
    assertEquals("true", response.getHeader("Deprecation"));
    // Sunset header is HTTP-date for 2027-01-01 00:00:00 UTC → "Fri, 01 Jan 2027 00:00:00 GMT"
    assertEquals("Fri, 01 Jan 2027 00:00:00 GMT", response.getHeader("Sunset"));
    assertEquals("</api/v2/things>; rel=\"alternate\"", response.getHeader("Link"));
  }

  /** Empty sunset / replacement attributes must not surface as headers. */
  @Test
  void preHandle_withApiDeprecationWithoutSunsetOrReplacement_shouldOnlyAddDeprecation()
      throws Exception {
    // Given
    HandlerMethod handler = handlerMethodOf(SampleController.class, "minimalApiDeprecation");

    // When
    interceptor.preHandle(request, response, handler);

    // Then
    assertEquals("true", response.getHeader("Deprecation"));
    assertNull(response.getHeader("Sunset"));
    assertNull(response.getHeader("Link"));
  }

  /** Malformed sunset date is logged and skipped — no Sunset header emitted, no exception. */
  @Test
  void preHandle_withInvalidSunsetDate_shouldEmitDeprecationButNoSunset() throws Exception {
    // Given
    HandlerMethod handler = handlerMethodOf(SampleController.class, "badSunsetDate");

    // When
    assertDoesNotThrow(() -> interceptor.preHandle(request, response, handler));

    // Then
    assertEquals("true", response.getHeader("Deprecation"));
    assertNull(response.getHeader("Sunset"));
    // Replacement still emitted
    assertEquals("</api/v2/foo>; rel=\"alternate\"", response.getHeader("Link"));
  }

  /** Class-level ApiDeprecation falls through when the method has none. */
  @Test
  void preHandle_classLevelApiDeprecation_shouldApplyToUnannotatedMethod() throws Exception {
    // Given
    HandlerMethod handler = handlerMethodOf(DeprecatedClassSample.class, "anything");

    // When
    interceptor.preHandle(request, response, handler);

    // Then
    assertEquals("true", response.getHeader("Deprecation"));
    assertEquals("</api/v3/legacy>; rel=\"alternate\"", response.getHeader("Link"));
  }

  /** Class-level @Deprecated also flips the Deprecation header without ApiDeprecation. */
  @Test
  void preHandle_classLevelJavaDeprecated_shouldApplyToUnannotatedMethod() throws Exception {
    // Given
    HandlerMethod handler = handlerMethodOf(JavaDeprecatedClassSample.class, "anything");

    // When
    interceptor.preHandle(request, response, handler);

    // Then
    assertEquals("true", response.getHeader("Deprecation"));
    assertNull(response.getHeader("Sunset"));
    assertNull(response.getHeader("Link"));
  }

  /**
   * A malformed {@code sunset} value warns at most once per handler, not on every request — {@code
   * sunset()} is a compile-time constant, so re-warning per call would be pure noise.
   */
  @Test
  void preHandle_withInvalidSunsetDate_warnsAtMostOncePerHandler() throws Exception {
    Logger logger = (Logger) LoggerFactory.getLogger(DeprecationInterceptor.class);
    Level original = logger.getLevel();
    logger.setLevel(Level.WARN);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      HandlerMethod handler = handlerMethodOf(SampleController.class, "badSunsetDate");
      interceptor.preHandle(request, new MockHttpServletResponse(), handler);
      interceptor.preHandle(request, new MockHttpServletResponse(), handler);
      interceptor.preHandle(request, new MockHttpServletResponse(), handler);

      long warnings =
          appender.list.stream()
              .filter(e -> e.getLevel() == Level.WARN)
              .filter(e -> e.getFormattedMessage().contains("Invalid sunset date format"))
              .count();
      assertEquals(1, warnings);
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(original);
    }
  }

  // ─── helpers ────────────────────────────────────────────────────────────────

  private static HandlerMethod handlerMethodOf(Class<?> beanClass, String methodName)
      throws Exception {
    Method method = beanClass.getMethod(methodName);
    Object bean = beanClass.getDeclaredConstructor().newInstance();
    return new HandlerMethod(bean, method);
  }

  @SuppressWarnings("unused") // reflectively referenced
  static class SampleController {
    public SampleController() {}

    public String clean() {
      return "ok";
    }

    @Deprecated
    public String javaDeprecatedOnly() {
      return "ok";
    }

    @ApiDeprecation(sunset = "2027-01-01", replacement = "/api/v2/things")
    public String fullyDeprecated() {
      return "ok";
    }

    @ApiDeprecation
    public String minimalApiDeprecation() {
      return "ok";
    }

    @ApiDeprecation(sunset = "not-a-date", replacement = "/api/v2/foo")
    public String badSunsetDate() {
      return "ok";
    }
  }

  @ApiDeprecation(sunset = "", replacement = "/api/v3/legacy")
  @SuppressWarnings("unused")
  static class DeprecatedClassSample {
    public DeprecatedClassSample() {}

    public String anything() {
      return "ok";
    }
  }

  @Deprecated
  @SuppressWarnings("unused")
  static class JavaDeprecatedClassSample {
    public JavaDeprecatedClassSample() {}

    public String anything() {
      return "ok";
    }
  }
}
