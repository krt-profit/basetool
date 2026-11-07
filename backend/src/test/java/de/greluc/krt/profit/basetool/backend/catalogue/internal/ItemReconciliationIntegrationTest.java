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

import de.greluc.krt.profit.basetool.backend.blueprint.api.BlueprintProducts;
import de.greluc.krt.profit.basetool.backend.blueprint.api.ResolvedProduct;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintReferenceDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Exercises archived source reconciliation, every reference owner and retained ids on PostgreSQL.
 */
@SpringBootTest
@Transactional
class ItemReconciliationIntegrationTest {

  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager entityManager;
  @Autowired private GameItemRepository items;
  @Autowired private GameItemRedirectRepository redirects;
  @Autowired private ItemReconciliationService reconciliation;
  @Autowired private ObjectMapper mapper;
  @Autowired private JobOrderItemService orderItems;
  @Autowired private BlueprintProducts blueprintProducts;

  @Test
  void everyReferenceSurvivesAndBothSourceStatesRemainArchived() {
    Pair pair = pair();
    jdbc.update(
        "UPDATE game_item SET wiki_metadata = ?::jsonb, description_en = ? WHERE id = ?",
        "{\"source\":\"SC_WIKI\",\"tags\":[\"retired-evidence\"]}",
        "Retired description",
        pair.retired());
    jdbc.update(
        "UPDATE game_item SET wiki_metadata = ?::jsonb, description_en = ? WHERE id = ?",
        "{\"source\":\"SC_WIKI\",\"tags\":[\"surviving-evidence\"]}",
        "Surviving description",
        pair.canonical());
    UUID unit = unit();
    UUID member = member();
    UUID location = location();
    UUID recipe =
        insert(
            "INSERT INTO blueprint (id, scwiki_uuid, output_item_id)" + " VALUES (?, ?, ?)",
            UUID.randomUUID(),
            pair.retired());
    List<Reference> references = new ArrayList<>();
    references.add(new Reference("blueprint", "output_item_id", recipe));
    references.add(
        new Reference(
            "blueprint_ingredient",
            "game_item_id",
            insert(
                "INSERT INTO blueprint_ingredient (id, blueprint_id, order_index, kind,"
                    + " game_item_id, quantity_units) VALUES (?, ?, 0, 'ITEM', ?, 2)",
                recipe,
                pair.retired())));
    references.add(
        new Reference(
            "personal_blueprint",
            "output_item_id",
            insert(
                "INSERT INTO personal_blueprint (id, owner_user_id, product_key, product_name,"
                    + " output_item_id) VALUES (?, ?, ?, 'Source Product', ?)",
                member,
                "product-" + UUID.randomUUID(),
                pair.retired())));
    references.add(
        new Reference(
            "default_blueprint",
            "output_item_id",
            insert(
                "INSERT INTO default_blueprint (id, product_key, product_name, output_item_id)"
                    + " VALUES (?, ?, 'Source Product', ?)",
                "default-" + UUID.randomUUID(),
                pair.retired())));
    references.add(
        new Reference(
            "blueprint_external_alias",
            "output_item_id",
            insert(
                "INSERT INTO blueprint_external_alias (id, source_system, external_name,"
                    + " product_key, product_name, output_item_id)"
                    + " VALUES (?, 'SCMDB', ?, ?, 'Source Product', ?)",
                "external-" + UUID.randomUUID(),
                "alias-" + UUID.randomUUID(),
                pair.retired())));
    UUID stock =
        insert(
            "INSERT INTO inventory_item (id, user_id, game_item_id, location_id,"
                + " amount, personal, stolen, owning_org_unit_id, version)"
                + " VALUES (?, ?, ?, ?, 7, false, true, ?, 0)",
            member,
            pair.retired(),
            location,
            unit);
    references.add(new Reference("inventory_item", "game_item_id", stock));
    UUID order =
        insert(
            "INSERT INTO job_order (id, priority, created_at, updated_at, version,"
                + " status, requesting_org_unit_id, responsible_org_unit_id, type)"
                + " VALUES (?, 2, now(), now(), 0, 'OPEN', ?, ?, 'ITEM')",
            unit,
            unit);
    references.add(
        new Reference(
            "job_order_item",
            "game_item_id",
            insert(
                "INSERT INTO job_order_item (id, job_order_id, game_item_id, blueprint_id, amount,"
                    + " delivered_amount, manufactured_amount, created_at, updated_at, version)"
                    + " VALUES (?, ?, ?, ?, 4, 1, 1, now(), now(), 0)",
                order,
                pair.retired(),
                recipe)));
    UUID allocation =
        insert(
            "INSERT INTO inventory_item_job_order_allocation (id, inventory_item_id, job_order_id,"
                + " amount, delivered, version, created_at, updated_at) VALUES (?, ?, ?, 2, false,"
                + " 0, now(), now())",
            stock,
            order);
    UUID need =
        insert(
            "INSERT INTO supply_need (id, org_unit_id, game_item_id, amount,"
                + " priority, status) VALUES (?, ?, ?, 5, 'NORMAL', 'OPEN')",
            unit,
            pair.retired());
    references.add(new Reference("supply_need", "game_item_id", need));
    references.add(
        new Reference(
            "supply_donation",
            "game_item_id",
            insert(
                "INSERT INTO supply_donation (id, donor_user_id, receiving_org_unit_id,"
                    + " game_item_id, pledged_amount, status, source_inventory_item_id) VALUES (?,"
                    + " ?, ?, ?, 3, 'PLEDGED', ?)",
                member,
                unit,
                pair.retired(),
                stock)));
    UUID partner =
        insert(
            "INSERT INTO purchasing_partner (id, kind, name, channel, contact_via,"
                + " status) VALUES (?, 'ORG', 'Catalogue Test', 'OTHER', 'OTHER', 'ACTIVE')");
    references.add(
        new Reference(
            "purchasing_deal",
            "game_item_id",
            insert(
                "INSERT INTO purchasing_deal (id, need_id, raising_org_unit_id, partner_id,"
                    + " delegate_user_id, game_item_id, amount, unit_price, total, payment_model,"
                    + " risk_class, status) VALUES (?, ?, ?, ?, ?, ?, 4, 100, 400, 'ADVANCE', 'A',"
                    + " 'DRAFT')",
                need,
                unit,
                partner,
                member,
                pair.retired())));
    UUID retiredFloor = floor(unit, pair.retired(), 8, 10);
    UUID canonicalFloor = floor(unit, pair.canonical(), 5, 20);
    UUID movedFloor = floor(unit(), pair.retired(), 2, 4);
    references.add(new Reference("inventory_stock_level", "game_item_id", movedFloor));
    UUID terminal = insert("INSERT INTO terminal (id, name) VALUES (?, 'Catalogue Test Terminal')");
    price(pair.retired(), terminal, 11, 20);
    price(pair.canonical(), terminal, 22, 10);
    stat(pair.retired(), 11);
    stat(pair.canonical(), 99);
    UUID oldest = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO game_item_redirect (id, canonical_id, retired_snapshot)"
            + " VALUES (?, ?, '{}'::jsonb)",
        oldest,
        pair.retired());

