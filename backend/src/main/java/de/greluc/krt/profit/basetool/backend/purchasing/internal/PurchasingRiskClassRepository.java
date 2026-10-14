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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import de.greluc.krt.profit.basetool.backend.purchasing.api.RiskClassCode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence of the risk-class table (REQ-PUR-011). */
@Repository
public interface PurchasingRiskClassRepository extends JpaRepository<PurchasingRiskClass, UUID> {

  /**
   * Lists the classes from the lowest to the highest.
   *
   * @return the three classes in code order
   */
  List<PurchasingRiskClass> findAllByOrderByCodeAsc();

  /**
   * Finds one class.
   *
   * @param code the class code
   * @return the class, or empty
   */
  Optional<PurchasingRiskClass> findByCode(RiskClassCode code);
}
