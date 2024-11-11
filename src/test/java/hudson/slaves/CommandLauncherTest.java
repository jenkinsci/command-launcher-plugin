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
import hudson.model.Descriptor;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import hudson.security.Permission;

import org.htmlunit.html.HtmlForm;
import org.jenkinsci.plugins.matrixauth.AuthorizationType;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;
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

import jenkins.model.Jenkins;

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
        DumbSlave agent = createAgentTimeout("false");

        String log = agent.toComputer().getLog();
        assertTrue(log, agent.toComputer().isOffline());
        assertThat(log, containsString("ERROR: Process terminated with exit code"));
        assertThat(log, not(containsString("ERROR: Process terminated with exit code 0")));
    }

    // TODO Sometimes gets `EOFException: unexpected stream termination` before then on CI builder; maybe needs to wait in a loop for a message to appear?
    @Test
    public void commandSucceedsWithoutChannel() throws Exception {
        assumeTrue(!Functions.isWindows());
        DumbSlave agent = createAgentTimeout("true");

        String log = agent.toComputer().getLog();
        assertTrue(log, agent.toComputer().isOffline());
        assertThat(log, containsString("ERROR: Process terminated with exit code 0"));
    }

    private static void connectToComputer(DumbSlave agent) {
        try {
            agent.toComputer().connect(false).get();
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

        DumbSlave agent = createAgent(command, workspacePath);
        connectToComputer(agent);
        String content = new Scanner(canary).useDelimiter("\\Z").next();
        j.jenkins.removeNode(agent);
        assertEquals(value, content);
    }

    private String createWorkspace() throws IOException {
        File tempDir = temporaryFolder.newFolder();
        return tempDir.getAbsolutePath();
    }

    public DumbSlave createAgent(String command, String workspacePath) throws Exception {
        DumbSlave agent;
        if (workspacePath == null)
            workspacePath = createWorkspace();

        synchronized (j.jenkins) { // TODO this lock smells like a bug post 1.607
            agent = new DumbSlave("dummy", workspacePath, new CommandLauncher(command));
            j.jenkins.addNode(agent);
        }
        return agent;
    }

    public DumbSlave createAgentTimeout(String command) throws Exception {
        DumbSlave agent = createAgent(command, null);

        try {
            agent.toComputer().connect(false).get(1, TimeUnit.SECONDS);
            fail("the agent was not supposed to connect successfully");
        } catch (ExecutionException e) {
            // ignore, we just want to
        }

        return agent;
    }

    @Test
    public void commandLauncher_ForceSandbox() throws Exception {
        DumbSlave commandLauncherAgent = new DumbSlave("commandLauncherAgent", "/",new CommandLauncher("echo unconfigured"));
        DumbSlave noCommandLauncherAgent = new DumbSlave("noCommandLauncherAgent", "/", new JNLPLauncher());

        j.jenkins.addNode(commandLauncherAgent);
        j.jenkins.addNode(noCommandLauncherAgent);

        Jenkins.MANAGE.setEnabled(true);

        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        GlobalMatrixAuthorizationStrategy strategy = new GlobalMatrixAuthorizationStrategy();

        PermissionEntry adminPermission = new PermissionEntry(AuthorizationType.USER, "admin");
        PermissionEntry develPermission = new PermissionEntry(AuthorizationType.USER, "devel");

        strategy.add(Jenkins.ADMINISTER, adminPermission);
        strategy.add(Jenkins.MANAGE, adminPermission);
        strategy.add(Jenkins.READ, adminPermission);
        strategy.add(Jenkins.MANAGE, develPermission);
        strategy.add(Jenkins.READ, develPermission);

        for (Permission p : SlaveComputer.PERMISSIONS.getPermissions()) {
            strategy.add(p, develPermission);
        }

        j.jenkins.setAuthorizationStrategy(strategy);

        try (ACLContext ctx = ACL.as(User.getById("devel", true))) {
            //With forceSandbox enabled, nonadmin users should not create agents with Launcher = CommandLauncher
            ScriptApproval.get().setForceSandbox(true);
            Descriptor.FormException ex = assertThrows(Descriptor.FormException.class, () ->
                new DumbSlave("s", "/",new CommandLauncher("echo unconfigured"))
             );

            assertEquals("This Launch Method requires scripts executions out of the sandbox."
                         + " This Jenkins instance has been configured to not allow regular users to disable the sandbox",
                         ex.getMessage());

            //With forceSandbox disabled, nonadmin users can create agents with Launcher = CommandLauncher
            ScriptApproval.get().setForceSandbox(false);
            new DumbSlave("s", "/",new CommandLauncher("echo unconfigured"));
        }

        try (ACLContext ctx = ACL.as(User.getById("admin", true))) {
            //admin users can create agents with Launcher = CommandLauncher independently of forceSandbox flag.
            ScriptApproval.get().setForceSandbox(true);
            new DumbSlave("s", "/",new CommandLauncher("echo unconfigured"));

            ScriptApproval.get().setForceSandbox(false);
            new DumbSlave("s", "/",new CommandLauncher("echo unconfigured"));
        }

        ScriptApproval.get().setForceSandbox(true);
        {
            try (JenkinsRule.WebClient wc = j.createWebClient().login("devel")) {
                //Edit noCommandLauncher Agent.
                //We are not admin and Sandbox is true,
                //We don't have any html object for CommandLauncher
                HtmlForm form = wc.getPage(noCommandLauncherAgent, "configure").getFormByName("config");
                assertTrue(form.getInputsByValue(CommandLauncher.class.getName()).isEmpty());

                //Edit CommandLauncher Agent.
                //Wwe are not admin and Sandbox is true
                // As the agent is already a commandLauncher one we have some html object for CommandLauncher
                form = wc.getPage(commandLauncherAgent, "configure").getFormByName("config");
                assertFalse(form.getInputsByValue(CommandLauncher.class.getName()).isEmpty());

                //Launch CommandLauncher non Approved Script
                //We are not admin and Sandbox is true,
                //Error message should not show any admin approval reference
                //TODO: not sure how to tackle this.
                //j.jenkins.addNode(test);

                //TODO: Test the new node page
            }

            try (JenkinsRule.WebClient wc = j.createWebClient().login("admin")) {
                //Edit noCommandLauncher Agent.
                //We areadmin and Sandbox is true,
                //We have some html object for CommandLauncher
                HtmlForm form = wc.getPage(noCommandLauncherAgent, "configure").getFormByName("config");
                assertFalse(form.getInputsByValue(CommandLauncher.class.getName()).isEmpty());

                //Edit CommandLauncher Agent.
                //Wwe not admin and Sandbox is true
                //We have some html object for CommandLauncher
                form = wc.getPage(commandLauncherAgent, "configure").getFormByName("config");
                assertFalse(form.getInputsByValue(CommandLauncher.class.getName()).isEmpty());
            }
        }

        ScriptApproval.get().setForceSandbox(false);
        {
            try (JenkinsRule.WebClient wc = j.createWebClient().login("devel")) {
                //Edit noCommandLauncher Agent.
                //We are not admin and Sandbox is false,
                //We have some html object for CommandLauncher
                HtmlForm form = wc.getPage(noCommandLauncherAgent, "configure").getFormByName("config");
                assertFalse(form.getInputsByValue(CommandLauncher.class.getName()).isEmpty());

                //Edit CommandLauncher Agent.
                //Wwe are not admin and Sandbox is false
                //We have some html object for CommandLauncher
                form = wc.getPage(commandLauncherAgent, "configure").getFormByName("config");
                assertFalse(form.getInputsByValue(CommandLauncher.class.getName()).isEmpty());
            }

            try (JenkinsRule.WebClient wc = j.createWebClient().login("admin")) {
                //Edit noCommandLauncher Agent.
                //We areadmin and Sandbox is false,
                //We have some html object for CommandLauncher
                HtmlForm form = wc.getPage(noCommandLauncherAgent, "configure").getFormByName("config");
                assertFalse(form.getInputsByValue(CommandLauncher.class.getName()).isEmpty());

                //Edit CommandLauncher Agent.
                //Wwe not admin and Sandbox is false
                //We have some html object for CommandLauncher
                form = wc.getPage(commandLauncherAgent, "configure").getFormByName("config");
                assertFalse(form.getInputsByValue(CommandLauncher.class.getName()).isEmpty());
            }
        }
    }
}
