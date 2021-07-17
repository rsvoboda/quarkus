package io.quarkus.gradle.tasks;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.inject.Inject;

import org.apache.tools.ant.types.Commandline;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.options.Option;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.jvm.toolchain.JavaToolchainSpec;
import org.gradle.util.GradleVersion;

import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.bootstrap.model.AppArtifact;
import io.quarkus.bootstrap.model.AppArtifactKey;
import io.quarkus.bootstrap.model.AppDependency;
import io.quarkus.bootstrap.model.AppModel;
import io.quarkus.bootstrap.model.PathsCollection;
import io.quarkus.bootstrap.resolver.AppModelResolver;
import io.quarkus.bootstrap.resolver.AppModelResolverException;
import io.quarkus.deployment.dev.DevModeContext;
import io.quarkus.deployment.dev.QuarkusDevModeLauncher;
import io.quarkus.gradle.QuarkusPlugin;
import io.quarkus.runtime.LaunchMode;

public class QuarkusDev extends QuarkusTask {

    public static final String IO_QUARKUS_DEVMODE_ARGS = "io.quarkus.devmode-args";
    private Set<File> filesIncludedInClasspath = new HashSet<>();

    private File buildDir;

    private String sourceDir;

    private String workingDir;

    private List<String> jvmArgs;

    private boolean preventnoverify = false;

    private List<String> args = new LinkedList<String>();

    private List<String> compilerArgs = new LinkedList<>();

    @Inject
    public QuarkusDev() {
        super("Development mode: enables hot deployment with background compilation");
    }

    public QuarkusDev(String name) {
        super(name);
    }

    @InputDirectory
    @Optional
    public File getBuildDir() {
        if (buildDir == null) {
            buildDir = getProject().getBuildDir();
        }
        return buildDir;
    }

    public void setBuildDir(File buildDir) {
        this.buildDir = buildDir;
    }

    @Optional
    @InputDirectory
    public File getSourceDir() {
        if (sourceDir == null) {
            return extension().sourceDir();
        } else {
            return new File(sourceDir);
        }
    }

    @Option(description = "Set source directory", option = "source-dir")
    public void setSourceDir(String sourceDir) {
        this.sourceDir = sourceDir;
    }

    @Input
    // @InputDirectory this breaks kotlin projects, the working dir at this stage will be evaluated to 'classes/java/main' instead of 'classes/kotlin/main'
    public String getWorkingDir() {
        if (workingDir == null) {
            return extension().workingDir().toString();
        } else {
            return workingDir;
        }
    }

    @Option(description = "Set working directory", option = "working-dir")
    public void setWorkingDir(String workingDir) {
        this.workingDir = workingDir;
    }

    @Optional
    @Input
    public List<String> getJvmArgs() {
        return jvmArgs;
    }

    @Option(description = "Set JVM arguments", option = "jvm-args")
    public void setJvmArgs(List<String> jvmArgs) {
        this.jvmArgs = jvmArgs;
    }

