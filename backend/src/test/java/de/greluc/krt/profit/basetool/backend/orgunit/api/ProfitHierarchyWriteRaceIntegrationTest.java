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

package de.greluc.krt.profit.basetool.backend.orgunit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderQueryService;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderService;
import de.greluc.krt.profit.basetool.backend.joborder.internal.UpdateJobOrderStatusDto;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.support.TransactionTemplate;

/** Races a real hierarchy change against the public order mutation API on PostgreSQL. */
@SpringBootTest
class ProfitHierarchyWriteRaceIntegrationTest {

  private static final int TIMEOUT_SECONDS = 20;

  @Autowired private OrgHierarchyService hierarchy;
  @Autowired private OrgUnitRepository units;
  @Autowired private SquadronRepository squadrons;
  @Autowired private JobOrderRepository orders;
  @Autowired private JobOrderService orderCommands;
  @Autowired private JobOrderQueryService orderReads;
  @Autowired private TransactionTemplate transactions;

  @Test
  @WithMockUser(roles = "ADMIN")
  void anInflightHierarchyChangeCannotBeBypassedByAStaleOrderWrite() throws Exception {
    Fixture fixture = transactions.execute(_ -> seed());
    CountDownLatch changed = new CountDownLatch(1);
    CountDownLatch commitHierarchy = new CountDownLatch(1);
    CountDownLatch writerStarted = new CountDownLatch(1);
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var hierarchyWrite =
          pool.submit(() -> {
            SecurityContextHolder.getContext().setAuthentication(authentication);
            try {
              transactions.executeWithoutResult(_ -> {
                hierarchy.setParent(fixture.unitId(), null, fixture.unitVersion());
                changed.countDown();
                await(commitHierarchy);
              });
            } finally {
              SecurityContextHolder.clearContext();
            }
          });
      assertThat(changed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
      var orderWrite =
          pool.submit(() -> {
            SecurityContextHolder.getContext().setAuthentication(authentication);
            try {
              writerStarted.countDown();
              assertThatThrownBy(() ->
                      orderCommands.updateJobOrderStatus(
                          fixture.orderId(),
                          new UpdateJobOrderStatusDto(
                              JobOrderStatus.IN_PROGRESS, fixture.orderVersion())))
                  .isInstanceOf(BadRequestException.class)
                  .hasMessage("orders.responsibility.invalid");
            } finally {
              SecurityContextHolder.clearContext();
            }
          });
      assertThat(writerStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> orderWrite.get(300, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);
      commitHierarchy.countDown();
      hierarchyWrite.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      orderWrite.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      var observed = transactions.execute(_ -> orderReads.getJobOrderById(fixture.orderId()));
      assertThat(observed.status()).isEqualTo(JobOrderStatus.OPEN);
      assertThat(observed.responsibilityInvalid()).isTrue();
    } finally {
      commitHierarchy.countDown();
      transactions.executeWithoutResult(_ -> {
        orders.deleteById(fixture.orderId());
        orders.flush();
        squadrons.deleteById(fixture.unitId());
      });
    }
  }

  private Fixture seed() {
    String tag = UUID.randomUUID().toString();
    List<UUID> roots = units.findActiveProfitRootIds();
    OrgUnit root =
        roots.isEmpty()
            ? hierarchy.createBereich(
                "Race Profit " + tag, "P" + tag, null, null, Department.PROFIT)
            : units.findById(roots.getFirst()).orElseThrow();
    Squadron unit = new Squadron();
    unit.setName("Race " + tag);
    unit.setShorthand("R" + tag);
    unit.setParent(root);
    unit = squadrons.saveAndFlush(unit);
    JobOrder order =
        orders.saveAndFlush(
            JobOrder.builder()
                .responsibleOrgUnit(unit)
                .requestingOrgUnit(unit)
                .handle("Race")
                .build());
    return new Fixture(unit.getId(), unit.getVersion(), order.getId(), order.getVersion());
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        throw new AssertionError("Hierarchy test coordination timed out");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError(exception);
    }
  }

  private record Fixture(UUID unitId, Long unitVersion, UUID orderId, Long orderVersion) {}
}
