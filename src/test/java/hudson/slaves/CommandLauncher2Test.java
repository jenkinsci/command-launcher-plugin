/*
 * The MIT License
 *
 * Copyright 2017 CloudBees, Inc.
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

import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlTextInput;
import hudson.XmlFile;
import hudson.cli.CLICommand;
import hudson.cli.CLICommandInvoker;
import hudson.cli.UpdateNodeCommand;
import hudson.model.Computer;
import hudson.model.User;
import java.io.File;
import java.io.IOException;
import java.util.Set;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import jenkins.model.Jenkins;
import org.apache.tools.ant.filters.StringInputStream;
import org.hamcrest.CustomTypeSafeMatcher;
import org.hamcrest.Description;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval.PendingScript;
import org.jenkinsci.plugins.scriptsecurity.scripts.languages.SystemCommandLanguage;
import static org.junit.Assert.*;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.JenkinsSessionRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.recipes.LocalData;

import static hudson.slaves.CommandLauncher2Test.PendingScriptApprovalMatcher.pendingScript;

public class CommandLauncher2Test {

    @Rule
    public JenkinsSessionRule rr = new JenkinsSessionRule();

    @Issue({"SECURITY-478", "SECURITY-3103"})
    @Test
    public void requireApproval() throws Throwable {
        rr.then(j -> {
                j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
                j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().
                    grant(Jenkins.ADMINISTER).everywhere().to("admin").
                    grant(Jenkins.READ, Computer.CONFIGURE).everywhere().to("dev"));
                ScriptApproval.get().preapprove("echo unconfigured", SystemCommandLanguage.get());
                DumbSlave s = new DumbSlave("s", "/", new CommandLauncher("echo unconfigured"));
                j.jenkins.addNode(s);
                // First, reconfigure using GUI.
                JenkinsRule.WebClient wc = j.createWebClient().login("admin");
                HtmlForm form = wc.getPage(s, "configure").getFormByName("config");
                HtmlTextInput input = form.getInputByName("_.command");
                assertEquals("echo unconfigured", input.getText());
                input.setText("echo configured by GUI");
                j.submit(form);
                s = (DumbSlave) j.jenkins.getNode("s");
                assertEquals("echo configured by GUI", ((CommandLauncher) s.getLauncher()).getCommand());
                assertSerialForm(j, s, "echo configured by GUI");
                assertThat(ScriptApproval.get().getPendingScripts(), contains(pendingScript("echo configured by GUI")));
                ScriptApproval.get().getPendingScripts().clear(); // reset

                // Then by REST.
                String configDotXml = s.toComputer().getUrl() + "config.xml";
                String xml = wc.goTo(configDotXml, "application/xml").getWebResponse().getContentAsString();
                assertThat(xml, containsString("echo configured by GUI"));
                WebRequest req = new WebRequest(wc.createCrumbedUrl(configDotXml), HttpMethod.POST);
                req.setEncodingType(null);
                req.setRequestBody(xml.replace("echo configured by GUI", "echo configured by REST"));
                wc.getPage(req);
                s = (DumbSlave) j.jenkins.getNode("s");
                assertEquals("echo configured by REST", ((CommandLauncher) s.getLauncher()).getCommand());
                assertSerialForm(j, s, "echo configured by REST");
                assertThat(ScriptApproval.get().getPendingScripts(), contains(pendingScript("echo configured by REST")));
                ScriptApproval.get().getPendingScripts().clear(); // reset

                // Then by CLI.
                CLICommand cmd = new UpdateNodeCommand();
                cmd.setTransportAuth(User.get("admin").impersonate());
                assertThat(new CLICommandInvoker(j, cmd).withStdin(new StringInputStream(xml.replace("echo configured by GUI", "echo configured by CLI"))).invokeWithArgs("s"), CLICommandInvoker.Matcher.succeededSilently());
                s = (DumbSlave) j.jenkins.getNode("s");
                assertEquals("echo configured by CLI", ((CommandLauncher) s.getLauncher()).getCommand());
                assertSerialForm(j, s, "echo configured by CLI");
                assertThat(ScriptApproval.get().getPendingScripts(), contains(pendingScript("echo configured by CLI")));
                ScriptApproval.get().getPendingScripts().clear(); // reset

                // Now verify that all modes failed as dev. First as GUI.
                ScriptApproval.get().preapprove("echo configured by admin", SystemCommandLanguage.get());
                s.setLauncher(new CommandLauncher("echo configured by admin"));
                s.save();
                wc = j.createWebClient().login("dev");
                form = wc.getPage(s, "configure").getFormByName("config");
                input = form.getInputByName("_.command");
                assertEquals("echo configured by admin", input.getText());
                input.setText("echo GUI ATTACK");
                j.submit(form);
                s = (DumbSlave) j.jenkins.getNode("s");
                assertEquals("echo GUI ATTACK", ((CommandLauncher) s.getLauncher()).getCommand());
                Set<ScriptApproval.PendingScript> pendingScripts = ScriptApproval.get().getPendingScripts();
                assertEquals(1, pendingScripts.size());
                ScriptApproval.PendingScript pendingScript = pendingScripts.iterator().next();
                assertEquals(SystemCommandLanguage.get(), pendingScript.getLanguage());
                assertEquals("echo GUI ATTACK", pendingScript.script);
                assertEquals("dev", pendingScript.getContext().getUser());
                ScriptApproval.get().denyScript(pendingScript.getHash());
                assertSerialForm(j, s, "echo GUI ATTACK");
                // Then by REST.
                req = new WebRequest(wc.createCrumbedUrl(configDotXml), HttpMethod.POST);
                req.setEncodingType(null);
                req.setRequestBody(xml.replace("echo configured by GUI", "echo REST ATTACK"));
                wc.getPage(req);
                s = (DumbSlave) j.jenkins.getNode("s");
                assertEquals("echo REST ATTACK", ((CommandLauncher) s.getLauncher()).getCommand());
                pendingScripts = ScriptApproval.get().getPendingScripts();
                assertEquals(1, pendingScripts.size());
                pendingScript = pendingScripts.iterator().next();
                assertEquals(SystemCommandLanguage.get(), pendingScript.getLanguage());
                assertEquals("echo REST ATTACK", pendingScript.script);
                assertEquals(/* deserialization, not recording user */ null, pendingScript.getContext().getUser());
                ScriptApproval.get().denyScript(pendingScript.getHash());
                assertSerialForm(j, s, "echo REST ATTACK");
                // Then by CLI.
                cmd = new UpdateNodeCommand();
                cmd.setTransportAuth(User.get("dev").impersonate());
                assertThat(new CLICommandInvoker(j, cmd).withStdin(new StringInputStream(xml.replace("echo configured by GUI", "echo CLI ATTACK"))).invokeWithArgs("s"), CLICommandInvoker.Matcher.succeededSilently());
                s = (DumbSlave) j.jenkins.getNode("s");
                assertEquals("echo CLI ATTACK", ((CommandLauncher) s.getLauncher()).getCommand());
                pendingScripts = ScriptApproval.get().getPendingScripts();
                assertEquals(1, pendingScripts.size());
                pendingScript = pendingScripts.iterator().next();
                assertEquals(SystemCommandLanguage.get(), pendingScript.getLanguage());
                assertEquals("echo CLI ATTACK", pendingScript.script);
                assertEquals(/* ditto */null, pendingScript.getContext().getUser());
                ScriptApproval.get().denyScript(pendingScript.getHash());
                assertSerialForm(j, s, "echo CLI ATTACK");
                // Now also check that SYSTEM deserialization works after a restart.
        });
        rr.then(j -> {
                DumbSlave s = (DumbSlave) j.jenkins.getNode("s");
                assertEquals("echo CLI ATTACK", ((CommandLauncher) s.getLauncher()).getCommand());
                Set<ScriptApproval.PendingScript> pendingScripts = ScriptApproval.get().getPendingScripts();
                assertEquals(1, pendingScripts.size());
                ScriptApproval.PendingScript pendingScript = pendingScripts.iterator().next();
                assertEquals(SystemCommandLanguage.get(), pendingScript.getLanguage());
                assertEquals("echo CLI ATTACK", pendingScript.script);
                assertEquals(/* ditto */null, pendingScript.getContext().getUser());
        });
    }

    private static void assertSerialForm(JenkinsRule j, DumbSlave s, @CheckForNull String expectedCommand) throws IOException {
        // cf. private methods in Nodes
        File nodesDir = new File(j.jenkins.getRootDir(), "nodes");
        XmlFile configXml = new XmlFile(Jenkins.XSTREAM, new File(new File(nodesDir, s.getNodeName()), "config.xml"));
        assertThat(configXml.asString(), expectedCommand != null ? containsString("<agentCommand>" + expectedCommand + "</agentCommand>") : not(containsString("<agentCommand>")));
    }

    @LocalData // saved by Hudson 1.215
    @Test
    public void ancientSerialForm() throws Throwable {
        rr.then(j -> {
                ComputerLauncher launcher = ((DumbSlave) j.jenkins.getNode("test")).getLauncher();
                assertThat(launcher, instanceOf(CommandLauncher.class));
                assertEquals("echo from CLI", ((CommandLauncher) launcher).getCommand());
        });
    }

    static class PendingScriptApprovalMatcher extends CustomTypeSafeMatcher<PendingScript> {

        private final String expectedScript;

        private PendingScriptApprovalMatcher(String expectedScript) {
            super("PendingScript with script " + expectedScript);
            this.expectedScript = expectedScript;
        }

        @Override
        protected boolean matchesSafely(PendingScript item) {
            return expectedScript.equals(item.script);
        }

        @Override
        public void describeMismatchSafely(PendingScript item, Description mismatchDescription) {
            mismatchDescription.appendText("has script ").appendText(item.script);
        }

        public static PendingScriptApprovalMatcher pendingScript(String expectedScript) {
            return new PendingScriptApprovalMatcher(expectedScript);
        }
    }
}
