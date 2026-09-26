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

package de.greluc.krt.profit.basetool.frontend.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The package names and signing-certificate digests {@code /.well-known/assetlinks.json} publishes,
 * so Android opens the App Link login callback in the app.
 *
 * <p>The digests are a list so old and new keys can be published together during a rotation.
 *
 * @param packageName the production application id
 * @param sha256CertFingerprints upper-case, colon-separated SHA-256 digests of every signing
 *     certificate that may claim the domain
 */
@Validated
@ConfigurationProperties(prefix = "app.android-app-link")
public record AndroidAppLinkProperties(
    @NotBlank String packageName,
    @NotEmpty
        List<
                @Pattern(
                    regexp = "^([0-9A-F]{2}:){31}[0-9A-F]{2}$",
                    message =
                        "must be an upper-case, colon-separated SHA-256 certificate digest, "
                            + "e.g. AB:CD:...:EF (32 pairs)")
                String>
            sha256CertFingerprints) {}
