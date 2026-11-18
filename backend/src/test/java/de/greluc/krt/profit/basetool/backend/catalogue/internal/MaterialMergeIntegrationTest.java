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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialExternalAlias;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialExternalAliasRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialExternalAliasSource;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * A second UEX listing of a commodity merges into the material holding the P4K GUID on PostgreSQL
 * (REQ-DATA-046), modelled on Party Favors (UEX 54, priced) and Fireworks (UEX 30, P4K GUID).
 */
@SpringBootTest
@Transactional
class MaterialMergeIntegrationTest {

  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager entityManager;
  @Autowired private MaterialRepository materials;
  @Autowired private MaterialExternalAliasRepository aliases;
  @Autowired private MaterialRedirectRepository redirects;
  @Autowired private MaterialMergeService mergeService;

  @Test
  void partyFavorsMergesIntoFireworks_withItsReferencesPricesAndWikiIdentity() {
    Pair pair = pair();
    UUID unit = unit();
    UUID member = member();
    UUID stock =
        insert(
            "INSERT INTO inventory_item (id, user_id, material_id, location_id, quality, amount,"
                + " personal, stolen, owning_org_unit_id, version)"
                + " VALUES (?, ?, ?, ?, 500, 12, false, false, ?, 0)",
            member,
            pair.twin(),
            location(),
            unit);
    UUID need =
        insert(
            "INSERT INTO supply_need (id, org_unit_id, material_id, amount, priority, status)"
                + " VALUES (?, ?, ?, 5, 'NORMAL', 'OPEN')",
            unit,
            pair.twin());
    UUID donation =
        insert(
            "INSERT INTO supply_donation (id, donor_user_id, receiving_org_unit_id, material_id,"
                + " pledged_amount, status, source_inventory_item_id)"
                + " VALUES (?, ?, ?, ?, 3, 'PLEDGED', ?)",
            member,
            unit,
            pair.twin(),
            stock);
    UUID partner =
        insert(
            "INSERT INTO purchasing_partner (id, kind, name, channel, contact_via, status)"
                + " VALUES (?, 'ORG', 'Merge Test', 'OTHER', 'OTHER', 'ACTIVE')");
    UUID deal =
        insert(
            "INSERT INTO purchasing_deal (id, need_id, raising_org_unit_id, partner_id,"
                + " delegate_user_id, material_id, amount, unit_price, total, payment_model,"
                + " risk_class, status) VALUES (?, ?, ?, ?, ?, ?, 4, 100, 400, 'ADVANCE', 'A',"
                + " 'DRAFT')",
            need,
            unit,
            partner,
            member,
            pair.twin());
    UUID request =
        insert(
            "INSERT INTO material_exchange_request (id, request_kind, requested_material_id,"
                + " requested_amount, owner_id, status, posted_at, created_at, updated_at)"
                + " VALUES (?, 'MATERIAL', ?, 1, ?, 'ACTIVE', now(), now(), now())",
            pair.twin(),
            member);
    UUID recipe =
        insert("INSERT INTO blueprint (id, scwiki_uuid) VALUES (?, ?)", UUID.randomUUID());
    UUID ingredient =
        insert(
            "INSERT INTO blueprint_ingredient (id, blueprint_id, order_index, kind, material_id,"
                + " quantity_scu) VALUES (?, ?, 0, 'RESOURCE', ?, 2)",
            recipe,
            pair.twin());
    UUID alias = alias(pair.twin(), "Party Favors " + UUID.randomUUID());
    Material ore = material("Party Ore " + UUID.randomUUID(), MaterialSourceSystem.UEX_ONLY);
    ore.setRefinedMaterial(materials.getReferenceById(pair.twin()));
    UUID shared = insert("INSERT INTO terminal (id, name) VALUES (?, 'Merge Shared Terminal')");
    UUID twinOnly = insert("INSERT INTO terminal (id, name) VALUES (?, 'Merge Twin Terminal')");
    price(pair.survivor(), shared, 1);
    price(pair.twin(), shared, 4761);
    price(pair.twin(), twinOnly, 4900);
    UUID olderAlias = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO material_redirect (id, canonical_id, retired_snapshot)"
            + " VALUES (?, ?, '{}'::jsonb)",
        olderAlias,
        pair.twin());
    materials.flush();

    merge(pair);

