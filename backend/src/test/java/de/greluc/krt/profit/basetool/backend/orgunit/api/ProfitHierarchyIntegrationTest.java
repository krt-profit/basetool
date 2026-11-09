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

import de.greluc.krt.profit.basetool.backend.kernel.DuplicateEntityException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Exercises current Profit eligibility through the organisational module API. */
@SpringBootTest
@Transactional
class ProfitHierarchyIntegrationTest {

  @Autowired private OrgHierarchyService hierarchy;
  @Autowired private ProfitHierarchyService profitHierarchy;
  @Autowired private SquadronRepository squadrons;
  @Autowired private OrgUnitRepository units;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void activeHierarchyAdmitsTheProfitAreaAndItsChildrenWithoutAnIndependentSwitch() {
    String tag = UUID.randomUUID().toString();
    OrgUnit root = activeRoot(tag);
    Squadron inside = new Squadron();
    inside.setName("Inside " + tag);
    inside.setShorthand("I" + tag);
    inside.setParent(root);
    inside = squadrons.saveAndFlush(inside);
    Squadron outside = new Squadron();
    outside.setName("Outside " + tag);
    outside.setShorthand("O" + tag);
    outside = squadrons.saveAndFlush(outside);

    assertThat(profitHierarchy.isEligible(root.getId())).isTrue();
    assertThat(profitHierarchy.isEligible(inside.getId())).isTrue();
    assertThat(profitHierarchy.isEligible(outside.getId())).isFalse();

    hierarchy.setParent(inside.getId(), null, inside.getVersion());

    assertThat(profitHierarchy.isEligible(inside.getId())).isFalse();
  }

  @Test
  void anotherProfitDesignationIsRejectedEvenWithADifferentDisplayName() {
    String tag = UUID.randomUUID().toString();
    activeRoot(tag);

    assertThatThrownBy(
            () -> hierarchy.createBereich("Other " + tag, "O" + tag, null, null, Department.PROFIT))
        .isInstanceOf(DuplicateEntityException.class);
  }

  @Test
  void requiresNewCannotBorrowTheSuspendedTransactionsExclusiveLock() {
    profitHierarchy.lockForHierarchyChange();
    TransactionTemplate inner = new TransactionTemplate(transactionManager);
    inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

    assertThatThrownBy(() ->
            inner.executeWithoutResult(_ -> {
              jdbc.execute("SET LOCAL lock_timeout = '250ms'");
              profitHierarchy.lockForOrderWrite();
            }))
        .rootCause()
        .hasMessageContaining("lock timeout");
  }

  @Test
  void ownHierarchyChangesNeverReuseAPreMutationExclusiveSnapshot() {
    String tag = UUID.randomUUID().toString();
    var roots = units.findActiveProfitRootIds();
    assertThat(roots).hasSizeLessThanOrEqualTo(1);
    OrgUnit root;
    if (roots.isEmpty()) {
      Bereich fixtureRoot = new Bereich();
      fixtureRoot.setName("Snapshot Profit " + tag);
      fixtureRoot.setShorthand("P" + tag);
      fixtureRoot.setDepartment(Department.PROFIT);
      root = units.saveAndFlush(fixtureRoot);
    } else {
      root = units.findById(roots.getFirst()).orElseThrow();
    }
    Squadron child = new Squadron();
    child.setName("Snapshot child " + tag);
    child.setShorthand("C" + tag);
    child.setParent(root);
    child = squadrons.saveAndFlush(child);
    profitHierarchy.lockForOrderWrite();
    assertThat(profitHierarchy.isEligible(child.getId())).isTrue();

    OrgUnit changed = hierarchy.setParent(child.getId(), null, child.getVersion());
    assertThat(profitHierarchy.isEligible(child.getId())).isFalse();
    hierarchy.setParent(child.getId(), root.getId(), changed.getVersion());
    assertThat(profitHierarchy.isEligible(child.getId())).isTrue();
  }

  private OrgUnit activeRoot(String tag) {
    var roots = units.findActiveProfitRootIds();
    assertThat(roots).hasSizeLessThanOrEqualTo(1);
    return roots.isEmpty()
        ? hierarchy.createBereich("Profit " + tag, "P" + tag, null, null, Department.PROFIT)
        : units.findById(roots.getFirst()).orElseThrow();
  }
}
