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

package de.greluc.krt.profit.basetool.backend.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.support.AppProblemProperties;
import de.greluc.krt.profit.basetool.backend.support.ProblemResponseFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.method.ControllerAdviceBean;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Guards the {@code @Order} on {@link GlobalExceptionHandler} against a second, competing
 * {@code @ControllerAdvice}.
 *
 * <p>{@code application.yml} sets {@code spring.mvc.problemdetails.enabled: true}, so Spring Boot
 * registers its own {@code ProblemDetailsExceptionHandler} — a {@link
 * ResponseEntityExceptionHandler} subclass annotated {@code @ControllerAdvice} and
 * {@code @Order(0)}. An unordered {@code @ControllerAdvice} sits at {@code LOWEST_PRECEDENCE} and
 * loses to it for every exception type both declare, which shipped to production: {@code
 * MethodArgumentNotValidException} was answered by Spring's advice with a bare {@link
 * org.springframework.http.ProblemDetail} — no {@code code}, no {@code correlationId}, no {@code
 * fieldErrors}, untranslated {@code "Invalid request content."} — so the frontend could not place
 * an inline error at the offending field and {@link
 * GlobalExceptionHandler#handleValidationExceptions}'s diagnostic WARN log never ran. A bank
 * employee confirming a booking request saw only "some fields are invalid", and the production log
 * held nothing at all.
 *
 * <p>{@link GlobalExceptionHandlerTest} cannot catch this: it invokes the handler methods directly
 * and so is blind to which advice bean Spring MVC would actually pick. This test instead drives
 * Spring's own advice discovery ({@link ControllerAdviceBean#findAnnotatedBeans}, which sorts by
 * order) and reproduces {@code ExceptionHandlerExceptionResolver}'s first-advice-wins lookup across
 * the sorted list.
 *
 * <p>Spring Boot's {@code ProblemDetailsExceptionHandler} is package-private and cannot be
 * instantiated here, so {@link CompetingSpringAdvice} stands in for it with the same shape and the
 * same {@code @Order(0)}.
 */
class GlobalExceptionHandlerAdviceOrderTest {

  /**
   * Stand-in for Spring Boot's package-private {@code ProblemDetailsExceptionHandler}: same base
   * class, same advice annotation, same order.
   */
  @ControllerAdvice
  @Order(0)
  static class CompetingSpringAdvice extends ResponseEntityExceptionHandler {}

  /**
   * Builds a context holding both advice beans and returns them in the order Spring MVC would
   * consult them.
   *
   * @param context the context to register the beans into; the caller owns its lifecycle
   * @return the discovered advice beans, sorted by precedence
   */
  private static List<ControllerAdviceBean> sortedAdvice(
      AnnotationConfigApplicationContext context) {
    // Registered competitor-first so a passing result can never be an artefact of definition order.
    context.registerBean("competingSpringAdvice", CompetingSpringAdvice.class);
    context.registerBean(
        "globalExceptionHandler",
        GlobalExceptionHandler.class,
        GlobalExceptionHandlerAdviceOrderTest::newHandler);
    context.refresh();
    return ControllerAdviceBean.findAnnotatedBeans(context);
  }

  /**
   * Creates a {@link GlobalExceptionHandler} with real collaborators, so the bean under test is the
   * production class rather than a mock whose annotations could diverge.
   *
   * @return a ready handler instance
   */
  private static GlobalExceptionHandler newHandler() {
    AppProblemProperties props = new AppProblemProperties();
    props.setBaseUri("https://profit-base.online/problems/");
    ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
    messageSource.setBasename("messages");
    messageSource.setDefaultEncoding("UTF-8");
    messageSource.setFallbackToSystemLocale(false);
    return new GlobalExceptionHandler(
        props, new ProblemResponseFactory(props), messageSource, new SimpleMeterRegistry());
  }

