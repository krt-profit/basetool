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

package de.greluc.krt.profit.basetool.backend.event;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * After-commit event published when an admin approves or rejects a pending registration
 * (REQ-NOTIF-014), consumed by {@code UserApprovalMailEventListener} to send the decision e-mail.
 *
 * <p>The recipient's address and name are captured inside the deciding transaction and are never
 * logged.
 *
 * @param userId the decided user's id, for correlation only
 * @param approved {@code true} for an approval, {@code false} for a rejection
 * @param recipientEmail the user's e-mail address, or {@code null} when none is on file
 * @param recipientName the user's effective display name for the greeting, or {@code null}
 * @param reason the rejection justification to include in the mail, or {@code null}
 */
public record UserApprovalDecidedEvent(
    @NotNull UUID userId,
    boolean approved,
    @Nullable String recipientEmail,
    @Nullable String recipientName,
    @Nullable String reason) {}
