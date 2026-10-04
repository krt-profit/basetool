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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.bank.api.BankConflictException;
import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeProblemException;
import de.greluc.krt.profit.basetool.backend.inventory.api.OverAllocationException;
import de.greluc.krt.profit.basetool.backend.joborder.api.ProductionAllocationException;
import de.greluc.krt.profit.basetool.backend.refinery.api.MissionParticipantRequiredException;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerOrgUnitRequiredException;
import de.greluc.krt.profit.basetool.backend.support.AppProblemProperties;
import de.greluc.krt.profit.basetool.backend.support.ProblemResponseFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * Pins the wire contract and the log identity of every module exception (ADR-0235): status, {@code
 * type}, {@code code}, {@code title}, {@code detail}, {@code instance}, {@code correlationId}, the
 * extension properties, and the log label and log fields of the handler's WARN line.
 */
class DomainProblemContractTest {

  private static final String BASE_URI = "https://profit-base.online/problems/";

  private static final String INSTANCE = "/api/v1/test";

  private ResourceBundleMessageSource messageSource;

  private GlobalExceptionHandler handler;

  private HttpServletRequest request;

  /**
   * The expected identity of one module exception.
   *
   * @param exception the thrown exception
   * @param status the answered status
   * @param code the {@code code} property
   * @param typeSuffix the suffix of the {@code type} URI
   * @param keyBase the bundle-key prefix of title and detail
   * @param detailKeyOrLiteral the key or literal the {@code detail} resolves from
   * @param logLabel the label of the handler's WARN line
   * @param logExtra the fields of the handler's WARN line, {@code null} for none
   * @param extra the extension properties beyond {@code code} and {@code correlationId}
   */
  record Expected(
      @NotNull AppException exception,
      @NotNull HttpStatus status,
      @NotNull String code,
      @NotNull String typeSuffix,
      @NotNull String keyBase,
      @NotNull String detailKeyOrLiteral,
      @NotNull String logLabel,
      @Nullable Map<String, ?> logExtra,
      @NotNull Map<String, Object> extra) {}

  @BeforeEach
  void setUp() {
    AppProblemProperties props = new AppProblemProperties(BASE_URI);
    messageSource = new ResourceBundleMessageSource();
    messageSource.setBasename("messages");
    messageSource.setDefaultEncoding("UTF-8");
    messageSource.setFallbackToSystemLocale(false);
    LocaleContextHolder.setLocale(Locale.ENGLISH);
    handler =
        new GlobalExceptionHandler(
            props, new ProblemResponseFactory(props), messageSource, new SimpleMeterRegistry());
    request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn(INSTANCE);
  }

  @AfterEach
  void tearDown() {
    LocaleContextHolder.resetLocaleContext();
  }

