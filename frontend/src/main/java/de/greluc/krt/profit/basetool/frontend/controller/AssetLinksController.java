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
 * Serves {@code /.well-known/assetlinks.json}, the file Android fetches to decide whether this
 * domain really belongs to the Basetool app.
 *
 * <p>Without it the app's login is broken in a way that looks like a server fault: the production
 * redirect URI is the App Link {@code https://profit-base.online/app/callback}, Android declines to
 * open an unverified link in the app, the browser follows it instead, and the member ends up on the
 * 404 page in the middle of signing in.
 *
 * <p><strong>A controller rather than a file under {@code static/}.</strong> Three of the things
 * Android insists on are properties of the response, not of a file: status {@code 200}, content
 * type {@code application/json}, and <em>no redirect</em>. A static resource behind this
 * application's security chain satisfied none of them — the path fell through to {@code
 * anyRequest().authenticated()}, which answered {@code 302} into the OAuth entry point. That is the
 * same trap the {@code /sm/**} and {@code /**}{@code /*.map} entries beside it in {@code
 * SecurityConfig} were added for. Serving it from code makes all three testable.
 *
 * <p>The digests come from configuration ({@link AndroidAppLinkProperties}) rather than a constant,
 * because a key rotation must publish the new one while the old one is still installed everywhere.
 */
@RestController
@RequiredArgsConstructor
public class AssetLinksController {

  /** Relation Android checks: this app may handle every URL on the domain. */
  private static final String HANDLE_ALL_URLS = "delegate_permission/common.handle_all_urls";

  /** What to publish: package name and the currently valid signing-certificate digests. */
  private final AndroidAppLinkProperties properties;

  /**
   * Returns the Digital Asset Links statement list for the Android app.
   *
   * <p>Cached for a day: Android re-fetches on its own schedule and the content changes only on a
   * key rotation, which is announced ahead of time anyway. A shorter age would buy nothing and a
   * longer one would slow a rotation down.
   *
   * @return the statement list, always exactly one entry, as {@code application/json}.
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
