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
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import io.openliberty.tools.common.plugins.config.ServerConfigXmlDocument;
import io.openliberty.tools.common.plugins.config.XmlDocument;
import io.openliberty.tools.common.plugins.util.BinaryScannerUtil;
import io.openliberty.tools.common.plugins.util.PluginExecutionException;
import io.openliberty.tools.common.plugins.util.ServerFeatureUtil;
import io.openliberty.tools.maven.ServerFeatureSupport;

/**
 * This mojo generates the features required in the featureManager element in server.xml.
 * It examines the dependencies declared in the pom.xml and the features already declared
 * in the featureManager elements in the XML configuration files. Then it generates any
 * missing feature names and stores them in a new featureManager element in a new XML file.
 */
@Mojo(name = "generate-features")
public class GenerateFeaturesMojo extends ServerFeatureSupport {

    private static final String GENERATED_FEATURES_FILE_NAME = "generated-features.xml";
    protected static final String GENERATED_FEATURES_FILE_PATH = "configDropins/overrides/" + GENERATED_FEATURES_FILE_NAME;
    protected static final String FEATURES_FILE_MESSAGE = "The Liberty Maven Plugin has generated Liberty features necessary for your application in "
            + GENERATED_FEATURES_FILE_PATH;
    protected static final String HEADER = "This file was generated by the Liberty Maven Plugin and will be overwritten on subsequent runs of the liberty:generate-features goal."
            + "\n It is recommended that you do not edit this file and that you commit this file to your version control.";
    protected static final String GENERATED_FEATURES_COMMENT = "The following features were generated based on API usage detected in your application";
    protected static final String NO_NEW_FEATURES_COMMENT = "No additional features generated";

    private static final String BINARY_SCANNER_MAVEN_GROUP_ID = "com.ibm.websphere.appmod.tools";
    private static final String BINARY_SCANNER_MAVEN_ARTIFACT_ID = "binary-app-scanner";
    private static final String BINARY_SCANNER_MAVEN_TYPE = "jar";
    private static final String BINARY_SCANNER_MAVEN_VERSION = "[21.0.0.4-SNAPSHOT,)";

    private File binaryScanner;

    @Parameter(property = "classFiles")
    private List<String> classFiles;

    /**
     * If optimize is true, pass all class files and only user specified features to binary scanner
     * Otherwise, if optimize is false, pass only provided updated class files (if any) and all existing features to binary scanner
     */
    @Parameter(property = "optimize", defaultValue = "true")
    private boolean optimize;

    /*
     * (non-Javadoc)
     * @see org.codehaus.mojo.pluginsupport.MojoSupport#doExecute()
     */
    @Override
    protected void doExecute() throws Exception {
        if (skip) {
            log.info("\nSkipping generate-features goal.\n");
            return;
        }
        generateFeatures();
    }

    @Override
    protected void init() throws MojoExecutionException, MojoFailureException {
        // @see io.openliberty.tools.maven.BasicSupport#init() skip server config
        // setup as generate features does not require the server to be set up install
        // dir, wlp dir, outputdir, etc.
        this.skipServerConfigSetup = true;
        super.init();
    }

