/**
 * Copyright 2016 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package io.fabric8.maven.generator.springboot;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.google.common.base.Strings;
import io.fabric8.maven.core.util.Configs;
import io.fabric8.maven.core.util.MavenUtil;
import io.fabric8.maven.core.util.SpringBootConfigurationHelper;
import io.fabric8.maven.core.util.SpringBootUtil;
import io.fabric8.maven.docker.config.ImageConfiguration;
import io.fabric8.maven.docker.util.Logger;
import io.fabric8.maven.generator.api.GeneratorContext;
import io.fabric8.maven.generator.api.GeneratorMode;
import io.fabric8.maven.core.util.FatJarDetector;
import io.fabric8.maven.generator.javaexec.JavaExecGenerator;
import org.apache.commons.io.FileUtils;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

import static io.fabric8.maven.core.util.SpringBootConfigurationHelper.DEV_TOOLS_REMOTE_SECRET;
import static io.fabric8.maven.generator.springboot.SpringBootGenerator.Config.color;

/**
 * @author roland
 * @since 15/05/16
 */
public class SpringBootGenerator extends JavaExecGenerator {

    private static final String DEFAULT_SERVER_PORT = "8080";

    public enum Config implements Configs.Key {
        color {{ d = ""; }};

        public String def() { return d; } protected String d;
    }

    public SpringBootGenerator(GeneratorContext context) {
        super(context, "spring-boot");
    }

    @Override
    public boolean isApplicable(List<ImageConfiguration> configs) {
        return shouldAddImageConfiguration(configs)
               && MavenUtil.hasPluginOfAnyGroupId(getProject(), SpringBootConfigurationHelper.SPRING_BOOT_MAVEN_PLUGIN_ARTIFACT_ID);
    }

    @Override
    public List<ImageConfiguration> customize(List<ImageConfiguration> configs, boolean isPrePackagePhase) throws MojoExecutionException {
        if (getContext().getGeneratorMode() == GeneratorMode.WATCH) {
            ensureSpringDevToolSecretToken();
            if (!isPrePackagePhase ) {
                addDevToolsFilesToFatJar(configs);
            }
        }
        return super.customize(configs, isPrePackagePhase);
    }

    @Override
    protected Map<String, String> getEnv(boolean prePackagePhase) throws MojoExecutionException {
        Map<String, String> res = super.getEnv(prePackagePhase);
        if (getContext().getGeneratorMode() == GeneratorMode.WATCH) {
            // adding dev tools token to env variables to prevent override during recompile
            String secret = SpringBootUtil.getSpringBootApplicationProperties(
                    SpringBootUtil.getSpringBootActiveProfile(getProject()),
                    MavenUtil.getCompileClassLoader(getProject())).getProperty(SpringBootConfigurationHelper.DEV_TOOLS_REMOTE_SECRET);
            if (secret != null) {
                res.put(SpringBootConfigurationHelper.DEV_TOOLS_REMOTE_SECRET_ENV, secret);
            }
        }
        return res;
    }

    @Override
    protected List<String> getExtraJavaOptions() {
        List<String> opts = super.getExtraJavaOptions();
        final String configuredColor = getConfig(color);
        if (configuredColor != null && !configuredColor.isEmpty()) {
            opts.add("-Dspring.output.ansi.enabled=" + configuredColor);
        }
        return opts;
    }

    @Override
    protected boolean isFatJar() throws MojoExecutionException {
        if (!hasMainClass() && isSpringBootRepackage()) {
            return true;
        }
        return super.isFatJar();
    }

    @Override
    protected List<String> extractPorts() {
        List<String> answer = new ArrayList<>();
        Properties properties = SpringBootUtil.getSpringBootApplicationProperties(
                SpringBootUtil.getSpringBootActiveProfile(getProject()),
                MavenUtil.getCompileClassLoader(this.getProject()));
        SpringBootConfigurationHelper propertyHelper = new SpringBootConfigurationHelper(SpringBootUtil.getSpringBootVersion(getProject()));
        String port = properties.getProperty(propertyHelper.getServerPortPropertyKey(), DEFAULT_SERVER_PORT);
        addPortIfValid(answer, getConfig(JavaExecGenerator.Config.webPort, port));
        addPortIfValid(answer, getConfig(JavaExecGenerator.Config.jolokiaPort));
        addPortIfValid(answer, getConfig(JavaExecGenerator.Config.prometheusPort));
        return answer;
    }

    // =============================================================================

    private void ensureSpringDevToolSecretToken() throws MojoExecutionException {
        Properties properties = SpringBootUtil.getSpringBootApplicationProperties(
                SpringBootUtil.getSpringBootActiveProfile(getProject()),
                MavenUtil.getCompileClassLoader(getProject()));
        String remoteSecret = properties.getProperty(DEV_TOOLS_REMOTE_SECRET);
        if (Strings.isNullOrEmpty(remoteSecret)) {
            addSecretTokenToApplicationProperties();
            throw new MojoExecutionException("No spring.devtools.remote.secret found in application.properties. Plugin has added it, please re-run goals");
        }
    }

    private void addDevToolsFilesToFatJar(List<ImageConfiguration> configs) throws MojoExecutionException {
        if (isFatJar()) {
            File target = getFatJarFile();
            try {
                File devToolsFile = getSpringBootDevToolsJar();
                File applicationPropertiesFile = new File(getProject().getBasedir(), "target/classes/application.properties");
                copyFilesToFatJar(Collections.singletonList(devToolsFile), Collections.singletonList(applicationPropertiesFile), target);
            } catch (Exception e) {
                throw new MojoExecutionException("Failed to add devtools files to fat jar " + target + ". " + e, e);
            }
        }
    }

