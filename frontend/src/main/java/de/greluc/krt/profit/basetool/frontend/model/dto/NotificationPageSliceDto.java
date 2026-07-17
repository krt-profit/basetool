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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import java.util.List;

/**
 * One page of the {@code /notifications} inbox as consumed by the load-more control
 * (REQ-NOTIF-019): the localized items of the requested page plus the paging facts the client needs
 * to keep its "showing X of Y" hint truthful and to hide the control once everything is loaded.
 *
 * @param items the localized notification views of the requested page, newest first
 * @param totalElements the caller's total notification count across all pages
 * @param hasMore whether at least one further page exists after the requested one
 */
public record NotificationPageSliceDto(
    List<NotificationViewDto> items, long totalElements, boolean hasMore) {}
