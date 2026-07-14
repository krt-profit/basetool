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

import org.springframework.http.HttpStatus;

/**
 * The fixed RFC&nbsp;7807 identity (status, stable code, i18n keys, problem-type suffix, WARN/ERROR
 * log label, disclosure policy) for every {@link AppException} subtype whose wire contract is a
 * per-<em>type</em> constant rather than computed per-instance (S4, #910).
 *
 * <p>{@code BankConflictException} is the sole exception excluded from this enum: its code, i18n
 * keys and problem-type suffix are chosen per-<em>instance</em> from one of its {@code CODE_BANK_*}
 * constants, so it implements {@link AppException}'s abstract accessors directly instead of
 * delegating to a fixed {@code AppExceptionKind} constant.
 *
 * <p>Values are byte-identical to the literals the corresponding {@code
 * GlobalExceptionHandler.handle*} method used to hardcode before S4 — this enum only relocates them
 * next to the exception types they describe, it does not change any of them.
 */
public enum AppExceptionKind {

  /** {@code BadRequestException} — service-layer rule {@code @Valid} cannot express. */
  BAD_REQUEST(
      HttpStatus.BAD_REQUEST,
      "BAD_REQUEST",
      "problem.bad_request.title",
      "problem.bad_request.detail",
      "bad-request",
      "Bad request",
      ErrorDisclosurePolicy.STANDARD),

  /**
   * {@code NotFoundException}. Its {@code GlobalExceptionHandler} handler stays a dedicated,
   * standalone {@code @ExceptionHandler} (not the generic {@code AppException} dispatch) because it
   * also covers three non-{@code AppException} JPA/JDK types ({@code EntityNotFoundException},
   * {@code NoSuchElementException}, {@code NoResourceFoundException}) that cannot be sealed under
   * this hierarchy. This constant exists so {@code NotFoundException} still exposes the uniform
   * accessor contract like every other sealed member, even though the handler does not consult it.
   */
  NOT_FOUND(
      HttpStatus.NOT_FOUND,
      "NOT_FOUND",
      "problem.not_found.title",
      "problem.not_found.detail",
      "not-found",
      "Not found",
      ErrorDisclosurePolicy.STANDARD),

  /** {@code BusinessConflictException} — cross-aggregate invariant or state-machine refusal. */
  BUSINESS_CONFLICT(
      HttpStatus.CONFLICT,
      "BUSINESS_CONFLICT",
      "problem.business_conflict.title",
      "problem.business_conflict.detail",
      "business-conflict",
      "Business conflict",
      ErrorDisclosurePolicy.STANDARD),

  /**
   * {@code OverAllocationException} — a well-formed, version-current request whose amount would
   * push a dimension's Σ over the inventory entry's own amount (Variante C, REQ-INV-027 rule R5).
   * The {@code 422} status is deliberately distinct from a {@code 400} validation error and a
   * {@code 409} lock conflict so the frontend can render an inline toast rather than a reload
   * prompt.
   */
  OVER_ALLOCATION(
      HttpStatus.UNPROCESSABLE_CONTENT,
      "OVER_ALLOCATION",
      "problem.over_allocation.title",
      "problem.over_allocation.detail",
      "over-allocation",
      "Over-allocation",
      ErrorDisclosurePolicy.STANDARD),

  /** {@code DuplicateEntityException} — service-layer uniqueness check. */
  DUPLICATE_ENTITY(
      HttpStatus.CONFLICT,
      "DUPLICATE_ENTITY",
      "problem.duplicate_entity.title",
      "problem.duplicate_entity.detail",
      "duplicate-entity",
      "Duplicate entity",
      ErrorDisclosurePolicy.STANDARD),

  /** {@code EntityInUseException} — delete blocked by an existing referencing entity. */
  ENTITY_IN_USE(
      HttpStatus.CONFLICT,
      "ENTITY_IN_USE",
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
      "EXTERNAL_SERVICE_ERROR",
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
      "REPORT_GENERATION_FAILED",
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

  AppExceptionKind(
      HttpStatus status,
      String code,
      String titleKey,
      String detailKey,
      String typeSuffix,
      String logLabel,
      ErrorDisclosurePolicy disclosurePolicy) {
    this.status = status;
    this.code = code;
    this.titleKey = titleKey;
    this.detailKey = detailKey;
    this.typeSuffix = typeSuffix;
    this.logLabel = logLabel;
    this.disclosurePolicy = disclosurePolicy;
  }

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
  public String code() {
    return code;
  }

  /**
   * The {@code MessageSource} key resolved for the RFC&nbsp;7807 {@code title}.
   *
   * @return the fixed title bundle key for this kind
   */
  public String titleKey() {
    return titleKey;
  }

  /**
   * The {@code MessageSource} fallback key {@code resolveDetail}/{@code tr} use for the
   * RFC&nbsp;7807 {@code detail}.
   *
   * @return the fixed detail bundle key for this kind
   */
  public String detailKey() {
    return detailKey;
  }

  /**
   * The suffix appended to {@code AppProblemProperties#getBaseUri()} for the RFC&nbsp;7807 {@code
   * type}.
   *
   * @return the fixed problem-type suffix for this kind
   */
  public String typeSuffix() {
    return typeSuffix;
  }

  /**
   * The short phrase the dispatch handler's log line names this kind by.
   *
   * @return the fixed log label for this kind
   */
  public String logLabel() {
    return logLabel;
  }

  /**
   * Whether the dispatch handler must suppress {@code getMessage()} and log at ERROR instead of
   * WARN.
   *
   * @return the fixed disclosure policy for this kind
   */
  public ErrorDisclosurePolicy disclosurePolicy() {
    return disclosurePolicy;
  }
}
