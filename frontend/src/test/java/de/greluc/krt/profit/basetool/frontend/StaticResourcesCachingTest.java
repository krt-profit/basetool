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

  @MockitoBean private WebClient webClient;

  @MockitoBean private WebClient termsDocumentClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilter(new ShallowEtagHeaderFilter(), "/*")
            .apply(springSecurity())
            .build();
  }

  @Test
  void staticResource_ShouldSendEtag_AndReturn304OnMatch() throws Exception {
    String resource = "/images/drake_interplanetary_black.svg";

    String etag =
        mockMvc
            .perform(get(resource))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", notNullValue()))
            .andExpect(header().string("Cache-Control", containsString("max-age")))
            .andReturn()
            .getResponse()
            .getHeader("ETag");

    mockMvc
        .perform(get(resource).header("If-None-Match", etag))
        .andExpect(status().isNotModified());
  }
}
