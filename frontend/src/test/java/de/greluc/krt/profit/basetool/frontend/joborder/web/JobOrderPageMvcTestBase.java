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

package de.greluc.krt.profit.basetool.frontend.joborder.web;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.livesync.LiveSyncLocalBus;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Declares the one mock set every job-order page render test shares, so they reuse a single cached
 * application context (REQ-OPS-041): the backend seam, the client registrations, the page reader
 * and the live-sync bus.
 */
@SpringBootTest
abstract class JobOrderPageMvcTestBase {

  @MockitoBean protected BackendApiClient backendApiClient;

  @MockitoBean protected JobOrderPageReader reader;

  @MockitoBean protected LiveSyncLocalBus liveSyncLocalBus;

  @MockitoBean protected ClientRegistrationRepository clientRegistrationRepository;
}
