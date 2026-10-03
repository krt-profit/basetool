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

package de.greluc.krt.profit.basetool.backend.service.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeChangeRepository;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The two lookups every op of a stock change set runs stay index lookups however long the member's
 * history grows: the latest change of a lot key and the lock of a lot's rows (REQ-XCH-016).
 */
@SpringBootTest(
    properties =
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
            + "de.greluc.krt.profit.basetool.backend.service.exchange"
            + ".ExchangeStockLookupPlanIntegrationTest$RecordingInspector")
class ExchangeStockLookupPlanIntegrationTest {

  @Autowired private JdbcTemplate jdbc;
  @Autowired private UserRepository userRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private InventoryItemRepository inventoryRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  private UUID member;
  private UUID material;
  private UUID location;
  private UUID item;

  @BeforeEach
  void setUp() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("lookup-plan-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    member = userRepository.saveAndFlush(user).getId();
    material = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,
                              is_job_order, is_visible, source_systems)
        VALUES (?, ?, 'NO_REFINE', 'SCU', false, false, true, 'UEX_ONLY')
        """,
        material,
        "lookup-plan-" + material);
    item = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO game_item (id, name, kind, source_systems) VALUES (?, ?, 'GENERIC',"
            + " 'UEX_ONLY')",
        item,
        "lookup-plan-" + item);
    Location place = new Location();
    place.setName("lookup-plan-" + UUID.randomUUID());
    location = locationRepository.saveAndFlush(place).getId();
  }

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM inventory_item WHERE user_id = ?", member);
    jdbc.update("DELETE FROM app_user WHERE id = ?", member);
    jdbc.update("DELETE FROM material WHERE id = ?", material);
    jdbc.update("DELETE FROM game_item WHERE id = ?", item);
    jdbc.update("DELETE FROM location WHERE id = ?", location);
  }

  @Test
  void theLatestChangeOfAKeyIsFoundThroughTheIndexNotAFilter() throws Exception {
    jdbc.update(
        """
        INSERT INTO exchange_change (user_id, resource, entity_key, source_channel)
        SELECT ?, 'STOCK', 'lot-' || g, 'client' FROM generate_series(1, 3000) g
        """,
        member);
    jdbc.execute("ANALYZE exchange_change");
    String sql =
        ExchangeChangeRepository.class
            .getMethod("findLatestForKey", UUID.class, String.class, String.class)
            .getAnnotation(Query.class)
            .value()
            .replace(":userId", literal(member))
            .replace(":resource", "'STOCK'")
            .replace(":entityKey", "'lot-7'");

    assertLooksUpBy(sql, "entity_key");
  }

  @Test
  void aLotsRowsAreLockedThroughTheStackKeyIndexOnEveryColumn() {
    jdbc.update(
        """
        INSERT INTO inventory_item (id, user_id, material_id, location_id, quality, amount,
                                    personal, stolen)
        SELECT gen_random_uuid(), ?, ?, ?, g % 1000, 1, true, false
        FROM generate_series(1, 3000) g
        """,
        member, material, location);
    jdbc.execute("ANALYZE inventory_item");

    String sql = lockStatement(7);
    List<String> values =
        List.of(literal(member), literal(material), literal(location), "7", "false");
    StringBuilder bound = new StringBuilder();
    int next = 0;
    for (char c : sql.toCharArray()) {
      bound.append(c == '?' ? values.get(next++) : String.valueOf(c));
    }

    assertThat(next).isEqualTo(values.size());
    assertLooksUpBy(bound.toString(), "quality");
  }

  @Test
  void theLotLockFindsTheRowsTheLotKeyNames() {
    UUID atZero = stock(material, null, 0, true);
    UUID atSeven = stock(material, null, 7, true);
    UUID sharedAtZero = stock(material, null, 0, false);
    stock(null, item, null, true);

    for (int quality : new int[] {0, 7, 3}) {
      List<UUID> locked = stockIds(quality);
      List<UUID> byLotKey =
          jdbc.queryForList(
              """
              SELECT id FROM inventory_item
              WHERE user_id = ? AND material_id = ? AND location_id = ?
                AND COALESCE(quality, 0) = ? AND stolen = false
              """,
              UUID.class,
              member,
              material,
              location,
              quality);

      assertThat(locked).containsExactlyInAnyOrderElementsOf(byLotKey);
    }
    assertThat(stockIds(0)).containsExactlyInAnyOrder(atZero, sharedAtZero);
    assertThat(stockIds(7)).containsExactly(atSeven);
    assertThatThrownBy(() -> stock(material, null, null, true))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("chk_inventory_item_quality_by_kind");
  }

  /**
   * Runs the lot lock for one quality and returns the statement Hibernate sent for it.
   *
   * @param quality the quality
   * @return the statement, with its parameters as {@code ?}
   */
  private @NotNull String lockStatement(int quality) {
    RecordingInspector.STATEMENTS.clear();
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status ->
                inventoryRepository.lockMaterialLot(member, material, location, quality, false));
    return RecordingInspector.STATEMENTS.stream()
        .filter(s -> s.contains("inventory_item") && s.contains("for no key update"))
        .findFirst()
        .orElseThrow();
  }

  /**
   * Plans a statement with sequential scans ruled out, as on a table many members share, and
   * asserts that one of its index scans looks up by a column.
   *
   * @param sql the statement, its parameters bound as literals
   * @param column the column
   */
  private void assertLooksUpBy(@NotNull String sql, @NotNull String column) {
    List<String> plan =
        Objects.requireNonNull(
            jdbc.execute(
                (ConnectionCallback<List<String>>)
                    connection -> {
                      List<String> lines = new ArrayList<>();
                      try (Statement statement = connection.createStatement()) {
                        statement.execute("SET enable_seqscan = off");
                        try (ResultSet rows = statement.executeQuery("EXPLAIN " + sql)) {
                          while (rows.next()) {
                            lines.add(rows.getString(1).trim());
                          }
                        } finally {
                          statement.execute("RESET enable_seqscan");
                        }
                      }
                      return lines;
                    }));

    assertThat(plan)
        .as(String.join(System.lineSeparator(), plan))
        .anyMatch(line -> line.startsWith("Index Cond:") && line.contains(column));
  }

  /**
   * Returns the ids the lot lock finds for a quality.
   *
   * @param quality the quality
   * @return the ids
   */
  private @NotNull List<UUID> stockIds(int quality) {
    return Objects.requireNonNull(
        new TransactionTemplate(transactionManager)
            .execute(
                status ->
                    inventoryRepository
                        .lockMaterialLot(member, material, location, quality, false)
                        .stream()
                        .map(InventoryItem::getId)
                        .toList()));
  }

  /**
   * Seeds one of the member's rows at the location.
   *
   * @param materialId the material, or {@code null} for an item row
   * @param gameItemId the item, or {@code null} for a material row
   * @param quality the quality, {@code null} for an item row
   * @param personal whether it is personal stock
   * @return the row's id
   */
  private @NotNull UUID stock(UUID materialId, UUID gameItemId, Integer quality, boolean personal) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO inventory_item (id, user_id, material_id, game_item_id, location_id, quality,
                                    amount, personal, stolen)
        VALUES (?, ?, ?, ?, ?, ?, 1, ?, false)
        """,
        id,
        member,
        materialId,
        gameItemId,
        location,
        quality,
        personal);
    return id;
  }

  /**
   * Quotes a UUID as an SQL literal.
   *
   * @param id the id
   * @return the literal
   */
  private static @NotNull String literal(@NotNull UUID id) {
    return "'" + id + "'";
  }

  /** Records every statement Hibernate prepares, so a test can plan the one a query sent. */
  public static final class RecordingInspector implements StatementInspector {

    /** The statements recorded since the last clear. */
    static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

    /**
     * Records a statement and passes it on unchanged.
     *
     * @param sql the statement
     * @return the same statement
     */
    @Override
    public String inspect(String sql) {
      STATEMENTS.add(sql);
      return sql;
    }
  }
}