    @Optional
    @Input
    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args;
    }

    @Option(description = "Set application arguments", option = "quarkus-args")
    public void setArgsString(String argsString) {
        this.setArgs(Arrays.asList(Commandline.translateCommandline(argsString)));
    }

    @Input
    public boolean isPreventnoverify() {
        return preventnoverify;
    }

    @Option(description = "value is intended to be set to true when some generated bytecode is" +
            " erroneous causing the JVM to crash when the verify:none option is set " +
            "(which is on by default)", option = "prevent-noverify")
    public void setPreventnoverify(boolean preventnoverify) {
        this.preventnoverify = preventnoverify;
    }

    @Optional
    @Input
    public List<String> getCompilerArgs() {
        return compilerArgs;
    }

    @Option(description = "Additional parameters to pass to javac when recompiling changed source files", option = "compiler-args")
    public void setCompilerArgs(List<String> compilerArgs) {
        this.compilerArgs = compilerArgs;
    }

    @TaskAction
    public void startDev() {
        if (!getSourceDir().isDirectory()) {
            throw new GradleException("The `src/main/java` directory is required, please create it.");
        }

        if (!extension().outputDirectory().isDirectory()) {
            throw new GradleException("The project has no output yet, " +
                    "this should not happen as build should have been executed first. " +
                    "Does the project have any source files?");
        }

        try {
            QuarkusDevModeLauncher runner = newLauncher();
            String outputFile = System.getProperty(IO_QUARKUS_DEVMODE_ARGS);
            if (outputFile == null) {
                getProject().exec(action -> {
                    action.commandLine(runner.args()).workingDir(getWorkingDir());
                    action.setStandardInput(System.in)
                            .setErrorOutput(System.out)
                            .setStandardOutput(System.out);
                });
            } else {
                try (BufferedWriter is = Files.newBufferedWriter(Paths.get(outputFile))) {
                    for (String i : runner.args()) {
                        is.write(i);
                        is.newLine();
                    }
                }
            }

        } catch (Exception e) {
            throw new GradleException("Failed to run", e);
        }
    }

    private QuarkusDevModeLauncher newLauncher() throws Exception {
        final Project project = getProject();

        String java = null;

        if (GradleVersion.current().compareTo(GradleVersion.version("6.7")) >= 0) {
            JavaToolchainService toolChainService = project.getExtensions().getByType(JavaToolchainService.class);
            JavaToolchainSpec toolchainSpec = project.getExtensions().getByType(JavaPluginExtension.class).getToolchain();
            Provider<JavaLauncher> javaLauncher = toolChainService.launcherFor(toolchainSpec);
            if (javaLauncher.isPresent()) {
                java = javaLauncher.get().getExecutablePath().getAsFile().getAbsolutePath();
            }
        }
        GradleDevModeLauncher.Builder builder = GradleDevModeLauncher.builder(getLogger(), java)
                .preventnoverify(isPreventnoverify())
                .projectDir(project.getProjectDir())
                .buildDir(getBuildDir())
                .outputDir(getBuildDir())
                .debug(System.getProperty("debug"))
                .debugHost(System.getProperty("debugHost"))
                .debugPort(System.getProperty("debugPort"))
                .suspend(System.getProperty("suspend"));
        if (System.getProperty(IO_QUARKUS_DEVMODE_ARGS) == null) {
            builder.jvmArgs("-Dquarkus.test.basic-console=true")
                    .jvmArgs("-Dio.quarkus.force-color-support=true");
        }

        if (getJvmArgs() != null) {
            builder.jvmArgs(getJvmArgs());
        }

        for (Map.Entry<String, ?> e : project.getProperties().entrySet()) {
            if (e.getValue() instanceof String) {
                builder.buildSystemProperty(e.getKey(), e.getValue().toString());
            }
        }

        //  this is a minor hack to allow ApplicationConfig to be populated with defaults
        builder.applicationName(project.getName());
        if (project.getVersion() != null) {
            builder.applicationVersion(project.getVersion().toString());

        }

        builder.sourceEncoding(getSourceEncoding());

        final AppModel appModel;
        final AppModelResolver modelResolver = extension().getAppModelResolver(LaunchMode.DEVELOPMENT);
        try {
            final AppArtifact appArtifact = extension().getAppArtifact();
            appArtifact.setPaths(QuarkusGradleUtils.getOutputPaths(project));
            appModel = modelResolver.resolveModel(appArtifact);
        } catch (AppModelResolverException e) {
            throw new GradleException("Failed to resolve application model " + extension().getAppArtifact() + " dependencies",
                    e);
        }

        final Set<AppArtifactKey> projectDependencies = new HashSet<>();
        addSelfWithLocalDeps(project, builder, new HashSet<>(), projectDependencies, true);

        for (AppDependency appDependency : appModel.getFullDeploymentDeps()) {
            final AppArtifact appArtifact = appDependency.getArtifact();
            //we only use the launcher for launching from the IDE, we need to exclude it
            if (appArtifact.getGroupId().equals("io.quarkus") && appArtifact.getGroupId().equals("quarkus-ide-launcher")) {
                continue;
            }
            if (!projectDependencies.contains(new AppArtifactKey(appArtifact.getGroupId(), appArtifact.getArtifactId()))) {
                appArtifact.getPaths().forEach(p -> {
                    if (Files.exists(p)) {
                        if (appArtifact.getGroupId().equals("io.quarkus")
                                && appArtifact.getArtifactId().equals("quarkus-class-change-agent")) {
                            builder.jvmArgs("-javaagent:" + p.toFile().getAbsolutePath());
                        } else {
                            addToClassPaths(builder, p.toFile());
                        }
                    }

                });
            }
        }

        //we also want to add the Gradle plugin to the class path
        //this allows us to just directly use classes, without messing around copying them
        //to the runner jar
        addGradlePluginDeps(builder);

        JavaPluginConvention javaPluginConvention = project.getConvention().findPlugin(JavaPluginConvention.class);
        if (javaPluginConvention != null) {
            builder.sourceJavaVersion(javaPluginConvention.getSourceCompatibility().toString());
            builder.targetJavaVersion(javaPluginConvention.getTargetCompatibility().toString());
        }

        if (getCompilerArgs().isEmpty()) {
            getJavaCompileTask()
                    .map(compileTask -> compileTask.getOptions().getCompilerArgs())
                    .ifPresent(builder::compilerOptions);
        } else {
            builder.compilerOptions(getCompilerArgs());
        }

        modifyDevModeContext(builder);

        final Path serializedModel = QuarkusGradleUtils.serializeAppModel(appModel, this, false);
        serializedModel.toFile().deleteOnExit();
        builder.jvmArgs("-D" + BootstrapConstants.SERIALIZED_APP_MODEL + "=" + serializedModel.toAbsolutePath());

        if (builder.isTestsPresent()) {
            final AppModel testAppModel;
            final AppModelResolver testModelResolver = extension().getAppModelResolver(LaunchMode.TEST);
            try {
                final AppArtifact appArtifact = extension().getAppArtifact();
                appArtifact.setPaths(QuarkusGradleUtils.getOutputPaths(project));
                testAppModel = testModelResolver.resolveModel(appArtifact);
            } catch (AppModelResolverException e) {
                throw new GradleException(
                        "Failed to resolve application model " + extension().getAppArtifact() + " dependencies",
                        e);
            }
            final Path serializedTestModel = QuarkusGradleUtils.serializeAppModel(testAppModel, this, true);
            serializedTestModel.toFile().deleteOnExit();
            builder.jvmArgs("-D" + BootstrapConstants.SERIALIZED_TEST_APP_MODEL + "=" + serializedTestModel.toAbsolutePath());
        }
        extension().outputDirectory().mkdirs();

        if (!args.isEmpty()) {
            builder.applicationArgs(String.join(" ", args));
        }

        return builder.build();
    }

    protected void modifyDevModeContext(GradleDevModeLauncher.Builder builder) {

    }

    private void addSelfWithLocalDeps(Project project, GradleDevModeLauncher.Builder builder, Set<String> visited,
            Set<AppArtifactKey> addedDeps, boolean root) {
        if (!visited.add(project.getPath())) {
            return;
        }
        final Configuration compileCp = project.getConfigurations().findByName(QuarkusPlugin.DEV_MODE_CONFIGURATION_NAME);
        if (compileCp != null) {
            compileCp.getIncoming().getDependencies().forEach(d -> {
                if (d instanceof ProjectDependency) {
                    addSelfWithLocalDeps(((ProjectDependency) d).getDependencyProject(), builder, visited, addedDeps, false);
                }
            });
        }

        addLocalProject(project, builder, addedDeps, root);
    }

    private void addLocalProject(Project project, GradleDevModeLauncher.Builder builder, Set<AppArtifactKey> addeDeps,
            boolean root) {
        final AppArtifactKey key = new AppArtifactKey(project.getGroup().toString(), project.getName());
        if (addeDeps.contains(key)) {
            return;
        }
        final JavaPluginConvention javaConvention = project.getConvention().findPlugin(JavaPluginConvention.class);
        if (javaConvention == null) {
            return;
        }

        SourceSetContainer sourceSets = javaConvention.getSourceSets();
        SourceSet mainSourceSet = sourceSets.findByName(SourceSet.MAIN_SOURCE_SET_NAME);
        if (mainSourceSet == null) {
            return;
        }
        Set<Path> sourcePaths = new LinkedHashSet<>();
        Set<Path> sourceParentPaths = new LinkedHashSet<>();

        for (File sourceDir : mainSourceSet.getAllJava().getSrcDirs()) {
            if (sourceDir.exists()) {
                sourcePaths.add(sourceDir.toPath().toAbsolutePath());
                sourceParentPaths.add(sourceDir.toPath().getParent().toAbsolutePath());
            }
        }
        final Set<Path> resourcesSrcDirs = new LinkedHashSet<>();
        for (File resourcesSrcDir : mainSourceSet.getResources().getSourceDirectories().getFiles()) {
            resourcesSrcDirs.add(resourcesSrcDir.toPath().toAbsolutePath());
        }
        // resourcesSrcDir may exist but if it's empty the resources output dir won't be created
        final File resourcesOutputDir = mainSourceSet.getOutput().getResourcesDir();

        if (sourcePaths.isEmpty() && !resourcesOutputDir.exists()) {
            return;
        }

        String classesDir = QuarkusGradleUtils.getClassesDir(mainSourceSet, project.getBuildDir(), false);
        if (classesDir == null) {
            return;
        } else {
            File classesDirFile = new File(classesDir);
            if (!classesDirFile.exists()) {
                return;
            }
        }

        final String resourcesOutputPath;
        if (resourcesOutputDir.exists()) {
            resourcesOutputPath = resourcesOutputDir.getAbsolutePath();
            if (!Files.exists(Paths.get(classesDir))) {
                // currently classesDir can't be null and is expected to exist
                classesDir = resourcesOutputPath;
            }
        } else {
            // currently resources dir should exist
            resourcesOutputPath = classesDir;
        }

        DevModeContext.ModuleInfo.Builder moduleBuilder = new DevModeContext.ModuleInfo.Builder().setAppArtifactKey(key)
                .setName(project.getName())
                .setProjectDirectory(project.getProjectDir().getAbsolutePath())
                .setSourcePaths(PathsCollection.from(sourcePaths))
                .setClassesPath(classesDir)
                .setResourcePaths(PathsCollection.from(resourcesSrcDirs))
                .setResourcesOutputPath(resourcesOutputPath)
                .setSourceParents(PathsCollection.from(sourceParentPaths))
                .setPreBuildOutputDir(project.getBuildDir().toPath().resolve("generated-sources").toAbsolutePath().toString())
                .setTargetDir(project.getBuildDir().toString());

        SourceSet testSourceSet = sourceSets.findByName(SourceSet.TEST_SOURCE_SET_NAME);
        if (testSourceSet != null) {

            Set<Path> testSourcePaths = new LinkedHashSet<>();
            Set<Path> testSourceParentPaths = new LinkedHashSet<>();

            for (File sourceDir : testSourceSet.getAllJava().getSrcDirs()) {
                if (sourceDir.exists()) {
                    testSourcePaths.add(sourceDir.toPath().toAbsolutePath());
                    testSourceParentPaths.add(sourceDir.toPath().getParent().toAbsolutePath());
                }
            }
            final Set<Path> testResourcesSrcDirs = new LinkedHashSet<>();
            for (File testResourcesSrcDir : testSourceSet.getResources().getSourceDirectories().getFiles()) {
                testResourcesSrcDirs.add(testResourcesSrcDir.toPath().toAbsolutePath());
            }
            // resourcesSrcDir may exist but if it's empty the resources output dir won't be created
            final File testResourcesOutputDir = testSourceSet.getOutput().getResourcesDir();

            if (!testSourcePaths.isEmpty() || (testResourcesOutputDir != null && testResourcesOutputDir.exists())) {
                String testClassesDir = QuarkusGradleUtils.getClassesDir(testSourceSet, project.getBuildDir(), true);
                if (testClassesDir != null) {
                    File testClassesDirFile = new File(testClassesDir);
                    if (testClassesDirFile.exists()) {
                        final String testResourcesOutputPath;
                        if (testResourcesOutputDir.exists()) {
                            testResourcesOutputPath = testResourcesOutputDir.getAbsolutePath();
                            if (!Files.exists(Paths.get(testClassesDir))) {
                                // currently classesDir can't be null and is expected to exist
                                testClassesDir = testResourcesOutputPath;
                            }
                        } else {
                            // currently resources dir should exist
                            testResourcesOutputPath = testClassesDir;
                        }
                        moduleBuilder.setTestSourcePaths(PathsCollection.from(testSourcePaths))
                                .setTestClassesPath(testClassesDir)
                                .setTestResourcePaths(PathsCollection.from(testResourcesSrcDirs))
                                .setTestResourcesOutputPath(testResourcesOutputPath);
                    }
                }
            }
        }
        DevModeContext.ModuleInfo wsModuleInfo = moduleBuilder
                .build();

        if (root) {
            builder.mainModule(wsModuleInfo);
        } else {
            builder.dependency(wsModuleInfo);
        }
        addeDeps.add(key);
    }

    private String getSourceEncoding() {
        return getJavaCompileTask()
                .map(javaCompile -> javaCompile.getOptions().getEncoding())
                .orElse(null);
    }

    private java.util.Optional<JavaCompile> getJavaCompileTask() {
        return java.util.Optional
                .ofNullable((JavaCompile) getProject().getTasks().getByName(JavaPlugin.COMPILE_JAVA_TASK_NAME));
    }

    private ResolvedDependency findQuarkusPluginDependency(Set<ResolvedDependency> dependencies) {
        for (ResolvedDependency rd : dependencies) {
            if ("io.quarkus.gradle.plugin".equals(rd.getModuleName())) {
                return rd;
            } else {
                Set<ResolvedDependency> children = rd.getChildren();
                if (children != null) {
                    ResolvedDependency quarkusPluginDependency = findQuarkusPluginDependency(children);
                    if (quarkusPluginDependency != null) {
                        return quarkusPluginDependency;
                    }
                }
            }
        }
        return null;
    }

    private void addGradlePluginDeps(GradleDevModeLauncher.Builder builder) {
        boolean foundQuarkusPlugin = false;
        Project prj = getProject();
        while (prj != null && !foundQuarkusPlugin) {
            final Set<ResolvedDependency> firstLevelDeps = prj.getBuildscript().getConfigurations().getByName("classpath")
                    .getResolvedConfiguration().getFirstLevelModuleDependencies();

            if (firstLevelDeps.isEmpty()) {
                // TODO this looks weird
            } else {
                ResolvedDependency quarkusPluginDependency = findQuarkusPluginDependency(firstLevelDeps);
                if (quarkusPluginDependency != null) {
                    quarkusPluginDependency.getAllModuleArtifacts().stream()
                            .map(ResolvedArtifact::getFile)
                            .forEach(f -> addToClassPaths(builder, f));

                    foundQuarkusPlugin = true;

                    break;
                }
            }
            prj = prj.getParent();
        }
        if (!foundQuarkusPlugin) {
            // that's weird, the project may include the plugin but not have it on its classpath
            // this may happen when running the plugin's tests
            // so here we check the property set in the Quarkus functional tests
            final String pluginUnderTestMetaData = System.getProperty("plugin-under-test-metadata.properties");
            if (pluginUnderTestMetaData != null) {
                final Path p = Paths.get(pluginUnderTestMetaData);
                if (Files.exists(p)) {
                    final Properties props = new Properties();
                    try (InputStream is = Files.newInputStream(p)) {
                        props.load(is);
                    } catch (IOException e) {
                        throw new IllegalStateException("Failed to read " + p, e);
                    }
                    final String classpath = props.getProperty("implementation-classpath");
                    for (String cpElement : classpath.split(File.pathSeparator)) {
                        final File f = new File(cpElement);
                        if (f.exists()) {
                            addToClassPaths(builder, f);
                        }
                    }
                }
            } else {
                throw new IllegalStateException("Unable to find quarkus-gradle-plugin dependency in " + getProject());
            }
        }
    }

    private void addToClassPaths(GradleDevModeLauncher.Builder classPathManifest, File file) {
        if (filesIncludedInClasspath.add(file)) {
            getProject().getLogger().debug("Adding dependency {}", file);
            classPathManifest.classpathEntry(file);
        }
    }
}
