# Trireme

This is a set of libraries for running node.js scripts inside Java.

## What is Trireme?

Trireme runs Node.js scripts inside the JVM.
This is important because there is a lot of software out there (including our own) that is built in Java and
isn't going to get rewritten in JavaScript now or in the future.

Trireme is specifically designed to be embeddable within any Java program. There is a lot of support inside
Trireme for this specific case:

* Many Node.js scripts can run inside a single JVM, subject to memory constraints.
* Each script is totally isolated from the others -- there is no way for one script to affect the heap of
the others.
* A sandbox is provided that lets the container control how, or if, the script gains access to the filesystem
and to the network.
* The HTTP server implementation is pluggable. An "HTTP Adapter" is supported that allows a container to
embed a Node.js script inside an existing HTTP container like a web server or other product. (A sample
adapter, built using Netty 4.0, is included.)
* The sandbox supports a Rhino feature that makes it possible to limit the execution time for a script.
With this feature enabled, a script that runs an infinite loop can be terminated after some time.

For a more detailed introduction, see our intro presentation:

* [Introduction to Trireme](./docs/trireme-intro.pdf)

## So, again, why would I use Trireme?

* To embed Node.js apps inside an existing Java application
* To run Node.js apps that take advantage of Java libraries you can't live without, like JDBC drivers and XML parsers

If neither of those reasons apply to you, then stick with "regular node!"

## How do I get it?

### From NPM

    sudo npm install -g trireme
    trireme -h
    trireme <your script name here>

The NPM package for Trireme lets you run it on the command line just like "node".

Unfortunately, Trireme does not support the "repl" yet (and it's hard since Java gives us limited control over
the TTY) so just running "trireme" with no arguments produces an error right now.

### From Maven Central

The best reason to use Trireme is because it's important to embed Node.js code inside an existing Java application.
In that case you will use the modules under "io.apigee.trireme" on Maven Central:

[io.apigee.trireme](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22io.apigee.trireme%22)

The "module map" later in this document shows which modules to use in which cases.

### From GitHub

