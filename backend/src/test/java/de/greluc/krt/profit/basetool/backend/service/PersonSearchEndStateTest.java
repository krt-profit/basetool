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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.dto.PersonSearchHitDto;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonMentionSource.Target;
import de.greluc.krt.profit.basetool.backend.privacy.internal.MentionSourceDeclarations;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real-Postgres characterisation of the admin Personensuche (REQ-SEC-060): one row in every table
 * that holds a searched column, the synthetic name written into each of its searched columns, and
 * the hits the search returns compared with a committed list of area, table, column, link kind and
 * snippet.
 *
 * <p>The rows are filled generically from the schema, so a column registered later is seeded and
 * pinned without touching this test, and a table the generic row cannot satisfy is reported by
 * name. Foreign keys are switched off for the transaction, since the search reads single tables.
 * The test is the safety net for moving the registry into the owning modules: it states what the
 * search reaches, not where the places are declared.
 */
@SpringBootTest
@Transactional
class PersonSearchEndStateTest {

  private static final String GOLDEN = "/privacy/person-search-end-state.txt";

  private static final String TERM = "ZzzMentionZzz";

  private static final String VALUE = "Vor " + TERM + " nach";

  private static final String THUMBPRINT = "A".repeat(43);

  /** Columns a generic row cannot satisfy, keyed {@code table.column}: enumerations and formats. */
  private static final Map<String, Object> VALID = new LinkedHashMap<>();

  static {
    VALID.put("app_user.rsi_handle", TERM);
    VALID.put("user_approval_event.decision", "APPROVED");
    VALID.put("deletion_request.status", "PENDING");
    VALID.put("mission_objective.kind", "PRIMARY");
    VALID.put("inventory_item.material_id", UUID.randomUUID());
    VALID.put("inventory_item.quality", 500);
    VALID.put("personal_inventory_item.location_type", "CITY");
    VALID.put("exchange_installation.key_thumbprint", THUMBPRINT);
    VALID.put("exchange_journal.resource", "STOCK");
    VALID.put("exchange_journal.action", "STOCK_SET_QUANTITY");
    VALID.put("material_exchange_offer.offer_kind", "ITEM");
    VALID.put("material_exchange_offer.status", "ACTIVE");
    VALID.put("material_exchange_offer.item_product_key", "zzz-key");
    VALID.put("material_exchange_offer.item_name", "Zzz Item");
    VALID.put("material_exchange_offer.item_quantity", 1);
    VALID.put("material_exchange_request.request_kind", "ITEM");
    VALID.put("material_exchange_request.status", "ACTIVE");
    VALID.put("material_exchange_request.item_product_key", "zzz-key");
    VALID.put("material_exchange_request.item_name", "Zzz Item");
    VALID.put("material_exchange_request.item_quantity", 1);
    VALID.put("bank_account.type", "SPECIAL");
    VALID.put("bank_account.status", "ACTIVE");
    VALID.put("bank_transaction.type", "DEPOSIT");
    VALID.put("bank_booking_request.type", "DEPOSIT");
    VALID.put("bank_booking_request.status", "PENDING");
    VALID.put("bank_booking_request.amount", 1);
    VALID.put("bank_audit_event.event_type", "ACCOUNT_CREATED");
    VALID.put("org_chart_position.position_type", "COMMAND_LEAD");
    VALID.put("org_unit.kind", "ORGANISATIONSLEITUNG");
    VALID.put("org_unit.is_promotion_enabled", Boolean.FALSE);
    VALID.put("org_chart_position.org_unit_id", UUID.randomUUID());
    VALID.put("bank_booking_request.counterparty_user_id", UUID.randomUUID());
    VALID.put("material_external_alias.source_system", "UEX");
    VALID.put("blueprint_external_alias.source_system", "SCMDB");
    VALID.put("promotion_level_content.level", "LEVEL_A");
    VALID.put("rank_requirement.minimum_level", "LEVEL_A");
    VALID.put("audit_event.domain", "JOB_ORDER");
    VALID.put("audit_event.event_type", "JOB_ORDER_CREATED");
    VALID.put("job_order.type", "MATERIAL");
    VALID.put("job_order.status", "OPEN");
  }

