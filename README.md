# gradle-hockeyapp-plugin [![Build Status](https://travis-ci.org/x2on/gradle-hockeyapp-plugin.png)](https://travis-ci.org/x2on/gradle-hockeyapp-plugin)
A Gradle plugin for uploading iOS and Android Apps to HockeyApp. 

## Basic usage

Add to your build.gradle

```gradle
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath 'de.felixschulze.gradle:gradle-hockeyapp-plugin:1.4+'
    }
}

apply plugin: 'hockeyApp'
```

## Advanced usage

Add to your build.gradle

```gradle
hockeyapp {
    apiToken = "YOURHOCKEYAPPTOKEN"
    releaseType = 2 // alpha
    notify = 0
    status = 2
    notesType = 1
    notes = "Some notes."
    symbolsDirectory = file("build/symbols/")
    mappingFileNameRegex = "R.txt"
    variantToApplicationId = [
            BuildVariantA:  "applicationIdA",
            BuildVariantB:  "applicationIdB",
    ]
}

```
* `appFileNameRegex`: Only needed for iOS
* `outputDirectory`: Only needed for iOS
* `apiToken`: Your API Token from [HockeyApp](http://hockeyapp.net/)
* `releaseType`: `0` beta, `1` live, `2` alpha
* `notify`: `0` not notify testers, `1` notify all testers that can install this app
* `status`: `1` not allow users to download the version, `2` make the version available for download
* `notesType`: `0` Textile, `1` Markdown
* `notes`: optional, release notes as Textile or Markdown
* `symbolsDirectory`: `file("directory")`
* `mappingFileNameRegex`:  `mappingFileNameRegex= "R.txt"`
* `variantToApplicationId`:  Optional: `[variantName: "appId", variantName2: "appId2"]` map between your variant and application IDs
* `commitSha`: Optional: commit SHA for this build
* `buildServerUrl`: Optional: the URL of the build job on your build server
* `repositoryUrl`: Optional: your source repository URL

## Changelog

[Releases](https://github.com/x2on/gradle-hockeyapp-plugin/releases)

## License

gradle-hockeyapp-plugin is available under the MIT license. See the LICENSE file for more info.
