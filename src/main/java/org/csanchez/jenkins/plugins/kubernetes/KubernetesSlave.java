package org.csanchez.jenkins.plugins.kubernetes;

import static org.csanchez.jenkins.plugins.kubernetes.KubernetesCloud.JNLP_NAME;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.console.ModelHyperlinkNote;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Executor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.TaskListener;
import hudson.remoting.Engine;
import hudson.remoting.VirtualChannel;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.Cloud;
import hudson.slaves.CloudRetentionStrategy;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.utils.Serialization;
import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.metrics.api.Metrics;
import jenkins.model.Jenkins;
import jenkins.security.MasterToSlaveCallable;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.Validate;
import org.csanchez.jenkins.plugins.kubernetes.pod.retention.PodRetention;
import org.jenkinsci.plugins.durabletask.executors.OnceRetentionStrategy;
import org.jenkinsci.plugins.kubernetes.auth.KubernetesAuthException;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jvnet.localizer.ResourceBundleHolder;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * @author Carlos Sanchez carlos@apache.org
 */
public class KubernetesSlave extends AbstractCloudSlave {

    private static final Logger LOGGER = Logger.getLogger(KubernetesSlave.class.getName());

    private static final Integer DISCONNECTION_TIMEOUT =
            Integer.getInteger(KubernetesSlave.class.getName() + ".disconnectionTimeout", 5);

    private static final long serialVersionUID = -8642936855413034232L;
    private static final String DEFAULT_AGENT_PREFIX = "jenkins-agent";

    /**
     * The resource bundle reference
     */
    private static final ResourceBundleHolder HOLDER = ResourceBundleHolder.get(Messages.class);

    private final String cloudName;
    private String namespace;

    @NonNull
    private String podTemplateId;

    private transient PodTemplate template;
    private transient Set<Queue.Executable> executables = new HashSet<>();

    @CheckForNull
    private transient Pod pod;

    @NonNull
    public PodTemplate getTemplate() throws IllegalStateException {
        // Look up updated pod template after a restart
        PodTemplate template = getTemplateOrNull();
        if (template == null) {
            throw new IllegalStateException("Unable to resolve pod template from id=" + podTemplateId);
        }
        return template;
    }

    @NonNull
    public String getTemplateId() {
        return podTemplateId;
    }

    @CheckForNull
    public PodTemplate getTemplateOrNull() {
        if (template == null) {
            template = getKubernetesCloud().getTemplateById(podTemplateId);
        }
        return template;
    }

    /**
     * Makes a best effort to find the build log corresponding to this agent.
     */
    @NonNull
    public TaskListener getRunListener() {
        PodTemplate podTemplate = getTemplateOrNull();
        if (podTemplate != null) {
            TaskListener listener = podTemplate.getListenerOrNull();
            if (listener != null) {
                return listener;
            }
        }
        Computer c = toComputer();
        if (c != null) {
            for (Executor executor : c.getExecutors()) {
                Queue.Executable executable = executor.getCurrentExecutable();
                // If this executor hosts a PlaceholderExecutable, send to the owning build log.
                if (executable != null) {
                    Queue.Executable parentExecutable = executable.getParentExecutable();
                    if (parentExecutable instanceof FlowExecutionOwner.Executable) {
                        FlowExecutionOwner flowExecutionOwner =
                                ((FlowExecutionOwner.Executable) parentExecutable).asFlowExecutionOwner();
                        if (flowExecutionOwner != null) {
                            try {
                                return flowExecutionOwner.getListener();
                            } catch (IOException x) {
                                LOGGER.log(Level.WARNING, null, x);
                            }
                        }
                    }
                }
                // TODO handle freestyle and similar if executable instanceof Run, by capturing a TaskListener from
                // RunListener.onStarted
            }
        }
        return TaskListener.NULL;
    }

    /**
     * @deprecated Use {@link Builder} instead.
     */
    @Deprecated
    public KubernetesSlave(PodTemplate template, String nodeDescription, KubernetesCloud cloud, String labelStr)
            throws Descriptor.FormException, IOException {

        this(template, nodeDescription, cloud.name, labelStr, new OnceRetentionStrategy(cloud.getRetentionTimeout()));
    }

    /**
     * @deprecated Use {@link Builder} instead.
     */
    @Deprecated
    public KubernetesSlave(PodTemplate template, String nodeDescription, KubernetesCloud cloud, Label label)
            throws Descriptor.FormException, IOException {
        this(
                template,
                nodeDescription,
                cloud.name,
                label.toString(),
                new OnceRetentionStrategy(cloud.getRetentionTimeout()));
    }

