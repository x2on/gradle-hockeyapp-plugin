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

/**
 * Extension for plugin config properties
 */
class HockeyAppPluginExtension {
    def Object outputDirectory
    def File symbolsDirectory = null
    def String apiToken = null
    def Map<String, String> variantToApiToken = null
    def String notes = "This build was uploaded using the gradle-hockeyapp-plugin"
    def Map<String, String> variantToNotes = null
    def String status = 2
    def String notify = 0
    def Map<String, String> variantToNotify = null
    def String notesType = 1
    def Map<String, String> variantToNotesType = null
    def String releaseType = 0
    def Map<String, String> variantToReleaseType = null
    def String appFileNameRegex = null
    def String mappingFileNameRegex = "mapping.txt"
    def String commitSha = null
    def String buildServerUrl = null
    def String repositoryUrl = null
    def String tags = null
    def Map<String, String> variantToTags = null
    def String teams = null
	def String users = null
    def int timeout = 60 * 1000
    def Map<String, String> variantToApplicationId = null
    def Boolean teamCityLog = false
    def Map<String, String> variantToStatus = null
    def String mandatory = 0
    def Map<String, String> variantToMandatory = null
    def String hockeyApiUrl = "https://rink.hockeyapp.net/api/2/apps"
    def boolean allowMultipleAppFiles = true


    private final Project project

    public HockeyAppPluginExtension(Project project) {
        this.project = project
        this.outputDirectory = {
            return project.project.getBuildDir()
        }
    }

    File getOutputDirectory() {
        return project.file(outputDirectory)
    }

    void setOutputDirectory(Object outputDirectory) {
        this.outputDirectory = outputDirectory
    }


}