  /**
   * One expectation per module exception, with the values the kernel answered before the move.
   *
   * @return the expectations
   */
  static Stream<Expected> moduleExceptions() {
    return Stream.of(
        new Expected(
            new OverAllocationException(),
            HttpStatus.UNPROCESSABLE_CONTENT,
            "OVER_ALLOCATION",
            "over-allocation",
            "problem.over_allocation",
            "problem.over_allocation.detail",
            "Over-allocation",
            null,
            Map.of()),
        new Expected(
            new ProductionAllocationException(),
            HttpStatus.UNPROCESSABLE_CONTENT,
            "PRODUCTION_ALLOCATION",
            "production-allocation",
            "problem.production_allocation",
            "problem.production_allocation.detail",
            "Production allocation",
            null,
            Map.of()),
        new Expected(
            new MissionParticipantRequiredException(),
            HttpStatus.BAD_REQUEST,
            "MISSION_PARTICIPANT_REQUIRED",
            "mission-participant-required",
            "problem.mission_participant_required",
            "problem.mission_participant_required.detail",
            "Mission participant required",
            null,
            Map.of()),
        new Expected(
            new OwnerOrgUnitRequiredException("No owning org unit could be resolved."),
            HttpStatus.BAD_REQUEST,
            "OWNER_ORG_UNIT_REQUIRED",
            "owner-org-unit-required",
            "problem.owner_org_unit_required",
            "No owning org unit could be resolved.",
            "Owning org unit required",
            null,
            Map.of()),
        new Expected(
            new BankConflictException(
                BankConflictException.CODE_BANK_OVERDRAFT, "Overdraft", Map.of("available", 100)),
            HttpStatus.CONFLICT,
            "BANK_OVERDRAFT",
            "bank-overdraft",
            "problem.bank_overdraft",
            "Overdraft",
            "Bank conflict",
            Map.of("bankCode", "BANK_OVERDRAFT"),
            Map.of("available", 100)),
        new Expected(
            new BankConflictException(BankConflictException.CODE_BANK_ACCOUNT_CLOSED, ""),
            HttpStatus.CONFLICT,
            "BANK_ACCOUNT_CLOSED",
            "bank-account-closed",
            "problem.bank_account_closed",
            "problem.bank_account_closed.detail",
            "Bank conflict",
            Map.of("bankCode", "BANK_ACCOUNT_CLOSED"),
            Map.of()),
        new Expected(
            ExchangeProblemException.scopeMissing(),
            HttpStatus.FORBIDDEN,
            "SCOPE_MISSING",
            "scope-missing",
            "problem.scope_missing",
            "The needed capability is not relayed and granted.",
            "Exchange refusal",
            Map.of("exchangeCode", "SCOPE_MISSING"),
            Map.of()),
        new Expected(
            ExchangeProblemException.cursorExpired(),
            HttpStatus.GONE,
            "CURSOR_EXPIRED",
            "cursor-expired",
            "problem.cursor_expired",
            "The feed cursor is older than the retained changes.",
            "Exchange refusal",
            Map.of("exchangeCode", "CURSOR_EXPIRED"),
            Map.of()));
  }

  @ParameterizedTest
  @MethodSource("moduleExceptions")
  void theExceptionCarriesItsIdentity(Expected expected) {
    AppException ex = expected.exception();

    assertThat(ex.status()).isEqualTo(expected.status());
    assertThat(ex.code()).isEqualTo(expected.code());
    assertThat(ex.typeSuffix()).isEqualTo(expected.typeSuffix());
    assertThat(ex.titleKey()).isEqualTo(expected.keyBase() + ".title");
    assertThat(ex.detailKey()).isEqualTo(expected.keyBase() + ".detail");
    assertThat(ex.logLabel()).isEqualTo(expected.logLabel());
    assertThat(ex.disclosurePolicy()).isEqualTo(ErrorDisclosurePolicy.STANDARD);
    assertThat(ex.logExtra()).isEqualTo(expected.logExtra());
    assertThat(ex.extraProperties()).isEqualTo(expected.extra());
    assertThat(ex.responseHeaders()).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("moduleExceptions")
  void theHandlerAnswersTheSameProblemBody(Expected expected) {
    ResponseEntity<ProblemDetail> response =
        handler.handleAppException(expected.exception(), request);

    assertThat(response.getStatusCode().value()).isEqualTo(expected.status().value());
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    ProblemDetail body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.getStatus()).isEqualTo(expected.status().value());
    assertThat(body.getType()).isEqualTo(URI.create(BASE_URI + expected.typeSuffix()));
    assertThat(body.getTitle()).isEqualTo(english(expected.keyBase() + ".title"));
    assertThat(body.getDetail()).isEqualTo(english(expected.detailKeyOrLiteral()));
    assertThat(body.getInstance()).isEqualTo(URI.create(INSTANCE));
    Map<String, Object> properties = new LinkedHashMap<>(body.getProperties());
    assertThat(properties.remove("correlationId")).asString().isNotBlank();
    assertThat(properties.remove("code")).isEqualTo(expected.code());
    assertThat(properties).isEqualTo(expected.extra());
  }

  /**
   * Resolves a key in English, or returns a literal unchanged.
   *
   * @param keyOrLiteral a bundle key or a literal detail
   * @return the English text
   */
  private String english(@NotNull String keyOrLiteral) {
    return messageSource.getMessage(keyOrLiteral, null, keyOrLiteral, Locale.ENGLISH);
  }
}
