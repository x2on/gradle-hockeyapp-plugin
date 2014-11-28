# gradle-hockeyapp-plugin [![Build Status](https://travis-ci.org/x2on/gradle-hockeyapp-plugin.png)](https://travis-ci.org/x2on/gradle-hockeyapp-plugin) [![Maven Central](https://maven-badges.herokuapp.com/maven-central/de.felixschulze.gradle/gradle-hockeyapp-plugin/badge.svg)](http://search.maven.org/#search%7Cgav%7C1%7Cg%3A%22de.felixschulze.gradle%22%20AND%20a%3A%22gradle-hockeyapp-plugin%22) [![License MIT](http://img.shields.io/badge/license-MIT-blue.svg)](https://github.com/x2on/gradle-hockeyapp-plugin/blob/master/LICENSE)

A Gradle plugin for uploading iOS and Android Apps to HockeyApp.

## Basic usage

Add to your build.gradle

```gradle
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath 'de.felixschulze.gradle:gradle-hockeyapp-plugin:2.4+'
    }
}

apply plugin: 'hockeyApp'
hockeyapp {
    apiToken = "YOURHOCKEYAPPTOKEN"
}
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
    variantToApplicationId = [
            BuildVariantA:  "applicationIdA",
            BuildVariantB:  "applicationIdB",
    ]
}

```
### Required
* `apiToken`: Your API Token from [HockeyApp](http://hockeyapp.net/)

### Optional
* `variantToApiToken`: Optional: `[variantName: "YOURHOCKEYAPPTOKEN", variantName2: "YOUROTHERHOCKEYAPPTOKEN"]` map between your variants and api tokens
* `releaseType`: `0` beta, `1` live, `2` alpha
* `variantToReleaseType`: Optional: `[variantName: "0", variantName2: "1"]` map between your variants and releaseType
* `notify`: `0` not notify testers, `1` notify all testers that can install this app
* `status`: `1` not allow users to download the version, `2` make the version available for download
* `variantToStatus`: Optional: `[variantName: "1", variantName2: "2"]` map between your variants and status
* `notes`: Release notes as Textile or Markdown
* `variantToNotes` : Optional: `[variantName: "some notes", variantName2: "some other Notes"]` map between your variants and notes
* `notesType`: `0` Textile, `1` Markdown
* `variantToNotesType` : Optional: `[variantName: "0", variantName2: "1"]` map between your variants and notesType
* `mandatory`: `0` not mandatory, `1` mandatory
* `variantToMandatory`: Optional: `[variantName: "0", variantName2: "1"]` map between your variants and mandatory
* `mappingFileNameRegex`:  Optional: `mappingFileNameRegex="mapping.txt"` Should contain the filename or a regex for the proguard `mapping.txt` mapping file (Android) or `dSYM` file (iOS). Standard is `mapping.txt`
* `variantToApplicationId`:  Optional (Android): `[variantName: "appId", variantName2: "appId2"]` map between your variants and application IDs
* `symbolsDirectory`: Only needed for iOS or if you don't use the android gradle plugin: `file("directory")` Directory which contains the `R` or `dSYM` file
* `appFileNameRegex`: Only needed for iOS or if you don't use the android gradle plugin `appFileNameRegex = ".*.ipa"
* `outputDirectory`: Only needed for iOS: `file("directory")`
* `tags`: Optional: restrict download to comma-separated list of tags
* `commitSha`: Optional: commit SHA for this build
* `buildServerUrl`: Optional: the URL of the build job on your build server
* `repositoryUrl`: Optional: your source repository URL
* `teamCityLog`: `true` Add features for [TeamCity](http://www.jetbrains.com/teamcity/)

## Changelog

[Releases](https://github.com/x2on/gradle-hockeyapp-plugin/releases)

## Fix for Error with Top-Level-Projects

If you use a top-level-project and got the error `java.lang.NoSuchFieldError: INSTANCE` move the dependency to this plugin to your root `build.gradle` file (see [#30](https://github.com/x2on/gradle-hockeyapp-plugin/issues/30))

## License

gradle-hockeyapp-plugin is available under the MIT license. See the LICENSE file for more info.
