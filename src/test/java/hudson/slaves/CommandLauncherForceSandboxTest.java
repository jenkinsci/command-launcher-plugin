package hudson.slaves;

import org.htmlunit.html.HtmlForm;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.JenkinsRule.WebClient;
import org.jvnet.hudson.test.MockAuthorizationStrategy;

import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import jenkins.model.Jenkins;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.*;

@WithJenkins
class CommandLauncherForceSandboxTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
        Jenkins.MANAGE.setEnabled(true);

        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());

        MockAuthorizationStrategy strategy = new MockAuthorizationStrategy().
           grant(Jenkins.ADMINISTER).everywhere().to("admin").
           grant(Jenkins.MANAGE).everywhere().to("devel").
           grant(Jenkins.READ, Computer.CONFIGURE).everywhere().to("devel");

        SlaveComputer.PERMISSIONS.getPermissions().forEach(p -> strategy.grant(p).everywhere().to("devel"));

        j.jenkins.setAuthorizationStrategy(strategy);
    }

    @Test
    void newCommandLauncher() throws Exception {
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
    void editCommandLauncherUI_ForceSandboxTrue() throws Exception {
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
    void editCommandLauncherUI_ForceSandboxFalse() throws Exception {
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
    void createCommandLauncherUI_ForceSandboxTrue() throws Exception {
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
    void createCommandLauncherUI_ForceSandboxFalse() throws Exception {
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
