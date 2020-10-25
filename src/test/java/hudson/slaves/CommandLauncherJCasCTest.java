package hudson.slaves;

import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class CommandLauncherJCasCTest {
    @Rule
    public JenkinsConfiguredWithCodeRule r = new JenkinsConfiguredWithCodeRule();

    @Test
    @ConfiguredWithCode("configuration-as-code.yml")
    public void shouldBeAbleToSetupAgentWithCommandLauncher() throws Exception {
        final DumbSlave agent = (DumbSlave) r.jenkins.getNode("this-node-precisely");
        Assert.assertNotNull(agent);

        final ComputerLauncher computerLauncher = agent.getLauncher();
        MatcherAssert.assertThat(computerLauncher, Matchers.instanceOf(CommandLauncher.class));
        final CommandLauncher commandLauncher = (CommandLauncher) computerLauncher;

        Assert.assertEquals("this is the command to start the agent", commandLauncher.getCommand());
    }
}
