package hudson.slaves;

import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.casc.misc.junit.jupiter.WithJenkinsConfiguredWithCode;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@WithJenkinsConfiguredWithCode
class CommandLauncherJCasCTest {

    @Test
    @ConfiguredWithCode("configuration-as-code.yml")
    void shouldBeAbleToSetupAgentWithCommandLauncher(JenkinsConfiguredWithCodeRule r) {
        final DumbSlave agent = (DumbSlave) r.jenkins.getNode("this-node-precisely");
        assertNotNull(agent);

        final ComputerLauncher computerLauncher = agent.getLauncher();
        assertThat(computerLauncher, Matchers.instanceOf(CommandLauncher.class));
        final CommandLauncher commandLauncher = (CommandLauncher) computerLauncher;

        assertEquals("this is the command to start the agent", commandLauncher.getCommand());
    }
}
