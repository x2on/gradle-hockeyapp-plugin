package de.felixschulze.gradle.util

import java.util.regex.PatternSyntaxException

class FileHelperTest extends GroovyTestCase {

    void testNullChecks() {
        assertNull(FileHelper.getFile(null, null))
        assertNull(FileHelper.getFile("*", null))
        assertNull(FileHelper.getFile(null, new File(".")))
    }

    void testNotExistingDirectory() {
        assertNull(FileHelper.getFile("*", new File("u4wzteg7ufgew78w49ufzw97hezgo4ew89gwhu")))
    }

    void testWrongRegex() {
        shouldFail(PatternSyntaxException) {
            FileHelper.getFile("*", new File("."))
        }
    }

    void testCorrectRegex() {
        File file = FileHelper.getFile(".*.md", new File("."))
        assertNotNull(file)
        assertEquals(file.name, "README.md")
    }

    void testCorrectRegexButNoFilePresent() {
        assertNull(FileHelper.getFile(".*.xyzxyz", new File(".")))
    }

    void testNotADirectory() {
        shouldFail(IllegalArgumentException) {
            FileHelper.getFile(".*", new File("build.gradle"))
        }
    }
}
