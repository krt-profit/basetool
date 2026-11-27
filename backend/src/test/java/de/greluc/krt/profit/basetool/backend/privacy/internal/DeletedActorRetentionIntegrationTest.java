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

package de.greluc.krt.profit.basetool.backend.privacy.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.bank.internal.BankLedgerNameRetention;
import de.greluc.krt.profit.basetool.backend.blueprint.internal.DefaultBlueprintActorNameRetention;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.CatalogueActorNameRetention;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.PatchChangeActorNameRetention;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The name retention's areas for deleted accounts against Postgres (REQ-DATA-047): names in the
 * bank ledger and actor ids on retained acts.
 */
@SpringBootTest
@Transactional
class DeletedActorRetentionIntegrationTest {

  private static final Instant CUTOFF = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant OLD = CUTOFF.minus(30, ChronoUnit.DAYS);
  private static final Instant RECENT = CUTOFF.plus(30, ChronoUnit.DAYS);
  private static final String SENTINEL = HandleAnonymisation.SENTINEL;
  private static final UUID SENTINEL_ID = HandleAnonymisation.SENTINEL_ID;

  @Autowired private BankLedgerNameRetention bankLedgerNameRetention;
  @Autowired private CatalogueActorNameRetention catalogueActorNameRetention;
  @Autowired private PatchChangeActorNameRetention patchChangeActorNameRetention;
  @Autowired private DefaultBlueprintActorNameRetention defaultBlueprintActorNameRetention;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbc;

  private String tag;
  private UUID living;
  private UUID deleted;
  private int labelCounter;

