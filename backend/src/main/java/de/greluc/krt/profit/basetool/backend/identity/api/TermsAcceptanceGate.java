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

package de.greluc.krt.profit.basetool.backend.identity.api;

import de.greluc.krt.profit.basetool.backend.kernel.CoreProblemCode;
import de.greluc.krt.profit.basetool.backend.kernel.ProblemCode;
import de.greluc.krt.profit.basetool.backend.platform.api.RefusedSubjectWindow;
import de.greluc.krt.profit.basetool.backend.platform.api.SessionGate;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * The Terms-of-Use consent gate (REQ-SEC-028): an authenticated caller who has not accepted the
 * wording in force is refused with {@code TERMS_NOT_ACCEPTED}. An exchange client's relayed call is
 * judged as its acting member.
 *
 * <p>The first session gate, so a member held by a later gate can still give consent. Each refused
 * subject is recorded in the window behind the {@code basetool_terms_refused_subjects} gauge. The
 * {@code test} profile stands the gate down unless {@link #ARMED_IN_TEST} re-arms it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TermsAcceptanceGate implements SessionGate {

  /**
   * Re-arms the consent gate under the {@code test} profile for a single test class; production is
   * armed regardless.
   */
  public static final String ARMED_IN_TEST = "app.security.terms.armed-in-test";

  /**
   * The only endpoints an unconsented caller may still reach, as exact paths: a new endpoint under
   * {@code /api/v1/terms} stays gated until it is added here (REQ-SEC-080).
   */
  public static final List<String> EXEMPT_PATHS =
      List.of(
          "/api/v1/terms/document",
          "/api/v1/terms/status",
          "/api/v1/terms/acceptance",
          "/api/v1/users/me/registration-status");

  private final TermsConsentCheck consentCheck;
  private final RefusedSubjectWindow refusedSubjects;
  private final boolean armed;

  /**
   * Creates the gate.
   *
   * @param consentCheck tells whether a member accepted the wording in force
   * @param refusedSubjectWindow the window the refused subjects are recorded into
   * @param environment the environment; the {@code test} profile disarms the gate unless {@link
   *     #ARMED_IN_TEST} is set
   */
  public TermsAcceptanceGate(
      @NotNull TermsConsentCheck consentCheck,
      @NotNull RefusedSubjectWindow refusedSubjectWindow,
      @NotNull Environment environment) {
    this.consentCheck = consentCheck;
    this.refusedSubjects = refusedSubjectWindow;
    this.armed =
        !environment.matchesProfiles("test")
            || environment.getProperty(ARMED_IN_TEST, Boolean.class, Boolean.FALSE);
  }

  @Override
  public @NotNull ProblemCode problemCode() {
    return CoreProblemCode.TERMS_NOT_ACCEPTED;
  }

  @Override
  public @NotNull @Unmodifiable List<String> exemptPaths() {
    return EXEMPT_PATHS;
  }

  @Override
  public boolean holds(@NotNull Authentication authentication, @NotNull UUID userId) {
    return armed && !consentCheck.hasAcceptedCurrentTerms(userId);
  }

  @Override
  public @NotNull String fallbackDetail() {
    return "The Terms of Use must be accepted before using the platform.";
  }

  @Override
  public void refused(@NotNull UUID userId) {
    refusedSubjects.record(userId);
  }
}
