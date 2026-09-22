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

package de.greluc.krt.profit.basetool.backend.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.service.SpecialCommandSecurityService;
import de.greluc.krt.profit.basetool.backend.service.SpecialCommandService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MockMvc gate matrix for {@code GET /api/v1/special-commands/{id}}, which the SK member page reads
 * to render its header. The endpoint shares the {@link
 * SpecialCommandSecurityService#canManageMembers} gate of the membership endpoints, so an admin and
 * the SK lead of <em>this</em> SK get the row while a lead of another SK and a plain member are
 * forbidden.
 *
 * <p>The security service is a spy, not a mock: the admin and the plain-member cases run its real
 * verdict (the admin short-circuit from the JWT authorities, and a membership lookup that finds no
 * row for a random subject). Only the SK-lead cases stub the verdict for one SK id, because a real
 * lead would need a persisted membership row; that pins that the SpEL passes the path {@code #id}
 * through, while the lookup itself is unit-tested in {@code SpecialCommandSecurityServiceTest}. The
 * write endpoints stay admin-only — the last case proves a lead is still refused an update.
 */
@SpringBootTest
@ActiveProfiles("test")
class SpecialCommandControllerSecurityTest {

  @Autowired private WebApplicationContext context;
  private MockMvc mockMvc;

  @MockitoBean private SpecialCommandService specialCommandService;
  @MockitoSpyBean private SpecialCommandSecurityService specialCommandSecurityService;
  @MockitoBean private JwtDecoder jwtDecoder;

  private final UUID skId = UUID.randomUUID();
  private final UUID otherSkId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    SpecialCommand sk = new SpecialCommand();
    sk.setId(skId);
    sk.setName("Alpha SK");
    sk.setShorthand("ASK");
    when(specialCommandService.getSpecialCommandById(skId)).thenReturn(sk);
  }

  /**
   * Builds a JWT caller with a random subject and the given authorities.
   *
   * @param roles the {@code ROLE_*} authorities to grant.
   * @return the request post-processor carrying the JWT.
   */
  private static RequestPostProcessor caller(String... roles) {
    SimpleGrantedAuthority[] auths = new SimpleGrantedAuthority[roles.length];
    for (int i = 0; i < roles.length; i++) {
      auths[i] = new SimpleGrantedAuthority(roles[i]);
    }
    return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())).authorities(auths);
  }

  @Test
  void getSpecialCommand_admin_isAllowed() throws Exception {
    mockMvc
        .perform(get("/api/v1/special-commands/{id}", skId).with(caller("ROLE_ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Alpha SK"));
  }

  @Test
  void getSpecialCommand_leadOfThisSk_isAllowed() throws Exception {
    doReturn(true).when(specialCommandSecurityService).canManageMembers(eq(skId), any());
    mockMvc
        .perform(get("/api/v1/special-commands/{id}", skId).with(caller("ROLE_OFFICER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.shorthand").value("ASK"));
  }

  @Test
  void getSpecialCommand_leadOfAnotherSk_isForbidden() throws Exception {
    doReturn(true).when(specialCommandSecurityService).canManageMembers(eq(otherSkId), any());
    mockMvc
        .perform(get("/api/v1/special-commands/{id}", skId).with(caller("ROLE_OFFICER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void getSpecialCommand_plainMember_isForbidden() throws Exception {
    mockMvc
        .perform(get("/api/v1/special-commands/{id}", skId).with(caller("ROLE_KRT_MEMBER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void updateSpecialCommand_leadOfThisSk_isStillForbidden() throws Exception {
    doReturn(true).when(specialCommandSecurityService).canManageMembers(eq(skId), any());
    mockMvc
        .perform(
            put("/api/v1/special-commands/{id}", skId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"id\":\""
                        + skId
                        + "\",\"name\":\"Alpha SK\",\"shorthand\":\"ASK\",\"active\":true,"
                        + "\"version\":0}")
                .with(caller("ROLE_OFFICER")))
        .andExpect(status().isForbidden());
  }
}
