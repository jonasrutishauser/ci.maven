#### generate-features
---
Scan the class files of your application and create a new Liberty configuration file containing the features your application requires.

This goal is available as a tech preview in the 3.5.2-SNAPSHOT. Please provide feedback by opening an issue at https://github.com/OpenLiberty/ci.maven.

This feature is best accessed through dev mode. When you start up `liberty:dev` it will compile your application and scan the files to verify that all the required Liberty features are part of your configuration. Then as you work, dev mode will continue to monitor the project to confirm the Liberty features are up to date. If you implement a new interface in Java, the scanner will determine if that API is connected to a Liberty feature, then update the configuration and install the feature. If you remove a feature from `server.xml`, dev mode will determine if that feature is actually necessary, and if so, add it to the configuration file as described below. For this snapshot you need to add the Sonatype repository to `pom.xml` (shown below), but in the future all the dependencies will be in Maven Central.

If you need to disable feature generation, you can use the parameter `-DgenerateFeatures=false`.

##### Lifecycle

This goal is not part of the Maven lifecycle, so to use it in your build you will need to understand its dependencies. Since it will scan the class files of your application, it must be run after the `compile` goal. The list of features that it generates will be used by the `liberty:create` and the `liberty:install-feature` goals, so run this goal first.

If this goal detects Liberty features used in your project but not present in your Liberty configuration, it will create a new file `configDropins/overrides/generated-features.xml` in the `src/main/liberty/config` directory of your project. The `generated-features.xml` file will contain a list of features required for your project. If the `generated-features.xml` file has been created in the past and no additional features have been detected, this file will be retained and will contain a comment indicating that there are no additional features generated.

The goal examines the `pom.xml` dependencies to determine what version of Java EE and what version of MicroProfile you may be using. It will then generate features which are compatible. 

For Java EE the goal looks for group ID `javax`, the artifact ID `javaee-api` and versions `6.0`, `7.0` or `8.0`. For Jakarta EE the goal looks for group ID `jakarta.platform`, the artifact ID `jakarta.jakartaee-api` and version `8.0`.

For MicroProfile it looks for group ID `org.eclipse.microprofile` and artifact ID `microprofile` and generates features according to the version number. The goal uses these compile dependencies to determine the best Liberty features to use with your application. 

The goal also considers the features you have already specified in `server.xml` or other files that Liberty will use (e.g. `include` elements and `configDropins` files). The goal will attempt to find a working set of features that are compatible with each other.

If there are conflicts with features specified in Liberty configuration files or features used in the application code, the goal will print an error message. If available, the goal will also print a list of suggested features with no conflicts.

##### Tech Preview Limitations

* For MicroProfile, this goal will generate the latest features available in a given major release (e.g. even if you specify `org.eclipse.microprofile:microprofile:3.2` and you use mpHealth APIs, this goal will generate the feature `mpHealth-2.2`, which is the latest version available for MicroProfile 3.x)
* Jakarta EE version 9 or 9.1 is not supported at this time
* When using the `serverXmlFile` parameter in the POM, if you specify a file not in the directory `src/main/liberty/config` and that file uses relative paths to include other files, any features in those files will not be considered for feature generation
* Any features accessed using property variables (e.g. `${custom.key}/configFile.xml`) are not considered for feature generation

For the tech preview snapshot, you must include the Sonatype repository in `pom.xml` for the plugin and its dependencies:
```xml
    <pluginRepositories>
      <pluginRepository>
        <id>sonatypep</id>
        <url>https://oss.sonatype.org/content/repositories/snapshots</url>
      </pluginRepository>
    </pluginRepositories>
    <repositories>
      <repository>
        <id>sonatype</id>
        <url>https://oss.sonatype.org/content/repositories/snapshots</url>
      </repository>
    </repositories>
```
```xml
<plugin>
    <groupId>io.openliberty.tools</groupId>
    <artifactId>liberty-maven-plugin</artifactId>
    <version>3.5.2-SNAPSHOT</version>
</plugin>
```
##### Example (outside of dev mode):

Compile the application code and generate Liberty features.
* `mvn compile liberty:generate-features`

