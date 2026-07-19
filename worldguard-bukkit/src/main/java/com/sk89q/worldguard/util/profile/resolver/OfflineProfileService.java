/*
 * WorldGuard, a suite of tools for Minecraft
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldGuard team and contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.sk89q.worldguard.util.profile.resolver;

import com.sk89q.worldguard.util.profile.Profile;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A profile service for offline-mode (cracked) servers that derives a player's
 * UUID directly from the name, matching the algorithm Bukkit uses for offline
 * players ({@code UUID.nameUUIDFromBytes("OfflinePlayer:" + name)}).
 *
 * <p>This performs no network lookups, so it never contacts Mojang and never
 * fails: a name always resolves to the same UUID the player receives when they
 * join an offline server. It is meant to replace the Mojang HTTP lookup in the
 * profile chain when the server runs in offline mode.</p>
 */
public final class OfflineProfileService extends SingleRequestService {

    private static final OfflineProfileService INSTANCE = new OfflineProfileService();

    private OfflineProfileService() {
    }

    /**
     * Compute the offline-mode UUID for a player name.
     *
     * @param name the player name
     * @return the offline UUID Bukkit would assign to that name
     */
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public int getIdealRequestLimit() {
        return Integer.MAX_VALUE;
    }

    @Override
    public Profile findByName(String name) {
        return new Profile(offlineUuid(name), name);
    }

    @Nullable
    @Override
    public Profile findByUuid(UUID uuid) {
        // Offline UUIDs are a one-way hash of the name, so they cannot be
        // reversed back into a name here.
        return null;
    }

    /**
     * Get the singleton instance.
     *
     * @return the instance
     */
    public static OfflineProfileService getInstance() {
        return INSTANCE;
    }
}