    /**
     * Generates features for the application given the API usage detected and
     * taking any user specified features into account
     * 
     * @throws MojoExecutionException
     * @throws PluginExecutionException indicates the binary-app-scanner.jar could
     *                                  not be found
     */
    private void generateFeatures() throws MojoExecutionException, PluginExecutionException {
        // If there are downstream projects (e.g. other modules depend on this module in the Maven Reactor build order),
        // then skip generate-features on this module
        ProjectDependencyGraph graph = session.getProjectDependencyGraph();
        List<MavenProject> upstreamProjects = new ArrayList<MavenProject>();
        if (graph != null) {
            checkMultiModuleConflicts(graph);
            List<MavenProject> downstreamProjects = graph.getDownstreamProjects(project, true);
            if (!downstreamProjects.isEmpty()) {
                log.debug("Downstream projects: " + downstreamProjects);
                return;
            } else {
                // get all upstream projects
                upstreamProjects.addAll(graph.getUpstreamProjects(project, true));
            }

            if (containsPreviousLibertyModule(graph)) {
                // skip this module
                return;
            }
        }

        binaryScanner = getBinaryScannerJarFromRepository();
        BinaryScannerHandler binaryScannerHandler = new BinaryScannerHandler(binaryScanner);

        log.debug("--- Generate Features values ---");
        log.debug("optimize generate features: " + optimize);
        if (classFiles != null && !classFiles.isEmpty()) {
            log.debug("Generate features for the following class files: " + classFiles.toString());
        }

        // TODO add support for env variables
        // commented out for now as the current logic depends on the server dir existing
        // and specifying features with env variables is an edge case
        /* Map<String, File> libertyDirPropertyFiles;
        try {
            libertyDirPropertyFiles = BasicSupport.getLibertyDirectoryPropertyFiles(installDirectory, userDirectory, serverDirectory);
        } catch (IOException e) {
            log.debug("Exception reading the server property files", e);
            log.error("Error attempting to generate server feature list. Ensure your user account has read permission to the property files in the server installation directory.");
            return;
        } */

        // TODO: get user specified features that have not yet been installed in the
        // original case they appear in a server config xml document.
        // getSpecifiedFeatures may not return the features in the correct case
        // Set<String> featuresToInstall = getSpecifiedFeatures(null); 

        // get existing server features from source directory
        ServerFeatureUtil servUtil = getServerFeatureUtil();
        servUtil.setLowerCaseFeatures(false);

        Set<String> generatedFiles = new HashSet<String>();
        generatedFiles.add(GENERATED_FEATURES_FILE_NAME);

        // if optimizing, ignore generated files when passing in existing features to
        // binary scanner
        Set<String> existingFeatures = servUtil.getServerFeatures(configDirectory, serverXmlFile,
                new HashMap<String, File>(), optimize ? generatedFiles : null);
        if (existingFeatures == null) {
            existingFeatures = new HashSet<String>();
        }
        servUtil.setLowerCaseFeatures(true);

        Set<String> scannedFeatureList = null;
        try {
            Set<String> directories = getClassesDirectories(upstreamProjects);
            String eeVersion = getEEVersion(project);
            String mpVersion = getMPVersion(project);
            scannedFeatureList = binaryScannerHandler.runBinaryScanner(existingFeatures, classFiles, directories, eeVersion, mpVersion, optimize);
        } catch (BinaryScannerUtil.NoRecommendationException noRecommendation) {
            throw new MojoExecutionException(String.format(BinaryScannerUtil.BINARY_SCANNER_CONFLICT_MESSAGE3, noRecommendation.getConflicts()));
        } catch (BinaryScannerUtil.RecommendationSetException showRecommendation) {
            if (showRecommendation.isExistingFeaturesConflict()) {
                throw new MojoExecutionException(String.format(BinaryScannerUtil.BINARY_SCANNER_CONFLICT_MESSAGE2, showRecommendation.getConflicts(), showRecommendation.getSuggestions()));
            } else {
                throw new MojoExecutionException(String.format(BinaryScannerUtil.BINARY_SCANNER_CONFLICT_MESSAGE1, showRecommendation.getConflicts(), showRecommendation.getSuggestions()));
            }
        } catch (InvocationTargetException | PluginExecutionException x) {
            // throw an error when there is a problem not caught in runBinaryScanner()
            Object o = x.getCause();
            if (o != null) {
                log.debug("Caused by exception:" + x.getCause().getClass().getName());
                log.debug("Caused by exception message:" + x.getCause().getMessage());
            }
            throw new MojoExecutionException("Failed to generate a working set of features. " + x.getMessage(), x);
        }

        Set<String> missingLibertyFeatures = new HashSet<String>();
        if (scannedFeatureList != null) {
            missingLibertyFeatures.addAll(scannedFeatureList);

            servUtil.setLowerCaseFeatures(false);
            // get set of user defined features so they can be omitted from the generated
            // file that will be written
            Set<String> userDefinedFeatures = optimize ? existingFeatures
                    : servUtil.getServerFeatures(configDirectory, serverXmlFile, new HashMap<String, File>(),
                            generatedFiles);
            log.debug("User defined features:" + userDefinedFeatures);
            servUtil.setLowerCaseFeatures(true);
            if (userDefinedFeatures != null) {
                missingLibertyFeatures.removeAll(userDefinedFeatures);
            }
        }
        log.debug("Features detected by binary scanner which are not in server.xml" + missingLibertyFeatures);

        File newServerXmlSrc = new File(configDirectory, GENERATED_FEATURES_FILE_PATH);
        File serverXml = findConfigFile("server.xml", serverXmlFile);
        ServerConfigXmlDocument doc = getServerXmlDocFromConfig(serverXml);
        log.debug("Xml document we'll try to update after generate features doc=" + doc + " file=" + serverXml);

        try {
            if (missingLibertyFeatures.size() > 0) {
                // Create special XML file to contain generated features.

                ServerConfigXmlDocument configDocument = ServerConfigXmlDocument.newInstance();
                configDocument.createComment(HEADER);
                Element featureManagerElem = configDocument.createFeatureManager();
                configDocument.createComment(featureManagerElem, GENERATED_FEATURES_COMMENT);
                for (String missing : missingLibertyFeatures) {
                    log.debug(String.format("Adding missing feature %s to %s.", missing, GENERATED_FEATURES_FILE_PATH));
                    configDocument.createFeature(missing);
                }
                configDocument.writeXMLDocument(newServerXmlSrc);
                log.debug("Created file " + newServerXmlSrc);
                // Add a reference to this new file in existing server.xml.
                addGenerationCommentToConfig(doc, serverXml);

                log.info("Generated the following features: " + missingLibertyFeatures);
            } else {
                log.info("No additional features were generated.");
                if (newServerXmlSrc.exists()) {
                    // generated-features.xml exists but no additional features were generated
                    // create empty features list with comment
                    ServerConfigXmlDocument configDocument = ServerConfigXmlDocument.newInstance();
                    configDocument.createComment(HEADER);
                    Element featureManagerElem = configDocument.createFeatureManager();
                    configDocument.createComment(featureManagerElem, NO_NEW_FEATURES_COMMENT);
                    configDocument.writeXMLDocument(newServerXmlSrc);
                }
            }
        } catch (ParserConfigurationException | TransformerException | IOException e) {
            log.debug("Exception creating the server features file", e);
                throw new MojoExecutionException(
                        "Automatic generation of features failed. Error attempting to create the "
                                + GENERATED_FEATURES_FILE_NAME
                                + ". Ensure your id has write permission to the server configuration directory.",
                        e);
        }
    }

