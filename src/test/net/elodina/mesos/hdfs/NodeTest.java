package net.elodina.mesos.hdfs;

import net.elodina.mesos.api.*;
import net.elodina.mesos.util.Constraint;
import net.elodina.mesos.util.Period;
import net.elodina.mesos.util.Range;
import net.elodina.mesos.util.Strings;
import org.junit.Test;

import java.util.*;

import static net.elodina.mesos.hdfs.Node.Failover;
import static net.elodina.mesos.hdfs.Node.Stickiness;
import static org.junit.Assert.*;

public class NodeTest extends HdfsMesosTestCase {
    @Test
    public void matches() {
        Node node = new Node("0", Node.Type.NAMENODE);
        node.cpus = 0.5;
        node.mem = 500;

        assertEquals("cpus < 0.5", node.matches(new Offer("resources:[cpus:0.1]")));
        assertEquals("mem < 500", node.matches(new Offer("resources:[cpus:0.5; mem:400]")));
    }

    @Test
    public void matches_namenode_state() {
        Node node = new Node("0", Node.Type.NAMENODE);
        node.cpus = 0.5;
        node.mem = 500;

        Offer offer = new Offer("id:1, frameworkId:2, slaveId:3, hostname:host, resources:[cpus:0.5; mem:500; ports:0..4]");
        assertNull(node.matches(offer));

        // no name node
        node.type = Node.Type.DATANODE;
        assertEquals("no namenode", node.matches(offer));

        // no running or external namenode
        Node nn = Nodes.addNode(new Node("nn", Node.Type.NAMENODE));
        assertEquals("no running or external namenode", node.matches(offer));

        // external namenode
        nn.externalFsUri = "fs-uri";
        assertNull(node.matches(offer));

        // running namenode
        nn.externalFsUri = null;
        nn.initRuntime(offer);
        nn.state = Node.State.RUNNING;
        assertNull(node.matches(offer));
    }

    @Test
    public void matches_constraints() {
        class O {
            Offer offer(String attributes) {
                return new Offer("id:id, frameworkId:fwId, slaveId:slaveId, hostname:host, resources:[cpus:2;mem:2048;ports:0..10]")
                    .attributes(Attribute.parse(attributes));
            }

            Map<String, Constraint> constraints(String s) {
                Map<String, Constraint> result = new HashMap<>();
                Map<String, String> m = Strings.parseMap(s);
                for (String name : m.keySet()) result.put(name, new Constraint(m.get(name)));
                return result;
            }

            Map<String, Collection<String>> otherAttributes(String s) {
                Map<String, Collection<String>> result = new HashMap<>();

                Map<String, String> m = Strings.parseMap(s);
                for (String name : m.keySet()) {
                    if (!result.containsKey(name)) result.put(name, new ArrayList<String>());
                    result.get(name).addAll(Arrays.asList(m.get(name).split(";")));
                }

                return result;
            }
        }
        O o = new O();

        Node node = Nodes.addNode(new Node("mm", Node.Type.NAMENODE));

        // like
        node.constraints = o.constraints("rack=like:1-.*");
        assertEquals(null, node.matches(o.offer("rack=1-1")));
        assertEquals(null, node.matches(o.offer("rack=1-2")));
        assertEquals("rack doesn't match like:1-.*", node.matches(o.offer("rack=2-1")));

        // groupBy
        node.constraints = o.constraints("rack=groupBy");
        assertEquals(null, node.matches(o.offer("rack=1")));
        assertEquals(null, node.matches(o.offer("rack=1"), o.otherAttributes("rack=1"), new Date()));
        assertEquals("rack doesn't match groupBy", node.matches(o.offer("rack=2"), o.otherAttributes("rack=1"), new Date()));
    }

    @Test
    public void matches_stickiness() {
        Node node = Nodes.addNode(new Node("nn"));
        String host0 = "host0";
        String host1 = "host1";
        String resources = "cpus:" + node.cpus + ";mem:" + node.mem + ";ports:0..10";

        Offer offer0 = new Offer("hostname:" + host0 + ", resources:[" + resources + "]");
        Offer offer1 = new Offer("hostname:" + host1 + ", resources:[" + resources + "]");

        assertEquals(null, node.matches(offer0, new Date(0)));
        assertEquals(null, node.matches(offer1, new Date(0)));

        node.registerStart(host0);
        node.registerStop(new Date(0), false);

        assertEquals(null, node.matches(offer0, new Date(0)));
        assertEquals("hostname != stickiness hostname", node.matches(offer1, new Date(0)));
        assertEquals(null, node.matches(offer1, new Date(node.stickiness.period.ms())));
    }

