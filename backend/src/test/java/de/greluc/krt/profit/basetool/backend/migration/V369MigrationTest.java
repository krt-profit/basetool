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

package de.greluc.krt.profit.basetool.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.catalogue.api.CityRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Migration test for {@code V369__add_refinery_order_provenance.sql}: the {@code last_changed_at}
 * backfill touches every existing order without announcing it in the exchange change feed
 * (REQ-XCH-051).
 */
@SpringBootTest
@Transactional
class V369MigrationTest {

  private static final String SCRIPT = "/db/migration/V369__add_refinery_order_provenance.sql";

  private static final String BACKFILL = "UPDATE refinery_order";

  private static final String FEED_TRIGGER = "trg_refinery_order_exchange_change";

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private UserRepository userRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private CityRepository cityRepository;

  private UUID order;

  @BeforeEach
  void seedAnOrderWithoutChangeRows() {
    User owner = new User();
    owner.setId(UUID.randomUUID());
    owner.setUsername("v369-owner");
    owner.setApprovalStatus(ApprovalStatus.ACTIVE);
    owner.setInKeycloak(true);
    UUID ownerId = userRepository.saveAndFlush(owner).getId();
    Location location = new Location();
    location.setName("v369-refinery");
    UUID locationId =
        locationRepository.saveAndFlush(TestPlaces.onOwnCity(location, cityRepository)).getId();
    order = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO refinery_order (id, version, owner_id, location_id, last_changed_at)"
            + " VALUES (?, 0, ?, ?, now())",
        order,
        ownerId,
        locationId);
    jdbcTemplate.update("DELETE FROM exchange_change WHERE entity_key = ?", order.toString());
  }

  @Test
  void aBareBackfillWouldAnnounceTheOrder() {
    jdbcTemplate.execute(
        "UPDATE refinery_order SET last_changed_at = COALESCE(updated_at, created_at, now())");

    assertThat(changeRows()).isEqualTo(1);
  }

  @Test
  void theMigrationsBackfillAnnouncesNoOrder_andLeavesTheFeedTriggerEnabled() throws IOException {
    runBackfillStatements();

    assertThat(changeRows()).isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT tgenabled FROM pg_trigger WHERE tgname = ?", String.class, FEED_TRIGGER))
        .isEqualTo("O");
  }

  private int changeRows() {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM exchange_change WHERE resource = 'REFINERY_ORDER' AND entity_key = ?",
        Integer.class,
        order.toString());
  }

  /** Runs the script's backfill and its trigger switches in order; the columns exist already. */
  private void runBackfillStatements() throws IOException {
    try (InputStream in = V369MigrationTest.class.getResourceAsStream(SCRIPT)) {
      assertThat(in).isNotNull();
      String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      List<String> statements =
          Arrays.stream(sql.split(";"))
              .filter(s -> s.contains(BACKFILL) || s.contains("TRIGGER"))
              .toList();
      assertThat(statements).hasSize(3);
      statements.forEach(jdbcTemplate::execute);
    }
  }
}
