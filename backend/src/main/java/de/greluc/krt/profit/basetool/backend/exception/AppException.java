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

import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.http.HttpStatus;

/**
 * Sealed base of the backend's domain exceptions, carrying the RFC&nbsp;7807 error contract that
 * {@code GlobalExceptionHandler} dispatches on.
 *
 * <p>Subtypes pass a fixed {@link AppExceptionKind} to {@link #AppException(AppExceptionKind,
 * String)} and inherit every accessor; only {@link BankConflictException}, whose identity is per
 * instance, uses the kind-less constructor and overrides every accessor.
 */
public abstract sealed class AppException extends RuntimeException
    permits BadRequestException,
        BankConflictException,
        BusinessConflictException,
        DuplicateEntityException,
        EntityInUseException,
        ExternalServiceException,
        MissionParticipantRequiredException,
        NotFoundException,
        OverAllocationException,
        OwnerOrgUnitRequiredException,
        ProductionAllocationException,
        ReportGenerationException {

  /**
   * The fixed per-type identity for the ten subtypes that have one; {@code null} for {@link
   * BankConflictException}, whose accessors are computed per-instance and therefore all overridden.
   */
  private final @Nullable AppExceptionKind kind;

  /**
   * Creates an exception whose accessors all delegate to a fixed {@code kind}.
   *
   * @param kind the fixed identity the accessors delegate to
   * @param message verbatim {@code detail} or an i18n key
   */
  protected AppException(AppExceptionKind kind, String message) {
    super(message);
    this.kind = kind;
  }

  /**
   * Creates an {@code AppException} whose accessor contract is delegated to a fixed {@code kind},
   * wrapping a lower-level cause kept for server-side logging only.
   *
   * @param kind the fixed identity this exception's accessors delegate to
   * @param message human-readable description; either a verbatim {@code detail} or an i18n key
   * @param cause underlying failure that triggered this exception
   */
  protected AppException(AppExceptionKind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = kind;
  }

  /**
   * Creates a kind-less exception for {@link BankConflictException}, which must override every
   * accessor.
   *
   * @param message verbatim {@code detail} or an i18n key
   */
  protected AppException(String message) {
    this(null, message);
  }

  /**
   * Creates a kind-less exception wrapping a cause kept for server-side logging only, for {@link
   * BankConflictException}.
   *
   * @param message verbatim {@code detail} or an i18n key
   * @param cause underlying failure that triggered this exception
   */
  protected AppException(String message, Throwable cause) {
    this(null, message, cause);
  }

  /**
   * The HTTP status the RFC&nbsp;7807 response carries. Delegates to the stored {@link
   * AppExceptionKind} by default; {@link BankConflictException} overrides this directly.
   *
   * @return the status for this exception
   */
  public HttpStatus status() {
    return requireKind().status();
  }

  /**
   * The stable, machine-readable {@code code} extension property. Must never change once published
   * — the frontend selects its localized message by this value. Delegates to the stored {@link
   * AppExceptionKind} by default; {@link BankConflictException} overrides this directly.
   *
   * @return the code for this exception
   */
  public String code() {
    return requireKind().code();
  }

  /**
   * The {@code MessageSource} key resolved for the RFC&nbsp;7807 {@code title}. Delegates to the
   * stored {@link AppExceptionKind} by default; {@link BankConflictException} overrides this
   * directly.
   *
   * @return the title bundle key for this exception
   */
  public String titleKey() {
    return requireKind().titleKey();
  }

  /**
   * The {@code MessageSource} key for the RFC&nbsp;7807 {@code detail}: the fallback when the
   * message does not resolve, and the only source when {@link #disclosurePolicy()} is {@link
   * ErrorDisclosurePolicy#SUPPRESSED}.
   *
   * @return the detail bundle key for this exception
   */
  public String detailKey() {
    return requireKind().detailKey();
  }

  /**
   * The suffix appended to {@code AppProblemProperties#getBaseUri()} for the RFC&nbsp;7807 {@code
   * type}. Delegates to the stored {@link AppExceptionKind} by default; {@link
   * BankConflictException} overrides this directly.
   *
   * @return the problem-type suffix for this exception
   */
  public String typeSuffix() {
    return requireKind().typeSuffix();
  }

  /**
   * The short phrase the dispatch handler's WARN/ERROR log line names this exception by (e.g.
   * {@code "Duplicate entity"}, {@code "Upstream service error"}). Delegates to the stored {@link
   * AppExceptionKind} by default; {@link BankConflictException} overrides this directly.
   *
   * @return the log label for this exception
   */
  public String logLabel() {
    return requireKind().logLabel();
  }

  /**
   * Whether the dispatch handler suppresses {@code getMessage()} and logs at ERROR with the stack
   * trace instead of WARN.
   *
   * <p>Falls back to {@link ErrorDisclosurePolicy#STANDARD} for the kind-less {@link
   * BankConflictException}.
   *
   * @return the disclosure policy for this exception
   */
  public ErrorDisclosurePolicy disclosurePolicy() {
    return kind != null ? kind.disclosurePolicy() : ErrorDisclosurePolicy.STANDARD;
  }

  /**
   * The fixed {@link AppExceptionKind} the accessors delegate to.
   *
   * @return the non-null kind
   * @throws IllegalStateException if this instance has no kind and its subclass did not override
   *     the calling accessor
   */
  private AppExceptionKind requireKind() {
    if (kind == null) {
      throw new IllegalStateException(
          getClass().getName()
              + " has no fixed AppExceptionKind; its subclass must override this accessor"
              + " directly (see BankConflictException)");
    }
    return kind;
  }

  /**
   * Extension properties copied onto the RFC&nbsp;7807 response beyond {@code code} and {@code
   * correlationId}, such as {@code BankConflictException}'s PII-free parameters; empty for every
   * other subtype.
   *
   * @return extension properties for the problem response; never {@code null}
   */
  @NotNull
  @Unmodifiable
  public Map<String, Object> extraProperties() {
    return Map.of();
  }

  /**
   * Additional structured fields for the dispatch handler's WARN log line; {@code null} for every
   * subtype except {@code BankConflictException}.
   *
   * @return extra fields for the WARN log line, or {@code null} for none
   */
  @Nullable
  public Map<String, ?> logExtra() {
    return null;
  }
}