  @Autowired private PersonSearchService personSearchService;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void theNameIsFoundInEveryRegisteredColumnAndOnlyThere() throws IOException {
    jdbc.execute("SET LOCAL session_replication_role = replica");
    Map<String, List<Target>> byTable = new LinkedHashMap<>();
    List<Target> declared = MentionSourceDeclarations.targets();
    for (Target target : declared) {
      byTable.computeIfAbsent(target.table(), table -> new ArrayList<>()).add(target);
    }

    List<String> failures = new ArrayList<>();
    for (Map.Entry<String, List<Target>> entry : byTable.entrySet()) {
      jdbc.execute("SAVEPOINT seed");
      try {
        insertRow(entry.getKey(), entry.getValue());
        jdbc.execute("RELEASE SAVEPOINT seed");
      } catch (RuntimeException e) {
        jdbc.execute("ROLLBACK TO SAVEPOINT seed");
        failures.add(entry.getKey() + ": " + e.getMessage());
      }
    }
    assertThat(failures)
        .as("tables the generic row could not be inserted into; give them an override")
        .isEmpty();

    PersonSearchService.PersonSearchResult result = personSearchService.search(TERM);
    List<String> lines =
        result.hits().stream().map(PersonSearchEndStateTest::line).sorted().toList();
    Files.writeString(
        Path.of("build/person-search-end-state.actual.txt"), String.join("\n", lines) + "\n");

    assertThat(result.truncated()).isFalse();
    assertThat(result.cappedColumns()).isEmpty();
    assertThat(result.hits()).as("one hit per registered column").hasSameSizeAs(declared);
    assertThat(lines).containsExactlyElementsOf(golden());
  }

  private static String line(PersonSearchHitDto hit) {
    return hit.area()
        + "|"
        + hit.table()
        + "|"
        + hit.column()
        + "|"
        + hit.linkKind()
        + "|"
        + hit.snippet();
  }

  private static List<String> golden() throws IOException {
    try (InputStream in = PersonSearchEndStateTest.class.getResourceAsStream(GOLDEN)) {
      assertThat(in).as("the committed list " + GOLDEN).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8)
          .lines()
          .filter(line -> !line.isBlank())
          .toList();
    }
  }

  private void insertRow(String table, List<Target> targets) {
    List<String> searched = targets.stream().map(Target::column).toList();
    List<String> names = new ArrayList<>();
    List<Object> values = new ArrayList<>();
    jdbc.query(
        """
        SELECT column_name, data_type, is_nullable, column_default, character_maximum_length
        FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = ?
        ORDER BY ordinal_position
        """,
        rs -> {
          String column = rs.getString(1);
          String key = table + "." + column;
          if (VALID.containsKey(key)) {
            names.add(column);
            values.add(VALID.get(key));
          } else if (searched.contains(column)) {
            names.add(column);
            values.add(VALUE);
          } else if ("NO".equals(rs.getString(3)) && rs.getString(4) == null) {
            names.add(column);
            values.add(filler(rs.getString(2), (Integer) rs.getObject(5)));
          }
        },
        table);
    String sql =
        "INSERT INTO "
            + table
            + " ("
            + String.join(", ", names)
            + ") VALUES ("
            + "?, ".repeat(names.size() - 1)
            + "?)";
    jdbc.update(sql, values.toArray());
  }

  private static Object filler(String dataType, Integer maxLength) {
    return switch (dataType) {
      case "uuid" -> UUID.randomUUID();
      case "character varying", "text", "character" -> {
        String text = "zzz-" + UUID.randomUUID();
        yield maxLength == null ? text : text.substring(0, Math.min(text.length(), maxLength));
      }
      case "integer", "bigint", "smallint" -> 1;
      case "numeric", "double precision", "real" -> 1.0;
      case "boolean" -> Boolean.FALSE;
      case "timestamp with time zone", "timestamp without time zone" -> OffsetDateTime.now();
      default -> throw new IllegalStateException("No filler for the column type " + dataType);
    };
  }
}
