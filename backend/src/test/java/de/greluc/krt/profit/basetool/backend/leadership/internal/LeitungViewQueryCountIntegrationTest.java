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

package de.greluc.krt.profit.basetool.backend.leadership.internal;

import static de.greluc.krt.profit.basetool.backend.support.StatementCounter.measured;
import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.orgunit.api.Bereich;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Department;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

/** Leadership SQL stays bounded when visible children have distinct inactive parents. */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class LeitungViewQueryCountIntegrationTest {
  @Autowired private LeitungViewService service;
  @Autowired private OrgUnitRepository repository;
  @Autowired private EntityManager entityManager;

  @Test
  void oneAndOneHundredTwentyFiveInactiveParentsUseTheSameStatementCount() throws Exception {
    Pair first = createPair();
    entityManager.flush();
    entityManager.clear();
    var small =
        measured(() -> service.buildView(SecurityContextHolder.getContext().getAuthentication()));
    assertThat(small.result().squadrons())
        .anySatisfy(unit -> {
          assertThat(unit.id()).isEqualTo(first.child());
          assertThat(unit.bereichName()).isEqualTo(first.parentName());
          assertThat(unit.department()).isEqualTo(Department.FORSCHUNG);
        });
    for (int i = 1; i < 125; i++) {
      createPair();
    }
    entityManager.flush();
    entityManager.clear();
    var large =
        measured(() -> service.buildView(SecurityContextHolder.getContext().getAuthentication()));
    assertThat(large.result().squadrons()).hasSize(small.result().squadrons().size() + 124);
    assertThat(large.statements()).isPositive().isEqualTo(small.statements());
    System.out.printf(
        "LEITUNG_QUERY_PROFILE inactive_parents=1 statements=%d inactive_parents=125"
            + " statements=%d%n",
        small.statements(), large.statements());
  }

  private Pair createPair() {
    String token = UUID.randomUUID().toString();
    Bereich parent = new Bereich();
    parent.setName("Inactive profile area " + token);
    parent.setShorthand("P" + token);
    parent.setActive(false);
    parent.setDepartment(Department.FORSCHUNG);
    repository.save(parent);
    Squadron child = new Squadron();
    child.setName("Active profile squadron " + token);
    child.setShorthand("C" + token);
    child.setParent(parent);
    repository.save(child);
    return new Pair(child.getId(), parent.getName());
  }

  /** Identifiers and labels of one synthetic parent-child pair. */
  private record Pair(UUID child, String parentName) {}
}
