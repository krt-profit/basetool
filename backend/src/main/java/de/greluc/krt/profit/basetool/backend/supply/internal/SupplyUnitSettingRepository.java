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

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Spring Data repository for {@link SupplyUnitSetting} (REQ-SUP-002). */
@Repository
public interface SupplyUnitSettingRepository extends JpaRepository<SupplyUnitSetting, UUID> {

  /**
   * Finds the settings of the units that raise needs.
   *
   * @return the settings with {@code mayRaiseNeeds}
   */
  List<SupplyUnitSetting> findByMayRaiseNeedsTrue();

  /**
   * Finds the settings of the units that accept donations.
   *
   * @return the settings with {@code acceptsDonations}
   */
  List<SupplyUnitSetting> findByAcceptsDonationsTrue();
}
