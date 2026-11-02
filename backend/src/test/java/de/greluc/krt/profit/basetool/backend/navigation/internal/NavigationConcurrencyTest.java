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
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real-Postgres tests, with committed transactions, of the two concurrency rules of the navigation
 * stores: a page is never stored both as favourite and hidden, and a layout write never reverts a
 * committed option change (REQ-UI-032, REQ-UI-034).
 */
@SpringBootTest
class NavigationConcurrencyTest {

  private static final String KEY = "orders.list";

  private static final int ROUNDS = 300;

  @Autowired private NavigationPreferencesService preferences;
  @Autowired private DashboardService dashboards;
  @Autowired private DashboardWidgetRepository widgets;
  @Autowired private UserRepository userRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  private UUID member() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("nav-conc-" + UUID.randomUUID());
    user.setRank(1);
    user.setInKeycloak(false);
    return userRepository.saveAndFlush(user).getId();
  }

  @Test
  @DisplayName("racing favourite and hidden writes of one page store it in one section only")
  void racingWritesNeverStoreAPageInBothSections() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < ROUNDS; round++) {
        UUID owner = member();
        preferences.replaceFavourites(owner, new FavouritesUpdateRequest(List.of(), 0L));
        CyclicBarrier start = new CyclicBarrier(2);
        Future<Boolean> favourite =
            pool.submit(() -> {
              start.await();
              return succeeds(() ->
                  preferences.replaceFavourites(
                      owner, new FavouritesUpdateRequest(List.of(KEY), 1L)));
            });
        Future<Boolean> hidden =
            pool.submit(() -> {
              start.await();
              return succeeds(() ->
                  preferences.replaceHidden(owner, new HiddenUpdateRequest(List.of(KEY), 0L)));
            });

        boolean favouriteStored = favourite.get(30, TimeUnit.SECONDS);
        boolean hiddenStored = hidden.get(30, TimeUnit.SECONDS);

        NavigationPreferencesResponse stored = preferences.read(owner);
        assertThat(favouriteStored && hiddenStored).as("round %d", round).isFalse();
        assertThat(stored.favourites().keys().contains(KEY) && stored.hidden().keys().contains(KEY))
            .as("round %d", round)
            .isFalse();
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("a layout write on a stale snapshot does not revert a committed option change")
  void aLayoutWriteKeepsACommittedOptionChange() {
    UUID owner = member();
    DashboardResponse layout =
        dashboards.replaceLayout(
            owner,
            DashboardMode.MEMBER,
            new DashboardLayoutRequest(
                List.of(
                    new DashboardLayoutItem(
                        null,
                        DashboardWidgetType.UPCOMING_DAYS,
                        DashboardWidgetType.UPCOMING_DAYS.getDefaultColumn())),
                0L));
    DashboardWidgetResponse widget = layout.widgets().getFirst();
    TransactionTemplate outer = new TransactionTemplate(transactionManager);
    TransactionTemplate inner = new TransactionTemplate(transactionManager);
    inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

    outer.executeWithoutResult(_ -> {
      NavigationDashboardWidget snapshot = widgets.findById(widget.id()).orElseThrow();
      inner.executeWithoutResult(_ ->
          dashboards.replaceOptions(
              owner,
              DashboardMode.MEMBER,
              widget.id(),
              new DashboardOptionsRequest(null, 14, 10, null, widget.optionsVersion())));
      snapshot.moveTo(DashboardColumn.NARROW, 3);
      widgets.saveAndFlush(snapshot);
    });

    DashboardWidgetResponse stored =
        dashboards.read(owner, DashboardMode.MEMBER).widgets().getFirst();
    assertThat(stored.days()).isEqualTo(14);
    assertThat(stored.rows()).isEqualTo(10);
    assertThat(stored.column()).isEqualTo(DashboardColumn.NARROW);
  }

  private static boolean succeeds(Runnable write) {
    try {
      write.run();
      return true;
    } catch (BadRequestException _) {
      return false;
    }
  }
}