    /**
     * @deprecated Use {@link Builder} instead.
     */
    @Deprecated
    public KubernetesSlave(
            PodTemplate template, String nodeDescription, KubernetesCloud cloud, String labelStr, RetentionStrategy rs)
            throws Descriptor.FormException, IOException {
        this(template, nodeDescription, cloud.name, labelStr, rs);
    }

    /**
     * @deprecated Use {@link Builder} instead.
     */
    @Deprecated
    @DataBoundConstructor // make stapler happy. Not actually used.
    public KubernetesSlave(
            PodTemplate template, String nodeDescription, String cloudName, String labelStr, RetentionStrategy rs)
            throws Descriptor.FormException, IOException {
        this(getSlaveName(template), template, nodeDescription, cloudName, labelStr, new KubernetesLauncher(), rs);
    }

    protected KubernetesSlave(
            String name,
            @NonNull PodTemplate template,
            String nodeDescription,
            String cloudName,
            String labelStr,
            ComputerLauncher computerLauncher,
            RetentionStrategy rs)
            throws Descriptor.FormException, IOException {
        super(name, null, computerLauncher);
        setNodeDescription(nodeDescription);
        setNumExecutors(1);
        setMode(template.getNodeUsageMode() != null ? template.getNodeUsageMode() : Node.Mode.NORMAL);
        setLabelString(labelStr);
        setRetentionStrategy(rs);
        setNodeProperties(template.getNodeProperties());
        this.cloudName = cloudName;
        this.template = template;
        this.podTemplateId = template.getId();
    }

    public String getCloudName() {
        return cloudName;
    }

    public void setNamespace(@NonNull String namespace) {
        this.namespace = namespace;
    }

    @Nullable
    public String getNamespace() {
        return namespace;
    }

    public String getPodName() {
        return PodTemplateUtils.substituteEnv(getNodeName());
    }

    private String remoteFS;

    @SuppressFBWarnings(
            value = "NM_CONFUSING",
            justification = "Naming confusion with a getRemoteFs method, but the latter is deprecated.")
    @Override
    public String getRemoteFS() {
        if (remoteFS == null) {
            Optional<Pod> optionalPod = getPod();
            if (optionalPod.isPresent()) {
                Optional<Container> optionalJnlp = optionalPod.get().getSpec().getContainers().stream()
                        .filter(c -> JNLP_NAME.equals(c.getName()))
                        .findFirst();
                if (optionalJnlp.isPresent()) {
                    remoteFS = StringUtils.defaultIfBlank(
                            optionalJnlp.get().getWorkingDir(), ContainerTemplate.DEFAULT_WORKING_DIR);
                }
            }
        }
        return Util.fixNull(remoteFS);
    }

    // Copied from Slave#getRootPath because this uses the underlying field
    @CheckForNull
    @Override
    public FilePath getRootPath() {
        final SlaveComputer computer = getComputer();
        if (computer == null) {
            // if computer is null then channel is null and thus we were going to return null anyway
            return null;
        } else {
            return createPath(StringUtils.defaultString(computer.getAbsoluteRemoteFs(), getRemoteFS()));
        }
    }

    /**
     * @deprecated Please use the strongly typed getKubernetesCloud() instead.
     */
    @Deprecated
    public Cloud getCloud() {
        return Jenkins.getInstance().getCloud(getCloudName());
    }

    public Optional<Pod> getPod() {
        return Optional.ofNullable(pod);
    }

    /**
     * Returns the cloud instance which created this agent.
     * @return the cloud instance which created this agent.
     * @throws IllegalStateException if the cloud doesn't exist anymore, or is not a {@link KubernetesCloud}.
     */
    @NonNull
    public KubernetesCloud getKubernetesCloud() {
        return getKubernetesCloud(getCloudName());
    }

    private static KubernetesCloud getKubernetesCloud(String cloudName) {
        Cloud cloud = Jenkins.get().getCloud(cloudName);
        if (cloud instanceof KubernetesCloud) {
            return (KubernetesCloud) cloud;
        } else if (cloud == null) {
            throw new IllegalStateException("No such cloud " + cloudName);
        } else {
            throw new IllegalStateException(KubernetesSlave.class.getName() + " can be launched only by instances of "
                    + KubernetesCloud.class.getName() + ". Cloud is "
                    + cloud.getClass().getName());
        }
    }

