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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Blueprint;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * A P4K APPLY stores every matched blueprint's produced-item GUID and moves the personal blueprints
 * from their name keys onto it (REQ-P4K-029, REQ-INV-070, ADR-0343).
 */
@SpringBootTest
@Transactional
class PersonalBlueprintKeyBackfillTest {

  private static final UUID LIGHT_BLUEPRINT =
      UUID.fromString("0037f308-adfc-4c4e-994f-cd477e895aab");
  private static final UUID LIGHT_ITEM = UUID.fromString("de20695c-eb2f-4b68-be6f-9806cbd205a7");
  private static final UUID HEAVY_BLUEPRINT =
      UUID.fromString("005d95db-96ca-45b7-9647-7e7537b8fac8");
  private static final UUID HEAVY_ITEM = UUID.fromString("bef9cd69-24f0-434a-ba7a-2bf751228093");
  private static final String LIGHT = "backfill light arms";
  private static final String HEAVY = "backfill heavy arms";
  private static final String LONELY = "backfill lonely helmet";
  private static final Instant EARLY = Instant.parse("2026-01-02T10:00:00Z");
  private static final Instant LATE = Instant.parse("2026-03-01T10:00:00Z");

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private BlueprintRepository blueprintRepository;
  @Autowired private GameItemRepository gameItemRepository;
  @Autowired private P4kReviewCaseRepository caseRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private JdbcTemplate jdbc;