  @BeforeEach
  void setUp() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("living-" + tag);
    user.setRank(1);
    user.setInKeycloak(true);
    living = userRepository.saveAndFlush(user).getId();
    deleted = UUID.randomUUID();
  }

  @Test
  void theNameOfADeletedAccountLeavesOldBookingsAndIdleHolders() {
    UUID oldBooking = booking(OLD, null, "Gone-" + tag);
    UUID recentBooking = booking(RECENT, null, "Gone-" + tag);
    UUID linkedBooking = booking(OLD, living, "living-" + tag);
    UUID idleHolder = holder(null, "Gone-" + tag, OLD);
    UUID activeHolder = holder(null, "Active-" + tag, OLD);
    jdbc.update(
        "INSERT INTO bank_holder_posting (id, transaction_id, holder_id, amount, created_at)"
            + " VALUES (?, ?, ?, 100, ?)",
        UUID.randomUUID(),
        recentBooking,
        activeHolder,
        Timestamp.from(RECENT));
    UUID linkedHolder = holder(living, "living-" + tag, OLD);

    assertThat(bankLedgerNameRetention.removeNamesBefore(CUTOFF)).isGreaterThanOrEqualTo(2);

    assertThat(text("bank_transaction", "counterparty_handle", oldBooking)).isEqualTo(SENTINEL);
    assertThat(text("bank_transaction", "counterparty_handle", recentBooking))
        .isEqualTo("Gone-" + tag);
    assertThat(text("bank_transaction", "counterparty_handle", linkedBooking))
        .isEqualTo("living-" + tag);
    assertThat(text("bank_holder", "handle", idleHolder)).isEqualTo(SENTINEL);
    assertThat(text("bank_holder", "handle", activeHolder)).isEqualTo("Active-" + tag);
    assertThat(text("bank_holder", "handle", linkedHolder)).isEqualTo("living-" + tag);
  }

  @Test
  void theIdOfADeletedAdminLeavesOldCatalogueActs() {
    UUID oldRun = importRun(deleted, OLD);
    UUID recentRun = importRun(deleted, RECENT);
    UUID livingRun = importRun(living, OLD);
    UUID oldCase = reviewCase(deleted, OLD, oldRun);
    UUID livingCase = reviewCase(living, OLD, livingRun);

    assertThat(catalogueActorNameRetention.removeNamesBefore(CUTOFF)).isGreaterThanOrEqualTo(2);

    assertThat(id("p4k_import_run", "created_by", oldRun)).isEqualTo(SENTINEL_ID);
    assertThat(id("p4k_import_run", "created_by", recentRun)).isEqualTo(deleted);
    assertThat(id("p4k_import_run", "created_by", livingRun)).isEqualTo(living);
    assertThat(id("p4k_review_case", "resolved_by", oldCase)).isEqualTo(SENTINEL_ID);
    assertThat(id("p4k_review_case", "resolved_by", livingCase)).isEqualTo(living);
  }

  @Test
  void theIdOfADeletedAdminLeavesOldPatchChanges() {
    UUID old = patchChange(deleted, OLD);
    UUID recent = patchChange(deleted, RECENT);
    UUID livingChange = patchChange(living, OLD);

    assertThat(patchChangeActorNameRetention.removeNamesBefore(CUTOFF)).isGreaterThanOrEqualTo(1);

    assertThat(id("patch_change", "triggered_by", old)).isEqualTo(SENTINEL_ID);
    assertThat(id("patch_change", "phase_ended_by", old)).isEqualTo(SENTINEL_ID);
    assertThat(id("patch_change", "triggered_by", recent)).isEqualTo(deleted);
    assertThat(id("patch_change", "triggered_by", livingChange)).isEqualTo(living);
  }

  @Test
  void theIdOfADeletedAdminLeavesOldDefaultBlueprints() {
    UUID old = defaultBlueprint(deleted.toString(), OLD);
    UUID recent = defaultBlueprint(deleted.toString(), RECENT);
    UUID livingDefault = defaultBlueprint(living.toString(), OLD);
    UUID seeded = defaultBlueprint("system", OLD);

    assertThat(defaultBlueprintActorNameRetention.removeNamesBefore(CUTOFF))
        .isGreaterThanOrEqualTo(1);

    assertThat(text("default_blueprint", "created_by", old)).isEqualTo(SENTINEL);
    assertThat(text("default_blueprint", "created_by", recent)).isEqualTo(deleted.toString());
    assertThat(text("default_blueprint", "created_by", livingDefault)).isEqualTo(living.toString());
    assertThat(text("default_blueprint", "created_by", seeded)).isEqualTo("system");
  }

  private UUID booking(Instant createdAt, UUID counterparty, String handle) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO bank_transaction (id, type, created_at, counterparty_user_id,"
            + " counterparty_handle) VALUES (?, 'DEPOSIT', ?, ?, ?)",
        id,
        Timestamp.from(createdAt),
        counterparty,
        handle);
    return id;
  }

  private UUID holder(UUID user, String handle, Instant updatedAt) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO bank_holder (id, version, created_at, updated_at, user_id, handle)"
            + " VALUES (?, 0, ?, ?, ?, ?)",
        id,
        Timestamp.from(updatedAt),
        Timestamp.from(updatedAt),
        user,
        handle);
    return id;
  }

  private UUID importRun(UUID admin, Instant appliedAt) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO p4k_import_run (id, channel, build_branch, build_version, change_number,"
            + " build_id, created_by, applied_at) VALUES (?, 'LIVE', 'sc-alpha-4.4', '4.4.0', 1,"
            + " ?, ?, ?)",
        id,
        "build-" + tag,
        admin,
        Timestamp.from(appliedAt));
    return id;
  }

  private UUID reviewCase(UUID admin, Instant resolvedAt, UUID run) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO p4k_review_case (id, kind, guid, reason, status, resolution, resolved_by,"
            + " resolved_at, first_run_id, last_run_id) VALUES (?, 'ITEM', ?,"
            + " 'UNCLEAR_PLAYABILITY', 'REVIEWED', 'KEEP', ?, ?, ?, ?)",
        id,
        UUID.randomUUID(),
        admin,
        Timestamp.from(resolvedAt),
        run,
        run);
    return id;
  }

  private UUID patchChange(UUID admin, Instant at) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO patch_change (id, version, label, kind, live_at, triggered_at, triggered_by,"
            + " cancel_refinery_orders, currency_wipe, home_location_prompt, phase_ended_at,"
            + " phase_ended_by) VALUES (?, 0, ?, 'REBUILD', ?, ?, ?, false, false, true, ?, ?)",
        id,
        label(),
        Timestamp.from(at),
        Timestamp.from(at),
        admin,
        Timestamp.from(at),
        admin);
    return id;
  }

  /** A unique patch label of the form the label check accepts (`major.minor.patch`). */
  private String label() {
    return "9." + labelCounter++ + "." + Math.abs(tag.hashCode() % 1_000_000);
  }

  private UUID defaultBlueprint(String createdBy, Instant createdAt) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO default_blueprint (id, product_key, product_name, created_by, created_at,"
            + " updated_at) VALUES (?, ?, 'Retention Default', ?, ?, ?)",
        id,
        "retention-" + id,
        createdBy,
        Timestamp.from(createdAt),
        Timestamp.from(createdAt));
    return id;
  }

  private String text(String table, String column, UUID id) {
    return jdbc.queryForObject(
        "SELECT " + column + " FROM " + table + " WHERE id = ?", String.class, id);
  }

  private UUID id(String table, String column, UUID id) {
    return jdbc.queryForObject(
        "SELECT " + column + " FROM " + table + " WHERE id = ?", UUID.class, id);
  }
}
