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

package de.greluc.krt.profit.basetool.backend.model.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Outbound projection of a single {@code InventoryItem} stock row. Since the Lager keeps every
 * contribution as its own append-only row (no destructive merge), {@code createdAt} carries the
 * row's creation instant so the UI can order the individual entries of a grouped stack oldest-first
 * and show when each contribution was recorded.
 *
 * <p>Variante C (REQ-INV-027) carries the two independent quantity splits: {@code
 * jobOrderAllocations} / {@code missionAllocations} list the earmarked orders/missions each with
 * their own amount, and {@code jobOrderRest} / {@code missionRest} are the still-unallocated
 * remainder per dimension ({@code amount − Σ}) the UI renders as the rest-chip. Both are the
 * authoritative multi-earmark view; the former single {@code jobOrderId} / {@code missionId}
 * scalars were dropped once every consumer read the allocations.
 */
public record InventoryItemDto(
    UUID id,
    UserReferenceDto user,
    MaterialReferenceDto material,
    LocationReferenceDto location,
    Integer quality,
    Double amount,
    Boolean personal,
    List<JobOrderAllocationDto> jobOrderAllocations,
    Double jobOrderRest,
    List<MissionAllocationDto> missionAllocations,
    Double missionRest,
    String note,
    SquadronReferenceDto owningSquadron,
    Long version,
    Instant createdAt) {

  /**
   * Returns a copy of this projection with {@link #version} replaced. Used by the force-increment
   * write paths (the per-order delivered toggle and the allocation add/change/remove endpoints):
   * those only mutate an inverse-side allocation slice, so they force-bump the entry's
   * {@code @Version} via {@code OPTIMISTIC_FORCE_INCREMENT}, which Hibernate applies at transaction
   * commit — the entity mapped in-transaction therefore still carries the pre-increment value. The
   * client must echo the post-commit version ({@code loaded + 1}) on its next write to the same
   * entry, or the follow-up optimistic-lock check 409s (REQ-FE-003, REQ-INV-027).
   *
   * @param newVersion the version the client should echo on its next write.
   * @return a copy of this DTO carrying {@code newVersion}.
   */
  public InventoryItemDto withVersion(Long newVersion) {
    return new InventoryItemDto(
        id,
        user,
        material,
        location,
        quality,
        amount,
        personal,
        jobOrderAllocations,
        jobOrderRest,
        missionAllocations,
        missionRest,
        note,
        owningSquadron,
        newVersion,
        createdAt);
  }
}
