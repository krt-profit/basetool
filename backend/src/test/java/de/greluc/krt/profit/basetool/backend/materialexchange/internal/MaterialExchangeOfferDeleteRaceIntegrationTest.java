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

package de.greluc.krt.profit.basetool.backend.materialexchange.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.internal.BulkCheckoutRequest;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryCheckoutService;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItem;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Two transactions on PostgreSQL: a Materialbörse release or deactivation on a Lager row, and a
 * Lager delete of the same row. Whichever runs first, the other waits for its row lock, so an offer
 * a delete cascades away carries its {@code MARKET_OFFER_REMOVED}, a release on a deleted row is
 * refused, and an offer deactivated before the delete is not reported as removed (REQ-MARKET-013,
 * ADR-0362).
 *
 * <p>Not {@code @Transactional}: every transaction commits, and the fixture is removed afterwards.
 */
@SpringBootTest
class MaterialExchangeOfferDeleteRaceIntegrationTest {

  /** How long a step may take to reach its lock wait or its end. */
  private static final Duration STEP_TIMEOUT = Duration.ofSeconds(30);

  /** How often the lock-wait probe looks at {@code pg_stat_activity}. */
  private static final Duration PROBE_INTERVAL = Duration.ofMillis(20);

  private static final double STOCK = 20.0;
  private static final double OFFERED = 5.0;

  @Autowired private MaterialExchangeService exchangeService;
  @Autowired private InventoryCheckoutService checkoutService;
  @Autowired private MaterialExchangeOfferRepository offerRepository;
  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbc;

  private final ExecutorService pool = Executors.newFixedThreadPool(2);

  private User owner;
  private User admin;
  private UUID unitId;
  private UUID materialId;
  private UUID locationId;
  private UUID rowId;

