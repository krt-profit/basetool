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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Pins the opt-in {@code aud}-claim validator from {@link SecurityConfig#audienceValidator(List)}:
 * once {@code app.security.jwt.expected-audiences} is set, a token whose {@code aud} does not
 * intersect it is rejected.
 */
class SecurityConfigAudienceValidatorTest {

  private static final OAuth2TokenValidator<Jwt> VALIDATOR =
      SecurityConfig.audienceValidator(List.of("basetool-backend", "account"));

  private static Jwt jwtWithAudience(List<String> audiences) {
    Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none").subject("tester");
    if (audiences != null) {
      builder.audience(audiences);
    }
    return builder.build();
  }

  @Test
  void acceptsTokenWhoseAudienceIntersectsExpected() {
    OAuth2TokenValidatorResult result =
        VALIDATOR.validate(jwtWithAudience(List.of("basetool-backend")));
    assertFalse(result.hasErrors(), "a matching aud must pass: " + result.getErrors());
  }

  @Test
  void rejectsTokenWhoseAudienceDoesNotIntersectExpected() {
    OAuth2TokenValidatorResult result =
        VALIDATOR.validate(jwtWithAudience(List.of("some-other-client")));
    assertTrue(result.hasErrors(), "a foreign aud must be rejected (token confusion)");
  }

  @Test
  void rejectsTokenWithoutAudienceClaim() {
    OAuth2TokenValidatorResult result = VALIDATOR.validate(jwtWithAudience(null));
    assertTrue(result.hasErrors(), "a missing aud must be rejected when enforcement is enabled");
  }
}