    /**
     * Gets the binary scanner jar file from the local cache.
     * Downloads it first from connected repositories such as Maven Central if a newer release is available than the cached version.
     * Note: Maven updates artifacts daily by default based on the last updated timestamp. Users should use 'mvn -U' to force updates if needed.
     * 
     * @return The File object of the binary scanner jar in the local cache.
     * @throws PluginExecutionException
     */
    private File getBinaryScannerJarFromRepository() throws PluginExecutionException {
        try {
            return getArtifact(BINARY_SCANNER_MAVEN_GROUP_ID, BINARY_SCANNER_MAVEN_ARTIFACT_ID, BINARY_SCANNER_MAVEN_TYPE, BINARY_SCANNER_MAVEN_VERSION).getFile();
        } catch (Exception e) {
            throw new PluginExecutionException("Could not retrieve the artifact " + BINARY_SCANNER_MAVEN_GROUP_ID + "."
                    + BINARY_SCANNER_MAVEN_ARTIFACT_ID
                    + " needed for liberty:generate-features. Ensure you have a connection to Maven Central or another repository that contains the "
                    + BINARY_SCANNER_MAVEN_GROUP_ID + "." + BINARY_SCANNER_MAVEN_ARTIFACT_ID
                    + ".jar configured in your pom.xml",
                    e);
        }
    }

    /*
     * Return specificFile if it exists; otherwise return the file with the requested fileName from the 
     * configDirectory, but only if it exists. Null is returned if the file does not exist in either location.
     */
    private File findConfigFile(String fileName, File specificFile) {
        if (specificFile != null && specificFile.exists()) {
            return specificFile;
        }

        File f = new File(configDirectory, fileName);
        if (configDirectory != null && f.exists()) {
            return f;
        }
        return null;
    }

    private ServerConfigXmlDocument getServerXmlDocFromConfig(File serverXml) {
        if (serverXml == null || !serverXml.exists()) {
            return null;
        }
        try {
            return ServerConfigXmlDocument.newInstance(serverXml);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            log.debug("Exception creating server.xml object model", e);
        }
        return null;
    }

    /**
     * Remove the comment in server.xml that warns we created another file with features in it.
     */
    private void removeGenerationCommentFromConfig(ServerConfigXmlDocument doc, File serverXml) {
        if (doc == null) {
            return;
        }
        try {
            doc.removeFMComment(FEATURES_FILE_MESSAGE);
            doc.writeXMLDocument(serverXml);
        } catch (IOException | TransformerException e) {
            log.debug("Exception removing comment from server.xml", e);
        }
        return;
    }

    /**
     * Add a comment to server.xml to warn them we created another file with features in it.
     * Only writes the file if the comment does not exist yet.
     */
    private void addGenerationCommentToConfig(ServerConfigXmlDocument doc, File serverXml) {
        if (doc == null) {
            return;
        }
        try {
            if (doc.createFMComment(FEATURES_FILE_MESSAGE)) {
                doc.writeXMLDocument(serverXml);
                XmlDocument.addNewlineBeforeFirstElement(serverXml);
            }
        } catch (IOException | TransformerException e) {
            log.debug("Exception adding comment to server.xml", e);
        }
        return;
    }

