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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonMentionSource;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The joborder's part of the person search: the columns of job orders, assignments and handovers
 * that can name a person, and those left out (REQ-SEC-060).
 */
@Component
public class JobOrderMentionSource implements PersonMentionSource {

  @Override
  public @NotNull List<Target> targets() {
    return List.of(
        new Target("JOB_ORDER", "job_order", "comment", "id", LinkKind.JOB_ORDER),
        new Target("JOB_ORDER", "job_order", "handle", "id", LinkKind.JOB_ORDER),
        new Target("JOB_ORDER", "job_order_assignees", "note", "job_order_id", LinkKind.JOB_ORDER),
        new Target(
            "JOB_ORDER",
            "job_order_handover",
            "recipient_handle",
            "job_order_id",
            LinkKind.JOB_ORDER),
        new Target(
            "JOB_ORDER",
            "job_order_handover",
            "recipient_squadron",
            "job_order_id",
            LinkKind.JOB_ORDER),
        new Target(
            "JOB_ORDER",
            "job_order_item_handover",
            "recipient_handle",
            "job_order_id",
            LinkKind.JOB_ORDER));
  }

  @Override
  public @NotNull Set<String> exemptColumns() {
    return Set.of("job_order.status", "job_order.type", "job_order_handover_item.location_name");
  }
}
