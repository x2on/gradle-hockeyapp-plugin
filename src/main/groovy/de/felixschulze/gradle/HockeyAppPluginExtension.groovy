/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2013 Felix Schulze
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.

 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package de.felixschulze.gradle

import org.gradle.api.Project

class HockeyAppPluginExtension {
    def Object outputDirectory
    def Object symbolsDirectory
    def String apiToken = null
    def String notes = "This build was uploaded using the gradle-hockeyapp-plugin"
    def String status = 2
    def String notify = 0
    def String notesType = 1
    def String releaseType = 0
    def String appFileNameRegex = ".*.ipa"
    def String mappingFileNameRegex = ".*.dSYM.zip"
    def String commitSha = null
    def String buildServerUrl = null
    def String repositoryUrl = null
    def boolean privatePage = null
    def Map<String, String> variantToApplicationId = null

    private final Project project

    public HockeyAppPluginExtension(Project project) {
        this.project = project
        this.outputDirectory = {
            return project.project.getBuildDir()
        }
        this.symbolsDirectory = this.outputDirectory
    }

    File getOutputDirectory() {
        return project.file(outputDirectory)
    }

    void setOutputDirectory(Object outputDirectory) {
        this.outputDirectory = outputDirectory
    }

    File getSymbolsDirectory() {
        return project.file(symbolsDirectory)
    }

    void setSymbolsDirectory(Object symbolsDirectory) {
        this.symbolsDirectory = symbolsDirectory
    }


}
