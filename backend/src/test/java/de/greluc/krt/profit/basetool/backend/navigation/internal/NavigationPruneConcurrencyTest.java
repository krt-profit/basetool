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

package de.greluc.krt.profit.basetool.backend.navigation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real-Postgres tests, with committed transactions, that a prune takes the member's root lock
 * before it reads: it never reverts a committed appearance change and never deadlocks with a layout
 * write (REQ-UI-032, REQ-UI-034).
 */
@SpringBootTest
class NavigationPruneConcurrencyTest {

  private static final String KEY = "orders.list";

  private static final int ROUNDS = 100;

  private static final long LOCK_WAIT_MILLIS = 400;

  @Autowired private NavigationAppearanceWriter appearances;
  @Autowired private NavigationPreferencesService preferences;
  @Autowired private DashboardService dashboards;
  @Autowired private UserRepository userRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  private UUID member() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("nav-prune-" + UUID.randomUUID());
    user.setRank(1);
    user.setInKeycloak(false);
    return userRepository.saveAndFlush(user).getId();
  }

  @Test
  @DisplayName("a prune waiting on a committing appearance write keeps the committed mode")
  void aPruneKeepsACommittedAppearanceChange() throws Exception {
    UUID owner = member();
    AppearanceResponse stored = appearances.replaceStartPage(owner, NavigationMode.MEMBER, KEY, 0L);
    CountDownLatch locked = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> writer =
          pool.submit(() ->
              new TransactionTemplate(transactionManager)
                  .executeWithoutResult(_ -> {
                    appearances.replace(owner, DesktopMode.RAIL, true, stored.version());
                    locked.countDown();
                    pause();
                  }));
      locked.await(30, TimeUnit.SECONDS);
      Future<Boolean> prune = pool.submit(() -> appearances.prune(owner, List.of(KEY)));

      writer.get(30, TimeUnit.SECONDS);
      boolean cleared = prune.get(30, TimeUnit.SECONDS);

      AppearanceResponse after = preferences.read(owner).appearance();
      assertThat(cleared).isTrue();
      assertThat(after.desktopMode()).isEqualTo(DesktopMode.RAIL);
      assertThat(after.startPageMember()).isNull();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("racing dashboard prunes and layout writes never deadlock")
  void dashboardPruneAndLayoutWriteSerialise() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < ROUNDS; round++) {
        UUID owner = member();
        DashboardResponse layout =
            dashboards.replaceLayout(
                owner,
                DashboardMode.MEMBER,
                new DashboardLayoutRequest(
                    List.of(
                        item(DashboardWidgetType.UPCOMING_DAYS),
                        item(DashboardWidgetType.NEXT_MISSION)),
                    0L));
        List<DashboardLayoutItem> reordered =
            List.of(
                new DashboardLayoutItem(
                    layout.widgets().get(1).id(),
                    DashboardWidgetType.NEXT_MISSION,
                    DashboardWidgetType.NEXT_MISSION.getDefaultColumn()),
                new DashboardLayoutItem(
                    layout.widgets().get(0).id(),
                    DashboardWidgetType.UPCOMING_DAYS,
                    DashboardWidgetType.UPCOMING_DAYS.getDefaultColumn()));
        CyclicBarrier start = new CyclicBarrier(2);
        Future<Boolean> write =
            pool.submit(() -> {
              start.await();
              return layoutWritten(owner, reordered, layout.version());
            });
        Future<Boolean> prune =
            pool.submit(() -> {
              start.await();
              dashboards.prune(
                  owner, DashboardMode.MEMBER, Set.of(DashboardWidgetType.UPCOMING_DAYS));
              return true;
            });

        assertThat(prune.get(30, TimeUnit.SECONDS)).as("round %d", round).isTrue();
        write.get(30, TimeUnit.SECONDS);
        assertThat(dashboards.read(owner, DashboardMode.MEMBER).widgets())
            .as("round %d", round)
            .noneMatch(widget -> widget.type() == DashboardWidgetType.UPCOMING_DAYS);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private boolean layoutWritten(UUID owner, List<DashboardLayoutItem> items, long version) {
    try {
      dashboards.replaceLayout(
          owner, DashboardMode.MEMBER, new DashboardLayoutRequest(items, version));
      return true;
    } catch (ObjectOptimisticLockingFailureException _) {
      return false;
    }
  }

  private static DashboardLayoutItem item(DashboardWidgetType type) {
    return new DashboardLayoutItem(null, type, type.getDefaultColumn());
  }

  private static void pause() {
    CompletableFuture.runAsync(
            () -> {}, CompletableFuture.delayedExecutor(LOCK_WAIT_MILLIS, TimeUnit.MILLISECONDS))
        .join();
  }
}
