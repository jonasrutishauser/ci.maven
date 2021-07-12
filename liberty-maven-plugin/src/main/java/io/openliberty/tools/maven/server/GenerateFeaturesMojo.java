/**
 * (C) Copyright IBM Corporation 2021.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package io.openliberty.tools.maven.server;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.MalformedURLException;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.execution.ProjectDependencyGraph;

import io.openliberty.tools.common.plugins.config.ServerConfigDropinXmlDocument;
import io.openliberty.tools.common.plugins.util.InstallFeatureUtil;
import io.openliberty.tools.common.plugins.util.PluginExecutionException;
import io.openliberty.tools.common.plugins.util.PluginScenarioException;
import io.openliberty.tools.common.plugins.util.InstallFeatureUtil.ProductProperties;
import io.openliberty.tools.maven.BasicSupport;
import io.openliberty.tools.maven.InstallFeatureSupport;

/**
 * This mojo generates the features required in the featureManager element in server.xml.
 * It examines the dependencies declared in the pom.xml and the features already declared
 * in the featureManager elements in the XML configuration files. Then it generates any
 * missing feature names and stores them in a new featureManager element in a new XML file.
 */
@Mojo(name = "generate-features")
public class GenerateFeaturesMojo extends InstallFeatureSupport {

    /**
     * The name of the jar file which contains the binary scanner used to detect features.
     */
    @Parameter(property = "featureScannerJar")
    private File binaryScanner;

    protected static final String PLUGIN_ADDED_FEATURES_FILE = "configDropins/overrides/liberty-plugin-added-features.xml";
    protected static final String HEADER = "# Generated by liberty-maven-plugin";

    /*
     * (non-Javadoc)
     * @see org.codehaus.mojo.pluginsupport.MojoSupport#doExecute()
     */
    @Override
    protected void doExecute() throws Exception {
        if(!initialize()) {
            return;
        }
        generateFeatures();
    }

