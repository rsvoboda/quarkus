package io.quarkus.devtools.project.update.rewrite;

import static io.quarkus.devtools.project.update.rewrite.QuarkusUpdateRecipe.RECIPE_IO_QUARKUS_OPENREWRITE_QUARKUS;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.quarkus.bootstrap.resolver.maven.options.BootstrapMavenOptions;
import io.quarkus.devtools.messagewriter.MessageWriter;
import io.quarkus.devtools.project.BuildTool;
import io.quarkus.qute.Qute;
import io.smallrye.common.os.OS;
import io.smallrye.common.process.ProcessBuilder;
import io.smallrye.common.process.ProcessUtil;

public class QuarkusUpdateCommand {

    public static final String MAVEN_REWRITE_PLUGIN_GROUP = "org.openrewrite.maven";
    public static final String MAVEN_REWRITE_PLUGIN_ARTIFACT = "rewrite-maven-plugin";
    public static Set<String> ADDITIONAL_SOURCE_FILES_SET = Set.of("**/META-INF/services/**",
            "**/*.txt",
            "**/*.adoc",
            "**/*.md",
            "**/src/main/codestarts/**/*.java",
            "**/src/test/resources/__snapshots__/**/*.java",
            "**/*.kt");

    public static String ADDITIONAL_SOURCE_FILES = String.join(",", ADDITIONAL_SOURCE_FILES_SET);

    public static String goal(boolean dryRun) {
        return dryRun ? "dryRun" : "run";
    }

    public static void handle(MessageWriter log, BuildTool buildTool, Path baseDir,
            String rewritePluginVersion, String recipesGAV, Path recipe, Path logFile, boolean dryRun) {
        switch (buildTool) {
            case MAVEN:
                runMavenUpdate(log, baseDir, rewritePluginVersion, recipesGAV, recipe, logFile, dryRun);
                break;
            case GRADLE:
                runGradleUpdate(log, baseDir, rewritePluginVersion, recipesGAV, recipe, logFile, dryRun);
                break;
            default:
                throw new QuarkusUpdateException(buildTool.getKey() + " is not supported yet");
        }
    }

    private static void runMavenUpdate(MessageWriter log, Path baseDir, String rewritePluginVersion, String recipesGAV,
            Path recipe,
            Path logFile, boolean dryRun) {
        final String mvnBinary = findMvnBinary(baseDir);
        Map<String, List<String>> commands = new LinkedHashMap<>();
        commands.put(getRewriteCommandName(rewritePluginVersion, dryRun),
                getMavenUpdateCommand(mvnBinary, rewritePluginVersion, recipesGAV, recipe, dryRun));

        // format the sources
        if (!dryRun) {
            commands.put("process-sources", getMavenProcessSourcesCommand(mvnBinary));
        }

        executeRewrite(baseDir, commands, log, logFile);
    }

    private static String getRewriteCommandName(String rewritePluginVersion, boolean dryRun) {
        return "OpenRewrite %s%s".formatted(dryRun ? " in dry mode" : "", rewritePluginVersion);
    }

    private static void runGradleUpdate(MessageWriter log, Path baseDir, String rewritePluginVersion, String recipesGAV,
            Path recipe, Path logFile, boolean dryRun) {
        Path tempInit = null;
        try {
            tempInit = Files.createTempFile("openrewrite-init", "gradle");
            try (InputStream inputStream = QuarkusUpdateCommand.class.getResourceAsStream("/openrewrite-init.gradle")) {
                String template = new BufferedReader(
                        new InputStreamReader(inputStream, StandardCharsets.UTF_8))
                        .lines()
                        .collect(Collectors.joining("\n"));

                String rewriteFile = recipe.toAbsolutePath().toString();
                if (OS.WINDOWS.isCurrent()) {
                    rewriteFile = rewriteFile.replace('\\', '/');
                }

                Files.writeString(tempInit,
                        Qute.fmt(template, Map.of(
                                "rewriteFile", rewriteFile,
                                "pluginVersion", rewritePluginVersion,
                                "recipesGAV", recipesGAV,
                                "activeRecipe", RECIPE_IO_QUARKUS_OPENREWRITE_QUARKUS,
                                "plainTextMask", ADDITIONAL_SOURCE_FILES_SET.stream()
                                        .map(s -> "\"" + s + "\"")
                                        .collect(Collectors.joining(", ")))));
            }
            final String gradleBinary = findGradleBinary(baseDir);
            List<String> command = List.of(gradleBinary.toString(), "--console", "plain", "--stacktrace",
                    "--init-script",
                    tempInit.toAbsolutePath().toString(), dryRun ? "rewriteDryRun" : "rewriteRun");
            Map<String, List<String>> commands = new LinkedHashMap<>();
            commands.put(getRewriteCommandName(rewritePluginVersion, dryRun), command);
            executeRewrite(baseDir, commands, log, logFile);
        } catch (QuarkusUpdateException e) {
            throw e;
        } catch (Exception e) {
            throw new QuarkusUpdateException(
                    "Error while running Gradle rewrite command, see the execution logs above for more details", e);
        } finally {
            if (tempInit != null) {
                try {
                    Files.deleteIfExists(tempInit);
                } catch (Exception e) {
                    // ignore
                }
            }

        }
    }

