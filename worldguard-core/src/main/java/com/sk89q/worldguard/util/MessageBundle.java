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

import com.google.common.collect.ImmutableMap;
import com.sk89q.worldedit.util.formatting.text.serializer.legacy.LegacyComponentSerializer;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Holds the user-facing messages of WorldGuard, loaded from a YAML language
 * file so that server administrators can customize them without touching code.
 *
 * <p>Messages are looked up by dotted key (for example
 * {@code "commands.fire.already-disabled"}). Values may contain
 * {@code {placeholder}} tokens that are substituted at format time. Lookups
 * never fail: a value present in the admin-editable file wins, otherwise the
 * built-in default shipped inside the jar is used, and if a key is completely
 * unknown the key itself is returned so that a mistake is visible rather than
 * throwing.</p>
 *
 * <p>The default language shipped in the jar is {@code en-US} and always
 * contains every key, so it acts as the ultimate fallback regardless of the
 * selected locale.</p>
 */
public final class MessageBundle {

    /**
     * The locale that is always bundled in the jar and used as the final
     * fallback for any missing key.
     */
    public static final String DEFAULT_LOCALE = "en-US";

    private static final Logger log = Logger.getLogger(MessageBundle.class.getCanonicalName());
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");

    private final Map<String, String> messages;
    private final Map<String, String> builtinDefaults;

    private MessageBundle(Map<String, String> messages, Map<String, String> builtinDefaults) {
        this.messages = ImmutableMap.copyOf(messages);
        this.builtinDefaults = ImmutableMap.copyOf(builtinDefaults);
    }

    /**
     * Create a bundle backed only by the built-in defaults shipped in the jar,
     * without touching the disk.
     *
     * <p>Used as a safe default before the plugin data folder is known, so that
     * message lookups work even if something queries them very early.</p>
     *
     * @return a bundle containing only the built-in {@code en-US} defaults
     */
    public static MessageBundle bundledOnly() {
        Map<String, String> builtinDefaults = loadBundled(DEFAULT_LOCALE);
        return new MessageBundle(builtinDefaults, builtinDefaults);
    }

    /**
     * Load the message bundle for the given locale.
     *
     * <p>If {@code lang/<locale>.yml} does not exist inside {@code dataFolder}
     * it is created from the bundled default so that administrators have a file
     * to edit. The bundled {@code en-US} defaults are always loaded as a
     * fallback, guaranteeing that every key resolves to something.</p>
     *
     * @param dataFolder the plugin data folder (where {@code config.yml} lives)
     * @param locale the locale to load, such as {@code "en-US"}
     * @return a loaded message bundle, never {@code null}
     */
    public static MessageBundle load(File dataFolder, String locale) {
        String requested = (locale == null || locale.trim().isEmpty()) ? DEFAULT_LOCALE : locale.trim();

        Map<String, String> builtinDefaults = loadBundled(DEFAULT_LOCALE);

        // Active messages start from the complete built-in defaults so that no
        // key is ever missing, then get overlaid by any translation shipped in
        // the jar and finally by the administrator's on-disk file.
        Map<String, String> active = new LinkedHashMap<>(builtinDefaults);
        if (!requested.equals(DEFAULT_LOCALE)) {
            active.putAll(loadBundled(requested));
        }

        File langDir = new File(dataFolder, "lang");
        File langFile = new File(langDir, requested + ".yml");
        copyDefaultIfMissing(langDir, langFile, requested);
        active.putAll(loadFromFile(langFile));

        return new MessageBundle(active, builtinDefaults);
    }

    /**
     * Get the raw message for a key, without placeholder substitution.
     *
     * @param key the dotted message key
     * @return the message text, or the key itself if it is unknown
     */
    public String get(String key) {
        String message = messages.get(key);
        if (message == null) {
            message = builtinDefaults.get(key);
        }
        if (message == null) {
            log.warning("Missing WorldGuard message for key: " + key);
            return key;
        }
        return colorize(message);
    }

