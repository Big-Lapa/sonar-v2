package com.sonar.trading.i18n;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A {@link MessageCode} with its parameters; {@link #text()} renders the English fallback. */
public record Msg(MessageCode code, Map<String, String> params) {

    private static final Pattern GROUP = Pattern.compile("\\[([^\\[\\]]*)]");
    private static final Pattern PARAM = Pattern.compile("\\{(\\w+)}");

    /** {@code Msg.of(CODE, "k1", v1, "k2", v2)}; a null value means the parameter is absent. */
    public static Msg of(MessageCode code, Object... kv) {
        if (kv.length % 2 != 0) throw new IllegalArgumentException("key/value pairs expected");
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] != null) params.put(String.valueOf(kv[i]), String.valueOf(kv[i + 1]));
        }
        return new Msg(code, params);
    }

    public String text() {
        return render(code.template(), params);
    }

    static String render(String template, Map<String, String> params) {
        Matcher g = GROUP.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (g.find()) {
            String inner = g.group(1);
            boolean complete = PARAM.matcher(inner).results().allMatch(m -> params.containsKey(m.group(1)));
            g.appendReplacement(sb, Matcher.quoteReplacement(complete ? inner : ""));
        }
        g.appendTail(sb);
        Matcher p = PARAM.matcher(sb.toString());
        StringBuilder out = new StringBuilder();
        while (p.find()) {
            p.appendReplacement(out, Matcher.quoteReplacement(params.getOrDefault(p.group(1), "")));
        }
        p.appendTail(out);
        return out.toString();
    }
}
