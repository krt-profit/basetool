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

package de.greluc.krt.profit.basetool.frontend.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Guards the three properties Android actually checks when it decides whether the Basetool app may
 * open {@code https://profit-base.online/app/callback}.
 *
 * <p>This exists because the first release shipped without the file. The path fell through the
 * security chain to {@code anyRequest().authenticated()} and answered {@code 302} into the OAuth
 * entry point; Android's verification failed silently, the login callback opened in a browser
 * instead of the app, and the member landed on the 404 page in the middle of signing in. Nothing in
 * the build could see it — the app was correct, the server was correct, and only their agreement
 * was missing.
 */
@SpringBootTest
@DisplayName("Digital Asset Links")
class AssetLinksControllerTest {

  /** Signing digest of the production release key, as published in the app's README. */
  private static final String PROD_FINGERPRINT =
      "E8:40:20:5E:EC:16:F5:FD:CD:BA:8B:44:81:18:06:3C:4A:37:E6:16:20:99:CC:49:00:DF:23:80:C1:AF:50:64";

  @Autowired private WebApplicationContext context;

  /**
   * Keeps the real client registration out of the context.
   *
   * <p>Building it performs OIDC discovery against the configured issuer, which no unit test can
   * reach — the context then fails with an {@code UnknownHostException} that says nothing about the
   * endpoint under test. Every other {@code @SpringBootTest} in this module mocks it for the same
   * reason.
   */
  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  /**
   * Builds a MockMvc that runs the real security filter chain.
   *
   * <p>Without the chain this test would pass while the endpoint is unreachable in production,
   * which is exactly the failure it exists to catch.
   *
   * @return the configured MockMvc.
   */
  private MockMvc mvc() {
    return MockMvcBuilders.webAppContextSetup(context)
        .apply(
            org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                .springSecurity())
        .build();
  }

  @Test
  @DisplayName("is served anonymously, as JSON, with no redirect")
  void servedAnonymouslyAsJson() throws Exception {
    // All three matter to Android and none of them held before this endpoint existed: an
    // unauthenticated GET answered 302 into the OAuth entry point.
    mvc()
        .perform(get("/.well-known/assetlinks.json"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
  }

  @Test
  @DisplayName("names the production package and its signing digest")
  void namesPackageAndFingerprint() throws Exception {
    mvc()
        .perform(get("/.well-known/assetlinks.json"))
        .andExpect(jsonPath("$[0].relation[0]").value("delegate_permission/common.handle_all_urls"))
        .andExpect(jsonPath("$[0].target.namespace").value("android_app"))
        .andExpect(
            jsonPath("$[0].target.package_name").value("de.greluc.krt.profit.basetool.android"))
        .andExpect(jsonPath("$[0].target.sha256_cert_fingerprints[0]").value(PROD_FINGERPRINT));
  }

  @Test
  @DisplayName("publishes the digests as a list, so a key rotation can name two at once")
  void fingerprintsAreAList() throws Exception {
    // A rotation must publish the new digest while the old key is still installed everywhere. If
    // this ever became a bare string, that overlap would be impossible and one population would
    // break for the length of the rollout.
    mvc()
        .perform(get("/.well-known/assetlinks.json"))
        .andExpect(jsonPath("$[0].target.sha256_cert_fingerprints").isArray())
        .andExpect(jsonPath("$").isArray());
  }
}
