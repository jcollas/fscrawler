/*
 * Licensed to David Pilato (the "Author") under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Author licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package fr.pilato.elasticsearch.crawler.fs.cli;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import fr.pilato.elasticsearch.crawler.fs.FsCrawlerImpl;
import fr.pilato.elasticsearch.crawler.fs.beans.FsJobFileHandler;
import fr.pilato.elasticsearch.crawler.fs.framework.*;
import fr.pilato.elasticsearch.crawler.fs.rest.RestServer;
import fr.pilato.elasticsearch.crawler.fs.settings.FsCrawlerValidator;
import fr.pilato.elasticsearch.crawler.fs.settings.FsSettings;
import fr.pilato.elasticsearch.crawler.fs.settings.FsSettingsLoader;
import fr.pilato.elasticsearch.crawler.fs.settings.FsSettingsParser;
import fr.pilato.elasticsearch.crawler.plugins.FsCrawlerPluginsManager;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.LevelMatchFilter;
import org.apache.logging.log4j.core.filter.LevelRangeFilter;

import java.io.Console;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Scanner;

import static fr.pilato.elasticsearch.crawler.fs.framework.FsCrawlerUtil.*;

/**
 * Main entry point to launch FsCrawler
 */
public class FsCrawlerCli {

    private static final long CLOSE_POLLING_WAIT_MS = 100;

    private static final Logger logger = LogManager.getLogger();
    private static FsCrawlerPluginsManager pluginsManager;

    @SuppressWarnings("CanBeFinal")
    public static class FsCrawlerCommand {
        @Parameter(description = "job_name")
        List<String> jobName;

        @Parameter(names = "--config_dir", description = "Config directory. Default to ~/.fscrawler")
        String configDir = null;

        @Parameter(names = "--api_key", description = "Elasticsearch api key. (Deprecated - use -Delasticsearch.api_key instead)")
        @Deprecated
        String apiKey = null;

        @Parameter(names = "--username", description = "Elasticsearch username. (Deprecated - use -Delasticsearch.api_key instead)")
        @Deprecated
        String username = null;

        @Parameter(names = "--loop", description = "Number of scan loop before exiting.")
        Integer loop = -1;

        @Parameter(names = "--restart", description = "Restart fscrawler job like if it never ran before. " +
                "This does not clean elasticsearch indices.")
        boolean restart = false;

        @Parameter(names = "--rest", description = "Start REST Layer")
        boolean rest = false;

        @Parameter(names = "--upgrade", description = "Upgrade elasticsearch indices from one old version to the last version.")
        boolean upgrade = false;

        @Deprecated
        @Parameter(names = "--debug", description = "Debug mode (Deprecated - use FS_JAVA_OPTS=\"-DLOG_LEVEL=debug\" instead)")
        boolean debug = false;

        @Deprecated
        @Parameter(names = "--trace", description = "Trace mode (Deprecated - use FS_JAVA_OPTS=\"-DLOG_LEVEL=trace\" instead)")
        boolean trace = false;

        @Parameter(names = "--silent", description = "Silent mode")
        boolean silent = false;

        @Parameter(names = "--help", description = "display current help", help = true)
        boolean help;
    }


    public static void main(String[] args) throws Exception {
        FsCrawlerCommand command = commandParser(args);

        if (command != null) {
            if (command.debug) {
                // Deprecated command line option
                logger.warn("--debug option has been deprecated. Use FS_JAVA_OPTS=\"-DLOG_LEVEL=debug\" instead.");
            }
            if (command.trace) {
                // Deprecated command line option
                logger.warn("--trace option has been deprecated. Use FS_JAVA_OPTS=\"-DLOG_LEVEL=trace\" instead.");
            }

            // We change the log level if needed
            changeLoggerContext(command);

            // Display the welcome banner
            banner();

            // Load all plugins
            pluginsManager = new FsCrawlerPluginsManager();
            pluginsManager.loadPlugins();

            // We can now launch the crawler
            runner(command);
        }
    }

    static FsCrawlerCommand commandParser(String[] args) {
        FsCrawlerCommand commands = new FsCrawlerCommand();
        JCommander jCommander = new JCommander(commands);
        jCommander.parse(args);

        // Check the expected parameters when in silent mode
        if (commands.silent) {
            if (commands.jobName == null) {
                banner();
                logger.warn("--silent is set but no job has been defined. Add a job name or remove --silent option. Exiting.");
                jCommander.usage();
                throw new FsCrawlerIllegalConfigurationException("No job specified while in silent mode.");
            }
        }

        if (commands.help) {
            jCommander.usage();
            return null;
        }

        return commands;
    }