    @Test
    public void reserve() {
        Node node = new Node("0");
        node.cpus = 0.5;
        node.mem = 400;

        // incomplete reservation
        Node.Reservation reservation = node.reserve(new Offer("resources:[cpus:0.3;mem:300]"));
        assertEquals(0.3d, reservation.cpus, 0.001);
        assertEquals(300, reservation.mem);
        assertTrue("" + reservation.ports, reservation.ports.isEmpty());

        // complete reservation
        reservation = node.reserve(new Offer("resources:[cpus:0.7;mem:1000;ports:0..10]"));
        assertEquals(node.cpus, reservation.cpus, 0.001);
        assertEquals(node.mem, reservation.mem);
        assertEquals(2, reservation.ports.size());
        assertEquals(new Integer(0), reservation.ports.get(Node.Port.HTTP));
        assertEquals(new Integer(1), reservation.ports.get(Node.Port.IPC));
    }

    @Test
    public void reservePort() {
        Node node = new Node("0");
        List<Range> ports = new ArrayList<>();
        ports.add(new Range("0..100"));

        assertEquals(10, node.reservePort(new Range("10..20"), ports));
        assertEquals(Arrays.asList(new Range("0..9"), new Range("11..100")), ports);

        assertEquals(0, node.reservePort(new Range("0..0"), ports));
        assertEquals(Arrays.asList(new Range("1..9"), new Range("11..100")), ports);

        assertEquals(100, node.reservePort(new Range("100..200"), ports));
        assertEquals(Arrays.asList(new Range("1..9"), new Range("11..99")), ports);

        assertEquals(50, node.reservePort(new Range("50..60"), ports));
        assertEquals(Arrays.asList(new Range("1..9"), new Range("11..49"), new Range("51..99")), ports);
    }

    @Test
    public void initRuntime() {
        Node node = Nodes.addNode(new Node("0"));
        node.cpus = 0.1;
        node.mem = 100;

        Offer offer = new Offer("id:id, frameworkId:fwId, slaveId:slaveId, hostname:host, resources:[cpus:2;mem:1024;ports:0..10], attributes:[a=1,b=2]");
        node.initRuntime(offer);

        assertNotNull(node.runtime);
        assertNotNull(node.runtime.taskId);
        assertNotNull(node.runtime.executorId);
        assertNotNull(node.runtime.fsUri);

        assertEquals(offer.slaveId(), node.runtime.slaveId);
        assertEquals(offer.hostname(), node.runtime.hostname);
        assertEquals(Strings.parseMap("a=1,b=2"), node.runtime.attributes);

        assertNotNull(node.reservation);
        assertEquals(0.1, node.reservation.cpus, 0.001);
        assertEquals(100, node.reservation.mem);
    }

    @Test
    public void initRuntime_fsUri() {
        Node node = Nodes.addNode(new Node("0", Node.Type.NAMENODE));

        // name node
        Offer offer = new Offer("hostname:master, resources:[ports:0..10]");
        node.initRuntime(offer);
        assertTrue(node.runtime.fsUri, node.runtime.fsUri.contains(offer.hostname()));

        // data node, no name node
        node.type = Node.Type.DATANODE;
        try { node.initRuntime(offer); fail(); }
        catch (IllegalStateException e) { assertTrue(e.getMessage(), e.getMessage().contains("no namenode")); }

        // data node, running name node
        Node nn = Nodes.addNode(new Node("1", Node.Type.NAMENODE));
        nn.initRuntime(offer);
        node.initRuntime(offer);
        assertTrue(node.runtime.fsUri, node.runtime.fsUri.contains(nn.runtime.hostname));

        // data node, external name node
        nn.runtime = null;
        nn.externalFsUri = "fs-uri";
        node.initRuntime(offer);
        assertEquals(nn.externalFsUri, node.runtime.fsUri);
    }

    @Test
    public void newTask() {
        Node node = Nodes.addNode(new Node("0"));
        node.initRuntime(new Offer("resources:[ports:0..10]"));

        Task task = node.newTask();
        assertEquals(task.id(), node.runtime.taskId);
        assertEquals("hdfs-" + node.id, task.name());
        assertEquals(task.slaveId(), node.runtime.slaveId);

        assertNotNull(task.executor());
        assertEquals("" + node.toJson(), new String(task.data()));
        assertEquals(node.reservation.toResources(), task.resources());
    }

