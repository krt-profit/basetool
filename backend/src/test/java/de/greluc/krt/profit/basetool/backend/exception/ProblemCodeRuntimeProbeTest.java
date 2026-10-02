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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * Runtime probes of the error-code registry (REQ-API-019): real requests through the security
 * chain, the filters and the exception handler answer with a {@code code} the registry holds.
 */
@SpringBootTest
class ProblemCodeRuntimeProbeTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  /** Builds MockMvc with the real security filter chain. */
  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * An anonymous read, a verb the path does not take, an unreadable body and a path no controller
   * serves each answer a registered code.
   *
   * @throws Exception if a request cannot be performed
   */
  @Test
  void everyProbedRefusalCarriesARegisteredCode() throws Exception {
    Set<String> registered =
        ProblemCodeRegistry.codes(ProblemCodeRegistry.registryEnums()).keySet();

    assertThat(codeOf(get("/api/v1/users/me"))).isEqualTo(CoreProblemCode.UNAUTHENTICATED.code());
    assertThat(
            codeOf(
                post("/api/v1/app/version-policy")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))))
        .isEqualTo(CoreProblemCode.METHOD_NOT_ALLOWED.code());

    String unreadable =
        codeOf(
            post("/api/v1/missions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not json"));
    assertThat(unreadable).isNotNull();
    assertThat(registered).contains(unreadable);

    String unknownPath =
        codeOf(
            get("/api/v1/a-path-no-controller-serves")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    assertThat(unknownPath).isNotNull();
    assertThat(registered).contains(unknownPath);
  }

  /**
   * Performs a request and reads the problem body's {@code code}.
   *
   * @param request the request
   * @return the code, or {@code null} when the body carries none
   * @throws Exception if the request cannot be performed
   */
  private String codeOf(RequestBuilder request) throws Exception {
    MvcResult result = mockMvc.perform(request).andReturn();
    String body = result.getResponse().getContentAsString();
    assertThat(result.getResponse().getStatus())
        .as("the probe expected a refusal, got %s", body)
        .isGreaterThanOrEqualTo(400);
    if (body.isBlank()) {
      return null;
    }
    return new ObjectMapper().readTree(body).path("code").asString(null);
  }
}
