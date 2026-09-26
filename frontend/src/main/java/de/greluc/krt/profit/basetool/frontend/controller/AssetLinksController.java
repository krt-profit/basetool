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

package de.greluc.krt.profit.basetool.frontend.controller;

import de.greluc.krt.profit.basetool.frontend.config.AndroidAppLinkProperties;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves {@code /.well-known/assetlinks.json}, which Android fetches to verify that this domain
 * belongs to the Basetool app. A controller guarantees status {@code 200}, {@code application/json}
 * and no redirect; the certificate digests come from {@link AndroidAppLinkProperties} so a key
 * rotation can publish old and new together.
 */
@RestController
@RequiredArgsConstructor
public class AssetLinksController {

  /** Relation Android checks: this app may handle every URL on the domain. */
  private static final String HANDLE_ALL_URLS = "delegate_permission/common.handle_all_urls";

  /** What to publish: package name and the currently valid signing-certificate digests. */
  private final AndroidAppLinkProperties properties;

  /**
   * Returns the Digital Asset Links statement list for the Android app, cacheable for a day.
   *
   * @return the statement list, always exactly one entry, as {@code application/json}
   */
  @GetMapping(path = "/.well-known/assetlinks.json", produces = MediaType.APPLICATION_JSON_VALUE)
  public @NotNull ResponseEntity<List<Map<String, Object>>> assetLinks() {
    final var statement =
        Map.<String, Object>of(
            "relation",
            List.of(HANDLE_ALL_URLS),
            "target",
            Map.of(
                "namespace",
                "android_app",
                "package_name",
                properties.packageName(),
                "sha256_cert_fingerprints",
                properties.sha256CertFingerprints()));
    return ResponseEntity.ok()
        .header("Cache-Control", "public, max-age=86400")
        .body(List.of(statement));
  }
}
