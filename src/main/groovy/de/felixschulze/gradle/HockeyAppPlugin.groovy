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

import com.android.build.gradle.api.ApplicationVariant
import org.apache.commons.io.FilenameUtils
import org.gradle.api.Plugin
import org.gradle.api.Project
import com.android.build.gradle.AppExtension
import com.android.build.gradle.AppPlugin

class HockeyAppPlugin implements Plugin<Project> {

    void apply(Project project) {
        applyExtensions(project)
        applyTasks(project)
    }

    void applyExtensions(final Project project) {
        project.extensions.create('hockeyapp', HockeyAppPluginExtension, project)
    }

    void applyTasks(final Project project) {

        if (!project.plugins.hasPlugin(AppPlugin)) {
            project.task('uploadToHockeyApp', type: HockeyAppUploadTask, group: 'HockeyApp')
        } else {
            AppExtension android = project.android
            android.applicationVariants.all { ApplicationVariant variant ->

                HockeyAppUploadTask task = project.tasks.create("upload${variant.name.capitalize()}ToHockeyApp", HockeyAppUploadTask)
                task.group = 'HockeyApp'
                task.description = "Upload '${variant.name}' to HockeyApp"

                // Get the first output apk file
                variant.outputs.each {
                    if (FilenameUtils.isExtension(it.outputFile.getName(),"apk")) {
                        task.applicationFile = it.outputFile
                        return true
                    }
                }

                if (variant.getObfuscation()) {
                    task.mappingFile = variant.getMappingFile()
                } else {
                    task.mightHaveMapping = false
                }

                task.variantName = variant.name
                task.outputs.upToDateWhen { false }

                task.dependsOn variant.assemble
            }
        }
    }

}
