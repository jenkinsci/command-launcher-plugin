/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi, Stephen Connolly
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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.Functions;
import hudson.Util;
import hudson.model.ComputerSet;
import hudson.model.Descriptor;
import hudson.model.DescriptorVisibilityFilter;
import hudson.model.Slave;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.util.FormValidation;
import hudson.util.ProcessTree;
import hudson.util.StreamCopyThread;
import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.scriptsecurity.scripts.ApprovalContext;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;
import org.jenkinsci.plugins.scriptsecurity.scripts.UnapprovedUsageException;
import org.jenkinsci.plugins.scriptsecurity.scripts.languages.SystemCommandLanguage;
import org.kohsuke.stapler.Ancestor;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest;

/**
 * {@link ComputerLauncher} through a remote login mechanism like ssh/rsh.
 *
 * @author Stephen Connolly
 * @author Kohsuke Kawaguchi
*/
public class CommandLauncher extends ComputerLauncher {

    /**
     * Command line to launch the agent, like
     * "ssh my-agent java -jar /path/to/agent.jar"
     */
    private final String agentCommand;

    /**
     * Optional environment variables to add to the current environment. Can be null.
     */
    private final EnvVars env;

    /** Constructor for use from UI. Conditionally approves the script.
     *
     * @param command  the command to run pending approval
     *
     * @see #CommandLauncher(String command, EnvVars env)
     */
    @DataBoundConstructor
    public CommandLauncher(String command) throws Descriptor.FormException {
        checkSandbox();
        agentCommand = command;
        env = null;
        // TODO add withKey if we can determine the Slave.nodeName being configured
        ScriptApproval.get().configuring(command, SystemCommandLanguage.get(), ApprovalContext.create().withCurrentUser(), Stapler.getCurrentRequest() == null);
    }

    /** Constructor for programmatic use. Always approves the script.
     *
     * @param command   the single command to run; note: this can't be a shell statement
     *                  (e.g. "echo foo &gt; bar; baz" -- if you need to do that, either use
     *                  "sh -c" or write the expression into a script and point to the script)
     * @param env       environment variables for the launcher to include when it runs the command
     */
    public CommandLauncher(String command, EnvVars env) {
    	this.agentCommand = command;
    	this.env = env;
        ScriptApproval.get().preapprove(command, SystemCommandLanguage.get());
    }

    /** Constructor for use from {@link CommandConnector}. Never approves the script. */
    CommandLauncher(EnvVars env, String command) {
        this.agentCommand = command;
        this.env = env;
    }

    /**
     * Check if the current user is forced to use the Sandbox when creating a new instance.
     * In this case, we don't allow saving new instances of the CommandLauncher object by throwing a new exception
     */
    private void checkSandbox() throws Descriptor.FormException {
          if (ScriptApproval.get().isForceSandboxForCurrentUser()) {
              throw new Descriptor.FormException(
                      "This Launch Method requires scripts executions out of the sandbox."
                      + " This Jenkins instance has been configured to not allow regular users to disable the sandbox", "command");
          }
      }
    
    private Object readResolve() {
        ScriptApproval.get().configuring(agentCommand, SystemCommandLanguage.get(), ApprovalContext.create(), true);
        return this;
    }

    public String getCommand() {
        return agentCommand;
    }

    /**
     * Gets the formatted current time stamp.
     */
    private static String getTimestamp() {
        return String.format("[%1$tD %1$tT]", new Date());
    }

