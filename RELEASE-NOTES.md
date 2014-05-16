# 0.7.4 15-May-2014:

* [Issue 54](https://github.com/apigee/trireme/issues/54) Properly raise an 'error' event if
child_process.spawn() is blocked due to the Sandbox being enabled.
* [Issue 53](https://github.com/apigee/trireme/issues/54) Add the new "trireme-xslt" module which uses native
Java XSLST processing and supports multiple threads. Also, create a compatible version of the
"node_xslt" module so that it may be used in Trireme as well without requiring libxml.

# 0.7.3 11-Apr-2014:

* [Issue 51](https://github.com/apigee/trireme/pull/51) Java 6 only: mkdir should return ENOENT on non-existent
parent directories.
* Fix ipc in child_process module so that it works.

With this fix, a Node.js app running in Trireme can "fork" a sub-process
and communicate via the IPC pipe using "send" on the child process and on the "process" object, as described
in the docs for the "child_process" module.

Handles are not yet supported, which means that a parent cannot send a TCP socket to a child and expect it to
process it.

Note that unlike regular Node.js, child scripts spawned using "fork" run inside the same JVM as the parent,
but with a totally different script context and in a separate thread. "IPC" communication happens using a
concurrent queue. Communication between parent and child via this mechanism should be very fast.

# 0.7.2 28-Mar-2014:

* [Issue 47](https://github.com/apigee/trireme/issues/47) When the executeModule() function is used to run
a script (instead of the more typical "execute()"), process.argv was not being generated in a consistent
way.
* [Issue 49](https://github.com/apigee/trireme/issues/49) HTTP responses that included an extra space at the
end of the status line (such as "HTTP/1.1 413 ") were resulting in a parsing error.

# 0.7.1 18-Mar-2014:

The biggest change in this release is that you must include at least two Jar files in order for Trireme to work:

* trireme-core
* trireme-node10src

The reason is that we are separating the Node.js-specific JavaScript code from the generic runtime in Java.
That way, in the future we may be able to support multiple versions of Node.js in the same runtime.

In the past, you only had to declare "trireme-core" in pom.xml and Maven will pick up everything. Now, you need
to include both packages listed above, or you will see the error: "No available Node.js implementation".

* Separate version-specific JavaScript code from the core runtime. This lays the groundwork for supporting
multiple versions of Node.js in the same instance of Trireme by using the same low-level support in Java
across all versions, and running different versions of the Node.js JavaScript code.
* [Issue 32](https://github.com/apigee/trireme/issues/32) Add a mechanism to cache compiled JavaScript
classes. This reduces memory usage and startup time in servers that host many Node.js scripts in a single
Trireme environment. The cache works by creating a SHA-256 hash of the source and using that as the key.
* [Issue 41](https://github.com/apigee/trireme/issues/41) Relative symbolic links were being mangled by the
code that implements the "sandbox" functionality. The result was that NPM didn't run. Now NPM can run on Trireme.
* [Issue 45](https://github.com/apigee/trireme/issues/45) Add an "extra class shutter" to Sandbox that allows
implementors to expand the list of Java classes that may be called directly from Trireme. By default, only
a few classes are supported, which means that Node.js code cannot normally invoke Java code directly unless
additional classes are whitelisted using this mechanism.

# 0.7.0 18-Feb-2014:

* [Issue 17](https://github.com/apigee/trireme/issues/17) Diffie-Hellman crypto
is now supported. DSA is currently not supported, and "SecureContext" won't be supported because it's specific to Node's
TLS implementation.
* [Issue 19](https://github.com/apigee/trireme/issues/19) NPM can now run on Trireme (as long as it is run on
Java 7). Various fixes to the "fs" module were necessary in order to make this happen.
* [Issue 25](https://github.com/apigee/trireme/issues/25) Move JavaScript source to a separate package called
"trireme-node10src". This package is required by "trireme-core". In the future, this may allow us to support
multiple versions of Node.js on a single instance of Trireme.
* [Issue 27](https://github.com/apigee/trireme/issues/27) Run the main loop of Trireme using a JavaScript adapted
from "node.js" rather than building it in Java. This makes Trireme more compatible with regular Node.
* [Issue 38](https://github.com/apigee/trireme/issues/38) Address an issue with callbacks that made MongoDB
fail to run.
* [Issue 39](https://github.com/apigee/trireme/issues/39) Add an option to the Sandbox to hide details of the CPU and
network interfaces from scripts. We will use this when embedding Trireme in a secure environment.
* [Issue 40](https://github.com/apigee/trireme/issues/40) Completion of the "tty" module, including support for
getting the window size, which allow "mocha" to run on Trireme.

# 0.6.9 22-Jan-2014:

* [Issue 35](https://github.com/apigee/trireme/issues/35) Add socket.localAddress, socket.remoteAddress, and socket.address() to HTTP requests
* [Issue 33](https://github.com/apigee/trireme/issues/33) Add "attachment" object to HTTP requests passed through HTTP adapter that is attached to the "request" object in JS.
* [Issue 22](https://github.com/apigee/trireme/issues/22) Stop sharing and sealing Rhino root context -- now every script can modify built-in object prototypes if they wish.
* [Issue 31](https://github.com/apigee/trireme/issues/31) Back off and retry if Rhino cannot compile a script because it is would result in more than 64K of bytecode.
* [Issue 17](https://github.com/apigee/trireme/issues/17) Implement crypto.Cipher and crypto.Decipher for DES, Triple DES, and AES
* [Issue 34](https://github.com/apigee/trireme/pull/34) Do not load character sets using Charset.forName() so that Trireme works outside the system classpath.
* Update modules from Node.js to 10.24
* Default Rhino optimization level to 9
* Numerous bug fixes.

# 0.6.8 17-Dec-2013:

* Issue 29: Assertion error while talking to HTTPS target.

A few build process changes.

# 0.6.7 31-Oct-2013:

Initial open-source release.
