package com.proguski.syslogparser.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Typed access helpers over SnakeYAML's generic Map/List output. */
public final class Yaml {

    private Yaml() {}

    public static Map<String, Object> loadFile(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            org.yaml.snakeyaml.Yaml yaml =
                    new org.yaml.snakeyaml.Yaml(new SafeConstructor(new LoaderOptions()));
            Object doc = yaml.load(in);
            if (doc == null) {
                return new LinkedHashMap<>();
            }
            return asMap(doc, path.toString());
        } catch (IOException e) {
            throw new ConfigException("cannot read " + path + ": " + e.getMessage(), e);
        } catch (org.yaml.snakeyaml.error.YAMLException e) {
            throw new ConfigException("invalid YAML in " + path + ": " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o, String what) {
        if (!(o instanceof Map)) {
            throw new ConfigException(what + ": expected a mapping, got "
                    + (o == null ? "null" : o.getClass().getSimpleName()));
        }
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o, String what) {
        if (!(o instanceof List)) {
            throw new ConfigException(what + ": expected a list, got "
                    + (o == null ? "null" : o.getClass().getSimpleName()));
        }
        return (List<Object>) o;
    }

    public static Map<String, Object> map(Map<String, Object> parent, String key, String where) {
        Object o = parent.get(key);
        if (o == null) return new LinkedHashMap<>();
        return asMap(o, where + "." + key);
    }

    public static String str(Map<String, Object> parent, String key, String def) {
        Object o = parent.get(key);
        return o == null ? def : String.valueOf(o);
    }

    public static String requireStr(Map<String, Object> parent, String key, String where) {
        Object o = parent.get(key);
        if (o == null || String.valueOf(o).isBlank()) {
            throw new ConfigException(where + ": missing required field \"" + key + "\"");
        }
        return String.valueOf(o);
    }

    public static int intVal(Map<String, Object> parent, String key, int def) {
        Object o = parent.get(key);
        if (o == null) return def;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            throw new ConfigException("field \"" + key + "\" is not an integer: " + o);
        }
    }

    public static boolean boolVal(Map<String, Object> parent, String key, boolean def) {
        Object o = parent.get(key);
        if (o == null) return def;
        if (o instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(o));
    }
}
