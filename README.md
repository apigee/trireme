# Noderunner

This is a set of libraries for running node.js scripts inside Java.

## What is Noderunner?

Noderunner runs Node.js scripts inside the JVM.
This is important because there is a lot of software out there (including our own) that is built in Java and
isn't going to get rewritten in JavaScript now or in the future.

Noderunner is specifically designed to be embeddable within any Java program. There is a lot of support inside
Noderunner for this specific case:

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

## How Complete is Noderunner?

Noderunner supports most of the Node.js APIs and passes much of the Node.js test suite.

The table below shows each module and its status. "Complete" means that a module is functionally complete,
although it may not necessarily pass all the node.js tests.

<table>
  <tr><td><b>Module</b></td><td><b>Status</b></td><td><b>Source</b></td></tr>
  <tr><td>assert</td><td>Complete</td><td>node.js</td></tr>
  <tr><td>child_process</td><td>Partial</td><td>Noderunner</td></tr>
  <tr><td>cluster</td><td>Not Implemented Yet</td><td>node.js</td></tr>
  <tr><td>console</td><td>Complete</td><td>node.js</td></tr>
  <tr><td>crypto</td><td>Partial</td><td>node.js + Noderunner</td></tr>
  <tr><td>debugger</td><td>Not Implemented</td><td><NA/td></tr>
  <tr><td>dgram</td><td>Partial</td><td>node.js + Noderunner</td></tr>
  <tr><td>dns</td><td>Partial</td><td>Noderunner</td></tr>
  <tr><td>domain</td><td>Complete</td><td>node.js + Noderunner</td></tr>
  <tr><td>events</td><td>Complete</td><td>node.js</td></tr>
  <tr><td>fs</td><td>Complete</td><td>node.js + Noderunner</td></tr>
  <tr><td>globals</td><td>Complete</td><td>node.js + Noderunner</td></tr>
  <tr><td>http</td><td>Complete</td><td>node.js + Noderunner</td></tr>
  <tr><td>https</td><td>Partial</td><td>Noderunner</td></tr>
  <tr><td>module</td><td>Complete</td><td>node.js</td></tr>
  <tr><td>net</td><td>Complete</td><td>node.js + Noderunner</td></tr>
  <tr><td>os</td><td>Partial</td><td>Noderunner</td></tr>
  <tr><td>path</td><td>Complete</td><td>node.js</td></tr>
  <tr><td>process</td><td>Complete</td><td>Noderunner</td></tr>
  <tr><td>punycode</td><td>Complete</td><td>node.js</td></tr>
  <tr><td>querystring</td><td>Complete</td><td>node.js</td></tr>
  <tr><td>readline</td><td>Partial</td><td>node.js + Noderunner</td></tr>
  <tr><td>repl</td><td>Not Implemented</td><td>node.js + Noderunner</td></tr>
  <tr><td>stream</td><td>Complete</td><td>node.js</td></tr>
  <tr><td>string_decoder</td><td>Complete</td><td>node.js</td></tr>
  <tr><td>timers</td><td>Complete</td><td>node.js + Noderunner</td></tr>
  <tr><td>tls</td><td>Partial</td><td>Noderunner</td></tr>
  <tr><td>tty</td><td>Not Implemented</td><td>NA</td></tr>
  <tr><td>url</td><td>Complete</td><td>node.js</td></tr>
  <tr><td>util</td><td>Complete</td><td>node.js</td></tr>
  <tr><td>vm</td><td>Complete</td><td>Noderunner</td></tr>
  <tr><td>zlib</td><td>Complete</td><td>node.js + Noderunner</td></tr>
</table>

## What are the Major Differences with "real" node.js?

A few of the modules are different, some in major ways:

### JavaScript Language

Noderunner runs in the JVM on Rhino, which is the most complete JavaScript implementation for
the JVM. Rhino currently implements version 1.8 of JavaScript, which means that a few things supported
in later versions of JavaScript, such as the "trimLeft" method of the "String" object, are not supported.

Also, newer features of V8, particularly the primitive array types, are not supported in Rhino.

Most of the time the differences between V8 and Rhino do not affect Node.js code, but occassionally
there is a problem. We would love some help from the Rhino community to start to address these differences.

### TLS/SSL and HTTPS

Noderunner uses Java's standard "SSLEngine" for TLS/SSL and HTTPS support, whereas standard Node.js uses
OpenSSL. The TLS implementation in Node.js is a fairly thin layer on top of OpenSSL and we chose not to try
and replicate this in Java.

That means that the biggest difference between the Node.js and Noderunner APIs is in how TLS certificates
and keys are handled. Whereas many TLS programs in Node.js use the "key" and "cert" arguments, which contain
PEM-encoded keys and certificates, Noderunner uses the "keystore" and "truststore" arguments, which contain
the names of Java keystore files.

In other words, a Node.js HTTPS server may be written like this:

    var options = {
      key: fs.readFileSync(common.fixturesDir + '/keys/agent1-key.pem'),
      cert: fs.readFileSync(common.fixturesDir + '/keys/agent1-cert.pem')
    };

    var server = https.createServer(options, function(req, res) {
      console.log('got request');
    });

But the corresponding Noderunner script must be written like this:

    var options = {
      keystore: common.fixturesDir + '/keys/agent1.jks',
      passphrase: 'secure'
    };

    var server = https.createServer(options, function(req, res) {
      console.log('got request');
    });

Similarly, Noderunner supports the "truststore" argument to supply a trust store for validating connections
with client-side certificates, and it supports the truststore as well so that a client may choose which
server certificates to accept.

### Crypto