    static void changeLoggerContext(FsCrawlerCommand command) {
        // Change debug level if needed
        if (command.debug || command.trace || command.silent) {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            Configuration config = ctx.getConfiguration();
            LoggerConfig loggerConfig = config.getLoggerConfig("fr.pilato.elasticsearch.crawler.fs");
            ConsoleAppender console = config.getAppender("Console");

            if (command.silent) {
                // We don't write anything on the console anymore
                if (console != null) {
                    console.addFilter(LevelMatchFilter.newBuilder().setLevel(Level.ALL).setOnMatch(Filter.Result.DENY).build());
                }
            } else {
                if (console != null) {
                    console.addFilter(LevelRangeFilter.createFilter(
                            command.debug ? Level.TRACE : Level.ALL,
                            Level.ALL,
                            Filter.Result.DENY,
                            Filter.Result.ACCEPT));
                }
            }

            loggerConfig.setLevel(command.debug ? Level.DEBUG : Level.TRACE);
            ctx.updateLoggers();
        }
    }

    /**
     * Reinit the logger context to remove all filters
     */
    static void reinitLoggerContext() {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        ConsoleAppender console = config.getAppender("Console");
        if (console != null) {
            Filter filter = console.getFilter();
            console.removeFilter(filter);
        }

        LoggerConfig loggerConfig = config.getLoggerConfig("fr.pilato.elasticsearch.crawler.fs");
        if (loggerConfig != null) {
            loggerConfig.setLevel(Level.INFO);
        }
    }

    /**
     * Create a job if needed
     * @param jobName the job name
     * @param configDir the config dir
     * @param scanner the scanner to read user input
     * @throws IOException In case of IO problem
     */
    static void createJob(String jobName, Path configDir, Scanner scanner) throws IOException {
        FSCrawlerLogger.console("job [{}] does not exist", jobName);

        String yesno = null;
        while (!"y".equalsIgnoreCase(yesno) && !"n".equalsIgnoreCase(yesno)) {
            FSCrawlerLogger.console("Do you want to create it (Y/N)?");
            yesno = scanner.next();
        }

        if ("y".equalsIgnoreCase(yesno)) {
            Path configJobDir = configDir.resolve(jobName);
            Files.createDirectories(configJobDir);
            Path configFile = configJobDir.resolve(FsSettingsLoader.SETTINGS_YAML);

            // Write the example config files from the classpath FsSettingsLoader.EXAMPLE_SETTINGS
            copyResourceFile(FsSettingsLoader.EXAMPLE_SETTINGS, configFile);
            FSCrawlerLogger.console("Settings have been created in [{}]. Please review and edit before relaunch", configFile);
        }
    }