    @Override
    public void launch(SlaveComputer computer, final TaskListener listener) {
        EnvVars _cookie = null;
        Process _proc = null;
        try {
            Slave node = computer.getNode();
            if (node == null) {
                throw new AbortException("Cannot launch commands on deleted nodes");
            }

            listener.getLogger().println(org.jenkinsci.plugins.command_launcher.Messages.Slave_Launching(getTimestamp()));
            String command = ScriptApproval.get().using(getCommand(), SystemCommandLanguage.get());
            if (command.trim().length() == 0) {
                listener.getLogger().println(org.jenkinsci.plugins.command_launcher.Messages.CommandLauncher_NoLaunchCommand());
                return;
            }
            listener.getLogger().println("$ " + command);

            ProcessBuilder pb = new ProcessBuilder(Util.tokenize(command));
            final EnvVars cookie = _cookie = EnvVars.createCookie();
            pb.environment().putAll(cookie);
            pb.environment().put("WORKSPACE", StringUtils.defaultString(computer.getAbsoluteRemoteFs(), node.getRemoteFS())); //path for local agent log

            {// system defined variables
                pb.environment().put("NODE_NAME", computer.getName());
                String rootUrl = Jenkins.getInstance().getRootUrl();
                if (rootUrl!=null) {
                    pb.environment().put("HUDSON_URL", rootUrl);    // for backward compatibility
                    pb.environment().put("JENKINS_URL", rootUrl);
                    pb.environment().put("SLAVEJAR_URL", rootUrl+"/jnlpJars/agent.jar");
                    pb.environment().put("AGENTJAR_URL", rootUrl+"/jnlpJars/agent.jar");
                }
            }

            if (env != null) {
            	pb.environment().putAll(env);
            }

            final Process proc = _proc = pb.start();

            // capture error information from stderr. this will terminate itself
            // when the process is killed.
            new StreamCopyThread("stderr copier for remote agent on " + computer.getDisplayName(),
                    proc.getErrorStream(), listener.getLogger()).start();

            computer.setChannel(proc.getInputStream(), proc.getOutputStream(), listener.getLogger(), new Channel.Listener() {
                @Override
                public void onClosed(Channel channel, IOException cause) {
                    reportProcessTerminated(proc, listener);

                    try {
                        ProcessTree.get().killAll(proc, cookie);
                    } catch (InterruptedException e) {
                        LOGGER.log(Level.INFO, "interrupted", e);
                    }
                }
            });

            LOGGER.info("agent launched for " + computer.getDisplayName());
        } catch (InterruptedException e) {
            Functions.printStackTrace(e, listener.error(org.jenkinsci.plugins.command_launcher.Messages.CommandLauncher_abortedLaunch()));
        } catch (UnapprovedUsageException e) {
            listener.error(e.getMessage());
        } catch (RuntimeException | Error e) {
            Functions.printStackTrace(e, listener.error(org.jenkinsci.plugins.command_launcher.Messages.CommandLauncher_unexpectedError()));
        } catch (IOException e) {
            Util.displayIOException(e, listener);

            String msg = Util.getWin32ErrorMessage(e);
            if (msg == null) {
                msg = "";
            } else {
                msg = " : " + msg;
                // FIXME TODO i18n what is this!?
            }
            msg = org.jenkinsci.plugins.command_launcher.Messages.Slave_UnableToLaunch(computer.getDisplayName(), msg);
            LOGGER.log(Level.SEVERE, msg, e);
            Functions.printStackTrace(e, listener.error(msg));

            if(_proc!=null) {
                reportProcessTerminated(_proc, listener);
                try {
                    ProcessTree.get().killAll(_proc, _cookie);
                } catch (InterruptedException x) {
                    Functions.printStackTrace(x, listener.error(org.jenkinsci.plugins.command_launcher.Messages.CommandLauncher_abortedLaunch()));
                }
            }
        }
    }

    private static void reportProcessTerminated(Process proc, TaskListener listener) {
        try {
            int exitCode = proc.exitValue();
            listener.error("Process terminated with exit code " + exitCode);
        } catch (IllegalThreadStateException e) {
            // hasn't terminated yet
        }
    }

    private static final Logger LOGGER = Logger.getLogger(CommandLauncher.class.getName());

    @Extension @Symbol("command")
    public static class DescriptorImpl extends Descriptor<ComputerLauncher> {

        @Override
        public ComputerLauncher newInstance(@Nullable StaplerRequest req, @NonNull JSONObject formData) throws FormException {
            CommandLauncher instance = (CommandLauncher) super.newInstance(req, formData);
            if (formData.get("oldCommand") != null) {
                String oldCommand = formData.getString("oldCommand");
                boolean approveIfAdmin = !StringUtils.equals(oldCommand, instance.agentCommand);
                if (approveIfAdmin) {
                    ScriptApproval.get().configuring(instance.agentCommand, SystemCommandLanguage.get(),
                            ApprovalContext.create().withCurrentUser(), true);
                }
            }
            return instance;
        }
        public String getDisplayName() {
            return org.jenkinsci.plugins.command_launcher.Messages.CommandLauncher_displayName();
        }

        public FormValidation doCheckCommand(@QueryParameter String value, @QueryParameter String oldCommand) {
            if(Util.fixEmptyAndTrim(value)==null)
                return FormValidation.error(org.jenkinsci.plugins.command_launcher.Messages.CommandLauncher_NoLaunchCommand());
            else
                return ScriptApproval.get().checking(value, SystemCommandLanguage.get(), !StringUtils.equals(value, oldCommand));
        }
    }

    /**
     * In case the flag
     * {@link ScriptApproval#isForceSandboxForCurrentUser} is true, we don't show the {@link DescriptorImpl descriptor}
     * for the current user, except if we are editing a node that already has the launcher {@link CommandLauncher}
     */
    @Extension
    public static class DescriptorVisibilityFilterForceSandBox extends DescriptorVisibilityFilter {
        @Override
        public boolean filter(@CheckForNull Object context, @NonNull Descriptor descriptor) {
            if(descriptor instanceof DescriptorImpl) {
                return !ScriptApproval.get().isForceSandboxForCurrentUser() ||
                       (context instanceof Slave && ((Slave) context).getLauncher() instanceof CommandLauncher);
            }
            return true;
        }

        @Override
        public boolean filterType(@NonNull Class<?> contextClass, @NonNull Descriptor descriptor) {
            if(descriptor instanceof DescriptorImpl)
            {
                //If we are creating a new object, check ScriptApproval.get().isForceSandboxForCurrentUser()
                //If we are NOT creating a new object, return true, and delegate the logic to #filter
                return !(isCreatingNewObject() && ScriptApproval.get().isForceSandboxForCurrentUser());
            }
            return true;
        }

        private boolean isCreatingNewObject() {
            var req = Stapler.getCurrentRequest();
            if (req != null) {
                List<Ancestor> ancs = req.getAncestors();
                for (Ancestor anc : ancs) {
                    if (anc.getObject() instanceof ComputerSet) {
                        String uri = req.getOriginalRequestURI();
                        if (uri.endsWith("createItem")) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }
}
