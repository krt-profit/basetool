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

import de.greluc.krt.profit.basetool.backend.model.DefaultBlueprint;
import de.greluc.krt.profit.basetool.backend.model.SystemSetting;
import de.greluc.krt.profit.basetool.backend.repository.DefaultBlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.SystemSettingRepository;
import de.greluc.krt.profit.basetool.backend.service.BlueprintProductService.ResolvedProduct;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Startup bootstrap for the default-blueprint feature (REQ-INV-016/017). Runs once per boot, after
 * Flyway and the rest of the context are ready, and does two things:
 *
 * <ol>
 *   <li><strong>One-time seed</strong> of the admin-managed {@code default_blueprint} table from
 *       {@link DefaultBlueprintCatalog}, guarded by a {@code SystemSetting} flag so an admin's
 *       later removal of a seeded default is never resurrected on the next boot. Each curated name
 *       is normalized and resolved against the live blueprint catalog to stamp the canonical key /
 *       name / output item; an unresolved name is still seeded (degraded) so the default is granted
 *       regardless, with a warning so an admin can re-resolve it through the picker.
 *   <li><strong>Backfill</strong> — grants the current default set to every existing user, so the
 *       feature takes effect immediately on the deploy that introduces it without waiting for the
 *       periodic sweep. Idempotent ({@code ON CONFLICT DO NOTHING}).
 * </ol>
 *
 * <p>Gated by {@code app.default-blueprints.provisioning.enabled} (default on) so the test profile
 * can disable it; integration tests that exercise the feature re-enable it explicitly.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
    prefix = "app.default-blueprints.provisioning",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class DefaultBlueprintBootstrap implements CommandLineRunner {

  /** {@code SystemSetting} key whose presence/{@code "true"} value marks the one-time seed done. */
  static final String SEEDED_FLAG_KEY = "defaultBlueprints.seeded";

  private final SystemSettingRepository systemSettingRepository;
  private final DefaultBlueprintRepository defaultBlueprintRepository;
  private final BlueprintProductService blueprintProductService;
  private final GameItemRepository gameItemRepository;
  private final BlueprintNameNormalizer normalizer;
  private final DefaultBlueprintProvisioningService provisioningService;
  private final DefaultBlueprintKeyService keyService;

  /**
   * Seeds the default set once, then backfills the default blueprints for all existing users.
   *
   * @param args ignored command-line arguments
   */
  @Override
  @Transactional
  public void run(String... args) {
    seedOnce();
    keyService.refresh();
    int granted = provisioningService.grantDefaultsToAllUsers();
    if (granted > 0) {
      log.info("Default-blueprint startup backfill granted {} owned row(s).", granted);
    }
  }

  /** Seeds the curated starter defaults exactly once, guarded by the {@code SystemSetting} flag. */
  private void seedOnce() {
    if (alreadySeeded()) {
      return;
    }
    int seeded = 0;
    for (String displayName : DefaultBlueprintCatalog.STARTER_PRODUCT_NAMES) {
      if (seedOne(displayName)) {
        seeded++;
      }
    }
    markSeeded();
    log.info(
        "Seeded {} of {} starter default blueprint(s).",
        seeded,
        DefaultBlueprintCatalog.STARTER_PRODUCT_NAMES.size());
  }

  /**
   * Seeds one curated default if its normalized key is not already present.
   *
   * @param displayName the curated display name
   * @return {@code true} if a row was inserted
   */
  private boolean seedOne(String displayName) {
    String key = normalizer.normalize(displayName);
    if (key.isEmpty() || defaultBlueprintRepository.existsByProductKey(key)) {
      return false;
    }
    DefaultBlueprint entity = new DefaultBlueprint();
    entity.setCreatedBy("system");
    Optional<ResolvedProduct> resolved = blueprintProductService.resolveByProductKey(key);
    if (resolved.isPresent()) {
      ResolvedProduct product = resolved.get();
      entity.setProductKey(product.productKey());
      entity.setProductName(product.productName());
      if (product.outputItemId() != null) {
        entity.setOutputItem(gameItemRepository.getReferenceById(product.outputItemId()));
      }
    } else {
      log.warn(
          "Default blueprint '{}' did not resolve against the blueprint catalog; seeding degraded"
              + " row (key='{}').",
          displayName,
          key);
      entity.setProductKey(key);
      entity.setProductName(displayName);
    }
    defaultBlueprintRepository.save(entity);
    return true;
  }

  /**
   * Whether the one-time seed has already run.
   *
   * @return {@code true} if the seed flag is present and {@code "true"}
   */
  private boolean alreadySeeded() {
    return systemSettingRepository
        .findById(SEEDED_FLAG_KEY)
        .map(s -> "true".equalsIgnoreCase(s.getValue()))
        .orElse(false);
  }

  /** Persists the seed flag so the one-time seed never runs again. */
  private void markSeeded() {
    SystemSetting flag =
        systemSettingRepository
            .findById(SEEDED_FLAG_KEY)
            .orElseGet(
                () -> {
                  SystemSetting s = new SystemSetting();
                  s.setId(SEEDED_FLAG_KEY);
                  return s;
                });
    flag.setValue("true");
    systemSettingRepository.save(flag);
  }
}