    static String getSlaveName(PodTemplate template) {
        String randString = RandomStringUtils.random(5, "bcdfghjklmnpqrstvwxz0123456789");
        String name = template.getName();
        if (StringUtils.isEmpty(name)) {
            return String.format("%s-%s", DEFAULT_AGENT_PREFIX, randString);
        }
        // no spaces
        name = name.replaceAll("[ _]", "-").toLowerCase(Locale.getDefault());
        // keep it under 63 chars (62 is used to account for the '-')
        name = name.substring(0, Math.min(name.length(), 62 - randString.length()));
        String slaveName = String.format("%s-%s", name, randString);
        if (!slaveName.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*")) {
            return String.format("%s-%s", DEFAULT_AGENT_PREFIX, randString);
        }
        return slaveName;
    }

    @Override
    public KubernetesComputer createComputer() {
        return KubernetesComputerFactory.createInstance(this);
    }

    public PodRetention getPodRetention(KubernetesCloud cloud) {
        PodRetention retentionPolicy = cloud.getPodRetention();
        PodTemplate template = getTemplateOrNull();
        if (template != null) {
            PodRetention pr = template.getPodRetention();
            // https://issues.jenkins-ci.org/browse/JENKINS-53260
            // even though we default the pod template's retention
            // strategy, there are various legacy paths for injecting
            // pod templates where the
            // value can still be null, so check for it here so
            // as to not blow up termination path
            // if (pr != null) {
            retentionPolicy = pr;
            // } else {
            //    LOGGER.fine("Template pod retention policy was null");
            // }
        }
        return retentionPolicy;
    }

    @Override
    protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
        LOGGER.log(Level.INFO, "Terminating Kubernetes instance for agent {0}", name);

        KubernetesCloud cloud;
        try {
            cloud = getKubernetesCloud();
        } catch (IllegalStateException e) {
            e.printStackTrace(
                    listener.fatalError(
                            "Unable to terminate agent. Cloud may have been removed. There may be leftover resources on the Kubernetes cluster."));
            LOGGER.log(
                    Level.SEVERE,
                    String.format(
                            "Unable to terminate agent %s. Cloud may have been removed. There may be leftover resources on the Kubernetes cluster.",
                            name));
            return;
        }

        KubernetesClient client;
        try {
            client = cloud.connect();
        } catch (KubernetesAuthException | IOException e) {
            String msg = String.format(
                    "Failed to connect to cloud %s. There may be leftover resources on the Kubernetes cluster.",
                    getCloudName());
            e.printStackTrace(listener.fatalError(msg));
            LOGGER.log(Level.SEVERE, msg);
            return;
        }

        // Prior to termination, determine if we should delete the slave pod based on
        // the slave pod's current state and the pod retention policy.
        // Healthy slave pods should still have a JNLP agent running at this point.
        boolean deletePod = getPodRetention(cloud).shouldDeletePod(cloud, () -> client.pods()
                .inNamespace(getNamespace())
                .withName(name)
                .get());

        Computer computer = toComputer();
        if (computer == null) {
            String msg = String.format("Computer for agent is null: %s", name);
            LOGGER.log(Level.SEVERE, msg);
            listener.fatalError(msg);
            return;
        }

        // Tell the slave to stop JNLP reconnects.
        VirtualChannel ch = computer.getChannel();
        if (ch != null) {
            Future<Void> disconnectorFuture = ch.callAsync(new SlaveDisconnector());
            try {
                disconnectorFuture.get(DISCONNECTION_TIMEOUT, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                String msg = String.format(
                        "Ignoring error sending order to not reconnect agent %s: %s", name, e.getMessage());
                LOGGER.log(Level.INFO, msg, e);
            }
        }

        if (getCloudName() == null) {
            String msg = String.format("Cloud name is not set for agent, can't terminate: %s", name);
            LOGGER.log(Level.SEVERE, msg);
            listener.fatalError(msg);
            return;
        }

        if (deletePod) {
            deleteSlavePod(listener, client);
            Metrics.metricRegistry().counter(MetricNames.PODS_TERMINATED).inc();
        } else {
            // Log warning, as the agent pod may still be running
            LOGGER.log(Level.WARNING, "Agent pod {0} was not deleted due to retention policy {1}.", new Object[] {
                name, getPodRetention(cloud)
            });
        }
        String msg = String.format("Disconnected computer %s", name);
        LOGGER.log(Level.INFO, msg);
        listener.getLogger().println(msg);
    }

