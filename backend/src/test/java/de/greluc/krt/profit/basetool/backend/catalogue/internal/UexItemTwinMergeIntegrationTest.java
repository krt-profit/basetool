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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexItemDto;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** A row a second UEX listing created merges into its game row on PostgreSQL (REQ-DATA-044). */
@SpringBootTest
@Transactional
class UexItemTwinMergeIntegrationTest {

  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager entityManager;
  @Autowired private GameItemRepository items;
  @Autowired private GameItemRedirectRepository redirects;
  @Autowired private ItemReconciliationService reconciliation;
  @Autowired private UexItemTwinMerger twinMerger;

  @Test
  void aTwinWhoseListingCarriesTheGameRowsUuidMergesAndMovesItsReferences() {
    String name = uniqueName();
    UUID uuid = UUID.randomUUID();
    int targetUex = uexId();
    int twinUex = uexId();
    UUID target = gameRow(name, uuid, null, null, targetUex);
    UUID twin = twin(name, twinUex);
    UUID recipe = blueprint(twin);
    UUID personal =
        insert(
            "INSERT INTO personal_blueprint (id, owner_user_id, product_key, product_name,"
                + " output_item_id) VALUES (?, ?, ?, 'Twin Product', ?)",
            member(),
            "twin-" + UUID.randomUUID(),
            twin);

    merge(
        List.of(listing(targetUex, name, uuid), listing(twinUex, name, uuid)),
        twin,
        target,
        UexItemTwinRule.GAME_UUID);

    assertThat(outputOf("blueprint", recipe)).isEqualTo(target);
    assertThat(outputOf("personal_blueprint", personal)).isEqualTo(target);
    assertThat(count("SELECT count(*) FROM game_item WHERE id = ?", twin)).isZero();
    assertThat(items.findById(twin)).map(GameItem::getId).contains(target);
    assertThat(
            jdbc.queryForObject(
                "SELECT uex_item_id FROM game_item WHERE id = ?", Integer.class, target))
        .isEqualTo(targetUex);
    assertThat(redirects.isMergedUexItemId(twinUex)).isTrue();
    assertThat(redirects.isMergedUexItemId(targetUex)).isFalse();
    assertThat(
            jdbc.queryForList(
                "SELECT details FROM audit_event WHERE event_type = 'CATALOGUE_ITEM_MERGED'"
                    + " AND subject_id = ?",
                String.class,
                target))
        .singleElement()
        .asString()
        .contains("retiredItem=" + twin, "rule=GAME_UUID", "uexItemId=" + twinUex, "references=2");
  }

  @Test
  void aTwinWithoutAGameUuidMergesIntoTheOnlyGameRowOfItsName() {
    String name = uniqueName();
    int targetUex = uexId();
    int twinUex = uexId();
    UUID target = gameRow(name, null, UUID.randomUUID(), null, targetUex);
    UUID twin = twin(name, twinUex);
    UUID recipe = blueprint(twin);

    merge(
        List.of(listing(targetUex, name, null), listing(twinUex, name, null)),
        twin,
        target,
        UexItemTwinRule.UNIQUE_NAME);

    assertThat(outputOf("blueprint", recipe)).isEqualTo(target);
    assertThat(redirects.isMergedUexItemId(twinUex)).isTrue();
  }

  @Test
  void aTwinOfTwoGsPairedRowsMergesIntoTheGsRow() {
    String name = uniqueName();
    String className = "TWN_Pistol_" + UUID.randomUUID().toString().substring(0, 8);
    int oldUex = uexId();
    int gsUex = uexId();
    int twinUex = uexId();
    gameRow(name, null, UUID.randomUUID(), className, oldUex);
    UUID gs = gameRow(name, null, UUID.randomUUID(), className + "_GS", gsUex);
    UUID twin = twin(name, twinUex);
    UUID recipe = blueprint(twin);

    merge(
        List.of(
            listing(oldUex, name, null), listing(gsUex, name, null), listing(twinUex, name, null)),
        twin,
        gs,
        UexItemTwinRule.GS_RECORD);

    assertThat(outputOf("blueprint", recipe)).isEqualTo(gs);
  }

  @Test
  void aGameUuidOnARowOfAnotherNameMergesNothing() {
    String name = uniqueName();
    UUID uuid = UUID.randomUUID();
    int rifleUex = uexId();
    int namesakeUex = uexId();
    int twinUex = uexId();
    gameRow(name + " Rifle", uuid, null, null, rifleUex);
    gameRow(name, UUID.randomUUID(), null, null, namesakeUex);
    UUID twin = twin(name, twinUex);
    List<UexItemDto> listings =
        List.of(
            listing(rifleUex, name + " Rifle", uuid),
            listing(namesakeUex, name, null),
            listing(twinUex, name, uuid));

    assertThat(twinMerger.findTwins(listings, census(listings)))
        .noneMatch(found -> found.twinId().equals(twin));
  }

