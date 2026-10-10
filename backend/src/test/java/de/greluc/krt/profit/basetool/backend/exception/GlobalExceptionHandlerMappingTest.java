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

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
import jakarta.validation.ConstraintViolationException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import org.hibernate.StaleObjectStateException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Pins which handler method of {@link GlobalExceptionHandler} answers which exception type, so the
 * split of the advice into families (J-S10b) cannot drop, add or re-route a mapping: there is one
 * advice, one resolver, and the same method for every type it handled before.
 */
class GlobalExceptionHandlerMappingTest {

  /** Every exception type the advice maps, with the name of the method that answers it. */
  private static final Map<Class<? extends Throwable>, String> EXPECTED = expected();

  /** The advice resolves every pinned type to the pinned method, and maps nothing else. */
  @Test
  void everyExceptionTypeResolvesToItsPinnedHandler() {
    ExceptionHandlerMethodResolver resolver =
        new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

    Map<Class<? extends Throwable>, String> actual = new LinkedHashMap<>();
    for (Class<? extends Throwable> type : EXPECTED.keySet()) {
      Method method = resolver.resolveMethodByExceptionType(type);
      actual.put(type, method == null ? null : method.getName());
    }

    assertThat(actual).isEqualTo(EXPECTED);
  }

  /** The advice declares exactly the pinned handler methods, none of them twice. */
  @Test
  void theAdviceDeclaresExactlyThePinnedHandlerMethods() {
    Map<String, Long> declared =
        Arrays.stream(GlobalExceptionHandler.class.getMethods())
            .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
            .collect(Collectors.groupingBy(Method::getName, Collectors.counting()));

    assertThat(declared.keySet())
        .containsExactlyInAnyOrderElementsOf(new HashSet<>(EXPECTED.values()));
    assertThat(declared.values()).containsOnly(1L);
  }

  /**
   * Builds the pinned mapping.
   *
   * @return exception type to handler method name
   */
  private static Map<Class<? extends Throwable>, String> expected() {
    Map<Class<? extends Throwable>, String> out = new LinkedHashMap<>();
    String optimistic = "handleOptimisticLockingFailure";
    out.put(ObjectOptimisticLockingFailureException.class, optimistic);
    out.put(OptimisticLockException.class, optimistic);
    out.put(StaleObjectStateException.class, optimistic);
    out.put(PessimisticLockingFailureException.class, "handlePessimisticLocking");
    out.put(AuthenticationException.class, "handleAuthentication");
    out.put(AccessDeniedException.class, "handleAccessDenied");
    out.put(AuthorizationDeniedException.class, "handleAccessDenied");
    out.put(MethodArgumentNotValidException.class, "handleValidationExceptions");
    out.put(ConstraintViolationException.class, "handleConstraintViolation");
    out.put(AppException.class, "handleAppException");
    out.put(IllegalArgumentException.class, "handleIllegalArgument");
    out.put(IllegalStateException.class, "handleIllegalState");
    out.put(ResponseStatusException.class, "handleResponseStatus");
    out.put(ErrorResponseException.class, "handleErrorResponseException");
    out.put(HttpMessageNotReadableException.class, "handleHttpMessageNotReadable");
    out.put(DataIntegrityViolationException.class, "handleDataIntegrityViolation");
    out.put(MethodArgumentTypeMismatchException.class, "handleTypeMismatch");
    out.put(HttpMediaTypeNotSupportedException.class, "handleMediaTypeNotSupported");
    out.put(HttpMediaTypeNotAcceptableException.class, "handleMediaTypeNotAcceptable");
    out.put(ServletRequestBindingException.class, "handleMissingRequestValue");
    out.put(MissingServletRequestPartException.class, "handleMissingRequestValue");
    out.put(MaxUploadSizeExceededException.class, "handleMaxUploadSizeExceeded");
    out.put(HttpRequestMethodNotSupportedException.class, "handleMethodNotSupported");
    out.put(NotFoundException.class, "handleNotFound");
    out.put(EntityNotFoundException.class, "handleNotFound");
    out.put(NoSuchElementException.class, "handleNotFound");
    out.put(NoResourceFoundException.class, "handleNotFound");
    out.put(RestClientException.class, "handleRestClientException");
    out.put(AsyncRequestNotUsableException.class, "handleDisconnectedClient");
    out.put(Exception.class, "handleAllExceptions");
    return out;
  }
}