    private void deleteSlavePod(TaskListener listener, KubernetesClient client) {
        if (getNamespace() == null) {
            return;
        }
        try {
            boolean deleted = client.pods()
                            .inNamespace(getNamespace())
                            .withName(name)
                            .delete()
                            .size()
                    == 1;
            if (!deleted) {
                String msg = String.format("Failed to delete pod for agent %s/%s: not found", getNamespace(), name);
                LOGGER.log(Level.WARNING, msg);
                listener.error(msg);
                return;
            }
        } catch (KubernetesClientException e) {
            String msg =
                    String.format("Failed to delete pod for agent %s/%s: %s", getNamespace(), name, e.getMessage());
            LOGGER.log(Level.WARNING, msg, e);
            listener.error(msg);
            // TODO should perhaps retry later, in case API server is just overloaded currently
            return;
        }

        String msg = String.format("Terminated Kubernetes instance for agent %s/%s", getNamespace(), name);
        LOGGER.log(Level.INFO, msg);
        listener.getLogger().println(msg);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        KubernetesSlave that = (KubernetesSlave) o;
        return cloudName.equals(that.cloudName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), cloudName);
    }

    @Override
    public Launcher createLauncher(TaskListener listener) {
        Launcher launcher = super.createLauncher(listener);
        if (template != null) {
            Executor executor = Executor.currentExecutor();
            if (executor != null) {
                Queue.Executable currentExecutable = executor.getCurrentExecutable();
                if (currentExecutable != null && executables.add(currentExecutable)) {
                    listener.getLogger()
                            .println(Messages.KubernetesSlave_AgentIsProvisionedFromTemplate(
                                    ModelHyperlinkNote.encodeTo("/computer/" + getNodeName(), getNodeName()),
                                    template.getName()));
                    printAgentDescription(listener);
                    checkHomeAndWarnIfNeeded(listener);
                }
            }
        }
        return launcher;
    }

    void assignPod(@CheckForNull Pod pod) {
        this.pod = pod;
    }

    private void printAgentDescription(TaskListener listener) {
        if (pod != null && template.isShowRawYaml()) {
            listener.getLogger().println(podAsYaml());
        }
    }

    private String podAsYaml() {
        String x = Serialization.asYaml(pod);
        Computer computer = toComputer();
        if (computer instanceof SlaveComputer) {
            SlaveComputer sc = (SlaveComputer) computer;
            return x.replaceAll(sc.getJnlpMac(), "********");
        }
        return x;
    }

    private void checkHomeAndWarnIfNeeded(TaskListener listener) {
        try {
            Computer computer = toComputer();
            if (computer != null) {
                String home = computer.getEnvironment().get("HOME");
                if ("/".equals(home)) {
                    listener.getLogger().println(Messages.KubernetesSlave_HomeWarning());
                }
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace(listener.error("[WARNING] Unable to retrieve HOME environment variable"));
        }
    }

    @Override
    protected Object readResolve() {
        KubernetesSlave ks = (KubernetesSlave) super.readResolve();
        ks.executables = new HashSet<>();
        return ks;
    }

    /**
     * Returns a new {@link Builder} instance.
     * @return a new {@link Builder} instance.
     */
    public static Builder builder() {
        return new Builder();
    }

    public void annotateTtl(TaskListener listener) {
        try {
            var kubernetesCloud = getKubernetesCloud();
            Optional.ofNullable(kubernetesCloud.getGarbageCollection()).ifPresent(gc -> {
                var ns = getNamespace();
                var name = getPodName();
                var l = Instant.now();
                try {
                    kubernetesCloud
                            .connect()
                            .pods()
                            .inNamespace(ns)
                            .withName(name)
                            .patch("{\"metadata\":{\"annotations\":{\"" + GarbageCollection.ANNOTATION_LAST_REFRESH
                                    + "\":\"" + l.toEpochMilli() + "\"}}}");
                } catch (KubernetesAuthException e) {
                    e.printStackTrace(listener.error("Failed to authenticate to Kubernetes cluster"));
                } catch (IOException e) {
                    e.printStackTrace(listener.error("Failed to connect to Kubernetes cluster"));
                }
                listener.getLogger().println("Annotated agent pod " + ns + "/" + name + " with TTL");
                LOGGER.log(Level.FINE, () -> "Annotated agent pod " + ns + "/" + name + " with TTL");
                try {
                    save();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, e, () -> "Failed to save");
                }
            });
        } catch (RuntimeException e) {
            e.printStackTrace(listener.error("Failed to annotate agent pod with TTL"));
        }
    }

    /**
     * Builds a {@link KubernetesSlave} instance.
     */
    public static class Builder {
        private String name;
        private String nodeDescription;
        private PodTemplate podTemplate;
        private KubernetesCloud cloud;
        private String label;
        private ComputerLauncher computerLauncher;
        private RetentionStrategy retentionStrategy;

        /**
         * @param name The name of the future {@link KubernetesSlave}
         * @return the current instance for method chaining
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * @param nodeDescription The node description of the future {@link KubernetesSlave}
         * @return the current instance for method chaining
         */
        public Builder nodeDescription(String nodeDescription) {
            this.nodeDescription = nodeDescription;
            return this;
        }

        /**
         * @param podTemplate The pod template the future {@link KubernetesSlave} has been created from
         * @return the current instance for method chaining
         */
        public Builder podTemplate(PodTemplate podTemplate) {
            this.podTemplate = podTemplate;
            return this;
        }

        /**
         * @param cloud The cloud that is provisioning the {@link KubernetesSlave} instance.
         * @return the current instance for method chaining
         */
        public Builder cloud(KubernetesCloud cloud) {
            this.cloud = cloud;
            return this;
        }

        /**
         * @param label The label the {@link KubernetesSlave} has.
         * @return the current instance for method chaining
         */
        public Builder label(String label) {
            this.label = label;
            return this;
        }

        /**
         * @param computerLauncher The computer launcher to use to launch the {@link KubernetesSlave} instance.
         * @return the current instance for method chaining
         */
        public Builder computerLauncher(ComputerLauncher computerLauncher) {
            this.computerLauncher = computerLauncher;
            return this;
        }

        /**
         * @param retentionStrategy The retention strategy to use for the {@link KubernetesSlave} instance.
         * @return the current instance for method chaining
         */
        public Builder retentionStrategy(RetentionStrategy retentionStrategy) {
            this.retentionStrategy = retentionStrategy;
            return this;
        }

        private static RetentionStrategy determineRetentionStrategy(
                @NonNull KubernetesCloud cloud, @NonNull PodTemplate podTemplate) {
            if (podTemplate.getIdleMinutes() == 0) {
                return new OnceRetentionStrategy(cloud.getRetentionTimeout());
            } else {
                return new CloudRetentionStrategy(podTemplate.getIdleMinutes());
            }
        }

        /**
         * Builds the resulting {@link KubernetesSlave} instance.
         * @return an initialized {@link KubernetesSlave} instance.
         * @throws IOException
         * @throws Descriptor.FormException
         */
        @SuppressFBWarnings(
                value = "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR",
                justification = "False positive. https://github.com/spotbugs/spotbugs/issues/567")
        public KubernetesSlave build() throws IOException, Descriptor.FormException {
            Validate.notNull(podTemplate);
            Validate.notNull(cloud);
            return new KubernetesSlave(
                    name == null ? getSlaveName(podTemplate) : name,
                    podTemplate,
                    nodeDescription == null ? podTemplate.getName() : nodeDescription,
                    cloud.name,
                    label == null ? podTemplate.getLabel() : label,
                    decorateLauncher(
                            cloud,
                            computerLauncher == null
                                    ? new KubernetesLauncher(cloud.getJenkinsTunnel(), null)
                                    : computerLauncher),
                    retentionStrategy == null ? determineRetentionStrategy(cloud, podTemplate) : retentionStrategy);
        }

        private ComputerLauncher decorateLauncher(@NonNull KubernetesCloud cloud, @NonNull ComputerLauncher launcher) {
            if (launcher instanceof KubernetesLauncher) {
                ((KubernetesLauncher) launcher).setWebSocket(cloud.isWebSocket());
            }
            return launcher;
        }
    }

    @Extension
    public static final class DescriptorImpl extends SlaveDescriptor {

        @Override
        public String getDisplayName() {
            return "Kubernetes Agent";
        }

        @Override
        public boolean isInstantiable() {
            return false;
        }
    }

    private static class SlaveDisconnector extends MasterToSlaveCallable<Void, IOException> {

        private static final long serialVersionUID = 8683427258340193283L;

        private static final Logger LOGGER = Logger.getLogger(SlaveDisconnector.class.getName());

        @Override
        public Void call() throws IOException {
            Engine e = Engine.current();
            // No engine, do nothing.
            if (e == null) {
                return null;
            }
            // Tell the JNLP agent to not attempt further reconnects.
            e.setNoReconnect(true);
            LOGGER.log(Level.INFO, "Disabled agent engine reconnects.");
            return null;
        }
    }
}
