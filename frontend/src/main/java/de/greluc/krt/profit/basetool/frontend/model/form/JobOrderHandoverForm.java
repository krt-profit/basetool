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

package de.greluc.krt.profit.basetool.frontend.model.form;

import de.greluc.krt.profit.basetool.frontend.model.dto.AllocationReductionDto;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Data;

/** Form-binding object for Job Order Handover input. */
@Data
public class JobOrderHandoverForm {
  private String handoverTime; // We will parse it to Instant before sending
  private String recipientHandle;
  private String recipientSquadron;
  private List<JobOrderHandoverItemForm> items = new ArrayList<>();

  /** Form-binding object for Job Order Handover Item input. */
  @Data
  public static class JobOrderHandoverItemForm {
    private UUID inventoryItemId;
    private Double amount;

    /**
     * Optional per-mission "deduct from" plan for the handed amount (Variante C, REQ-INV-027): the
     * ambiguous-case picker fills it so the handed SCU leave the chosen mission earmarks; {@code
     * null} lets the backend auto-clamp the mission dimension (rest-first, then proportional).
     */
    private List<AllocationReductionDto> missionReductions;
  }
}
