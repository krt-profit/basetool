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
import de.greluc.krt.profit.basetool.backend.mapper.DefaultBlueprintMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.DefaultBlueprint;
import de.greluc.krt.profit.basetool.backend.model.dto.DefaultBlueprintResponse;
import de.greluc.krt.profit.basetool.backend.repository.DefaultBlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.service.BlueprintProductService.ResolvedProduct;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin curation of the default-blueprint set (REQ-INV-017), the products granted to every user
 * automatically (REQ-INV-016).
 *
 * <p>Adding a default resolves it against the live catalog, refreshes the key cache and grants it
 * to every user. Removing one leaves users' existing rows in place as ordinary, removable
 * blueprints.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class DefaultBlueprintService {

  private final DefaultBlueprintRepository repository;
  private final DefaultBlueprintMapper mapper;
  private final BlueprintProductService blueprintProductService;
  private final GameItemRepository gameItemRepository;
  private final DefaultBlueprintProvisioningService provisioningService;
  private final DefaultBlueprintKeyService keyService;
  private final AuditService auditService;

  /**
   * Lists the current default set, alphabetically by product name, for the admin page.
   *
   * @return the default-blueprint entries
   */
  @NotNull
  public List<DefaultBlueprintResponse> list() {
    return repository.findAll(Sort.by(Sort.Order.asc("productName").ignoreCase())).stream()
        .map(mapper::toResponse)
        .toList();
  }

  /**
   * Adds a product to the default set and grants it to every existing user. The product key is
   * resolved against the active blueprint catalog; the canonical name and output item are stamped
   * onto the new row.
   *
   * @param productKey normalized product key of the product to mark as default
   * @param createdBy the adding admin's {@code app_user.id} rendered as text, or {@code "system"}
   *     for a seeded row (provenance, not a foreign key)
   * @return the persisted DTO
   * @throws NotFoundException if the product key matches no active product
   * @throws DuplicateEntityException if the product is already a default
   */
  @Transactional
  @NotNull
  public DefaultBlueprintResponse add(@NotNull String productKey, @Nullable String createdBy) {
    ResolvedProduct product =
        Entities.require(
            blueprintProductService.resolveByProductKey(productKey),
            () -> "Blueprint product not found: " + productKey);
    if (repository.existsByProductKey(product.productKey())) {
      throw new DuplicateEntityException(
          "Default blueprint '" + product.productName() + "' already exists.");
    }
    DefaultBlueprint entity = new DefaultBlueprint();
    entity.setProductKey(product.productKey());
    entity.setProductName(product.productName());
    if (product.outputItemId() != null) {
      entity.setOutputItem(gameItemRepository.getReferenceById(product.outputItemId()));
    }
    entity.setCreatedBy(createdBy);
    DefaultBlueprint saved = repository.save(entity);
    auditService.record(
        AuditEventType.BLUEPRINT_DEFAULT_ADDED,
        saved.getId(),
        saved.getProductName(),
        null,
        AuditDetails.of("product", saved.getProductKey()));
    keyService.refresh();
    int granted = provisioningService.grantDefaultsToAllUsers();
    log.info(
        "Added default blueprint productKey='{}' by={}; granted {} new owned row(s)",
        saved.getProductKey(),
        createdBy,
        granted);
    return mapper.toResponse(saved);
  }

  /**
   * Removes a product from the default set. Existing users keep the blueprints already granted to
   * them (they become ordinary removable owned rows); only the curated default row is deleted.
   *
   * @param id default-blueprint entry id
   * @throws NotFoundException when the id is unknown
   */
  @Transactional
  public void remove(@NotNull UUID id) {
    DefaultBlueprint entity =
        Entities.require(repository.findById(id), () -> "DefaultBlueprint not found: " + id);
    repository.delete(entity);
    auditService.record(
        AuditEventType.BLUEPRINT_DEFAULT_REMOVED,
        id,
        entity.getProductName(),
        null,
        AuditDetails.of("product", entity.getProductKey()));
    keyService.refresh();
    log.info("Removed default blueprint id={} productKey='{}'", id, entity.getProductKey());
  }
}