    /**
     * Get all key/value entries that live directly or indirectly under a dotted
     * prefix, with the prefix stripped from the returned keys.
     *
     * <p>For example, {@code getSection("plugin.usage-args")} returns a map whose
     * keys are the leaf names (such as {@code "<flag>"}) and whose values are the
     * corresponding messages. Values are returned raw, without colour
     * processing, since sections are typically used for token substitution rather
     * than display. The built-in defaults are included first and then overlaid by
     * the active locale, so a partially translated section still resolves every
     * entry.</p>
     *
     * @param prefix the dotted section prefix, without a trailing dot
     * @return a map of leaf key to value, empty if the section is absent
     */
    public Map<String, String> getSection(String prefix) {
        String dotted = prefix + ".";
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : builtinDefaults.entrySet()) {
            if (entry.getKey().startsWith(dotted)) {
                out.put(entry.getKey().substring(dotted.length()), entry.getValue());
            }
        }
        for (Map.Entry<String, String> entry : messages.entrySet()) {
            if (entry.getKey().startsWith(dotted)) {
                out.put(entry.getKey().substring(dotted.length()), entry.getValue());
            }
        }
        return out;
    }

    /**
     * Translate legacy {@code &} colour/format codes (e.g. {@code &c}, {@code &l})
     * in a message into the section-sign codes the client renders.
     *
     * <p>This lets administrators colour any message directly in the language
     * file. It is a no-op for messages that contain no {@code &}, so the default
     * appearance is unchanged.</p>
     *
     * @param message the raw message
     * @return the message with colour codes applied
     */
    private static String colorize(String message) {
        if (message.indexOf('&') < 0) {
            return message;
        }
        return LegacyComponentSerializer.INSTANCE.serialize(
                LegacyComponentSerializer.INSTANCE.deserialize(message, '&'));
    }

    /**
     * Get a message and substitute {@code {placeholder}} tokens.
     *
     * <p>Placeholders are supplied as alternating name/value pairs, for example
     * {@code format("commands.fire.disabled-broadcast", "world", worldName,
     * "actor", senderName)}. Unknown placeholders in the template are left
     * untouched.</p>
     *
     * @param key the dotted message key
     * @param placeholders alternating placeholder names and values
     * @return the formatted message
     */
    public String format(String key, Object... placeholders) {
        String template = get(key);
        if (placeholders.length == 0) {
            return template;
        }
        if (placeholders.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "Placeholders must be supplied as name/value pairs, got " + placeholders.length + " arguments");
        }

        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < placeholders.length; i += 2) {
            values.put(String.valueOf(placeholders[i]), String.valueOf(placeholders[i + 1]));
        }
        return substitute(template, values);
    }

    private static String substitute(String template, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = values.get(name);
            matcher.appendReplacement(builder, Matcher.quoteReplacement(value != null ? value : matcher.group()));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }

    private static void copyDefaultIfMissing(File langDir, File langFile, String locale) {
        if (langFile.exists()) {
            return;
        }
        // Prefer a bundled file for the exact locale, otherwise fall back to the
        // default locale so the administrator still gets a usable template.
        String resource = resourcePath(locale);
        InputStream in = MessageBundle.class.getResourceAsStream(resource);
        if (in == null) {
            resource = resourcePath(DEFAULT_LOCALE);
            in = MessageBundle.class.getResourceAsStream(resource);
        }
        if (in == null) {
            return;
        }
        try (InputStream stream = in) {
            if (!langDir.exists() && !langDir.mkdirs()) {
                log.warning("Could not create WorldGuard language folder: " + langDir);
                return;
            }
            Files.copy(stream, langFile.toPath());
        } catch (IOException e) {
            log.log(Level.WARNING, "Could not write default language file " + langFile, e);
        }
    }

    private static Map<String, String> loadFromFile(File file) {
        if (!file.exists()) {
            return new LinkedHashMap<>();
        }
        try (InputStream in = Files.newInputStream(file.toPath())) {
            return flatten(in);
        } catch (IOException | RuntimeException e) {
            log.log(Level.WARNING, "Could not read WorldGuard language file " + file
                    + "; using built-in defaults for its messages.", e);
            return new LinkedHashMap<>();
        }
    }

    private static Map<String, String> loadBundled(String locale) {
        InputStream in = MessageBundle.class.getResourceAsStream(resourcePath(locale));
        if (in == null) {
            return new LinkedHashMap<>();
        }
        try (InputStream stream = in) {
            return flatten(stream);
        } catch (IOException | RuntimeException e) {
            log.log(Level.WARNING, "Could not read bundled language resource for " + locale, e);
            return new LinkedHashMap<>();
        }
    }

    private static String resourcePath(String locale) {
        return "/lang/" + locale + ".yml";
    }

    private static Map<String, String> flatten(InputStream in) {
        // Tolerate duplicate keys (keep the last, like Bukkit's own config loader)
        // so a single stray duplicate can never wipe out the whole file.
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(true);
        Object root = new Yaml(new SafeConstructor(options)).load(new InputStreamReader(in, StandardCharsets.UTF_8));
        Map<String, String> flat = new LinkedHashMap<>();
        if (root instanceof Map) {
            flatten("", (Map<?, ?>) root, flat);
        }
        return flat;
    }

    private static void flatten(String prefix, Map<?, ?> node, Map<String, String> out) {
        for (Map.Entry<?, ?> entry : node.entrySet()) {
            String key = prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) {
                flatten(key, (Map<?, ?>) value, out);
            } else if (value != null) {
                out.put(key, String.valueOf(value));
            }
        }
    }
}
