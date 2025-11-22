/*
 * The MIT License
 *
 * Copyright (c) 2015 Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.slaves;

import hudson.EnvVars;
import hudson.Functions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.Scanner;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assertions.*;

@WithJenkins
class CommandLauncherTest {

    private static final MessageFormat windowsCommand = new MessageFormat("{0} /c \"echo %{1}%> {2}\"");
    private static final MessageFormat posixCommand = new MessageFormat("sh -c \"echo ${1}> {2}\"");

    private JenkinsRule j;

    @TempDir
    private File temporaryFolder;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    // TODO sometimes gets EOFException as in commandSucceedsWithoutChannel
    @Test
    void commandFails() throws Exception {
        assumeFalse(Functions.isWindows());
        DumbSlave agent = createAgentTimeout("false");

        String log = agent.toComputer().getLog();
        assertTrue(agent.toComputer().isOffline(), log);
        assertThat(log, containsString("ERROR: Process terminated with exit code"));
        assertThat(log, not(containsString("ERROR: Process terminated with exit code 0")));
    }

    // TODO Sometimes gets `EOFException: unexpected stream termination` before then on CI builder; maybe needs to wait in a loop for a message to appear?
    @Test
    void commandSucceedsWithoutChannel() throws Exception {
        assumeFalse(Functions.isWindows());
        DumbSlave agent = createAgentTimeout("true");

        String log = agent.toComputer().getLog();
        assertTrue(agent.toComputer().isOffline(), log);
        assertThat(log, containsString("ERROR: Process terminated with exit code 0"));
    }

    @Test
    void hasEnvVarNodeName() throws Exception {
        hasEnvVar("NODE_NAME", "dummy", null);
    }

    @Test
    void hasEnvVarWorkspace() throws Exception {
        String workspacePath = createWorkspace();
        hasEnvVar("WORKSPACE", workspacePath, workspacePath);
    }

    @Test
    void hasEnvVarJenkinsUrl() throws Exception {
        String url = j.jenkins.getRootUrl();
        hasEnvVar("HUDSON_URL", url, null);
        hasEnvVar("JENKINS_URL", url, null);
    }

    @Test
    void hasEnvVarAgentUrl() throws Exception {
        String url = j.jenkins.getRootUrl()+"/jnlpJars/agent.jar";
        hasEnvVar("SLAVEJAR_URL", url, null);
        hasEnvVar("AGENTJAR_URL", url, null);
    }

    private static void connectToComputer(DumbSlave agent) {
        try {
            agent.toComputer().connect(false).get();
        } catch (Exception e) {
            System.err.println("uninteresting error (not running an actual agent.jar): " + e);
        }
    }

    private void hasEnvVar(String name, String value, String workspacePath) throws Exception {
        File canary = File.createTempFile("junit", null, temporaryFolder);
        String command;
        String shell = EnvVars.masterEnvVars.get("comspec");
        Object[] args = {shell, name, canary.getAbsolutePath()};
        if (shell != null) {
            command = windowsCommand.format(args);
        } else {
            command = posixCommand.format(args);
        }

        DumbSlave agent = createAgent(command, workspacePath);
        connectToComputer(agent);
        String content = new Scanner(canary).useDelimiter("\\Z").next();
        j.jenkins.removeNode(agent);
        assertEquals(value, content);
    }

    private String createWorkspace() throws IOException {
        File tempDir = newFolder(temporaryFolder, "junit");
        return tempDir.getAbsolutePath();
    }

    private DumbSlave createAgent(String command, String workspacePath) throws Exception {
        DumbSlave agent;
        if (workspacePath == null)
            workspacePath = createWorkspace();

        synchronized (j.jenkins) { // TODO this lock smells like a bug post 1.607
            agent = new DumbSlave("dummy", workspacePath, new CommandLauncher(command));
            j.jenkins.addNode(agent);
        }
        return agent;
    }

    private DumbSlave createAgentTimeout(String command) throws Exception {
        DumbSlave agent = createAgent(command, null);
        assertThrows(ExecutionException.class,
                () -> agent.toComputer().connect(false).get(1, TimeUnit.SECONDS),
                "the agent was not supposed to connect successfully");
        return agent;
    }

    private static File newFolder(File root, String... subDirs) throws IOException {
        String subFolder = String.join("/", subDirs);
        File result = new File(root, subFolder);
        if (!result.exists() && !result.mkdirs()) {
            throw new IOException("Couldn't create folders " + root);
        }
        return result;
    }
}
