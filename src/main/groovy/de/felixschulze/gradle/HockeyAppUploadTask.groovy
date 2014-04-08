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

import groovy.json.JsonSlurper
import org.apache.http.HttpHost
import org.apache.http.HttpResponse
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpPost
import org.apache.http.conn.params.ConnRoutePNames
import org.apache.http.entity.mime.MultipartEntity
import org.apache.http.entity.mime.content.FileBody
import org.apache.http.entity.mime.content.StringBody
import org.apache.http.impl.client.DefaultHttpClient
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

import java.util.regex.Pattern

class HockeyAppUploadTask extends DefaultTask {

    File applicationFile
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
        def mappingFile = getFile(project.hockeyapp.mappingFileNameRegex, project.hockeyapp.symbolsDirectory);


        println "App file: " + applicationFile.absolutePath
        if (mappingFile) {
            println "Mapping file: " + mappingFile.absolutePath
        }
        else {
            println "WARNING: No Mapping file found."
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
        HttpClient httpClient = new DefaultHttpClient()

        String proxyHost = System.getProperty("http.proxyHost", "")
        int proxyPort = System.getProperty("http.proxyPort", "0") as int
        if (proxyHost.length() > 0 && proxyPort > 0) {
            println "Using proxy: " + proxyHost + ":" + proxyPort
            HttpHost proxy = new HttpHost(proxyHost, proxyPort);
            httpClient.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY, proxy);
        }

        String uploadUrl = "https://rink.hockeyapp.net/api/2/apps"
        if (appId != null) {
            uploadUrl = "https://rink.hockeyapp.net/api/2/apps/${appId}/app_versions/upload"
        }

        HttpPost httpPost = new HttpPost(uploadUrl)
        logger.info("Will upload to: ${uploadUrl}")

        MultipartEntity entity = new MultipartEntity();

        entity.addPart("ipa", new FileBody(appFile))
        if (mappingFile) {
            entity.addPart("dsym", new FileBody(mappingFile))
        }
        decorateWithOptionalProperties(entity)

        httpPost.addHeader("X-HockeyAppToken", project.hockeyapp.apiToken)

        httpPost.setEntity(entity);

        logger.info("Request: " + httpPost.getRequestLine().toString())

        HttpResponse response = httpClient.execute(httpPost)

        if (response.getStatusLine().getStatusCode() != 201) {
            throw new IllegalStateException("File upload failed: " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase());
        }
        else {
            println "Application uploaded successfully."
            InputStreamReader reader = new InputStreamReader(response.getEntity().content)
            def uploadResponse = new JsonSlurper().parse(reader)
            reader.close()

            logger.info(" application: " + uploadResponse.title + " v" + uploadResponse.shortversion + "(" + uploadResponse.version + ")");
            logger.debug(" upload response:\n" + uploadResponse)
        }
    }

    private void decorateWithOptionalProperties(MultipartEntity entity) {
        if (project.hockeyapp.notify) {
            entity.addPart("notify", new StringBody(project.hockeyapp.notify))
        }
        if (project.hockeyapp.notesType) {
            entity.addPart("notes_type", new StringBody(project.hockeyapp.notesType))
        }
        if (project.hockeyapp.notes) {
            entity.addPart("notes", new StringBody(project.hockeyapp.notes))
        }
        if (project.hockeyapp.status) {
            entity.addPart("status", new StringBody(project.hockeyapp.status))
        }
        if (project.hockeyapp.releaseType) {
            entity.addPart("release_type", new StringBody(project.hockeyapp.releaseType))
        }
        if (project.hockeyapp.commitSha) {
            entity.addPart("commit_sha", new StringBody(project.hockeyapp.commitSha))
        }
        if (project.hockeyapp.buildServerUrl) {
            entity.addPart("build_server_url", new StringBody(project.hockeyapp.buildServerUrl))
        }
        if (project.hockeyapp.repositoryUrl) {
            entity.addPart("repository_url", new StringBody(project.hockeyapp.repositoryUrl))
        }
        if (project.hockeyapp.tags) {
            entity.addPart("tags", new StringBody(project.hockeyapp.tags))
        }
    }


    def getFile(String regex, File directory) {
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
