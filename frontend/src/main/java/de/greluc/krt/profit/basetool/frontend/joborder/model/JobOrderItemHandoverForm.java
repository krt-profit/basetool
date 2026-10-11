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

package de.greluc.krt.profit.basetool.frontend.joborder.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Data;

/**
 * Form-binding object for item-handover input on the order-detail page. Mirrors {@link
 * JobOrderHandoverForm} (the material counterpart) but binds per-ordered-item delivered quantities
 * instead of inventory items. {@code handoverTime} arrives as a client-produced UTC ISO-Instant
 * string and is parsed before relay to the backend.
 */
@Data
public class JobOrderItemHandoverForm {

  /** Client-produced UTC ISO-Instant string; parsed to {@code Instant} before sending. */
  private String handoverTime;

  /** The recipient's in-game handle. */
  private String recipientHandle;

  /** The delivered ordered-item lines; rows with a null/zero amount are dropped server-side. */
  private List<JobOrderItemHandoverEntryForm> entries = new ArrayList<>();

  /**
   * One delivered ordered-item line: how many whole units of {@code jobOrderItemId} changed hands.
   */
  @Data
  public static class JobOrderItemHandoverEntryForm {

    /** The ordered item line being fulfilled. */
    private UUID jobOrderItemId;

    /** Whole-unit count delivered for that line. */
    private Integer amount;
  }
}
