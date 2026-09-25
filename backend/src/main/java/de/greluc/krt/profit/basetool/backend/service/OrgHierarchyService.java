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
import de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.Bereich;
import de.greluc.krt.profit.basetool.backend.model.Department;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.Organisationsleitung;
import de.greluc.krt.profit.basetool.backend.repository.BereichRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrganisationsleitungRepository;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin-only lifecycle service for the upper org-hierarchy tiers (epic #692, REQ-ORG-014): creating
 * {@link Bereich} and {@link Organisationsleitung} rows and wiring the {@code parent_org_unit_id}
 * edges (Staffel/SK → Bereich, Bereich → OL). It deliberately does <strong>not</strong> inject
 * {@code OwnerScopeService}: like {@link SquadronService} / {@link SpecialCommandService} it is
 * pure org-unit lifecycle administration gated to ADMIN at the controller, with no per-caller
 * scope. The cascading <em>reach</em> that the hierarchy unlocks is computed elsewhere
 * (REQ-ORG-015, a later phase).
 *
 * <p>The parent-kind pairing is validated here for a clean 400, and additionally pinned by the V164
 * {@code validate_org_unit_parent} trigger and the {@code chk_org_unit_ol_has_no_parent} CHECK as
 * the DB backstop. The OL is treated as a singleton: a second active one is rejected with 409.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrgHierarchyService {

  private final BereichRepository bereichRepository;
  private final OrganisationsleitungRepository organisationsleitungRepository;
  private final OrgUnitRepository orgUnitRepository;

  /**
   * Lists Bereiche for the admin overview.
   *
   * @param includeInactive when {@code true}, include soft-deleted rows.
   * @return the Bereiche in repository order; never {@code null}.
   */
  public List<Bereich> listBereiche(boolean includeInactive) {
    return includeInactive ? bereichRepository.findAll() : bereichRepository.findAllByActiveTrue();
  }

  /**
   * Lists the Organisationsleitung row(s) — normally exactly one.
   *
   * @param includeInactive when {@code true}, include a soft-deleted OL.
   * @return the OL row(s); never {@code null}, normally a singleton list.
   */
  public List<Organisationsleitung> listOrganisationsleitung(boolean includeInactive) {
    return includeInactive
        ? organisationsleitungRepository.findAll()
        : organisationsleitungRepository.findAllByActiveTrue();
  }

  /**
   * Lists every active org unit across all four kinds (Staffel, SK, Bereich, OL) for the admin
   * hierarchy-management surface, parent eagerly loaded so each row exposes its current parent edge
   * and optimistic-lock version in one read.
   *
   * @return the active org units (parent pre-loaded) in arbitrary order; never {@code null}.
   */
  public List<OrgUnit> listAllOrgUnits() {
    return orgUnitRepository.findAllActiveWithParent();
  }

  /**
   * Creates a Bereich, optionally already wired under the Organisationsleitung. The same-kind
   * case-insensitive name check surfaces a clean 409 before the global {@code org_unit.name} UNIQUE
   * constraint trips (a cross-kind collision is caught at flush time as a 409 like the SK path).
   *
   * @param name display name; required.
   * @param shorthand short tag; required.
   * @param description free-form text; nullable.
   * @param parentOrgUnitId the owning OL's id, or {@code null} to leave the Bereich unparented for
   *     now; when non-null it must reference an {@code ORGANISATIONSLEITUNG}.
   * @param department the Kartell department / Bereichsfarbe (epic #692, REQ-ORG-026), or {@code
   *     null} to leave the Bereich untinted in the org chart for now.
   * @return the persisted Bereich.
   * @throws DuplicateEntityException if a Bereich with that name already exists.
   * @throws BadRequestException if {@code parentOrgUnitId} is not an Organisationsleitung.
   * @throws NotFoundException if {@code parentOrgUnitId} references no org unit.
   */
  @Transactional
  public Bereich createBereich(
      @NotNull String name,
      @NotNull String shorthand,
      @Nullable String description,
      @Nullable UUID parentOrgUnitId,
      @Nullable Department department) {
    if (bereichRepository.existsByNameIgnoreCase(name)) {
      throw new DuplicateEntityException("A Bereich with the name '" + name + "' already exists.");
    }
    Bereich bereich = new Bereich();
    bereich.setName(name);
    bereich.setShorthand(shorthand);
    bereich.setDescription(description);
    bereich.setDepartment(department);
    if (parentOrgUnitId != null) {
      bereich.setParent(requireKind(parentOrgUnitId, OrgUnitKind.ORGANISATIONSLEITUNG));
    }
    return bereichRepository.save(bereich);
  }

  /**
   * Creates the Organisationsleitung. There is exactly one OL, so a second active one is rejected.
   *
   * @param name display name; required.
   * @param shorthand short tag; required.
   * @param description free-form text; nullable.
   * @return the persisted OL.
   * @throws DuplicateEntityException if an active Organisationsleitung already exists, or a Bereich
   *     /SK with the same name does.
   */
  @Transactional
  public Organisationsleitung createOrganisationsleitung(
      @NotNull String name, @NotNull String shorthand, @Nullable String description) {
    if (!organisationsleitungRepository.findAllByActiveTrue().isEmpty()) {
      throw new DuplicateEntityException(
          "An Organisationsleitung already exists — there is exactly one.");
    }
    Organisationsleitung ol = new Organisationsleitung();
    ol.setName(name);
    ol.setShorthand(shorthand);
    ol.setDescription(description);
    return organisationsleitungRepository.save(ol);
  }

  /**
   * Sets (or clears) an org unit's parent in the hierarchy, validating the kind pairing: a Staffel
   * or SK must be parented to a Bereich, a Bereich to the Organisationsleitung, and the OL has no
   * parent. A {@code null} parent detaches the unit. The child's optimistic-lock version is
   * checked.
   *
   * @param orgUnitId the child org unit to (re)parent; never {@code null}.
   * @param parentOrgUnitId the new parent's id, or {@code null} to detach.
   * @param version the child's optimistic-lock version.
   * @return the persisted child org unit.
   * @throws NotFoundException if the child or the parent id references no org unit.
   * @throws BadRequestException if the parent kind does not match the child's level.
   * @throws ObjectOptimisticLockingFailureException if the supplied version is stale.
   */
  @Transactional
  public OrgUnit setParent(
      @NotNull UUID orgUnitId, @Nullable UUID parentOrgUnitId, @Nullable Long version) {
    OrgUnit child = Entities.require(orgUnitRepository.findById(orgUnitId), "Org unit not found");
    OptimisticLock.check(child.getVersion(), version, OrgUnit.class, orgUnitId);
    if (parentOrgUnitId == null) {
      child.setParent(null);
      return orgUnitRepository.saveAndFlush(child);
    }
    OrgUnit parent =
        Entities.require(orgUnitRepository.findById(parentOrgUnitId), "Parent org unit not found");
    validateParentKind(child, parent);
    child.setParent(parent);
    return orgUnitRepository.saveAndFlush(child);
  }

  /**
   * Loads the org unit and asserts it is of the expected kind, for create-time parent resolution.
   *
   * @param orgUnitId the org unit to load.
   * @param expected the required kind.
   * @return the loaded org unit.
   * @throws NotFoundException if no org unit matches.
   * @throws BadRequestException if the kind does not match.
   */
  private OrgUnit requireKind(UUID orgUnitId, OrgUnitKind expected) {
    OrgUnit unit =
        Entities.require(orgUnitRepository.findById(orgUnitId), "Parent org unit not found");
    if (unit.getKind() != expected) {
      throw new BadRequestException(
          "Parent org unit must be of kind " + expected + " but was " + unit.getKind());
    }
    return unit;
  }

  /**
   * Enforces the fixed three-level pairing: Staffel/SK → Bereich, Bereich → OL, OL → (no parent).
   *
   * @param child the org unit being parented.
   * @param parent the proposed parent.
   * @throws BadRequestException if the pairing is invalid.
   */
  private void validateParentKind(OrgUnit child, OrgUnit parent) {
    switch (child.getKind()) {
      case SQUADRON, SPECIAL_COMMAND -> {
        if (parent.getKind() != OrgUnitKind.BEREICH) {
          throw new BadRequestException(
              "A Staffel or Spezialkommando must be parented to a Bereich, not "
                  + parent.getKind());
        }
      }
      case BEREICH -> {
        if (parent.getKind() != OrgUnitKind.ORGANISATIONSLEITUNG) {
          throw new BadRequestException(
              "A Bereich must be parented to the Organisationsleitung, not " + parent.getKind());
        }
      }
      case ORGANISATIONSLEITUNG ->
          throw new BadRequestException("The Organisationsleitung has no parent.");
      default -> throw new IllegalStateException("Unhandled org-unit kind: " + child.getKind());
    }
  }
}