See [the releases page](https://github.com/apigee/trireme/releases) to download the latest release files.

"trireme-x.y.z.jar" is always a stand-alone jar that you can run just like "node":

    java -jar trireme-x.y.z.jar script.js

## What version of Node.js does Trireme Support?

Trireme is currently based on Node.js 0.10. We are also working on ways to support multiple versions of the Node.js
runtime inside the same JVM.

## Running Trireme

### Using NPM

If you installed Trireme using NPM, just run:

    trireme <script name>

Trireme will execute your script just like Node.

In addition, the environment variable TRIREME_CLASSPATH may be used to add extra JARs or directories to the
classpath used to run Trireme. Anything on this path will be appended to the classpath used to
launch Trireme. This allows you to add JDBC drivers, etc.

### Using Java

The "jar" module builds a self-contained JAR that may be used to launch Trireme on the command
line just like regular Node.js:

    mvn install
    java -jar jar/target/trireme.X.Y.Z.jar <script name>

(and with no arguments it will launch the "repl" but that implementation is not complete)

## Embedding Trireme as a servlet

The [war](./samples/war/README.md) sample is a sample that shows how to assemble a Node.js application into
a WAR file. It uses the [trireme-servlet](./servlet/README.md) module to link the servlet to the Node.js script.
Any script that operates as an HTTP server using the "http" module can be embedded in this way.

## Embedding Trireme Anywhere Else that Java Runs

There is JavaDoc for the "NodeEnvironment" and "NodeScript" classes, and many other features.
Here are the basics:

    import org.apigee.trireme.core.NodeEnvironment;
    import org.apigee.trireme.core.NodeScript;

    // The NodeEnvironment controls the environment for many scripts
    NodeEnvironment env = new NodeEnvironment();

    // Pass in the script file name, a File pointing to the actual script, and an Object[] containg "argv"
    NodeScript script = env.createScript("my-test-script.js",
                                         new File("my-test-script.js"), null);

    // Wait for the script to complete
    ScriptStatus status = script.execute().get();

    // Check the exit code
    System.exit(status.getExitCode());

## Trireme Extensions

There are a few NPM modules that only work in Trireme. These allow access to
features of the Java platform that are normally accessed via native code in regular
Node.js. These modules are as follows:

* [trireme-jdbc](https://www.npmjs.org/package/trireme-jdbc): This module provides
access to JDBC drivers from inside Node.js code. This makes it possible to use
databases that have excellent JDBC drivers (such as Oracle) without compiling
any native code.
* [trireme-xslt](https://www.npmjs.org/package/trireme-xslt): This module provides
access to the XSLT processor inside your Java platform, which is faster and
can support more of the XSLT standard than libxslt.

## How Complete is Trireme?

Trireme supports most of the Node.js APIs and passes much of the Node.js test suite.

The table below shows each module and its status. "Complete" means that a module is functionally complete,
although it may not necessarily pass all the node.js tests.

<table>
  <tr><td><b>Module</b></td><td><b>Status</b></td><td><b>Source</b></td></tr>
  <tr><td>assert</td><td>Complete</td><td>node.js</td></tr>
  <tr><td>child_process</td><td>Partial</td><td>Trireme</td></tr>
  <tr><td>cluster</td><td>Not Implemented Yet</td><td>node.js</td></tr>
  <tr><td>console</td><td>Complete</td><td>node.js</td></tr>
  <tr><td>crypto</td><td>Complete</td><td>node.js + Trireme</td></tr>
  <tr><td>debugger</td><td>Not Supported</td><td><NA/td></tr>
  <tr><td>dgram</td><td>Complete</td><td>node.js + Trireme</td></tr>
  <tr><td>dns</td><td>Partial</td><td>Trireme</td></tr>
  <tr><td>domain</td><td>Complete</td><td>node.js + Trireme</td></tr>
  <tr><td>events</td><td>Complete</td><td>node.js</td></tr>
  <tr><td>fs</td><td>Complete</td><td>node.js + Trireme</td></tr>
  <tr><td>globals</td><td>Complete</td><td>node.js + Trireme</td></tr>
  <tr><td>http</td><td>Complete</td><td>node.js + Trireme</td></tr>
  <tr><td>https</td><td>Complete but See Notes</td><td>Trireme</td></tr>
  <tr><td>module</td><td>Complete</td><td>node.js</td></tr>
  <tr><td>net</td><td>Complete</td><td>node.js + Trireme</td></tr>
  <tr><td>os</td><td>Partial</td><td>Trireme</td></tr>
  <tr><td>path</td><td>Complete</td><td>node.js</td></tr>
  <tr><td>process</td><td>Complete</td><td>Trireme</td></tr>
  <tr><td>punycode</td><td>Complete</td><td>node.js</td></tr>
  <tr><td>querystring</td><td>Complete</td><td>node.js</td></tr>
  <tr><td>readline</td><td>Partial</td><td>node.js</td></tr>
  <tr><td>repl</td><td>Not Supported</td><td>node.js</td></tr>
  <tr><td>stream</td><td>Complete</td><td>node.js</td></tr>
  <tr><td>string_decoder</td><td>Complete</td><td>node.js</td></tr>
  <tr><td>timers</td><td>Complete</td><td>node.js + Trireme</td></tr>
  <tr><td>tls</td><td>Complete but See Notes</td><td>Trireme</td></tr>
  <tr><td>tty</td><td>Complete</td><td>Trireme</td></tr>
  <tr><td>url</td><td>Complete</td><td>node.js</td></tr>
  <tr><td>util</td><td>Complete</td><td>node.js</td></tr>
  <tr><td>vm</td><td>Complete</td><td>Trireme</td></tr>
  <tr><td>zlib</td><td>Complete</td><td>Trireme</td></tr>
</table>

## What are the Major Differences with "real" node.js?

A few of the modules are different, some in major ways:

### JavaScript Language

Trireme runs in the JVM on Rhino, which is the most complete JavaScript implementation for
the JVM. Rhino currently implements version 1.8 of JavaScript, which means that a few things supported
in later versions of JavaScript, such as the "trimLeft" method of the "String" object, are not supported.

Also, newer features of V8, particularly the primitive array types, are not supported in Rhino.

Most of the time the differences between V8 and Rhino do not affect Node.js code, but occasionally
there is a problem. We would love some help from the Rhino community to start to address these differences.

### TLS/SSL and HTTPS

Trireme uses Java's standard "SSLEngine" for TLS/SSL and HTTPS support, whereas standard Node.js uses
OpenSSL. The TLS implementation in Node.js is a fairly thin layer on top of OpenSSL and we chose not to try
and replicate this in Java.

For the most part, TLS and HTTPS in Trireme will work just like they do in Node.js. However, they SSLEngine and
OpenSSL are not exactly the same. There are a few differences:

1) Most notably, especially with Java 7, SSLEngine supports
a different set of cipher suites, particularly the various elliptical curve ciphers. There are ciphers in common
(otherwise almost everything will break) but there are many that are not. Many Node.js tests that rely on
older cipher suites using DES or RC4 will not run on Trireme because many of these older and weaker
cipher suites are disabled by default in Java. However, "OpenSSL style" names work in Trireme just as they
do in regular Node and if the JVM supports a particular cipher suite from OpenSSL, you will get the same one
in Trireme.

2) Java handles SSL sessions differently, and gives the user less control about it. Right now, Trireme
is unable to support the ability of a TLS or HTTPS client to retrieve the session from an existing connection
and re-use it for another TCP connection.

3) Java also will produce different certificate validation errors than OpenSSL does. The errors will still
come in the same places and for the same reasons, but if your code depends on a specific error message,
it will likely get a different one.

