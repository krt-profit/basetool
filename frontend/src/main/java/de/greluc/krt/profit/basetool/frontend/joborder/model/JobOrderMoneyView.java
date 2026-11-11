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

package de.greluc.krt.profit.basetool.frontend.joborder.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.List;

/**
 * Monetary totals, history and the caller's current aUEC order actions.
 *
 * @param target the requested net amount
 * @param targetVersion the target's independent version
 * @param provided the effective provisions
 * @param holdings the current order holdings
 * @param netHandedOver the effective net delivered amount
 * @param remaining the still outstanding net target
 * @param holders the current order-local holdings
 * @param history the visible provisions
 * @param canProvideSelf whether the reader can report their own funds
 * @param canProvideOnBehalf whether the writer can report another player's funds
 * @param canDelete whether the caller can hard-delete the order
 * @param fees the effective fees charged to the order
 * @param overpaid the excess handed over above the target
 * @param recipients the designated recipient identities
 * @param canDesignateRecipient whether the caller can manage recipients
 * @param canEditTarget whether the caller can edit the net target
 * @param canEditInformation whether the caller can edit the contact and comment
 * @param informationVersion the independent information revision
 * @param canMoveForOthers whether the caller can move other holders' funds
 * @param canRecordRequesterHandover whether the caller can record delivery for the requester
 * @param targetChanges the recorded changes to the order's net target
 */
@DtoMirror
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
    boolean canDelete,
    String fees,
    String overpaid,
    List<JobOrderMoneyRecipientView> recipients,
    boolean canDesignateRecipient,
    boolean canEditTarget,
    boolean canEditInformation,
    long informationVersion,
    boolean canMoveForOthers,
    boolean canRecordRequesterHandover,
    List<JobOrderMoneyTargetChangeView> targetChanges) {}
