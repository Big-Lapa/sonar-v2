package com.sonar.trading.i18n;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin panel is a static page translated client-side from {@code static/admin/i18n/{en,ru}.json}.
 * Backend error/notification codes ({@link MessageCode}) are translated there too, under {@code msg.<CODE>}.
 */
class AdminI18nDictionaryTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");
    private static final Pattern CYRILLIC = Pattern.compile("[\\u0400-\\u04FF]");
    private static final Pattern DATA_I18N = Pattern.compile("data-i18n(?:-[a-z]+)?=\"([^\"]+)\"");

    private static Map<String, String> load(String lang) throws IOException {
        try (InputStream in = AdminI18nDictionaryTest.class.getResourceAsStream("/static/admin/i18n/" + lang + ".json")) {
            assertThat(in).as("dictionary %s.json on the classpath", lang).isNotNull();
            return new ObjectMapper().readValue(in, new TypeReference<>() {});
        }
    }

    private static String indexHtml() throws IOException {
        try (InputStream in = AdminI18nDictionaryTest.class.getResourceAsStream("/static/admin/index.html")) {
            assertThat(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Set<String> placeholders(String s) {
        Set<String> out = new HashSet<>();
        Matcher m = PLACEHOLDER.matcher(s);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    @Test
    void dictionariesHaveTheSameKeys() throws IOException {
        assertThat(load("ru").keySet()).containsExactlyInAnyOrderElementsOf(load("en").keySet());
    }

    @Test
    void dictionariesHaveTheSamePlaceholdersPerKey() throws IOException {
        Map<String, String> en = load("en");
        Map<String, String> ru = load("ru");
        for (String key : en.keySet()) {
            assertThat(placeholders(ru.get(key))).as("placeholders of %s", key).isEqualTo(placeholders(en.get(key)));
        }
    }

    @Test
    void englishDictionaryHasNoCyrillicAndRussianOneIsActuallyTranslated() throws IOException {
        Map<String, String> en = load("en");
        Map<String, String> ru = load("ru");
        en.forEach((k, v) -> assertThat(CYRILLIC.matcher(v).find()).as("en[%s]", k).isFalse());
        long translated = ru.entrySet().stream().filter(e -> CYRILLIC.matcher(e.getValue()).find()).count();
        assertThat(translated).as("ru entries containing Cyrillic").isGreaterThan(ru.size() / 2);
    }

    @Test
    void everyBackendMessageCodeIsTranslatedInBothLanguages() throws IOException {
        Map<String, String> en = load("en");
        Map<String, String> ru = load("ru");
        for (MessageCode code : MessageCode.values()) {
            assertThat(en).as("en has msg.%s", code).containsKey("msg." + code.name());
            assertThat(ru).as("ru has msg.%s", code).containsKey("msg." + code.name());
        }
    }

    @Test
    void everyKeyUsedInTheMarkupExists() throws IOException {
        Map<String, String> en = load("en");
        Matcher m = DATA_I18N.matcher(indexHtml());
        int seen = 0;
        while (m.find()) {
            seen++;
            assertThat(en).as("key used in index.html").containsKey(m.group(1));
        }
        assertThat(seen).as("data-i18n attributes in index.html").isGreaterThan(20);
    }

    @Test
    void pageIsEnglishByDefaultAndHasALanguageSwitcher() throws IOException {
        String html = indexHtml();
        assertThat(html).contains("<html lang=\"en\"");
        assertThat(html).contains("id=\"lang-switch\"");
        assertThat(CYRILLIC.matcher(html).find()).as("Cyrillic left in index.html").isFalse();
    }
}
