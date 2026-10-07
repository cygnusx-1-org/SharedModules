package com.liskovsoft.sharedutils.helpers;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
public class FileHelpersTest {
    @Rule
    public TemporaryFolder mFolder = new TemporaryFolder();

    @Test
    public void contentsAsWritten() throws IOException {
        File file = mFolder.newFile("data");
        StringBuilder data = new StringBuilder();

        // Bigger than any read buffer, with characters of 1 to 4 bytes in UTF-8
        while (data.length() < 300_000) {
            data.append("Title • Ünïcödé 日本語 😀&vi;12345&si;");
        }

        FileHelpers.stringToFile(data.toString(), file);

        assertEquals(data.toString(), FileHelpers.getFileContents(file));
    }

    @Test
    public void emptyFile() throws IOException {
        assertEquals("", FileHelpers.getFileContents(mFolder.newFile("empty")));
    }

    @Test
    public void missingFile() {
        assertNull(FileHelpers.getFileContents(new File(mFolder.getRoot(), "missing")));
        assertNull(FileHelpers.getFileContents(null));
    }
}
