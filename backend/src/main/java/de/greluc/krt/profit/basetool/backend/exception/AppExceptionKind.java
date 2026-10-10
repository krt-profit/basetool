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

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;

/**
 * The fixed RFC&nbsp;7807 identity (status, stable code, i18n keys, problem-type suffix, log label,
 * disclosure policy) of each generic kind of the error kernel (ADR-0235).
 *
 * <p>Module exceptions extend {@link DomainProblem} and carry their own identity instead.
 */
@RequiredArgsConstructor
public enum AppExceptionKind {

  /** {@code BadRequestException} — service-layer rule {@code @Valid} cannot express. */
  BAD_REQUEST(
      HttpStatus.BAD_REQUEST,
      CoreProblemCode.BAD_REQUEST.code(),
      "problem.bad_request.title",
      "problem.bad_request.detail",
      "bad-request",
      "Bad request",
      ErrorDisclosurePolicy.STANDARD),

  /**
   * {@code NotFoundException}. Its dedicated handler also covers JPA/JDK not-found types and does
   * not consult this constant.
   */
  NOT_FOUND(
      HttpStatus.NOT_FOUND,
      CoreProblemCode.NOT_FOUND.code(),
      "problem.not_found.title",
      "problem.not_found.detail",
      "not-found",
      "Not found",
      ErrorDisclosurePolicy.STANDARD),

  /** {@code BusinessConflictException} — cross-aggregate invariant or state-machine refusal. */
  BUSINESS_CONFLICT(
      HttpStatus.CONFLICT,
      CoreProblemCode.BUSINESS_CONFLICT.code(),
      "problem.business_conflict.title",
      "problem.business_conflict.detail",
      "business-conflict",
      "Business conflict",
      ErrorDisclosurePolicy.STANDARD),

  /**
   * {@code RateLimitExceededException} — a budget a controller keeps itself refused the call; the
   * response carries {@code Retry-After}.
   */
  RATE_LIMIT_EXCEEDED(
      HttpStatus.TOO_MANY_REQUESTS,
      CoreProblemCode.RATE_LIMIT_EXCEEDED.code(),
      "problem.rate_limit_exceeded.title",
      "problem.rate_limit_exceeded.detail",
      "rate-limit-exceeded",
      "Rate limit exceeded",
      ErrorDisclosurePolicy.STANDARD),

  /** {@code DuplicateEntityException} — service-layer uniqueness check. */
  DUPLICATE_ENTITY(
      HttpStatus.CONFLICT,
      CoreProblemCode.DUPLICATE_ENTITY.code(),
      "problem.duplicate_entity.title",
      "problem.duplicate_entity.detail",
      "duplicate-entity",
      "Duplicate entity",
      ErrorDisclosurePolicy.STANDARD),

  /** {@code EntityInUseException} — delete blocked by an existing referencing entity. */
  ENTITY_IN_USE(
      HttpStatus.CONFLICT,
      CoreProblemCode.ENTITY_IN_USE.code(),
      "problem.entity_in_use.title",
      "problem.entity_in_use.detail",
      "entity-in-use",
      "Entity in use",
      ErrorDisclosurePolicy.STANDARD),

  /**
   * {@code ExternalServiceException} — an upstream dependency (Keycloak, UEX, …) errored or is
   * unreachable. {@link ErrorDisclosurePolicy#SUPPRESSED}: the upstream response body may leak
   * implementation details, so the client gets a generic detail and the full exception is logged at
   * ERROR.
   */
  EXTERNAL_SERVICE_ERROR(
      HttpStatus.BAD_GATEWAY,
      CoreProblemCode.EXTERNAL_SERVICE_ERROR.code(),
      "problem.external_service.title",
      "problem.external_service.detail",
      "external-service-error",
      "Upstream service error",
      ErrorDisclosurePolicy.SUPPRESSED),

  /**
   * {@code ReportGenerationException} — the PDF/CSV report pipeline failed unexpectedly. {@link
   * ErrorDisclosurePolicy#SUPPRESSED}: the library-internal message (font/encoding, file paths) may
   * leak implementation details, so the client gets a generic detail and the full exception is
   * logged at ERROR.
   */
  REPORT_GENERATION_FAILED(
      HttpStatus.INTERNAL_SERVER_ERROR,
      CoreProblemCode.REPORT_GENERATION_FAILED.code(),
      "problem.report_generation_failed.title",
      "problem.report_generation_failed.detail",
      "report-generation-failed",
      "Report generation failed",
      ErrorDisclosurePolicy.SUPPRESSED);

  private final HttpStatus status;
  private final String code;
  private final String titleKey;
  private final String detailKey;
  private final String typeSuffix;
  private final String logLabel;
  private final ErrorDisclosurePolicy disclosurePolicy;

  /**
   * The HTTP status the RFC&nbsp;7807 response carries.
   *
   * @return the fixed status for this kind
   */
  public HttpStatus status() {
    return status;
  }

  /**
   * The stable, machine-readable {@code code} extension property.
   *
   * @return the fixed code for this kind
   */
  @NotNull
  public String code() {
    return code;
  }

  /**
   * The {@code MessageSource} key resolved for the RFC&nbsp;7807 {@code title}.
   *
   * @return the fixed title bundle key for this kind
   */
  @NotNull
  public String titleKey() {
    return titleKey;
  }

  /**
   * The {@code MessageSource} fallback key {@code resolveDetail}/{@code tr} use for the
   * RFC&nbsp;7807 {@code detail}.
   *
   * @return the fixed detail bundle key for this kind
   */
  @NotNull
  public String detailKey() {
    return detailKey;
  }

  /**
   * The suffix appended to {@code AppProblemProperties#getBaseUri()} for the RFC&nbsp;7807 {@code
   * type}.
   *
   * @return the fixed problem-type suffix for this kind
   */
  @NotNull
  public String typeSuffix() {
    return typeSuffix;
  }

  /**
   * The short phrase the dispatch handler's log line names this kind by.
   *
   * @return the fixed log label for this kind
   */
  @NotNull
  public String logLabel() {
    return logLabel;
  }

  /**
   * Whether the dispatch handler must suppress {@code getMessage()} and log at ERROR instead of
   * WARN.
   *
   * @return the fixed disclosure policy for this kind
   */
  @NotNull
  public ErrorDisclosurePolicy disclosurePolicy() {
    return disclosurePolicy;
  }
}
