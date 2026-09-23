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

import de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.dto.SpecialCommandDto;
import de.greluc.krt.profit.basetool.backend.repository.SpecialCommandRepository;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD service for {@link SpecialCommand} — the Spezialkommando tenant kind introduced by the
 * Spezialkommando R2.a slice (see {@code SPEZIALKOMMANDO_PLAN.md}). Mirrors the {@link
 * SquadronService} surface field-for-field with three exceptions:
 *
 * <ul>
 *   <li>No promotion-feature toggle — Spezialkommandos never carry the promotion subsystem. The V94
 *       {@code chk_org_unit_promotion_only_squadron} CHECK constraint plus the {@link
 *       SpecialCommand} setter override enforce this at the data and JPA layer; no service method
 *       can flip the flag, so none is exposed here.
 *   <li>No Spring Cache integration — SK lifecycle events are rare (admin-only create/delete) and
 *       SK rows are read through the same {@link SpecialCommandRepository} that supplies the
 *       member-management UI's roster, where stale data would surface as a stale chip. Plain
 *       {@code @Transactional(readOnly = true)} suffices; we can revisit caching later if the admin
 *       SK list ever shows up on a hot path.
 *   <li>{@code isPromotionEnabled} is not exposed on the wire — {@link SpecialCommandDto} omits the
 *       field, the constructor of {@link SpecialCommand} forces it to {@code false} regardless of
 *       how the entity is built, so the service has nothing meaningful to mutate.
 * </ul>
 *
 * <p>Same soft-delete, case-insensitive uniqueness and optimistic-locking semantics as {@link
 * SquadronService}. Uniqueness check spans the entire {@code org_unit} table — a Spezialkommando
 * named "IRIDIUM" is rejected because the IRIDIUM Squadron already carries that name (the
 * underlying {@code UNIQUE} constraint on {@code org_unit.name} is global across both kinds). The
 * repository method {@link SpecialCommandRepository#existsByNameIgnoreCase(String)} however filters
 * via the JPA discriminator and therefore only sees other SK rows — uniqueness conflicts with
 * Squadron rows are caught at flush time as a DB-level constraint violation, which the
 * GlobalExceptionHandler maps to a 409 Problem Detail. Acceptable trade-off because admin SK
 * creation is rare and a sane name-collision message ("IRIDIUM already exists") is more useful than
 * the bare DB error in 99% of cases — admins simply do not collide with Squadron names.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SpecialCommandService {

  private final SpecialCommandRepository specialCommandRepository;

  /**
   * Unpaged Spezialkommando list for dropdowns and the owner picker. Soft-deleted rows are excluded
   * unless {@code includeInactive} is set; the admin list page is the only caller that passes
   * {@code true}.
   *
   * @param includeInactive when {@code true}, include soft-deleted entries.
   * @return list of Spezialkommandos in repository insertion order.
   */
  public List<SpecialCommand> getAllSpecialCommands(boolean includeInactive) {
    return includeInactive
        ? specialCommandRepository.findAll()
        : specialCommandRepository.findAllByActiveTrue();
  }

  /**
   * Paged variant for the admin list view.
   *
   * @param pageable page request.
   * @param includeInactive when {@code true}, include soft-deleted entries.
   * @return page of Spezialkommandos in the requested order.
   */
  public Page<SpecialCommand> getAllSpecialCommands(
      @NotNull Pageable pageable, boolean includeInactive) {
    return includeInactive
        ? specialCommandRepository.findAll(pageable)
        : specialCommandRepository.findAllByActiveTrue(pageable);
  }

  /**
   * Looks up a Spezialkommando by its UUID. Used by the admin detail page, by the membership-
   * management endpoints, and by the owner-picker UI to resolve a chip click. Throws when no SK
   * carries the given id.
   *
   * @param id Spezialkommando primary key; never {@code null}.
   * @return the matching entity, never {@code null}.
   * @throws NotFoundException if no SK matches the given id.
   */
  public SpecialCommand getSpecialCommandById(@NotNull UUID id) {
    return Entities.require(specialCommandRepository.findById(id), "SpecialCommand not found");
  }

  /**
   * Persists a new Spezialkommando. Case-insensitive uniqueness check against other SK rows
   * surfaces as {@link DuplicateEntityException} → 409 before the SQL UNIQUE constraint trips, so
   * the admin UI gets a structured validation error instead of a generic optimistic-lock failure.
   * The {@link SpecialCommand} constructor pre-sets {@code isPromotionEnabled = false} so the V94
   * CHECK constraint accepts the row regardless of what the inbound DTO carries.
   *
   * @param specialCommand transient entity built from the inbound DTO.
   * @return the persisted entity with id and version populated.
   * @throws DuplicateEntityException if a Spezialkommando with the same name already exists.
   */
  @Transactional
  public SpecialCommand createSpecialCommand(@NotNull SpecialCommand specialCommand) {
    if (specialCommandRepository.existsByNameIgnoreCase(specialCommand.getName())) {
      throw new DuplicateEntityException(
          "A SpecialCommand with the name '" + specialCommand.getName() + "' already exists.");
    }
    return specialCommandRepository.save(specialCommand);
  }

  /**
   * Updates an existing Spezialkommando. Case-insensitive uniqueness check excludes the row being
   * updated so a no-op rename does not trip the duplicate guard. Optimistic-lock version check
   * surfaces concurrent edits as {@link ObjectOptimisticLockingFailureException} → 409.
   *
   * @param id Spezialkommando primary key.
   * @param dto update payload from the admin form.
   * @return the persisted entity.
   * @throws NotFoundException if no SK matches the given id.
   * @throws DuplicateEntityException if the new name collides with a different SK row.
   * @throws ObjectOptimisticLockingFailureException if the supplied version is stale.
   */
  @Transactional
  public SpecialCommand updateSpecialCommand(@NotNull UUID id, @NotNull SpecialCommandDto dto) {
    if (specialCommandRepository.existsByNameIgnoreCaseAndIdNot(dto.name(), id)) {
      throw new DuplicateEntityException(
          "A SpecialCommand with the name '" + dto.name() + "' already exists.");
    }
    SpecialCommand sc = getSpecialCommandById(id);

    OptimisticLock.check(sc.getVersion(), dto.version(), SpecialCommand.class, id);

    sc.setName(dto.name());
    sc.setShorthand(dto.shorthand());
    sc.setDescription(dto.description());
    return specialCommandRepository.save(sc);
  }

  /**
   * Soft-deletes a Spezialkommando by flipping {@code active = false}. Memberships and any
   * aggregate that already references the SK as an owner stay in place; the SK simply disappears
   * from the active-list dropdowns and the owner picker until {@link #activateSpecialCommand} is
   * called.
   *
   * @param id Spezialkommando primary key.
   * @throws NotFoundException if no SK matches the given id.
   */
  @Transactional
  public void deleteSpecialCommand(@NotNull UUID id) {
    SpecialCommand sc = getSpecialCommandById(id);
    sc.setActive(false);
    specialCommandRepository.save(sc);
  }

  /**
   * Reverses a soft-delete. ADMIN-only at the controller layer.
   *
   * @param id Spezialkommando primary key.
   * @throws NotFoundException if no SK matches the given id.
   */
  @Transactional
  public void activateSpecialCommand(@NotNull UUID id) {
    SpecialCommand sc = getSpecialCommandById(id);
    sc.setActive(true);
    specialCommandRepository.save(sc);
  }

  /**
   * Toggles the per-SK profit-eligibility flag deciding whether this Spezialkommando may be picked
   * as the responsible (processing) org unit of a Job Order. SKs of non-Profit departments leave
   * the flag {@code false}: they can still place orders (as the requesting org unit) but never
   * appear in the responsible picker. Kept as a dedicated mutator separate from {@link
   * #updateSpecialCommand(UUID, SpecialCommandDto)} so the flag cannot be flipped as a side-effect
   * of a name/description edit and the access log can attribute the change to the admin who pressed
   * the toggle. Flipping the flag never touches any Job Order.
   *
   * @param id Spezialkommando primary key.
   * @param eligible new value of {@code is_profit_eligible}; {@code true} makes the SK selectable
   *     as a Job-Order processor, {@code false} removes it from the responsible picker.
   * @return the persisted entity.
   * @throws NotFoundException if no SK matches the given id.
   */
  @Transactional
  public SpecialCommand setProfitEligible(@NotNull UUID id, boolean eligible) {
    SpecialCommand sc = getSpecialCommandById(id);
    sc.setProfitEligible(eligible);
    return specialCommandRepository.save(sc);
  }
}
