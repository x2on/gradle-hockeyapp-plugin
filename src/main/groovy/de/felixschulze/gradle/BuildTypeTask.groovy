package de.felixschulze.gradle

import com.android.builder.model.BuildType
import org.gradle.api.DefaultTask
import org.gradle.api.Project

/**
 * Tasks with for specific build types
 */
class BuildTypeTask extends DefaultTask {

    static BuildTypeTask createBuildTypeTask(
            Project project, BuildType buildType, HockeyAppUploadTask hockeyAppUploadTask) {

        BuildTypeTask buildTypeTask = project.tasks.create("upload${buildType.name.capitalize()}ToHockeyApp", BuildTypeTask);

        buildTypeTask.group = HockeyAppPlugin.GROUP_NAME
        buildTypeTask.description = "Uploads all variants of build Type '${buildType.name}' to HockeyApp"
        buildTypeTask.outputs.upToDateWhen { false }

        buildTypeTask.dependsOn(hockeyAppUploadTask)

        return buildTypeTask;
    }
}