4) Java's SSLEngine relies on its own "keystore" files, whereas OpenSSL can operate on a variety
of files but typically processes PEM files.
Trireme handles this disparity by using the "Bouncy Castle" crypto framework to translate PEM files into
keys and certificates that SSLEngine can understand. In addition, you can also use regular Java keystore files,
as described below.

In order to support TLS and HTTPS using PEM files, the "trireme-crypto" module and its dependencies
(Bouncy Castle) must be in the class path. If they are not present, then TLS is still available, but it will
only work with Java keystore files (see below) or without using any keys at all. Trireme checks for this
dependency at runtime, so it is simply a matter of including it on the class path, since it will fail
at runtime if the dependency is needed, and work otherwise.

(For instance, Trireme can still execute a Node program that acts as an HTTPS client using only default
certificates without requiring trireme-crypto. But if it needs to validate a particular CA certificate
or if it needs to use a client-side certificate then trireme-crypto is also necessary.)

In addition, the TLS and HTTPS-related methods in Trireme can use a Java keystore instead of PEM files.
There are three parameters that are relevant here:

* keystore: The file name of a Java ".jks" keystore file containing a key and certificate
* truststore: The file name of a Java ".jks" keystore file containing trusted CA certificates
* passphrase: The passphrase for the keystore and truststore

The corresponding Trireme script may be written like this, as it would be in any Node.js program. However,
if the "trireme-crypto" module is not present in the classpath, then this will raise an exception:

    var options = {
      key: fs.readFileSync(common.fixturesDir + '/keys/agent1-key.pem'),
      cert: fs.readFileSync(common.fixturesDir + '/keys/agent1-cert.pem')
    };

    var server = https.createServer(options, function(req, res) {
      console.log('got request');
    });

In addition, the following is also valid, and "trireme-crypto" will not be needed:

    var options = {
      keystore: common.fixturesDir + '/keys/agent1.jks',
      passphrase: 'secure'
    };

    var server = https.createServer(options, function(req, res) {
      console.log('got request');
    });

### Crypto

