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
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.Scanner;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class CommandLauncherTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    // TODO sometimes gets EOFException as in commandSucceedsWithoutChannel
    public void commandFails() throws Exception {
        assumeTrue(!Functions.isWindows());
        DumbSlave slave = createSlaveTimeout("false");

        String log = slave.toComputer().getLog();
        assertTrue(log, slave.toComputer().isOffline());
        assertThat(log, containsString("ERROR: Process terminated with exit code"));
        assertThat(log, not(containsString("ERROR: Process terminated with exit code 0")));
    }

    // TODO Sometimes gets `EOFException: unexpected stream termination` before then on CI builder; maybe needs to wait in a loop for a message to appear?
    @Test
    public void commandSucceedsWithoutChannel() throws Exception {
        assumeTrue(!Functions.isWindows());
        DumbSlave slave = createSlaveTimeout("true");

        String log = slave.toComputer().getLog();
        assertTrue(log, slave.toComputer().isOffline());
        assertThat(log, containsString("ERROR: Process terminated with exit code 0"));
    }

    private static void connectToComputer(DumbSlave slave) {
        try {
            slave.toComputer().connect(false).get();
        } catch (Exception e) {
            System.err.println("uninteresting error (not running an actual agent.jar): " + e);
        }
    }

    @Test
    public void hasEnvVarNodeName() throws Exception {
        hasEnvVar("NODE_NAME", "dummy", null);
    }

    @Test
    public void hasEnvVarWorkspace() throws Exception {
        String workspacePath = createWorkspace();
        hasEnvVar("WORKSPACE", workspacePath, workspacePath);
    }

    @Test
    public void hasEnvVarJenkinsUrl() throws Exception {
        String url = j.jenkins.getRootUrl();
        hasEnvVar("HUDSON_URL", url, null);
        hasEnvVar("JENKINS_URL", url, null);
    }

    @Test
    public void hasEnvVarAgentUrl() throws Exception {
        String url = j.jenkins.getRootUrl()+"/jnlpJars/agent.jar";
        hasEnvVar("SLAVEJAR_URL", url, null);
        hasEnvVar("AGENTJAR_URL", url, null);
    }

    private static MessageFormat windowsCommand = new MessageFormat("{0} /c \"echo %{1}%> {2}\"");
    private static MessageFormat posixCommand = new MessageFormat("sh -c \"echo ${1}> {2}\"");
    private void hasEnvVar(String name, String value, String workspacePath) throws Exception {
        File canary = temporaryFolder.newFile();
        String command;
        String shell = EnvVars.masterEnvVars.get("comspec");
        Object[] args = {shell, name, canary.getAbsolutePath()};
        if (shell != null) {
            command = windowsCommand.format(args);
        } else {
            command = posixCommand.format(args);
        }

        DumbSlave slave = createSlave(command, workspacePath);
        connectToComputer(slave);
        String content = new Scanner(canary).useDelimiter("\\Z").next();
        j.jenkins.removeNode(slave);
        assertEquals(value, content);
    }

    private String createWorkspace() throws IOException {
        File tempDir = temporaryFolder.newFolder();
        return tempDir.getAbsolutePath();
    }

    public DumbSlave createSlave(String command, String workspacePath) throws Exception {
        DumbSlave slave;
        if (workspacePath == null)
            workspacePath = createWorkspace();

        synchronized (j.jenkins) { // TODO this lock smells like a bug post 1.607
            slave = new DumbSlave("dummy", workspacePath, new CommandLauncher(command));
            j.jenkins.addNode(slave);
        }
        return slave;
    }

    public DumbSlave createSlaveTimeout(String command) throws Exception {
        DumbSlave slave = createSlave(command, null);

        try {
            slave.toComputer().connect(false).get(1, TimeUnit.SECONDS);
            fail("the slave was not supposed to connect successfully");
        } catch (ExecutionException e) {
            // ignore, we just want to
        }

        return slave;
    }
}
