package net.elodina.mesos.hdfs;

import net.elodina.mesos.util.Constraint;
import net.elodina.mesos.util.IO;
import net.elodina.mesos.util.Period;
import net.elodina.mesos.util.Strings;
import org.apache.log4j.Logger;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;

public class HttpServer {
    private static final Logger logger = Logger.getLogger(HttpServer.class);

    private Server server;

    public void start() throws Exception {
        if (server != null) throw new IllegalStateException("started");
        Scheduler.Config config = Scheduler.$.config;

        QueuedThreadPool threadPool = new QueuedThreadPool(Runtime.getRuntime().availableProcessors() * 16);
        threadPool.setName("Jetty");

        server = new Server(threadPool);
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(config.apiPort());
        connector.setIdleTimeout(60 * 1000);

        ServletContextHandler handler = new ServletContextHandler();
        handler.addServlet(new ServletHolder(new Servlet()), "/");
        handler.setErrorHandler(new ErrorHandler());

        server.setHandler(handler);
        server.addConnector(connector);
        server.start();

        logger.info("started on port " + connector.getLocalPort());
    }

    public void stop() throws Exception {
        if (server == null) throw new IllegalStateException("!started");

        server.stop();
        server.join();
        server = null;

        logger.info("stopped");
    }

    private class Servlet extends HttpServlet {
        protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException { doGet(request, response); }

        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
            String url = request.getRequestURL() + (request.getQueryString() != null ? "?" + request.getQueryString() : "");
            logger.info("handling - " + url);

            try {
                handle(request, response);
                logger.info("finished handling");
            } catch (HttpError e) {
                response.sendError(e.getCode(), e.getMessage());
            } catch (Exception e) {
                logger.error("error handling", e);
                response.sendError(500, "" + e);
            }
        }

        private void handle(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
            String uri = request.getRequestURI();
            Scheduler.Config config = Scheduler.$.config;

            if (uri.equals("/health")) handleHealth(response);
            else if (uri.startsWith("/api/node")) handleNodeApi(request, response);
            else if (uri.startsWith("/jar/")) downloadFile(config.jar, response);
            else if (uri.startsWith("/hadoop/")) downloadFile(config.hadoop, response);
            else if (config.jre != null && uri.startsWith("/jre/")) downloadFile(config.jre, response);
            else throw new HttpError(404, "not found");
        }

        private void handleHealth(HttpServletResponse response) throws IOException {
            response.setContentType("text/plain; charset=utf-8");
            response.getWriter().println("ok");
        }

        private void handleNodeApi(HttpServletRequest request, HttpServletResponse response) throws IOException {
            String uri = request.getRequestURI();
            uri = uri.substring("/api/node".length());

            request.setAttribute("jsonResponse", true);
            response.setContentType("application/json; charset=utf-8");

            switch (uri) {
                case "/list": handleNodeList(request, response); break;
                case "/add": case "/update": handleNodeAddUpdate(request, response, uri.equals("/add")); break;
                case "/start": case "/stop": handleNodeStartStop(request, response, uri.equals("/start")); break;
                case "/remove": handleNodeRemove(request, response); break;
                default: throw new HttpError(404, "unsupported method " + uri);
            }
        }

        private void handleNodeList(HttpServletRequest request, HttpServletResponse response) throws IOException {
            String expr = "*";
            if (request.getParameter("node") != null) expr = request.getParameter("node");

            List<String> ids;
            try { ids = Nodes.expandExpr(expr); }
            catch (IllegalArgumentException e) { throw new HttpError(400, "invalid node"); }

            List<Node> nodes = Nodes.getNodes(ids);

            @SuppressWarnings("unchecked") List<JSONObject> nodesJson = new JSONArray();
            for (Node node : nodes) nodesJson.add(node.toJson());

            response.getWriter().println("" + nodesJson);
        }

