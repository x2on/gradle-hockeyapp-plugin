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
import org.apache.http.HttpEntity
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
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.TaskAction
import org.gradle.logging.ProgressLogger
import org.gradle.logging.ProgressLoggerFactory

import java.util.regex.Pattern

class HockeyAppUploadTask extends DefaultTask {

    File applicationFile
    File mappingFile
    String variantName


    HockeyAppUploadTask() {
        super()
        this.description = "Uploades the app (.ipa, .dsym, .apk) to HockeyApp"
    }


    @TaskAction
    def upload() throws IOException {

        if (project.hockeyapp.apiToken == null) {
            throw new IllegalArgumentException("Cannot upload to HockeyApp because API Token is missing")
        }

        if (applicationFile == null || !applicationFile.exists()) {
            applicationFile = getFile(project.hockeyapp.appFileNameRegex, project.hockeyapp.outputDirectory);
            if (applicationFile == null) {
                throw new IllegalStateException("No app file found in directory " + project.hockeyapp.outputDirectory.absolutePath)
            }
        }
        File symbolsDirectory = mappingFile != null && mappingFile.exists() ? mappingFile : project.hockeyapp.symbolsDirectory
        def mappingFile = getFile(project.hockeyapp.mappingFileNameRegex, symbolsDirectory);

        logger.lifecycle("App file: " + applicationFile.absolutePath)
        if (mappingFile) {
            logger.lifecycle("Mapping file: " + mappingFile.absolutePath)
        }
        else {
            logger.warn("No Mapping file found.")
        }

        String appId = null
        if (project.hockeyapp.variantToApplicationId != null) {
            appId = project.hockeyapp.variantToApplicationId[variantName]
            if (appId == null)
                throw new IllegalArgumentException("Could not resolve app ID for variant: ${variantName} in the variantToApplicationId map.")
        }

        uploadApp(applicationFile, mappingFile, appId)
    }

    def void uploadApp(File appFile, File mappingFile, String appId) {

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
        if (appId != null) {
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

        httpPost.addHeader("X-HockeyAppToken", project.hockeyapp.apiToken)


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
            throw new IllegalStateException("File upload failed: " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase());
        }
        else {
            logger.lifecycle("Application uploaded successfully.")
            InputStreamReader reader = new InputStreamReader(response.getEntity().content)
            def uploadResponse = new JsonSlurper().parse(reader)
            reader.close()

            logger.info(" application: " + uploadResponse.title + " v" + uploadResponse.shortversion + "(" + uploadResponse.version + ")");
            logger.debug(" upload response:\n" + uploadResponse)
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
        if (project.hockeyapp.notesType) {
            entityBuilder.addPart("notes_type", new StringBody(project.hockeyapp.notesType))
        }
        if (project.hockeyapp.notes) {
            entityBuilder.addPart("notes", new StringBody(project.hockeyapp.notes))
        }
        if (project.hockeyapp.status) {
            entityBuilder.addPart("status", new StringBody(project.hockeyapp.status))
        }
        if (project.hockeyapp.releaseType) {
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
    }


    def static getFile(String regex, File directory) {
        def pattern = Pattern.compile(regex)

        if (!directory.exists()) {
            throw new IllegalStateException("OutputDirectory not found")
        }

        def fileList = directory.list(
                [accept: { d, f -> f ==~ pattern }] as FilenameFilter
        ).toList()

        if (fileList == null || fileList.size() == 0) {
            return null
        }
        return new File(directory, fileList[0])
    }


}