    assertThat(count("SELECT count(*) FROM material WHERE id = ?", pair.twin())).isZero();
    for (Reference reference :
        List.of(
            new Reference("inventory_item", "material_id", stock),
            new Reference("supply_need", "material_id", need),
            new Reference("supply_donation", "material_id", donation),
            new Reference("purchasing_deal", "material_id", deal),
            new Reference("material_exchange_request", "requested_material_id", request),
            new Reference("blueprint_ingredient", "material_id", ingredient),
            new Reference("material_external_alias", "material_id", alias),
            new Reference("material", "refined_material_id", ore.getId()),
            new Reference("material_redirect", "canonical_id", olderAlias))) {
      assertThat(
              jdbc.queryForObject(
                  "SELECT " + reference.column() + " FROM " + reference.table() + " WHERE id = ?",
                  UUID.class,
                  reference.id()))
          .as(reference.table())
          .isEqualTo(pair.survivor());
    }
    assertThat(sellPrice(pair.survivor(), shared)).containsExactly(1d);
    assertThat(sellPrice(pair.survivor(), twinOnly)).containsExactly(4900d);
    Material survivor = materials.findById(pair.survivor()).orElseThrow();
    assertThat(survivor.getName()).isEqualTo(pair.survivorName());
    assertThat(survivor.getIdCommodity()).isEqualTo(pair.survivorUex());
    assertThat(survivor.getScwikiUuid()).isEqualTo(pair.resourceType());
    assertThat(survivor.getScwikiKey()).isEqualTo("PartyFavors");
    assertThat(survivor.getSourceSystems()).isEqualTo(MaterialSourceSystem.BOTH);
    assertThat(survivor.getIsJobOrder()).isTrue();
    assertThat(materials.findById(pair.twin())).map(Material::getId).contains(pair.survivor());
    assertThat(materials.existsById(pair.twin())).isTrue();
    assertThat(materials.findAllById(List.of(pair.twin(), pair.survivor())))
        .extracting(Material::getId)
        .containsExactly(pair.survivor());
    assertThat(materials.getReferenceById(pair.twin()).getId()).isEqualTo(pair.survivor());
    assertThat(redirects.isMergedUexCommodityId(pair.twinUex())).isTrue();
    assertThat(redirects.isMergedUexCommodityId(pair.survivorUex())).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT retired_snapshot ->> 'retiredMaterial' IS NOT NULL FROM material_redirect"
                    + " WHERE id = ?",
                Boolean.class,
                pair.twin()))
        .isTrue();
    assertThat(details("CATALOGUE_MATERIAL_MERGED", pair.survivor()))
        .singleElement()
        .asString()
        .contains(
            "retiredMaterial=" + pair.twin(),
            "rule=UEX_WIKI_KEY",
            "uexCommodityId=" + pair.twinUex(),
            "references=");
    assertThat(details("MARKET_CATALOGUE_REASSIGNED", request))
        .singleElement()
        .asString()
        .contains("retiredMaterial=" + pair.twin(), "survivorMaterial=" + pair.survivor());
    assertThat(details("INVENTORY_ITEM_CATALOGUE_REASSIGNED", stock)).hasSize(1);
    assertThat(details("SUPPLY_CATALOGUE_REASSIGNED", need)).hasSize(1);
    assertThat(details("PURCHASING_CATALOGUE_REASSIGNED", deal)).hasSize(1);
  }

  @Test
  void aStockFloorBothMaterialsHoldKeepsTheHigherSettings_andAnotherFloorMoves() {
    Pair pair = pair();
    UUID unit = unit();
    UUID otherUnit = unit();
    UUID twinFloor = floor(unit, pair.twin(), 8, 10);
    UUID survivorFloor = floor(unit, pair.survivor(), 5, 20);
    UUID movedFloor = floor(otherUnit, pair.twin(), 2, 4);

    merge(pair);

    assertThat(count("SELECT count(*) FROM inventory_stock_level WHERE id = ?", twinFloor))
        .isZero();
    assertThat(
            jdbc.queryForMap(
                "SELECT minimum_amount, target_amount, material_id FROM inventory_stock_level"
                    + " WHERE id = ?",
                survivorFloor))
        .containsEntry("minimum_amount", 8d)
        .containsEntry("target_amount", 20d)
        .containsEntry("material_id", pair.survivor());
    assertThat(
            jdbc.queryForObject(
                "SELECT material_id FROM inventory_stock_level WHERE id = ?",
                UUID.class,
                movedFloor))
        .isEqualTo(pair.survivor());
    assertThat(details("MIN_STOCK_LEVEL_CATALOGUE_REASSIGNED", twinFloor))
        .singleElement()
        .asString()
        .contains("retired=true");
  }

  @Test
  void aPairTheRuleNoLongerHoldsForUnderTheLockMergesNothing() {
    Pair pair = pair();
    jdbc.update(
        "UPDATE material SET scwiki_uuid = ? WHERE id = ?", UUID.randomUUID(), pair.survivor());
    entityManager.clear();

    assertThat(mergeService.merge(pair.twin(), pair.survivor(), MaterialMergeRule.UEX_WIKI_KEY))
        .isFalse();
    assertThat(count("SELECT count(*) FROM material WHERE id = ?", pair.twin())).isEqualTo(1);
    assertThat(redirects.isMergedUexCommodityId(pair.twinUex())).isFalse();
  }

  private void merge(Pair pair) {
    materials.flush();
    assertThat(
            UexCommodityTwins.find(materials.findAll(), Set.of(pair.survivorUex(), pair.twinUex())))
        .contains(
            new UexCommodityTwins.Twin(
                pair.twin(), pair.survivor(), MaterialMergeRule.UEX_WIKI_KEY));
    entityManager.clear();
    assertThat(mergeService.merge(pair.twin(), pair.survivor(), MaterialMergeRule.UEX_WIKI_KEY))
        .isTrue();
    entityManager.flush();
    entityManager.clear();
  }

  private Pair pair() {
    UUID resourceType = UUID.randomUUID();
    int survivorUex = uexId();
    int twinUex = uexId();
    String survivorName = "Fireworks " + UUID.randomUUID();
    Material fireworks = material(survivorName, MaterialSourceSystem.UEX_ONLY);
    fireworks.setIdCommodity(survivorUex);
    fireworks.setP4kUuid(UUID.randomUUID());
    fireworks.setP4kSubtypeUuid(resourceType);
    Material partyFavors = material("Party Favors " + UUID.randomUUID(), MaterialSourceSystem.BOTH);
    partyFavors.setIdCommodity(twinUex);
    partyFavors.setScwikiUuid(resourceType);
    partyFavors.setScwikiKey("PartyFavors");
    partyFavors.setIsJobOrder(true);
    partyFavors.setPriceSell(4761d);
    materials.flush();
    return new Pair(
        partyFavors.getId(), fireworks.getId(), twinUex, survivorUex, survivorName, resourceType);
  }

  private Material material(String name, MaterialSourceSystem source) {
    Material material = new Material();
    material.setName(name);
    material.setType(MaterialType.NO_REFINE);
    material.setSourceSystems(source);
    return materials.save(material);
  }

  private UUID alias(UUID material, String externalName) {
    MaterialExternalAlias alias = new MaterialExternalAlias();
    alias.setSourceSystem(MaterialExternalAliasSource.UEX);
    alias.setExternalName(externalName);
    alias.setMaterial(materials.getReferenceById(material));
    alias.setCreatedBy("system");
    return aliases.save(alias).getId();
  }

  private void price(UUID material, UUID terminal, double sell) {
    insert(
        "INSERT INTO material_price (id, material_id, terminal_id, price_sell, version)"
            + " VALUES (?, ?, ?, ?, 0)",
        material,
        terminal,
        sell);
  }

  private List<Double> sellPrice(UUID material, UUID terminal) {
    return jdbc.queryForList(
        "SELECT price_sell FROM material_price WHERE material_id = ? AND terminal_id = ?",
        Double.class,
        material,
        terminal);
  }

  private UUID floor(UUID unit, UUID material, double minimum, double target) {
    return insert(
        "INSERT INTO inventory_stock_level (id, org_unit_id, material_id, minimum_amount,"
            + " target_amount, low_notified_at) VALUES (?, ?, ?, ?, ?, now())",
        unit,
        material,
        minimum,
        target);
  }

  private List<String> details(String eventType, UUID subject) {
    return jdbc.queryForList(
        "SELECT details FROM audit_event WHERE event_type = ? AND subject_id = ?",
        String.class,
        eventType,
        subject);
  }

  private UUID unit() {
    return insert(
        "INSERT INTO org_unit (id, kind, name, shorthand, active, is_profit_eligible)"
            + " VALUES (?, 'SPECIAL_COMMAND', ?, ?, true, false)",
        "Merge " + UUID.randomUUID(),
        "M" + UUID.randomUUID().toString().substring(0, 8));
  }

  private UUID member() {
    return insert(
        "INSERT INTO app_user (id, username, version) VALUES (?, ?, 0)",
        "merge-" + UUID.randomUUID());
  }

  private UUID location() {
    UUID id = UUID.randomUUID();
    TestPlaces.insertLocation(jdbc, id, "Merge " + id);
    return id;
  }

  private UUID insert(String sql, Object... values) {
    UUID id = UUID.randomUUID();
    Object[] arguments = new Object[values.length + 1];
    arguments[0] = id;
    System.arraycopy(values, 0, arguments, 1, values.length);
    jdbc.update(sql, arguments);
    return id;
  }

  private int count(String sql, UUID id) {
    Integer found = jdbc.queryForObject(sql, Integer.class, id);
    return found == null ? 0 : found;
  }

  private static int uexId() {
    return Math.floorMod(UUID.randomUUID().hashCode(), 1_000_000_000) + 100_000;
  }

  /**
   * A row that referenced the merged material.
   *
   * @param table the row's table
   * @param column the column naming the material
   * @param id the row's id
   */
  private record Reference(String table, String column, UUID id) {}

  /**
   * A twin and the material it merges into.
   *
   * @param twin the Party Favors row
   * @param survivor the Fireworks row
   * @param twinUex the twin's UEX commodity id
   * @param survivorUex the survivor's UEX commodity id
   * @param survivorName the survivor's name
   * @param resourceType the record's {@code ResourceType}, the twin's SC Wiki UUID
   */
  private record Pair(
      UUID twin,
      UUID survivor,
      int twinUex,
      int survivorUex,
      String survivorName,
      UUID resourceType) {}
}
