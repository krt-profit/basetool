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

import de.greluc.krt.profit.basetool.backend.kernel.AppProblemProperties;
import de.greluc.krt.profit.basetool.backend.kernel.ProblemResponseFactory;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hibernate.StaleObjectStateException;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Handlers for the 409 outcomes of concurrent and conflicting writes: optimistic and pessimistic
 * locking failures and data-integrity violations.
 */
public abstract class ConcurrencyProblemHandlers extends ProblemSupport {

  /**
   * Postgres / H2 / generic JDBC constraint name pattern (e.g. "violates foreign key constraint
   * \"fk_xyz\"").
   */
  private static final Pattern CONSTRAINT_NAME_PATTERN =
      Pattern.compile("constraint\\s+\"?([A-Za-z0-9_]+)\"?");

  /**
   * Creates the handler family over the collaborators every problem response needs.
   *
   * @param problemProperties the problem type base URI
   * @param problemResponseFactory the factory that fills the standard problem fields
   * @param messageSource the bundle for the localized title and detail
   * @param meterRegistry the registry for the error counters
   */
  ConcurrencyProblemHandlers(
      AppProblemProperties problemProperties,
      ProblemResponseFactory problemResponseFactory,
      MessageSource messageSource,
      MeterRegistry meterRegistry) {
    super(problemProperties, problemResponseFactory, messageSource, meterRegistry);
  }

  /**
   * Maps every optimistic-locking failure flavor to 409 with code {@code OPTIMISTIC_LOCK}; the WARN
   * line carries the entity, id and version pair from {@link #optimisticLockContext(Exception)}.
   *
   * @param ex the optimistic-locking exception
   * @param request servlet request for the {@code instance} URI and log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler({
    ObjectOptimisticLockingFailureException.class,
    OptimisticLockException.class,
    StaleObjectStateException.class
  })
  public ResponseEntity<ProblemDetail> handleOptimisticLockingFailure(
      Exception ex, HttpServletRequest request) {
    countHttpError(CODE_OPTIMISTIC_LOCK);
    ProblemDetail pd =
        problem(
            HttpStatus.CONFLICT,
            tr("problem.optimistic_lock.title"),
            tr("problem.optimistic_lock.detail"),
            request,
            "concurrency-conflict",
            CODE_OPTIMISTIC_LOCK);
    logProblem(request, pd, "Optimistic locking conflict", optimisticLockContext(ex));
    return toEntity(pd);
  }

  /**
   * Builds the insertion-ordered log context for the 409 WARN line: the exception's simple name
   * and, for {@link ObjectOptimisticLockingFailureException}, the entity class, identifier and
   * message (the version pair). Contains no user-supplied text (REQ-OBS-004).
   *
   * @param ex the optimistic-locking throwable
   * @return the mutable context map; may hold a {@code null} identifier
   */
  @NotNull
  private static Map<String, Object> optimisticLockContext(Exception ex) {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("exception", ex.getClass().getSimpleName());
    if (ex instanceof ObjectOptimisticLockingFailureException conflict) {
      context.put("entity", String.valueOf(conflict.getPersistentClassName()));
      context.put("entityId", String.valueOf(conflict.getIdentifier()));
      context.put("versions", String.valueOf(conflict.getMessage()));
    }
    return context;
  }

  /**
   * Maps pessimistic-lock acquisition failures (timeout / deadlock-victim) to 409 with code {@code
   * PESSIMISTIC_LOCK}. Distinguishable from {@code OPTIMISTIC_LOCK} so the frontend can surface a
   * different retry hint and operations can monitor the two failure modes separately.
   *
   * @param ex thrown {@link PessimisticLockingFailureException}
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler(PessimisticLockingFailureException.class)
  public ResponseEntity<ProblemDetail> handlePessimisticLocking(
      PessimisticLockingFailureException ex, HttpServletRequest request) {
    countHttpError(CODE_PESSIMISTIC_LOCK);
    ProblemDetail pd =
        problem(
            HttpStatus.CONFLICT,
            tr("problem.pessimistic_lock.title"),
            tr("problem.pessimistic_lock.detail"),
            request,
            "pessimistic-lock",
            CODE_PESSIMISTIC_LOCK);
    logProblem(
        request,
        pd,
        "Pessimistic locking conflict",
        Map.of("exception", ex.getClass().getSimpleName()));
    return toEntity(pd);
  }

  /**
   * Maps a {@link DataIntegrityViolationException} to 409 with code {@code
   * DATA_INTEGRITY_VIOLATION}, logging the constraint name matched by {@link
   * #CONSTRAINT_NAME_PATTERN} and only the first line of the cause message.
   *
   * @param ex thrown {@link DataIntegrityViolationException}
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(
      DataIntegrityViolationException ex, HttpServletRequest request) {
    ProblemDetail pd =
        problem(
            HttpStatus.CONFLICT,
            tr("problem.data_integrity.title"),
            tr("problem.data_integrity.detail"),
            request,
            "data-integrity-violation",
            CODE_DATA_INTEGRITY);
    Throwable cause = ex.getMostSpecificCause();
    Map<String, Object> extra = new HashMap<>();
    if (cause != null) {
      extra.put("rootCause", cause.getClass().getSimpleName());
      String msg = cause.getMessage();
      if (msg != null) {
        Matcher m = CONSTRAINT_NAME_PATTERN.matcher(msg);
        if (m.find()) {
          extra.put("constraint", m.group(1));
        }
        int nl = msg.indexOf('\n');
        extra.put("causeMessage", nl > 0 ? msg.substring(0, nl) : msg);
      }
    }
    logProblem(request, pd, "Data integrity violation", extra);
    return toEntity(pd);
  }
}
