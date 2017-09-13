/*
 * The MIT License
 *
 * Copyright (c) Red Hat, Inc.
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
package jenkins.plugins.openstack.compute.slaveopts;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHAuthenticator;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernameListBoxModel;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.converters.reflection.ReflectionConverter;
import com.thoughtworks.xstream.converters.reflection.ReflectionProvider;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.mapper.Mapper;
import com.trilead.ssh2.Connection;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.ItemGroup;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.security.ACL;
import hudson.security.AccessControlled;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.JNLPLauncher;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.plugins.openstack.compute.JCloudsCloud;
import jenkins.plugins.openstack.compute.JCloudsSlave;
import jenkins.plugins.openstack.compute.SlaveOptions;
import net.sf.json.JSONObject;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.Serializable;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Node launcher factory.
 *
 * @author ogondza.
 */
public abstract class SlaveType extends AbstractDescribableImpl<SlaveType> implements Serializable {
    private static final long serialVersionUID = -8322868020681278525L;
    private static final Logger LOGGER = Logger.getLogger(SlaveType.class.getName());

    /**
     * Create launcher to be used to start the computer.
     */
    public abstract ComputerLauncher createLauncher(@Nonnull JCloudsSlave slave) throws IOException;

    /**
     * Detect the machine is provisioned and can be added to Jenkins for launching.
     * <p>
     * This is guaranteed to be called after server is/was ACTIVE.
     */
    public abstract boolean isReady(@Nonnull JCloudsSlave slave);

    /**
     * Launch nodes via ssh-slaves plugin.
     */
    public static final class SSH extends SlaveType {

        private final String credentialsId;

        public String getCredentialsId() {
            return credentialsId;
        }

        @DataBoundConstructor
        public SSH(String credentialsId) {
            this.credentialsId = credentialsId;
        }

        @Override
        public ComputerLauncher createLauncher(@Nonnull JCloudsSlave slave) throws IOException {
            int maxNumRetries = 5;
            int retryWaitTime = 15;

            SlaveOptions opts = slave.getSlaveOptions();
            if (credentialsId == null) {
                throw new JCloudsCloud.ProvisioningFailedException("No ssh credentials selected");
            }

            String publicAddress = slave.getPublicAddressIpv4();
            if (publicAddress == null) {
                throw new IOException("The slave is likely deleted");
            }
            if ("0.0.0.0".equals(publicAddress)) {
                throw new IOException("Invalid host 0.0.0.0, your host is most likely waiting for an ip address.");
            }

            Integer timeout = opts.getStartTimeout();
            timeout = timeout == null ? 0: (timeout / 1000); // Never propagate null - always set some timeout

            return new SSHLauncher(publicAddress, 22, credentialsId, opts.getJvmOptions(), null, "", "", timeout, maxNumRetries, retryWaitTime);
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            SSH ssh = (SSH) o;

            return credentialsId != null ? credentialsId.equals(ssh.credentialsId): ssh.credentialsId == null;
        }

        @Override public int hashCode() {
            return credentialsId != null ? credentialsId.hashCode(): 0;
        }