  private P4kImportRuns runs;
  private UUID memberA;
  private UUID memberB;
  private Blueprint wikiHeavy;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(service, jobRepository);
    memberA = member();
    memberB = member();
    blueprint(LIGHT_BLUEPRINT, "Backfill Light Arms");
    blueprint(HEAVY_BLUEPRINT, "Backfill Heavy Arms");
    wikiHeavy = blueprint(null, "Backfill Heavy Arms");
    blueprint(null, "Backfill Lonely Helmet");
  }

  @Test
  void aMatchedBlueprintStoresItsProducedItemGuidAlsoWithoutAGameItem() {
    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    assertThat(blueprintRepository.findByP4kUuid(LIGHT_BLUEPRINT))
        .get()
        .extracting(Blueprint::getProducedItemGuid)
        .isEqualTo(LIGHT_ITEM);
    assertThat(gameItemRepository.findByP4kUuid(LIGHT_ITEM)).isEmpty();
  }

  @Test
  void theBackfillMovesEveryHolderOfAUniqueNameAndMergesAMemberWhoHoldsBoth() {
    owned(memberA, LIGHT, EARLY, "fallback note");
    owned(memberA, LIGHT_ITEM.toString(), LATE, null);
    owned(memberB, LIGHT, LATE, null);
    jdbc.update(
        "INSERT INTO default_blueprint (id, product_key, product_name) VALUES (?, ?, 'Light')",
        UUID.randomUUID(),
        LIGHT);
    jdbc.update(
        "INSERT INTO blueprint_external_alias (id, source_system, external_name, product_key,"
            + " product_name) VALUES (?, 'SCMDB', 'Backfill Light Alias', ?, 'Light')",
        UUID.randomUUID(),
        LIGHT);
    long earlierChange =
        jdbc.queryForObject(
            "INSERT INTO exchange_change (user_id, resource, entity_key, source_channel)"
                + " VALUES (?, 'BLUEPRINT', ?, 'client') RETURNING seq",
            Long.class,
            memberB,
            LIGHT);

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    assertThat(rows(memberA))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.get("product_key")).isEqualTo(LIGHT_ITEM.toString());
          assertThat(((Timestamp) row.get("acquired_at")).toInstant()).isEqualTo(EARLY);
          assertThat(row.get("note")).isEqualTo("fallback note");
        });
    assertThat(rows(memberB))
        .extracting(row -> row.get("product_key"))
        .containsExactly(LIGHT_ITEM.toString());
    assertThat(keys("SELECT product_key FROM default_blueprint WHERE product_name = 'Light'"))
        .containsExactly(LIGHT_ITEM.toString());
    assertThat(
            keys(
                "SELECT product_key FROM blueprint_external_alias"
                    + " WHERE external_name = 'Backfill Light Alias'"))
        .containsExactly(LIGHT_ITEM.toString());
    assertThat(
            jdbc.queryForObject(
                "SELECT entity_key FROM exchange_change WHERE seq = ?",
                String.class,
                earlierChange))
        .isEqualTo(LIGHT_ITEM.toString());
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM exchange_change WHERE user_id = ? AND entity_key = ?"
                    + " AND seq > ?",
                Long.class,
                memberB,
                LIGHT,
                earlierChange))
        .isPositive();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE event_type = 'BLUEPRINT_PRODUCT_MERGED'"
                    + " AND target_user_id = ?",
                Long.class,
                memberA))
        .isOne();
  }

  @Test
  void materialboerseOffersAndRequestsMoveWithTheKeyAlsoWhenTheMemberRowsMerge() {
    owned(memberA, LIGHT, EARLY, null);
    owned(memberA, LIGHT_ITEM.toString(), LATE, null);
    owned(memberB, LIGHT, LATE, null);
    UUID offerA = itemOffer(memberA, LIGHT);
    UUID offerB = itemOffer(memberB, LIGHT);
    UUID requestA = itemRequest(memberA, LIGHT);
    UUID heavyRequest = itemRequest(memberB, HEAVY);

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    for (UUID offer : List.of(offerA, offerB)) {
      assertThat(
              jdbc.queryForMap(
                  "SELECT item_product_key, version FROM material_exchange_offer WHERE id = ?",
                  offer))
          .containsEntry("item_product_key", LIGHT_ITEM.toString())
          .containsEntry("version", 1L);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT item_product_key FROM material_exchange_request WHERE id = ?",
                String.class,
                requestA))
        .isEqualTo(LIGHT_ITEM.toString());
    assertThat(
            jdbc.queryForObject(
                "SELECT item_product_key FROM material_exchange_request WHERE id = ?",
                String.class,
                heavyRequest))
        .isEqualTo(HEAVY);
    assertThat(rows(memberA))
        .extracting(row -> row.get("product_key"))
        .containsExactly(LIGHT_ITEM.toString());
  }

  @Test
  void aNameOfNoOrSeveralGuidsKeepsItsKeyWithAReviewCaseThatALaterRunCloses() {
    owned(memberB, HEAVY, null, null);
    owned(memberB, LONELY, null, null);

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    assertThat(rows(memberB))
        .extracting(row -> row.get("product_key"))
        .containsExactlyInAnyOrder(HEAVY, LONELY);
    assertThat(openCase(HEAVY, P4kReviewReason.PRODUCT_KEY_AMBIGUOUS)).isTrue();
    assertThat(openCase(LONELY, P4kReviewReason.PRODUCT_KEY_UNRESOLVED)).isTrue();

    wikiHeavy.setScwikiDeletedAt(Instant.now());
    blueprintRepository.saveAndFlush(wikiHeavy);
    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    assertThat(rows(memberB))
        .extracting(row -> row.get("product_key"))
        .containsExactlyInAnyOrder(HEAVY_ITEM.toString(), LONELY);
    assertThat(
            caseRepository.findByKindAndGuidAndReason(
                P4kKind.BLUEPRINT,
                P4kProductKeyReview.caseGuid(HEAVY),
                P4kReviewReason.PRODUCT_KEY_AMBIGUOUS))
        .get()
        .satisfies(c -> {
          assertThat(c.getStatus()).isEqualTo(P4kReviewStatus.REVIEWED);
          assertThat(c.getResolution()).isEqualTo(P4kReviewResolution.RESOLVED);
        });
    assertThat(openCase(LONELY, P4kReviewReason.PRODUCT_KEY_UNRESOLVED)).isTrue();
  }

  /**
   * Whether the review list holds an open case for a name key.
   *
   * @param nameKey the name key
   * @param reason the reason
   * @return {@code true} when the case exists and is open
   */
  private boolean openCase(String nameKey, P4kReviewReason reason) {
    return caseRepository
        .findByKindAndGuidAndReason(
            P4kKind.BLUEPRINT, P4kProductKeyReview.caseGuid(nameKey), reason)
        .map(c -> c.getStatus() == P4kReviewStatus.OPEN)
        .orElse(false);
  }

  /**
   * Creates a member.
   *
   * @return the member's id
   */
  private UUID member() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("bp-guid-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    user.setRoles(new HashSet<>(Set.of(roleRepository.findByCode(Roles.KRT_MEMBER).orElseThrow())));
    return userRepository.saveAndFlush(user).getId();
  }

  /**
   * Creates an active blueprint.
   *
   * @param p4kUuid the P4K GUID, or {@code null} for a Wiki-only blueprint
   * @param outputName the output name
   * @return the blueprint
   */
  private Blueprint blueprint(UUID p4kUuid, String outputName) {
    Blueprint blueprint = new Blueprint();
    blueprint.setScwikiUuid(UUID.randomUUID());
    blueprint.setScwikiKey("BP_GUID_TEST_" + UUID.randomUUID());
    blueprint.setOutputName(outputName);
    blueprint.setP4kUuid(p4kUuid);
    return blueprintRepository.saveAndFlush(blueprint);
  }

  /**
   * Gives a member a blueprint row.
   *
   * @param owner the member
   * @param productKey the key
   * @param acquiredAt the acquisition date, or {@code null}
   * @param note the note, or {@code null}
   */
  private void owned(UUID owner, String productKey, Instant acquiredAt, String note) {
    jdbc.update(
        "INSERT INTO personal_blueprint (id, owner_user_id, product_key, product_name,"
            + " acquired_at, note) VALUES (?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        owner,
        productKey,
        productKey,
        acquiredAt == null ? null : Timestamp.from(acquiredAt),
        note);
  }

  /**
   * Posts a free-stated item offer on the Materialbörse.
   *
   * @param owner the member
   * @param productKey the product key
   * @return the offer id
   */
  private UUID itemOffer(UUID owner, String productKey) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO material_exchange_offer (id, owner_id, status, released_at, created_at,"
            + " updated_at, offer_kind, item_product_key, item_name, item_quantity)"
            + " VALUES (?, ?, 'ACTIVE', now(), now(), now(), 'ITEM', ?, 'Arms', 1)",
        id,
        owner,
        productKey);
    return id;
  }

  /**
   * Posts an item request on the Materialbörse.
   *
   * @param owner the member
   * @param productKey the product key
   * @return the request id
   */
  private UUID itemRequest(UUID owner, String productKey) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO material_exchange_request (id, request_kind, item_product_key, item_name,"
            + " item_quantity, owner_id, status, posted_at, created_at, updated_at)"
            + " VALUES (?, 'ITEM', ?, 'Arms', 1, ?, 'ACTIVE', now(), now(), now())",
        id,
        productKey,
        owner);
    return id;
  }

  /**
   * Reads a member's blueprint rows.
   *
   * @param owner the member
   * @return the rows
   */
  private List<Map<String, Object>> rows(UUID owner) {
    return jdbc.queryForList(
        "SELECT product_key, acquired_at, note FROM personal_blueprint WHERE owner_user_id = ?",
        owner);
  }

  /**
   * Reads one column of keys.
   *
   * @param sql the query
   * @return the keys
   */
  private List<String> keys(String sql) {
    return jdbc.queryForList(sql, String.class);
  }
}
