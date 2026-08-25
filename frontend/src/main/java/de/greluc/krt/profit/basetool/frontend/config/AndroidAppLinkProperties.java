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
 * What {@code /.well-known/assetlinks.json} publishes, so Android will hand the login callback to
 * the app instead of to a browser.
 *
 * <p>The Android app's production redirect URI is an App Link — {@code
 * https://profit-base.online/app/callback} — rather than a custom scheme, so that no other
 * installed app can claim it. Android only honours that claim after fetching this file and finding
 * the app's package name and signing-certificate digest in it. Until then the callback opens in the
 * browser, which has no such route, and the member lands on the 404 page mid-login.
 *
 * <p><strong>{@link #sha256CertFingerprints()} is a list, and that is the point.</strong> A signing
 * key rotation has to publish the new digest <em>before</em> the rotated APK ships, while the old
 * one is still installed on every device — so both must be servable at once. A single-value
 * property would force a window in which one of the two populations is broken.
 *
 * @param packageName the production application id; the {@code .dev} flavour is deliberately absent
 *     because it uses a custom scheme and never needs verification.
 * @param sha256CertFingerprints upper-case, colon-separated SHA-256 digests of every signing
 *     certificate that may currently claim the domain.
 */
@Validated
@ConfigurationProperties(prefix = "app.android-app-link")
public record AndroidAppLinkProperties(
    @NotBlank String packageName,
    @NotEmpty
        List<
                @Pattern(
                    regexp = "^([0-9A-F]{2}:){31}[0-9A-F]{2}$",
                    // Literal, not a message-bundle key: this fails at STARTUP and is read by an
                    // operator in a log, never by a member in a browser. An unresolved key would
                    // print its own braces at exactly the moment someone needs the answer.
                    message =
                        "must be an upper-case, colon-separated SHA-256 certificate digest, "
                            + "e.g. AB:CD:...:EF (32 pairs)")
                String>
            sha256CertFingerprints) {}
