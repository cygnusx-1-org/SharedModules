package com.liskovsoft.sharedutils.helpers;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Random;
import java.util.regex.Pattern;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class HelpersTest {

    @Test
    public void testNearlyEqual() {
        assertTrue("Values is equal", Helpers.nearlyEqual(59.96f, 60.004f, 0.1f));
        assertTrue("Values is equal", Helpers.nearlyEqual(60.004f, 59.96f, 0.1f));
        assertFalse("Values not equal", Helpers.nearlyEqual(59.96f, 24.554f, 1f));
        assertFalse("Values not equal", Helpers.nearlyEqual(24.554f, 59.96f, 1f));
        assertTrue("Values is equal", Helpers.nearlyEqual(59.96f, 24.554f, 36f));
        assertTrue("Values is equal", Helpers.nearlyEqual(24.554f, 59.96f, 36f));
    }

    /**
     * The split without a regex keeps String.split's rules: a leading empty part stays, the trailing ones go
     */
    @Test
    public void splitLikeStringSplit() {
        String[] cases = {"a", "a&vi;b", "&vi;a", "a&vi;", "a&vi;&vi;", "&vi;", "&vi;&vi;", "a&vi;&vi;b", "&vi;&vi;a&vi;&vi;", "a&vi",
                "a&vi;b&vi;null&vi;", "&vi;&vi;&vi;b"};

        for (String data : cases) {
            assertArrayEquals(data, data.split(Pattern.quote("&vi;")), Helpers.split(data, "&vi;"));
        }

        assertArrayEquals(new String[]{}, Helpers.split(" ", "&vi;"));
        assertArrayEquals(new String[]{"a", "b"}, Helpers.split("a|b", "|")); // a regex character taken as it is
    }

    @Test
    public void splitLikeStringSplitOnRandomData() {
        Random random = new Random(42);
        String[] pieces = {"", "a", "bc", "&", "vi", ";", "&vi;", "&v", "null", "1.5"};

        for (int round = 0; round < 10_000; round++) {
            StringBuilder data = new StringBuilder("x");
            int count = random.nextInt(8);

            for (int i = 0; i < count; i++) {
                data.append(pieces[random.nextInt(pieces.length)]);
            }

            String value = data.toString();
            assertArrayEquals(value, value.split(Pattern.quote("&vi;")), Helpers.split(value, "&vi;"));
        }
    }

    /**
     * The same results as the String.matches they replace
     */
    @Test
    public void numbersAndWords() {
        String[] values = {"", "1", "-1", "+15", "1.0", "-1.0", ".5", "5.", "1e5", "abc", "12:34", "a1", " 1", "1 ", "LIVE", "4K", "-", "１２",
                "+", ".", "-.", "-.5", "1.2.3", "1..5", "--1", "+-1", "0", "00.00", "1\n", "754000", "45.833332", "١٢", "۱۲:۳۴", "-۱.۲", "१२"};

        for (String value : values) {
            assertMatchesLikeRegex(value);
        }

        assertFalse(Helpers.isNumeric(null));
        assertFalse(Helpers.isInteger(null));
    }

    @Test
    public void numbersLikeRegexOnRandomData() {
        Random random = new Random(42);
        char[] chars = {'0', '5', '9', '-', '+', '.', 'a', ' ', 'e', '\u0661', '\u06F1', '\uFF11'};

        for (int round = 0; round < 20_000; round++) {
            StringBuilder value = new StringBuilder();
            int length = random.nextInt(7);

            for (int i = 0; i < length; i++) {
                value.append(chars[random.nextInt(chars.length)]);
            }

            assertMatchesLikeRegex(value.toString());
        }
    }

    /**
     * The numbers as Android's regex sees them: its \d takes any decimal digit, while the JVM's takes 0-9 only
     */
    private static void assertMatchesLikeRegex(String value) {
        assertEquals(value, Pattern.compile("^[-+]?\\d*\\.?\\d+$", Pattern.UNICODE_CHARACTER_CLASS).matcher(value).matches(), Helpers.isNumeric(value));
        assertEquals(value, Pattern.compile("^[-+]?\\d+$", Pattern.UNICODE_CHARACTER_CLASS).matcher(value).matches(), Helpers.isInteger(value));
        assertEquals(value, value.matches("^.*[^\\d\\W]+.*$"), Helpers.hasWords(value));
        assertEquals(value, value.matches("^.*[-+]?\\d*\\.?\\d+.*$"), Helpers.hasDigits(value));
    }
}
