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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.JobOrderHandoverMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderHandover;
import de.greluc.krt.profit.basetool.backend.model.JobOrderHandoverItem;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderHandoverCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderHandoverDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderHandoverItemCreateDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderHandoverRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderMaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeOfferRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.InventoryAllocationSync;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Service handling JobOrderHandoverService operations. */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class JobOrderHandoverService {

  /**
   * Tolerance used when comparing handover / inventory quantities that are stored as {@code
   * double}. Quantities are user-edited and rounded to the displayed precision (three decimals) on
   * the way in, so any residual below 1e-4 is floating-point noise rather than a real surplus /
   * deficit. Picked an order of magnitude below the smallest representable user quantity to keep
   * the rounding-safe comparison cheap.
   */
  private static final double QUANTITY_EPSILON = 1e-4;

  private final JobOrderRepository jobOrderRepository;
  private final JobOrderHandoverRepository jobOrderHandoverRepository;
  private final InventoryItemRepository inventoryItemRepository;
  private final MaterialExchangeOfferRepository materialExchangeOfferRepository;
  private final JobOrderHandoverMapper jobOrderHandoverMapper;
  private final JobOrderMaterialRepository jobOrderMaterialRepository;
  private final JobOrderService jobOrderService;
  private final UserService userService;
  private final OrgUnitMembershipQueryService orgUnitMembershipQueryService;
  private final SquadronRepository squadronRepository;
  private final AuditService auditService;

  /**
   * One handed-over inventory row's scalar snapshot, captured before the row is decremented/deleted
   * so the {@code INVENTORY_HANDED_OVER} audit events can be emitted after the bulk unlinks without
   * touching a detached/deleted entity (bulk-clear landmine rules, CLAUDE.md).
   *
   * @param itemId the source inventory row id
   * @param label the {@code material @ location} label snapshot
   * @param material the material name snapshot
   * @param amount the handed-over amount
   * @param remaining the post-decrement amount (0 when depleted)
   * @param depleted whether the source row was removed
   */
  private record HandedItem(
      UUID itemId,
      String label,
      String material,
      double amount,
      double remaining,
      boolean depleted) {}

  /**
   * Creates a JobOrder handover and atomically applies the resulting effects:
   *
   * <ul>
   *   <li>reduces inventory amounts (or deletes the row when fully consumed),
   *   <li>reduces the open amount per {@link
   *       de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial},
   *   <li>persists the handover plus its items,
   *   <li>unlinks remaining inventory rows for materials that are now fully fulfilled,
   *   <li>completes the JobOrder if all materials are fulfilled.
   * </ul>
   *
   * <p><b>Concurrency / Optimistic Locking:</b> The previous implementation issued the bulk {@code
   * unlinkJobOrderMaterial} update <em>inside</em> the per-item loop. The bulk update carries
   * {@code @Modifying(clearAutomatically = true, flushAutomatically = true)} which (1) flushes
   * pending changes mid-iteration and (2) detaches the {@link JobOrder} aggregate (and all of its
   * {@link de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial}s) from the persistence
   * context. Subsequent loop iterations then operated on detached entities and called {@code
   * jobOrderMaterialRepository.save(mat)} which silently triggers an {@code EntityManager.merge()}.
   * Combined with the cascade on {@code JobOrder.materials} and the additional {@code
   * findById}/{@code save}/{@code flush} cycle in {@code
   * JobOrderService.completeJobOrderWithinTransaction()} this produced a second version-bump on
   * already-modified rows — a textbook {@link
   * org.springframework.orm.ObjectOptimisticLockingFailureException} (HTTP 409) when an entire
   * JobOrder was fulfilled by a single multi-material handover.
   *
   * <p>The structural fix (extension of the {@code *WithinTransaction} pattern documented in {@code
   * AGENTS.md} to the {@code JobOrderMaterial} / {@code Stock} aggregates) consists of three rules
   * enforced below:
   *
   * <ol>
   *   <li>The loop only mutates managed entities — it relies on Hibernate's dirty checking and
   *       never calls {@code jobOrderMaterialRepository.save(mat)} explicitly.
   *   <li>Bulk updates with {@code clearAutomatically=true} are deferred until <em>after</em> the
   *       loop has completed and the new handover has been persisted.
   *   <li>The {@link JobOrder} is re-fetched <em>once</em> after the bulk updates so the completion
   *       check operates on a freshly managed aggregate (with up-to-date {@code @Version}s).
   * </ol>
   */
  @Transactional
  public JobOrderHandoverDto createHandover(UUID jobOrderId, JobOrderHandoverCreateDto dto) {
    JobOrder jobOrder =
        jobOrderRepository
            .findById(jobOrderId)
            .orElseThrow(() -> new NotFoundException("JobOrder not found"));

    JobOrderHandover handover = new JobOrderHandover();
    handover.setJobOrder(jobOrder);
    handover.setHandoverTime(dto.handoverTime());
    handover.setRecipientHandle(dto.recipientHandle());
    handover.setRecipientSquadron(dto.recipientSquadron());

    // Audit trail: capture the executing user + their squadron snapshot at handover time.
    // Cross-staffel workspace means the executing user may belong to a different squadron than the
    // order's responsible one — without this stamp the audit trail does not record who actually
    // performed the write on a foreign squadron's items. REQ-ORG-017: the executor may now hold up
    // to two Staffeln, so the snapshot is order-aligned — the executor's Staffel that matches the
    // order's responsible org unit, else their deterministic primary (read from
    // org_unit_membership;
    // the legacy User.squadron column was dropped in V101).
    UUID responsibleOrgUnitId =
        jobOrder.getResponsibleOrgUnit() != null ? jobOrder.getResponsibleOrgUnit().getId() : null;
    userService
        .getCurrentUser()
        .ifPresent(
            current -> {
              handover.setExecutingUser(current);
              orgUnitMembershipQueryService
                  .findExecutingStaffelForOrder(current.getId(), responsibleOrgUnitId)
                  .flatMap(squadronRepository::findById)
                  .ifPresent(handover::setExecutingSquadron);
            });

    // Materials whose remaining open amount drops to (effectively) zero in this handover.
    // The corresponding inventory rows are unlinked AFTER the loop so that no
    // {@code clearAutomatically=true} bulk update detaches the aggregate mid-iteration.
    Set<UUID> materialsToUnlink = new HashSet<>();

    // Snapshot each handed-over inventory row BEFORE it is decremented/deleted; the
    // INVENTORY_HANDED_OVER audit events are emitted after the bulk unlinks from these snapshots,
    // never by re-reading a detached/deleted entity (bulk-clear landmine rules).
    final Integer orderDisplayId = jobOrder.getDisplayId();
    final List<HandedItem> handedItems = new ArrayList<>();

    for (JobOrderHandoverItemCreateDto itemDto : dto.items()) {
      // The InventoryItem is only used as a transient lookup source for the snapshot data
      // (material, quality) and to update / delete the source inventory row. It is intentionally
      // NOT referenced from JobOrderHandoverItem anymore so that emptying the inventory does not
      // break historical handover records (see CHANGELOG / V64 migration).
      InventoryItem inventoryItem =
          inventoryItemRepository
              .findByIdForUpdate(itemDto.inventoryItemId())
              .orElseThrow(() -> new NotFoundException("Inventory item not found"));

      if (inventoryItem.getJobOrder() == null
          || !inventoryItem.getJobOrder().getId().equals(jobOrderId)) {
        // Plan §4.4 cross-staffel pre-write guard: the handover may only mutate inventory items
        // that are bound to the current order via job_order_id. A mismatch means either a stale
        // client payload or a concurrent unlink — in both cases the handover cannot proceed and
        // the application is in an inconsistent state for this request. GlobalExceptionHandler
        // maps IllegalStateException to 400 so the wire format stays the same as before.
        throw new IllegalStateException("Inventory item does not belong to this JobOrder");
      }

      if (itemDto.amount() == null || itemDto.amount() <= 0) {
        // Defence in depth behind the DTO's @Positive (now actually cascaded via @Valid, audit
        // M-4): a non-positive amount would pass the "more than available" check, then *increase*
        // both the inventory stock (remaining = stock - amount) and the open requirement.
        throw new BadRequestException("Handover amount must be positive");
      }
      if (itemDto.amount() > inventoryItem.getAmount() + QUANTITY_EPSILON) {
        throw new BadRequestException("Cannot hand over more than the available amount");
      }
      QuantityType quantityType =
          inventoryItem.getMaterial() != null
              ? inventoryItem.getMaterial().getQuantityType()
              : null;
      if (quantityType == QuantityType.PIECE && itemDto.amount() % 1 != 0) {
        throw new BadRequestException("Amount must be a whole number for PIECE materials");
      }

      final double remainingAmount = inventoryItem.getAmount() - itemDto.amount();

      JobOrderHandoverItem handoverItem = new JobOrderHandoverItem();
      handoverItem.setMaterial(inventoryItem.getMaterial());
      handoverItem.setQuality(inventoryItem.getQuality());
      handoverItem.setAmount(itemDto.amount());
      handoverItem.setLocationName(
          inventoryItem.getLocation() != null ? inventoryItem.getLocation().getName() : null);

      handover.addItem(handoverItem);

      boolean itemDepleted = remainingAmount <= QUANTITY_EPSILON;
      String materialName =
          inventoryItem.getMaterial() != null ? inventoryItem.getMaterial().getName() : "—";
      String locationName =
          inventoryItem.getLocation() != null ? inventoryItem.getLocation().getName() : "—";
      handedItems.add(
          new HandedItem(
              inventoryItem.getId(),
              materialName + " @ " + locationName,
              materialName,
              itemDto.amount(),
              itemDepleted ? 0.0 : remainingAmount,
              itemDepleted));

      if (remainingAmount <= QUANTITY_EPSILON) {
        inventoryItemRepository.delete(inventoryItem);
      } else {
        inventoryItem.setAmount(remainingAmount);
        // Variante C soak: shrink the entry's allocation to the post-handover amount in step, so
        // the
        // allocation-based fulfilment reads do not over-credit the reduced row (REQ-INV-027).
        InventoryAllocationSync.mirrorScalars(inventoryItem);
        inventoryItemRepository.save(inventoryItem);
      }

      // Mutate the managed JobOrderMaterial via dirty checking. We MUST NOT call
      // jobOrderMaterialRepository.save(mat) here: a save() on a detached entity (which
      // would happen if a previous iteration's bulk update detached the aggregate) silently
      // performs a merge() and produces a second version-bump on the same row, leading to
      // ObjectOptimisticLockingFailureException at commit time.
      jobOrder.getMaterials().stream()
          .filter(mat -> mat.getMaterial().getId().equals(inventoryItem.getMaterial().getId()))
          .findFirst()
          .ifPresent(
              mat -> {
                double newAmount = mat.getAmount() - itemDto.amount();
                mat.setAmount(Math.max(0.0, newAmount));
                if (mat.getAmount() <= QUANTITY_EPSILON) {
                  materialsToUnlink.add(mat.getMaterial().getId());
                }
              });
    }

    // Persist the handover (incl. items via cascade) BEFORE issuing any bulk update that would
    // clear the persistence context. This guarantees the handover row exists by the time the
    // bulk UPDATEs run and avoids implicit re-merge of detached entities.
    JobOrderHandover savedHandover = jobOrderHandoverRepository.save(handover);
    // Capture the DTO while the entity graph is still attached and fully initialised.
    final JobOrderHandoverDto resultDto = jobOrderHandoverMapper.toDto(savedHandover);

    // Run the (potentially session-clearing) bulk unlinks ONCE per fulfilled material, AFTER
    // all per-item bookkeeping is done.  Each call carries
    // {@code @Modifying(clearAutomatically=true, flushAutomatically=true)} which flushes the
    // pending dirty changes from the loop and then detaches the persistence context; doing
    // this once at the end (instead of inside the loop) is what makes the multi-material
    // completion flow safe with respect to optimistic locking.
    for (UUID materialId : materialsToUnlink) {
      inventoryItemRepository.unlinkJobOrderMaterial(jobOrderId, materialId);
      // R2 (REQ-INV-027): release the fulfilled material's allocation slices too, so the leftover
      // stock stays in the Lager as (partially) unassigned rather than keeping a phantom order
      // link.
      inventoryItemRepository.deleteJobOrderAllocationsByJobOrderAndMaterial(
          jobOrderId, materialId);
    }

    // Re-fetch the JobOrder so the completion check runs on a freshly managed aggregate
    // with up-to-date {@code @Version}s. The previous bulk unlinks (and any auto-flush) have
    // already detached the original {@code jobOrder} reference from the session.
    JobOrder managedJobOrder =
        jobOrderRepository
            .findById(jobOrderId)
            .orElseThrow(() -> new NotFoundException("JobOrder not found"));

    boolean allFulfilled =
        managedJobOrder.getMaterials().stream()
            .allMatch(mat -> mat.getAmount() <= QUANTITY_EPSILON);

    if (allFulfilled) {
      // Use the dedicated WithinTransaction method that works on the already-managed entity
      // to avoid the double-save / optimistic-locking conflict that would otherwise occur if
      // {@code updateJobOrderStatus()} performed its own findById() + save() + flush() inside
      // the running transaction (see {@code AGENTS.md} — "INTRA-TRANSACTION SERVICE CALLS").
      jobOrderService.completeJobOrderWithinTransaction(managedJobOrder);
    }

    // Emit the audit events AFTER the bulk unlinks + completion, from the loop-captured snapshots
    // (never re-reading a detached/deleted inventory entity). One INVENTORY_HANDED_OVER per item
    // (cross-domain inventory effect) plus one JOB_ORDER_HANDOVER_CREATED. The recipientHandle is
    // user free text and is never written to the audit details. completeJobOrderWithinTransaction
    // already recorded JOB_ORDER_COMPLETED when the order was fulfilled, so this method does not.
    for (HandedItem h : handedItems) {
      // Ratchet any active Materialbörse offer on a non-depleted handed-over row down to its
      // reduced
      // stock (REQ-MARKET-013); a depleted row was deleted and its offer cascade-removed (V210).
      if (!h.depleted()) {
        materialExchangeOfferRepository.clampOfferedAmountToStock(h.itemId(), h.remaining());
      }
      auditService.record(
          AuditEventType.INVENTORY_HANDED_OVER,
          h.itemId(),
          h.label(),
          null,
          AuditDetails.of("source", "HANDOVER")
              .with("jobOrder", "#" + orderDisplayId)
              .with("material", h.material())
              .with("amount", h.amount())
              .with("remaining", h.remaining())
              .with("depleted", h.depleted()));
    }
    auditService.record(
        AuditEventType.JOB_ORDER_HANDOVER_CREATED,
        jobOrderId,
        "#" + managedJobOrder.getDisplayId() + " '" + managedJobOrder.getHandle() + "'",
        null,
        AuditDetails.of("handover", savedHandover.getId())
            .with("items", handedItems.size())
            .with("autoCompleted", allFulfilled));

    return resultDto;
  }
}
