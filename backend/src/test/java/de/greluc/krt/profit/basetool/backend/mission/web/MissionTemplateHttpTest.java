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

package de.greluc.krt.profit.basetool.backend.mission.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Bereich;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import de.greluc.krt.profit.basetool.backend.platform.internal.ApiCacheControlFilter;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/** Exercises the mission template lifecycle through the HTTP API and Postgres. */
@SpringBootTest
@Transactional
class MissionTemplateHttpTest {

  private static final String PATH = "/api/v1/mission-templates";

  @Autowired private WebApplicationContext context;
  @Autowired private OrgUnitRepository orgUnits;
  @Autowired private UserRepository users;
  @Autowired private OrgUnitMembershipRepository memberships;

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(ApiCacheControlFilter.class))
            .apply(springSecurity())
            .build();
  }

  @Test
  void createsAndListsBlankTemplateInItsOwningUnit() throws Exception {
    UUID owner = createOwner();
    mvc.perform(
            post(PATH)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(blank(owner, "Recovery")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.owningOrgUnitId").value(owner.toString()))
        .andExpect(jsonPath("$.name").value("Recovery"))
        .andExpect(jsonPath("$.version").isNumber())
        .andExpect(jsonPath("$.objectives").isEmpty())
        .andExpect(jsonPath("$.steps").isEmpty())
        .andExpect(jsonPath("$.units").isEmpty());

    mvc.perform(get(PATH).with(admin()).param("owningOrgUnitId", owner.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("Recovery"));
  }

  @Test
  void replacingPlanningRowsAdvancesRevisionAndRejectsStaleDeletion() throws Exception {
    UUID owner = createOwner();
    MvcResult saved = create(owner, "Recovery");
    String id = JsonPath.read(saved.getResponse().getContentAsString(), "$.id");
    Number version = JsonPath.read(saved.getResponse().getContentAsString(), "$.version");
    String update =
        """
        {
          "version":%s,"name":"Recovery",
          "objectives":[{"title":"Survey wreck","kind":"SECONDARY"},{"title":"Recover cargo","kind":"PRIMARY"}],
          "steps":[{"title":"Launch","meta":"At 18:00"},{"title":"Extract"}],
          "units":[{"name":"Scout","note":"Check landing zone","highValueUnit":true,"frequency":127.456}]
        }
        """
            .formatted(version);
    MvcResult edited =
        mvc.perform(
                put(PATH + "/{id}", id)
                    .with(admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(update))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(version.longValue() + 1))
            .andReturn();
    Number latestVersion = JsonPath.read(edited.getResponse().getContentAsString(), "$.version");

    mvc.perform(get(PATH + "/{id}", id).with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.objectives[0].title").value("Survey wreck"))
        .andExpect(jsonPath("$.objectives[1].kind").value("PRIMARY"))
        .andExpect(jsonPath("$.steps[0].meta").value("At 18:00"))
        .andExpect(jsonPath("$.steps[1].title").value("Extract"))
        .andExpect(jsonPath("$.units[0].name").value("Scout"))
        .andExpect(jsonPath("$.units[0].note").value("Check landing zone"))
        .andExpect(jsonPath("$.units[0].highValueUnit").value(true))
        .andExpect(jsonPath("$.units[0].frequency").value(127.46));

    mvc.perform(delete(PATH + "/{id}", id).with(admin()).param("version", version.toString()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK"));
    expectAuditCount(id, "MISSION_TEMPLATE_UPDATED", 1);
    expectAuditCount(id, "MISSION_TEMPLATE_DELETED", 0);
    mvc.perform(delete(PATH + "/{id}", id).with(admin()).param("version", latestVersion.toString()))
        .andExpect(status().isNoContent());
    expectAuditCount(id, "MISSION_TEMPLATE_DELETED", 1);
    mvc.perform(get(PATH).with(admin()).param("owningOrgUnitId", owner.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }

  private MvcResult create(UUID owner, String name) throws Exception {
    return mvc.perform(
            post(PATH)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(blank(owner, name)))
        .andExpect(status().isCreated())
        .andReturn();
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void concurrentDuplicateNamesHaveOneWinnerAndOneAuditEvent() throws Exception {
    UUID owner = createOwner();
    UUID otherOwner = createOwner();
    String name = "Recovery " + owner;
    try {
      CountDownLatch ready = new CountDownLatch(2);
      CountDownLatch start = new CountDownLatch(1);
      try (var executor = Executors.newFixedThreadPool(2)) {
        Callable<MvcResult> request =
            () -> {
              ready.countDown();
              assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
              return mvc.perform(
                      post(PATH)
                          .with(manager(owner))
                          .locale(Locale.ENGLISH)
                          .contentType(MediaType.APPLICATION_JSON)
                          .content(blank(owner, name)))
                  .andReturn();
            };
        var first = executor.submit(request);
        var second = executor.submit(request);
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        MvcResult firstResult = first.get(30, TimeUnit.SECONDS);
        MvcResult secondResult = second.get(30, TimeUnit.SECONDS);
        assertThat(
                List.of(
                    firstResult.getResponse().getStatus(), secondResult.getResponse().getStatus()))
            .containsExactlyInAnyOrder(201, 409);

        MvcResult loser = firstResult.getResponse().getStatus() == 409 ? firstResult : secondResult;
        String problem = loser.getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(problem, "$.code")).isEqualTo("DUPLICATE_ENTITY");
        assertThat(JsonPath.<String>read(problem, "$.fieldErrors[0].field")).isEqualTo("name");

        MvcResult winner =
            firstResult.getResponse().getStatus() == 201 ? firstResult : secondResult;
        String id = JsonPath.read(winner.getResponse().getContentAsString(), "$.id");
        mvc.perform(get(PATH + "/{id}", id).with(manager(owner)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value(name));
        mvc.perform(
                get("/api/v1/audit/MISSION")
                    .with(admin())
                    .param("eventType", "MISSION_TEMPLATE_CREATED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[?(@.subjectId == '%s')]", id).value(hasSize(1)))
            .andExpect(
                jsonPath("$.content[?(@.subjectId == '%s')].details", id)
                    .value(contains("orgUnit=" + owner)));
      }
      mvc.perform(
              post(PATH)
                  .with(manager(otherOwner))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(blank(otherOwner, name)))
          .andExpect(status().isCreated());
      mvc.perform(
              post(PATH)
                  .with(manager(owner))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(blank(owner, name + " alternate")))
          .andExpect(status().isCreated());
    } finally {
      deleteFixtures(owner);
      deleteFixtures(otherOwner);
    }
  }

  private void deleteFixtures(UUID owner) throws Exception {
    MvcResult list =
        mvc.perform(get(PATH).with(admin()).param("owningOrgUnitId", owner.toString()))
            .andExpect(status().isOk())
            .andReturn();
    List<String> ids = JsonPath.read(list.getResponse().getContentAsString(), "$[*].id");
    List<Number> versions = JsonPath.read(list.getResponse().getContentAsString(), "$[*].version");
    for (int index = 0; index < ids.size(); index++) {
      mvc.perform(
              delete(PATH + "/{id}", ids.get(index))
                  .with(admin())
                  .param("version", versions.get(index).toString()))
          .andExpect(status().isNoContent());
    }
    orgUnits.deleteById(owner);
  }

  private static JwtRequestPostProcessor manager(UUID owner) {
    return jwt()
        .authorities(
            new SimpleGrantedAuthority(Roles.authority(Roles.KRT_MEMBER)),
            new OrgUnitContextualAuthority(Roles.MISSION_MANAGER, owner));
  }

  @Test
  void foreignScopeCannotListCreateReadUpdateOrDeleteTemplates() throws Exception {
    UUID owner = createOwner();
    UUID foreign = createOwner();
    MvcResult saved = create(foreign, "Foreign plan");
    String id = JsonPath.read(saved.getResponse().getContentAsString(), "$.id");
    Number version = JsonPath.read(saved.getResponse().getContentAsString(), "$.version");
    String owners = PATH + "/owning-org-units";

    mvc.perform(get(owners).with(manager(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].id").value(contains(owner.toString())));
    mvc.perform(get(owners).with(member()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
    mvc.perform(get(owners).with(admin()).header("X-Active-Org-Unit-Id", owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].id").value(contains(owner.toString())));

    mvc.perform(get(PATH).with(manager(owner)).param("owningOrgUnitId", foreign.toString()))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(PATH)
                .with(manager(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(blank(foreign, "Forbidden plan")))
        .andExpect(status().isForbidden());
    mvc.perform(get(PATH + "/{id}", id).with(manager(owner))).andExpect(status().isNotFound());
    mvc.perform(get(PATH + "/{id}", UUID.randomUUID()).with(manager(owner)))
        .andExpect(status().isNotFound());
    mvc.perform(
            put(PATH + "/{id}", id)
                .with(manager(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"version":%s,"name":"Forbidden edit","objectives":[],"steps":[],"units":[]}
                    """
                        .formatted(version)))
        .andExpect(status().isNotFound());
    mvc.perform(
            delete(PATH + "/{id}", id).with(manager(owner)).param("version", version.toString()))
        .andExpect(status().isNotFound());

    mvc.perform(get(PATH + "/{id}", id).with(manager(foreign)))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "private, no-store"))
        .andExpect(jsonPath("$.name").value("Foreign plan"))
        .andExpect(jsonPath("$.version").value(version.intValue()));
    expectAuditCount(id, "MISSION_TEMPLATE_UPDATED", 0);
    expectAuditCount(id, "MISSION_TEMPLATE_DELETED", 0);
  }

  @Test
  void anOfficerSeatCanManageTemplatesWithoutAMissionManagerGrant() throws Exception {
    UUID owner = createOwner();
    User officer = new User();
    officer.setId(UUID.randomUUID());
    officer.setUsername("template-officer-" + officer.getId());
    officer.setApprovalStatus(ApprovalStatus.ACTIVE);
    officer.setInKeycloak(true);
    users.saveAndFlush(officer);

    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(officer.getId(), owner));
    membership.setUser(officer);
    membership.setRole(MembershipRole.BEREICHSLEITER);
    membership.setJoinedAt(Instant.now());
    memberships.saveAndFlush(membership);

    JwtRequestPostProcessor caller =
        member().jwt(token -> token.subject(officer.getId().toString()));
    mvc.perform(get(PATH + "/owning-org-units").with(caller))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].id").value(contains(owner.toString())));
    mvc.perform(
            post(PATH)
                .with(caller)
                .contentType(MediaType.APPLICATION_JSON)
                .content(blank(owner, "Officer plan")))
        .andExpect(status().isCreated());
  }

  private void expectAuditCount(String id, String eventType, int count) throws Exception {
    mvc.perform(get("/api/v1/audit/MISSION").with(admin()).param("eventType", eventType))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[?(@.subjectId == '%s')]", id).value(hasSize(count)));
  }

  private static JwtRequestPostProcessor member() {
    return jwt().authorities(new SimpleGrantedAuthority(Roles.authority(Roles.KRT_MEMBER)));
  }

  private UUID createOwner() {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    Bereich owner = new Bereich();
    owner.setName("Template test area " + unique);
    owner.setShorthand("T" + unique);
    return orgUnits.saveAndFlush(owner).getId();
  }

  private static JwtRequestPostProcessor admin() {
    return jwt().authorities(new SimpleGrantedAuthority(Roles.authority(Roles.ADMIN)));
  }

  private static String blank(UUID owner, String name) {
    return """
    {"owningOrgUnitId":"%s","name":"%s","objectives":[],"steps":[],"units":[]}
    """
        .formatted(owner, name);
  }
}