Like TLS, certain features (Sign/Verify in particular) only work if the "trireme-crypto" module and its
dependencies are in the class path. If they are not present then these methods will throw an exception.
This is primarily because the trireme-crypto module uses Bouncy Castle to implement PEM file reading
and decryption.

### Child Process

Child processes are supported. Arbitrary commands may be executed, just like in standard Node.js. The Sandbox
may be used to restrict whether particular commands may be executed, or if none should be executed at all.

When a Trireme script uses "fork" to spawn a new instance of itself, the script runs as a separate
thread inside the same JVM, rather than as a separate OS process as it works in regular Node.js. The parent
may use "send" on the child process to send messages to the child, and the child can use "process.send"
to talk back to the parent. This "IPC" mechanism works just like regular Node.js except that it all happens
inside the same JVM using a concurrent queue.

Support for "handles" is not currently implemented, however, so a parent may not send a TCP socket to the child
and expect the child to be able to handle it.

Some Node.js scripts rely on the ability to spawn a process called "./node" in order to fork itself. Trireme
looks for this and tries to use it to spawn a new thread but it does not work in all cases. It does seem to
be mostly the Node.js test suite itself that does this.

### Cluster

The "cluster" module is not yet supported. When it is, it will support running multiple scripts within a
single JVM, like the child process module works as described above.

### Filesystem

The filesystem is fairly complete, but remember that Java is an abstraction on top of the OS so it may not
behave exactly the same as it does on Linux.

On Java 6, the filesystem implementation falls back to using only the APIs supported in this version of Java,
which means that many things like symbolic links are not supported, and support for "chmod" and the like is
not exactly the same as in standard Node.js. On Java 7, Trireme is able to use additional features and
the filesystem support is much more complete.

Programs that make extensive use of the filesystem, such as NPM, work on Java 7 but we cannot guarantee
that they will work on Java 6.

### OS

Again, Trireme runs on top of the JVM, which presents an operating-system abstraction. Things that Node.js
programs can do like set up signal handlers and the like are not supported.

## How Fast is It?

Rhino on the JVM is much slower than V8. (In some benchmarks it is 50 times slower.) However, Node.js programs
take advantage of a lot of native code, especially when HTTP and TLS are used, so Trireme generally
fares much better.

In general, we have seen simple HTTP benchmarks run at about one-half the speed of the same programs on
standard Node.js. Some things are slower than that, and others are faster -- it all depends, as it does with
all benchmarks.

