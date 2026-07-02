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

package com.sk89q.worldguard.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public class MessageBundleTest {

    @Test
    public void bundledResourceIsPresent() {
        InputStream in = MessageBundle.class.getResourceAsStream("/lang/en-US.yml");
        assertNotNull(in, "Bundled /lang/en-US.yml must be on the classpath");
    }

    @Test
    public void resolvesKnownKeys() {
        MessageBundle bundle = MessageBundle.bundledOnly();
        assertEquals("Region: ", bundle.get("commands.region.info.region-label"));
        assertEquals("Region Info", bundle.get("commands.region.info.title"));
        assertEquals("God mode disabled!", bundle.get("commands.ungod.self"));
    }

    @Test
    public void substitutesPlaceholders() {
        MessageBundle bundle = MessageBundle.bundledOnly();
        assertEquals("God enabled by Steve.",
                bundle.format("commands.god.by-other", "actor", "Steve"));
    }

    @Test
    public void keepsMessagesWithoutColorCodesUnchanged() {
        // Default appearance is preserved when a value has no '&' codes.
        assertEquals("Region: ", MessageBundle.bundledOnly().get("commands.region.info.region-label"));
    }

    @Test
    public void translatesColorCodes(@TempDir Path dataFolder) throws Exception {
        Path langDir = dataFolder.resolve("lang");
        Files.createDirectories(langDir);
        Files.writeString(langDir.resolve("en-US.yml"),
                "commands:\n  stack:\n    done: \"&cTest\"\n");
        MessageBundle bundle = MessageBundle.load(dataFolder.toFile(), "en-US");
        // '&' codes become the section-sign codes the client renders.
        assertEquals("§cTest", bundle.get("commands.stack.done"));
    }

    @Test
    public void loadsBundledRussianLocale(@TempDir Path dataFolder) {
        MessageBundle bundle = MessageBundle.load(dataFolder.toFile(), "ru-RU");
        // A key that exists in ru-RU must resolve to a non-English, non-key value.
        String title = bundle.get("commands.region.info.title");
        assertNotEquals("commands.region.info.title", title, "key must resolve");
        assertNotEquals("Region Info", title, "ru-RU must override the English default");
    }
}
