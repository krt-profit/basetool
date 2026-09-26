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
 * disclosure policy) of every {@link AppException} subtype whose wire contract is constant per
 * type.
 *
 * <p>{@code BankConflictException} is not listed: its identity is chosen per instance.
 */
@RequiredArgsConstructor
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
   * {@code NotFoundException}. Its dedicated handler also covers JPA/JDK not-found types and does
   * not consult this constant.
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
   * {@code OverAllocationException}: an amount would push a dimension's sum over the inventory
   * entry's own amount (REQ-INV-027). A {@code 422}, distinct from {@code 400} and {@code 409}, so
   * the frontend renders an inline toast.
   */
  OVER_ALLOCATION(
      HttpStatus.UNPROCESSABLE_CONTENT,
      "OVER_ALLOCATION",
      "problem.over_allocation.title",
      "problem.over_allocation.detail",
      "over-allocation",
      "Over-allocation",
      ErrorDisclosurePolicy.STANDARD),

  /**
   * {@code ProductionAllocationException} — a well-formed, version-current production booking whose
   * amount exceeds the item line's remaining-to-manufacture, or whose per-material consumption does
   * not exactly cover the required demand (REQ-ORDERS-025). Like {@link #OVER_ALLOCATION} it is a
   * {@code 422} so the frontend renders an inline toast rather than a {@code 409} reload prompt.
   */
  PRODUCTION_ALLOCATION(
      HttpStatus.UNPROCESSABLE_CONTENT,
      "PRODUCTION_ALLOCATION",
      "problem.production_allocation.title",
      "problem.production_allocation.detail",
      "production-allocation",
      "Production allocation",
      ErrorDisclosurePolicy.STANDARD),

  /**
   * {@code OwnerOrgUnitRequiredException}: a caller in several org units supplied no {@code
   * owningOrgUnitId} and has no usable active-context pin, so no owner can be resolved
   * (REQ-ORG-017).
   *
   * <p>A separate code from {@link #BAD_REQUEST} so the frontend can ask the member to choose an
   * org unit (REQ-ORG-023).
   */
  OWNER_ORG_UNIT_REQUIRED(
      HttpStatus.BAD_REQUEST,
      "OWNER_ORG_UNIT_REQUIRED",
      "problem.owner_org_unit_required.title",
      "problem.owner_org_unit_required.detail",
      "owner-org-unit-required",
      "Owning org unit required",
      ErrorDisclosurePolicy.STANDARD),

  /**
   * {@code MissionParticipantRequiredException}: a refinery order is linked to a mission its owner
   * does not take part in (REQ-SEC-042).
   *
   * <p>A separate code from {@link #BAD_REQUEST} so the form can point at the mission field.
   */
  MISSION_PARTICIPANT_REQUIRED(
      HttpStatus.BAD_REQUEST,
      "MISSION_PARTICIPANT_REQUIRED",
      "problem.mission_participant_required.title",
      "problem.mission_participant_required.detail",
      "mission-participant-required",
      "Mission participant required",
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
