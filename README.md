# gradle-hockeyapp-plugin [![Build Status](https://travis-ci.org/x2on/gradle-hockeyapp-plugin.png)](https://travis-ci.org/x2on/gradle-hockeyapp-plugin) [![Maven Central](https://maven-badges.herokuapp.com/maven-central/de.felixschulze.gradle/gradle-hockeyapp-plugin/badge.svg)](http://search.maven.org/#search%7Cgav%7C1%7Cg%3A%22de.felixschulze.gradle%22%20AND%20a%3A%22gradle-hockeyapp-plugin%22) [![License MIT](http://img.shields.io/badge/license-MIT-blue.svg)](https://github.com/x2on/gradle-hockeyapp-plugin/blob/master/LICENSE)

A Gradle plugin for uploading iOS and Android Apps to HockeyApp.

## Compatibility

The plugin is compatible with gradle 2.14 and up.

## Basic usage

Add to your build.gradle

```gradle
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath 'de.felixschulze.gradle:gradle-hockeyapp-plugin:3.4'
    }
}

apply plugin: 'de.felixschulze.gradle.hockeyapp'
hockeyapp {
    apiToken = "YOURHOCKEYAPITOKEN"
}
```

### Upload task
The task name is generated based on your productFlavors and buildTypes. For a basic release build with no flavors using the gradle wrapper:
```gradle
./gradlew uploadReleaseToHockeyApp
```

## Advanced usage

Add to your build.gradle

```gradle
hockeyapp {
    apiToken = "YOURHOCKEYAPITOKEN"
    releaseType = 2 // alpha
    notify = 0
    status = 2
    notesType = 1
    notes = new File(file('../README.md').absolutePath).getText('UTF-8')
    variantToApplicationId = [
            BuildVariantA:  "hockeyAppapplicationIdA",
            BuildVariantB:  "hockeyAppapplicationIdB",
    ]
}

```
### Required
* `apiToken`: Your API Token from [HockeyApp](http://hockeyapp.net/)

### Optional

* `allowMultipleAppFiles`: `true` allow upload multiple app files (for example when using APK splits for Android)
* `buildServerUrl`: Optional: the URL of the build job on your build server
* `commitSha`: Optional: commit SHA for this build
* `mandatory`: `0` not mandatory, `1` mandatory
* `mappingFileNameRegex`:  Optional: `mappingFileNameRegex="mapping.txt"` Should contain the filename or a regex for the proguard `mapping.txt` mapping file (Android) or `dSYM` file (iOS). Standard is `mapping.txt`
* `notes`: Release notes as Textile or Markdown
* `notesType`: `0` Textile, `1` Markdown
* `notify`: `0` not notify testers, `1` notify all testers that can install this app
* `releaseType`: `0` beta, `1` live, `2` alpha
* `repositoryUrl`: Optional: your source repository URL
* `status`: `1` not allow users to download the version, `2` make the version available for download
* `tags`: Optional: restrict download to comma-separated list of tags
* `teamCityLog`: `true` Add features for [TeamCity](http://www.jetbrains.com/teamcity/)
* `teams`: Optional: restrict download to comma-separated list of team IDs; example teams 123, 213 with 123,213 being database ids of your teams
* `users`: Optional: restrict download to comma-separated list of user IDs; example:
					users=1224,5678 with 1224 and 5678 being the database IDs of your users
* `variantToApiToken`: Optional: `[variantName: "YOURHOCKEYAPITOKEN", variantName2: "YOUROTHERHOCKEYAPITOKEN"]` map between your variants and api tokens
* `variantToApplicationId`:  Optional (Android): `[variantName: "hockeyAppAppId", variantName2: "hockeyAppAppId2"]` map between your variants and HockeyApp application IDs
* `variantToMandatory`: Optional: `[variantName: "0", variantName2: "1"]` map between your variants and mandatory
* `variantToNotes` : Optional: `[variantName: "some notes", variantName2: "some other Notes"]` map between your variants and notes
* `variantToNotesType` : Optional: `[variantName: "0", variantName2: "1"]` map between your variants and notesType
* `variantToReleaseType`: Optional: `[variantName: "0", variantName2: "1"]` map between your variants and releaseType
* `variantToStatus`: Optional: `[variantName: "1", variantName2: "2"]` map between your variants and status
* `variantToTags`: Optional: `[variantName: "1", variantName2: "2"]` map between your variants and tags
* `variantToNotify`: Optional: `[variantName: "1", variantName2: "2"]` map between your variants and notify


### iOS or custom Android build only options
* `appFileNameRegex`: Only needed for iOS or if you don't use the android gradle plugin `appFileNameRegex = ".*.ipa"
* `outputDirectory`: Only needed for iOS: `file("directory")`
* `symbolsDirectory`: Only needed for iOS or if you don't use the android gradle plugin: `file("directory")` Directory which contains the `R` or `dSYM` file

## Migration from 2.x to >= 3.0

To migrate to version >= 3.0 please change 
```gradle
apply plugin: 'hockeyApp'
```
to 
```gradle
apply plugin: 'de.felixschulze.gradle.hockeyapp'
```

## Changelog

[Releases](https://github.com/x2on/gradle-hockeyapp-plugin/releases)

## Fix for Error with Top-Level-Projects / Multi project environment

If you use a top-level-project or multi project environment and got the error `java.lang.NoSuchFieldError: INSTANCE` or `Could not initialize class org.apache.http.impl.conn.ManagedHttpClientConnectionFactory` move the dependency to this plugin to your root `build.gradle` file (see [#30](https://github.com/x2on/gradle-hockeyapp-plugin/issues/30), [#62](https://github.com/x2on/gradle-hockeyapp-plugin/issues/62))

## License

gradle-hockeyapp-plugin is available under the MIT license. See the LICENSE file for more info.