  /**
   * Reproduces {@code ExceptionHandlerExceptionResolver}'s lookup: walk the sorted advice beans and
   * take the first one declaring a handler for the exception type.
   *
   * @param advice the advice beans in precedence order
   * @param exceptionType the exception to resolve a handler for
   * @return the winning handler method, or {@code null} when no advice declares one
   */
  private static Method resolveAcrossAdvice(
      List<ControllerAdviceBean> advice, Class<? extends Throwable> exceptionType) {
    for (ControllerAdviceBean bean : advice) {
      Class<?> beanType = bean.getBeanType();
      if (beanType == null) {
        continue;
      }
      Method method =
          new ExceptionHandlerMethodResolver(beanType).resolveMethodByExceptionType(exceptionType);
      if (method != null) {
        return method;
      }
    }
    return null;
  }

  /**
   * The ordering itself: our advice must be consulted before Spring's. Asserted separately from the
   * per-exception resolution so a dropped {@code @Order} fails with an unambiguous message rather
   * than as six confusing handler mismatches.
   */
  @Test
  void ourAdviceIsConsultedBeforeSpringsProblemDetailsAdvice() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      List<ControllerAdviceBean> advice = sortedAdvice(context);

      int ours = indexOf(advice, GlobalExceptionHandler.class);
      int springs = indexOf(advice, CompetingSpringAdvice.class);
      assertTrue(ours >= 0, "GlobalExceptionHandler must be discovered as a @ControllerAdvice");
      assertTrue(springs >= 0, "the competing Spring advice must be discovered too");
      assertTrue(
          ours < springs,
          "GlobalExceptionHandler must outrank Spring's @Order(0) problemdetails advice, but was"
              + " consulted at index "
              + ours
              + " vs "
              + springs
              + " — the @Order on GlobalExceptionHandler is load-bearing, see its Javadoc");
    }
  }

  /**
   * The exception types declared by BOTH this advice and {@link ResponseEntityExceptionHandler} —
   * exactly the set the ordering decides.
   *
   * @return the overlapping exception types
   */
  private static Stream<Class<? extends Throwable>> springNativeOverlap() {
    return Stream.of(
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        HttpRequestMethodNotSupportedException.class,
        NoResourceFoundException.class,
        ErrorResponseException.class);
  }

  /**
   * Every exception type both advices declare must resolve to ours, so the response keeps {@code
   * code}, {@code correlationId} and (for validation) {@code fieldErrors}.
   *
   * @param exceptionType the exception class both advices handle
   */
  @ParameterizedTest
  @MethodSource("springNativeOverlap")
  void springNativeExceptionsResolveToOurAdvice(Class<? extends Throwable> exceptionType) {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      List<ControllerAdviceBean> advice = sortedAdvice(context);

      Method winner = resolveAcrossAdvice(advice, exceptionType);

      assertNotNull(winner, "no advice declared a handler for " + exceptionType.getName());
      assertEquals(
          GlobalExceptionHandler.class,
          winner.getDeclaringClass(),
          exceptionType.getName()
              + " must be handled by GlobalExceptionHandler, not by Spring's advice — otherwise the"
              + " response loses code/correlationId/fieldErrors and the detail is untranslated");
    }
  }

  /**
   * Pins the validation handler specifically, since it is the one whose {@code fieldErrors} the
   * frontend needs to render an inline error instead of a generic toast.
   */
  @Test
  void validationFailuresResolveToTheFieldErrorProducingHandler() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      Method winner =
          resolveAcrossAdvice(sortedAdvice(context), MethodArgumentNotValidException.class);

      assertNotNull(winner);
      assertEquals("handleValidationExceptions", winner.getName());
    }
  }

  /**
   * Finds an advice bean's position in the precedence-sorted list.
   *
   * @param advice the sorted advice beans
   * @param type the advice class to locate
   * @return the zero-based index, or {@code -1} when absent
   */
  private static int indexOf(List<ControllerAdviceBean> advice, Class<?> type) {
    for (int i = 0; i < advice.size(); i++) {
      if (type.equals(advice.get(i).getBeanType())) {
        return i;
      }
    }
    return -1;
  }
}
