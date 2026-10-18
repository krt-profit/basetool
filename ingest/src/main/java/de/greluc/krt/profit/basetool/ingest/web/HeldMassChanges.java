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

package de.greluc.krt.profit.basetool.ingest.web;

import de.greluc.krt.profit.basetool.ingest.config.IngestProperties;
import de.greluc.krt.profit.basetool.ingest.config.LoggingProperties;
import de.greluc.krt.profit.basetool.ingest.gate.ExchangeRequestContext;
import de.greluc.krt.profit.basetool.ingest.handoff.HandoffStagingService;
import de.greluc.krt.profit.basetool.ingest.observability.MetricNames;
import de.greluc.krt.profit.basetool.ingest.problem.Problems;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Answers change sets the mass-change guard held back: the {@code 409} with the confirmation link,
 * and a byte-identical resend of a held batch that still waits for the member with the same link,
 * without relaying it (REQ-XCH-021, ADR-0280).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class HeldMassChanges {

  /** The code of a change set the mass-change guard held back for the member's confirmation. */
  static final String MASS_CHANGE_CONFIRMATION_REQUIRED = "MASS_CHANGE_CONFIRMATION_REQUIRED";

  /** The frontend page where the member confirms a staged mass change. */
  static final String CONFIRMATION_PATH = "/connected-apps/confirm";

  private final HandoffStagingService stagingService;
  private final LoggingProperties loggingProperties;
  private final IngestProperties ingestProperties;
  private final MeterRegistry meterRegistry;

  /** Registers the staged and the repeated mass-change counters at zero. */
  @PostConstruct
  void registerCounters() {
    meterRegistry.counter(
        MetricNames.EXCHANGE_MASS_CHANGES_STAGED,
        MetricNames.TAG_CLIENT_ID,
        MetricNames.EXCHANGE_CLIENT_NONE);
    meterRegistry.counter(
        MetricNames.EXCHANGE_MASS_CHANGES_REPEATED,
        MetricNames.TAG_CLIENT_ID,
        MetricNames.EXCHANGE_CLIENT_NONE);
  }

  /**
   * Computes the digest a held change set is recognised by when it is resent.
   *
   * @param context the admitted request
   * @param resource the resource's path segment
   * @param body the change set
   * @return the digest, or {@code null} for a dry run, which the guard never holds
   */
  @Nullable
  String digest(
      @NotNull ExchangeRequestContext context, @NotNull String resource, @NotNull JsonNode body) {
    return body.path("dryRun").asBoolean(false)
        ? null
        : stagingService.massChangeDigest(context.keyThumbprint(), resource, body);
  }

  /**
   * Answers a resend of a held batch that still waits unopened for the member with that batch's
   * link, and counts it as a repeat.
   *
   * @param context the admitted request
   * @param digest the change set's digest, or {@code null} when none was computed
   * @return {@code 409 MASS_CHANGE_CONFIRMATION_REQUIRED} with the staged batch's {@code
   *     confirmationUrl}, or {@code null} when no staged batch matches and the change set is
   *     relayed
   */
  @Nullable
  ResponseEntity<?> resent(@NotNull ExchangeRequestContext context, @Nullable String digest) {
    if (digest == null) {
      return null;
    }
    HandoffStagingService.HeldMassChange held =
        stagingService.liveMassChange(context.clientId(), context.member(), digest);
    if (held == null) {
      return null;
    }
    meterRegistry
        .counter(
            MetricNames.EXCHANGE_MASS_CHANGES_REPEATED,
            MetricNames.TAG_CLIENT_ID,
            context.clientId())
        .increment();
    log.info(
        "Answered a resent held mass change with its staged confirmation link (client={})",
        context.clientId());
    return confirmationRequired(held.handoffId(), held.detail());
  }

  /**
   * Counts a newly staged held batch, records its digest so a resend finds it, and answers where
   * the member confirms it.
   *
   * @param context the admitted request
   * @param digest the change set's digest, or {@code null} when none was computed
   * @param handoffId the staged batch's handoff id
   * @param detail the guard's explanation
   * @return {@code 409 MASS_CHANGE_CONFIRMATION_REQUIRED} with the {@code confirmationUrl}
   */
  @NotNull
  ResponseEntity<?> staged(
      @NotNull ExchangeRequestContext context,
      @Nullable String digest,
      @NotNull String handoffId,
      @Nullable String detail) {
    meterRegistry
        .counter(
            MetricNames.EXCHANGE_MASS_CHANGES_STAGED, MetricNames.TAG_CLIENT_ID, context.clientId())
        .increment();
    if (digest != null) {
      stagingService.rememberMassChange(
          context.clientId(),
          context.member(),
          new HandoffStagingService.HeldMassChange(digest, handoffId, detail));
    }
    return confirmationRequired(handoffId, detail);
  }

  /**
   * Builds {@code 409 MASS_CHANGE_CONFIRMATION_REQUIRED} with the guard's detail and the link to
   * the staged batch.
   *
   * @param handoffId the staged batch's handoff id
   * @param detail the guard's explanation
   * @return the problem response
   */
  private @NotNull ResponseEntity<?> confirmationRequired(
      @NotNull String handoffId, @Nullable String detail) {
    ProblemDetail problem =
        Problems.of(
            loggingProperties,
            HttpStatus.CONFLICT,
            HttpStatus.CONFLICT.getReasonPhrase(),
            MASS_CHANGE_CONFIRMATION_REQUIRED,
            detail);
    problem.setProperty(
        "confirmationUrl",
        ingestProperties.frontendBaseUrl() + CONFIRMATION_PATH + "?handoff=" + handoffId);
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }
}