        @SuppressWarnings("ConstantConditions")
        private void handleNodeAddUpdate(HttpServletRequest request, HttpServletResponse response, boolean add) throws IOException {
            String expr = request.getParameter("node");
            if (expr == null || expr.isEmpty()) throw new HttpError(400, "node required");

            List<String> ids;
            try { ids = Nodes.expandExpr(expr); }
            catch (IllegalArgumentException e) { throw new HttpError(400, "invalid node"); }

            for (String id : ids) {
                Node node = Nodes.getNode(id);
                if (add && node != null) throw new HttpError(400, "duplicate node");
                if (!add && node == null) throw new HttpError(400, "node not found");
                if (!add && node.state != Node.State.IDLE) throw new HttpError(400, "node not idle");
            }

            Node.Type type = null;
            if (add) {
                if (request.getParameter("type") == null) throw new HttpError(400, "type required");

                try { type = Node.Type.valueOf(request.getParameter("type").toUpperCase()); }
                catch (IllegalArgumentException e) { throw new HttpError(400, "invalid type"); }


            }

            Double cpus = null;
            if (request.getParameter("cpus") != null)
                try { cpus = Double.valueOf(request.getParameter("cpus")); }
                catch (IllegalArgumentException e) { throw new HttpError(400, "invalid cpus"); }

            Long mem = null;
            if (request.getParameter("mem") != null)
                try { mem = Long.valueOf(request.getParameter("mem")); }
                catch (IllegalArgumentException e) { throw new HttpError(400, "invalid mem"); }

            Map<String, Constraint> constraints = null;
            if (request.getParameter("constraints") != null) {
                constraints = new LinkedHashMap<>();
                Map<String, String> m = Strings.parseMap(request.getParameter("constraints"));
                for (String name : m.keySet())
                    try { constraints.put(name, new Constraint(m.get(name))); }
                    catch (IllegalArgumentException e) { throw new HttpError(400, "invalid constraint: " + e.getMessage()); }
            }

            String executorJvmOpts = request.getParameter("executorJvmOpts");
            String hadoopJvmOpts = request.getParameter("hadoopJvmOpts");

            String coreSiteOpts = request.getParameter("coreSiteOpts");
            try { Strings.parseMap(coreSiteOpts, false); }
            catch (IllegalArgumentException e) { throw new HttpError(400, "invalid coreSiteOpts"); }

            String hdfsSiteOpts = request.getParameter("hdfsSiteOpts");
            try { Strings.parseMap(hdfsSiteOpts, false); }
            catch (IllegalArgumentException e) { throw new HttpError(400, "invalid hdfsSiteOpts"); }

            String externalFsUri = request.getParameter("externalFsUri");

            Period failoverDelay = null;
            if (request.getParameter("failoverDelay") != null)
                try { failoverDelay = new Period(request.getParameter("failoverDelay")); }
                catch (IllegalArgumentException e) { throw new HttpError(400, "invalid failoverDelay"); }

            Period failoverMaxDelay = null;
            if (request.getParameter("failoverMaxDelay") != null)
                try { failoverMaxDelay = new Period(request.getParameter("failoverMaxDelay")); }
                catch (IllegalArgumentException e) { throw new HttpError(400, "invalid failoverMaxDelay"); }

            String failoverMaxTries = request.getParameter("failoverMaxTries");
            if (failoverMaxTries != null && !failoverMaxTries.equals("") && !Strings.isInteger(failoverMaxTries))
                throw new HttpError(400, "invalid failoverMaxTries");

            String stickinessHostname = null;
            if (request.getParameter("stickinessHostname") != null)
                try { stickinessHostname = new String(request.getParameter("stickinessHostname")); }
                catch (IllegalArgumentException e) { throw new HttpError(400, "invalid stickinessHostname"); }

            Boolean stickinessPersist = null;
            if (request.getParameter("stickinessPersist") != null)
                try { stickinessPersist =  Boolean.valueOf(request.getParameter("stickinessPersist")); }
                catch (IllegalArgumentException e) { throw new HttpError(400, "invalid stickinessPersist"); }

            List<Node> nodes = new ArrayList<>();
            for (String id : ids) {
                Node node;
                if (add) node = Nodes.addNode(new Node(id, type));
                else node = Nodes.getNode(id);

                nodes.add(node);

                if (cpus != null) node.cpus = cpus;
                if (mem != null) node.mem = mem;

                if (constraints != null) node.constraints = constraints;

                if (executorJvmOpts != null) node.executorJvmOpts = executorJvmOpts.equals("") ? null : executorJvmOpts;
                if (hadoopJvmOpts != null) node.hadoopJvmOpts = hadoopJvmOpts.equals("") ? null : hadoopJvmOpts;

                if (coreSiteOpts != null) node.coreSiteOpts = Strings.parseMap(coreSiteOpts);
                if (hdfsSiteOpts != null) node.hdfsSiteOpts = Strings.parseMap(hdfsSiteOpts);

                if (externalFsUri != null && node.type == Node.Type.NAMENODE)
                    node.externalFsUri = externalFsUri.equals("") ? null : externalFsUri;

                if (failoverDelay != null) node.failover.delay = failoverDelay;
                if (failoverMaxDelay != null) node.failover.maxDelay = failoverMaxDelay;
                if (failoverMaxTries != null) node.failover.maxTries = !failoverMaxTries.equals("") ? Integer.valueOf(failoverMaxTries) : null;
                if (stickinessHostname != null) node.stickiness.hostname = stickinessHostname.equals("") ? null : stickinessHostname;
                if (stickinessPersist != null) node.stickiness.persist = stickinessPersist;
            }
            Nodes.save();

            @SuppressWarnings("unchecked") List<JSONObject> nodesJson = new JSONArray();
            for (Node node : nodes) nodesJson.add(node.toJson());
            response.getWriter().println("" + nodesJson);
        }