    private File getFatJarFile() throws MojoExecutionException {
        FatJarDetector.Result fatJarDetectResult = detectFatJar();
        if (fatJarDetectResult == null) {
            throw new MojoExecutionException("No fat jar built yet. Please ensure that the 'package' phase has run");
        }
        return fatJarDetectResult.getArchiveFile();
    }

    private void copyFilesToFatJar(List<File> libs, List<File> classes, File target) throws IOException {
        File tmpZip = File.createTempFile(target.getName(), null);
        tmpZip.delete();

        // Using Apache commons rename, because renameTo has issues across file systems
        FileUtils.moveFile(target, tmpZip);

        byte[] buffer = new byte[8192];
        ZipInputStream zin = new ZipInputStream(new FileInputStream(tmpZip));
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(target));
        for (ZipEntry ze = zin.getNextEntry(); ze != null; ze = zin.getNextEntry()) {
            if (matchesFatJarEntry(libs, ze.getName(), true) || matchesFatJarEntry(classes, ze.getName(), false)) {
                continue;
            }
            out.putNextEntry(ze);
            for(int read = zin.read(buffer); read > -1; read = zin.read(buffer)){
                out.write(buffer, 0, read);
            }
            out.closeEntry();
        }

        for (File lib : libs) {
            try (InputStream in = new FileInputStream(lib)) {
                out.putNextEntry(createZipEntry(lib, getFatJarFullPath(lib, true)));
                for (int read = in.read(buffer); read > -1; read = in.read(buffer)) {
                    out.write(buffer, 0, read);
                }
                out.closeEntry();
            }
        }

        for (File cls : classes) {
            try (InputStream in = new FileInputStream(cls)) {
                out.putNextEntry(createZipEntry(cls, getFatJarFullPath(cls, false)));
                for (int read = in.read(buffer); read > -1; read = in.read(buffer)) {
                    out.write(buffer, 0, read);
                }
                out.closeEntry();
            }
        }

        out.close();
        tmpZip.delete();
    }

    private boolean matchesFatJarEntry(List<File> fatJarEntries, String path, boolean lib) {
        for (File e : fatJarEntries) {
            String fullPath = getFatJarFullPath(e, lib);
            if (fullPath.equals(path)) {
                return true;
            }
        }
        return false;
    }

    private String getFatJarFullPath(File file, boolean lib) {
        if (lib) {
            return "BOOT-INF/lib/" + file.getName();
        }
        return "BOOT-INF/classes/" + file.getName();
    }

    private ZipEntry createZipEntry(File file, String fullPath) throws IOException {
        ZipEntry entry = new ZipEntry(fullPath);

        byte[] buffer = new byte[8192];
        int bytesRead = -1;
        try (InputStream is = new FileInputStream(file)) {
            CRC32 crc = new CRC32();
            int size = 0;
            while ((bytesRead = is.read(buffer)) != -1) {
                crc.update(buffer, 0, bytesRead);
                size += bytesRead;
            }
            entry.setSize(size);
            entry.setCompressedSize(size);
            entry.setCrc(crc.getValue());
            entry.setMethod(ZipEntry.STORED);
            return entry;
        }
    }

    private void addSecretTokenToApplicationProperties() throws MojoExecutionException {
        String newToken = UUID.randomUUID().toString();
        log.verbose(Logger.LogVerboseCategory.BUILD, "Generating the spring devtools token in property: " + DEV_TOOLS_REMOTE_SECRET);

        // We always add to application.properties, even when an application.yml exists, since both
        // files are evaluated by Spring Boot.
        appendSecretTokenToFile("target/classes/application.properties", newToken);
        appendSecretTokenToFile("src/main/resources/application.properties", newToken);
    }

    private void appendSecretTokenToFile(String path, String token) throws MojoExecutionException {
        File file = new File(getProject().getBasedir(), path);
        file.getParentFile().mkdirs();
        String text = String.format("%s" +
                        "# Remote secret added by fabric8-maven-plugin\n" +
                        "%s=%s\n",
                file.exists() ? "\n" : "", DEV_TOOLS_REMOTE_SECRET, token);

        try (FileWriter writer = new FileWriter(file, true)) {
            writer.append(text);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to append to file: " + file + ". " + e, e);
        }
    }

    private boolean isSpringBootRepackage() {
        MavenProject project = getProject();
        Plugin plugin = MavenUtil.getPluginOfAnyGroupId(project, SpringBootConfigurationHelper.SPRING_BOOT_MAVEN_PLUGIN_ARTIFACT_ID);
        if (plugin != null) {
            Map<String, PluginExecution> executionsAsMap = plugin.getExecutionsAsMap();
            if (executionsAsMap != null) {
                for (PluginExecution execution : executionsAsMap.values()) {
                    List<String> goals = execution.getGoals();
                    if (goals.contains("repackage")) {
                        log.verbose(Logger.LogVerboseCategory.BUILD, "Using fat jar packaging as the spring boot plugin is using `repackage` goal execution");
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private File getSpringBootDevToolsJar() throws IOException {
        String version = SpringBootUtil.getSpringBootDevToolsVersion(getProject()).orElseThrow(() -> new IllegalStateException("Unable to find the spring-boot version"));
        return getContext().getArtifactResolver().resolveArtifact(SpringBootConfigurationHelper.SPRING_BOOT_GROUP_ID, SpringBootConfigurationHelper.SPRING_BOOT_DEVTOOLS_ARTIFACT_ID, version, "jar");
    }

}