    GameItem canonical = items.findById(pair.canonical()).orElseThrow();
    assertThat(
            reconciliation.mergeExisting(canonical, pair.retired(), UUID.randomUUID(), evidence()))
        .isSameAs(canonical);
    entityManager.clear();

    for (Reference reference : references) {
      assertThat(
              jdbc.queryForObject(
                  "SELECT " + reference.column() + " FROM " + reference.table() + " WHERE id = ?",
                  UUID.class,
                  reference.id()))
          .isEqualTo(pair.canonical());
      assertThat(
              jdbc.queryForObject(
                  "SELECT version FROM " + reference.table() + " WHERE id = ?",
                  Long.class,
                  reference.id()))
          .isEqualTo(1L);
    }
    Map<String, Object> storedStock =
        jdbc.queryForMap(
            "SELECT amount, user_id, location_id, owning_org_unit_id, personal, stolen"
                + " FROM inventory_item WHERE id = ?",
            stock);
    assertThat(storedStock)
        .containsEntry("amount", 7d)
        .containsEntry("user_id", member)
        .containsEntry("location_id", location)
        .containsEntry("owning_org_unit_id", unit)
        .containsEntry("personal", false)
        .containsEntry("stolen", true);
    assertThat(
            jdbc.queryForObject(
                "SELECT inventory_item_id FROM inventory_item_job_order_allocation"
                    + " WHERE id = ?",
                UUID.class,
                allocation))
        .isEqualTo(stock);
    assertThat(
            jdbc.queryForObject(
                "SELECT amount FROM inventory_item_job_order_allocation" + " WHERE id = ?",
                Double.class,
                allocation))
        .isEqualTo(2d);
    assertThat(count("SELECT count(*) FROM inventory_stock_level WHERE id = ?", retiredFloor))
        .isZero();
    assertThat(
            jdbc.queryForMap(
                "SELECT minimum_amount, target_amount, low_notified_at, version"
                    + " FROM inventory_stock_level WHERE id = ?",
                canonicalFloor))
        .containsEntry("minimum_amount", 8d)
        .containsEntry("target_amount", 20d)
        .containsEntry("low_notified_at", null)
        .containsEntry("version", 1L);
    assertThat(
            jdbc.queryForObject(
                "SELECT price_buy FROM game_item_price WHERE game_item_id = ?",
                Double.class,
                pair.canonical()))
        .isEqualTo(11d);
    assertThat(
            jdbc.queryForObject(
                "SELECT base_value FROM game_item_stat WHERE game_item_id = ?",
                Double.class,
                pair.canonical()))
        .isEqualTo(99d);
    assertThat(count("SELECT count(*) FROM game_item WHERE id = ?", pair.retired())).isZero();
    JsonNode archive =
        mapper.readTree(
            jdbc.queryForObject(
                "SELECT retired_snapshot::text FROM game_item_redirect WHERE id = ?",
                String.class,
                pair.retired()));
    assertThat(archive.path("retiredItem").path("uex_item_id").asInt()).isEqualTo(pair.uexId());
    assertThat(archive.path("survivingItem").path("external_uuid").asString())
        .isEqualTo(pair.gameUuid().toString());
    assertThat(archive.path("retiredItem").path("wiki_metadata").path("tags").get(0).asString())
        .isEqualTo("retired-evidence");
    assertThat(archive.path("survivingItem").path("wiki_metadata").path("tags").get(0).asString())
        .isEqualTo("surviving-evidence");
    assertThat(archive.path("retiredItem").path("description_en").asString())
        .isEqualTo("Retired description");
    assertThat(archive.path("survivingItem").path("description_en").asString())
        .isEqualTo("Surviving description");
    assertThat(archive.path("retiredPrices").get(0).path("price_buy").asDouble()).isEqualTo(11d);
    assertThat(archive.path("survivingPrices").get(0).path("price_buy").asDouble()).isEqualTo(22d);
    assertThat(archive.path("retiredStats").get(0).path("base_value").asDouble()).isEqualTo(11d);
    assertThat(archive.path("survivingStats").get(0).path("base_value").asDouble()).isEqualTo(99d);
    assertThat(items.findById(pair.retired()))
        .get()
        .extracting(GameItem::getId)
        .isEqualTo(pair.canonical());
    assertThat(items.getReferenceById(oldest).getId()).isEqualTo(pair.canonical());
    assertThat(items.findAllById(List.of(oldest, pair.retired(), pair.canonical())))
        .extracting(GameItem::getId)
        .containsExactly(pair.canonical());
    assertThat(redirects.findCanonicalId(oldest)).contains(pair.canonical());
    assertThat(count("SELECT count(*) FROM audit_event WHERE subject_id = ?", stock)).isOne();
    assertThat(count("SELECT count(*) FROM audit_event WHERE subject_id = ?", retiredFloor))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "SELECT details FROM audit_event WHERE subject_id = ?", String.class, retiredFloor))
        .contains("minimumFrom=8", "targetFrom=10", "retired=true");
  }

  @Test
  void aRetiredIdStillOffersItsActiveBlueprintsInOneQuery() {
    Pair pair = pair();
    UUID active =
        insert(
            "INSERT INTO blueprint (id, scwiki_uuid, output_item_id, output_name)"
                + " VALUES (?, ?, ?, 'Retained Product')",
            UUID.randomUUID(),
            pair.canonical());
    insert(
        "INSERT INTO blueprint (id, scwiki_uuid, output_item_id, output_name, scwiki_deleted_at)"
            + " VALUES (?, ?, ?, 'Inactive Product', now())",
        UUID.randomUUID(),
        pair.canonical());
    jdbc.update("DELETE FROM game_item WHERE id = ?", pair.retired());
    jdbc.update(
        "INSERT INTO game_item_redirect (id, canonical_id, retired_snapshot)"
            + " VALUES (?, ?, '{}'::jsonb)",
        pair.retired(),
        pair.canonical());
    entityManager.clear();
    var statistics =
        entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    boolean enabled = statistics.isStatisticsEnabled();
    statistics.setStatisticsEnabled(true);
    try {
      long before = statistics.getPrepareStatementCount();

      assertThat(orderItems.blueprintsForItem(pair.retired()))
          .extracting(BlueprintReferenceDto::id)
          .containsExactly(active);

      assertThat(statistics.getPrepareStatementCount() - before).isOne();
    } finally {
      statistics.setStatisticsEnabled(enabled);
    }
  }

  @Test
  void localIdNormalizationUsesBoundedBatchesAndRetainsUnknownIdsWithoutDuplicates() {
    Pair pair = pair();
    jdbc.update("DELETE FROM game_item WHERE id = ?", pair.retired());
    jdbc.update(
        "INSERT INTO game_item_redirect (id, canonical_id, retired_snapshot)"
            + " VALUES (?, ?, '{}'::jsonb)",
        pair.retired(),
        pair.canonical());
    List<UUID> input = new ArrayList<>();
    input.add(pair.retired());
    input.add(pair.canonical());
    for (int index = 0; index < 499; index++) {
      input.add(UUID.randomUUID());
    }
    var statistics =
        entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    boolean enabled = statistics.isStatisticsEnabled();
    statistics.setStatisticsEnabled(true);
    try {
      long before = statistics.getPrepareStatementCount();

      List<UUID> resolved = items.resolveLocalIds(input);

      assertThat(resolved)
          .hasSize(500)
          .startsWith(pair.canonical())
          .doesNotContain(pair.retired())
          .containsAll(input.subList(2, input.size()));
      assertThat(statistics.getPrepareStatementCount() - before).isEqualTo(2);
      before = statistics.getPrepareStatementCount();
      assertThat(items.resolveLocalIds(List.of())).isEmpty();
      assertThat(statistics.getPrepareStatementCount()).isEqualTo(before);
    } finally {
      statistics.setStatisticsEnabled(enabled);
    }
  }

  @Test
  void archivedJsonRoundTripsAsAnObjectWithoutNumericPrecisionLoss() {
    Pair pair = pair();
    String precise = "12345678901234567890.123456789012345678901234567890";
    String rawJson = "{\"precise\":" + precise + ",\"nested\":{\"name\":\"Source Variant\"}}";
    GameItemRedirect redirect = new GameItemRedirect();
    redirect.setId(UUID.randomUUID());
    redirect.setCanonicalItem(items.getCanonicalReferenceById(pair.canonical()));
    redirect.setRetiredSnapshot(rawJson);
    redirects.saveAndFlush(redirect);
    entityManager.clear();

    assertThat(redirects.findById(redirect.getId()).orElseThrow().getRetiredSnapshot())
        .contains(precise, "Source Variant");
    assertThat(
            jdbc.queryForObject(
                "SELECT jsonb_typeof(retired_snapshot) FROM game_item_redirect WHERE id = ?",
                String.class,
                redirect.getId()))
        .isEqualTo("object");
    assertThat(
            jdbc.queryForObject(
                "SELECT retired_snapshot->>'precise' FROM game_item_redirect WHERE id = ?",
                String.class,
                redirect.getId()))
        .isEqualTo(precise);
  }

  @Test
  void redirectBatchUsesOneStatementRegardlessOfTheNumberOfAliases() {
    List<UUID> aliases = new ArrayList<>();
    List<UUID> survivors = new ArrayList<>();
    for (int index = 0; index < 25; index++) {
      Pair pair = pair();
      UUID alias = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO game_item_redirect (id, canonical_id, retired_snapshot)"
              + " VALUES (?, ?, '{}'::jsonb)",
          alias,
          pair.canonical());
      aliases.add(alias);
      survivors.add(pair.canonical());
    }
    entityManager.clear();
    var stats =
        entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    boolean enabled = stats.isStatisticsEnabled();
    stats.setStatisticsEnabled(true);
    try {
      long before = stats.getPrepareStatementCount();

      assertThat(items.findLocalIdRedirects(aliases))
          .hasSize(25)
          .containsValues(survivors.toArray(UUID[]::new));

      assertThat(stats.getPrepareStatementCount() - before).isOne();
      before = stats.getPrepareStatementCount();

      assertThat(items.findAllById(aliases))
          .extracting(GameItem::getId)
          .containsExactlyInAnyOrderElementsOf(survivors);

      assertThat(stats.getPrepareStatementCount() - before).isOne();
    } finally {
      stats.setStatisticsEnabled(enabled);
    }
  }

  @Test
  void eachSourceTombstoneBumpsOnlyItsAffectedRowsOnce() {
    Pair pair = pair();
    Instant deletedAt = Instant.parse("2026-11-06T10:00:00Z");
    jdbc.update(
        "UPDATE game_item SET version = 3, updated_at = '2000-01-01T00:00:00Z' WHERE id = ?",
        pair.retired());
    jdbc.update(
        "UPDATE game_item SET version = 4, updated_at = '2000-01-01T00:00:00Z' WHERE id = ?",
        pair.canonical());

    items.markUexDeletedExcept(List.of(-1), deletedAt);
    items.markScwikiDeletedExcept(Set.of(UUID.randomUUID()), deletedAt);

    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM game_item WHERE id = ?", Long.class, pair.retired()))
        .isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM game_item WHERE id = ?", Long.class, pair.canonical()))
        .isEqualTo(5);
    assertThat(
            jdbc.queryForObject(
                    "SELECT updated_at FROM game_item WHERE id = ?",
                    java.sql.Timestamp.class,
                    pair.retired())
                .toInstant())
        .isEqualTo(deletedAt);
    assertThat(
            jdbc.queryForObject(
                    "SELECT updated_at FROM game_item WHERE id = ?",
                    java.sql.Timestamp.class,
                    pair.canonical())
                .toInstant())
        .isEqualTo(deletedAt);
    assertThat(items.markUexDeletedExcept(List.of(-1), deletedAt.plusSeconds(60))).isZero();
    assertThat(items.markScwikiDeletedExcept(Set.of(UUID.randomUUID()), deletedAt.plusSeconds(60)))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM game_item WHERE id = ?", Long.class, pair.retired()))
        .isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM game_item WHERE id = ?", Long.class, pair.canonical()))
        .isEqualTo(5);
  }

  @Test
  void aP4kLedCanonicalIdentityKeepsItsNameAndAttributes() {
    Pair pair = pair();
    jdbc.update(
        "UPDATE game_item SET name = 'P4K Name', p4k_name = 'P4K Name', source_systems = 'P4K',"
            + " p4k_synced_at = now(), mass = 100 WHERE id = ?",
        pair.canonical());
    GameItem canonical = items.findById(pair.canonical()).orElseThrow();

    reconciliation.mergeExisting(canonical, pair.retired(), UUID.randomUUID(), evidence());
    entityManager.clear();

    GameItem result = items.findById(pair.canonical()).orElseThrow();
    assertThat(result.getName()).isEqualTo("P4K Name");
    assertThat(result.getMass()).isEqualTo(100d);
    assertThat(result.getUexItemId()).isEqualTo(pair.uexId());
  }

  @Test
  void aGuidKeyedPersonalBlueprintStillResolvesToTheSurvivingItem() {
    Pair pair = pair();
    UUID guid = UUID.randomUUID();
    jdbc.update("UPDATE game_item SET p4k_uuid = ? WHERE id = ?", guid, pair.retired());
    jdbc.update(
        "INSERT INTO blueprint (id, scwiki_uuid, output_item_id, output_name, produced_item_guid)"
            + " VALUES (?, ?, ?, 'Survivor Arms', ?)",
        UUID.randomUUID(),
        UUID.randomUUID(),
        pair.retired(),
        guid);
    UUID owner = member();
    jdbc.update(
        "INSERT INTO personal_blueprint (id, owner_user_id, product_key, product_name,"
            + " output_item_id) VALUES (?, ?, ?, 'Survivor Arms', ?)",
        UUID.randomUUID(),
        owner,
        guid.toString(),
        pair.retired());
    GameItem canonical = items.findById(pair.canonical()).orElseThrow();

    reconciliation.mergeExisting(canonical, pair.retired(), UUID.randomUUID(), evidence());
    entityManager.clear();

    assertThat(
            jdbc.queryForMap(
                "SELECT product_key, output_item_id FROM personal_blueprint"
                    + " WHERE owner_user_id = ?",
                owner))
        .containsEntry("product_key", guid.toString())
        .containsEntry("output_item_id", pair.canonical());
    assertThat(items.findById(pair.canonical()).orElseThrow().getP4kUuid()).isEqualTo(guid);
    assertThat(blueprintProducts.resolveByProductKey(guid.toString()))
        .get()
        .extracting(ResolvedProduct::outputItemId)
        .isEqualTo(pair.canonical());
  }

  @Test
  void contradictoryKindsNeverRetireEitherIdentity() {
    Pair pair = pair();
    jdbc.update("UPDATE game_item SET kind = 'WEAPON' WHERE id = ?", pair.retired());
    GameItem canonical = items.findById(pair.canonical()).orElseThrow();

    assertThat(
            reconciliation.mergeExisting(canonical, pair.retired(), UUID.randomUUID(), evidence()))
        .isNull();

    assertThat(
            count(
                "SELECT count(*) FROM game_item WHERE id IN (?, ?)",
                pair.retired(),
                pair.canonical()))
        .isEqualTo(2);
    assertThat(redirects.findCanonicalId(pair.retired())).isEmpty();
  }

  @Test
  void currentWikiWeaponFactsRejectAnArmorCandidateEvenWhenTheStoredWikiKindWasGeneric() {
    Pair pair = pair();
    jdbc.update("UPDATE game_item SET kind = 'GENERIC' WHERE id = ?", pair.canonical());
    jdbc.update("UPDATE game_item SET kind = 'ARMOR' WHERE id = ?", pair.retired());
    GameItem canonical = items.findById(pair.canonical()).orElseThrow();

    var current =
        new ItemReconciliationService.WikiIdentity("Same Item", GameItemKind.WEAPON, null, null);
    assertThat(reconciliation.mergeExisting(canonical, pair.retired(), UUID.randomUUID(), current))
        .isNull();

    assertThat(
            count(
                "SELECT count(*) FROM game_item WHERE id IN (?, ?)",
                pair.retired(),
                pair.canonical()))
        .isEqualTo(2);
    assertThat(redirects.findCanonicalId(pair.retired())).isEmpty();
  }

  @Test
  void aStoredGenericWikiIdentityAcceptsACompatibleCurrentArmorSource() {
    Pair pair = pair();
    jdbc.update("UPDATE game_item SET kind = 'GENERIC' WHERE id = ?", pair.canonical());
    jdbc.update("UPDATE game_item SET kind = 'ARMOR' WHERE id = ?", pair.retired());
    GameItem canonical = items.findById(pair.canonical()).orElseThrow();

    var current =
        new ItemReconciliationService.WikiIdentity("Same Item", GameItemKind.ARMOR, null, null);
    assertThat(reconciliation.mergeExisting(canonical, pair.retired(), UUID.randomUUID(), current))
        .isNotNull();

    assertThat(items.findById(pair.canonical()).orElseThrow().getKind())
        .isEqualTo(GameItemKind.ARMOR);
    assertThat(redirects.findCanonicalId(pair.retired())).contains(pair.canonical());
  }

  @Test
  void theMergeParticipantsCoverEveryCurrentForeignKeyOwner() {
    Set<String> actual =
        Set.copyOf(
            jdbc.queryForList(
                """
                SELECT rel.relname || '.' || attr.attname
                FROM pg_constraint fk
                JOIN pg_class rel ON rel.oid = fk.conrelid
                JOIN pg_attribute attr ON attr.attrelid = fk.conrelid AND attr.attnum = ANY(fk.conkey)
                WHERE fk.contype = 'f' AND fk.confrelid = 'game_item'::regclass
                """,
                String.class));

    assertThat(actual)
        .containsExactlyInAnyOrder(
            "blueprint.output_item_id", "blueprint_ingredient.game_item_id",
            "game_item_price.game_item_id", "game_item_stat.game_item_id",
            "personal_blueprint.output_item_id", "default_blueprint.output_item_id",
            "blueprint_external_alias.output_item_id", "inventory_item.game_item_id",
            "inventory_stock_level.game_item_id", "job_order_item.game_item_id",
            "supply_need.game_item_id", "supply_donation.game_item_id",
            "purchasing_deal.game_item_id", "game_item_redirect.canonical_id",
            "ship_type_loadout_port.game_item_id", "ship_type_paint.game_item_id");
  }

  @Test
  void positivePresenceRestoresOnlyKnownWikiOwnershipAndKeepsItsMetadataVersion() {
    Pair pair = pair();
    Instant oldMetadata = Instant.parse("2026-01-01T00:00:00Z");
    UUID uexOnlyUuid = UUID.randomUUID();
    jdbc.update(
        "UPDATE game_item SET scwiki_deleted_at = now(), scwiki_synced_at = ?,"
            + " scwiki_game_version_seen = '4.8.0-LIVE', mass = 42, class_name = 'source_class'"
            + " WHERE id = ?",
        java.sql.Timestamp.from(oldMetadata),
        pair.canonical());
    jdbc.update(
        "UPDATE game_item SET external_uuid = ?, scwiki_deleted_at = now() WHERE id = ?",
        uexOnlyUuid,
        pair.retired());

    assertThat(items.restoreKnownWikiItems(Set.of(pair.gameUuid(), uexOnlyUuid), Instant.now()))
        .isOne();
    entityManager.clear();

    GameItem restored = items.findById(pair.canonical()).orElseThrow();
    assertThat(restored.getScwikiDeletedAt()).isNull();
    assertThat(restored.getVersion()).isEqualTo(1L);
    assertThat(restored.getScwikiSyncedAt()).isEqualTo(oldMetadata);
    assertThat(restored.getScwikiGameVersionSeen()).isEqualTo("4.8.0-LIVE");
    assertThat(restored.getName()).isEqualTo("Same Item");
    assertThat(restored.getClassName()).isEqualTo("source_class");
    assertThat(restored.getMass()).isEqualTo(42d);
    assertThat(items.findById(pair.retired()).orElseThrow().getScwikiDeletedAt()).isNotNull();
  }

  private static ItemReconciliationService.WikiIdentity evidence() {
    return new ItemReconciliationService.WikiIdentity(
        "Same Item", GameItemKind.VEHICLE_ITEM, null, null);
  }

  private Pair pair() {
    UUID canonical = UUID.randomUUID();
    UUID retired = UUID.randomUUID();
    UUID gameUuid = UUID.randomUUID();
    int sourceId = Math.floorMod(retired.hashCode(), 1_000_000_000) + 100_000;
    jdbc.update(
        "INSERT INTO game_item (id, name, scwiki_name, kind, external_uuid,"
            + " source_systems, scwiki_synced_at) VALUES (?, 'Same Item', 'Same Item',"
            + " 'VEHICLE_ITEM', ?, 'WIKI_ONLY', now())",
        canonical,
        gameUuid);
    jdbc.update(
        "INSERT INTO game_item (id, name, uex_name, kind, uex_item_id,"
            + " source_systems, uex_synced_at) VALUES (?, 'Same Item', 'Same Item',"
            + " 'VEHICLE_ITEM', ?, 'UEX_ONLY', now())",
        retired,
        sourceId);
    return new Pair(canonical, retired, gameUuid, sourceId);
  }

  private UUID unit() {
    return insert(
        "INSERT INTO org_unit (id, kind, name, shorthand, active, is_profit_eligible)"
            + " VALUES (?, 'SPECIAL_COMMAND', ?, ?, true, false)",
        "Catalogue " + UUID.randomUUID(),
        "C" + UUID.randomUUID().toString().substring(0, 8));
  }

  private UUID member() {
    return insert(
        "INSERT INTO app_user (id, username, version) VALUES (?, ?, 0)",
        "catalogue-" + UUID.randomUUID());
  }

  private UUID location() {
    return insert(
        "INSERT INTO location (id, name, hidden) VALUES (?, ?, false)",
        "Catalogue " + UUID.randomUUID());
  }

  private UUID floor(UUID unit, UUID item, double minimum, double target) {
    return insert(
        "INSERT INTO inventory_stock_level (id, org_unit_id, game_item_id, minimum_amount,"
            + " target_amount, low_notified_at) VALUES (?, ?, ?, ?, ?, now())",
        unit,
        item,
        minimum,
        target);
  }

  private void price(UUID item, UUID terminal, double price, long modified) {
    insert(
        "INSERT INTO game_item_price (id, game_item_id, terminal_id, price_buy,"
            + " date_modified, uex_synced_at) VALUES (?, ?, ?, ?, ?, now())",
        item,
        terminal,
        price,
        modified);
  }

  private void stat(UUID item, double value) {
    insert(
        "INSERT INTO game_item_stat (id, game_item_id, property, base_value)"
            + " VALUES (?, ?, 'HEALTH_MAX_HEALTH', ?)",
        item,
        value);
  }

  private UUID insert(String sql, Object... values) {
    UUID id = UUID.randomUUID();
    Object[] arguments = new Object[values.length + 1];
    arguments[0] = id;
    System.arraycopy(values, 0, arguments, 1, values.length);
    jdbc.update(sql, arguments);
    return id;
  }

  private int count(String sql, Object... values) {
    return jdbc.queryForObject(sql, Integer.class, values);
  }

  /** The independently seeded source identities used by a reconciliation. */
  private record Pair(UUID canonical, UUID retired, UUID gameUuid, int uexId) {}

  /** A reference row whose id and version must survive a catalogue repair. */
  private record Reference(String table, String column, UUID id) {}
}
