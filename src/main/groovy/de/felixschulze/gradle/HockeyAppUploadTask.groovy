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
import de.felixschulze.gradle.util.FileHelper
import de.felixschulze.gradle.util.ProgressHttpEntityWrapper
import de.felixschulze.teamcity.TeamCityProgressType
import de.felixschulze.teamcity.TeamCityStatusMessageHelper
import groovy.json.JsonSlurper
import org.apache.commons.io.FilenameUtils
import org.apache.http.Consts
import org.apache.http.HttpHost
import org.apache.http.HttpResponse
import org.apache.http.HttpStatus
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
import org.gradle.internal.logging.progress.ProgressLogger
import org.gradle.internal.logging.progress.ProgressLoggerFactory

/**
 * Upload task for plugin
 */
class HockeyAppUploadTask extends DefaultTask {

    List<File> applicationFiles = []
    File symbolsDirectory
    File mappingFile
    String variantName
    ApplicationVariant applicationVariant
    boolean mappingFileCouldBePresent = true
    HockeyAppPluginExtension hockeyApp
    String uploadAllPath


    HockeyAppUploadTask() {
        super()
        this.description = 'Uploads the app (Android: (.apk, mapping.txt), iOS:(.ipa, .dsym)) to HockeyApp'
    }


    @TaskAction
    def upload() throws IOException {

        hockeyApp = project.hockeyapp

        // Get all output apk files if android
        if (applicationVariant) {
            logger.debug('Using android application variants')

            applicationVariant.outputs.each {
                if (FilenameUtils.isExtension(it.outputFile.getName(), "apk")) {
                    if (it.outputFile.exists()) {
                        applicationFiles << it.outputFile
                    } else {
                        logger.debug("App file doesn't exist: $it.outputFile.absolutePath")
                    }
                }
            }

            if (applicationVariant.getMappingFile()?.exists()) {
                logger.debug('Mapping file found')
                mappingFile = applicationVariant.getMappingFile()
            } else {
                logger.debug('Mapping file not found')
                mappingFileCouldBePresent = false
            }
        }
        else {
            logger.debug('Not using android application variants')
        }

        if (!getApiToken()) {
            throw new IllegalArgumentException("Cannot upload to HockeyApp because API Token is missing")
        }

        if (!applicationFiles) {
            if (!applicationVariant && !hockeyApp.appFileNameRegex) {
                throw new IllegalArgumentException("No appFileNameRegex provided.")
            }
            if (!hockeyApp.outputDirectory || !hockeyApp.outputDirectory.exists()) {
                throw new IllegalArgumentException("The outputDirectory (" + hockeyApp.outputDirectory ? hockeyApp.outputDirectory.absolutePath : " not defined " + ") doesn't exists")
            }
            applicationFiles = FileHelper.getFiles(hockeyApp.appFileNameRegex, hockeyApp.outputDirectory);
            if (!applicationFiles) {
                throw new IllegalStateException("No app file found in directory " + hockeyApp.outputDirectory.absolutePath)
            }
        }

        def appFilePaths = applicationFiles.collect { it.name }

        if (!hockeyApp.allowMultipleAppFiles && applicationFiles.size() > 1) {
            throw new IllegalStateException("Upload multiple files is not allowed: $appFilePaths")
        }

        logger.lifecycle("App files: $appFilePaths")

        // Retrieve mapping file if not using Android Gradle Plugin
        // Requires it to be set in the project config
        if (mappingFileCouldBePresent && !mappingFile && hockeyApp.symbolsDirectory?.exists()) {
            symbolsDirectory = hockeyApp.symbolsDirectory
            mappingFile = FileHelper.getFile(hockeyApp.mappingFileNameRegex, symbolsDirectory);

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
            if (!appId) {
                if(project.getGradle().getTaskGraph().hasTask(uploadAllPath)) {
                    logger.error("Could not resolve app ID for variant: ${variantName} in the variantToApplicationId map.")
                } else {
                    throw new IllegalArgumentException("Could not resolve app ID for variant: ${variantName} in the variantToApplicationId map.")
                }
            }
        }

        applicationFiles.each {
            uploadFilesToHockeyApp(it, mappingFile, appId)
        }

    }

