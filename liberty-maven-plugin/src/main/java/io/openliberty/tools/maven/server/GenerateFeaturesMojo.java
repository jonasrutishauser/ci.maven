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
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.xml.sax.SAXException;

import io.openliberty.tools.common.plugins.config.ServerConfigXmlDocument;
import io.openliberty.tools.common.plugins.config.XmlDocument;
import io.openliberty.tools.common.plugins.util.InstallFeatureUtil;
import io.openliberty.tools.common.plugins.util.PluginExecutionException;
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

    private static final String GENERATED_FEATURES_FILE_NAME = "generated-features.xml";
    protected static final String GENERATED_FEATURES_FILE_PATH = "configDropins/overrides/" + GENERATED_FEATURES_FILE_NAME;
    protected static final String FEATURES_FILE_MESSAGE = "The Liberty Maven Plugin has generated Liberty features necessary for your application in " + GENERATED_FEATURES_FILE_PATH;
    protected static final String HEADER = "# Generated by liberty-maven-plugin";

    private static final String BINARY_SCANNER_MAVEN_GROUP_ID = "com.ibm.websphere.appmod.tools";
    private static final String BINARY_SCANNER_MAVEN_ARTIFACT_ID = "binaryAppScanner";
    private static final String BINARY_SCANNER_MAVEN_TYPE = "jar";
    private static final String BINARY_SCANNER_MAVEN_VERSION = "[0.0.1,)";

    private static final String BINARY_SCANNER_CONFLICT_MESSAGE1 = "A working set of features could not be generated due to conflicts " +
            "between configured features and the application's API usage: %s. Review and update your server configuration and " +
            "application to ensure they are not using conflicting features and APIs from different levels of MicroProfile, " +
            "Java EE, or Jakarta EE. Refer to the following set of suggested features for guidance: %s";
    private static final String BINARY_SCANNER_CONFLICT_MESSAGE2 = "A working set of features could not be generated due to conflicts " +
            "between configured features: %s. Review and update your server configuration to ensure it is not using conflicting " +
            "features from different levels of MicroProfile, Java EE, or Jakarta EE. Refer to the following set of " +
            "suggested features for guidance: %s";
    private static final String BINARY_SCANNER_CONFLICT_MESSAGE3 = "A working set of features could not be generated due to conflicts " +
            "in the application’s API usage: %s. Review and update your application to ensure it is not using conflicting APIs " +
            "from different levels of MicroProfile, Java EE, or Jakarta EE.";
    private static final String BINARY_SCANNER_CONFLICT_MESSAGE4 = "[None available]"; // format should match JVM Set.toString()

    private File binaryScanner;
    private URLClassLoader binaryScannerClassLoader = null;

    @Parameter(property = "classFiles")
    private List<String> classFiles;

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
        binaryScanner = getBinaryScannerJarFromRepository();

        if (classFiles != null && !classFiles.isEmpty()) {
            log.debug("Generate features for the following class files: " + classFiles.toString());
        } else {
            log.debug("Generate features for all class files");
        }

        Map<String, File> libertyDirPropertyFiles;
        try {
            libertyDirPropertyFiles = BasicSupport.getLibertyDirectoryPropertyFiles(installDirectory, userDirectory, serverDirectory);
        } catch (IOException e) {
            log.debug("Exception reading the server property files", e);
            log.error("Error attempting to generate server feature list. Ensure your user account has read permission to the property files in the server installation directory.");
            return;
        }
        // TODO: get user specified features that have not yet been installed in the
        // original case they appear in a server config xml document.
        // getSpecifiedFeatures may not return the features in the correct case
        // Set<String> featuresToInstall = getSpecifiedFeatures(null); 

        // get existing installed server features
        InstallFeatureUtil util = getInstallFeatureUtil(new HashSet<String>(), null);
        util.setLowerCaseFeatures(false);

        final boolean optimize = (classFiles == null || classFiles.isEmpty()) ? true : false;
        Set<String> generatedFiles = new HashSet<String>();
        generatedFiles.add(GENERATED_FEATURES_FILE_NAME);  

        // if optimizing, ignore generated files when passing in existing features to binary scanner
        Set<String> existingFeatures = util.getServerFeatures(serverDirectory, libertyDirPropertyFiles, optimize ? generatedFiles : null);
        if (existingFeatures == null) {
            existingFeatures = new HashSet<String>();
        }
        util.setLowerCaseFeatures(true);
        log.debug("Existing features:" + existingFeatures);

        Set<String> scannedFeatureList = null;
        try {
            Set<String> directories = getClassesDirectories();
            String[] binaryInputs = getBinaryInputs(classFiles, directories);
            scannedFeatureList = runBinaryScanner(existingFeatures, binaryInputs);
        } catch (InvocationTargetException x) {
            // TODO Figure out what to do when there is a problem not caught in runBinaryScanner()
            log.error("Exception:"+x.getClass().getName());
            Object o = x.getCause();
            if (o != null) {
                log.warn("Caused by exception:"+x.getCause().getClass().getName());
                log.warn("Caused by exception message:"+x.getCause().getMessage());
            }
            log.error(x.getMessage());
            return;
        } catch (NoRecommendationException noRecommendation) {
            log.error(String.format(BINARY_SCANNER_CONFLICT_MESSAGE3, noRecommendation.getConflicts()));
            return;
        } catch (RecommendationSetException showRecommendation) {
            if (showRecommendation.isExistingFeaturesConflict()) {
                log.error(String.format(BINARY_SCANNER_CONFLICT_MESSAGE2, showRecommendation.getConflicts(), showRecommendation.getSuggestions()));
            } else {
                log.error(String.format(BINARY_SCANNER_CONFLICT_MESSAGE1, showRecommendation.getConflicts(), showRecommendation.getSuggestions()));
            }
            return;
        }

        Set<String> missingLibertyFeatures = new HashSet<String>();
        if (scannedFeatureList != null) {
            missingLibertyFeatures.addAll(scannedFeatureList);

            util.setLowerCaseFeatures(false);
            // get set of user defined features so they can be omitted from the generated file that will be written
            Set<String> userDefinedFeatures = optimize ? existingFeatures : util.getServerFeatures(serverDirectory, libertyDirPropertyFiles, generatedFiles);
            log.debug("User defined features:" + userDefinedFeatures);
            util.setLowerCaseFeatures(true);
            if (userDefinedFeatures != null) {
                missingLibertyFeatures.removeAll(userDefinedFeatures);
            }
        }
        log.debug("Features detected by binary scanner which are not in server.xml" + missingLibertyFeatures);

        File newServerXmlSrc = new File(configDirectory, GENERATED_FEATURES_FILE_PATH);
        File serverXml = findConfigFile("server.xml", serverXmlFile);
        ServerConfigXmlDocument doc = getServerXmlDocFromConfig(serverXml);
        log.debug("Xml document we'll try to update after generate features doc="+doc+" file="+serverXml);

        if (missingLibertyFeatures.size() > 0) {
            // Create special XML file to contain generated features.
            try {
                ServerConfigXmlDocument configDocument = ServerConfigXmlDocument.newInstance();
                configDocument.createComment(HEADER);
                for (String missing : missingLibertyFeatures) {
                    log.debug(String.format("Adding missing feature %s to %s.", missing, GENERATED_FEATURES_FILE_PATH));
                    configDocument.createFeature(missing);
                }
                configDocument.writeXMLDocument(newServerXmlSrc);
                log.debug("Created file "+newServerXmlSrc);
                // Add a reference to this new file in existing server.xml.
                addGenerationCommentToConfig(doc, serverXml);

                log.info("Generated the following features: " + missingLibertyFeatures);
            } catch(ParserConfigurationException | TransformerException | IOException e) {
                log.debug("Exception creating the server features file", e);
                log.error("Error attempting to create the server feature file. Ensure your id has write permission to the server installation directory.");
                return;
            }
        } else {
            log.debug("No additional features were generated.");
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
            throw new PluginExecutionException("Could not retrieve the binary scanner jar. Ensure you have a connection to Maven Central or another repository that contains the jar configured in your pom.xml", e);
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

    private Set<String> runBinaryScanner(Set<String> currentFeatureSet, String[] binaryInputs)
            throws PluginExecutionException, InvocationTargetException, NoRecommendationException, RecommendationSetException {
        Set<String> featureList = null;
        if (binaryScanner != null && binaryScanner.exists()) {
            try {
                ClassLoader cl = getScannerClassLoader();
                Class driveScan = cl.loadClass("com.ibm.ws.report.binary.cmdline.DriveScan");
                // args: String[], String, String, List, java.util.Locale
                java.lang.reflect.Method driveScanMavenFeatureList = driveScan.getMethod("driveScanMavenFeatureList", String[].class, String.class, String.class, List.class, java.util.Locale.class);
                if (driveScanMavenFeatureList == null) {
                    log.debug("Error finding binary scanner method using reflection");
                    return null;
                }

                String eeVersion = getEEVersion(project); 
                String mpVersion = getMPVersion(project);
                List<String> currentFeatures;
                if (currentFeatureSet == null) { // signifies we are calling the binary scanner for a sample list of features
                    currentFeatures = new ArrayList<String>();
                } else {
                    currentFeatures = new ArrayList<String>(currentFeatureSet);
                }
                log.debug("The following messages are from the application binary scanner used to generate Liberty features");
                featureList = (Set<String>) driveScanMavenFeatureList.invoke(null, binaryInputs, eeVersion, mpVersion, currentFeatures, java.util.Locale.getDefault());
                log.debug("End of messages from application binary scanner. Features recommended :");
                for (String s : featureList) {log.debug(s);};
            } catch (InvocationTargetException ite) {
                // This is the exception from the JVM that indicates there was an exception in the method we
                // called through reflection. We must extract the actual exception from the 'cause' field.
                // A RuntimeException means the currentFeatureSet contains conflicts.
                // A FeatureConflictException means the binary files scanned conflict with each other or with
                // the currentFeatureSet parameter.
                Throwable scannerException = ite.getCause();
                if (scannerException instanceof RuntimeException) {
                    // The list of features from the app is passed in but it contains conflicts 
                    String problemMessage = scannerException.getMessage();
                    if (problemMessage == null || problemMessage.isEmpty()) {
                        log.debug("RuntimeException from binary scanner without descriptive message", scannerException);
                        log.error("Error scanning the application for Liberty features.");
                    } else {
                        Set<String> conflicts = parseScannerMessage(problemMessage);
                        Set<String> sampleFeatureList = null;
                        try {
                            sampleFeatureList = runBinaryScanner(null, getBinaryInputs(null, getClassesDirectories()));
                        } catch (InvocationTargetException retryException) {
                            // binary scanner should not return a RuntimeException since there is no list of app features passed in
                            sampleFeatureList = getNoSampleFeatureList();
                        }
                        throw new RecommendationSetException(true, conflicts, sampleFeatureList);
                    }
                } else if (scannerException.getClass().getName().endsWith("FeatureConflictException")) {
                    // The scanned files conflict with each other or with current features
                    Set<String> conflicts = getConflicts(scannerException);
                    Set<String> sampleFeatureList = null;
                    if (currentFeatureSet != null) {
                        try {
                            sampleFeatureList = runBinaryScanner(null, getBinaryInputs(null, getClassesDirectories()));
                        } catch (InvocationTargetException retryException) {
                            Throwable scannerSecondException = retryException.getCause();
                            if (scannerSecondException.getClass().getName().endsWith("FeatureConflictException")) {
                                // Even after removing the server.xml feature list there are still conflicts in the binaries
                                throw new NoRecommendationException(conflicts);
                            } else {
                                log.debug("Unexpected failure on retry call to binary scanner", scannerSecondException);
                                log.debug("Passed directories to binary scanner:"+getClassesDirectories());
                                sampleFeatureList = getNoSampleFeatureList();
                            }
                        }
                        throw new RecommendationSetException(false, conflicts, sampleFeatureList);
                    } else {
                        throw ite;
                    }
                }
            } catch (MalformedURLException|ClassNotFoundException|NoSuchMethodException|IllegalAccessException x){
                // TODO Figure out what to do when there is a problem scanning the features
                log.error("Exception:"+x.getClass().getName());
                Object o = x.getCause();
                if (o != null) {
                    log.warn("Caused by exception:"+x.getCause().getClass().getName());
                    log.warn("Caused by exception message:"+x.getCause().getMessage());
                }
                log.error(x.getMessage());
            }
        } else {
            if (binaryScanner == null) {
                throw new PluginExecutionException("The binary scanner jar location is not defined.");
            } else {
                throw new PluginExecutionException("Could not find the binary scanner jar at " + binaryScanner.getAbsolutePath());
            }
        }
        return featureList;
    }

    private Set<String> getNoSampleFeatureList() {
        Set<String> sampleFeatureList;
        sampleFeatureList = new HashSet<String>();
        sampleFeatureList.add(BINARY_SCANNER_CONFLICT_MESSAGE4);
        return sampleFeatureList;
    }

    private ClassLoader getScannerClassLoader() throws MalformedURLException {
        if (binaryScannerClassLoader == null) {
            ClassLoader cl = this.getClass().getClassLoader();
            binaryScannerClassLoader = new URLClassLoader(new URL[] { binaryScanner.toURI().toURL() }, cl);
        }
        return binaryScannerClassLoader;
    }

    private String[] getBinaryInputs(List<String> classFiles, Set<String> classDirectories) throws PluginExecutionException {
        Collection<String> resultSet;
        if (classFiles != null && !classFiles.isEmpty()) {
            resultSet = classFiles;
        } else {
            if (classDirectories == null || classDirectories.isEmpty()) {
                throw new PluginExecutionException("Error collecting list of directories to send to binary scanner, list is null or empty.");
            }
            resultSet = classDirectories;
        }

        for (String s : resultSet) {
            log.debug("Binary scanner input: " + s);
        }

        String[] result = resultSet.toArray(new String[resultSet.size()]);
        return result;
    }

    // Return a list containing the classes directory of the current project and any upstream module projects
    private Set<String> getClassesDirectories() {
        Set<String> dirs = new HashSet<String>();
        String classesDirName = null;
        // First check the Java build output directory (target/classes) for the current project
        classesDirName = getClassesDirectory(project.getBuild().getOutputDirectory());
        if (classesDirName != null) {
            dirs.add(classesDirName);
        }

        // Use graph to find upstream projects and look for classes directories. Some projects have no Java.
        ProjectDependencyGraph graph = session.getProjectDependencyGraph();
        List<MavenProject> upstreamProjects = graph.getUpstreamProjects(project, true);
        log.debug("For binary scanner gathering Java build output directories for upstream projects, size=" + upstreamProjects.size());
        for (MavenProject upstreamProject : upstreamProjects) {
            classesDirName = getClassesDirectory(upstreamProject.getBuild().getOutputDirectory());
            if (classesDirName != null) {
                dirs.add(classesDirName);
            }
        }
        for (String s : dirs) {log.debug("Found dir:"+s);};
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
            if (d.getGroupId().equals("io.openliberty.features")) {
                String id = d.getArtifactId();
                if (id.equals("javaee-7.0")) {
                    return "ee7";
                } else if (id.equals("javaee-8.0")) {
                    return "ee8";
                } else if (id.equals("javaeeClient-7.0")) {
                    return "ee7";
                } else if (id.equals("javaeeClient-8.0")) {
                    return "ee8";
                } else if (id.equals("jakartaee-8.0")) {
                    return "ee8";
                }
            } else if (d.getGroupId().equals("jakarta.platform") &&
                    d.getArtifactId().equals("jakarta.jakartaee-api") &&
                    d.getVersion().equals("8.0.0")) {
                return "ee8";
            }
        }
        return null;
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
                } else if (version.startsWith("3")) {
                    return "mp3";
                }
                return "mp4"; // add support for future versions of MicroProfile here
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
        } else if (mpVersion == 3) {
            return "mp3";
        }
        return "mp4";
    }

    public static int getMPVersion(String shortName) {
        final int MP_VERSIONS = 4; // number of version columns in table
        String[][] mpComponents = {
            // Name, MP1 version, MP2 version, MP3 version
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

    @SuppressWarnings("unchecked")
    private Set<String> getConflicts(Throwable scannerResponse) {
        try {
            ClassLoader cl = getScannerClassLoader();
            @SuppressWarnings("rawtypes")
            Class featureConflictException = cl.loadClass("com.ibm.ws.report.exceptions.FeatureConflictException");
            java.lang.reflect.Method conflictFeatureList = featureConflictException.getMethod("getFeatures");
            if (conflictFeatureList == null) {
                log.debug("Error finding FeatureConflictException method getFeatures using reflection");
                return null;
            }
            return (Set<String>) conflictFeatureList.invoke(scannerResponse);
        } catch (ClassNotFoundException | MalformedURLException | NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException | InvocationTargetException x) {
            //TODO maybe nothing
            log.error("Exception:"+x.getClass().getName());
            log.error("Message:"+x.getMessage());
            Object o = x.getCause();
            if (o != null) {
                log.warn("Caused by exception:"+x.getCause().getClass().getName());
                log.warn("Caused by exception message:"+x.getCause().getMessage());
            }
        }
        return null;
    }

    private Set<String> parseScannerMessage(String messages) {
        Set<String> features = new HashSet<String>();
        String[] messageArray = messages.split("\n");
        for (String message : messageArray) {
            if (message.startsWith("CWMIG12083")) {
                String [] messageParts = message.split(" ");
                if (messageParts.length > 4) { // should be 20
                    features.add(messageParts[2]);
                    features.add(messageParts[messageParts.length-2]);
                }
            }
        }
        return features;
    }

    // A class to pass the list of conflicts back to the caller.
    private class NoRecommendationException extends Exception {
        private static final long serialVersionUID = 1L;
        Set<String> conflicts;
        NoRecommendationException(Set<String> conflictSet) {
            conflicts = conflictSet;
        }
        public Set<String> getConflicts() {
            return conflicts;
        }
    }

    // A class that encapsulates a list of conflicting features, a suggested list of replacements
    // and a flag that indicates whether the conflicts were found in the features existing in the
    // app's server config or if the conflicts exist in the binary files we examined.
    private class RecommendationSetException extends Exception {
        private static final long serialVersionUID = 1L;
        boolean existingFeaturesConflict;
        Set<String> conflicts;
        Set<String> suggestions;
        RecommendationSetException(boolean existing, Set<String> conflictSet, Set<String> suggestionSet) {
            existingFeaturesConflict = existing;
            conflicts = conflictSet;
            suggestions = suggestionSet;
        }
        public boolean isExistingFeaturesConflict() {
            return existingFeaturesConflict;
        }
        public Set<String> getConflicts() {
            return conflicts;
        }
        public Set<String> getSuggestions() {
            return suggestions;
        }
    }
}
