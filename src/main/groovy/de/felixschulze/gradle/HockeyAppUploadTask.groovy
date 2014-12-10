/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2013-2014 Felix Schulze
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

import de.felixschulze.gradle.util.ProgressHttpEntityWrapper
import de.felixschulze.teamcity.TeamCityProgressType
import de.felixschulze.teamcity.TeamCityStatusMessageHelper
import groovy.json.JsonSlurper
import org.apache.http.HttpHost
import org.apache.http.HttpResponse
import org.apache.http.client.HttpClient
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.mime.MultipartEntityBuilder
import org.apache.http.entity.mime.content.FileBody
import org.apache.http.entity.mime.content.StringBody
import org.apache.http.impl.client.HttpClientBuilder
import org.gradle.api.DefaultTask
import org.gradle.api.Nullable
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.TaskAction
import org.gradle.logging.ProgressLogger
import org.gradle.logging.ProgressLoggerFactory

import java.util.regex.Pattern

class HockeyAppUploadTask extends DefaultTask {

    File applicationFile
    File symbolsDirectory
    File mappingFile
    String variantName
    boolean mightHaveMapping = true   // Specify otherwise in Android config


    HockeyAppUploadTask() {
        super()
        this.description = "Uploads the app (Android: (.apk, mapping.txt), iOS:(.ipa, .dsym)) to HockeyApp"
    }


    @TaskAction
    def upload() throws IOException {

        if (!getApiToken()) {
            throw new IllegalArgumentException("Cannot upload to HockeyApp because API Token is missing")
        }

        if (!applicationFile?.exists()) {
            applicationFile = getFile(project.hockeyapp.appFileNameRegex, project.hockeyapp.outputDirectory);
            if (!applicationFile) {
                throw new IllegalStateException("No app file found in directory " + project.hockeyapp.outputDirectory.absolutePath)
            }
        }

        logger.lifecycle("App file: " + applicationFile.absolutePath)

        // Retrieve mapping file if not using Android Gradle Plugin
        // Requires it to be set in the project config
        if (mightHaveMapping && !mappingFile && project.hockeyapp.symbolsDirectory?.exists()) {
            symbolsDirectory = project.hockeyapp.symbolsDirectory
            mappingFile = getFile(project.hockeyapp.mappingFileNameRegex, symbolsDirectory);

            if (!mappingFile) {
                logger.warn("No Mapping file found.")
            }
        }

        if (mappingFile) {
            logger.lifecycle("Mapping file: " + mappingFile.absolutePath)
        }

        String appId = null
        if (project.hockeyapp.variantToApplicationId) {
            appId = project.hockeyapp.variantToApplicationId[variantName]
            if (!appId)
                throw new IllegalArgumentException("Could not resolve app ID for variant: ${variantName} in the variantToApplicationId map.")
        }

        uploadApp(applicationFile, mappingFile, appId)
    }

    def void uploadApp(File appFile, @Nullable File mappingFile, String appId) {

        ProgressLogger progressLogger = services.get(ProgressLoggerFactory).newOperation(this.getClass())
        progressLogger.start("Upload file to Hockey App", "Upload file")
        if (project.hockeyapp.teamCityLog) {
            println TeamCityStatusMessageHelper.buildProgressString(TeamCityProgressType.START, "Upload file to Hockey App")
        }

        RequestConfig.Builder requestBuilder = RequestConfig.custom()
        requestBuilder = requestBuilder.setConnectTimeout(project.hockeyapp.timeout)
        requestBuilder = requestBuilder.setConnectionRequestTimeout(project.hockeyapp.timeout)

        String proxyHost = System.getProperty("http.proxyHost", "")
        int proxyPort = System.getProperty("http.proxyPort", "0") as int
        if (proxyHost.length() > 0 && proxyPort > 0) {
            logger.lifecycle("Using proxy: " + proxyHost + ":" + proxyPort)
            HttpHost proxy = new HttpHost(proxyHost, proxyPort);
            requestBuilder = requestBuilder.setProxy(proxy)
        }

        HttpClientBuilder builder = HttpClientBuilder.create();
        builder.setDefaultRequestConfig(requestBuilder.build());
        HttpClient httpClient = builder.build();

        String uploadUrl = "https://rink.hockeyapp.net/api/2/apps"
        if (appId) {
            uploadUrl = "https://rink.hockeyapp.net/api/2/apps/${appId}/app_versions/upload"
        }

        HttpPost httpPost = new HttpPost(uploadUrl)
        logger.info("Will upload to: ${uploadUrl}")

        MultipartEntityBuilder entityBuilder = MultipartEntityBuilder.create()

        entityBuilder.addPart("ipa", new FileBody(appFile))
        if (mappingFile) {
            entityBuilder.addPart("dsym", new FileBody(mappingFile))
        }
        decorateWithOptionalProperties(entityBuilder)

        httpPost.addHeader("X-HockeyAppToken", getApiToken())


        int lastProgress = 0
        Logger loggerForCallback = logger
        Boolean teamCityLog = project.hockeyapp.teamCityLog
        ProgressHttpEntityWrapper.ProgressCallback progressCallback = new ProgressHttpEntityWrapper.ProgressCallback() {

            @Override
            public void progress(float progress) {
                int progressInt = (int)progress
                if (progressInt > lastProgress) {
                    lastProgress = progressInt
                    if (progressInt % 5 == 0) {
                        progressLogger.progress(progressInt + "% uploaded")
                        loggerForCallback.info(progressInt + "% uploaded")
                        if (teamCityLog) {
                            println TeamCityStatusMessageHelper.buildProgressString(TeamCityProgressType.MESSAGE, progressInt + "% uploaded")
                        }
                    }
                }
            }

        }

        httpPost.setEntity(new ProgressHttpEntityWrapper(entityBuilder.build(), progressCallback));

        logger.info("Request: " + httpPost.getRequestLine().toString())

        HttpResponse response = httpClient.execute(httpPost)

        if (response.getStatusLine().getStatusCode() != 201) {
            if (response.getEntity() && response.getEntity().getContentLength() > 0) {
                InputStreamReader reader = new InputStreamReader(response.getEntity().content)
                def uploadResponse = new JsonSlurper().parse(reader)
                reader.close()
                logger.debug("Upload response: " + uploadResponse)
                if (uploadResponse && uploadResponse.status && uploadResponse.status.equals("error") && uploadResponse.message) {
                    logger.error("Error response from HockeyApp: " + uploadResponse.message)
                    throw new IllegalStateException("File upload failed: " + uploadResponse.message + " - Status: " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase());
                }
            }
            throw new IllegalStateException("File upload failed: " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase());
        }
        else {
            logger.lifecycle("Application uploaded successfully.")
            if (response.getEntity() && response.getEntity().getContentLength() > 0) {
                InputStreamReader reader = new InputStreamReader(response.getEntity().content)
                def uploadResponse = new JsonSlurper().parse(reader)
                reader.close()
                if (uploadResponse) {
                    logger.info(" application: " + uploadResponse.title + " v" + uploadResponse.shortversion + "(" + uploadResponse.version + ")");
                    logger.debug(" upload response: " + uploadResponse)
                }
            }
            if (project.hockeyapp.teamCityLog) {
                println TeamCityStatusMessageHelper.buildProgressString(TeamCityProgressType.FINISH, "Application uploaded successfully.")
            }
            progressLogger.completed()
        }
    }

