package de.felixschulze.gradle.util

import java.util.regex.PatternSyntaxException

class FileHelperTest extends GroovyTestCase {

    void testNullChecks() {
        assertEquals([], FileHelper.getFiles(null, null))
        assertEquals([], FileHelper.getFiles(".*", null))
        assertEquals([], FileHelper.getFiles(null, new File(".")))
        assertNull(FileHelper.getFile(null, null))
        assertNull(FileHelper.getFile(".*", null))
        assertNull(FileHelper.getFile(null, new File(".")))
    }

    void testNotExistingDirectory() {
        assertEquals([], FileHelper.getFiles(".*", new File("u4wzteg7ufgew78w49ufzw97hezgo4ew89gwhu")))
        assertNull(FileHelper.getFile(".*", new File("u4wzteg7ufgew78w49ufzw97hezgo4ew89gwhu")))
    }

    void testWrongRegex() {
        shouldFail(PatternSyntaxException) {
            FileHelper.getFiles("*", new File("."))
        }
        shouldFail(PatternSyntaxException) {
            FileHelper.getFile("*", new File("."))
        }
    }

    void testCorrectRegex() {
        File[] files = FileHelper.getFiles(".+\\.gradle", new File("."))
        assertLength(2, files)
        assertEquals(["build.gradle", "settings.gradle"], files.sort().collect { it.name })
        File file = FileHelper.getFile(".*.md", new File("."))
        assertNotNull(file)
        assertEquals(file.name, "README.md")
    }

    void testCorrectRegexButNoFilePresent() {
        assertEquals([], FileHelper.getFiles(".*.xyzxyz", new File(".")))
        assertNull(FileHelper.getFile(".*.xyzxyz", new File(".")))
    }

    void testNotADirectory() {
        shouldFail(IllegalArgumentException) {
            FileHelper.getFiles(".*", new File("build.gradle"))
        }
        shouldFail(IllegalArgumentException) {
            FileHelper.getFile(".*", new File("build.gradle"))
        }
    }
}
