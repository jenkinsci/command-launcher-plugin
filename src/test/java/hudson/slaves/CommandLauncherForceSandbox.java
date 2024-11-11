package hudson.slaves;

import java.io.IOException;

import org.htmlunit.html.HtmlForm;
import org.jenkinsci.plugins.matrixauth.AuthorizationType;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.JenkinsRule.WebClient;

import hudson.model.Descriptor;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import jenkins.model.Jenkins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CommandLauncherForceSandbox {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Before
    public void configureTest() throws IOException {
        Jenkins.MANAGE.setEnabled(true);

        PermissionEntry adminPermission = new PermissionEntry(AuthorizationType.USER, "admin");
        PermissionEntry develPermission = new PermissionEntry(AuthorizationType.USER, "devel");

        GlobalMatrixAuthorizationStrategy strategy = new GlobalMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, adminPermission);
        strategy.add(Jenkins.MANAGE, adminPermission);
        strategy.add(Jenkins.READ, adminPermission);
        strategy.add(Jenkins.MANAGE, develPermission);
        strategy.add(Jenkins.READ, develPermission);

        SlaveComputer.PERMISSIONS.getPermissions().forEach(p -> strategy.add(p,develPermission));

        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(strategy);
    }

    @Test
    public void newCommandLauncher() throws Exception {
        try (ACLContext ctx = ACL.as(User.getById("devel", true))) {
            //With forceSandbox enabled, nonadmin users should not create agents with Launcher = CommandLauncher
            ScriptApproval.get().setForceSandbox(true);
            Descriptor.FormException ex = assertThrows(Descriptor.FormException.class, () ->
                    new DumbSlave("s", "/",new CommandLauncher("echo unconfigured")));

            assertEquals("This Launch Method requires scripts executions out of the sandbox."
                         + " This Jenkins instance has been configured to not allow regular users to disable the sandbox",
                         ex.getMessage());

            //With forceSandbox disabled, nonadmin users can create agents with Launcher = CommandLauncher
            ScriptApproval.get().setForceSandbox(false);
            new DumbSlave("s", "/", new CommandLauncher("echo unconfigured"));
        }

        try (ACLContext ctx = ACL.as(User.getById("admin", true))) {
            //admin users can create agents with Launcher = CommandLauncher independently of forceSandbox flag.
            ScriptApproval.get().setForceSandbox(true);
            new DumbSlave("s", "/", new CommandLauncher("echo unconfigured"));

            ScriptApproval.get().setForceSandbox(false);
            new DumbSlave("s", "/", new CommandLauncher("echo unconfigured"));
        }
    }

    @Test
    public void editCommandLauncherUI_ForceSandboxTrue() throws Exception {
        ScriptApproval.get().setForceSandbox(true);

        DumbSlave commandLauncherAgent = new DumbSlave("commandLauncherAgent", "/", new CommandLauncher("echo unconfigured"));
        DumbSlave noCommandLauncherAgent = new DumbSlave("noCommandLauncherAgent", "/", new JNLPLauncher());
        j.jenkins.addNode(commandLauncherAgent);
        j.jenkins.addNode(noCommandLauncherAgent);

        try (WebClient wc = j.createWebClient().login("devel")) {
            //Edit noCommandLauncher Agent.
            //We are not admin and Sandbox is true,
            //We don't have any html object for CommandLauncher
            HtmlForm form = wc.getPage(noCommandLauncherAgent, "configure").getFormByName("config");
            assertTrue(form.getInputsByValue(CommandLauncher.class.getName()).isEmpty());

            //Edit CommandLauncher Agent.
            //We are not admin and Sandbox is true
            // As the agent is already a commandLauncher one we have some html object for CommandLauncher
            form = wc.getPage(commandLauncherAgent, "configure").getFormByName("config");
            assertFalse(form.getInputsByValue(CommandLauncher.class.getName()).isEmpty());
        }

        try (WebClient wc = j.createWebClient().login("admin")) {
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
        }    }

    @Test
    public void editCommandLauncherUI_ForceSandboxFalse() throws Exception {
        ScriptApproval.get().setForceSandbox(false);

        DumbSlave commandLauncherAgent = new DumbSlave("commandLauncherAgent", "/", new CommandLauncher("echo unconfigured"));
        DumbSlave noCommandLauncherAgent = new DumbSlave("noCommandLauncherAgent", "/", new JNLPLauncher());
        j.jenkins.addNode(commandLauncherAgent);
        j.jenkins.addNode(noCommandLauncherAgent);

        try (WebClient wc = j.createWebClient().login("devel")) {
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

        try (WebClient wc = j.createWebClient().login("admin")) {
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

    @Test
    public void createCommandLauncherUI_ForceSandboxTrue() throws Exception {
        ScriptApproval.get().setForceSandbox(true);

        try (WebClient wc = j.createWebClient().login("devel")) {
            //Create Permanent Agent.
            //We are not admin and Sandbox is true,
            //We don't have any html object for CommandLauncher
            HtmlForm form = wc.goTo("computer/new").getFormByName("createItem");
            form.getInputByName("name").setValue("devel_ComandLauncher");
            form.getInputsByValue(DumbSlave.class.getName()).stream().findFirst().get().setChecked(true);
            HtmlForm createNodeForm =  j.submit(form).getFormByName("config");
            assertTrue(createNodeForm.getInputsByValue(CommandLauncher.class.getName()).isEmpty());
        }

        try (WebClient wc = j.createWebClient().login("admin")) {
            //Create Permanent Agent.
            //We are admin and Sandbox is true,
            //We have some html object for CommandLauncher
            HtmlForm form = wc.goTo("computer/new").getFormByName("createItem");
            form.getInputByName("name").setValue("devel_ComandLauncher");
            form.getInputsByValue(DumbSlave.class.getName()).stream().findFirst().get().setChecked(true);
            HtmlForm createNodeForm =  j.submit(form).getFormByName("config");
            assertFalse(createNodeForm.getInputsByValue(CommandLauncher.class.getName()).isEmpty());
        }
    }

    @Test
    public void createCommandLauncherUI_ForceSandboxFalse() throws Exception {
        ScriptApproval.get().setForceSandbox(false);

        try (WebClient wc = j.createWebClient().login("devel")) {
            //Create Permanent Agent.
            //We are not admin and Sandbox is false,
            //We have some html object for CommandLauncher
            HtmlForm form = wc.goTo("computer/new").getFormByName("createItem");
            form.getInputByName("name").setValue("devel_ComandLauncher");
            form.getInputsByValue(DumbSlave.class.getName()).stream().findFirst().get().setChecked(true);
            HtmlForm createNodeForm =  j.submit(form).getFormByName("config");
            assertFalse(createNodeForm.getInputsByValue(CommandLauncher.class.getName()).isEmpty());
        }

        try (WebClient wc = j.createWebClient().login("admin")) {
            //Create Permanent Agent.
            //We are admin and Sandbox is true,
            //We have some html object for CommandLauncher
            HtmlForm form = wc.goTo("computer/new").getFormByName("createItem");
            form.getInputByName("name").setValue("devel_ComandLauncher");
            form.getInputsByValue(DumbSlave.class.getName()).stream().findFirst().get().setChecked(true);
            HtmlForm createNodeForm =  j.submit(form).getFormByName("config");
            assertFalse(createNodeForm.getInputsByValue(CommandLauncher.class.getName()).isEmpty());
        }
    }
}
