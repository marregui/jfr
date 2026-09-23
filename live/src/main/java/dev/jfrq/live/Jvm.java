// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.live;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;

import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import jdk.management.jfr.FlightRecorderMXBean;

/**
 * A connection to a running JVM's flight recorder. The attach API starts the JVM's local
 * management agent (the same thing {@code jcmd} and Mission Control do), and the recorder
 * is then driven through {@link FlightRecorderMXBean} over that local JMX connector.
 *
 * <p>Attaching needs the same user as the target, a target that was not started with
 * {@code -XX:+DisableAttachMechanism}, and a target runtime that carries
 * {@code jdk.management.agent} (every full JDK; a jlinked image may not).
 */
public final class Jvm implements AutoCloseable {

    private static final String FLIGHT_RECORDER = "jdk.management.jfr:type=FlightRecorder";
    private static final String RMI_HOSTNAME = "java.rmi.server.hostname";

    private final JMXConnector connector;
    private final FlightRecorderMXBean flightRecorder;
    private final String pid;
    private final RuntimeMXBean runtime;

    private Jvm(final String pid, final JMXConnector connector, final FlightRecorderMXBean flightRecorder, final RuntimeMXBean runtime) {
        this.pid = pid;
        this.connector = connector;
        this.flightRecorder = flightRecorder;
        this.runtime = runtime;
    }

    /** @throws IOException when the process does not exist, is not a JVM, or refuses attachment */
    public static Jvm attach(final String pid) throws IOException {
        // RMI names this side's endpoint by resolving the machine's own hostname, which on a Mac
        // whose name does not resolve costs five seconds per connection. The local connector
        // only ever talks over loopback, so the name is irrelevant; say so before RMI asks.
        if (System.getProperty(RMI_HOSTNAME) == null) {
            System.setProperty(RMI_HOSTNAME, "127.0.0.1");
        }
        VirtualMachine vm;
        try {
            vm = VirtualMachine.attach(pid);
        } catch (final AttachNotSupportedException | IOException e) {
            throw new IOException("cannot attach to " + pid + ": " + e.getMessage(), e);
        }
        String address;
        try {
            // Idempotent: a JVM whose agent is already running answers with the same address.
            address = vm.startLocalManagementAgent();
        } finally {
            vm.detach();
        }
        final JMXConnector connector = JMXConnectorFactory.connect(new JMXServiceURL(address));
        try {
            final MBeanServerConnection mbsc = connector.getMBeanServerConnection();
            final FlightRecorderMXBean fr = ManagementFactory.newPlatformMXBeanProxy(mbsc, FLIGHT_RECORDER,
                    FlightRecorderMXBean.class);
            final RuntimeMXBean runtime = ManagementFactory.newPlatformMXBeanProxy(mbsc,
                    ManagementFactory.RUNTIME_MXBEAN_NAME, RuntimeMXBean.class);
            return new Jvm(pid, connector, fr, runtime);
        } catch (final IOException | RuntimeException e) {
            connector.close();
            throw e;
        }
    }

    public FlightRecorderMXBean flightRecorder() {
        return flightRecorder;
    }

    public String pid() {
        return pid;
    }

    /** {@code pid@host}, as the JVM names itself. */
    public String name() {
        return runtime.getName();
    }

    /** Epoch milliseconds: with the pid, this identifies one JVM incarnation (pids are reused). */
    public long startTime() {
        return runtime.getStartTime();
    }

    public String vmVersion() {
        return runtime.getVmVersion();
    }

    @Override
    public void close() throws IOException {
        connector.close();
    }
}
