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

package de.greluc.krt.profit.basetool.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Context tests for {@link JwtAudienceStartupCheck} (REQ-SEC-024, APPSEC-08): under the {@code
 * prod} profile a blank {@code app.security.jwt.expected-audiences} must abort the start instead of
 * silently switching the {@code aud} check off, while every other profile keeps "blank = off". Each
 * failing case has a passing twin that differs in exactly the one input, so a green failure case
 * cannot be green for an unrelated reason.
 */
class JwtAudienceStartupCheckTest {

  /**
   * The runner under test. Boot's shared conversion service is installed on the bean factory, as
   * {@code SpringApplication} does, so the comma list splits into a {@code List} exactly as in the
   * running application rather than through the plain property-editor fallback.
   */
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withInitializer(
              context ->
                  context
                      .getBeanFactory()
                      .setConversionService(ApplicationConversionService.getSharedInstance()))
          .withUserConfiguration(JwtAudienceStartupCheck.class);

  /**
   * Activates the {@code prod} profile on the runner's environment before the refresh.
   *
   * @return a runner whose context runs under {@code prod}
   */
  private ApplicationContextRunner prodRunner() {
    return runner.withInitializer(
        context ->
            context.getEnvironment().setActiveProfiles(JwtAudienceStartupCheck.PROD_PROFILE));
  }

  @Test
  void prod_blankAudiences_refusesToStart() {
    prodRunner()
        .withPropertyValues("app.security.jwt.expected-audiences=")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("IRI_BACKEND_EXPECTED_AUDIENCES"));
  }

  @Test
  void prod_propertyAbsent_refusesToStart() {
    prodRunner().run(context -> assertThat(context).hasFailed());
  }

  @Test
  void prod_onlyBlankEntries_refusesToStart() {
    prodRunner()
        .withPropertyValues("app.security.jwt.expected-audiences= , ")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void prod_withAudience_startsAndExposesTrimmedList() {
    prodRunner()
        .withPropertyValues("app.security.jwt.expected-audiences= basetool-backend ")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(JwtAudienceStartupCheck.class).getAudiences())
                  .containsExactly("basetool-backend");
            });
  }

  @Test
  void prod_commaList_bindsEveryAudience() {
    prodRunner()
        .withPropertyValues("app.security.jwt.expected-audiences=basetool-backend,account")
        .run(
            context ->
                assertThat(context.getBean(JwtAudienceStartupCheck.class).getAudiences())
                    .containsExactly("basetool-backend", "account"));
  }

  @Test
  void nonProd_blankAudiences_startsWithCheckOff() {
    runner
        .withPropertyValues("app.security.jwt.expected-audiences=")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(JwtAudienceStartupCheck.class).getAudiences()).isEmpty();
            });
  }
}
