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

import com.android.build.gradle.api.ApplicationVariant
import de.felixschulze.gradle.util.ProgressHttpEntityWrapper
import de.felixschulze.teamcity.TeamCityProgressType
import de.felixschulze.teamcity.TeamCityStatusMessageHelper
import groovy.json.JsonSlurper
import org.apache.commons.io.FilenameUtils
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
    ApplicationVariant applicationVariant
    boolean mightHaveMapping = true   // Specify otherwise in Android config
    HockeyAppPluginExtension hockeyApp


    HockeyAppUploadTask() {
        super()
        this.description = "Uploads the app (Android: (.apk, mapping.txt), iOS:(.ipa, .dsym)) to HockeyApp"
    }


    @TaskAction
    def upload() throws IOException {

        hockeyApp = project.hockeyapp

        // Get the first output apk file if android
        if (applicationVariant) {
            applicationVariant.outputs.each {
                if (FilenameUtils.isExtension(it.outputFile.getName(), "apk")) {
                    applicationFile = it.outputFile
                    return true
                }
            }

            if (applicationVariant.getObfuscation()) {
                mappingFile = applicationVariant.getMappingFile()
            } else {
                mightHaveMapping = false
            }
        }

        if (!getApiToken()) {
            throw new IllegalArgumentException("Cannot upload to HockeyApp because API Token is missing")
        }

        if (!applicationFile?.exists()) {
            if (!hockeyApp.appFileNameRegex) {
                throw new IllegalArgumentException("No applicationFile found or no appFileNameRegex provided.")
            }
            if (!hockeyApp.outputDirectory || !hockeyApp.outputDirectory.exists()) {
                throw new IllegalArgumentException("The outputDirectory (" + hockeyApp.outputDirectory ? hockeyApp.outputDirectory.absolutePath : " not defined " + ") doesn't exists")
            }
            applicationFile = getFile(hockeyApp.appFileNameRegex, hockeyApp.outputDirectory);
            if (!applicationFile) {
                throw new IllegalStateException("No app file found in directory " + hockeyApp.outputDirectory.absolutePath)
            }
        }

        logger.lifecycle("App file: " + applicationFile.absolutePath)

        // Retrieve mapping file if not using Android Gradle Plugin
        // Requires it to be set in the project config
        if (mightHaveMapping && !mappingFile && hockeyApp.symbolsDirectory?.exists()) {
            symbolsDirectory = hockeyApp.symbolsDirectory
            mappingFile = getFile(hockeyApp.mappingFileNameRegex, symbolsDirectory);

            if (!mappingFile) {
                logger.warn("No Mapping file found.")
            }
        }

        if (mappingFile?.exists()) {
            logger.lifecycle("Mapping file: " + mappingFile.absolutePath)
        }

        String appId = null
        if (hockeyApp.variantToApplicationId) {
            appId = hockeyApp.variantToApplicationId[variantName]
            if (!appId)
                throw new IllegalArgumentException("Could not resolve app ID for variant: ${variantName} in the variantToApplicationId map.")
        }

        uploadApp(applicationFile, mappingFile, appId)
    }

    def void uploadApp(File appFile, @Nullable File mappingFile, String appId) {

        ProgressLogger progressLogger = services.get(ProgressLoggerFactory).newOperation(this.getClass())
        progressLogger.start("Upload file to Hockey App", "Upload file")
        if (hockeyApp.teamCityLog) {
            println TeamCityStatusMessageHelper.buildProgressString(TeamCityProgressType.START, "Upload file to Hockey App")
        }

        RequestConfig.Builder requestBuilder = RequestConfig.custom()
        requestBuilder = requestBuilder.setConnectTimeout(hockeyApp.timeout)
        requestBuilder = requestBuilder.setConnectionRequestTimeout(hockeyApp.timeout)

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
        if (mappingFile?.exists()) {
            entityBuilder.addPart("dsym", new FileBody(mappingFile))
        }
        decorateWithOptionalProperties(entityBuilder)

        httpPost.addHeader("X-HockeyAppToken", getApiToken())


        int lastProgress = 0
        Logger loggerForCallback = logger
        Boolean teamCityLog = hockeyApp.teamCityLog
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
            if (response.getEntity()?.getContentLength() > 0) {
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
            if (hockeyApp.teamCityLog) {
                println TeamCityStatusMessageHelper.buildProgressString(TeamCityProgressType.FINISH, "Application uploaded successfully.")
            }
            progressLogger.completed()
        }
    }

    private void decorateWithOptionalProperties(MultipartEntityBuilder entityBuilder) {
        if (hockeyApp.notify) {
            entityBuilder.addPart("notify", new StringBody(hockeyApp.notify as String))
        }
        String notesType = hockeyApp.notesType
        if(hockeyApp.variantToNotesType){
        	if(hockeyApp.variantToNotesType[variantName]){
        		notesType = hockeyApp.variantToNotesType[variantName]
        	}
        }
        if (notesType) {
            entityBuilder.addPart("notes_type", new StringBody(notesType))
        }
        String notes = hockeyApp.notes
        if(hockeyApp.variantToNotes){
        	if(hockeyApp.variantToNotes[variantName]){
        		notes = hockeyApp.variantToNotes[variantName]
        	}
        }
        if (notes) {
            entityBuilder.addPart("notes", new StringBody(notes))
        }
        String status = hockeyApp.status
        if (hockeyApp.variantToStatus) {
            if (hockeyApp.variantToStatus[variantName]){
              status = hockeyApp.variantToStatus[variantName]
            }
        }
        if (status) {
            entityBuilder.addPart("status", new StringBody(status))
        }
        String releaseType = hockeyApp.releaseType
        if (hockeyApp.variantToReleaseType) {
            if (hockeyApp.variantToReleaseType[variantName]) {
                releaseType = hockeyApp.variantToReleaseType[variantName]
            }
        }
        if (releaseType) {
            entityBuilder.addPart("release_type", new StringBody(hockeyApp.releaseType as String))
        }
        if (hockeyApp.commitSha) {
            entityBuilder.addPart("commit_sha", new StringBody(hockeyApp.commitSha))
        }
        if (hockeyApp.buildServerUrl) {
            entityBuilder.addPart("build_server_url", new StringBody(hockeyApp.buildServerUrl))
        }
        if (hockeyApp.repositoryUrl) {
            entityBuilder.addPart("repository_url", new StringBody(hockeyApp.repositoryUrl))
        }
        if (hockeyApp.tags) {
            entityBuilder.addPart("tags", new StringBody(hockeyApp.tags))
        }
        if (hockeyApp.teams) {
            entityBuilder.addPart("teams", new StringBody(hockeyApp.teams))
        }
        String mandatory = hockeyApp.mandatory
        if (hockeyApp.variantToMandatory){
            if (hockeyApp.variantToMandatory[variantName]){
              mandatory = hockeyApp.variantToMandatory[variantName]
            }
        }
        if (mandatory){
            entityBuilder.addPart("mandatory", new StringBody(mandatory))
        }
    }

    private String getApiToken() {
        String apiToken = hockeyApp.apiToken
        if (hockeyApp.variantToApiToken) {
            if (hockeyApp.variantToApiToken[variantName]) {
                apiToken = hockeyApp.variantToApiToken[variantName]
            }
        }
        return apiToken
    }

    @Nullable
    def static getFile(String regex, File directory) {
        if (!regex || !directory || !directory.exists()) {
            return null
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
