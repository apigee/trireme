# Trireme Servlet

This module contains a servlet that lets a Node.js app run inside a standard Servlet container.

The servlet is built using the servlet API 2.5, and uses a standard (aka not "async" servlet,
so it will be able to run in most modern containers.

For an example of a WAR that uses this servlet, see the [readme in the war module.](../samples/war/README.md)

## Configuration

Use the following steps to use the servlet:

1. Add the Trireme jars either to the app server's classpath, or put them into WEB-INF/lib. See below
for options on how to do this.
2. Create one or more servlets that run the class io.apigee.trireme.servlet.TriremeServlet
3. Copy your Node.js source (including all the "node_modules") into the servlet WAR.
4. Set the configuration parameters in web.xml to tell TriremeServlet where to find the script to run.
5. Deploy it!

## Script Requirements

The servlet works through the "http" module. In order to use it, write a server using the "http" module like
any other HTTP server. When the script calls "http.Server.listen," it will be available for new requests.
The "port" parameter to "listen" will be ignored.

## Adding Trireme Jars

The servlet engine will need the following JARs available in order to run the servlet:

* trireme-kernel
* trireme-core
* trireme-node10src
* trireme-servlet
* rhino
* slf4j
* logback-core

In addition, you should include the following optional ones.

* trireme-util
* trireme-crypto

For these jars, you have two choices: you can either include them in the app server's own classpath, or put them
in the "WEB-INF/lib" directory of the servlet itself.

If you make the jars shared (in the app server's classpath) then all the servlets on the server will share the same
NodeEnvironment. This can save on resources, as NodeEnvironment creates a thread pool for synchronous tasks
like file I/O and DNS lookups. However, either way works.

On the other hand, if you include all the dependencies in the WAR itself, then the servlet is self-contained
and can be deployed anywhere. The "war" sample in the "samples" directory does this automatically using Maven.

See the [main README](../README.md) for more information on which libraries to include.

## Node.js Versus Servlets

When using this module, be aware that Node.js and Servlets are different things. This module bridges the gap.
The Node.js script runs in a single thread, but never blocks. (Trireme uses asychronous I/O to avoid blocking,
and a thread pool to do things that must block so that the main script never blocks.)  Servlets, on the other hand,
use blocking I/O and a thread pool. (There are also asynchronous servlets, but that's another matter.)

In order to bridge this gap, this module does the following whenever a request arrives:

1. If the script is not running, then it starts the script, in a separate thread.
(The servlet may be configured, like any servlet, to do this when it is deployed, or when the first request arrives.)
2. If the servlet has already exited (normally, or because of a crash) then the servlet returns an error.
3. The servlet waits for the script to be running, and for it to call "http.Server.listen". If this does not
happen in "TriremeStartupTimeout" seconds (default 10) then it returns an error.
4. The servlet constructs the HTTP request object and populates it with the headers, method, and URI. It then
forwards the request to Trireme, which makes the appropriate callbacks in its thread like any Node script.
5. The servlet reads the request body, and feeds it in chunks to the script, which will deliver them on the
"Request" object like any other servlet.
6. Once the whole request body has been delivered, the servlet waits for the script to tell it that it has
data to send back in the response.
7. The servlet writes any response data it gets back to the servlet's output stream, and closes it when
all the data has arrived.
8. If any part of steps 6 or 7 takes more than "TriremeResponseTimeout" seconds, then the servlet returns
an error.
9. If at any point during steps 5 through 7 the script throws an exception, Trireme handles it via the
"domain" module and returns an error to the client.

## Configuration Parameters

The servlet is configured as a standard servlet, and uses "init parameters" for its configuration. The
following parameters are supported:

### TriremeScript (required)

The file name of the script to run, relative to the root directory of the WAR. (In other words, if the
script is in "WEB-INF/node/foo.js", then that's what should be here.) If there is a package.json file,
then a directory name works as well. Basically, imagine that when the servlet starts, it will
run "node TriremeScript" where "TriremeScript" is what you put in this parameter.

### TriremeResponseTimeout (optional)

If set, then the servlet will automatically return a 500 error if the script does not respond to a particular
HTTP request before the timeout. The timeout is in seconds. The default is no timeout.

### TriremeStartupTimeout (optional)

The number of seconds to wait for the script to start the first time. The default is 10, which means 10 seconds.
If the script does not start in that time, then HTTP requests will be rejected with a 500 error, although
the servlet will continue to wait for the script to start.

### TriremeSandbox (optional)

If this is set to "true" then Trireme is configured with a "Sandbox" that restricts what the Node script
can do. This is designed so that many servlets can be deployed to the same container and they will not
pose a security risk to each other. When enabled, this mode does the following:

* The script is restricted to the part of the filesystem where the WAR was exploded on the server.
The top level of the WAR will appear as "/" to the script, and everything will be relative to that.
The script will be able to read and write to directories that were inside the WAR, but nowhere else.
* The script may not use "net.Server" to listen for incoming TCP connections. (However, it must still use
"http.Server" to act as an HTTP server.
* The script may not spawn any child processes.
* The script may not use the "trireme-support" module to load additional modules from JAR files.

## Sample web.xml

    <?xml version="1.0" encoding="UTF-8"?>
    <web-app version="2.5" xmlns="http://java.sun.com/xml/ns/javaee"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://java.sun.com/xml/ns/javaee http://java.sun.com/xml/ns/javaee/web-app_2_5.xsd">
      <servlet>
        <servlet-name>WarSample</servlet-name>
        <!-- All servlets running Trireme must be of this class -->
        <servlet-class>io.apigee.trireme.servlet.TriremeServlet</servlet-class>
        <init-param>
          <!-- The name of the script file to run -->
          <param-name>TriremeScript</param-name>
          <param-value>/WEB-INF/node/server.js</param-value>
        </init-param>
        <init-param>
          <!-- The number of seconds to wait for the script to write response data -->
          <param-name>TriremeResponseTimeout</param-name>
          <param-value>5</param-value>
        </init-param>
        <init-param>
          <!-- The number of seconds to wait for the script to start -->
          <param-name>TriremeStartupTimeout</param-name>
          <param-value>10</param-value>
        </init-param>
        <init-param>
          <!-- If true, restrict file system, native code, subprocess, and network access -->
          <param-name>TriremeSandbox</param-name>
          <param-value>true</param-value>
        </init-param>
      </servlet>

      <servlet-mapping>
        <!-- Mapping. Be sure to use a wildcard here unless your Node script only has one URL -->
        <servlet-name>WarSample</servlet-name>
        <url-pattern>/servlet/*</url-pattern>
      </servlet-mapping>
    </web-app>