    private static String getMavenSettingsArg() {
        final String mavenSettings = System.getProperty("maven.settings");
        if (mavenSettings != null) {
            return Files.exists(Paths.get(mavenSettings)) ? mavenSettings : null;
        }
        return BootstrapMavenOptions.newInstance().getOptionValue(BootstrapMavenOptions.ALTERNATE_USER_SETTINGS);
    }

    private static void propagateSystemPropertyIfSet(String name, List<String> command) {
        if (System.getProperties().containsKey(name)) {
            final StringBuilder buf = new StringBuilder();
            buf.append("-D").append(name);
            final String value = System.getProperty(name);
            if (value != null && !value.isEmpty()) {
                buf.append("=").append(value);
            }
            command.add(buf.toString());
        }
    }

    private static List<String> getMavenProcessSourcesCommand(String mvnBinary) {
        List<String> command = new ArrayList<>();
        command.add(mvnBinary);
        command.add("-B");
        command.add("-e");
        command.add("process-sources");
        final String mavenSettings = getMavenSettingsArg();
        if (mavenSettings != null) {
            command.add("-s");
            command.add(mavenSettings);
        }
        return command;
    }

    private static List<String> getMavenUpdateCommand(String mvnBinary, String rewritePluginVersion, String recipesGAV,
            Path recipe,
            boolean dryRun) {
        final List<String> command = new ArrayList<>();
        command.add(mvnBinary);
        command.add("-B");
        command.add("-e");
        command.add(
                String.format("%s:%s:%s:%s", MAVEN_REWRITE_PLUGIN_GROUP, MAVEN_REWRITE_PLUGIN_ARTIFACT, rewritePluginVersion,
                        dryRun ? "dryRun" : "run"));
        command.add(String.format("-Drewrite.plainTextMasks=%s", ADDITIONAL_SOURCE_FILES));
        command.add(String.format("-Drewrite.configLocation=%s", recipe.toAbsolutePath()));
        command.add(String.format("-Drewrite.recipeArtifactCoordinates=%s", recipesGAV));
        command.add(String.format("-Drewrite.activeRecipes=%s", RECIPE_IO_QUARKUS_OPENREWRITE_QUARKUS));
        command.add("-Drewrite.pomCacheEnabled=false");
        final String mavenSettings = getMavenSettingsArg();
        if (mavenSettings != null) {
            command.add("-s");
            command.add(mavenSettings);
        }
        return command;
    }

    private static void executeRewrite(Path baseDir, Map<String, List<String>> commands, MessageWriter log, Path logFile) {

        log.info("");
        log.info(" ------------------------------------------------------------------------");

        log.info("");

        String logInfo;

        if (logFile != null) {
            logInfo = "Logs can be found at: %s".formatted(baseDir.relativize(logFile).toString());

            try {
                // we delete the log file prior to executing the command so that we start clean
                Files.deleteIfExists(logFile);
            } catch (IOException e) {
                // ignore, it's not a big deal
            }
        } else {
            logInfo = "See the execution logs above for more details";
        }

        log.info("Update in progress (this may take a while):");

        for (Map.Entry<String, List<String>> e : commands.entrySet()) {
            log.info("  - executing " + e.getKey() + " command...");
            execute(e.getKey(), e.getValue(), log, logFile, logInfo);
        }
        log.info("");
        log.info("Rewrite process completed. " + logInfo);
        log.info("");

    }

    private static void execute(String name, List<String> command, MessageWriter log, Path logFile, String logInfo) {

        final List<String> effectiveCommand = prepareCommand(command);

        var pb = ProcessBuilder.newBuilder(command.get(0))
                .arguments(command.subList(1, command.size()));

        try {
            if (logFile != null) {
                try (BufferedWriter bw = Files.newBufferedWriter(logFile, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND)) {
                    bw.write("Running: ");
                    bw.write(String.join(" ", effectiveCommand));
                    bw.write(System.lineSeparator());
                    pb.output().transferTo(bw)
                            .error().transferTo(bw)
                            .run();
                    bw.write(System.lineSeparator());
                    bw.write(System.lineSeparator());
                    bw.write("~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~");
                    bw.write(System.lineSeparator());
                    bw.write(System.lineSeparator());
                }
            } else {
                pb.output().consumeLinesWith(8192, line -> {
                    LogLevel logLevel = LogLevel.of(line).orElse(LogLevel.UNKNOWN);
                    switch (logLevel) {
                        case ERROR -> log.error(logLevel.clean(line));
                        case WARNING -> log.warn(logLevel.clean(line));
                        case INFO -> log.info(logLevel.clean(line));
                        default -> log.info(line);
                    }
                });
                pb.error().consumeLinesWith(8192, log::error);
                try {
                    pb.run();
                } finally {
                    log.info("");
                }
            }
        } catch (Exception e) {
            throw new QuarkusUpdateException("The %s command exited with an error. %s".formatted(name, logInfo), e);
        }
    }

