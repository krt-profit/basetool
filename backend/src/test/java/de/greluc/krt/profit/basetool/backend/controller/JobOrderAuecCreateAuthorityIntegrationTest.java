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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyFixtures;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins who may create an aUEC order through the web: a contextual writer of either side inside the
 * active pin, an administrator within their scope, and the unit checks that come first
 * (REQ-ORDERS-053).
 */
@SpringBootTest
@Transactional
class JobOrderAuecCreateAuthorityIntegrationTest {

  @Autowired private WebApplicationContext context;
  @Autowired private ObjectMapper json;
  @Autowired private OrgUnitRepository units;
  @Autowired private SquadronRepository squadrons;
  @Autowired private UserRepository users;
  @Autowired private OrgUnitMembershipRepository memberships;

  private MockMvc mvc;
  private Squadron responsible;
  private Squadron requester;
  private Squadron unrelated;
  private UUID caller;

  @BeforeEach
  void seed() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    String tag = UUID.randomUUID().toString();
    OrgUnit profit = ProfitHierarchyFixtures.persistedProfitArea(units);
    responsible = squadron("Auec responsible " + tag, profit);
    requester = squadron("Auec requester " + tag, null);
    unrelated = squadron("Auec unrelated " + tag, null);
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("auec-create-" + tag);
    user = users.saveAndFlush(user);
    caller = user.getId();
    join(user, responsible);
    join(user, requester);
  }

  @Test
  void aRequestingWriterCreatesAnOpenOrderForTheNamedPair() throws Exception {
    String created =
        create(responsible.getId(), requester.getId(), writerOf(requester.getId()), null)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.type").value("AUEC"))
            .andExpect(jsonPath("$.status").value("OPEN"))
            .andExpect(jsonPath("$.responsibleOrgUnit.id").value(responsible.getId().toString()))
            .andExpect(jsonPath("$.requestingOrgUnit.id").value(requester.getId().toString()))
            .andExpect(jsonPath("$.canEdit").value(false))
            .andExpect(jsonPath("$.canEditAsRequester").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    mvc.perform(
            get("/api/v1/orders/{id}/money", json.readTree(created).get("id").asString())
                .with(
                    jwt()
                        .jwt(token -> token.subject(caller.toString()))
                        .authorities(writerOf(requester.getId()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.target").value("250000"));
  }

  @Test
  void aResponsibleWriterCreatesForARequesterItDoesNotWrite() throws Exception {
    create(responsible.getId(), requester.getId(), writerOf(responsible.getId()), null)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.canEdit").value(true))
        .andExpect(jsonPath("$.canEditAsRequester").value(false));
  }

  @Test
  void membershipOfBothSidesWithoutAGrantCannotCreate() throws Exception {
    create(responsible.getId(), requester.getId(), member(), null)
        .andExpect(status().isForbidden());
  }

  @Test
  void aPinOnTheOtherSideRefusesTheWriterOfOneSide() throws Exception {
    create(responsible.getId(), requester.getId(), writerOf(requester.getId()), responsible.getId())
        .andExpect(status().isForbidden());
    create(responsible.getId(), requester.getId(), writerOf(responsible.getId()), requester.getId())
        .andExpect(status().isForbidden());
  }

  @Test
  void aGrantOnAThirdUnitCannotCreate() throws Exception {
    create(responsible.getId(), requester.getId(), writerOf(unrelated.getId()), null)
        .andExpect(status().isForbidden());
  }

  @Test
  void anAdministratorCreatesWithoutAGrantWithinTheirScope() throws Exception {
    create(responsible.getId(), requester.getId(), admin(), null).andExpect(status().isCreated());
    create(responsible.getId(), requester.getId(), admin(), requester.getId())
        .andExpect(status().isCreated());
    create(responsible.getId(), requester.getId(), admin(), unrelated.getId())
        .andExpect(status().isForbidden());
  }

  @Test
  void theUnitsAreCheckedBeforeTheWriter() throws Exception {
    create(requester.getId(), requester.getId(), member(), null).andExpect(status().isBadRequest());
    create(responsible.getId(), UUID.randomUUID(), member(), null)
        .andExpect(status().isBadRequest());
    create(UUID.randomUUID(), requester.getId(), writerOf(requester.getId()), null)
        .andExpect(status().isBadRequest());
  }

  private ResultActions create(
      UUID responsibleId, UUID requestingId, List<GrantedAuthority> authorities, @Nullable UUID pin)
      throws Exception {
    MockHttpServletRequestBuilder request =
        post("/api/v1/orders/auec")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"responsibleOrgUnitId\":\""
                    + responsibleId
                    + "\",\"requestingOrgUnitId\":\""
                    + requestingId
                    + "\",\"handle\":\"Auec contact\",\"target\":\"250000\"}");
    if (pin != null) {
      request.header("X-Active-Org-Unit-Id", pin);
    }
    return mvc.perform(
        request
            .with(jwt().jwt(token -> token.subject(caller.toString())).authorities(authorities))
            .accept(MediaType.APPLICATION_JSON));
  }

  private static List<GrantedAuthority> member() {
    return List.of(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
  }

  private static List<GrantedAuthority> writerOf(UUID unit) {
    List<GrantedAuthority> authorities = new ArrayList<>(member());
    authorities.add(new SimpleGrantedAuthority("ROLE_LOGISTICIAN"));
    authorities.add(new OrgUnitContextualAuthority(Roles.LOGISTICIAN, unit));
    return authorities;
  }

  private static List<GrantedAuthority> admin() {
    List<GrantedAuthority> authorities = new ArrayList<>(member());
    authorities.add(new SimpleGrantedAuthority(Roles.authority(Roles.ADMIN)));
    return authorities;
  }

  private Squadron squadron(String name, @Nullable OrgUnit parent) {
    Squadron unit = new Squadron();
    unit.setName(name);
    unit.setShorthand(name);
    unit.setParent(parent);
    return squadrons.saveAndFlush(unit);
  }

  private void join(User user, OrgUnit unit) {
    OrgUnitMembership row = new OrgUnitMembership();
    row.setId(new OrgUnitMembershipId(user.getId(), unit.getId()));
    row.setUser(user);
    row.setKind(unit.getKind());
    row.setJoinedAt(Instant.now());
    memberships.saveAndFlush(row);
  }
}
