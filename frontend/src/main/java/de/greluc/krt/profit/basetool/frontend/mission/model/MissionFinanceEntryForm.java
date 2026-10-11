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

package de.greluc.krt.profit.basetool.frontend.mission.model;

import de.greluc.krt.profit.basetool.frontend.kernel.web.WholeNumber;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

/** Form-binding object for Mission Finance Entry input. */
@Data
public class MissionFinanceEntryForm {

  private UUID id;

  private UUID missionId;

  private UUID participantId;

  private String note;

  @NotNull(message = "{finance.validation.type.null}")
  private FinanceType type;

  @NotNull(message = "{finance.validation.amount.null}")
  @DecimalMin(value = "0.0", message = "{finance.validation.amount.min}")
  @WholeNumber
  private BigDecimal amount;

  private Long version;
}
