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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * Internal key that stores the version of a language file, used to decide
     * whether an on-disk file needs migrating. It is not a user-facing message.
     */
    public static final String VERSION_KEY = "lang-version";

    private static final Logger log = Logger.getLogger(MessageBundle.class.getCanonicalName());
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");
    // Matches a "key:" or "key: value" line, capturing indent, key and trailing value.
    private static final Pattern YAML_ENTRY = Pattern.compile("^(\\s*)([^\\s#:][^:]*):(\\s(.*))?$");

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
     * Migrate the administrator's on-disk language files to match the language
     * files shipped in the current jar.
     *
     * <p>For every existing {@code lang/<locale>.yml} that has a bundled
     * counterpart, new keys are added and obsolete keys are removed. The
     * administrator's own message values are preserved, and the comments and
     * structure of the bundled template are used for the rewritten file (any
     * comments the administrator added to their own file are not carried over).
     * A migration only runs when the file's key set differs from the bundled one
     * or its {@link #VERSION_KEY} is older; otherwise the file is left untouched.
     * The previous file is copied to a {@code .bak} beside it first. Missing files
     * are left to {@link #load} to create fresh.</p>
     *
     * @param dataFolder the plugin data folder (where {@code config.yml} lives)
     * @param locale the active locale; migrated together with {@code en-US}
     */
    public static void migrate(File dataFolder, String locale) {
        File langDir = new File(dataFolder, "lang");
        if (!langDir.isDirectory()) {
            return;
        }

        // Migrate the active locale, the default locale and any other locale the
        // administrator already has a file for.
        Set<String> locales = new LinkedHashSet<>();
        if (locale != null && !locale.trim().isEmpty()) {
            locales.add(locale.trim());
        }
        locales.add(DEFAULT_LOCALE);
        File[] existing = langDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (existing != null) {
            for (File file : existing) {
                String name = file.getName();
                locales.add(name.substring(0, name.length() - ".yml".length()));
            }
        }

        for (String loc : locales) {
            try {
                migrateLocale(langDir, loc);
            } catch (IOException | RuntimeException e) {
                log.log(Level.WARNING, "Failed to migrate WorldGuard language file for " + loc, e);
            }
        }
    }

    private static void migrateLocale(File langDir, String locale) throws IOException {
        File langFile = new File(langDir, locale + ".yml");
        if (!langFile.exists()) {
            return;
        }

        List<String> bundledLines = readBundledLines(locale);
        if (bundledLines == null) {
            // No bundled reference for this locale; nothing to migrate against.
            return;
        }

        Map<String, String> bundledValues = loadBundled(locale);
        Map<String, String> userValues = loadFromFile(langFile);

        Set<String> bundledKeys = messageKeys(bundledValues);
        Set<String> userKeys = messageKeys(userValues);
        if (userKeys.equals(bundledKeys) && parseVersion(userValues) >= parseVersion(bundledValues)) {
            return; // already up to date
        }

        File backup = uniqueBackup(langDir, locale);
        Files.copy(langFile.toPath(), backup.toPath());

        List<String> merged = rebuild(bundledLines, bundledValues, userValues);
        Files.write(langFile.toPath(), merged, StandardCharsets.UTF_8);

        Set<String> added = new LinkedHashSet<>(bundledKeys);
        added.removeAll(userKeys);
        Set<String> removed = new LinkedHashSet<>(userKeys);
        removed.removeAll(bundledKeys);
        log.info("Migrated WorldGuard language file lang/" + locale + ".yml ("
                + added.size() + " keys added, " + removed.size() + " removed); "
                + "previous file saved as lang/" + backup.getName());
    }

    /**
     * Rebuild a language file from the bundled template line by line, keeping its
     * comments and structure, but substituting the administrator's own values for
     * keys they changed. New keys keep the bundled default; obsolete keys are
     * absent because they are not in the template.
     */
    private static List<String> rebuild(List<String> bundledLines,
                                        Map<String, String> bundledValues,
                                        Map<String, String> userValues) {
        List<String> out = new ArrayList<>(bundledLines.size());
        List<Integer> indents = new ArrayList<>();
        List<String> names = new ArrayList<>();

        for (String line : bundledLines) {
            Matcher m = YAML_ENTRY.matcher(line);
            if (!m.matches()) {
                out.add(line); // comment, blank line, or anything we don't parse
                continue;
            }

            int indent = m.group(1).length();
            String key = m.group(2).trim();
            boolean hasValue = m.group(4) != null && !m.group(4).trim().isEmpty();

            // Pop deeper-or-equal levels so the path reflects this line's indent.
            while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                indents.remove(indents.size() - 1);
                names.remove(names.size() - 1);
            }
            String fullKey = names.isEmpty() ? key : String.join(".", names) + "." + key;

            if (!hasValue) {
                indents.add(indent);
                names.add(key);
                out.add(line);
                continue;
            }

            // Keep the internal version line as shipped (the target version).
            if (!fullKey.equals(VERSION_KEY)
                    && userValues.containsKey(fullKey)
                    && !userValues.get(fullKey).equals(bundledValues.get(fullKey))) {
                out.add(m.group(1) + key + ": " + quote(userValues.get(fullKey)));
            } else {
                out.add(line);
            }
        }
        return out;
    }

    private static String quote(String value) {
        // Values with newlines/tabs cannot be a single-quoted YAML scalar (a literal
        // line break there is silently folded away), so use a double-quoted scalar
        // with escapes for them, matching how the bundled files store such messages.
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\t') >= 0) {
            String escaped = value
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\r", "\\r")
                    .replace("\n", "\\n")
                    .replace("\t", "\\t");
            return "\"" + escaped + "\"";
        }
        return "'" + value.replace("'", "''") + "'";
    }

    private static Set<String> messageKeys(Map<String, String> values) {
        Set<String> keys = new LinkedHashSet<>(values.keySet());
        keys.remove(VERSION_KEY);
        return keys;
    }

    private static int parseVersion(Map<String, String> values) {
        String raw = values.get(VERSION_KEY);
        if (raw == null) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static File uniqueBackup(File langDir, String locale) {
        int index = 1;
        File candidate;
        do {
            candidate = new File(langDir, locale + "-" + index + ".yml.bak");
            index++;
        } while (candidate.exists());
        return candidate;
    }

    private static List<String> readBundledLines(String locale) {
        InputStream in = MessageBundle.class.getResourceAsStream(resourcePath(locale));
        if (in == null) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "Could not read bundled language resource for " + locale, e);
            return null;
        }
        return lines;
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