        /**
         * The node is considered ready when ssh port is open.
         */
        @Override
        public boolean isReady(@Nonnull JCloudsSlave slave) {

            // richnou:
            //	Use Ipv4 Method to make sure IPV4 is the default here
            //	OVH cloud provider returns IPV6 as last address, and getPublicAddress returns the last address
            //  The Socket connection test then does not work.
            //	This method could return the address object and work on it, but for now stick to IPV4
            //  for the sake of simplicity
            //
            String publicAddress = slave.getPublicAddressIpv4();
            // Wait until ssh is exposed not to timeout for too long in ssh-slaves launcher
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(publicAddress, 22), 500);
                socket.close();
                return true;
            } catch (ConnectException | NoRouteToHostException | SocketTimeoutException ex) {
                // Exactly what we are looking for
                LOGGER.log(Level.FINEST, "SSH port at " + publicAddress + " not open (yet)", ex);
                return false;
            } catch (IOException ex) {
                // TODO: General IOException to be understood and handled explicitly
                LOGGER.log(Level.INFO, "SSH port  at " + publicAddress + " not (yet) open?", ex);
                return false;
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "SSH probe failed", ex);
                // We have no idea what happen. Log the cause and proceed with the server so it fail fast.
                return true;
            }


        }

        @Extension
        public static final class Desc extends Descriptor<SlaveType> {
            @Restricted(DoNotUse.class)
            public ListBoxModel doFillCredentialsIdItems(@AncestorInPath ItemGroup context) {
                if (!(context instanceof AccessControlled ? (AccessControlled) context : Jenkins.getActiveInstance()).hasPermission(Computer.CONFIGURE)) {
                    return new ListBoxModel();
                }
                List<StandardUsernameCredentials> credentials = CredentialsProvider.lookupCredentials(
                        StandardUsernameCredentials.class, context, ACL.SYSTEM, SSHLauncher.SSH_SCHEME
                );
                return new StandardUsernameListBoxModel()
                        .withMatching(SSHAuthenticator.matcher(Connection.class), credentials)
                        .withEmptySelection()
                ;
            }

            // TODO
//            @Restricted(DoNotUse.class)
//            public FormValidation doCheckCredentialsId(
//                    @QueryParameter String value,
//                    @RelativePath("../../slaveOptions") @QueryParameter("credentialsId") String def
//            ) {
//                if (Util.fixEmpty(value) == null) {
//                    String d = getDefault(def, opts().getCredentialsId());
//                    if (d != null) {
//                        d = CredentialsNameProvider.name(SSHLauncher.lookupSystemCredentials(d)); // ID to name
//                        return FormValidation.ok(def(d));
//                    }
//                    return REQUIRED;
//                }
//                return OK;
//            }
        }
    }

    /**
     * Wait for JNLP connection to be made.
     */
    public static final class JNLP extends SlaveType {

        public static final SlaveType JNLP = new JNLP();

        private JNLP() {}

        @Override
        public ComputerLauncher createLauncher(@Nonnull JCloudsSlave slave) throws IOException {
            Jenkins.getActiveInstance().addNode(slave);
            return new JNLPLauncher();
        }

        @Override
        public boolean isReady(@Nonnull JCloudsSlave slave) {
            // The address might not be visible at all so let's just wait for connection.
            return slave.getChannel() != null;
        }

        @Override
        public int hashCode() {
            return 31;
        }

        @Override
        public boolean equals(Object obj) {
            return obj != null && getClass() == obj.getClass();
        }

        private Object readResolve() {
            return JNLP; // Let's avoid creating instances where we can
        }

        @Extension
        public static final class Desc extends Descriptor<SlaveType> {
            @Override
            public SlaveType newInstance(StaplerRequest req, @Nonnull JSONObject formData) throws FormException {
                return JNLP; // Let's avoid creating instances where we can
            }
        }
    }

    /**
     * No slave type specified. This exists only as a field in select to be read as null.
     */
    public static final class Unspecified extends SlaveType {
        private Unspecified() {} // Never instantiate

        @Override public ComputerLauncher createLauncher(@Nonnull JCloudsSlave slave) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override public boolean isReady(@Nonnull JCloudsSlave slave) {
            throw new UnsupportedOperationException();
        }

        @Extension public static final class Desc extends Descriptor<SlaveType> {
            @Override public @Nonnull String getDisplayName() {
                return "Inherit / Override later";
            }

            @Override public SlaveType newInstance(StaplerRequest req, @Nonnull JSONObject formData) throws FormException {
                return null; // Make sure this is never instantiated and hence will be treated as absent
            }
        }
    }

    static {
        Jenkins.XSTREAM2.registerConverter(new CompatibilityConverter(
                Jenkins.XSTREAM2.getMapper(),
                Jenkins.XSTREAM2.getReflectionProvider(),
                SlaveType.class
        ));
    }

    // Deserialize configuration that was saved when SlaveType was an enum: "<slaveType>JNLP</slaveType>". Do not
    // intercept the serialization in any other way.
    private static final class CompatibilityConverter extends ReflectionConverter {
        private CompatibilityConverter(Mapper mapper, ReflectionProvider reflectionProvider, Class type) {
            super(mapper, reflectionProvider, type);
        }

        @Override public Object unmarshal(HierarchicalStreamReader reader, UnmarshallingContext context) {
            String value = reader.getValue();
            Object ret;
            switch (value) {
                case "SSH":
                    ret = new SSH(null); // Just to unmarshal the type - will be replaced by enclosing type with an instance with read credentialsId
                break;
                case "JNLP":
                    ret = JNLP.JNLP;
                break;
                default:
                    ret = super.unmarshal(reader, context);
                break;
            }
            return ret;
        }
    }
}
