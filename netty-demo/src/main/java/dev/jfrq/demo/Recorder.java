package dev.jfrq.demo;

import java.io.IOException;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Duration;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;

/**
 * Starts an in-process JFR recording tuned for the questions jfrq asks. The tuning is
 * what you would pass on the command line for a real service; it is here so the demo
 * is one command:
 *
 * <pre>
 * -XX:StartFlightRecording=filename=app.jfr,settings=profile,\
 *   jdk.JavaMonitorEnter#threshold=1ms,jdk.ThreadPark#threshold=1ms,jdk.ThreadSleep#threshold=1ms,\
 *   jdk.SocketRead#threshold=1ms,jdk.SocketWrite#threshold=1ms,jdk.FileRead#threshold=1ms,\
 *   jdk.ExecutionSample#period=10ms,jdk.NativeMethodSample#period=10ms,\
 *   jdk.ObjectAllocationSample#throttle=1000/s
 * </pre>
 */
final class Recorder implements AutoCloseable {

    private final Recording recording;

    Recorder(Path destination) throws IOException, ParseException {
        recording = new Recording(Configuration.getConfiguration("profile"));
        recording.setName("jfrq-demo");
        Duration oneMs = Duration.ofMillis(1);
        for (String blocking : new String[] {"jdk.JavaMonitorEnter", "jdk.ThreadPark", "jdk.ThreadSleep",
                "jdk.JavaMonitorWait", "jdk.SocketRead", "jdk.SocketWrite", "jdk.FileRead", "jdk.FileWrite"}) {
            recording.enable(blocking).withThreshold(oneMs).withStackTrace();
        }
        recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
        recording.enable("jdk.NativeMethodSample").withPeriod(Duration.ofMillis(10));
        recording.enable("jdk.ObjectAllocationSample").with("throttle", "1000/s").withStackTrace();
        recording.enable("jdk.GCPhasePause").withThreshold(Duration.ZERO);
        recording.enable("jdk.SafepointBegin").withThreshold(Duration.ZERO);
        recording.enable("jdk.SafepointEnd").withThreshold(Duration.ZERO);
        recording.setDestination(destination);
        recording.setToDisk(true);
    }

    void start() {
        recording.start();
    }

    @Override
    public void close() {
        recording.stop();
        recording.close();
    }
}