    static void runner(FsCrawlerCommand command) throws IOException {
        // create a scanner so we can read the command-line input
        Console con = System.console();
        Scanner scanner = null;
        if (con != null) {
            scanner = new Scanner(con.reader());
        }

        BootstrapChecks.check();

        Path configDir;

        if (command.configDir == null) {
            configDir = MetaFileHandler.DEFAULT_ROOT;
        } else {
            configDir = Paths.get(command.configDir);
        }

        // Create the config dir if needed
        FsCrawlerUtil.createDirIfMissing(configDir);

        // We copy default mapping and settings to the default settings dir .fscrawler/_default/
        copyDefaultResources(configDir);

        FsSettings fsSettings;

        String jobName;

        if (command.jobName == null) {
            if (scanner == null) {
                logger.error("No job specified. Exiting.");
                System.exit(1);
            }

            // The user did not enter a job name.
            // We can list available jobs for him
            FSCrawlerLogger.console("No job specified. Here is the list of existing jobs:");

            List<String> files = FsCrawlerJobsUtil.listExistingJobs(configDir);

            if (!files.isEmpty()) {
                for (int i = 0; i < files.size(); i++) {
                    FSCrawlerLogger.console("[{}] - {}", i+1, files.get(i));
                }
                int chosenFile = 0;
                while (chosenFile <= 0 || chosenFile > files.size()) {
                    FSCrawlerLogger.console("Choose your job [1-{}]...", files.size());
                    chosenFile = scanner.nextInt();
                }
                jobName = files.get(chosenFile - 1);
            } else {
                FSCrawlerLogger.console("No job exists in [{}].", configDir);
                FSCrawlerLogger.console("To create your first job, run 'fscrawler job_name' with 'job_name' you want");
                return;
            }

        } else {
            jobName = command.jobName.get(0);
        }

        // If we ask to reinit, we need to clean the status for the job
        if (command.restart) {
            logger.debug("Cleaning existing status for job [{}]...", jobName);
            new FsJobFileHandler(configDir).clean(jobName);
        }

        logger.debug("Starting job [{}]...", jobName);
        try {
            fsSettings = new FsSettingsLoader(configDir).read(jobName);
            // Let's make the job name not mandatory in the settings file
            if (fsSettings.getName() == null) {
                fsSettings.setName(jobName);
            }
        } catch (FsCrawlerIllegalConfigurationException e) {
            if (e.getCause() == null) {
                logger.debug("job [{}] does not exist.", jobName);
                // We can only have a dialog with the end user if we are not silent
                if (command.silent || scanner == null) {
                    logger.error("job [{}] does not exist. Exiting as we are in silent mode or no input available.", jobName);
                    System.exit(2);
                }

                createJob(jobName, configDir, scanner);
                return;
            }
            throw e;
        } catch (Exception e) {
            logger.fatal("Cannot parse the configuration file: {}", e.getMessage());
            throw e;
        }

        if (command.username != null) {
            logger.fatal("We don't support reading elasticsearch username from the command line anymore. " +
                            "Please use either -Delasticsearch.username={} or set the env variable as follows: " +
                            "FSCRAWLER_ELASTICSEARCH_USERNAME={} ",
                    command.username, command.username);
            return;
        }

        if (command.apiKey != null) {
            logger.fatal("We don't support reading elasticsearch username from the command line anymore. " +
                            "Please use either -Delasticsearch.api_key={} or set the env variable as follows: " +
                            "FSCRAWLER_ELASTICSEARCH_API_KEY={} ",
                    command.apiKey, command.apiKey);
            return;
        }

        if (fsSettings.getElasticsearch().getUsername() != null && fsSettings.getElasticsearch().getPassword() == null && scanner != null) {
            logger.fatal("We don't support reading elasticsearch password from the command line anymore. " +
                            "Please use either -Delasticsearch.password=YOUR_PASS or set the env variable as follows: " +
                            "FSCRAWLER_ELASTICSEARCH_PASSWORD=YOUR_PASS.");
            logger.warn("Using username and password is deprecated. Please use API Keys instead. See " +
                            "https://fscrawler.readthedocs.io/en/latest/admin/fs/elasticsearch.html#api-key");
            return;
        }

        if (logger.isTraceEnabled()) {
            logger.trace("settings used for this crawler: [{}]", FsSettingsParser.toYaml(fsSettings));
        }
        if (FsCrawlerValidator.validateSettings(logger, fsSettings)) {
            // We don't go further as we have critical errors
            return;
        }

        pluginsManager.startPlugins();

        try (FsCrawlerImpl fsCrawler = new FsCrawlerImpl(configDir, fsSettings, command.loop, command.rest)) {
            Runtime.getRuntime().addShutdownHook(new FSCrawlerShutdownHook(fsCrawler, pluginsManager));
            // Let see if we want to upgrade an existing cluster to the latest version
            if (command.upgrade) {
                logger.info("Upgrading job [{}]. No rule implemented. Skipping.", jobName);
            } else {
                if (!startEsClient(fsCrawler)) {
                    return;
                }
                String elasticsearchVersion = fsCrawler.getManagementService().getVersion();
                checkForDeprecatedResources(configDir, elasticsearchVersion);

                // Start the REST Server if needed
                if (command.rest) {
                    RestServer.start(fsSettings, fsCrawler.getManagementService(), fsCrawler.getDocumentService(), pluginsManager);
                }

                // We just have to wait until the process is stopped
                while (!fsCrawler.getFsParser().isClosed()) {
                    sleep();
                }
            }
        } catch (Exception e) {
            logger.fatal("Fatal error received while running the crawler: [{}]", e.getMessage());
            logger.debug("error caught", e);
        }
    }

    private static boolean startEsClient(FsCrawlerImpl fsCrawler) {
        try {
            fsCrawler.start();
            return true;
        } catch (Exception t) {
            logger.fatal("We can not start Elasticsearch Client. Exiting.", t);
            return false;
        }
    }

    private static final int BANNER_LENGTH = 100;

