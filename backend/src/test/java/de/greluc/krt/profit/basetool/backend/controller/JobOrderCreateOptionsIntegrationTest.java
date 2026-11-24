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

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Department;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgHierarchyService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import de.greluc.krt.profit.basetool.backend.support.OrgUnitFixtures;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/** Verifies current creation options through the public authenticated order HTTP surface. */
@SpringBootTest
@Transactional
class JobOrderCreateOptionsIntegrationTest {

  @Autowired private WebApplicationContext context;
  @Autowired private OrgHierarchyService hierarchy;
  @Autowired private OrgUnitRepository units;
  @Autowired private SquadronRepository squadrons;
  @Autowired private UserRepository users;
  @Autowired private OrgUnitMembershipRepository memberships;

  private MockMvc mvc;
  private Squadron responsible;
  private Squadron requester;
  private Squadron unrelated;
  private UUID caller;
  private UUID profitRootId;

  @BeforeEach
  void seed() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    String tag = UUID.randomUUID().toString();
    List<UUID> roots = units.findActiveProfitRootIds();
    OrgUnit profit =
        roots.isEmpty()
            ? hierarchy.createBereich(
                "Creation Profit " + tag, "CP" + tag, null, null, Department.PROFIT)
            : units.findById(roots.getFirst()).orElseThrow();
    profitRootId = profit.getId();
    responsible =
        OrgUnitFixtures.persistedSquadron(squadrons, "0 Creation responsible " + tag, profit);
    requester = OrgUnitFixtures.persistedSquadron(squadrons, "Creation requester " + tag, null);
    unrelated = OrgUnitFixtures.persistedSquadron(squadrons, "Creation unrelated " + tag, null);
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("creation-" + tag);
    user = users.saveAndFlush(user);
    caller = user.getId();
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(caller, requester.getId()));
    membership.setUser(user);
    membership.setKind(requester.getKind());
    membership.setJoinedAt(Instant.now());
    memberships.saveAndFlush(membership);
  }

  @Test
  void plainMembershipReadsWorkspaceWithoutBeingOfferedCreation() throws Exception {
    mvc.perform(
            get("/api/v1/orders/create-options")
                .with(
                    jwt()
                        .jwt(token -> token.subject(caller.toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.canCreate").value(false))
        .andExpect(jsonPath("$.responsibleOptions").isEmpty())
        .andExpect(jsonPath("$.requestingOptions").isEmpty());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void eitherCurrentSideWriterReceivesOnlyUsableOptionsAndTruthfulCapabilities(
      boolean responsibleSide) throws Exception {
    UUID grant = responsibleSide ? responsible.getId() : requester.getId();
    mvc.perform(
            get("/api/v1/orders/create-options")
                .with(
                    jwt()
                        .jwt(token -> token.subject(caller.toString()))
                        .authorities(
                            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
                            new SimpleGrantedAuthority("ROLE_LOGISTICIAN"),
                            new OrgUnitContextualAuthority(Roles.LOGISTICIAN, grant))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.canCreate").value(true))
        .andExpect(
            jsonPath(
                    "$.responsibleOptions[?(@.orgUnitId == '"
                        + responsible.getId()
                        + "')].canWrite")
                .value(org.hamcrest.Matchers.contains(responsibleSide)))
        .andExpect(
            jsonPath("$.requestingOptions[?(@.orgUnitId == '" + requester.getId() + "')].canWrite")
                .value(org.hamcrest.Matchers.contains(!responsibleSide)))
        .andExpect(jsonPath("$.suggestedResponsibleOrgUnitId").value(not(profitRootId.toString())))
        .andExpect(jsonPath("$.suggestedRequestingOrgUnitId").value(grant.toString()))
        .andExpect(jsonPath("$.suggestedAuecResponsibleOrgUnitId").value(profitRootId.toString()));
    mvc.perform(
            get("/api/v1/me/capabilities")
                .with(
                    jwt()
                        .jwt(token -> token.subject(caller.toString()))
                        .authorities(
                            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
                            new SimpleGrantedAuthority("ROLE_LOGISTICIAN"),
                            new OrgUnitContextualAuthority(Roles.LOGISTICIAN, grant))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.canViewJobOrders").value(false))
        .andExpect(jsonPath("$.canViewOrderWorkspace").value(true))
        .andExpect(jsonPath("$.canViewOrderUnitWork").value(responsibleSide))
        .andExpect(jsonPath("$.canCreateJobOrders").value(true));
  }

  @Test
  void pinningAwayFromTheWriterRevokesCreationWithoutRevokingOrdinaryRead() throws Exception {
    mvc.perform(
            get("/api/v1/orders/create-options")
                .header("X-Active-Org-Unit-Id", requester.getId())
                .with(
                    jwt()
                        .jwt(token -> token.subject(caller.toString()))
                        .authorities(
                            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
                            new SimpleGrantedAuthority("ROLE_LOGISTICIAN"),
                            new OrgUnitContextualAuthority(
                                Roles.LOGISTICIAN, responsible.getId()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.canCreate").value(false))
        .andExpect(jsonPath("$.responsibleOptions").isEmpty())
        .andExpect(jsonPath("$.requestingOptions").isEmpty());
  }

  @Test
  void aRequestingWriterIsOfferedOnlyTheRequestingUnitsItWrites() throws Exception {
    options(null, writerOf(requester.getId()))
        .andExpect(jsonPath(requesting(requester) + ".canWrite").value(contains(true)))
        .andExpect(jsonPath(requesting(unrelated)).value(empty()))
        .andExpect(jsonPath(responsibleOption(responsible) + ".canWrite").value(contains(false)));
  }

  @Test
  void aResponsibleWriterIsOfferedEveryActiveUnitAsRequester() throws Exception {
    options(null, writerOf(responsible.getId()))
        .andExpect(jsonPath(requesting(requester) + ".canWrite").value(contains(false)))
        .andExpect(jsonPath(requesting(unrelated) + ".canWrite").value(contains(false)))
        .andExpect(jsonPath(responsibleOption(responsible) + ".canWrite").value(contains(true)));
  }

  @Test
  void anAdministratorWithoutAPinWritesEveryOfferedUnit() throws Exception {
    options(null, admin())
        .andExpect(jsonPath("$.canCreate").value(true))
        .andExpect(jsonPath(requesting(requester) + ".canWrite").value(contains(true)))
        .andExpect(jsonPath(requesting(unrelated) + ".canWrite").value(contains(true)))
        .andExpect(jsonPath(responsibleOption(responsible) + ".canWrite").value(contains(true)))
        .andExpect(jsonPath("$.suggestedAuecResponsibleOrgUnitId").value(profitRootId.toString()));
  }

  @Test
  void anAdministratorPinnedToOneUnitWritesOnlyThatUnit() throws Exception {
    options(requester.getId(), admin())
        .andExpect(jsonPath("$.canCreate").value(true))
        .andExpect(jsonPath(requesting(requester) + ".canWrite").value(contains(true)))
        .andExpect(jsonPath(requesting(unrelated)).value(empty()))
        .andExpect(jsonPath(responsibleOption(responsible) + ".canWrite").value(contains(false)))
        .andExpect(jsonPath("$.suggestedRequestingOrgUnitId").value(requester.getId().toString()));
  }

  @Test
  void aDeactivatedUnitIsNeverOffered() throws Exception {
    unrelated.setActive(false);
    squadrons.saveAndFlush(unrelated);

    options(null, admin()).andExpect(jsonPath(requesting(unrelated)).value(empty()));
  }

  private ResultActions options(UUID pin, List<GrantedAuthority> authorities) throws Exception {
    MockHttpServletRequestBuilder request = get("/api/v1/orders/create-options");
    if (pin != null) {
      request.header("X-Active-Org-Unit-Id", pin);
    }
    return mvc.perform(
            request.with(
                jwt().jwt(token -> token.subject(caller.toString())).authorities(authorities)))
        .andExpect(status().isOk());
  }

  private static String requesting(OrgUnit unit) {
    return "$.requestingOptions[?(@.orgUnitId == '" + unit.getId() + "')]";
  }

  private static String responsibleOption(OrgUnit unit) {
    return "$.responsibleOptions[?(@.orgUnitId == '" + unit.getId() + "')]";
  }

  private static List<GrantedAuthority> writerOf(UUID unit) {
    return List.of(
        new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
        new SimpleGrantedAuthority("ROLE_LOGISTICIAN"),
        new OrgUnitContextualAuthority(Roles.LOGISTICIAN, unit));
  }

  private static List<GrantedAuthority> admin() {
    return List.of(
        new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
        new SimpleGrantedAuthority(Roles.authority(Roles.ADMIN)));
  }
}
