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

import static de.greluc.krt.profit.basetool.backend.support.JobOrderParticipationFixtures.persistedParticipation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTier;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialClaim;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderParticipationRepository;
import de.greluc.krt.profit.basetool.backend.joborder.internal.MaterialClaimRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Bereich;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyFixtures;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import de.greluc.krt.profit.basetool.backend.privacy.api.DataExportService;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/** Exercises committed hierarchy maintenance and its current-writer notification audience. */
@SpringBootTest
class ProfitHierarchyNoticeIntegrationTest {

  private static final String NOTICE_TYPE = "JOB_ORDER_CLAIM_WITHDRAWN";
  private static final Set<Audience> RECIPIENTS =
      Set.of(
          Audience.AFFECTED_WRITER,
          Audience.RESPONSIBLE_WRITER,
          Audience.AREA_WRITER,
          Audience.AREA_LEADER,
          Audience.AFFECTED_LEADER,
          Audience.PREFERENCE_WRITER);

  @Autowired private JobOrderParticipationRepository participations;

  @Autowired private WebApplicationContext context;
  @Autowired private TransactionTemplate transactions;
  @Autowired private OrgUnitRepository units;
  @Autowired private OrgUnitMembershipRepository memberships;
  @Autowired private UserRepository users;
  @Autowired private RoleRepository roles;
  @Autowired private JobOrderRepository orders;
  @Autowired private MaterialRepository materials;
  @Autowired private MaterialClaimRepository claims;
  @Autowired private QualityTierRepository tiers;
  @Autowired private DataExportService exports;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private NotificationService inbox;

  private MockMvc mvc;
  private Fixture fixture;