    private static List<String> prepareCommand(List<String> command) {
        final List<String> effectiveCommand = new ArrayList<>(command);
        propagateSystemPropertyIfSet("maven.repo.local", effectiveCommand);
        return effectiveCommand;
    }

    static String findMvnBinary(Path baseDir) {
        Path mavenCmd = findWrapperOrBinary(baseDir, "mvnw", "mvn");
        if (mavenCmd == null) {
            throw new QuarkusUpdateException("Cannot locate mvnw or mvn"
                    + ". Make sure mvnw is in the project directory or mvn is in your PATH.");
        }
        return mavenCmd.toString();
    }

    static String findGradleBinary(Path baseDir) {
        Path gradleCmd = findWrapperOrBinary(baseDir, "gradlew", "gradle");
        if (gradleCmd == null) {
            throw new QuarkusUpdateException("Cannot gradlew mvnw or gradle"
                    + ". Make sure gradlew is in the current directory or gradle in your PATH.");
        }
        return gradleCmd.toString();
    }

    private static Path findWrapperOrBinary(Path baseDir, String wrapper, String cmd) {
        Path found = searchPath(wrapper, baseDir.toString());
        if (found == null) {
            found = searchPath(cmd);
        }
        return found;
    }

    /**
     * Searches the locations defined by PATH for the given executable
     *
     * @param cmd The name of the executable to look for
     * @return A Path to the executable, if found, null otherwise
     */
    public static Path searchPath(String cmd) {
        return ProcessUtil.pathOfCommand(Path.of(cmd)).orElse(null);
    }

    /**
     * Searches the locations defined by `paths` for the given executable
     *
     * @param cmd The name of the executable to look for
     * @param paths A string containing the paths to search
     * @return A Path to the executable, if found, null otherwise
     */
    public static Path searchPath(String cmd, String paths) {
        return Arrays.stream(paths.split(File.pathSeparator))
                .map(dir -> Paths.get(dir).resolve(cmd))
                .flatMap(QuarkusUpdateCommand::executables)
                .filter(QuarkusUpdateCommand::isExecutable)
                .findFirst()
                .orElse(null);
    }

    private static Stream<Path> executables(Path base) {
        if (isWindows()) {
            return Stream.of(Paths.get(base.toString() + ".exe"),
                    Paths.get(base.toString() + ".bat"),
                    Paths.get(base.toString() + ".cmd"),
                    Paths.get(base.toString() + ".ps1"));
        } else {
            return Stream.of(base);
        }
    }

    private static boolean isExecutable(Path file) {
        if (Files.isRegularFile(file)) {
            if (isWindows()) {
                String nm = file.getFileName().toString().toLowerCase();
                return nm.endsWith(".exe") || nm.endsWith(".bat") || nm.endsWith(".cmd") || nm.endsWith(".ps1");
            } else {
                return Files.isExecutable(file);
            }
        }
        return false;
    }

    public static boolean isWindows() {
        return OS.WINDOWS.isCurrent();
    }

    private enum LogLevel {

        ERROR,
        WARNING,
        INFO,
        UNKNOWN;

        private static final Pattern LEVEL_PATTERN = Pattern.compile("^\\[[A-Z]+\\].*");

        private static Optional<LogLevel> of(String line) {
            if (line == null || line.isBlank()) {
                return Optional.empty();
            }

            for (LogLevel level : LogLevel.values()) {
                if (level.matches(line)) {
                    return Optional.of(level);
                }
            }

            if (LEVEL_PATTERN.matcher(line).matches()) {
                return Optional.of(UNKNOWN);
            }

            return Optional.empty();
        }

        private String clean(String line) {
            if (line == null || line.isBlank()) {
                return line;
            }

            String pattern = "[" + name() + "] ";

            if (!line.startsWith(pattern)) {
                return line;
            }

            return line.substring(pattern.length());
        }

        private boolean matches(String line) {
            return line != null && line.startsWith("[" + name() + "]");
        }
    }
}
