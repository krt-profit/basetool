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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeCaller;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeJournalAction;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeJournalService;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.EarmarkChange;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.EarmarkDimension;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolAllocation;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolEntryChange;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolOutcome;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolRefusal;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolRestore;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolRestoreResult;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLotWritePlans;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The Lager's pool-entry commands look an earmark's internal mission up without a bound request and
 * whatever the caller may read (REQ-INV-027): an admin's bulk undo of a connected application's
 * writes, which runs on the bulk undo executor (REQ-XCH-034, REQ-XCH-046), sets the earmark back,
 * and an earmark write labels its audit event with the mission's name. Writes commit.
 */
@SpringBootTest
class InventoryPoolEarmarkWithoutRequestIntegrationTest {

  /** How long one command may run on its worker thread. */
  private static final long TIMEOUT_SECONDS = 30;

  /** The quality of every seeded entry. */
  private static final int QUALITY = 1;

  /** The amount every mission earmark of these tests holds. */
  private static final double EARMARKED = 4.0;

  @Autowired private PoolEntryCommands poolEntryCommands;
  @Autowired private StockLotWritePlans lotPlans;
  @Autowired private ExchangeJournalService journal;
  @Autowired private AuthHelperService authHelperService;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbc;

  private final List<UUID> users = new ArrayList<>();
  private final List<UUID> orgUnits = new ArrayList<>();
  private final List<UUID> missions = new ArrayList<>();
  private final List<UUID> entries = new ArrayList<>();

  private UUID pool;
  private UUID otherUnit;
  private UUID holder;
  private UUID admin;
  private UUID writer;
  private UUID material;
  private UUID location;

