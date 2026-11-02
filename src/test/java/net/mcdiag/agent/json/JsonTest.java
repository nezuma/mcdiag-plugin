package net.mcdiag.agent.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class JsonTest {

    private static String str(String s, int max) {
        return Json.str(new StringBuilder(), s, max).toString();
    }

    @Test
    void escapesControlQuotesAndNonAscii() {
        assertEquals("\"a\\\"b\\\\c\\n\\t\\u0001\"", str("a\"b\\c\n\t\u0001", 100));
        assertEquals("\"\\u043c\\u0438\\u0440\"", str("мир", 100));
    }

    @Test
    void truncatesWithoutSplittingSurrogatePair() {
        String emoji = "ab😀";
        assertEquals("\"ab\"", str(emoji, 3));
        assertEquals("\"ab\\ud83d\\ude00\"", str(emoji, 4));
    }

    @Test
    void sanitizesEnumNames() {
        assertEquals("ENTITY_ATTACK", Json.enumName("ENTITY_ATTACK"));
        assertEquals("BREEZE_WIND_CHARGE", Json.enumName("BREEZE_WIND_CHARGE1"));
        assertNull(Json.enumName("123"));
        assertNull(Json.enumName(null));
    }

    @Test
    void formatsTwoDecimals() {
        assertEquals("19.98", Json.decimal2(new StringBuilder(), 19.9799).toString());
        assertEquals("20.05", Json.decimal2(new StringBuilder(), 20.05).toString());
        assertEquals("0.07", Json.decimal2(new StringBuilder(), 0.07).toString());
        assertEquals("0", Json.decimal2(new StringBuilder(), Double.NaN).toString());
        assertEquals("12.00", Json.decimal2(new StringBuilder(), 12).toString());
    }
}
