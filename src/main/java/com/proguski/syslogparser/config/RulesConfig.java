package com.proguski.syslogparser.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** rules.yaml — defines, defaults and the ordered rule list (changed often). */
public record RulesConfig(
        Map<String, String> defines,
        Map<String, Object> defaultsTemplate,
        List<RuleConfig> rules) {

    /** One rule as written in YAML, before regex/template compilation. */
    public record RuleConfig(
            String name,
            boolean enabled,
            String prefilter,
            String regex,
            String outputTopic,
            String outputKey,
            Map<String, Object> template) {
    }

    public static RulesConfig load(Path path) {
        Map<String, Object> root = Yaml.loadFile(path);

        Map<String, String> defines = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : Yaml.map(root, "defines", "rules").entrySet()) {
            defines.put(e.getKey(), String.valueOf(e.getValue()));
        }

        Map<String, Object> defaults = Yaml.map(root, "defaults", "rules");
        Map<String, Object> defaultsTemplate = Yaml.map(defaults, "template", "rules.defaults");

        List<RuleConfig> rules = new ArrayList<>();
        Object rulesNode = root.get("rules");
        if (rulesNode == null) {
            throw new ConfigException(path + ": missing \"rules\" list");
        }
        int i = 0;
        for (Object item : Yaml.asList(rulesNode, "rules")) {
            Map<String, Object> r = Yaml.asMap(item, "rules[" + i + "]");
            String name = Yaml.str(r, "name", null);
            String where = "rule " + (name != null ? "\"" + name + "\"" : "#" + i);
            if (name == null || name.isBlank()) {
                throw new ConfigException("rules[" + i + "]: missing required field \"name\"");
            }
            Map<String, Object> output = Yaml.map(r, "output", where);
            rules.add(new RuleConfig(
                    name,
                    Yaml.boolVal(r, "enabled", true),
                    Yaml.str(r, "prefilter", null),
                    Yaml.requireStr(r, "regex", where),
                    Yaml.requireStr(output, "topic", where + ".output"),
                    Yaml.str(output, "key", null),
                    Yaml.map(r, "template", where)));
            i++;
        }
        return new RulesConfig(defines, defaultsTemplate, rules);
    }
}
