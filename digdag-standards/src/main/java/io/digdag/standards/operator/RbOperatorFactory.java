package io.digdag.standards.operator;

import java.nio.file.Files;
import java.util.List;
import java.io.Writer;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.OutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.CharStreams;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import io.digdag.spi.OperatorContext;
import io.digdag.standards.command.SimpleCommandExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.digdag.spi.CommandExecutor;
import io.digdag.spi.CommandLogger;
import io.digdag.spi.TaskResult;
import io.digdag.spi.Operator;
import io.digdag.spi.OperatorFactory;
import io.digdag.client.config.Config;
import io.digdag.util.BaseOperator;

public class RbOperatorFactory
        implements OperatorFactory
{
    private static Logger logger = LoggerFactory.getLogger(RbOperatorFactory.class);

    private final String runnerScript;

    {
        try (InputStreamReader reader = new InputStreamReader(
                    RbOperatorFactory.class.getResourceAsStream("/digdag/standards/rb/runner.rb"),
                    StandardCharsets.UTF_8)) {
            runnerScript = CharStreams.toString(reader);
        }
        catch (IOException ex) {
            throw Throwables.propagate(ex);
        }
    }

    private final CommandExecutor exec;
    private final CommandLogger clog;
    private final ObjectMapper mapper;

    @Inject
    public RbOperatorFactory(CommandExecutor exec, CommandLogger clog,
            ObjectMapper mapper)
    {
        this.exec = exec;
        this.clog = clog;
        this.mapper = mapper;
    }

    public String getType()
    {
        return "rb";
    }

    @Override
    public Operator newOperator(OperatorContext context)
    {
        return new RbOperator(context);
    }

    private class RbOperator
            extends BaseOperator
    {
        public RbOperator(OperatorContext context)
        {
            super(context);
        }

        @Override
        public TaskResult runTask()
        {
            Config params = request.getConfig()
                .mergeDefault(request.getConfig().getNestedOrGetEmpty("rb"))
                .merge(request.getLastStateParams());  // merge state parameters in addition to regular config

            Config data;
            try {
                data = runCode(params);
            }
            catch (IOException | InterruptedException ex) {
                throw Throwables.propagate(ex);
            }

            return TaskResult.defaultBuilder(request)
                .subtaskConfig(data.getNestedOrGetEmpty("subtask_config"))
                .exportParams(data.getNestedOrGetEmpty("export_params"))
                .storeParams(data.getNestedOrGetEmpty("store_params"))
                .build();
        }

        private Config runCode(Config params)
                throws IOException, InterruptedException
        {
            final Path inFile = workspace.createTempFile("digdag-rb-in-", ".tmp"); // absolute
            final Path outFile = workspace.createTempFile("digdag-rb-out-", ".tmp"); // absolute

            final String script;
            final List<String> args;
            final Path cwd = workspace.getPath();

            if (params.has("_command")) {
                String command = params.get("_command", String.class);
                script = runnerScript;
                args = ImmutableList.of(command,
                        cwd.relativize(inFile).toString(), // relative
                        cwd.relativize(outFile).toString()); // relative
            }
            else {
                script = params.get("script", String.class);
                args = ImmutableList.of(
                        cwd.relativize(inFile).toString(), // relative
                        cwd.relativize(outFile).toString()); // relative
            }

            Optional<String> feature = params.getOptional("require", String.class);

            try (final OutputStream fo = Files.newOutputStream(inFile)) {
                mapper.writeValue(fo, ImmutableMap.of("params", params));
            }

            ImmutableList.Builder<String> cmdline = ImmutableList.builder();
            cmdline.add("ruby");
            cmdline.add("-I").add(workspace.getPath().toString());
            if (feature.isPresent()) {
                cmdline.add("-r").add(feature.get());
            }
            cmdline.add("--").add("-");  // script is fed from stdin  TODO: this doesn't work with jruby
            cmdline.addAll(args);

            ProcessBuilder pb = new ProcessBuilder(cmdline.build());
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(true);

            // Set up process environment according to env config. This can also refer to secrets.
            Map<String, String> env = pb.environment();
            SimpleCommandExecutor.collectEnvironmentVariables(env, context.getPrivilegedVariables());

            Process p = exec.start(workspace.getPath(), request, pb);

            // feed script to stdin
            try (Writer writer = new BufferedWriter(new OutputStreamWriter(p.getOutputStream()))) {
                writer.write(script);
            }

            // read stdout to stdout
            clog.copyStdout(p, System.out);

            int ecode = p.waitFor();

            if (ecode != 0) {
                throw new RuntimeException("Ruby command failed with code " + ecode);
            }

            return mapper.readValue(outFile.toFile(), Config.class);
        }
    }
}
