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

package de.greluc.krt.profit.basetool.backend.support;

import de.greluc.krt.profit.basetool.backend.joborder.api.AggregatedMaterialDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderAssigneeDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderHandoverDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemHandoverDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterialDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import de.greluc.krt.profit.basetool.backend.kernel.SquadronReferenceDto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;

/**
 * Builds the {@link JobOrderDto} the service and controller tests stub a mapper or service with.
 */
public final class JobOrderDtoFixtures {

  private JobOrderDtoFixtures() {}

  /**
   * Starts an open {@code MATERIAL} order of version 1 without units, contact, lines or flags.
   *
   * @return a builder holding the defaults its fields name
   */
  @NotNull
  public static JobOrderDtoBuilder jobOrder() {
    return new JobOrderDtoBuilder();
  }

  /** Fluent builder of a {@link JobOrderDto}; every setter overrides one component's default. */
  @Setter
  @Accessors(fluent = true, chain = true)
  public static final class JobOrderDtoBuilder {

    /** The order id, a fresh random one by default. */
    private UUID id = UUID.randomUUID();

    /** The display number, {@code 1} by default. */
    private Integer displayId = 1;

    /** The responsible unit, none by default. */
    private SquadronReferenceDto responsibleOrgUnit;

    /** The requesting unit, none by default. */
    private SquadronReferenceDto requestingOrgUnit;

    /** The contact handle, none by default. */
    private String handle;

    /** The comment, none by default. */
    private String comment;

    /** The queue priority, {@code 1} by default. */
    private Integer priority = 1;

    /** The lifecycle status, {@code OPEN} by default. */
    private JobOrderStatus status = JobOrderStatus.OPEN;

    /** The order kind, {@code MATERIAL} by default. */
    private JobOrderType type = JobOrderType.MATERIAL;

    /** Whether the blueprint view counts variants, {@code true} by default. */
    private boolean countBlueprintsWithVariants = true;

    /** The material lines, none by default. */
    private List<JobOrderMaterialDto> materials = List.of();

    /** The item lines, none by default. */
    private List<JobOrderItemDto> items = List.of();

    /** The aggregated material needs, none by default. */
    private List<AggregatedMaterialDto> aggregatedMaterials = List.of();

    /** The assignees, none by default. */
    private List<JobOrderAssigneeDto> assignees = List.of();

    /** The material handovers, none by default. */
    private List<JobOrderHandoverDto> handovers = List.of();

    /** The item handovers, none by default. */
    private List<JobOrderItemHandoverDto> itemHandovers = List.of();

    /** The creation instant, the builder's creation by default. */
    private Instant createdAt = Instant.now();

    /** The optimistic-lock version, {@code 1} by default. */
    private Long version = 1L;

    /** The edit flag, unresolved by default. */
    private Boolean canEdit;

    /** The assignee-management flag, unresolved by default. */
    private Boolean canManageAssignees;

    /** Whether the responsibility is invalid, {@code false} by default. */
    private boolean responsibilityInvalid;

    /** Whether the original Profit boundary is unknown, {@code false} by default. */
    private boolean originalProfitBoundaryUnknown;

    /** The responsibility-transfer flag, unresolved by default. */
    private Boolean canTransferResponsibility;

    /** The requester-edit flag, {@code false} by default. */
    private Boolean canEditAsRequester = false;

    /** The cancel flag, {@code false} by default. */
    private Boolean canCancel = false;

    /** The reopen flag, {@code false} by default. */
    private Boolean canReopen = false;

    /** The lifecycle revision, {@code 0} by default. */
    private Long lifecycleVersion = 0L;

    /** The responsibility revision, {@code 0} by default. */
    private Long responsibilityVersion = 0L;

    /** The comment revision, {@code 0} by default. */
    private Long commentVersion = 0L;

    /** The creating application, the Basetool by default. */
    private String createdClient;

    /** The application of the last comment write, the Basetool by default. */
    private String commentClient;

    private JobOrderDtoBuilder() {}

    /**
     * Builds the record from the current components.
     *
     * @return the order
     */
    @NotNull
    public JobOrderDto build() {
      return new JobOrderDto(
          id,
          displayId,
          responsibleOrgUnit,
          requestingOrgUnit,
          handle,
          comment,
          priority,
          status,
          type,
          countBlueprintsWithVariants,
          materials,
          items,
          aggregatedMaterials,
          assignees,
          handovers,
          itemHandovers,
          createdAt,
          version,
          canEdit,
          canManageAssignees,
          responsibilityInvalid,
          originalProfitBoundaryUnknown,
          canTransferResponsibility,
          canEditAsRequester,
          canCancel,
          canReopen,
          lifecycleVersion,
          responsibilityVersion,
          commentVersion,
          createdClient,
          commentClient);
    }
  }
}
