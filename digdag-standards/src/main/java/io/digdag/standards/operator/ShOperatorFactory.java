package io.digdag.standards.operator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import io.digdag.client.config.Config;
import io.digdag.client.config.ConfigException;
import io.digdag.spi.CommandExecutor;
import io.digdag.spi.CommandLogger;
import io.digdag.spi.Operator;
import io.digdag.spi.OperatorFactory;
import io.digdag.spi.PrivilegedVariables;
import io.digdag.spi.OperatorContext;
import io.digdag.spi.TaskRequest;
import io.digdag.spi.TaskResult;
import io.digdag.util.BaseOperator;
import io.digdag.util.UserSecretTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

public class ShOperatorFactory
        implements OperatorFactory
{
    private static Logger logger = LoggerFactory.getLogger(ShOperatorFactory.class);

    private static Pattern VALID_ENV_KEY = Pattern.compile("[a-zA-Z_][a-zA-Z_0-9]*");

    private final CommandExecutor exec;
    private final CommandLogger clog;

    @Inject
    public ShOperatorFactory(CommandExecutor exec, CommandLogger clog)
    {
        this.exec = exec;
        this.clog = clog;
    }

    public String getType()
    {
        return "sh";
    }

    @Override
    public ShOperator newOperator(OperatorContext context)
    {
        return new ShOperator(context);
    }

    class ShOperator
            extends BaseOperator
    {
        public ShOperator(OperatorContext context)
        {
            super(context);
        }

        @Override
        public TaskResult runTask()
        {
            Config params = request.getConfig()
                .mergeDefault(request.getConfig().getNestedOrGetEmpty("sh"));

            List<String> shell = params.getListOrEmpty("shell", String.class);
            if (shell.isEmpty()) {
                shell = ImmutableList.of("/bin/sh");
            }
            String command = UserSecretTemplate.of(params.get("_command", String.class))
                    .format(context.getSecrets());

            ProcessBuilder pb = new ProcessBuilder(shell);
            pb.directory(workspace.getPath().toFile());

            final Map<String, String> env = new HashMap<>();

            params.getKeys()
                .forEach(key -> {
                    if (isValidEnvKey(key)) {
                        JsonNode value = params.get(key, JsonNode.class);
                        String string;
                        if (value.isTextual()) {
                            string = value.textValue();
                        }
                        else {
                            string = value.toString();
                        }
                        env.put(key, string);
                    }
                    else {
                        logger.trace("Ignoring invalid env var key: {}", key);
                    }
                });

            // Set up process environment according to env config. This can also refer to secrets.
            collectEnvironmentVariables(env, context.getPrivilegedVariables());

            // add workspace path to the end of $PATH so that bin/cmd works without ./ at the beginning
            String pathEnv = System.getenv("PATH");
            if (pathEnv == null) {
                pathEnv = workspace.getPath().toString();
            }
            else {
                pathEnv = pathEnv + File.pathSeparator + workspace.getPath().toAbsolutePath().toString();
            }

            pb.redirectErrorStream(true);

            int ecode;
            try {
                Process p = exec.start(workspace.getPath(), request, pb, env);

                // feed command to stdin
                try (Writer writer = new BufferedWriter(new OutputStreamWriter(p.getOutputStream()))) {
                    writer.write(command);
                }

                // copy stdout to System.out and logger
                clog.copyStdout(p, System.out);

                ecode = p.waitFor();
            }
            catch (IOException | InterruptedException ex) {
                throw Throwables.propagate(ex);
            }

            if (ecode != 0) {
                throw new RuntimeException("Command failed with code " + ecode);
            }

            return TaskResult.empty(request);
        }
    }

    static void collectEnvironmentVariables(Map<String, String> env, PrivilegedVariables variables)
    {
        for (String name : variables.getKeys()) {
            if (!VALID_ENV_KEY.matcher(name).matches()) {
                throw new ConfigException("Invalid _env key name: " + name);
            }
            env.put(name, variables.get(name));
        }
    }

    private static boolean isValidEnvKey(String key)
    {
        return VALID_ENV_KEY.matcher(key).matches();
    }
}