        @SuppressWarnings("unchecked")
        private void handleNodeStartStop(HttpServletRequest request, HttpServletResponse response, boolean start) throws IOException {
            String expr = request.getParameter("node");
            if (expr == null || expr.isEmpty()) throw new HttpError(400, "node required");

            List<String> ids;
            try { ids = Nodes.expandExpr(expr); }
            catch (IllegalArgumentException e) { throw new HttpError(400, "invalid node"); }

            for (String id : ids) {
                Node node = Nodes.getNode(id);
                if (node == null) throw new HttpError(400, "node not found");
                if (start && node.state != Node.State.IDLE) throw new HttpError(400, "node not idle");
                if (!start && node.state == Node.State.IDLE) throw new HttpError(400, "node idle");
                if (node.isExternal()) throw new HttpError(400, "node external");
            }

            Period timeout = new Period("2m");
            if (request.getParameter("timeout") != null)
                try { timeout = new Period(request.getParameter("timeout")); }
                catch (IllegalArgumentException e) { throw new HttpError(400, "invalid timeout"); }


            boolean completed = true;
            boolean wait = timeout.ms() > 0;
            List<Node> nodes = Nodes.getNodes(ids);

            for (Node node : nodes) {
                node.failover.resetFailures();
                node.state = start ? Node.State.STARTING : Node.State.STOPPING;
                if (!start && node.runtime != null) node.runtime.killSent = false;

                Nodes.save();
                if (!wait) continue;

                try { completed = node.waitFor(start ? Node.State.RUNNING : Node.State.IDLE, timeout); }
                catch (InterruptedException e) { throw new IllegalStateException(e); }
                if (!completed) break;
            }

            String status = wait ? (completed ? (start ? "started": "stopped"): "timeout") : "scheduled";
            @SuppressWarnings("unchecked") List<JSONObject> nodesJson = (List<JSONObject>)new JSONArray();
            for (Node node : nodes) nodesJson.add(node.toJson());

            JSONObject json = new JSONObject();
            json.put("status", status);
            json.put("nodes", nodesJson);
            response.getWriter().write("" + json);
        }

        private void handleNodeRemove(HttpServletRequest request, HttpServletResponse response) throws IOException {
            String expr = request.getParameter("node");
            if (expr == null || expr.isEmpty()) throw new HttpError(400, "node required");

            List<String> ids;
            try { ids = Nodes.expandExpr(expr); }
            catch (IllegalArgumentException e) { throw new HttpError(400, "invalid node"); }

            if (ids.isEmpty()) throw new HttpError(400, "node not found");

            for (String id : ids) {
                Node node = Nodes.getNode(id);
                if (node == null) throw new HttpError(400, "node not found");
                if (node.state != Node.State.IDLE) throw new HttpError(400, "node not idle");
            }

            for (Node node : Nodes.getNodes(ids))
                Nodes.removeNode(node);
            Nodes.save();

            @SuppressWarnings("unchecked") List<String> json = new JSONArray();
            json.addAll(ids);
            response.getWriter().write("" + json);
        }

        private void downloadFile(File file, HttpServletResponse response) throws IOException {
            response.setContentType("application/zip");
            response.setHeader("Content-Length", "" + file.length());
            response.setHeader("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"");
            IO.copyAndClose(new FileInputStream(file), response.getOutputStream());
        }
    }

    private class ErrorHandler extends org.eclipse.jetty.server.handler.ErrorHandler {
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response_) throws IOException {
            Response response = (Response) response_;
            int code = response.getStatus();

            String error = response.getReason() != null ? response.getReason() : "";
            PrintWriter writer = response.getWriter();

            if (request.getAttribute("jsonResponse") != null) {
                response.setContentType("application/json; charset=utf-8");

                Map<String, Object> map = new HashMap<>();
                map.put("code", code);
                map.put("error", error);

                writer.println("" + new JSONObject(map));
            } else {
                response.setContentType("text/plain; charset=utf-8");
                writer.println(code + " - " + error);
            }

            writer.flush();
            baseRequest.setHandled(true);
        }
    }

    class HttpError extends RuntimeException {
        private int code;

        public HttpError(int code, String message) {
            super(message);
            this.code = code;
        }

        public int getCode() { return code; }
    }
}
