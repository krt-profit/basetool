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

package de.greluc.krt.profit.basetool.backend.orgchart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipChangeObserver;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * Pins the org chart module's externally visible contract: the status of every {@code
 * /api/v1/org-chart} and {@code /api/v1/leitung} endpoint per caller, the RFC 7807 answers, and the
 * org chart as the one {@link MembershipChangeObserver}, whose mirror refuses to run outside the
 * caller's transaction (REQ-ROLE-006).
 */
@SpringBootTest
@Transactional
class OrgChartModuleContractTest {

  private static final String CHART = "/api/v1/org-chart";

  private static final String POSITIONS = CHART + "/positions/";

  private static final String LEITUNG_VIEW = "/api/v1/leitung/view";

  private static final String PROBLEM_JSON = "application/problem+json";

  @Autowired private WebApplicationContext context;

  @Autowired private PlatformTransactionManager transactionManager;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void anonymousCallerIsUnauthorizedOnEveryReadEndpoint() throws Exception {
    mockMvc.perform(get(CHART)).andExpect(status().isUnauthorized());
    mockMvc.perform(get(LEITUNG_VIEW)).andExpect(status().isUnauthorized());
  }

  @Test
  void memberReadsTheChartWithItsFiveSections() throws Exception {
    mockMvc
        .perform(get(CHART).with(member()).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.bereiche").isArray())
        .andExpect(jsonPath("$.squadrons").isArray())
        .andExpect(jsonPath("$.specialCommands").isArray())
        .andExpect(jsonPath("$.areaLeadership").exists());
  }

  @Test
  void memberReadsTheLeitungViewAsNonAdmin() throws Exception {
    mockMvc
        .perform(get(LEITUNG_VIEW).with(member()).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.admin").value(false))
        .andExpect(jsonPath("$.organisationsleitungen").isArray())
        .andExpect(jsonPath("$.bereiche").isArray())
        .andExpect(jsonPath("$.squadrons").isArray())
        .andExpect(jsonPath("$.specialCommands").isArray());
  }

  @Test
  void adminReadsTheLeitungViewAsAdmin() throws Exception {
    mockMvc
        .perform(get(LEITUNG_VIEW).with(admin()).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.admin").value(true));
  }

  @Test
  void everyPositionWriteIsForbiddenForANonAdmin() throws Exception {
    UUID id = UUID.randomUUID();
    mockMvc
        .perform(
            post(CHART + "/positions")
                .with(officer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"positionType\":\"AREA_COORDINATOR\"}"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            put(POSITIONS + id)
                .with(officer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(delete(POSITIONS + id + "/leader").param("version", "0").with(officer()))
        .andExpect(status().isForbidden());
    mockMvc.perform(delete(POSITIONS + id).with(officer())).andExpect(status().isForbidden());
  }

  @Test
  void adminCreateWithoutAPositionTypeIsAValidationProblem() throws Exception {
    mockMvc
        .perform(
            post(CHART + "/positions")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.fieldErrors").exists());
  }

  @Test
  void adminWritesOnAnUnknownPositionAnswerNotFound() throws Exception {
    UUID id = UUID.randomUUID();
    mockMvc
        .perform(
            put(POSITIONS + id)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    mockMvc
        .perform(delete(POSITIONS + id + "/leader").param("version", "0").with(admin()))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    mockMvc
        .perform(delete(POSITIONS + id).with(admin()))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
  }

  @Test
  void theOrgChartIsTheOnlyMembershipChangeObserver() {
    Map<String, MembershipChangeObserver> observers =
        context.getBeansOfType(MembershipChangeObserver.class);

    assertThat(observers).containsOnlyKeys("orgChartService");
    assertThat(AopUtils.getTargetClass(observers.get("orgChartService")).getSimpleName())
        .isEqualTo("OrgChartService");
  }

  @Test
  void theMirrorRefusesToRunOutsideTheCallersTransaction() {
    MembershipChangeObserver observer =
        context.getBean("orgChartService", MembershipChangeObserver.class);
    TransactionTemplate withoutTransaction = new TransactionTemplate(transactionManager);
    withoutTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);

    assertThatThrownBy(
            () ->
                withoutTransaction.executeWithoutResult(
                    _ -> observer.onUnitMembershipRemoved(UUID.randomUUID(), UUID.randomUUID())))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  private static RequestPostProcessor member() {
    return jwt().authorities(new SimpleGrantedAuthority("ROLE_MEMBER"));
  }

  private static RequestPostProcessor officer() {
    return jwt().authorities(new SimpleGrantedAuthority("ROLE_OFFICER"));
  }

  private static RequestPostProcessor admin() {
    return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }
}
