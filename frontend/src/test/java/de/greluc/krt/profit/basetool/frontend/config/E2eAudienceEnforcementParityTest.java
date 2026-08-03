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

package de.greluc.krt.profit.basetool.frontend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Build-time enforcement of the audit L-1 / REQ-SEC-024 {@code aud} contract for the E2E stack,
 * whose two halves sit on opposite sides of a Docker boundary and are related by nothing at compile
 * time.
 *
 * <p>{@code E2eStackExtension} sets {@code IRI_BACKEND_EXPECTED_AUDIENCES}, which makes the E2E
 * backend build the custom {@code resourceServerJwtDecoder} and REJECT every token whose {@code
 * aud} does not carry that value. The claim itself is stamped by the {@code aud-basetool-backend}
 * protocol mapper on the {@code basetool-frontend} client in {@code realm-export.e2e.json} — the
 * client that mints every E2E token, browser-flow and {@code BackendSeeder} ROPC alike. Drop the
 * mapper, rename the client, or change either string, and the suite does not fail with a hint: it
 * fails with a 401 on literally every test, including the ones that only read a public page.
 *
 * <p>Enforcement is switched ON for E2E on purpose (the deployed prod {@code .env} leaves the knob
 * empty until an operator flips it), so this is the one place the enforced path is exercised
 * against a real Keycloak-minted token before it reaches production.
 *
 * <p>Both files live in the {@code e2e} source set, which is not on this test's classpath, so they
 * are read off disk — the same technique as {@code PickerSearchLimitsParityTest} and {@code
 * ComboboxKindsParityTest}, which read the shipped JS and the head fragment.
 */
class E2eAudienceEnforcementParityTest {

  /** Throwaway realm the E2E Keycloak imports; carries the audience mapper under test. */
  private static final String REALM_EXPORT = "src/e2e/resources/realm-export.e2e.json";

  /** The extension that feeds {@code IRI_BACKEND_EXPECTED_AUDIENCES} into the compose stack. */
  private static final String STACK_EXTENSION =
      "src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/E2eStackExtension.java";

  /** Keycloak client whose tokens the E2E backend receives; must be the one carrying the mapper. */
  private static final String TOKEN_CLIENT_ID = "basetool-frontend";

  /**
   * Matches the {@code EXPECTED_AUDIENCE} constant's literal in {@code E2eStackExtension}. Anchored
   * on the full declaration so a mention of the same string in a Javadoc paragraph cannot match.
   */
  private static final Pattern EXPECTED_AUDIENCE_CONSTANT =
      Pattern.compile("String\\s+EXPECTED_AUDIENCE\\s*=\\s*\"([^\"]+)\"");

  /**
   * Pins the audience the E2E backend enforces to the one the E2E realm actually stamps, so the
   * suite can never be armed against a claim no token carries.
   *
   * @throws IOException if either source file cannot be read from disk
   */
  @Test
  void enforcedAudience_isStampedByTheE2eRealmsTokenClient() throws IOException {
    String enforced = enforcedAudience();
    JsonNode mapper = audienceMapper(enforced);

    assertThat(mapper)
        .as(
            "no oidc-audience-mapper stamping aud=%s on the %s client in %s — E2eStackExtension"
                + " enforces that audience, so without the mapper every E2E token is rejected 401",
            enforced, TOKEN_CLIENT_ID, REALM_EXPORT)
        .isNotNull();
    assertThat(mapper.path("config").path("access.token.claim").asString())
        .as("the audience must land on the ACCESS token — that is what the backend validates")
        .isEqualTo("true");
  }

  /**
   * Keeps the audience off the ID token, mirroring the prod realm's mapper config.
   *
   * <p>The frontend is an OAuth2 client and validates its own ID token: a second {@code aud} value
   * there pushes Spring Security's {@code OidcIdTokenValidator} onto its multi-audience branch,
   * which additionally requires {@code azp}. Prod stamps the access token only, so E2E must too —
   * otherwise E2E would be rehearsing a token shape production never issues.
   *
   * @throws IOException if either source file cannot be read from disk
   */
  @Test
  void enforcedAudience_staysOffTheIdToken() throws IOException {
    JsonNode mapper = audienceMapper(enforcedAudience());
    assertThat(mapper).isNotNull();
    assertThat(mapper.path("config").path("id.token.claim").asString())
        .as("id.token.claim must stay false — the prod realm's mapper stamps the access token only")
        .isNotEqualTo("true");
  }

  /**
   * Extracts the audience literal {@code E2eStackExtension} arms the E2E backend with.
   *
   * @return the value of the extension's {@code EXPECTED_AUDIENCE} constant
   * @throws IOException if the extension source cannot be read
   */
  private static String enforcedAudience() throws IOException {
    String source = Files.readString(resolve(STACK_EXTENSION), StandardCharsets.UTF_8);
    Matcher matcher = EXPECTED_AUDIENCE_CONSTANT.matcher(source);
    if (!matcher.find()) {
      // Never pass silently: a renamed constant would otherwise turn this whole test into a no-op.
      return fail(
          "EXPECTED_AUDIENCE constant not found in %s — this parity test cannot verify anything;"
                  .formatted(STACK_EXTENSION)
              + " update this test if the constant was renamed");
    }
    return matcher.group(1);
  }

  /**
   * Finds the audience protocol mapper on the E2E realm's token client that stamps {@code
   * audience}.
   *
   * @param audience the audience literal the mapper must emit
   * @return the matching mapper node, or {@code null} when the client carries no such mapper
   * @throws IOException if the realm export cannot be read
   */
  private static JsonNode audienceMapper(String audience) throws IOException {
    String json = Files.readString(resolve(REALM_EXPORT), StandardCharsets.UTF_8);
    JsonNode realm = JsonMapper.builder().build().readTree(json);
    for (JsonNode client : realm.path("clients")) {
      if (!TOKEN_CLIENT_ID.equals(client.path("clientId").asString())) {
        continue;
      }
      for (JsonNode mapper : client.path("protocolMappers")) {
        boolean stampsAudience =
            "oidc-audience-mapper".equals(mapper.path("protocolMapper").asString())
                && audience.equals(
                    mapper.path("config").path("included.custom.audience").asString());
        if (stampsAudience) {
          return mapper;
        }
      }
    }
    return null;
  }

  /**
   * Resolves a repo-relative path whether the test runs with the {@code frontend} module or the
   * repo root as its working directory.
   *
   * @param moduleRelative the path relative to the {@code frontend} module directory
   * @return the first candidate that exists
   */
  private static Path resolve(String moduleRelative) {
    Path fromModule = Path.of(moduleRelative);
    return Files.exists(fromModule) ? fromModule : Path.of("frontend").resolve(moduleRelative);
  }
}