    // Return a list containing the classes directory of the current project and any
    // upstream module projects
    private Set<String> getClassesDirectories(List<MavenProject> upstreamProjects) throws MojoExecutionException {
        Set<String> dirs = new HashSet<String>();
        String classesDirName = null;
        // First check the Java build output directory (target/classes) for the current
        // project
        classesDirName = getClassesDirectory(project.getBuild().getOutputDirectory());
        if (classesDirName != null) {
            dirs.add(classesDirName);
        }
        log.debug("For binary scanner gathering Java build output directories for upstream projects, size="
                + upstreamProjects.size());
        for (MavenProject upstreamProject : upstreamProjects) {
            classesDirName = getClassesDirectory(upstreamProject.getBuild().getOutputDirectory());
            if (classesDirName != null) {
                dirs.add(classesDirName);
            }
        }
        for (String s : dirs) {
            log.debug("Found dir:" + s);
        }
        return dirs;
    }

    // Check one directory and if it exists return its canonical path (or absolute path if error).
    private String getClassesDirectory(String outputDir) {
        File classesDir = new File(outputDir);
        try {
            if (classesDir.exists()) {
                return classesDir.getCanonicalPath();
            }
        } catch (IOException x) {
            String classesDirAbsPath = classesDir.getAbsolutePath();
            log.debug("IOException obtaining canonical path name for a project's classes directory: " + classesDirAbsPath);
            return classesDirAbsPath;
        }
        return null; // directory does not exist.
    }

    public String getEEVersion(MavenProject project) {
        List<Dependency> dependencies = project.getDependencies();
        for (Dependency d : dependencies) {
            if (!d.getScope().equals("provided")) {
                continue;
            }
            log.debug("getEEVersion, dep="+d.getGroupId()+":"+d.getArtifactId()+":"+d.getVersion());
            if (d.getGroupId().equals("javax") && d.getArtifactId().equals("javaee-api")) {
                if (d.getVersion().startsWith("8.")) {
                    return "ee8";
                } else if (d.getVersion().startsWith("7.")) {
                    return "ee7";
                } else if (d.getVersion().startsWith("6.")) {
                    return "ee6";
                }
            } else if (d.getGroupId().equals("jakarta.platform") &&
                    d.getArtifactId().equals("jakarta.jakartaee-api") &&
                    d.getVersion().startsWith("8.")) {
                return "ee8";
            }
        }
        return null;
    }

    public String getMPVersion(MavenProject project) {  // figure out correct level of mp from declared dependencies
        List<Dependency> dependencies = project.getDependencies();
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
                } else if (version.startsWith("3")) {
                    return "mp3";
                }
                return "mp4"; // add support for future versions of MicroProfile here
            }
            // if (d.getGroupId().equals("io.openliberty.features")) {
            //     mpVersion = Math.max(mpVersion, getMPVersion(d.getArtifactId()));
            //     log.debug("dep=io.openliberty.features:"+d.getArtifactId()+" mpVersion="+mpVersion);
            // }
        }
        return null;
    }

    // logic to map individual deps to MP versions, not currently in use
    public static int getMPVersion(String shortName) {
        final int MP_VERSIONS = 4; // number of version columns in table
        String[][] mpComponents = {
            // Name, MP1 version, MP2 version, MP3 version, MP4 version
            { "mpconfig", "1.3", "1.3", "1.4", "2.0" },
            { "mpfaulttolerance", "1.1", "2.0", "2.1", "3.0" },
            { "mphealth", "1.0", "1.0", "2.2", "3.0" },
            { "mpjwt", "1.1", "1.1", "1.1", "1.2" },
            { "mpmetrics", "1.1", "1.1", "2.3", "3.0" },
            { "mpopenapi", "1.0", "1.1", "1.1", "2.0" },
            { "mpopentracing", "1.1", "1.3", "1.3", "2.0" },
            { "mprestclient", "1.1", "1.2", "1.4", "2.0" },
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

    // Define the logging functions of the binary scanner handler and make it available in this plugin
    private class BinaryScannerHandler extends BinaryScannerUtil {
        BinaryScannerHandler(File scannerFile) {
            super(scannerFile);
        }
        @Override
        public void debug(String msg) {
            log.debug(msg);
        }
        @Override
        public void debug(String msg, Throwable t) {
            log.debug(msg, t);
        }
        @Override
        public void error(String msg) {
            log.error(msg);
        }
        @Override
        public void warn(String msg) {
            log.warn(msg);
        }
        @Override
        public void info(String msg) {
            log.info(msg);
        }
    }

}