The "crypto" module in Noderunner currently supports random numbers, hashes, and "mac" calculation. Ciphers,
Diffie-Hellman, and public-key validation and signatures are not yet supported.

Adding support for signing and validating will require us to either use a different key format like we
did with TLS, or put code into Java to support PEM keys and signatures.

Adding support for Ciphers is easier, but encrypting and signing with AES is sufficiently complex that we
may need to spend some time if we wish this to be compatible with encrypted data from standard Node.js.

Finally, Diffie-Hellman should not be difficult if it is important for someone's application.

### Child Process

Child processes are supported. Arbitary commands may be executed, just like in standard Node.js.

When a Noderunner script uses "fork" to spawn a new instance of itself, the script runs as a separate
thread inside the same JVM.

Some Node.js scripts rely on the ability to spawn a process called "./node" in order to fork itself. Noderunner
looks for this and tries to use it to spawn a new thread but it does not work in all cases. It does seem to
be mostly the Node.js test suite itself that does this.

### Filesystem

The filesystem is fairly complete, but remember that Java is an abstraction on top of the OS so it may not
behave exactly the same as it does on Linux.

On Java 6, the filesystem implementation falls back to using only the APIs supported in this version of Java,
which means that many things like symbolic links are not supported, and support for "chmod" and the like is
not exactly the same as in standard Node.js. On Java 7, Noderunner is able to use additional features and
the filesystem support is much more complete.

### OS

Again, Noderunner runs on top of the JVM, which presents an operating-system abstraction. Things that Node.js
programs can do like set up signal handlers and the like are not supported.

## How Fast is It?

Rhino on the JVM is much slower than V8. (In some benchmarks it is 50 times slower.) However, Node.js programs
take advantage of a lot of native code, especially when HTTP and TLS are used, so Noderunner generally
fares much better.

In general, we have seen simple HTTP benchmarks run at about one-half the speed of the same programs on
standard Node.js. Some things are slower than that, and others are faster -- it all depends, as it does with
all benchmarks.

We would love to be able to use a faster JavaScript implementation, which would speed up all of Noderunner.
However, for many programs, Noderunner on Rhino will be just fine, and the ability to embed Noderunner inside
another container is especially helpful.

## What Are the Dependencies?

Since Noderunner is supposed to be highly embeddable, we try to minimize the dependencies.

### Rhino.

This is the most mature framework for running JavaScript under Java. When some of the other efforts are
closer to working, we may look at replacing it if, as anticipated, they are much faster.

### Slf4j

This is the de facto standard logging API for Java.

### Java SE 6

Java is a great platform with a lot of support for a lot of nice things. We're going to try and do everything
we can without any additional stuff.

## Design

### Node.js Implementation

Noderunner has a similar architecture to Node.js itself. Many of the core modules in standard Node.js rely
on a JavaScript shell, with native modules underneath that are written in C++.

Noderunner is similar, and in many cases it exposes Java modules that mimic the interfaces of the C++
native modules in Node.js. So for instance, Noderunner implements a native "tcp_wrap" module in Java
that uses NIO to emulate the same API as the "tcp_wrap" module in Node.js. The same goes for udp,
HTTP parsing, and many other things.

### Threading Model

Each Noderunner script runs in a single thread. In other words, when the script is executed, it spawns a new thread
and occupies it until the script exits. Ticks and timers are implemented within that single thread. If the script
exits (has no ticks or timers, is not "pinned" by a library like http, and falls off the bottom of the code)
then the thread exits.

This way, there is no need for synchronization for most of the things that the scripts do, just like in regular
Node.js.

However, some modules, such as the filesystem, may block, so those modules dispatch to a thread pool, just like
in many other Java programs.

Similarly, the "HTTP adapter" allows Noderunner to be embedded inside an existing
server container, and in that case HTTP requests may come from many different threads. For that reason, the main
event loop for each Noderunner script depends on underlying collections that are thread-safe, so that different
threads may place events on the event loop.

In the future, we may choose to support multi-tenant script threads, so that many isolated scripts may run
in the same thread. That would decrease memory usage and context switching for servers that run many scripts.

### HTTP Adapter

The HTTP adapter is an interface that a server may implement and plug in to Noderunner. When it is plugged in,
the adapter is responsible for calling Noderunner when new HTTP requests arrive, and for presenting the
actual HTTP requests and responses.

When this is used, Noderunner scripts work just as they do in standard Node.js, but the "server" part of
http is delegated to the adapter. (The client side of http continues to work the same way, however.)

### The Sandbox

The sandbox is an interface that a server may implement that allows control over what scripts are
allowed to do. It allows a script to accept or reject requests to access the filesystem, access
the network, and execute programs. Using the sandbox, it is possible to run Node.js scripts in a
totally isolated environment in a muti-tenant server.

## Running Noderunner

The "jar" module builds a self-contained JAR that may be used to launch Noderunner on the command
line just like regular Node.js:

    mvn install
    java jar jar/target/noderunner.X.Y.Z.jar <script name>

(and with no arguments it will launch the "repl" but that implementation is not complete)

## Embedding Noderunner

There is JavaDoc for the "NodeEnvironment" and "NodeScript" classes, and many other features.
Here are the basics:

    import com.apigee.noderunner.core.NodeEnvironment;
    import com.apigee.noderunner.core.NodeScript;

    // The NodeEnvironment controls the environment for many scripts
    NodeEnvironment env = new NodeEnvironment();

    // Pass in the script file name, a File pointing to the actual script, and an Object[] containg "argv"
    NodeScript script = env.createScript("my-test-script.js",
                                         new File("my-test-script.js"), null);

    // Wait for the script to complete
    ScriptStatus status = script.execute().get();

    // Check the exit code
    System.exit(status.getExitCode());