    private void decorateWithOptionalProperties(MultipartEntityBuilder entityBuilder) {
        if (project.hockeyapp.notify) {
            entityBuilder.addPart("notify", new StringBody(project.hockeyapp.notify))
        }
        String notesType = project.hockeyapp.notesType
        if(project.hockeyapp.variantToNotesType){
        	if(project.hockeyapp.variantToNotesType[variantName]){
        		notesType = project.hockeyapp.variantToNotesType[variantName]
        	}
        }
        if (notesType) {
            entityBuilder.addPart("notes_type", new StringBody(notesType))
        }
        String notes = project.hockeyapp.notes
        if(project.hockeyapp.variantToNotes){
        	if(project.hockeyapp.variantToNotes[variantName]){
        		notes = project.hockeyapp.variantToNotes[variantName]
        	}
        }
        if (notes) {
            entityBuilder.addPart("notes", new StringBody(notes))
        }
        String status = project.hockeyapp.status
        if (project.hockeyapp.variantToStatus) {
            if (project.hockeyapp.variantToStatus[variantName]){
              status = project.hockeyapp.variantToStatus[variantName]
            }
        }
        if (status) {
            entityBuilder.addPart("status", new StringBody(status))
        }
        String releaseType = project.hockeyapp.releaseType
        if (project.hockeyapp.variantToReleaseType) {
            if (project.hockeyapp.variantToReleaseType[variantName]) {
                releaseType = project.hockeyapp.variantToReleaseType[variantName]
            }
        }
        if (releaseType) {
            entityBuilder.addPart("release_type", new StringBody(project.hockeyapp.releaseType))
        }
        if (project.hockeyapp.commitSha) {
            entityBuilder.addPart("commit_sha", new StringBody(project.hockeyapp.commitSha))
        }
        if (project.hockeyapp.buildServerUrl) {
            entityBuilder.addPart("build_server_url", new StringBody(project.hockeyapp.buildServerUrl))
        }
        if (project.hockeyapp.repositoryUrl) {
            entityBuilder.addPart("repository_url", new StringBody(project.hockeyapp.repositoryUrl))
        }
        if (project.hockeyapp.tags) {
            entityBuilder.addPart("tags", new StringBody(project.hockeyapp.tags))
        }
        if (project.hockeyapp.teams) {
            entityBuilder.addPart("teams", new StringBody(project.hockeyapp.teams))
        }
        String mandatory = project.hockeyapp.mandatory
        if (project.hockeyapp.variantToMandatory){
            if (project.hockeyapp.variantToMandatory[variantName]){
              mandatory = project.hockeyapp.variantToMandatory[variantName]
            }
        }
        if (mandatory){
            entityBuilder.addPart("mandatory", new StringBody(mandatory))
        }
    }

    private String getApiToken() {
        String apiToken = project.hockeyapp.apiToken
        if (project.hockeyapp.variantToApiToken) {
            if (project.hockeyapp.variantToApiToken[variantName]) {
                apiToken = project.hockeyapp.variantToApiToken[variantName]
            }
        }
        return apiToken
    }

    @Nullable
    def static getFile(String regex, File directory) {
        if (!regex) {
            throw new IllegalArgumentException("No appFileNameRegex provided.")
        }

        if (!directory) {
            throw new IllegalArgumentException("No outputDirectory provided")
        }

        if (!directory.exists()) {
            throw new IllegalArgumentException("The outputDirectory (" + directory.absolutePath + ")doesn't exists")
        }

        def pattern = Pattern.compile(regex)

        def fileList = directory.list(
                [accept: { d, f -> f ==~ pattern }] as FilenameFilter
        ).toList()

        if (!fileList) {
            return null
        }
        return new File(directory, fileList[0])
    }


}