  @BeforeEach
  void setUp() {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    owner = user("race-owner-" + tag);
    admin = user("race-admin-" + tag);
    Squadron unit = new Squadron();
    unit.setName("Race " + tag);
    unit.setShorthand("R" + tag.substring(0, 4));
    unit.setProfitEligible(true);
    unitId = squadronRepository.save(unit).getId();
    Material material = new Material();
    material.setName("Race ore " + tag);
    material.setType(MaterialType.RAW);
    material.setQuantityType(QuantityType.SCU);
    materialId = materialRepository.save(material).getId();
    locationId = UUID.randomUUID();
    TestPlaces.insertLocation(jdbc, locationId, "Race depot " + tag);
    rowId =
        transactionTemplate.execute(_ -> {
          InventoryItem row = new InventoryItem();
          row.setUser(owner);
          row.setMaterial(materialRepository.getReferenceById(materialId));
          row.setLocation(locationRepository.getReferenceById(locationId));
          row.setOwningOrgUnit(squadronRepository.getReferenceById(unitId));
          row.setQuality(500);
          row.setAmount(STOCK);
          row.setPersonal(false);
          return inventoryItemRepository.save(row).getId();
        });
  }

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
    jdbc.update("DELETE FROM material_exchange_offer WHERE owner_id = ?", owner.getId());
    jdbc.update("DELETE FROM inventory_item WHERE user_id = ?", owner.getId());
    jdbc.update(
        "DELETE FROM audit_event WHERE actor_user_id IN (?, ?) OR target_user_id IN (?, ?)",
        owner.getId(),
        admin.getId(),
        owner.getId(),
        admin.getId());
    squadronRepository.deleteById(unitId);
    materialRepository.deleteById(materialId);
    UUID cityId =
        jdbc.queryForObject("SELECT city_id FROM location WHERE id = ?", UUID.class, locationId);
    jdbc.update("DELETE FROM location WHERE id = ?", locationId);
    jdbc.update("DELETE FROM city WHERE id = ?", cityId);
    userRepository.deleteById(owner.getId());
    userRepository.deleteById(admin.getId());
  }

  @Test
  void anOfferReleasedWhileTheWipeWaitsForTheRowIsAuditedAsRemoved() throws Exception {
    CountDownLatch released = new CountDownLatch(1);
    CountDownLatch commit = new CountDownLatch(1);
    Future<MaterialExchangeOfferDto> release =
        run(owner, Roles.KRT_MEMBER, null, () -> held(released, commit, this::release));
    await(released);
    int waitingBefore = lockWaiters();

    Future<Integer> wipe =
        run(admin, Roles.ADMIN, unitId, () -> checkoutService.deleteAllGlobalInventory());
    awaitLockWaiters(waitingBefore + 1);
    commit.countDown();

    UUID offerId = finish(release).id();
    assertThat(finish(wipe)).isEqualTo(1);
    assertThat(details("MARKET_OFFER_RELEASED", offerId)).hasSize(1);
    assertThat(details("MARKET_OFFER_REMOVED", offerId))
        .containsExactly("kind=MATERIAL reason=wipe");
    assertThat(offerRepository.existsById(offerId)).isFalse();
  }

  @Test
  void aReleaseOnARowTheWipeHoldsIsRefusedOnceTheWipeCommits() throws Exception {
    CountDownLatch wiped = new CountDownLatch(1);
    CountDownLatch commit = new CountDownLatch(1);
    Future<Integer> wipe =
        run(
            admin,
            Roles.ADMIN,
            unitId,
            () -> held(wiped, commit, checkoutService::deleteAllGlobalInventory));
    await(wiped);
    int waitingBefore = lockWaiters();

    Future<MaterialExchangeOfferDto> release = run(owner, Roles.KRT_MEMBER, null, this::release);
    awaitLockWaiters(waitingBefore + 1);
    commit.countDown();

    assertThat(finish(wipe)).isEqualTo(1);
    assertThatThrownBy(() -> finish(release))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(NotFoundException.class);
    assertThat(offersOnTheRow()).isZero();
    assertThat(eventsFor(owner, "MARKET_OFFER_RELEASED")).isZero();
  }

  @Test
  void anOfferReleasedWhileTheBulkCheckoutWaitsForTheRowIsAuditedAsRemoved() throws Exception {
    CountDownLatch released = new CountDownLatch(1);
    CountDownLatch commit = new CountDownLatch(1);
    Future<MaterialExchangeOfferDto> release =
        run(owner, Roles.KRT_MEMBER, null, () -> held(released, commit, this::release));
    await(released);
    int waitingBefore = lockWaiters();

    Future<Boolean> checkout = run(owner, Roles.KRT_MEMBER, null, this::bulkCheckout);
    awaitLockWaiters(waitingBefore + 1);
    commit.countDown();

    UUID offerId = finish(release).id();
    assertThat(finish(checkout)).isTrue();
    assertThat(details("MARKET_OFFER_REMOVED", offerId))
        .containsExactly("kind=MATERIAL reason=bulk-checkout");
    assertThat(offerRepository.existsById(offerId)).isFalse();
  }

  @Test
  void anOfferDeactivatedWhileTheBulkCheckoutWaitsForTheRowIsNotReportedAsRemoved()
      throws Exception {
    UUID offerId = activeOffer();
    CountDownLatch deactivated = new CountDownLatch(1);
    CountDownLatch commit = new CountDownLatch(1);
    Future<MaterialExchangeOfferDto> deactivate =
        run(
            owner,
            Roles.KRT_MEMBER,
            null,
            () -> held(deactivated, commit, () -> exchangeService.deactivate(offerId)));
    await(deactivated);
    int waitingBefore = lockWaiters();

    Future<Boolean> checkout = run(owner, Roles.KRT_MEMBER, null, this::bulkCheckout);
    awaitLockWaiters(waitingBefore + 1);
    commit.countDown();

    finish(deactivate);
    assertThat(finish(checkout)).isTrue();
    assertThat(details("MARKET_OFFER_DEACTIVATED", offerId)).hasSize(1);
    assertThat(details("MARKET_OFFER_REMOVED", offerId)).isEmpty();
    assertThat(offerRepository.existsById(offerId)).isFalse();
  }

  /**
   * Releases part of the fixture row as the signed-in owner.
   *
   * @return the released offer
   */
  private MaterialExchangeOfferDto release() {
    return exchangeService.release(new MaterialExchangeReleaseRequest(rowId, OFFERED, null));
  }

  /**
   * Books the fixture row out in full through the bulk checkout.
   *
   * @return {@code true} once the checkout committed
   */
  private boolean bulkCheckout() {
    checkoutService.bulkCheckout(new BulkCheckoutRequest(List.of(rowId)), owner.getId());
    return true;
  }

  /**
   * Commits an active material offer on the fixture row.
   *
   * @return the offer's id
   */
  private UUID activeOffer() {
    return transactionTemplate.execute(_ ->
        offerRepository
            .save(
                MaterialExchangeOffer.builder()
                    .kind(MaterialExchangeOfferKind.MATERIAL)
                    .inventoryItemId(rowId)
                    .owner(owner)
                    .owningOrgUnit(squadronRepository.getReferenceById(unitId))
                    .offeredAmount(OFFERED)
                    .status(MaterialExchangeOfferStatus.ACTIVE)
                    .releasedAt(Instant.now())
                    .build())
            .getId());
  }

  /**
   * Runs a step in a transaction it holds open: signals {@code done} once the step returned, and
   * commits only when {@code commit} is counted down.
   *
   * @param done counted down after the step
   * @param commit awaited before the commit
   * @param step the step
   * @param <T> the step's result
   * @return the step's result
   */
  private <T> T held(
      @NotNull CountDownLatch done, @NotNull CountDownLatch commit, @NotNull Supplier<T> step) {
    return transactionTemplate.execute(_ -> {
      T result = step.get();
      done.countDown();
      await(commit);
      return result;
    });
  }

  /**
   * Runs work on a worker thread as the given member, inside a request that pins the given org
   * unit.
   *
   * @param user the member
   * @param role the member's role code
   * @param activeUnit the org unit the request pins, or {@code null}
   * @param work the work
   * @param <T> the work's result
   * @return the pending result
   */
  private <T> Future<T> run(
      @NotNull User user, @NotNull String role, @Nullable UUID activeUnit, Supplier<T> work) {
    return pool.submit(() -> {
      MockHttpServletRequest request = new MockHttpServletRequest();
      if (activeUnit != null) {
        request.addHeader(RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER, activeUnit.toString());
      }
      RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
      SecurityContextHolder.getContext()
          .setAuthentication(
              new JwtAuthenticationToken(
                  Jwt.withTokenValue("race")
                      .header("alg", "none")
                      .subject(user.getId().toString())
                      .build(),
                  List.of(new SimpleGrantedAuthority(Roles.authority(role)))));
      try {
        return work.get();
      } finally {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
      }
    });
  }

  /**
   * Waits for a worker's result.
   *
   * @param future the worker
   * @param <T> the result
   * @return the result
   * @throws Exception when the worker failed or did not finish in time
   */
  private static <T> T finish(@NotNull Future<T> future) throws Exception {
    return future.get(STEP_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
  }

  /**
   * Waits for a latch, failing the test when it is not counted down in time.
   *
   * @param latch the latch
   */
  private static void await(@NotNull CountDownLatch latch) {
    try {
      assertThat(latch.await(STEP_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
          .as("step reached in time")
          .isTrue();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  /**
   * Counts the sessions of this database that wait for a lock.
   *
   * @return the waiting sessions
   */
  private int lockWaiters() {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                + " AND datname = current_database()",
            Integer.class);
    return count == null ? 0 : count;
  }

  /**
   * Waits until at least {@code expected} sessions wait for a lock.
   *
   * @param expected the waiting sessions to reach
   * @throws InterruptedException when the probe is interrupted
   */
  private void awaitLockWaiters(int expected) throws InterruptedException {
    Instant deadline = Instant.now().plus(STEP_TIMEOUT);
    while (lockWaiters() < expected && Instant.now().isBefore(deadline)) {
      Thread.sleep(PROBE_INTERVAL);
    }
    assertThat(lockWaiters())
        .as("the second transaction waits for the row")
        .isGreaterThanOrEqualTo(expected);
  }

  /**
   * Reads the details of the audit rows of one type for one offer.
   *
   * @param type the event type
   * @param offerId the offer
   * @return the details, one per row
   */
  private List<String> details(String type, UUID offerId) {
    return jdbc.queryForList(
        "SELECT details FROM audit_event WHERE event_type = ? AND subject_id = ?",
        String.class,
        type,
        offerId);
  }

  /**
   * Counts the audit rows of one type recorded by a member.
   *
   * @param actor the member
   * @param type the event type
   * @return the rows
   */
  private int eventsFor(@NotNull User actor, String type) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM audit_event WHERE event_type = ? AND actor_user_id = ?",
            Integer.class,
            type,
            actor.getId());
    return count == null ? 0 : count;
  }

  /**
   * Counts the offers that reference the fixture row.
   *
   * @return the offers
   */
  private int offersOnTheRow() {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM material_exchange_offer WHERE inventory_item_id = ?",
            Integer.class,
            rowId);
    return count == null ? 0 : count;
  }

  /**
   * Commits a member.
   *
   * @param username the username
   * @return the member
   */
  private User user(String username) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username);
    return userRepository.save(user);
  }
}