  @BeforeEach
  void seed() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    fixture = transactions.execute(_ -> createFixture());
  }

  @AfterEach
  void cleanOwnRows() {
    if (fixture == null) {
      return;
    }
    transactions.executeWithoutResult(_ -> {
      jdbc.update("DELETE FROM notification WHERE entity_id = ?", fixture.order());
      jdbc.update("DELETE FROM material_claim WHERE job_order_id = ?", fixture.order());
      orders.deleteById(fixture.order());
      orders.flush();
      for (UUID user : fixture.people().values()) {
        jdbc.update("DELETE FROM org_unit_membership WHERE user_id = ?", user);
        users.deleteById(user);
      }
      users.flush();
      units.deleteById(fixture.claiming());
      units.deleteById(fixture.responsible());
      units.flush();
      units.deleteById(fixture.outside());
      materials.deleteAllById(fixture.materials());
    });
  }

  @Test
  void oneHierarchyChangeGroupsEndedPromisesForCurrentWritersAndPreservesHistory()
      throws Exception {
    List<Map<String, Object>> history = claimHistory();
    assertThat(history).hasSize(2);
    assertThat(history).allSatisfy(row -> assertThat(row.get("ended_at")).isNull());
    assertThat(history)
        .extracting(row -> ((Number) row.get("amount")).doubleValue())
        .containsExactlyInAnyOrder(4.0, 7.0);
    assertActivePromises(4.0, 7.0);
    as(Audience.RECORDER, get("/api/v1/orders/" + fixture.order()), null)
        .andExpect(status().isOk());
    as(Audience.ADMIN, get("/api/v1/orders/" + fixture.order()), null).andExpect(status().isOk());

    moveClaimingUnit(fixture.outside());

    assertActivePromises(0.0, 0.0);
    List<Map<String, Object>> endedHistory = claimHistory();
    assertThat(endedHistory)
        .allSatisfy(row -> {
          assertThat(row.get("ended_at")).isNotNull();
          assertThat(row.get("amount")).isEqualTo(0.0);
          List<Map<String, Object>> changes = JsonPath.read(row.get("changes").toString(), "$");
          assertThat(changes)
              .singleElement()
              .satisfies(change -> {
                assertThat(change)
                    .containsEntry("reason", "UNIT_INELIGIBLE")
                    .containsEntry("amount", 0.0)
                    .containsEntry("creditDelta", 0.0);
                assertThat(((Number) change.get("previousAmount")).doubleValue())
                    .isEqualTo(row.get("material").equals("Notice copper") ? 4.0 : 7.0);
              });
        });
    assertThat(stableContributionFacts(endedHistory))
        .containsExactlyInAnyOrderElementsOf(stableContributionFacts(history));
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> {
          for (Audience recipient : RECIPIENTS) {
            assertGroupedNotice(recipient);
          }
        });
    assertExcludedAudiences();
    as(Audience.RESPONSIBLE_WRITER, get("/api/v1/orders/" + fixture.order()), fixture.responsible())
        .andExpect(status().isOk());
    as(Audience.AFFECTED_LEADER, get("/api/v1/orders/" + fixture.order()), fixture.claiming())
        .andExpect(status().isOk());
    String readableHistory =
        as(
                Audience.AFFECTED_LEADER,
                get("/api/v1/orders/" + fixture.order() + "/claims/history"),
                fixture.claiming())
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String endedClaim = JsonPath.read(readableHistory, "$[0].id");
    Number endedVersion = JsonPath.read(readableHistory, "$[0].version");
    as(
            Audience.AFFECTED_LEADER,
            delete("/api/v1/orders/" + fixture.order() + "/claims/" + endedClaim)
                .param("version", endedVersion.toString()),
            fixture.claiming())
        .andExpect(status().isForbidden());

    moveClaimingUnit(fixture.profit());

    assertActivePromises(0.0, 0.0);
    assertThat(claimHistory()).containsExactlyInAnyOrderElementsOf(endedHistory);
    await()
        .during(Duration.ofMillis(400))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> {
          for (Audience recipient : RECIPIENTS) {
            assertGroupedNotice(recipient);
          }
          assertExcludedAudiences();
        });
  }

  @Test
  void startupGroupsOneOrderAcrossMoreThanOneClaimingUnitPage() throws Exception {
    List<UUID> extraUnits =
        transactions.execute(_ -> {
          jdbc.update(
              "UPDATE job_order_material SET amount = 1000 WHERE job_order_id = ?",
              fixture.order());
          List<Squadron> legacyUnits = new java.util.ArrayList<>();
          for (int index = 0; index < 200; index++) {
            Squadron unit = new Squadron();
            String label = "Startup legacy " + UUID.randomUUID();
            unit.setName(label);
            unit.setShorthand(label);
            legacyUnits.add(unit);
          }
          List<Squadron> saved = units.saveAllAndFlush(legacyUnits);
          var order = orders.getReferenceById(fixture.order());
          var material = materials.getReferenceById(fixture.materials().getFirst());
          var quality = tiers.findByCode("GOOD").orElseThrow();
          var recorder = users.getReferenceById(fixture.people().get(Audience.RECORDER));
          claims.saveAllAndFlush(
              saved.stream()
                  .map(unit ->
                      MaterialClaim.builder()
                          .jobOrder(order)
                          .material(material)
                          .qualityTier(quality)
                          .claimingOrgUnit(unit)
                          .participation(persistedParticipation(participations, order, unit))
                          .amount(1.0)
                          .claimedByUser(recorder)
                          .build())
                  .toList());
          jdbc.update(
              "UPDATE org_unit SET parent_org_unit_id = ? WHERE id = ?",
              fixture.outside(),
              fixture.claiming());
          return saved.stream().map(OrgUnit::getId).toList();
        });
    try {
      context
          .getBean("jobOrderEligibilityStartup", ApplicationRunner.class)
          .run(new DefaultApplicationArguments());

      assertActivePromises(0.0, 0.0);
      await()
          .during(Duration.ofMillis(600))
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> assertGroupedNotice(Audience.RESPONSIBLE_WRITER));
    } finally {
      transactions.executeWithoutResult(_ -> {
        for (UUID unit : extraUnits) {
          jdbc.update("DELETE FROM material_claim WHERE claiming_org_unit_id = ?", unit);
          jdbc.update("DELETE FROM job_order_participation WHERE claiming_org_unit_id = ?", unit);
        }
        units.deleteAllById(extraUnits);
      });
    }
  }

  @Test
  void hierarchyActorReceivesNoDuplicateNoticeDespiteAnAffectedUnitWriteGrant() throws Exception {
    String observed =
        as(Audience.ACTOR, get("/api/v1/orders/" + fixture.order() + "/claims"), fixture.claiming())
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<Number> versions =
        JsonPath.read(observed, "$[?(@.material.name == 'Notice copper')].claims[*].version");
    as(
            Audience.ACTOR,
            post("/api/v1/orders/" + fixture.order() + "/claims")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"materialId\":\""
                        + fixture.materials().getFirst()
                        + "\",\"qualityRequirement\":\"GOOD\",\"claimingOrgUnitId\":\""
                        + fixture.claiming()
                        + "\",\"amount\":4.0,\"version\":"
                        + versions.getFirst()
                        + "}"),
            fixture.claiming())
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.amount").value(4.0));

    moveClaimingUnit(fixture.outside());

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertGroupedNotice(Audience.RESPONSIBLE_WRITER));
    await()
        .during(Duration.ofMillis(400))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(notices(Audience.ACTOR)).isEmpty());
  }

  @Test
  void automaticGroupedNoticesRespectTheExistingTypeMuteWithoutRetrospectiveDelivery()
      throws Exception {
    setMuted(true);
    assertPreference(true);

    moveClaimingUnit(fixture.outside());

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertGroupedNotice(Audience.RESPONSIBLE_WRITER));
    await()
        .during(Duration.ofMillis(400))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(notices(Audience.PREFERENCE_WRITER)).isEmpty());
    assertPreference(true);

    setMuted(false);

    assertPreference(false);
    assertThat(notices(Audience.PREFERENCE_WRITER)).isEmpty();
  }

  @Test
  void anEmptySupportEndingUsesExistingMuteAndCurrentWritersWithoutInventingMaterials()
      throws Exception {
    String current =
        as(
                Audience.ACTOR,
                get("/api/v1/orders/" + fixture.order() + "/claims/history"),
                fixture.claiming())
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<Map<String, Object>> observed = JsonPath.read(current, "$");
    for (Map<String, Object> contribution : observed) {
      as(
              Audience.ACTOR,
              delete("/api/v1/orders/" + fixture.order() + "/claims/" + contribution.get("id"))
                  .param("version", contribution.get("version").toString()),
              fixture.claiming())
          .andExpect(status().isNoContent());
    }
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() ->
            assertThat(orderNotices(Audience.AFFECTED_WRITER))
                .hasSize(2)
                .allSatisfy(notice ->
                    assertThat(notice.get("type"))
                        .isEqualTo("JOB_ORDER_MATERIAL_CONTRIBUTIONS_CHANGED")));
    assertThat(orderNotices(Audience.RESPONSIBLE_WRITER)).hasSize(2);
    assertExcludedAudiences();
    for (Audience audience : RECIPIENTS) {
      UUID person = fixture.people().get(audience);
      inbox.markAllRead(person);
      inbox.deleteAllRead(person);
    }
    setMuted(true);

    moveClaimingUnit(fixture.outside());

    Set<Audience> receiving =
        Set.of(
            Audience.AFFECTED_WRITER,
            Audience.RESPONSIBLE_WRITER,
            Audience.AREA_WRITER,
            Audience.AREA_LEADER,
            Audience.AFFECTED_LEADER);
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> {
          for (Audience recipient : receiving) {
            assertThat(orderNotices(recipient))
                .singleElement()
                .satisfies(notice -> {
                  assertThat(notice.get("type")).isEqualTo(NOTICE_TYPE);
                  Map<String, Object> params = JsonPath.read(notice, "$.params");
                  assertThat(params)
                      .containsEntry("material", null)
                      .containsEntry("reasonCode", "INELIGIBLE_UNIT");
                });
          }
        });
    await()
        .during(Duration.ofMillis(400))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(orderNotices(Audience.PREFERENCE_WRITER)).isEmpty());
    assertPreference(true);
    assertExcludedAudiences();

    moveClaimingUnit(fixture.outside());

    for (Audience recipient : receiving) {
      assertThat(orderNotices(recipient)).hasSize(1);
    }
    assertThat(orderNotices(Audience.PREFERENCE_WRITER)).isEmpty();
  }

  private void assertPreference(boolean muted) throws Exception {
    String json =
        as(Audience.PREFERENCE_WRITER, get("/api/v1/notifications/preferences"), null)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<Map<String, Object>> preferences = JsonPath.read(json, "$");
    assertThat(preferences.stream().filter(row -> NOTICE_TYPE.equals(row.get("type"))).toList())
        .singleElement()
        .satisfies(
            row -> assertThat(row).containsEntry("mutable", true).containsEntry("muted", muted));
  }

  private void assertGroupedNotice(Audience recipient) throws Exception {
    List<Map<String, Object>> inbox = notices(recipient);
    assertThat(inbox).hasSize(1);
    assertThat(inbox.getFirst())
        .containsEntry("entityType", "JOB_ORDER")
        .containsEntry("entityId", fixture.order().toString());
    Object params = inbox.getFirst().get("params");
    assertThat(params).isInstanceOf(Map.class);
    Map<?, ?> render = (Map<?, ?>) params;
    assertThat(render.get("reasonCode")).isEqualTo("INELIGIBLE_UNIT");
    assertThat(render.get("material")).isEqualTo("Notice copper, Notice titanium");
  }

  private void assertExcludedAudiences() throws Exception {
    for (Audience recipient : Audience.values()) {
      if (!RECIPIENTS.contains(recipient)) {
        assertThat(orderNotices(recipient)).as("inbox of %s", recipient).isEmpty();
      }
    }
  }

  private void assertActivePromises(double copper, double titanium) throws Exception {
    String json =
        as(
                Audience.RESPONSIBLE_WRITER,
                get("/api/v1/orders/" + fixture.order() + "/claims"),
                fixture.responsible())
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<Map<String, Object>> buckets = JsonPath.read(json, "$");
    assertThat(buckets).as("claim buckets: %s", json).hasSize(2);
    assertThat(
            JsonPath.<List<Double>>read(
                json, "$[?(@.material.name == 'Notice copper')].claimedAmount"))
        .as("copper claim in %s", json)
        .containsExactly(copper);
    assertThat(
            JsonPath.<List<Double>>read(
                json, "$[?(@.material.name == 'Notice titanium')].claimedAmount"))
        .as("titanium claim in %s", json)
        .containsExactly(titanium);
  }

  private static List<Map<String, Object>> stableContributionFacts(List<Map<String, Object>> rows) {
    return rows.stream()
        .map(row -> {
          Map<String, Object> facts = new java.util.HashMap<>(row);
          facts.remove("ended_at");
          facts.remove("amount");
          facts.remove("changes");
          return facts;
        })
        .toList();
  }

  private List<Map<String, Object>> claimHistory() {
    return exports.export(fixture.people().get(Audience.RECORDER)).sections().stream()
        .filter(section -> section.key().equals("materialClaims"))
        .findFirst()
        .orElseThrow()
        .rows();
  }

  private void moveClaimingUnit(UUID parent) throws Exception {
    Long version = units.findById(fixture.claiming()).orElseThrow().getVersion();
    mvc.perform(
            patch("/api/v1/org-units/" + fixture.claiming() + "/parent")
                .with(
                    jwt()
                        .jwt(
                            token -> token.subject(fixture.people().get(Audience.ACTOR).toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"parentOrgUnitId\":\"" + parent + "\",\"version\":" + version + "}"))
        .andExpect(status().isOk());
  }

  private void setMuted(boolean muted) throws Exception {
    as(
            Audience.PREFERENCE_WRITER,
            put("/api/v1/notifications/preferences/" + NOTICE_TYPE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"muted\":" + muted + "}"),
            null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.muted").value(muted));
  }

  private List<Map<String, Object>> notices(Audience recipient) throws Exception {
    return orderNotices(recipient).stream()
        .filter(notice -> NOTICE_TYPE.equals(notice.get("type")))
        .toList();
  }

  private List<Map<String, Object>> orderNotices(Audience recipient) throws Exception {
    String json =
        as(recipient, get("/api/v1/notifications").param("size", "100"), null)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(json, "$.content[?(@.entityId == '" + fixture.order() + "')]");
  }

  private ResultActions as(Audience caller, MockHttpServletRequestBuilder request, UUID grant)
      throws Exception {
    var token = jwt().jwt(jwt -> jwt.subject(fixture.people().get(caller).toString()));
    if (grant == null) {
      token.authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
    } else {
      token.authorities(
          new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
          new SimpleGrantedAuthority("ROLE_LOGISTICIAN"),
          new OrgUnitContextualAuthority(Roles.LOGISTICIAN, grant));
    }
    return mvc.perform(request.with(token).accept(MediaType.APPLICATION_JSON));
  }

  private Fixture createFixture() {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    OrgUnit profit = ProfitHierarchyFixtures.persistedProfitArea(units);
    Bereich outside = new Bereich();
    outside.setName("Notice outside " + tag);
    outside.setShorthand("NO" + tag);
    outside = units.saveAndFlush(outside);
    Squadron claiming = new Squadron();
    claiming.setName("Notice claiming " + tag);
    claiming.setShorthand("NC" + tag);
    claiming.setParent(profit);
    claiming = units.saveAndFlush(claiming);
    SpecialCommand responsible = new SpecialCommand();
    responsible.setName("Notice responsible " + tag);
    responsible.setShorthand("NR" + tag);
    responsible.setParent(profit);
    responsible = units.saveAndFlush(responsible);
    Map<Audience, UUID> people = new EnumMap<>(Audience.class);
    for (Audience audience : Audience.values()) {
      people.put(audience, createUser(audience, tag).getId());
    }
    for (Audience audience :
        List.of(
            Audience.ACTOR,
            Audience.AFFECTED_WRITER,
            Audience.PREFERENCE_WRITER,
            Audience.DISABLED_WRITER)) {
      join(people.get(audience), claiming, true, MembershipRole.MEMBER);
    }
    join(people.get(Audience.AFFECTED_WRITER), responsible, true, MembershipRole.MEMBER);
    join(people.get(Audience.RESPONSIBLE_WRITER), responsible, true, MembershipRole.MEMBER);
    join(people.get(Audience.AREA_WRITER), outside, true, MembershipRole.MEMBER);
    join(people.get(Audience.AREA_LEADER), outside, false, MembershipRole.BEREICHSOPERATOR);
    join(people.get(Audience.AFFECTED_LEADER), claiming, false, MembershipRole.STAFFELLEITER);
    join(people.get(Audience.NONMEMBER_LEADER), claiming, false, MembershipRole.ENSIGN);
    join(people.get(Audience.RECORDER), claiming, false, MembershipRole.MEMBER);
    join(people.get(Audience.RECORDER), responsible, false, MembershipRole.MEMBER);
    join(people.get(Audience.ADMIN), responsible, false, MembershipRole.MEMBER);
    join(people.get(Audience.MEMBER), claiming, false, MembershipRole.MEMBER);
    QualityTier good = tiers.findByCode("GOOD").orElseThrow();
    Material copper = material("Notice copper");
    Material titanium = material("Notice titanium");
    JobOrder order =
        JobOrder.builder()
            .responsibleOrgUnit(responsible)
            .requestingOrgUnit(responsible)
            .handle("Notice fixture")
            .build();
    order.addMaterial(
        JobOrderMaterial.builder().material(copper).qualityTier(good).amount(10.0).build());
    order.addMaterial(
        JobOrderMaterial.builder().material(titanium).qualityTier(good).amount(20.0).build());
    order = orders.saveAndFlush(order);
    User recorder = users.getReferenceById(people.get(Audience.RECORDER));
    claims.saveAllAndFlush(
        List.of(
            MaterialClaim.builder()
                .jobOrder(order)
                .material(copper)
                .qualityTier(good)
                .claimingOrgUnit(claiming)
                .participation(persistedParticipation(participations, order, claiming))
                .amount(4.0)
                .claimedByUser(recorder)
                .build(),
            MaterialClaim.builder()
                .jobOrder(order)
                .material(titanium)
                .qualityTier(good)
                .claimingOrgUnit(claiming)
                .participation(persistedParticipation(participations, order, claiming))
                .amount(7.0)
                .claimedByUser(recorder)
                .build()));
    return new Fixture(
        profit.getId(),
        outside.getId(),
        claiming.getId(),
        responsible.getId(),
        order.getId(),
        List.of(copper.getId(), titanium.getId()),
        Map.copyOf(people));
  }

  private User createUser(Audience audience, String tag) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("notice-" + audience.name().toLowerCase(java.util.Locale.ROOT) + "-" + tag);
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    user.setEnabledInKeycloak(audience != Audience.DISABLED_WRITER);
    if (audience == Audience.ADMIN) {
      user.getRoles().add(roles.findByCode("ADMIN").orElseThrow());
    }
    if (!Set.of(Audience.AREA_WRITER, Audience.NONMEMBER_LEADER).contains(audience)) {
      user.getRoles().add(roles.findByCode("KRT_MEMBER").orElseThrow());
    }
    return users.saveAndFlush(user);
  }

  private void join(UUID person, OrgUnit unit, boolean logistician, MembershipRole role) {
    OrgUnitMembership row = new OrgUnitMembership();
    row.setId(new OrgUnitMembershipId(person, unit.getId()));
    row.setUser(users.getReferenceById(person));
    row.setLogistician(logistician);
    row.setRole(role);
    row.setJoinedAt(Instant.now());
    memberships.saveAndFlush(row);
  }

  private Material material(String name) {
    Material row = new Material();
    row.setName(name);
    row.setType(MaterialType.RAW);
    return materials.saveAndFlush(row);
  }

  /** Functional relationships considered independently for the directed notice audience. */
  private enum Audience {
    ACTOR,
    AFFECTED_WRITER,
    RESPONSIBLE_WRITER,
    AREA_WRITER,
    AREA_LEADER,
    AFFECTED_LEADER,
    PREFERENCE_WRITER,
    RECORDER,
    MEMBER,
    ADMIN,
    NONMEMBER_LEADER,
    DISABLED_WRITER
  }

  /** Committed synthetic identifiers owned by this test, excluding the shared Profit area. */
  private record Fixture(
      UUID profit,
      UUID outside,
      UUID claiming,
      UUID responsible,
      UUID order,
      List<UUID> materials,
      Map<Audience, UUID> people) {}
}
