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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
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
import java.util.concurrent.TimeoutException;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/** Exercises the mission template lifecycle through the HTTP API and Postgres. */
@SpringBootTest
@Transactional
class MissionTemplateHttpTest {

  private static final String PATH = "/api/v1/mission-templates";
  private static final String CHOICES = "/api/v1/missions/template-choices";

  @Autowired private WebApplicationContext context;
  @Autowired private OrgUnitRepository orgUnits;
  @Autowired private UserRepository users;
  @Autowired private RoleRepository roles;
  @Autowired private PlatformTransactionManager transactionManager;
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
        .andExpect(jsonPath("$.content[0].name").value("Recovery"));
  }

  @Test
  void managementAndApplicationListPagesUseTheEffectiveOwnerAndStableOrder() throws Exception {
    UUID owner = createOwner();
    UUID foreign = createOwner();
    create(owner, "Recovery");
    create(owner, "Patrol");
    create(foreign, "Extraction");
    JwtRequestPostProcessor planner = planner(owner);

    mvc.perform(
            get(PATH)
                .with(admin())
                .param("owningOrgUnitId", owner.toString())
                .param("page", "0")
                .param("size", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[*].name").value(contains("Patrol")))
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.totalPages").value(2));
    mvc.perform(
            get(PATH)
                .with(admin())
                .param("owningOrgUnitId", owner.toString())
                .param("page", "1")
                .param("size", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[*].name").value(contains("Recovery")))
        .andExpect(jsonPath("$.page").value(1));
    mvc.perform(get(CHOICES).with(planner).param("page", "1").param("size", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[*].name").value(contains("Recovery")))
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.totalPages").value(2));
    mvc.perform(get(CHOICES).with(planner).param("page", "-1").param("size", "1001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[*].name").value(contains("Patrol", "Recovery")))
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(1000));
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void choicesForAMemberOfSeveralUnitsWithoutAPickAreAnEmptyPage() throws Exception {
    UUID first = createOwner();
    UUID second = createOwner();
    User planner = createPlanner(first, second);
    try {
      mvc.perform(
              get(CHOICES).with(member().jwt(token -> token.subject(planner.getId().toString()))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.content").isEmpty())
          .andExpect(jsonPath("$.totalElements").value(0));
    } finally {
      memberships.deleteById(new OrgUnitMembershipId(planner.getId(), first));
      memberships.deleteById(new OrgUnitMembershipId(planner.getId(), second));
      users.deleteById(planner.getId());
      orgUnits.deleteById(first);
      orgUnits.deleteById(second);
    }
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
        .andExpect(jsonPath("$.content").isEmpty());
  }

  @Test
  void missionPreviewUsesTheEffectiveOwnerWithoutGrantingTemplateManagement() throws Exception {
    UUID owner = createOwner();
    UUID foreign = createOwner();
    MvcResult saved = create(owner, "Recovery");
    String id = JsonPath.read(saved.getResponse().getContentAsString(), "$.id");
    Number version = JsonPath.read(saved.getResponse().getContentAsString(), "$.version");
    String preview = "/api/v1/missions/template-preview/{id}";

    JwtRequestPostProcessor planner = planner(owner);

    mvc.perform(get(CHOICES).with(planner).header("If-None-Match", "*"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "private, no-store"))
        .andExpect(header().doesNotExist("ETag"))
        .andExpect(jsonPath("$.content[*].id").value(contains(id)));
    mvc.perform(get(preview, id).with(planner).header("If-None-Match", "*"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "private, no-store"))
        .andExpect(header().doesNotExist("ETag"))
        .andExpect(jsonPath("$.version").value(version.intValue()))
        .andExpect(jsonPath("$.units").isEmpty());
    mvc.perform(get(preview, id).with(planner(foreign))).andExpect(status().isNotFound());
    mvc.perform(get(PATH + "/{id}", id).with(planner)).andExpect(status().isNotFound());
    JwtRequestPostProcessor ownerless = planner(null);
    mvc.perform(get(CHOICES).with(ownerless))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isEmpty())
        .andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(get(preview, id).with(ownerless)).andExpect(status().isNotFound());
  }

  @Test
  void staleSelectionLeavesNoMissionOrAuditAndEditedRowsSurviveDetachment() throws Exception {
    UUID owner = createOwner();
    MvcResult saved = create(owner, "Recovery");
    String id = JsonPath.read(saved.getResponse().getContentAsString(), "$.id");
    Number version = JsonPath.read(saved.getResponse().getContentAsString(), "$.version");
    JwtRequestPostProcessor planner = planner(owner);
    String mission = "Edited rescue plan";
    String draft =
        """
        {"name":"%s","status":"PLANNED","isInternal":true,
         "objectives":[{"title":"Secure site","kind":"SECONDARY"},{"title":"Recover pilot","kind":"PRIMARY"}],
         "steps":[{"title":"Brief","meta":"Hangar","done":true,"orderIndex":23},{"title":"Launch"}],
         "units":[{"name":"Scout","note":"Check landing zone","highValueUnit":true,"frequency":127.456}]%s}
        """;
    String selection =
        """
        ,"templateSelection":{"id":"%s","version":%s}
        """
            .formatted(id, version);

    MvcResult edited =
        mvc.perform(
                put(PATH + "/{id}", id)
                    .with(admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"version":%s,"name":"Recovery","objectives":[],"steps":[{"title":"Scan"}],"units":[]}
                        """
                            .formatted(version)))
            .andExpect(status().isOk())
            .andReturn();

    expectStaleMission(planner, draft.formatted(mission, selection));
    Number currentVersion = JsonPath.read(edited.getResponse().getContentAsString(), "$.version");
    mvc.perform(
            delete(PATH + "/{id}", id).with(admin()).param("version", currentVersion.toString()))
        .andExpect(status().isNoContent());
    expectStaleMission(planner, draft.formatted(mission, selection));

    mvc.perform(get("/api/v1/missions").with(planner).param("q", mission))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isEmpty());
    mvc.perform(get("/api/v1/audit/MISSION").with(admin()).param("eventType", "MISSION_CREATED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[?(@.subjectLabel == '%s')]", mission).isEmpty());

    MvcResult created =
        mvc.perform(
                post("/api/v1/missions")
                    .with(planner)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(draft.formatted(mission, "")))
            .andExpect(status().isOk())
            .andReturn();
    String missionId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    mvc.perform(get("/api/v1/missions/{id}", missionId).with(planner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value(mission))
        .andExpect(jsonPath("$.objectives[0].title").value("Secure site"))
        .andExpect(jsonPath("$.objectives[1].kind").value("PRIMARY"))
        .andExpect(jsonPath("$.steps[0].title").value("Brief"))
        .andExpect(jsonPath("$.steps[0].orderIndex").value(0))
        .andExpect(jsonPath("$.steps[0].done").value(false))
        .andExpect(jsonPath("$.steps[1].title").value("Launch"))
        .andExpect(jsonPath("$.assignedUnits[0].id").isNotEmpty())
        .andExpect(jsonPath("$.assignedUnits[0].name").value("Scout"))
        .andExpect(jsonPath("$.assignedUnits[0].note").value("Check landing zone"))
        .andExpect(jsonPath("$.assignedUnits[0].highValueUnit").value(true))
        .andExpect(jsonPath("$.assignedUnits[0].frequency").value(127.46));
    expectAuditCount(missionId, "MISSION_CREATED", 1);
    expectAuditCount(missionId, "MISSION_OBJECTIVE_ADDED", 2);
    expectAuditCount(missionId, "MISSION_STEP_ADDED", 2);
    expectAuditCount(missionId, "MISSION_UNIT_ADDED", 1);
  }

  @Test
  void operationLinkedMissionUsesMissionOwnerAndKeepsItsOwnRowsAfterTemplateDeletion()
      throws Exception {
    UUID missionOwner = createOwner();
    UUID operationOwner = createOwner();
    JwtRequestPostProcessor caller =
        planner(missionOwner).authorities(new SimpleGrantedAuthority(Roles.authority(Roles.ADMIN)));
    MvcResult operation =
        mvc.perform(
                post("/api/v1/operations")
                    .with(caller)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"name":"Recovery operation","status":"PLANNED","owningOrgUnitId":"%s"}
                        """
                            .formatted(operationOwner)))
            .andExpect(status().isOk())
            .andReturn();
    String operationId = JsonPath.read(operation.getResponse().getContentAsString(), "$.id");

    MvcResult foreign = create(operationOwner, "Operation template");
    MvcResult saved = create(missionOwner, "Mission template");
    String id = JsonPath.read(saved.getResponse().getContentAsString(), "$.id");
    Number version = JsonPath.read(saved.getResponse().getContentAsString(), "$.version");
    String foreignId = JsonPath.read(foreign.getResponse().getContentAsString(), "$.id");
    Number foreignVersion = JsonPath.read(foreign.getResponse().getContentAsString(), "$.version");
    String draft =
        """
        {"name":"Linked mission","status":"PLANNED","operationId":"%s",
         "units":[{"name":"Edited scout"}],"templateSelection":{"id":"%s","version":%s}}
        """;
    expectStaleMission(caller, draft.formatted(operationId, foreignId, foreignVersion));

    MvcResult created =
        mvc.perform(
                post("/api/v1/missions")
                    .with(caller)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(draft.formatted(operationId, id, version)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.operation.id").value(operationId))
            .andExpect(jsonPath("$.owningSquadron.id").value(missionOwner.toString()))
            .andReturn();
    String missionId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    mvc.perform(delete(PATH + "/{id}", id).with(admin()).param("version", version.toString()))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/missions/{id}", missionId).with(caller))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assignedUnits[0].name").value("Edited scout"));
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void missionSubmitWaitsForTheTemplateEditorAndRejectsItsOldRevision() throws Exception {
    UUID owner = createOwner();
    try {
      MvcResult saved = create(owner, "Concurrent template");
      String id = JsonPath.read(saved.getResponse().getContentAsString(), "$.id");
      Number version = JsonPath.read(saved.getResponse().getContentAsString(), "$.version");
      CountDownLatch templateEdited = new CountDownLatch(1);
      CountDownLatch allowCommit = new CountDownLatch(1);
      CountDownLatch submitting = new CountDownLatch(1);
      try (var executor = Executors.newFixedThreadPool(2)) {
        var editor =
            executor.submit(() ->
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(_ -> {
                      try {
                        mvc.perform(
                                put(PATH + "/{id}", id)
                                    .with(admin())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                        """
                                        {"version":%s,"name":"Concurrent template","objectives":[],
                                         "steps":[{"title":"Updated step"}],"units":[]}
                                        """
                                            .formatted(version)))
                            .andExpect(status().isOk());
                        templateEdited.countDown();
                        assertThat(allowCommit.await(10, TimeUnit.SECONDS)).isTrue();
                      } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                      } catch (Exception failure) {
                        throw new AssertionError(failure);
                      }
                    }));
        try {
          assertThat(templateEdited.await(10, TimeUnit.SECONDS)).isTrue();
          var submit =
              executor.submit(() -> {
                submitting.countDown();
                return mvc.perform(
                        post("/api/v1/missions")
                            .with(admin().jwt(token -> token.subject(UUID.randomUUID().toString())))
                            .header("X-Active-Org-Unit-Id", owner)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                """
                                {"name":"Racing mission","status":"PLANNED","isInternal":true,
                                 "templateSelection":{"id":"%s","version":%s}}
                                """
                                    .formatted(id, version)))
                    .andReturn();
              });
          assertThat(submitting.await(10, TimeUnit.SECONDS)).isTrue();
          assertThatThrownBy(() -> submit.get(1, TimeUnit.SECONDS))
              .isInstanceOf(TimeoutException.class);
          allowCommit.countDown();
          editor.get(10, TimeUnit.SECONDS);
          MvcResult refused = submit.get(10, TimeUnit.SECONDS);
          assertThat(refused.getResponse().getStatus()).isEqualTo(409);
          assertThat(JsonPath.<String>read(refused.getResponse().getContentAsString(), "$.code"))
              .isEqualTo("MISSION_TEMPLATE_STALE");
        } finally {
          allowCommit.countDown();
        }
      }
    } finally {
      deleteFixtures(owner);
    }
  }

  private void expectStaleMission(JwtRequestPostProcessor caller, String draft) throws Exception {
    mvc.perform(
            post("/api/v1/missions")
                .with(caller)
                .contentType(MediaType.APPLICATION_JSON)
                .content(draft))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("MISSION_TEMPLATE_STALE"));
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
    List<String> ids = JsonPath.read(list.getResponse().getContentAsString(), "$.content[*].id");
    List<Number> versions =
        JsonPath.read(list.getResponse().getContentAsString(), "$.content[*].version");
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

  private JwtRequestPostProcessor planner(UUID owner) {
    User planner = owner == null ? createPlanner() : createPlanner(owner);
    return member().jwt(token -> token.subject(planner.getId().toString()));
  }

  private User createPlanner(UUID... owners) {
    User planner = new User();
    planner.setId(UUID.randomUUID());
    planner.setUsername("template-planner-" + planner.getId());
    planner.setApprovalStatus(ApprovalStatus.ACTIVE);
    planner.setInKeycloak(true);
    planner.getRoles().add(roles.findByCode(Roles.KRT_MEMBER).orElseThrow());
    users.saveAndFlush(planner);

    for (UUID owner : owners) {
      OrgUnitMembership membership = new OrgUnitMembership();
      membership.setId(new OrgUnitMembershipId(planner.getId(), owner));
      membership.setUser(planner);
      membership.setRole(MembershipRole.MEMBER);
      membership.setJoinedAt(Instant.now());
      memberships.saveAndFlush(membership);
    }
    return planner;
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