    /**
     * This is coming from: <a href="https://patorjk.com/software/taag/#p=display&f=3D%20Diagonal&t=FSCrawler">https://patorjk.com/software/taag/#p=display&f=3D%20Diagonal&t=FSCrawler</a>
     */
    private static final String ASCII_ART = "" +
            "    ,---,.  .--.--.     ,----..                                     ,--,                      \n" +
            "  ,'  .' | /  /    '.  /   /   \\                                  ,--.'|                      \n" +
            ",---.'   ||  :  /`. / |   :     :  __  ,-.                   .---.|  | :               __  ,-.\n" +
            "|   |   .';  |  |--`  .   |  ;. /,' ,'/ /|                  /. ./|:  : '             ,' ,'/ /|\n" +
            ":   :  :  |  :  ;_    .   ; /--` '  | |' | ,--.--.       .-'-. ' ||  ' |      ,---.  '  | |' |\n" +
            ":   |  |-, \\  \\    `. ;   | ;    |  |   ,'/       \\     /___/ \\: |'  | |     /     \\ |  |   ,'\n" +
            "|   :  ;/|  `----.   \\|   : |    '  :  / .--.  .-. | .-'.. '   ' .|  | :    /    /  |'  :  /  \n" +
            "|   |   .'  __ \\  \\  |.   | '___ |  | '   \\__\\/: . ./___/ \\:     ''  : |__ .    ' / ||  | '   \n" +
            "'   :  '   /  /`--'  /'   ; : .'|;  : |   ,\" .--.; |.   \\  ' .\\   |  | '.'|'   ;   /|;  : |   \n" +
            "|   |  |  '--'.     / '   | '/  :|  , ;  /  /  ,.  | \\   \\   ' \\ |;  :    ;'   |  / ||  , ;   \n" +
            "|   :  \\    `--'---'  |   :    /  ---'  ;  :   .'   \\ \\   \\  |--\" |  ,   / |   :    | ---'    \n" +
            "|   | ,'               \\   \\ .'         |  ,     .-./  \\   \\ |     ---`-'   \\   \\  /          \n" +
            "`----'                  `---`            `--`---'       '---\"                `----'           \n";

    private static void banner() {
        FSCrawlerLogger.console(
                separatorLine(",", ".") +
                centerAsciiArt() +
                separatorLine("+", "+") +
                bannerLine("You know, for Files!") +
                bannerLine("Made from France with Love") +
                bannerLine("Source: https://github.com/dadoonet/fscrawler/") +
                bannerLine("Documentation: https://fscrawler.readthedocs.io/") +
                separatorLine("`", "'"));
    }

    private static String centerAsciiArt() {
        String[] lines = StringUtils.split(ASCII_ART, '\n');

        // Edit line 0 as we want to add the version
        String version = Version.getVersion();
        String firstLine = StringUtils.stripEnd(StringUtils.center(lines[0], BANNER_LENGTH), null);
        String pad = StringUtils.rightPad(firstLine, BANNER_LENGTH - version.length() - 1) + version;
        lines[0] = pad;

        StringBuilder content = new StringBuilder();
        for (String line : lines) {
            content.append(bannerLine(line));
        }

        return content.toString();
    }

    private static String bannerLine(String text) {
        return "|" + StringUtils.center(text, BANNER_LENGTH) + "|\n";
    }

    private static String separatorLine(String first, String last) {
        return first + StringUtils.center("", BANNER_LENGTH, "-") + last + "\n";
    }

    private static void checkForDeprecatedResources(Path configDir, String elasticsearchVersion) throws IOException {
        try {
            // If we are able to read an old configuration file, we should tell the user to check the documentation
            readDefaultJsonVersionedFile(configDir, extractMajorVersion(elasticsearchVersion), "doc");
            logger.warn("We found old configuration index settings: [{}/_default/doc.json]. You should look at the documentation" +
                    " about upgrades: https://fscrawler.readthedocs.io/en/latest/installation.html#upgrade-fscrawler.",
                    configDir);
        } catch (IllegalArgumentException ignored) {
            // If we can't find the deprecated resource, it will throw an exception which needs to be ignored.
        }
        try {
            // If we are able to read an old configuration file, we should tell the user to check the documentation
            readDefaultJsonVersionedFile(configDir, extractMajorVersion(elasticsearchVersion), "folder");
            logger.warn("We found old configuration index settings: [{}/_default/folder.json]. You should look at the documentation" +
                    " about upgrades: https://fscrawler.readthedocs.io/en/latest/installation.html#upgrade-fscrawler.",
                    configDir);
        } catch (IllegalArgumentException ignored) {
            // If we can't find the deprecated resource, it will throw an exception which needs to be ignored.
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(CLOSE_POLLING_WAIT_MS);
        }
        catch(InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
