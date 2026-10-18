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

package de.greluc.krt.profit.basetool.backend.inventory;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Pins how a malformed Lager filter value is answered: 400 {@code application/problem+json}, the
 * same as a mistyped request parameter (REQ-API, REQ-INV-066).
 */
@SpringBootTest
class LagerFilterBindingTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/inventory/all/grouped?stolenOnly=abc",
        "/api/v1/inventory/all/grouped?materialIds=not-a-uuid",
        "/api/v1/inventory/all/grouped?catalog=ITEM&kinds=BOGUS",
        "/api/v1/inventory/all?minQuality=abc",
        "/api/v1/inventory/my-inventory/grouped?personalOnly=abc",
        "/api/v1/inventory/my-inventory/entry-ids?catalog=BOGUS"
      })
  void aMalformedFilterValueAnswersBadRequestProblemJson(String url) throws Exception {
    mockMvc
        .perform(
            get(url)
                .with(
                    jwt()
                        .authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_KRT_MEMBER"))))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  void aWellFormedFilterIsNotRejected() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/inventory/all/grouped?stolenOnly=true&nonStolenOnly=true")
                .with(
                    jwt()
                        .authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_KRT_MEMBER"))))
        .andExpect(status().isOk());
  }
}
