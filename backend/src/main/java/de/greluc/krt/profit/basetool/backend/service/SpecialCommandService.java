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
 * CRUD service for {@link SpecialCommand} (Spezialkommando), mirroring {@link SquadronService}
 * without the promotion toggle and without caching.
 *
 * <p>Same soft-delete, case-insensitive uniqueness and optimistic-locking semantics as {@link
 * SquadronService}. The explicit duplicate check sees only other SKs; a clash with a Squadron name
 * fails at flush on the global unique constraint and maps to 409.
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
   * Returns the Spezialkommando with the given id.
   *
   * @param id Spezialkommando primary key; never {@code null}.
   * @return the matching entity, never {@code null}.
   * @throws NotFoundException if no SK matches the given id.
   */
  public SpecialCommand getSpecialCommandById(@NotNull UUID id) {
    return Entities.require(specialCommandRepository.findById(id), "SpecialCommand not found");
  }

  /**
   * Persists a new Spezialkommando after a case-insensitive name check against other SKs; promotion
   * is always disabled.
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
   * Sets whether this Spezialkommando may be picked as the responsible (processing) org unit of a
   * Job Order. Separate from {@link #updateSpecialCommand(UUID, SpecialCommandDto)}; existing Job
   * Orders are not touched.
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
