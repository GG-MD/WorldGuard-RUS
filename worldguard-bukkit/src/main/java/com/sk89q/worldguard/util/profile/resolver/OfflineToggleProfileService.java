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

import com.google.common.collect.ImmutableList;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.config.ConfigurationManager;
import com.sk89q.worldguard.util.profile.Profile;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.UUID;
import java.util.function.Predicate;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A {@link ProfileService} that delegates to one of two underlying chains
 * depending on the {@code regions.offline-uuid} configuration option, chosen
 * lazily on every request.
 *
 * <p>The choice is made per request (rather than once at construction) because
 * the profile service is built before the configuration is read, and because it
 * lets {@code /wg reload} change the behaviour without a restart. Both chains are
 * built up front, so switching is just a cheap field read.</p>
 *
 * <ul>
 *     <li>{@code disabled} &mdash; always use the {@code online} chain (Mojang lookup).</li>
 *     <li>{@code enabled} &mdash; always use the {@code offline} chain (local UUID).</li>
 *     <li>{@code auto} (default) &mdash; use the {@code offline} chain only when the
 *         server itself runs in offline mode and is not behind an online-mode proxy.</li>
 * </ul>
 */
public final class OfflineToggleProfileService implements ProfileService {

    private final ProfileService online;
    private final ProfileService offline;
    private final boolean serverIsOffline;

    /**
     * Create a new instance.
     *
     * @param online the chain to use in online mode (Mojang lookup)
     * @param offline the chain to use in offline mode (local UUID generation)
     * @param serverIsOffline whether the server runs in offline mode without an online proxy
     */
    public OfflineToggleProfileService(ProfileService online, ProfileService offline, boolean serverIsOffline) {
        checkNotNull(online);
        checkNotNull(offline);
        this.online = online;
        this.offline = offline;
        this.serverIsOffline = serverIsOffline;
    }

    private ProfileService active() {
        String mode = "auto";
        try {
            // getPlatform() throws if called before the platform is set, so guard the
            // whole read and fall back to "auto" rather than trusting a null check.
            ConfigurationManager config = WorldGuard.getInstance().getPlatform().getGlobalStateManager();
            if (config != null && config.offlineUuidMode != null) {
                mode = config.offlineUuidMode;
            }
        } catch (RuntimeException ignored) {
            // Platform/config not ready yet; keep the "auto" default.
        }

        boolean useOffline;
        switch (mode) {
            case "enabled":
                useOffline = true;
                break;
            case "disabled":
                useOffline = false;
                break;
            default: // "auto"
                useOffline = serverIsOffline;
        }
        return useOffline ? offline : online;
    }

    @Override
    public int getIdealRequestLimit() {
        return active().getIdealRequestLimit();
    }

    @Nullable
    @Override
    public Profile findByName(String name) throws IOException, InterruptedException {
        return active().findByName(name);
    }

    @Override
    public ImmutableList<Profile> findAllByName(Iterable<String> names) throws IOException, InterruptedException {
        return active().findAllByName(names);
    }

    @Override
    public void findAllByName(Iterable<String> names, Predicate<Profile> consumer) throws IOException, InterruptedException {
        active().findAllByName(names, consumer);
    }

    @Nullable
    @Override
    public Profile findByUuid(UUID uuid) throws IOException, InterruptedException {
        return active().findByUuid(uuid);
    }

    @Override
    public ImmutableList<Profile> findAllByUuid(Iterable<UUID> uuids) throws IOException, InterruptedException {
        return active().findAllByUuid(uuids);
    }

    @Override
    public void findAllByUuid(Iterable<UUID> uuids, Predicate<Profile> consumer) throws IOException, InterruptedException {
        active().findAllByUuid(uuids, consumer);
    }
}
