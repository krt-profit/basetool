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

package de.greluc.krt.profit.basetool.backend.exchange.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDomain;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventRepository;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeNameWithholdingRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/** MockMvc tests of {@link ExchangeNameWithholdingController} (REQ-XCH-044). */
@SpringBootTest
@Transactional
class ExchangeNameWithholdingControllerTest {

  private static final String PATH = "/api/v1/connected-apps/name-withholding";
  private static final UUID MEMBER = UUID.fromString("44444444-4444-4444-4444-4444444440d1");

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ExchangeNameWithholdingRepository repository;
  @Autowired private AuditEventRepository auditEventRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    User user = new User();
    user.setId(MEMBER);
    user.setUsername("withholding-member");
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    user.setRoles(new HashSet<>(Set.of(roleRepository.findByCode(Roles.KRT_MEMBER).orElseThrow())));
    userRepository.saveAndFlush(user);
  }

  @Test
  void aMemberWhoNeverChoseSharesTheirNameAtVersionZero() throws Exception {
    mockMvc
        .perform(get(PATH).with(browser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.withholdName").value(false))
        .andExpect(jsonPath("$.version").value(0));
  }

  @Test
  void switchingOnIsStoredReadBackAndAudited() throws Exception {
    mockMvc
        .perform(
            put(PATH)
                .with(browser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"withholdName\":true,\"version\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.withholdName").value(true));

    mockMvc
        .perform(get(PATH).with(browser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.withholdName").value(true));
    assertThat(repository.findById(MEMBER))
        .hasValueSatisfying(r -> assertThat(r.isWithholdName()).isTrue());
    assertThat(auditEventRepository.findAll())
        .anyMatch(
            e ->
                e.getDomain() == AuditDomain.CONNECTED_APPS
                    && e.getEventType() == AuditEventType.EXCHANGE_NAME_WITHHOLDING_CHANGED
                    && MEMBER.equals(e.getTargetUserId()));
  }

  @Test
  void aStaleVersionIsAConflict() throws Exception {
    mockMvc
        .perform(
            put(PATH)
                .with(browser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"withholdName\":true,\"version\":0}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            put(PATH)
                .with(browser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"withholdName\":false,\"version\":7}"))
        .andExpect(status().isConflict());
  }

  @Test
  void aConnectedApplicationCanNeitherReadNorChangeIt() throws Exception {
    JwtRequestPostProcessor app =
        jwt().jwt(t -> t.subject(MEMBER.toString()).claim("azp", "versekit-ca"));

    mockMvc.perform(get(PATH).with(app)).andExpect(status().isForbidden());
    mockMvc
        .perform(
            put(PATH)
                .with(app)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"withholdName\":true,\"version\":0}"))
        .andExpect(status().isForbidden());
    assertThat(repository.findById(MEMBER)).isEmpty();
  }

  @Test
  void aMissingChoiceIsRejected() throws Exception {
    mockMvc
        .perform(
            put(PATH)
                .with(browser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}"))
        .andExpect(status().isBadRequest());
  }

  private static @NotNull JwtRequestPostProcessor browser() {
    return jwt().jwt(t -> t.subject(MEMBER.toString()).claim("azp", "basetool-frontend"));
  }
}