  @Test
  void theGameRowKeepsItsOwnPriceAndTakesTheTwinsOtherTerminals() {
    String name = uniqueName();
    UUID uuid = UUID.randomUUID();
    int targetUex = uexId();
    int twinUex = uexId();
    UUID target = gameRow(name, uuid, null, null, targetUex);
    UUID twin = twin(name, twinUex);
    UUID shared = insert("INSERT INTO terminal (id, name) VALUES (?, 'Twin Shared Terminal')");
    UUID twinOnly = insert("INSERT INTO terminal (id, name) VALUES (?, 'Twin Only Terminal')");
    price(target, shared, 10, 1);
    price(twin, shared, 99, 9);
    price(twin, twinOnly, 30, 3);

    merge(
        List.of(listing(targetUex, name, uuid), listing(twinUex, name, uuid)),
        twin,
        target,
        UexItemTwinRule.GAME_UUID);

    assertThat(
            jdbc.queryForList(
                "SELECT price_buy FROM game_item_price WHERE game_item_id = ? AND terminal_id = ?",
                Double.class,
                target,
                shared))
        .containsExactly(10d);
    assertThat(
            jdbc.queryForList(
                "SELECT price_buy FROM game_item_price WHERE game_item_id = ? AND terminal_id = ?",
                Double.class,
                target,
                twinOnly))
        .containsExactly(30d);
    assertThat(count("SELECT count(*) FROM game_item_price WHERE game_item_id = ?", twin)).isZero();
  }

  private void merge(
      List<UexItemDto> listings, UUID twin, UUID target, UexItemTwinRule expectedRule) {
    assertThat(twinMerger.findTwins(listings, census(listings)))
        .contains(new UexItemTwins.Twin(twin, target, expectedRule));
    assertThat(reconciliation.mergeUexTwin(twin, target, expectedRule, UUID.randomUUID())).isTrue();
    entityManager.flush();
    entityManager.clear();
  }

  private static UexIdentityCensus census(List<UexItemDto> listings) {
    return UexIdentityCensus.of(listings, UexItemDto::id, UexItemDto::uuid, UexItemDto::name, true);
  }

  private UUID gameRow(
      String name,
      @Nullable UUID externalUuid,
      @Nullable UUID p4kUuid,
      @Nullable String className,
      int uexItemId) {
    return insert(
        "INSERT INTO game_item (id, name, uex_name, kind, external_uuid, p4k_uuid, class_name,"
            + " uex_item_id, source_systems, scwiki_synced_at, uex_synced_at)"
            + " VALUES (?, ?, ?, 'GENERIC', ?, ?, ?, ?, 'BOTH', now(), now())",
        name,
        name,
        externalUuid,
        p4kUuid,
        className,
        uexItemId);
  }

  private UUID twin(String name, int uexItemId) {
    return insert(
        "INSERT INTO game_item (id, name, uex_name, kind, uex_item_id, source_systems,"
            + " uex_synced_at) VALUES (?, ?, ?, 'GENERIC', ?, 'UEX_ONLY', now())",
        name,
        name,
        uexItemId);
  }

  private UUID blueprint(UUID item) {
    return insert(
        "INSERT INTO blueprint (id, scwiki_uuid, output_item_id) VALUES (?, ?, ?)",
        UUID.randomUUID(),
        item);
  }

  private UUID member() {
    return insert(
        "INSERT INTO app_user (id, username, version) VALUES (?, ?, 0)",
        "twin-" + UUID.randomUUID());
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

  private UUID outputOf(String table, UUID id) {
    return jdbc.queryForObject(
        "SELECT output_item_id FROM " + table + " WHERE id = ?", UUID.class, id);
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

  private static String uniqueName() {
    return "Twin Test Pistol " + UUID.randomUUID();
  }

  private static int uexId() {
    return Math.floorMod(UUID.randomUUID().hashCode(), 1_000_000_000) + 100_000;
  }

  private static UexItemDto listing(int id, String name, @Nullable UUID uuid) {
    return new UexItemDto(
        id,
        0,
        3,
        1,
        0,
        name,
        "twin-" + id,
        uuid == null ? "" : uuid.toString(),
        "1",
        null,
        null,
        0,
        null,
        "Personal Weapons",
        "Pistols",
        "Klaus & Werner",
        null,
        null,
        0,
        0,
        0,
        0,
        0,
        "4.8.0-LIVE",
        0L,
        0L,
        null);
  }
}