Furthermore, Java is notoriously slow to start up, and this especially hurts Trireme when it's used to run
command-line tools. So please try it as a long-running server (which is Java's strong suit) before dismissing the
whole thing because "trireme /tmp/hello-world.js" runs 40 times slower than node. Thanks!

Finally, we would love to be able to use a faster JavaScript implementation, which would speed up all of Trireme.
However, for many programs, Trireme on Rhino will be just fine, and the ability to embed Trireme inside
another container is especially helpful.

## Package Map

Trireme today consists of several modules. A typical application will wish to include the following in
CLASSPATH:

* trireme-core
* trireme-node10src
* trireme-crypto
* trireme-util

The last two packages are optional for environments that are constrained by space or strong aversion to third-
party dependencies.

"trireme-node10src" is the module that contains the code from Node.js that runs the modules. Both it and "trireme-core"
are required in order to actually run Trireme inside a larger container. If only "trireme-core" is on the
classpath, then all scripts will fail with the message, "No available Node.js implementation."

This table will help keep them straight:

<table>
<tr><td><b>module</b></td><td><b>Required?</b></td><td><b>Recommended?</b></td><td><b>Description</b></td></tr>
<tr><td>trireme-core</td><td>X</td><td>X</td><td>The core module containing the guts of Trireme</td></tr>
<tr><td>trireme-node10src</td><td>X</td><td>X</td><td>JavaScript code that makes Trireme implement Node.js 0.10</td></tr>
<tr><td>trireme-crypto</td><td/><td>X</td>
  <td>Support code for reading PEM files and some other crypto operations. Uses Bouncy Castle. If not in the classpath,
  certain crypto operations (notably PEM file support for TLS and HTTPS) will not work. Nonetheless, this is a separate
  package in case some implementations are wary of distributing Bouncy Castle.</td></tr>
<tr><td>trireme-util</td><td/><td>X</td>
  <td>Native Trireme / Java implementations of a few Node.js modules, notably "iconv". These are
  faster than the usual packages from NPM. If in the classpath, these modules will be used instead
  of searching the module path for a regular module.</td></tr>
<tr><td>trireme-net</td><td/><td/><td>An HttpAdaptor implementation that uses Netty. Mainly useful as an example
  to show how to write an HTTP adaptor for embedding into another container.</td></tr>
<tr><td>trireme-shell</td><td/><td/><td>A command-line shell for Trireme that mimics "node"</td></tr>
<tr><td>trireme-jar</td><td/><td/><td>A package that builds an all-in-one jar that contains all of the above.</td></tr>
<tr><td>rhino-compiler</td><td/><td/><td>A Maven plugin that compiles JavaScript into .class files for use in Rhino.
Used in the build process or "node10src" and others.</td></tr>
</table>

Additional modules in this directory are used only for testing.

## What Are the Dependencies?

Since Trireme is supposed to be highly embeddable, we try to minimize the dependencies.

### Rhino

This is the most mature framework for running JavaScript under Java. When some of the other efforts are
closer to working, we may look at replacing it if, as anticipated, they are much faster.

### Slf4j

This is the de facto standard logging API for Java.

### Java SE 6

Trireme runs on Java 6 and up. If Java 7 is available, it will use the new filesystem APIs, which allow a much wider
range of filesystem features, like links and permissions that work the same way as regular Node. It also uses
the asynchronous I/O interfaces for file I/O which are theoretically faster. (Although it turns out that on
anything but Windows they are actually doing the same thing under the covers.)

## Design

### Node.js Implementation

Trireme has a similar architecture to Node.js itself. Many of the core modules in standard Node.js rely
on a JavaScript shell, with native modules underneath that are written in C++.

Trireme is similar, and in many cases it exposes Java modules that mimic the interfaces of the C++
native modules in Node.js. So for instance, Trireme implements a native "tcp_wrap" module in Java
that uses NIO to emulate the same API as the "tcp_wrap" module in Node.js. The same goes for udp,
HTTP parsing, and many other things.

### Threading Model

Each Trireme script runs in a single thread. In other words, when the script is executed, it spawns a new thread
and occupies it until the script exits. Ticks and timers are implemented within that single thread. If the script
exits (has no ticks or timers, is not "pinned" by a library like http, and falls off the bottom of the code)
then the thread exits.

This way, there is no need for synchronization for most of the things that the scripts do, just like in regular
Node.js.

However, some modules, such as the filesystem, may block, so those modules dispatch to a thread pool, just like
in many other Java programs.

Similarly, the "HTTP adapter" allows Trireme to be embedded inside an existing
server container, and in that case HTTP requests may come from many different threads. For that reason, the main
event loop for each Trireme script depends on underlying collections that are thread-safe, so that different
threads may place events on the event loop.

In the future, we may choose to support multi-tenant script threads, so that many isolated scripts may run
in the same thread. That would decrease memory usage and context switching for servers that run many scripts.

### HTTP Adapter

The HTTP adapter is an interface that a server may implement and plug in to Trireme. When it is plugged in,
the adapter is responsible for calling Trireme when new HTTP requests arrive, and for presenting the
actual HTTP requests and responses.

When this is used, Trireme scripts work just as they do in standard Node.js, but the "server" part of
http is delegated to the adapter. (The client side of http continues to work the same way, however.)

### The Sandbox

The sandbox is an interface that a server may implement that allows control over what scripts are
allowed to do. It allows a script to accept or reject requests to access the filesystem, access
the network, and execute programs. Using the sandbox, it is possible to run Node.js scripts in a
totally isolated environment in a multi-tenant server.
