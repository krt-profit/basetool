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

package de.greluc.krt.profit.basetool.frontend.support;

import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderCreateOptionsDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderCreateUnitOptionDto;
import java.util.List;
import java.util.UUID;

/** Supplies explicit backend-authorized creation choices for rendered form tests. */
public final class OrderCreationOptionsFixtures {

  private OrderCreationOptionsFixtures() {}

  /**
   * Offers an eligible responsible unit and a requesting-side writer.
   *
   * @param requestingUnitId the offered requesting unit
   * @return one valid pair with the requesting unit's explicit writer capability
   */
  public static JobOrderCreateOptionsDto permitted(UUID requestingUnitId) {
    UUID responsibleId = UUID.fromString("00000000-0000-0000-0000-000000000185");
    return new JobOrderCreateOptionsDto(
        true,
        List.of(
            new JobOrderCreateUnitOptionDto(
                responsibleId, "Responsible unit", "RU", "SQUADRON", false)),
        List.of(
            new JobOrderCreateUnitOptionDto(
                requestingUnitId, "Requesting unit", "QU", "SQUADRON", true)),
        responsibleId,
        requestingUnitId);
  }
}
