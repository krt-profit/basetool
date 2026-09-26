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

package de.greluc.krt.profit.basetool.frontend;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.ShallowEtagHeaderFilter;
import org.springframework.web.reactive.function.client.WebClient;

@SpringBootTest
@ActiveProfiles("test")
class StaticResourcesCachingTest {

  @Autowired private WebApplicationContext context;

  /** The ETag filter registration {@code EtagConfig} contributes, applied with its own scope. */
  @Autowired private FilterRegistrationBean<ShallowEtagHeaderFilter> shallowEtagHeaderFilter;

  @MockitoBean private WebClient webClient;

  @MockitoBean private WebClient termsDocumentClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilter(
                shallowEtagHeaderFilter.getFilter(),
                shallowEtagHeaderFilter.getUrlPatterns().toArray(String[]::new))
            .apply(springSecurity())
            .build();
  }

  /**
   * Verifies that a static asset keeps its year-long {@code immutable} cache header and {@code
   * Last-Modified}, answers {@code If-Modified-Since} with {@code 304}, and carries no ETag.
   *
   * @throws Exception if the MockMvc request fails
   */
  @Test
  void staticResource_ShouldBeImmutableWithLastModified_AndCarryNoEtag() throws Exception {
    String resource = "/images/drake_interplanetary_black.svg";

    String lastModified =
        mockMvc
            .perform(get(resource))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", containsString("max-age=31536000")))
            .andExpect(header().string("Cache-Control", containsString("immutable")))
            .andExpect(header().string("Last-Modified", notNullValue()))
            .andExpect(header().doesNotExist("ETag"))
            .andReturn()
            .getResponse()
            .getHeader("Last-Modified");

    mockMvc
        .perform(get(resource).header("If-Modified-Since", lastModified))
        .andExpect(status().isNotModified());
  }

  /**
   * Verifies that a page stylesheet is served without a session, with the year-long {@code
   * immutable} header and no ETag (FE-PERF-02).
   *
   * @throws Exception if the MockMvc request fails
   */
  @Test
  void pageStylesheet_ShouldBePublicAndImmutable() throws Exception {
    for (String resource : new String[] {"/css/pages/error-404.css", "/css/pages/hangar.css"}) {
      mockMvc
          .perform(get(resource))
          .andExpect(status().isOk())
          .andExpect(header().string("Cache-Control", containsString("max-age=31536000")))
          .andExpect(header().string("Cache-Control", containsString("immutable")))
          .andExpect(header().doesNotExist("ETag"));
    }
  }

  /**
   * The web app manifest keeps its ETag: it is publicly cacheable for an hour, so a browser that
   * re-reads it afterwards revalidates with {@code If-None-Match} and gets a body-less {@code 304}.
   *
   * @throws Exception if the MockMvc request fails
   */
  @Test
  void manifest_ShouldSendEtag_AndReturn304OnMatch() throws Exception {
    String etag =
        mockMvc
            .perform(get("/manifest.webmanifest"))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", notNullValue()))
            .andReturn()
            .getResponse()
            .getHeader("ETag");

    mockMvc
        .perform(get("/manifest.webmanifest").header("If-None-Match", etag))
        .andExpect(status().isNotModified());
  }

  /**
   * Verifies that a page outside the ETag filter's scope is not buffered, which shows as a missing
   * {@code Content-Length} (FE-PERF-03).
   *
   * @throws Exception if the MockMvc request fails
   */
  @Test
  void pageOutsideTheEtagScope_ShouldNotBeBuffered() throws Exception {
    mockMvc
        .perform(get("/impressum"))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("ETag"))
        .andExpect(header().doesNotExist("Content-Length"));
  }
}