  @BeforeEach
  void setUp() {
    pool = orgUnit("Pool");
    otherUnit = orgUnit("Other");
    holder = user("holder");
    admin = user("admin");
    writer = user("writer");
    joins(holder, pool, false);
    joins(writer, pool, true);
    material = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,
                              is_job_order, is_visible, source_systems)
        VALUES (?, ?, 'RAW', 'SCU', false, false, true, 'UEX_ONLY')
        """,
        material,
        "Earmark " + material);
    location = UUID.randomUUID();
    TestPlaces.insertLocation(jdbc, location, "Earmark " + location);
  }

  @AfterEach
  void tearDown() {
    entries.forEach(id -> jdbc.update("DELETE FROM audit_event WHERE subject_id = ?", id));
    for (UUID id : users) {
      jdbc.update("DELETE FROM exchange_journal WHERE user_id = ?", id);
      jdbc.update("DELETE FROM notification WHERE recipient_user_id = ?", id);
      jdbc.update("DELETE FROM inventory_item WHERE user_id = ?", id);
      jdbc.update("DELETE FROM org_unit_membership WHERE user_id = ?", id);
    }
    missions.forEach(id -> jdbc.update("DELETE FROM mission WHERE id = ?", id));
    users.forEach(id -> jdbc.update("DELETE FROM app_user WHERE id = ?", id));
    orgUnits.forEach(id -> jdbc.update("DELETE FROM org_unit WHERE id = ?", id));
    jdbc.update("DELETE FROM material WHERE id = ?", material);
    jdbc.update("DELETE FROM location WHERE id = ?", location);
  }

  @Test
  void anAdminsUndoSetsBackAnEarmarkOnAnInternalMissionOfThePool() throws Exception {
    UUID mission = internalMission(pool, "Secret-Op-" + UUID.randomUUID());
    UUID entry = stock(10);
    PoolEntryChange added = earmark(entry, 0, EarmarkChange.ADD, mission, EARMARKED);
    PoolEntryChange removed =
        earmark(
            entry,
            Objects.requireNonNull(added.after()).version(),
            EarmarkChange.REMOVE,
            mission,
            null);
    StockLotWritePlans.EntryRestore undo =
        new StockLotWritePlans.EntryRestore(
            0,
            journaled(entry, removed),
            new PoolRestore(entry, removed.after(), removed.before()));

    List<PoolRestoreResult> results =
        withoutRequest(
            caller(admin, Roles.ADMIN),
            () -> {
              var prepared =
                  lotPlans.prepareLotBatch(
                      writer, List.of(), List.of(undo), List.of(undo), List.of());
              var confirmed = lotPlans.confirmLotBatch(prepared, List.of(undo), List.of(), false);
              return lotPlans.executePreparedEntryRestores(confirmed);
            });

    assertThat(results).containsExactly(PoolRestoreResult.RESTORED);
    assertThat(amountOf(entry)).isEqualTo(10);
    assertThat(missionEarmarks(entry, mission)).containsExactly(EARMARKED);
  }

  @Test
  void rejectsNewEarmarksOnHiddenAndUnknownMissionsTheSameWay() throws Exception {
    UUID hidden = internalMission(otherUnit, "Hidden mission");
    UUID entry = stock(10);
    for (UUID target : List.of(hidden, UUID.randomUUID())) {
      PoolOutcome outcome =
          withoutRequest(
              caller(writer, Roles.KRT_MEMBER),
              () ->
                  poolEntryCommands.allocate(
                      new PoolAllocation(
                          entry,
                          pool,
                          0,
                          EarmarkChange.ADD,
                          EarmarkDimension.MISSION,
                          target,
                          EARMARKED,
                          QuantityType.SCU.name()),
                      false));
      assertThat(outcome.refusal()).isEqualTo(PoolRefusal.EARMARK_TARGET_INVALID);
    }
    assertThat(missionEarmarks(entry, hidden)).isEmpty();
  }

  @Test
  void anExistingHiddenEarmarkCanBeChangedWithoutARequest() throws Exception {
    String name = "Secret-Op-" + UUID.randomUUID();
    UUID mission = internalMission(otherUnit, name);
    UUID entry = stock(10);
    jdbc.update(
        "INSERT INTO inventory_item_mission_allocation"
            + " (id, inventory_item_id, mission_id, amount, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, now(), now())",
        UUID.randomUUID(),
        entry,
        mission,
        2.0);

    earmark(entry, 0, EarmarkChange.CHANGE, mission, EARMARKED);

    assertThat(missionEarmarks(entry, mission)).containsExactly(EARMARKED);
    assertThat(
            jdbc.queryForList(
                "SELECT details FROM audit_event WHERE subject_id = ?"
                    + " AND event_type = 'INVENTORY_ALLOCATION_CHANGED'",
                String.class,
                entry))
        .singleElement()
        .satisfies(details -> assertThat(details).contains("ref=" + name));
  }

  /**
   * Runs work in a transaction of its own on a fresh thread, which no request is bound to, under an
   * authentication, the way the bulk undo executor runs a member's undo.
   *
   * @param authentication the authentication the work runs under
   * @param work the work
   * @param <T> the work's result type
   * @return the work's result
   * @throws Exception when the work fails or does not end in time
   */
  private <T> T withoutRequest(@NotNull Authentication authentication, @NotNull Supplier<T> work)
      throws Exception {
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      return worker
          .submit(() -> {
            assertThat(RequestContextHolder.getRequestAttributes())
                .as("the request bound to the worker thread")
                .isNull();
            AtomicReference<T> result = new AtomicReference<>();
            authHelperService.runAs(
                authentication, () -> result.set(transactionTemplate.execute(_ -> work.get())));
            return result.get();
          })
          .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } finally {
      worker.shutdownNow();
    }
  }

  /**
   * Builds a member's or an admin's bearer authentication.
   *
   * @param user the member
   * @param role the role code the token carries
   * @return the authentication
   */
  private static @NotNull Authentication caller(@NotNull UUID user, @NotNull String role) {
    Jwt token =
        Jwt.withTokenValue("pool-earmark")
            .header("alg", "none")
            .subject(user.toString())
            .claim("azp", "basetool-frontend")
            .build();
    return new JwtAuthenticationToken(
        token, List.of(new SimpleGrantedAuthority(Roles.authority(role))));
  }

  /**
   * Writes a mission earmark on an entry as the pool's Logistician, as a connected application of
   * theirs writes it, in a committed transaction of its own.
   *
   * @param entry the entry
   * @param version the entry's version the write names
   * @param change whether the earmark is added or removed
   * @param mission the mission
   * @param amount the earmarked amount, or {@code null} for a removal
   * @return the entry's change
   * @throws Exception when the write fails or does not end in time
   */
  private @NotNull PoolEntryChange earmark(
      @NotNull UUID entry,
      long version,
      @NotNull EarmarkChange change,
      @NotNull UUID mission,
      @Nullable Double amount)
      throws Exception {
    PoolOutcome outcome =
        withoutRequest(
            caller(writer, Roles.KRT_MEMBER),
            () ->
                poolEntryCommands.allocate(
                    new PoolAllocation(
                        entry,
                        pool,
                        version,
                        change,
                        EarmarkDimension.MISSION,
                        mission,
                        amount,
                        amount == null ? null : QuantityType.SCU.name()),
                    false));
    assertThat(outcome.refusal()).isNull();
    return outcome.changes().getFirst();
  }

  /**
   * Journals the Logistician's earmark removal as their connected application's write, the entry a
   * bulk undo names.
   *
   * @param entry the entry
   * @param removal the removal's change
   * @return the journal entry's id
   */
  private @NotNull UUID journaled(@NotNull UUID entry, @NotNull PoolEntryChange removal) {
    return Objects.requireNonNull(
        transactionTemplate.execute(_ ->
            journal
                .record(
                    new ExchangeCaller(writer, "pool-earmark", "pool-earmark-installation"),
                    UUID.randomUUID(),
                    ExchangeJournalAction.ORG_STOCK_EARMARK_REMOVE,
                    entry.toString(),
                    false,
                    removal.before(),
                    removal.after())
                .getId()));
  }

  /**
   * Reads an entry's amount.
   *
   * @param entry the entry
   * @return its amount
   */
  private double amountOf(@NotNull UUID entry) {
    Double amount =
        jdbc.queryForObject("SELECT amount FROM inventory_item WHERE id = ?", Double.class, entry);
    return amount == null ? 0 : amount;
  }

  /**
   * Reads the amounts an entry holds for one mission.
   *
   * @param entry the entry
   * @param mission the mission
   * @return the earmarks' amounts, empty when the entry holds none for it
   */
  private @NotNull List<Double> missionEarmarks(@NotNull UUID entry, @NotNull UUID mission) {
    return jdbc.queryForList(
        "SELECT amount FROM inventory_item_mission_allocation"
            + " WHERE inventory_item_id = ? AND mission_id = ?",
        Double.class,
        entry,
        mission);
  }

  /**
   * Inserts and commits a shared entry of the holder in the pool, at version 0.
   *
   * @param amount the amount
   * @return the entry's id
   */
  private @NotNull UUID stock(double amount) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO inventory_item (id, user_id, material_id, location_id, quality, amount,
                                    personal, stolen, owning_org_unit_id, version)
        VALUES (?, ?, ?, ?, ?, ?, false, false, ?, 0)
        """,
        id,
        holder,
        material,
        location,
        QUALITY,
        amount,
        pool);
    entries.add(id);
    return id;
  }

  /**
   * Seeds an internal mission of an org unit.
   *
   * @param unit the owning org unit
   * @param name the mission's name
   * @return its id
   */
  private @NotNull UUID internalMission(@NotNull UUID unit, @NotNull String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO mission (id, name, version, is_internal, owning_org_unit_id)"
            + " VALUES (?, ?, 0, TRUE, ?)",
        id,
        name,
        unit);
    missions.add(id);
    return id;
  }

  /**
   * Seeds an active Spezialkommando named {@code <name> <id>}.
   *
   * @param name the visible part of the name
   * @return its id
   */
  private @NotNull UUID orgUnit(@NotNull String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active,"
            + " is_profit_eligible) VALUES (?, 'SPECIAL_COMMAND', ?, ?, TRUE, FALSE)",
        id,
        name + " " + id,
        "E" + id.toString().substring(0, 8));
    orgUnits.add(id);
    return id;
  }

  /**
   * Makes a member a direct member of an org unit.
   *
   * @param member the member
   * @param orgUnit the org unit
   * @param logistician whether the member is a Logistician of it
   */
  private void joins(@NotNull UUID member, @NotNull UUID orgUnit, boolean logistician) {
    jdbc.update(
        "INSERT INTO org_unit_membership (user_id, org_unit_id, role, is_logistician)"
            + " VALUES (?, ?, 'MEMBER', ?)",
        member,
        orgUnit,
        logistician);
  }

  /**
   * Seeds a user.
   *
   * @param name the visible part of the username
   * @return the user's id
   */
  private @NotNull UUID user(@NotNull String name) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("earmark-" + name + "-" + UUID.randomUUID());
    UUID id = userRepository.saveAndFlush(user).getId();
    users.add(id);
    return id;
  }
}
