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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.scope.api.ScopeParityCallers;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies the coverage filter of the needs list against PostgreSQL (REQ-SUP-006): an open need is
 * uncovered while nothing is credited to it and partly covered while some, but not all, is.
 */
@SpringBootTest
@Transactional
class NeedCoverageDataTest {

  @Autowired private SupplyNeedRepository needRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private MaterialRepository materialRepository;

  /** Uncovered needs have nothing credited; partly covered ones have some but not the whole. */
  @Test
  void specifications_splitTheNeedsByWhatIsCredited() {
    Squadron unit = ScopeParityCallers.persistSquadron(squadronRepository, "N");
    SupplyNeed none = persistNeed(unit.getId(), 10.0, 0.0, NeedStatus.OPEN);
    SupplyNeed some = persistNeed(unit.getId(), 10.0, 4.0, NeedStatus.OPEN);
    SupplyNeed all = persistNeed(unit.getId(), 10.0, 10.0, NeedStatus.OPEN);

    List<SupplyNeed> uncovered = needRepository.findAll(NeedCoverage.UNCOVERED.specification());
    List<SupplyNeed> partial = needRepository.findAll(NeedCoverage.PARTIAL.specification());

    assertThat(uncovered).contains(none).doesNotContain(some, all);
    assertThat(partial).contains(some).doesNotContain(none, all);
  }

  private SupplyNeed persistNeed(
      UUID orgUnitId, double amount, double fulfilled, NeedStatus status) {
    Material material = new Material();
    material.setName("Coverage-" + UUID.randomUUID());
    material.setType(MaterialType.RAW);
    materialRepository.save(material);
    SupplyNeed need = new SupplyNeed();
    need.setOrgUnitId(orgUnitId);
    need.setMaterial(material);
    need.setAmount(amount);
    need.setFulfilledAmount(fulfilled);
    need.setStatus(status);
    return needRepository.saveAndFlush(need);
  }
}