    @Test
    public void newExecutor() {
        Node node = Nodes.addNode(new Node("0"));
        node.executorJvmOpts = "-Xmx100m";
        node.initRuntime(new Offer("resources:[ports:0..10]"));

        Task.Executor executor = node.newExecutor();
        assertEquals("hdfs-" + node.id, executor.name());
        assertEquals(node.runtime.executorId, executor.id());

        // uris
        Command command = executor.command();
        assertEquals(2, command.uris().size());

        String uri = command.uris().get(0).value();
        assertTrue(uri, uri.contains(Scheduler.$.config.jar.getName()));
        uri = command.uris().get(1).value();
        assertTrue(uri, uri.contains(Scheduler.$.config.hadoop.getName()));

        // cmd
        String cmd = command.value();
        assertTrue(cmd, cmd.contains("java"));
        assertTrue(cmd, cmd.contains(node.executorJvmOpts));
        assertTrue(cmd, cmd.contains(Executor.class.getName()));
    }

    @Test
    public void toJson_fromJson() {
        Node node = Nodes.addNode(new Node("node"));
        node.type = Node.Type.NAMENODE;
        node.state = Node.State.RUNNING;

        node.cpus = 2;
        node.mem = 1024;

        node.constraints.put("hostname", new Constraint("like:master"));
        node.constraints.put("a", new Constraint("like:1"));

        node.executorJvmOpts = "executor-opts";
        node.executorJvmOpts = "hadoop-opts";
        node.coreSiteOpts.put("a", "1");
        node.hdfsSiteOpts.put("b", "2");

        node.externalFsUri = "external-fs-uri";

        node.initRuntime(new Offer());
        node.stickiness.registerStart("hostname");
        node.failover.failures = 5;

        Node read = new Node(node.toJson());
        assertEquals(node.id, read.id);
        assertEquals(node.type, read.type);
        assertEquals(node.state, read.state);

        assertEquals(node.cpus, read.cpus, 0.001);
        assertEquals(node.mem, read.mem);

        assertEquals(node.constraints, read.constraints);

        assertEquals(node.executorJvmOpts, read.executorJvmOpts);
        assertEquals(node.hadoopJvmOpts, read.hadoopJvmOpts);
        assertEquals(node.coreSiteOpts, read.coreSiteOpts);
        assertEquals(node.hdfsSiteOpts, read.hdfsSiteOpts);

        assertEquals(node.externalFsUri, read.externalFsUri);

        assertEquals(node.stickiness.hostname, read.stickiness.hostname);
        assertEquals(node.failover.failures, read.failover.failures);
        assertNotNull(read.runtime);
        assertNotNull(read.reservation);
    }

    // Runtime
    @Test
    public void Runtime_toJson_fromJson() {
        Node.Runtime runtime = new Node.Runtime();
        runtime.slaveId = "slaveId";
        runtime.hostname = "hostname";
        runtime.attributes.putAll(Strings.parseMap("a=1,b=2"));

        runtime.fsUri = "hdfs://localhost:31000";
        runtime.killSent = true;

        Node.Runtime read = new Node.Runtime(runtime.toJson());
        assertEquals(runtime.taskId, read.taskId);
        assertEquals(runtime.executorId, read.executorId);

        assertEquals(runtime.slaveId, read.slaveId);
        assertEquals(runtime.hostname, read.hostname);
        assertEquals(runtime.attributes, read.attributes);

        assertEquals(runtime.fsUri, read.fsUri);
        assertEquals(runtime.killSent, read.killSent);
    }

    // Reservation
    @Test
    public void Reservation_toJson_fromJson() {
        Node.Reservation reservation = new Node.Reservation();
        reservation.cpus = 0.5;
        reservation.mem = 256;
        reservation.ports.put(Node.Port.HTTP, 10);
        reservation.ports.put(Node.Port.IPC, 20);

        Node.Reservation read = new Node.Reservation(reservation.toJson());
        assertEquals(reservation.cpus, read.cpus, 0.001);
        assertEquals(reservation.mem, read.mem);
        assertEquals(reservation.ports, read.ports);
    }

    @Test
    public void Reservation_toResources() {
        assertEquals(Resource.parse(""), new Node.Reservation().toResources());
        assertEquals(Resource.parse("cpus:0.5;mem:500;ports:1000..1000"), new Node.Reservation(0.5, 500, Collections.singletonMap("ipc", 1000)).toResources());
    }

    // Stickiness
    @Test
    public void Stickiness_allowsHostname() {
        Stickiness stickiness = new Stickiness();
        assertTrue(stickiness.allowsHostname("host0", new Date(0)));
        assertTrue(stickiness.allowsHostname("host1", new Date(0)));

        stickiness.registerStart("host0");
        stickiness.registerStop(new Date(0));
        assertTrue(stickiness.allowsHostname("host0", new Date(0)));
        assertFalse(stickiness.allowsHostname("host1", new Date(0)));
        assertTrue(stickiness.allowsHostname("host1", new Date(stickiness.period.ms())));
    }

