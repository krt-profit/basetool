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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation;
import de.greluc.krt.profit.basetool.backend.platform.api.HandleAnonymisationParticipant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The job-order module's part of the handle anonymisation: the order contact and the recipients of
 * the material and item handovers, all typed by hand and therefore matched by text.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class JobOrderHandleAnonymisation implements HandleAnonymisationParticipant {

  static final int POSITION = 30;

  private final JobOrderRepository jobOrderRepository;
  private final JobOrderHandoverRepository jobOrderHandoverRepository;
  private final JobOrderItemHandoverRepository jobOrderItemHandoverRepository;

  @Override
  public int position() {
    return POSITION;
  }

  @Override
  public @NotNull List<String> columns() {
    return List.of(
        "job_order.handle",
        "job_order_handover.recipient_handle",
        "job_order_item_handover.recipient_handle");
  }

  /**
   * For each spelling, rewrites the order contact, the material-handover recipient and the
   * item-handover recipient whose whole value matches it, case-insensitively.
   *
   * @param userId the member whose snapshots are erased, unused: no column here carries a user id
   * @param spellings every name the member is stored under
   * @return the rows rewritten per table
   */
  @Override
  public @NotNull Map<Rewritten, Integer> anonymise(
      @NotNull UUID userId, @NotNull List<String> spellings) {
    String sentinel = HandleAnonymisation.SENTINEL;
    int jobOrders = 0;
    int materialHandovers = 0;
    int itemHandovers = 0;
    for (String name : spellings) {
      jobOrders += jobOrderRepository.anonymiseHandle(name, sentinel);
      materialHandovers += jobOrderHandoverRepository.anonymiseRecipientHandle(name, sentinel);
      itemHandovers += jobOrderItemHandoverRepository.anonymiseRecipientHandle(name, sentinel);
    }
    Map<Rewritten, Integer> rewritten = new EnumMap<>(Rewritten.class);
    rewritten.put(Rewritten.JOB_ORDERS, jobOrders);
    rewritten.put(Rewritten.MATERIAL_HANDOVERS, materialHandovers);
    rewritten.put(Rewritten.ITEM_HANDOVERS, itemHandovers);
    return rewritten;
  }

  /**
   * Records nothing: the orchestrator's activity-trail marker covers the job orders.
   *
   * @param userId the member whose snapshots were erased
   * @param rewritten the summed row counts of every participant
   */
  @Override
  public void recordAnonymised(@NotNull UUID userId, @NotNull Map<Rewritten, Integer> rewritten) {}
}
