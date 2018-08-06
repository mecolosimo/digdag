package io.digdag.standards.operator;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.google.api.client.util.Maps;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import io.digdag.client.config.Config;
import io.digdag.client.config.ConfigElement;
import io.digdag.client.config.ConfigException;
import io.digdag.spi.CommandExecutor;
import io.digdag.spi.CommandExecutorContext;
import io.digdag.spi.CommandExecutorRequest;
import io.digdag.spi.CommandLogger;
import io.digdag.spi.CommandStatus;
import io.digdag.spi.OperatorContext;
import io.digdag.spi.TaskExecutionException;
import io.digdag.spi.TemplateEngine;
import io.digdag.spi.TemplateException;
import io.digdag.spi.TaskResult;
import io.digdag.spi.Operator;
import io.digdag.spi.OperatorFactory;
import io.digdag.standards.command.SimpleCommandExecutor;
import io.digdag.standards.operator.state.TaskState;
import io.digdag.standards.operator.td.YamlLoader;
import io.digdag.util.BaseOperator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.digdag.standards.operator.Secrets.resolveSecrets;
import static java.nio.charset.StandardCharsets.UTF_8;

public class EmbulkOperatorFactory
        implements OperatorFactory
{
    private static Logger logger = LoggerFactory.getLogger(EmbulkOperatorFactory.class);

    private final CommandExecutor exec;
    private final CommandLogger clog;
    private final TemplateEngine templateEngine;
    private final ObjectMapper mapper;
    private final YAMLFactory yaml;

    @Inject
    public EmbulkOperatorFactory(CommandExecutor exec, TemplateEngine templateEngine, CommandLogger clog, ObjectMapper mapper)
    {
        this.exec = exec;
        this.clog = clog;
        this.templateEngine = templateEngine;
        this.mapper = mapper;
        this.yaml = new YAMLFactory()
            .configure(YAMLGenerator.Feature.WRITE_DOC_START_MARKER, false);
    }

    public String getType()
    {
        return "embulk";
    }

    @Override
    public Operator newOperator(OperatorContext context)
    {
        return new EmbulkOperator(context);
    }

    private class EmbulkOperator
            extends BaseOperator
    {
        // TODO extract as config params.
        final int scriptPollInterval = (int) Duration.ofSeconds(3).getSeconds();

        public EmbulkOperator(OperatorContext context)
        {
            super(context);
        }

        @Override
        public TaskResult runTask()
        {
            try {
                runEmbulk();
                return TaskResult.empty(request);
            }
            catch (IOException | TemplateException | InterruptedException e) {
                throw Throwables.propagate(e);
            }
        }

        private void runEmbulk()
                throws IOException, TemplateException, InterruptedException
        {
            Config params = request.getConfig()
                    .mergeDefault(request.getConfig().getNestedOrGetEmpty("embulk"));
            final Config stateParams = TaskState.of(request).params();
            final Path projectPath = workspace.getProjectPath();

            final CommandStatus status;
            if (!stateParams.has("commandStatus")) {
                // Run the code since command state doesn't exist
                status = runEmbulk(params, projectPath);
            }
            else {
                // Check the status of the code running
                final ObjectNode previousStatusJson = stateParams.get("commandStatus", ObjectNode.class);
                status = checkCodeState(params, projectPath, previousStatusJson);
            }

            if (status.isFinished()) {
                final int statusCode = status.getStatusCode();
                if (statusCode != 0) {
                    // Remove the polling state after fetching the result so that the result fetch can be retried
                    // without resubmitting the code.
                    stateParams.remove("commandStatus");
                    throw new RuntimeException("Command failed with code " + statusCode);
                }
                return;
            }
            else {
                stateParams.set("commandStatus", status);
                throw TaskExecutionException.ofNextPolling(scriptPollInterval, ConfigElement.copyOf(stateParams));
            }
        }

        private CommandStatus runEmbulk(final Config params, final Path projectPath)
                throws IOException, InterruptedException, TemplateException
        {
            final Path tempDir = workspace.createTempDir(String.format("digdag-embulk-%d-", request.getTaskId()));
            final Path workingDirectory = workspace.getPath(); // absolute
            final Path embulkYmlPath = tempDir.resolve("load.yml");

            if (params.has("_command")) {
                String command = params.get("_command", String.class);
                String data = workspace.templateFile(templateEngine, command, UTF_8, params);

                ObjectNode embulkConfig;
                try {
                    embulkConfig = new YamlLoader().loadString(data);
                }
                catch (RuntimeException | IOException ex) {
                    Throwables.propagateIfInstanceOf(ex, ConfigException.class);
                    throw new ConfigException("Failed to parse yaml file", ex);
                }

                Files.write(workingDirectory.relativize(embulkYmlPath),
                        mapper.writeValueAsBytes(resolveSecrets(embulkConfig, context.getSecrets())));
            }
            else {
                final Config embulkConfig = params.getNested("config");
                try (final YAMLGenerator out = yaml.createGenerator(Files.newOutputStream(embulkYmlPath), JsonEncoding.UTF8)) {
                    mapper.writeValue(out, embulkConfig);
                }
            }

            final List<String> cmdline = ImmutableList.of("embulk", "run", workingDirectory.relativize(embulkYmlPath).toString());

            final Map<String, String> environments = Maps.newHashMap();
            environments.putAll(System.getenv());
            SimpleCommandExecutor.collectEnvironmentVariables(environments, context.getPrivilegedVariables());

            final CommandExecutorContext context = buildCommandExecutorContext(projectPath);
            final CommandExecutorRequest request = buildCommandExecutorRequest(context, workingDirectory, tempDir, environments, cmdline);
            return exec.run(context, request);

            // TaskExecutionException could not be thrown here to poll the task by non-blocking for process-base
            // command executor. Because they will be bounded by the _instance_ where the command was executed
            // first.
        }

        private CommandStatus checkCodeState(final Config params,
                final Path projectPath,
                final ObjectNode previousStatusJson)
                throws IOException, InterruptedException
        {
            final CommandExecutorContext context = buildCommandExecutorContext(projectPath);
            return exec.poll(context, previousStatusJson);
        }

        private CommandExecutorContext buildCommandExecutorContext(final Path projectPath)
        {
            return CommandExecutorContext.builder()
                    .localProjectPath(projectPath)
                    .taskRequest(this.request)
                    .build();
        }

        private CommandExecutorRequest buildCommandExecutorRequest(final CommandExecutorContext context,
                final Path workingDirectory,
                final Path tempDir,
                final Map<String, String> environments,
                final List<String> cmdline)
        {
            final Path projectPath = context.getLocalProjectPath();
            final Path relativeWorkingDirectory = projectPath.relativize(workingDirectory); // relative
            final Path ioDirectory = projectPath.relativize(tempDir); // relative
            return CommandExecutorRequest.builder()
                    .workingDirectory(relativeWorkingDirectory)
                    .environments(environments)
                    .command(cmdline)
                    .ioDirectory(ioDirectory)
                    .build();
        }
    }
}
