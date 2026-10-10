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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;

import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeChangeResultDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeItemRef;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeLocationRef;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeStockChangeSet;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Concurrent stock change sets and undos of one member, on PostgreSQL: they never deadlock, and a
 * set or undo that waited for another sees what that one wrote (REQ-XCH-016, ADR-0229).
 */
@SpringBootTest
class ExchangeStockWriteConcurrencyIntegrationTest {

  private static final String CLIENT = "versekit-lock";
  private static final String OTHER_CLIENT = "versekit-lock-other";

  @Autowired private ExchangeStockWriteService service;
  @Autowired private ExchangeUndoService undoService;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private UserRepository userRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @MockitoSpyBean private ExchangeLocationResolver locationResolver;
  @MockitoSpyBean private ExchangeLiveSync liveSync;

  private final AtomicBoolean holdArmed = new AtomicBoolean();
  private final CountDownLatch holding = new CountDownLatch(1);

  private UUID member;
  private UUID material;
  private UUID location;
  private String locationName;
  private ExchangeClient client;

  @BeforeEach
  void setUp() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("lock-order-" + UUID.randomUUID());
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
        "lock-order-" + material);
    locationName = "lock-order-" + UUID.randomUUID();
    Location place = new Location();
    place.setName(locationName);
    location = locationRepository.saveAndFlush(place).getId();
    stock(1);
    stock(2);
    client = new ExchangeClient();
    client.setClientId(CLIENT);
    client.setDisplayName("VerseKit");
    client.setStatus(ExchangeClientStatus.ACTIVE);
    client.setCapabilities(EnumSet.of(ExchangeCapability.CONNECT, ExchangeCapability.STOCK_WRITE));
    client = clientRepository.saveAndFlush(client);
    doAnswer(
            invocation -> {
              if (holdArmed.compareAndSet(true, false)) {
                holding.countDown();
                awaitAnotherWaiter();
              }
              return invocation.callRealMethod();
            })
        .when(liveSync)
        .stockChanged(anyBoolean());
  }

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM inventory_item WHERE user_id = ?", member);
    jdbc.update("DELETE FROM app_user WHERE id = ?", member);
    jdbc.update("DELETE FROM material WHERE id = ?", material);
    jdbc.update("DELETE FROM location WHERE id = ?", location);
    clientRepository.delete(client);
  }

  @Test
  void overlappingSetsInOppositeOrdersBothFinish() throws Exception {
    CyclicBarrier bothBetweenTheirLots = new CyclicBarrier(2);
    ThreadLocal<Integer> resolved = ThreadLocal.withInitial(() -> 0);
    doAnswer(
            invocation -> {
              resolved.set(resolved.get() + 1);
              if (resolved.get() == 2) {
                awaitBriefly(bothBetweenTheirLots);
              }
              return invocation.callRealMethod();
            })
        .when(locationResolver)
        .resolve(any());

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<ExchangeChangeResultDto> first =
          pool.submit(() -> service.apply(caller(CLIENT, "A"), changeSet(1, 2)));
      Future<ExchangeChangeResultDto> second =
          pool.submit(() -> service.apply(caller(CLIENT, "B"), changeSet(2, 1)));

      assertThat(first.get(60, TimeUnit.SECONDS)).isNotNull();
      assertThat(second.get(60, TimeUnit.SECONDS)).isNotNull();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void theSameRiseOfALotFromTwoInstallationsAppliesOnce() throws Exception {
    List<ExchangeChangeResultDto> results =
        whileTheFirstHolds(
            () -> service.apply(caller(CLIENT, "A"), rise(1, "5", "6")),
            () -> service.apply(caller(CLIENT, "B"), rise(1, "5", "6")));

    assertOneAppliedAndOneConflicted(results);
    assertThat(lot(1)).isEqualByComparingTo("6");
  }

  @Test
  void theSameRiseOfAnEmptyLotFromTwoInstallationsAppliesOnce() throws Exception {
    List<ExchangeChangeResultDto> results =
        whileTheFirstHolds(
            () -> service.apply(caller(CLIENT, "A"), rise(9, "0", "1")),
            () -> service.apply(caller(CLIENT, "B"), rise(9, "0", "1")));

    assertOneAppliedAndOneConflicted(results);
    assertThat(lot(9)).isEqualByComparingTo("1");
  }

  @Test
  void anUndoWaitsForARunningWriteAndLeavesTheLotItChanged() throws Exception {
    service.apply(caller(CLIENT, "A"), rise(1, "5", "6"));

    List<Object> results =
        whileTheFirstHolds(
            () -> service.apply(caller(OTHER_CLIENT, "C"), rise(1, "6", "7")),
            () -> undoService.undo(member, CLIENT, Instant.now().minusSeconds(3600)));

    ExchangeUndoResultDto undo = (ExchangeUndoResultDto) results.get(1);
    assertThat(undo.restored()).isZero();
    assertThat(undo.skipped())
        .singleElement()
        .satisfies(
            skipped ->
                assertThat(skipped.reason()).isEqualTo(ExchangeUndoService.CHANGED_AFTERWARDS));
    assertThat(lot(1)).isEqualByComparingTo("7");
  }

  /**
   * Runs the first task until it has written its changes and holds it there; runs the second until
   * it waits for a lock, then lets the first commit.
   *
   * @param first the task that holds its locks
   * @param second the task that meets them
   * @param <T> the tasks' result type
   * @return both results, the first task's first
   * @throws Exception when a task fails or does not finish in time
   */
  private <T> @NotNull List<T> whileTheFirstHolds(
      @NotNull Callable<? extends T> first, @NotNull Callable<? extends T> second)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      holdArmed.set(true);
      Future<? extends T> holder = pool.submit(first);
      assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();
      Future<? extends T> waiter = pool.submit(second);
      T held = holder.get(60, TimeUnit.SECONDS);
      T waited = waiter.get(60, TimeUnit.SECONDS);
      return List.of(held, waited);
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * Asserts that the holding set applied its op and the waiting one was refused with {@code
   * VERSION_CONFLICT}.
   *
   * @param results the two results, the holding set's first
   */
  private static void assertOneAppliedAndOneConflicted(
      @NotNull List<ExchangeChangeResultDto> results) {
    assertThat(results).extracting(ExchangeChangeResultDto::applied).containsExactly(1, 0);
    assertThat(results.get(1).results())
        .singleElement()
        .satisfies(
            op -> assertThat(op.reason()).isEqualTo(ExchangeStockWriteService.VERSION_CONFLICT));
  }

  /**
   * Blocks until another session waits for a lot's advisory lock or row lock, or for at most 15
   * seconds.
   *
   * @throws Exception when the database cannot be read
   */
  private void awaitAnotherWaiter() throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) {
      try (Connection connection = dataSource.getConnection();
          PreparedStatement query =
              connection.prepareStatement(
                  """
                  SELECT count(*) FROM pg_stat_activity
                  WHERE wait_event_type = 'Lock' AND pid <> pg_backend_pid()
                    AND (query LIKE '%pg_advisory_xact_lock%' OR query LIKE '%inventory_item%')
                  """);
          ResultSet rows = query.executeQuery()) {
        rows.next();
        if (rows.getLong(1) > 0) {
          return;
        }
      }
      TimeUnit.MILLISECONDS.sleep(50);
    }
  }

  /**
   * Waits for the other thread at the barrier, and goes on alone when it does not come.
   *
   * @param barrier the barrier
   * @throws InterruptedException when the thread is interrupted
   */
  private static void awaitBriefly(@NotNull CyclicBarrier barrier) throws InterruptedException {
    try {
      barrier.await(3, TimeUnit.SECONDS);
    } catch (TimeoutException | BrokenBarrierException ignored) {
      barrier.reset();
    }
  }

  /**
   * Builds a change set that raises the lots of the given qualities from five to six, in that
   * order.
   *
   * @param qualities the lots' qualities, in op order
   * @return the change set
   */
  private @NotNull ExchangeStockChangeSet changeSet(int... qualities) {
    List<ExchangeStockChangeSet.Op> ops =
        Arrays.stream(qualities).mapToObj(quality -> op(quality, "5", "6")).toList();
    return new ExchangeStockChangeSet(ops, false);
  }

  /**
   * Builds a change set of one op that sets a lot from one quantity to another.
   *
   * @param quality the lot's quality
   * @param expected the quantity the client last saw
   * @param target the quantity to set
   * @return the change set
   */
  private @NotNull ExchangeStockChangeSet rise(
      int quality, @NotNull String expected, @NotNull String target) {
    return new ExchangeStockChangeSet(List.of(op(quality, expected, target)), false);
  }

  /**
   * Builds one op on the test material's lot of a quality at the test location.
   *
   * @param quality the lot's quality
   * @param expected the quantity the client last saw
   * @param target the quantity to set
   * @return the op
   */
  private @NotNull ExchangeStockChangeSet.Op op(
      int quality, @NotNull String expected, @NotNull String target) {
    return new ExchangeStockChangeSet.Op(
        "q" + quality,
        "set-quantity",
        new ExchangeItemRef(material.toString(), null, null, null, null, null, null),
        new ExchangeLocationRef(locationName, null),
        quality,
        false,
        new ExchangeStockChangeSet.Quantity(new BigDecimal(target), "SCU"),
        new ExchangeStockChangeSet.Quantity(new BigDecimal(expected), "SCU"),
        null);
  }

  /**
   * Builds the caller of one installation of the member.
   *
   * @param clientId the client
   * @param installation the installation's distinguishing letter
   * @return the caller
   */
  private @NotNull ExchangeCaller caller(@NotNull String clientId, @NotNull String installation) {
    return new ExchangeCaller(member, clientId, installation.repeat(43));
  }

  /**
   * Sums the member's personal lot of a quality.
   *
   * @param quality the quality
   * @return the amount, 0 for an empty lot
   */
  private @NotNull BigDecimal lot(int quality) {
    return jdbc.queryForObject(
        """
        SELECT COALESCE(SUM(amount), 0) FROM inventory_item
        WHERE user_id = ? AND personal AND material_id = ? AND quality = ?
        """,
        BigDecimal.class,
        member,
        material,
        quality);
  }

  /**
   * Seeds a personal lot of five SCU at a quality.
   *
   * @param quality the quality
   */
  private void stock(int quality) {
    jdbc.update(
        """
        INSERT INTO inventory_item (id, user_id, material_id, location_id, quality, amount,
                                    personal, stolen, version)
        VALUES (?, ?, ?, ?, ?, 5, true, false, 0)
        """,
        UUID.randomUUID(),
        member,
        material,
        location,
        quality);
  }
}