    def void uploadFilesToHockeyApp(File appFile, @Nullable File mappingFile, @Nullable String appId) {

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

        String uploadUrl = hockeyApp.hockeyApiUrl
        if (appId) {
            uploadUrl = "${hockeyApp.hockeyApiUrl}/${appId}/app_versions/upload"
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

        logger.debug("Response status code: " + response.getStatusLine().getStatusCode())

        if (response.getStatusLine().getStatusCode() != HttpStatus.SC_CREATED) {
            parseResponseAndThrowError(response)
        }
        else {
            logger.lifecycle("Application uploaded successfully.")
            if (response.getEntity() && response.getEntity().getContentLength() > 0) {
                InputStreamReader reader = new InputStreamReader(response.getEntity().content)
                def uploadResponse = null
                try {
                    uploadResponse = new JsonSlurper().parse(reader)
                }
                catch (Exception e) {
                    logger.error("Error while parsing JSON response: " + e.toString())
                }
                reader.close()
                if (uploadResponse) {
                    logger.info("Upload information: Title: '" + uploadResponse.title?.toString() + "' Config url: '" + uploadResponse.config_url?.toString()) + "'";
                    logger.debug("Upload response: " + uploadResponse.toString())
                    logger.lifecycle("Application public url " + uploadResponse.public_url?.toString())
                }
            }
            if (hockeyApp.teamCityLog) {
                println TeamCityStatusMessageHelper.buildProgressString(TeamCityProgressType.FINISH, "Application uploaded successfully.")
            }
            progressLogger.completed()
        }
    }

    private void parseResponseAndThrowError(HttpResponse response) {
        if (response.getEntity()?.getContentLength() > 0) {
            logger.debug("Response Content-Type: " + response.getFirstHeader("Content-type").getValue())
            InputStreamReader reader = new InputStreamReader(response.getEntity().content)
            Object uploadResponse = null
            try {
                uploadResponse = new JsonSlurper().parse(reader)
            } catch (Exception e) {
                logger.debug("Error while parsing JSON response: " + e.toString())
            }
            reader.close()

            if (uploadResponse) {
                logger.debug("Upload response: " + uploadResponse.toString())

                if (uploadResponse.status && uploadResponse.status.equals("error") && uploadResponse.message) {
                    logger.error("Error response from HockeyApp: " + uploadResponse.message.toString())
                    throw new IllegalStateException("File upload failed: " + uploadResponse.message.toString() + " - Status: " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase())
                }
                if (uploadResponse.errors?.credentials) {
                    if (uploadResponse.errors.credentials instanceof ArrayList) {
                        ArrayList credentialsError = uploadResponse.errors.credentials;
                        if (!credentialsError.isEmpty()) {
                            logger.error(credentialsError.get(0).toString())
                            throw new IllegalStateException(credentialsError.get(0).toString())
                        }
                    }
                }
            }
        }
        throw new IllegalStateException("File upload failed: " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase());
    }

    private void decorateWithOptionalProperties(MultipartEntityBuilder entityBuilder) {
        String notify = optionalProperty(hockeyApp.notify as String, hockeyApp.variantToNotify)
        if (notify) {
            entityBuilder.addPart("notify", new StringBody(notify as String))
        }
        String notesType = optionalProperty(hockeyApp.notesType as String, hockeyApp.variantToNotesType)
        if (notesType) {
            entityBuilder.addPart("notes_type", new StringBody(notesType))
        }
        String notes = optionalProperty(hockeyApp.notes, hockeyApp.variantToNotes)
        if (notes) {
            entityBuilder.addPart("notes", new StringBody(notes, Consts.UTF_8))
        }
        String status = optionalProperty(hockeyApp.status as String, hockeyApp.variantToStatus)
        if (status) {
            entityBuilder.addPart("status", new StringBody(status))
        }
        String releaseType = optionalProperty(hockeyApp.releaseType as String, hockeyApp.variantToReleaseType)
        if (releaseType) {
            entityBuilder.addPart("release_type", new StringBody(releaseType as String))
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
        String tags = optionalProperty(hockeyApp.tags as String, hockeyApp.variantToTags)
        if (tags) {
            entityBuilder.addPart("tags", new StringBody(tags))
        }
        if (hockeyApp.teams) {
            entityBuilder.addPart("teams", new StringBody(hockeyApp.teams))
        }
        if (hockeyApp.users) {
            entityBuilder.addPart("users", new StringBody(hockeyApp.users))
        }
        String mandatory = optionalProperty(hockeyApp.mandatory as String, hockeyApp.variantToMandatory)
        if (mandatory){
            entityBuilder.addPart("mandatory", new StringBody(mandatory))
        }
    }

    private String optionalProperty(String property, Map<String, String> variantToProperty) {
        if(variantToProperty) {
            if(variantToProperty[variantName]) {
                property = variantToProperty[variantName]
            }
        }
        return property
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


}
