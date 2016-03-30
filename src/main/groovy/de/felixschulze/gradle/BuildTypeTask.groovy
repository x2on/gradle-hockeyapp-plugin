package de.felixschulze.gradle

import com.android.build.gradle.internal.dsl.BuildType
import org.gradle.api.DefaultTask

/**
 * Tasks with for specific build types
 */
class BuildTypeTask extends DefaultTask{
    BuildType buildType
}