    private void generateFeatures() throws PluginExecutionException {
        log.warn("warn");
        List<ProductProperties> propertiesList = InstallFeatureUtil.loadProperties(installDirectory);
        String openLibertyVersion = InstallFeatureUtil.getOpenLibertyVersion(propertiesList);
        log.warn("version:"+openLibertyVersion);

        InstallFeatureMojoUtil util;
        try {
            util = new InstallFeatureMojoUtil(new HashSet<String>(), propertiesList, openLibertyVersion, null);
        } catch (PluginScenarioException e) {
            log.debug("Exception creating the server utility object", e);
            log.error("Error attempting to generate server feature list.");
            return;
        }

        Set<String> visibleServerFeatures = util.getAllServerFeatures();
        log.warn("feature count="+visibleServerFeatures.size());

        Set<String> libertyFeatureDependencies = getFeaturesFromDependencies(project);
        log.warn("maven dependencies that are liberty features:"+libertyFeatureDependencies);

        // Remove project dependency features which are hidden.
        Set<String> visibleLibertyProjectDependencies = new HashSet<String>(libertyFeatureDependencies);
        visibleLibertyProjectDependencies.retainAll(visibleServerFeatures);
        log.warn("maven dependencies that are VALID liberty features:"+visibleLibertyProjectDependencies);

        File newServerXml = new File(serverDirectory, PLUGIN_ADDED_FEATURES_FILE);
        log.warn("New server xml file:"+newServerXml);

        Path tempDir = null;
        Path newServerXmlCopy = null;
        Map<String, File> libertyDirPropertyFiles;
        try {
            if (newServerXml.exists()) {  // about to regenerate this file. Must be removed before getLibertyDirectoryPropertyFiles
                tempDir = Files.createTempDirectory("liberty-plugin-added-features");
                newServerXmlCopy = Files.move(newServerXml.toPath(), tempDir.resolve(newServerXml.getName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            libertyDirPropertyFiles = BasicSupport.getLibertyDirectoryPropertyFiles(installDirectory, userDirectory, serverDirectory);
            if (newServerXmlCopy != null) {
                Files.delete(newServerXmlCopy); // delete the saved file
                Files.delete(tempDir);
            }
        } catch (IOException e) {
            if (newServerXmlCopy != null) {
                try {
                    // restore the xml file just moved aside above
                    Files.move(newServerXmlCopy, newServerXml.toPath());
                    Files.delete(tempDir);
                } catch (IOException f) {
                    log.debug("Exception trying to restore file: "+PLUGIN_ADDED_FEATURES_FILE+". "+f);
                }
            }
            log.debug("Exception reading the server property files", e);
            log.error("Error attempting to generate server feature list. Ensure your id has read permission to the property files in the server installation directory.");
            return;
        }
        Set<String> existingFeatures = util.getServerFeatures(serverDirectory, libertyDirPropertyFiles);
        log.warn("Features in server.xml:"+existingFeatures);

        // The Liberty features missing from server.xml
        Set<String> missingLibertyFeatures = getMissingLibertyFeatures(visibleLibertyProjectDependencies,
				existingFeatures);
        log.warn("maven dependencies that are VALID liberty features but are missing from server.xml:"+missingLibertyFeatures);
        //
        // Scan for features after processing the POM. POM features take priority over scannned features
        Set<String> scannedFeatureList = runBinaryScanner();
        if (scannedFeatureList != null) {
            // tabulate the existing features by name and version number and lookup each scanned feature
            Map<String, String> existingFeatureMap = new HashMap();
            for (String existingFeature : existingFeatures) {
                String[] nameAndVersion = getNameAndVersion(existingFeature);
                existingFeatureMap.put(nameAndVersion[0], nameAndVersion[1]);
            }
            for (String missingLibertyFeature : missingLibertyFeatures) {
                String[] nameAndVersion = getNameAndVersion(missingLibertyFeature);
                existingFeatureMap.put(nameAndVersion[0], nameAndVersion[1]);
            }
            for (String scannedFeature : scannedFeatureList) {
                String[] scannedNameAndVersion = getNameAndVersion(scannedFeature);
                String existingFeatureVersion = existingFeatureMap.get(scannedNameAndVersion[0]);
                if (existingFeatureVersion != null) {
                    if (existingFeatureVersion.compareTo(scannedNameAndVersion[1]) < 0) {
                        log.warn(String.format("The binary scanner detected a dependency on %s but the project's POM or server.xml specified the dependency %s-%s.", scannedFeature, scannedNameAndVersion[0], existingFeatureVersion));
                    }
                } else {
                    // scanned feature not found in server.xml or POM
                    missingLibertyFeatures.add(scannedFeature);
                    log.debug(String.format("Adding feature %s to server.xml because it was detected by binary scanner.", scannedFeature));
                }
            }
        }
        if (missingLibertyFeatures.size() > 0) {
            // Create specialized server.xml
            try {
                ServerConfigDropinXmlDocument configDocument = ServerConfigDropinXmlDocument.newInstance();
                configDocument.createComment(HEADER);
                for (String missing : missingLibertyFeatures) {
                    log.debug(String.format("Adding missing feature %s to %s.", missing, PLUGIN_ADDED_FEATURES_FILE));
                    configDocument.createFeature(missing);
                }
                configDocument.writeXMLDocument(newServerXml);
                log.debug("Created file "+newServerXml);
            } catch(ParserConfigurationException | TransformerException | IOException e) {
                log.debug("Exception creating the server features file", e);
                log.error("Error attempting to create the server feature file. Ensure your id has write permission to the server installation directory.");
                return;
            }
        }
    }

    /**
     * Comb through the list of Maven project dependencies and find the ones which are 
     * Liberty features.
     * @param project  Current Maven project
     * @return List of names of dependencies
     */
    private Set<String> getFeaturesFromDependencies(MavenProject project) {
        Set<String> libertyFeatureDependencies = new HashSet<String>();
        List<Dependency> allProjectDependencies = project.getDependencies();
        for (Dependency d : allProjectDependencies) {
            String featureName = getFeatureName(d);
            if (featureName != null) {
                libertyFeatureDependencies.add(featureName);
            }
        }
        return libertyFeatureDependencies;
    }

    /**
     * From all the candidate project dependencies remove the ones already in server.xml
     * to make the list of the ones that are missing from server.xml.
     * @param visibleLibertyProjectDependencies
     * @param existingFeatures
     * @return
     */
	private Set<String> getMissingLibertyFeatures(Set<String> visibleLibertyProjectDependencies,
			Set<String> existingFeatures) {
		Set<String> missingLibertyFeatures = new HashSet<String>(visibleLibertyProjectDependencies);
        if (existingFeatures != null) {
            for (String s : visibleLibertyProjectDependencies) {
                // existingFeatures are all lower case
                if (existingFeatures.contains(s.toLowerCase())) {
                    missingLibertyFeatures.remove(s);
                }
            }
        }
		return missingLibertyFeatures;
	}

	/**
	 * Determine if a dependency is a Liberty feature or not
	 * @param mavenDependency  a Maven project dependency 
	 * @return the Liberty feature name if the input is a Liberty feature otherwise return null.
	 */
    private String getFeatureName(Dependency mavenDependency) {
        if (mavenDependency.getGroupId().equals("io.openliberty.features")) {
            return mavenDependency.getArtifactId();
        }
        return null;
    }

    private Set<String> runBinaryScanner() {
        log.warn("Run binary scanner using reflection");
        log.debug("binaryScanner="+binaryScanner);
        if (binaryScanner != null && binaryScanner.exists()) {
            ClassLoader cl = this.getClass().getClassLoader();
            try {
                URLClassLoader ucl = new URLClassLoader(new URL[] { binaryScanner.toURI().toURL() }, cl);
                Class driveScan = ucl.loadClass("com.ibm.ws.report.binary.cmdline.DriveScan");
                // args: String[], String, String, java.util.Locale
                java.lang.reflect.Method driveScanMavenFeaureList = driveScan.getMethod("DriveScanMavenFeaureList", String[].class, String.class, String.class, java.util.Locale.class);
                log.warn("new method, driveScanMavenFeaureList="+driveScanMavenFeaureList.getName()+" parameter count="+ driveScanMavenFeaureList.getParameterCount());
                String[] directoryList = getClassesDirectories();
                if (directoryList == null || directoryList.length == 0) {
                    log.warn("Error collecting list of directories to send to binary scanner, list is null or empty.");
                    log.debug("Error collecting list of directories to send to binary scanner, list is null or empty.");
                    return null;
                }
                for (String s : directoryList) {log.warn(" scanning directory "+ s);}
                String eeVersion = getEEVersion(project); 
                String mpVersion = getMPVersion(project);
                log.warn("eeVersion="+eeVersion+" mpVersion="+mpVersion);
                log.debug("The following messages are from the application binary scanner used to generate Liberty features");
                Set<String> featureList = (Set<String>) driveScanMavenFeaureList.invoke(null, directoryList, eeVersion, mpVersion, java.util.Locale.getDefault());
                log.debug("End of messages from application binary scanner");
                log.warn("runBinaryScanner, Features recommended :");
                for (String s : featureList) {log.warn(s);};
                return featureList;
            } catch (MalformedURLException|ClassNotFoundException|NoSuchMethodException|IllegalAccessException|java.lang.reflect.InvocationTargetException x){
                // TODO Figure out what to do when there is a problem scanning the features
                log.error("Exception:"+x.getClass().getName());
                Object o = x.getCause();
                if (o != null) {
                    log.warn("Exception:"+x.getCause().getClass().getName());
                    log.warn("Exception message:"+x.getCause().getMessage());
                }
                log.error(x.getMessage());
            }
            return null;
        } else {
            log.debug("Unable to load the binary scanner jar");
            return null;
        }
    }

    // Return a list containing the classes directory of the current project and any module projects
    private String[] getClassesDirectories() {
        log.warn("getClassesDirectories");
        List<String> dirs = new ArrayList();
        String classesDirName = null;
        classesDirName = getClassesDirectory(project.getBuild().getOutputDirectory());
        log.warn("this project's dir name:"+classesDirName);
        if (classesDirName != null) {
            dirs.add(classesDirName);
            log.warn("added "+classesDirName);
        }
        ProjectDependencyGraph graph = session.getProjectDependencyGraph();
        List<MavenProject> upstreamProjects = graph.getUpstreamProjects(project, true);
        log.warn("upstreamProjects.size()="+upstreamProjects.size());
        List<MavenProject> allProjects = graph.getSortedProjects();
        log.warn("allProjects.size()="+allProjects.size());
        for (MavenProject aProject : allProjects) {
            log.warn("aProject id="+aProject.getId());
        }
        for (MavenProject upstreamProject : upstreamProjects) {
            classesDirName = getClassesDirectory(upstreamProject.getBuild().getOutputDirectory());
            log.warn("upstream project classesdir "+classesDirName);
            if (classesDirName != null) {
                dirs.add(classesDirName);
                log.warn("added "+classesDirName);
            }
        }
        return dirs.toArray(new String[dirs.size()]);
    }

    private String getClassesDirectory(String outputDir) {
        log.warn("get one dir name:"+outputDir);
        File classesDir = new File(outputDir);
        log.warn("File classesDir:"+classesDir+" exists:"+classesDir.exists());
        try {
            if (classesDir.exists()) {
                log.warn("1. returning:"+ classesDir.getCanonicalPath());
                return classesDir.getCanonicalPath();
            }
        } catch (IOException x) {
            log.debug("IOException obtaining canonical path name for project classes directory: " + classesDir.getAbsolutePath());
            log.warn("2. returning:"+ classesDir.getAbsolutePath());
            return classesDir.getAbsolutePath();
        }
        return null; // directory does not exist.
    }

    private String getEEVersion(MavenProject project) {
        return "ee8"; // figure out if we need 7 or 9
    }

    public String getMPVersion(MavenProject project) {  // figure out correct level of mp from declared dependencies
        List<Dependency> dependencies = project.getDependencies();
        int mpVersion = 0;
        for (Dependency d : dependencies) {
            if (!d.getScope().equals("provided")) {
                continue;
            }
            if (d.getGroupId().equals("org.eclipse.microprofile") &&
                d.getArtifactId().equals("microprofile")) {
                String version = d.getVersion();
                log.debug("dep=org.eclipse.microprofile:microprofile version="+version);
                if (version.startsWith("1")) {
                    return "mp1";
                } else if (version.startsWith("2")) {
                    return "mp2";
                }
                return "mp3"; // add support for future versions of MicroProfile here
            }
            if (d.getGroupId().equals("io.openliberty.features")) {
                mpVersion = Math.max(mpVersion, getMPVersion(d.getArtifactId()));
                log.debug("dep=io.openliberty.features:"+d.getArtifactId()+" mpVersion="+mpVersion);
            }
        }
        if (mpVersion == 1) {
            return "mp1";
        } else if (mpVersion == 2) {
            return "mp2";
        }
        return "mp3";
    }

    public static int getMPVersion(String shortName) {
        final int MP_VERSIONS = 3; // number of version columns in table
        String[][] mpComponents = {
            // Name, MP1 version, MP2 version, MP3 version
            { "mpconfig", "1.3", "1.3", "1.4" },
            { "mpfaulttolerance", "1.1", "2.0", "2.1" },
            { "mphealth", "1.0", "1.0", "2.2" },
            { "mpjwt", "1.1", "1.1", "1.1" },
            { "mpmetrics", "1.1", "1.1", "2.3" },
            { "mpopenapi", "1.0", "1.1", "1.1" },
            { "mpopentracing", "1.1", "1.3", "1.3" },
            { "mprestclient", "1.1", "1.2", "1.4" },
        };
        if (shortName == null) {
            return 0;
        }
        if (!shortName.startsWith("mp")) { // efficiency
            return 0;
        }
        String[] nameAndVersion = getNameAndVersion(shortName);
        if (nameAndVersion == null) {
            return 0;
        }
        String name = nameAndVersion[0];
        String version = nameAndVersion[1];
        for (int i = 0; i < mpComponents.length; i++) {
            if (mpComponents[i][0].equals(name)) {
                for (int j = MP_VERSIONS; j >= 0; j--) { // use highest compatible version
                    if (mpComponents[i][j].compareTo(version) < 0 ) {
                        return (j == MP_VERSIONS) ? MP_VERSIONS : j+1; // in case of error just return max version
                    }
                    if (mpComponents[i][j].compareTo(version) == 0 ) {
                        return j;
                    }
                }
                return 1; // version specified is between 1.0 and max version number in MicroProfile 1.2
            }
        }
        return 0; // the dependency name is not one of the Microprofile components
    }

    public static String[] getNameAndVersion(String featureName) {
        if (featureName == null) {
            return null;
        }
        String[] nameAndVersion = featureName.split("-", 2);
        if (nameAndVersion.length != 2) {
            return null;
        }
        if (nameAndVersion[1] == null) {
            return null;
        }
        nameAndVersion[0] = nameAndVersion[0].toLowerCase();
        if (nameAndVersion[1] == null || nameAndVersion[1].length() != 3) {
            return null;
        }
        return nameAndVersion;
    }
}
