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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import java.util.List;

/**
 * Reconstructed manual money figures for one order.
 *
 * @param target requested net amount
 * @param targetVersion independent target edit revision
 * @param provided total recorded new provision
 * @param holdings currently held purpose-bound amount
 * @param netHandedOver effective net delivered to the requester
 * @param remaining net amount still due
 * @param holders current order-local holders and their booked amounts
 * @param history durable accepted provisions, projected with permitted labels
 * @param canProvideSelf whether the caller can report a new own provision
 * @param canProvideOnBehalf whether the caller is also the current responsible writer
 * @param canDelete whether an administrator can delete before any accepted submission
 */
public record JobOrderMoneyView(
    String target,
    long targetVersion,
    String provided,
    String holdings,
    String netHandedOver,
    String remaining,
    List<JobOrderMoneyHolderView> holders,
    List<JobOrderMoneyOutcome> history,
    boolean canProvideSelf,
    boolean canProvideOnBehalf,
    boolean canDelete) {}
