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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
        assertEquals("(none)", bundle.get("commands.region.info.none"));
    }

    @Test
    public void substitutesPlaceholders() {
        MessageBundle bundle = MessageBundle.bundledOnly();
        assertEquals("Adding region 'spawn'",
                bundle.format("commands.region.define.task-adding", "id", "spawn"));
    }

    @Test
    public void getSectionReturnsLeafEntries() {
        MessageBundle bundle = MessageBundle.bundledOnly();
        // The usage-args section is used to translate command-usage placeholders.
        assertEquals("<flag>", bundle.getSection("plugin.usage-args").get("<flag>"));
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

    @Test
    public void migrateAddsNewKeysRemovesObsoleteAndKeepsEdits(@TempDir Path dataFolder) throws Exception {
        Path langDir = dataFolder.resolve("lang");
        Files.createDirectories(langDir);
        Path langFile = langDir.resolve("en-US.yml");
        // An out-of-date file: old version, a hand-edited value, an obsolete key,
        // and missing most of the keys the bundled file has.
        Files.writeString(langFile,
                "lang-version: 0\n"
                        + "commands:\n"
                        + "  fire:\n"
                        + "    already-disabled: 'CUSTOM VALUE'\n"
                        + "    obsolete-key: 'gone'\n");

        MessageBundle.migrate(dataFolder.toFile(), "en-US");

        String migrated = Files.readString(langFile);
        // Administrator's edit is preserved.
        assertTrue(migrated.contains("CUSTOM VALUE"), "hand-edited value must be kept");
        // Obsolete key is dropped, new keys are added, version is bumped.
        assertFalse(migrated.contains("obsolete-key"), "obsolete key must be removed");
        assertTrue(migrated.contains("already-enabled"), "new keys must be added");
        assertTrue(migrated.contains("lang-version: 1"), "version must be updated");
        // A backup of the previous file is created.
        assertTrue(Files.exists(langDir.resolve("en-US-1.yml.bak")), "backup must be written");

        // The kept value is loadable through the bundle.
        MessageBundle bundle = MessageBundle.load(dataFolder.toFile(), "en-US");
        assertEquals("CUSTOM VALUE", bundle.get("commands.fire.already-disabled"));
    }

    @Test
    public void migrateKeepsNewlinesInEditedMultilineValues(@TempDir Path dataFolder) throws Exception {
        Path langDir = dataFolder.resolve("lang");
        Files.createDirectories(langDir);
        Path langFile = langDir.resolve("en-US.yml");
        // Out-of-date file whose only key is a hand-edited multiline message.
        Files.writeString(langFile,
                "lang-version: 0\n"
                        + "commands:\n"
                        + "  region:\n"
                        + "    store:\n"
                        + "      migrate-dangerous: \"First line\\nSecond line\"\n");

        MessageBundle.migrate(dataFolder.toFile(), "en-US");

        // The newline in the administrator's edit must survive the round-trip.
        MessageBundle bundle = MessageBundle.load(dataFolder.toFile(), "en-US");
        assertEquals("First line\nSecond line",
                bundle.get("commands.region.store.migrate-dangerous"));
    }

    @Test
    public void migrateLeavesUpToDateFileUntouched(@TempDir Path dataFolder) throws Exception {
        // A fresh file created by load() is already up to date and must not be rewritten.
        MessageBundle.load(dataFolder.toFile(), "en-US");
        Path langFile = dataFolder.resolve("lang").resolve("en-US.yml");
        String before = Files.readString(langFile);

        MessageBundle.migrate(dataFolder.toFile(), "en-US");

        assertEquals(before, Files.readString(langFile), "up-to-date file must not change");
        assertFalse(Files.exists(dataFolder.resolve("lang").resolve("en-US-1.yml.bak")),
                "no backup should be created when nothing changes");
    }
}
