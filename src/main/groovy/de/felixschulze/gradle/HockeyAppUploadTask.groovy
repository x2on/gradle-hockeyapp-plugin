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
import de.felixschulze.gradle.internal.ProgressLoggerWrapper
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
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.client.methods.HttpPut
import org.apache.http.client.methods.HttpUriRequest
import org.apache.http.entity.mime.MultipartEntityBuilder
import org.apache.http.entity.mime.content.FileBody
import org.apache.http.entity.mime.content.StringBody
import org.apache.http.impl.client.HttpClientBuilder
import org.gradle.api.DefaultTask
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.TaskAction

import javax.annotation.Nullable

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


    @SuppressWarnings("GroovyUnusedDeclaration")
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
        } else {
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
                String s = hockeyApp.outputDirectory ? hockeyApp.outputDirectory.absolutePath : " not defined "
                throw new IllegalArgumentException("The outputDirectory ($s) doesn't exists")
            }
            applicationFiles = FileHelper.getFiles(hockeyApp.appFileNameRegex, hockeyApp.outputDirectory)
            if (!applicationFiles) {
                throw new IllegalStateException("No app file found in directory ${hockeyApp.outputDirectory.absolutePath}")
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
            mappingFile = FileHelper.getFile(hockeyApp.mappingFileNameRegex, symbolsDirectory)

            if (!mappingFile) {
                logger.warn("No Mapping file found.")
            }
        }

        if (mappingFile?.exists()) {
            logger.lifecycle("Mapping file: ${mappingFile.absolutePath}")
        }

        String appId = null
        if (hockeyApp.variantToApplicationId) {
            appId = hockeyApp.variantToApplicationId[variantName]
            if (!appId) {
                if (project.getGradle().getTaskGraph().hasTask(uploadAllPath)) {
                    logger.error("Could not resolve app ID for variant: ${variantName} in the " +
                            "variantToApplicationId map.")
                } else {
                    throw new IllegalArgumentException("Could not resolve app ID for variant: ${variantName} in the " +
                            "variantToApplicationId map.")
                }
            }
        }

        applicationFiles.each {
            boolean existingVersionUpdated = false
            if (hockeyApp.updateExisting) {
                existingVersionUpdated = tryUpdateExistingVersion(it, mappingFile, appId)
            }

            if (!existingVersionUpdated) {
                uploadFilesToHockeyApp(it, mappingFile, appId, null)
            }
        }

    }

    /**
     *
     * @param appFile
     * @param mappingFile
     * @param appId
     * @return true when version with given version name and code exists and was successfully updated </br>
     * false otherwise.
     */
    boolean tryUpdateExistingVersion(File appFile, @Nullable File mappingFile, @Nullable String appId) {
        ProgressLoggerWrapper progressLogger = new ProgressLoggerWrapper(project,
                "Update existing version in Hockey App")

        progressLogger.started()
        if (hockeyApp.teamCityLog) {
            println TeamCityStatusMessageHelper.buildProgressString(TeamCityProgressType.START,
                    "Update existing version in Hockey App")
        }

        HttpClient httpClient = getHttpClient()

        String versionsUrl = hockeyApp.hockeyApiUrl
        if (appId) {
            versionsUrl = "${hockeyApp.hockeyApiUrl}/${appId}/app_versions"
        }

        HttpGet httpGet = new HttpGet(versionsUrl)
        logger.info("Going to ask for existing version from: ${versionsUrl}")

        httpGet.addHeader("X-HockeyAppToken", getApiToken())
        logger.info("Request: ${httpGet.getRequestLine().toString()}")
        HttpResponse response = httpClient.execute(httpGet)
        logger.debug("Response status code: ${response.getStatusLine().getStatusCode()}")
        boolean returnValue = false
        if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
            parseResponseAndThrowError(response)
        } else {
            logger.lifecycle("Versions fetched successfully.")
            if (response.getEntity() &&
                    (response.getEntity().getContentLength() > 0 || response.getEntity().isStreaming())) {
                InputStreamReader reader = new InputStreamReader(response.getEntity().content)

                def versionsResponse = null
                try {
                    versionsResponse = new JsonSlurper().parse(reader)
                }
                catch (Exception e) {
                    logger.error("Error while parsing JSON response: ${e.toString()}")
                }
                reader.close()
                if (versionsResponse && versionsResponse.status == 'success') {
                    def versionInfo = null
                    for (iter in versionsResponse.app_versions) {
                        boolean versionCodeMatches = (iter.version?.toInteger() == project.versionCode)
                        boolean versionNameMatches = (iter.shortversion?.toString() == project.versionName)
                        if (versionCodeMatches && versionNameMatches) {
                            versionInfo = iter
                            break
                        }
                    }

                    if (versionInfo) {
                        logger.info("Found version to update: Title: '${versionInfo.title}' " +
                                "Config url: '${versionInfo.config_url}'")
                        logger.debug("Found version info: ${versionInfo.toString()}")
                        logger.lifecycle("Gonna try to update config url ${versionInfo.config_url}")

                        HttpPut httpPut = new HttpPut("$versionsUrl/${versionInfo.id}")
                        uploadFilesToHockeyApp(appFile, mappingFile, appId, httpPut)
                        returnValue = true
                    }
                }
            }
            if (hockeyApp.teamCityLog) {
                println TeamCityStatusMessageHelper.buildProgressString(TeamCityProgressType.FINISH,
                        "Application uploaded successfully.")
            }
            progressLogger.completed()
        }
        returnValue
    }

    void uploadFilesToHockeyApp(File appFile,
                                @Nullable File mappingFile,
                                @Nullable String appId,
                                @Nullable HttpUriRequest httpRequest) {

        ProgressLoggerWrapper progressLogger = new ProgressLoggerWrapper(project, "Upload file to Hockey App")

        progressLogger.started()
        if (hockeyApp.teamCityLog) {
            println TeamCityStatusMessageHelper.buildProgressString(TeamCityProgressType.START,
                    "Upload file to Hockey App")
        }

        HttpClient httpClient = getHttpClient()

        if (!httpRequest) {
            String uploadUrl = hockeyApp.hockeyApiUrl
            if (appId) {
                uploadUrl = "${hockeyApp.hockeyApiUrl}/${appId}/app_versions/upload"
            }

            httpRequest = new HttpPost(uploadUrl)
            logger.info("Will upload to: ${uploadUrl}")
        }

        MultipartEntityBuilder entityBuilder = MultipartEntityBuilder.create()

        entityBuilder.addPart("ipa", new FileBody(appFile))
        if (mappingFile?.exists()) {
            entityBuilder.addPart("dsym", new FileBody(mappingFile))
        }
        decorateWithOptionalProperties(entityBuilder)

        httpRequest.addHeader("X-HockeyAppToken", getApiToken())


        int lastProgress = 0
        Logger loggerForCallback = logger
        Boolean teamCityLog = hockeyApp.teamCityLog
        ProgressHttpEntityWrapper.ProgressCallback progressCallback = new ProgressHttpEntityWrapper.ProgressCallback() {

            @Override
            void progress(float progress) {
                int progressInt = (int) progress
                if (progressInt > lastProgress) {
                    lastProgress = progressInt
                    if (progressInt % 5 == 0) {
                        progressLogger.progress("${progressInt}% uploaded")
                        loggerForCallback.info("${progressInt}% uploaded")
                        if (teamCityLog) {
                            println TeamCityStatusMessageHelper.buildProgressString(
                                    TeamCityProgressType.MESSAGE, "${progressInt}% uploaded")
                        }
                    }
                }
            }

        }

        httpRequest.setEntity(new ProgressHttpEntityWrapper(entityBuilder.build(), progressCallback))

        logger.info("Request: ${httpRequest.getRequestLine()}")

        HttpResponse response = httpClient.execute(httpRequest)

        logger.debug("Response status code: ${response.getStatusLine().getStatusCode()}")

        if (response.getStatusLine().getStatusCode() != HttpStatus.SC_CREATED) {
            parseResponseAndThrowError(response)
        } else {
            logger.lifecycle("Application uploaded successfully.")
            if (response.getEntity() && response.getEntity().getContentLength() > 0) {
                def responseContent = extractResponseContent(response)
                if (responseContent) {
                    logger.info("Upload information: Title: '${responseContent.title}' Config url: " +
                            "'${responseContent.config_url}'")
                    logger.debug("Upload response: ${responseContent.toString()}")
                    logger.lifecycle("Application public url ${responseContent.public_url}")
                }
            }
            if (hockeyApp.teamCityLog) {
                println TeamCityStatusMessageHelper.buildProgressString(TeamCityProgressType.FINISH,
                        "Application uploaded successfully.")
            }
            progressLogger.completed()
        }
    }

    private HttpClient getHttpClient() {
        RequestConfig.Builder requestBuilder = RequestConfig.custom()
        requestBuilder = requestBuilder.setConnectTimeout(hockeyApp.timeout)
        requestBuilder = requestBuilder.setConnectionRequestTimeout(hockeyApp.timeout)

        String proxyHost = System.getProperty("http.proxyHost", "")
        int proxyPort = System.getProperty("http.proxyPort", "0") as int
        if (proxyHost.length() > 0 && proxyPort > 0) {
            logger.lifecycle("Using proxy: ${proxyHost}:${proxyPort}")
            HttpHost proxy = new HttpHost(proxyHost, proxyPort)
            requestBuilder = requestBuilder.setProxy(proxy)
        }

        HttpClientBuilder builder = HttpClientBuilder.create()
        builder.setDefaultRequestConfig(requestBuilder.build())
        builder.build()
    }

    private Object extractResponseContent(HttpResponse httpResponse) {
        Object responseContent = null
        if (!httpResponse) {
            return responseContent
        }

        if (!httpResponse.entity) {
            return responseContent
        }

        if (!(httpResponse.entity.contentLength > 0 || httpResponse.entity.isStreaming)) {
            return responseContent
        }

        InputStreamReader reader = new InputStreamReader(httpResponse.entity.content)
        try {
            responseContent = new JsonSlurper().parse(reader)
        } catch (Exception e) {
            logger.error("Error while parsing JSON response: ${e.toString()}")
        }
        reader.close()
        return responseContent
    }

    private void parseResponseAndThrowError(HttpResponse response) {
        if (response.getEntity()?.getContentLength() > 0) {
            logger.debug("Response Content-Type: ${response.getFirstHeader("Content-type").getValue()}")
            def responseContent = extractResponseContent(response)

            if (responseContent) {
                logger.debug("Upload response: ${responseContent.toString()}")

                if (responseContent.status && responseContent.status == 'error' && responseContent.message) {
                    logger.error("Error response from HockeyApp: ${responseContent.message.toString()}")
                    throw new IllegalStateException("File upload failed: ${responseContent.message.toString()} - Status:" +
                            " ${response.getStatusLine().getStatusCode()}  ${response.getStatusLine().getReasonPhrase()}")
                }
                if (responseContent.errors?.credentials) {
                    if (responseContent.errors.credentials instanceof ArrayList) {
                        ArrayList credentialsError = responseContent.errors.credentials
                        if (!credentialsError.isEmpty()) {
                            logger.error(credentialsError.get(0).toString())
                            throw new IllegalStateException(credentialsError.get(0).toString())
                        }
                    }
                }
            }
        }
        throw new IllegalStateException("File upload failed: ${response.getStatusLine().getStatusCode()} " +
                response.getStatusLine().getReasonPhrase())
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
        String strategy = optionalProperty(hockeyApp.strategy as String, hockeyApp.variantToStrategy)
        if (strategy) {
            entityBuilder.addPart("strategy", new StringBody(strategy))
        }
        if (hockeyApp.ownerId) {
            entityBuilder.addPart("owner_id", new StringBody(hockeyApp.ownerId))
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
        if (mandatory) {
            entityBuilder.addPart("mandatory", new StringBody(mandatory))
        }
    }

    private String optionalProperty(String property, Map<String, String> variantToProperty) {
        if (variantToProperty) {
            if (variantToProperty[variantName]) {
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