    @Test
    public void Stickiness_registerStart_registerStop() {
        Stickiness stickiness = new Stickiness();
        assertNull(stickiness.hostname);
        assertNull(stickiness.stopTime);

        stickiness.registerStart("host");
        assertEquals("host", stickiness.hostname);
        assertNull(stickiness.stopTime);

        stickiness.registerStop(new Date(0));
        assertEquals("host", stickiness.hostname);
        assertEquals(new Date(0), stickiness.stopTime);

        stickiness.registerStart("host1");
        assertEquals("host1", stickiness.hostname);
        assertNull(stickiness.stopTime);
    }

    @Test
    public void Stickiness_toJson_fromJson() {
        Stickiness stickiness = new Stickiness();
        stickiness.registerStart("localhost");
        stickiness.registerStop(new Date(0));

        Stickiness read = new Stickiness();
        read.fromJson(stickiness.toJson());

        assertEquals(stickiness.period, read.period);
        assertEquals(stickiness.hostname, read.hostname);
        assertEquals(stickiness.stopTime, read.stopTime);
    }

    // Failover
    @Test
    public void Failover_currentDelay() {
        Failover failover = new Failover(new Period("1s"), new Period("5s"));

        failover.failures = 0;
        assertEquals(new Period("0s"), failover.currentDelay());

        failover.failures = 1;
        assertEquals(new Period("1s"), failover.currentDelay());

        failover.failures = 2;
        assertEquals(new Period("2s"), failover.currentDelay());

        failover.failures = 3;
        assertEquals(new Period("4s"), failover.currentDelay());

        failover.failures = 4;
        assertEquals(new Period("5s"), failover.currentDelay());

        failover.failures = 32;
        assertEquals(new Period("5s"), failover.currentDelay());

        failover.failures = 33;
        assertEquals(new Period("5s"), failover.currentDelay());

        failover.failures = 100;
        assertEquals(new Period("5s"), failover.currentDelay());

        // multiplier boundary
        failover.maxDelay = new Period(Integer.MAX_VALUE + "s");

        failover.failures = 30;
        assertEquals(new Period((1 << 29) + "s"), failover.currentDelay());

        failover.failures = 31;
        assertEquals(new Period((1 << 30) + "s"), failover.currentDelay());

        failover.failures = 32;
        assertEquals(new Period((1 << 30) + "s"), failover.currentDelay());

        failover.failures = 100;
        assertEquals(new Period((1 << 30) + "s"), failover.currentDelay());
    }

    @Test
    public void Failover_delayExpires() {
        Failover failover = new Failover(new Period("1s"), new Period("5s"));
        assertEquals(new Date(0), failover.delayExpires());

        failover.registerFailure(new Date(0));
        assertEquals(new Date(1000), failover.delayExpires());

        failover.failureTime = new Date(1000);
        assertEquals(new Date(2000), failover.delayExpires());
    }

    @Test
    public void Failover_isWaitingDelay() {
        Failover failover = new Failover(new Period("1s"), new Period("5s"));
        assertFalse(failover.isWaitingDelay(new Date(0)));

        failover.registerFailure(new Date(0));

        assertTrue(failover.isWaitingDelay(new Date(0)));
        assertTrue(failover.isWaitingDelay(new Date(500)));
        assertTrue(failover.isWaitingDelay(new Date(999)));
        assertFalse(failover.isWaitingDelay(new Date(1000)));
    }

    @Test
    public void Failover_isMaxTriesExceeded() {
        Failover failover = new Failover();

        failover.failures = 100;
        assertFalse(failover.isMaxTriesExceeded());

        failover.maxTries = 50;
        assertTrue(failover.isMaxTriesExceeded());
    }

    @Test
    public void Failover_registerFailure_resetFailures() {
        Failover failover = new Failover();
        assertEquals(0, failover.failures);
        assertNull(failover.failureTime);

        failover.registerFailure(new Date(1));
        assertEquals(1, failover.failures);
        assertEquals(new Date(1), failover.failureTime);

        failover.registerFailure(new Date(2));
        assertEquals(2, failover.failures);
        assertEquals(new Date(2), failover.failureTime);

        failover.resetFailures();
        assertEquals(0, failover.failures);
        assertNull(failover.failureTime);

        failover.registerFailure(new Date(0));
        assertEquals(1, failover.failures);
    }

    @Test
    public void Failover_toJson_fromJson() {
        Failover failover = new Failover(new Period("1s"), new Period("5s"));
        failover.maxTries = 10;
        failover.registerFailure(new Date(0));

        Failover read = new Failover(failover.toJson());
        assertEquals(failover.delay, read.delay);
        assertEquals(failover.maxDelay, read.maxDelay);
        assertEquals(failover.maxTries, read.maxTries);

        assertEquals(failover.failures, read.failures);
        assertEquals(failover.failureTime, read.failureTime);
    }
}
