# 0.8.1 09-Sep-2014:

* [Issue 66](https://github.com/apigee/trireme/issues/66) Use NIO to implement the datagram (aka UDP)
module for better performance.
* [Issue 81](https://github.com/apigee/trireme/issues/81) Remove Trireme's Java implementation of
"iconv-lite," which was not compatible with the version on NPM. The standard "iconv-lite" module now
works, although it is very slow for unusual character sets like "big5" because those are now implemented
in JavaScript rather than using native Java character sets. (ASCII, UTF8, and other standards still
use the Java platform and are as fast or faster than native Node.) This also fixes a bug with
recent versions of the Express / connect "body parser" middleware.
* [Issue 82](https://github.com/apigee/trireme/issues/82) Add fields to the HTTP adapter to prevent a race
condition when recent versions of Connect "send-static" middleware try to use undocumented internal
fields. (This only affects the "HTTP adapter" which is not used by all users of Trireme.)

Other issues:

* Upgrade to version 1.50 of Bouncy Castle.
* Fix path translation for filesystems mounted outside root (t-beckmann)
* Fix SSL support to always emit Error objects on error (t-beckmann)
* Fix /?/ UNC path prefix (t-beckmann)
* Unwrap Rhino "Wrapper" objects (t-beckmann)
* Fix "binary" character set to handle unsigned values properly (t-beckmann)
* A number of other small but important fixes from Thomas Beckmann. Thanks!


# 0.8.0 17-Jul-2014:

This release fixes many compatibility issues with standard Node. The most significant is that we have switched
from the old "org.mozilla.rhino" to "io.apigee.rhino", and to the latest release of 1.7R5pre3. This has
a lot of fixes that improve compatibility with V8.

Trireme itself will continue to function on older versions of Rhino, but for best results, follow the
POM and use the one that is specified here...

* [Issue 17](https://github.com/apigee/trireme/issues/17) Support DSA signing and verification to complete the
"crypto" module.
* [Issue 24](https://github.com/apigee/trireme/issues/24) Small tweaks to string encoding and decoding for
a small performance gain.
* [Issue 42](https://github.com/apigee/trireme/issues/42) Implement "trimLeft" and "trimRight" on the String
prototype even if they are not supported by the JavaScript runtime. This un-breaks the "jade" template
engine.
* [Issue 62](https://github.com/apigee/trireme/issues/62) Make Java 6 filesystem code throw the same errors
for symlinks and chmod as in Java 7.
* [Issue 63](https://github.com/apigee/trireme/issues/63) Allow process.argv to be replaced.
* [Issue 64](https://github.com/apigee/trireme/issues/64) Explicitly ignore the second argument to the global
"escape()" function. This un-breaks several Amazon AWS modules.
* [Issue 67](https://github.com/apigee/trireme/issues/67) Support Error.captureStackTrace() and
Error.prepareStackTrace() for better Node.js compatibility. This un-breaks the latest versions of Express
3.x.
* [Issue 68](https://github.com/apigee/trireme/issues/68) Support string arguments to Cipher.update that
have no encoding. This un-breaks the "httpntlm" module.
* [Issue 69](https://github.com/apigee/trireme/issues/69) Thanks to issue 67, update tests to Express 3.12.1
to ensure that it keeps working.
* [Issue 70](https://github.com/apigee/trireme/issues/70) Don't use the "NetworkPolicy" in the Sandbox to control
UDP ports. This makes it possible to send and receive UDP datagrams even if the NetworkPolicy does not
allow listening on any port.
* [Issue 71](https://github.com/apigee/trireme/issues/71) Upgrade from org.mozilla.rhino version 1.7R4 to
io.apigee.rhino version 1.7R5pre3. This adds a number of compatibility fixes that make more V8 code run
unmodified inside Trireme.

# 0.7.5 16-May-2014:

* [Issue 56](https://github.com/apigee/trireme/issues/56) Support Windows. This entails a bunch of work around
path handling and file permissions handling.
* [Issue 55](https://github.com/apigee/trireme/issues/55) Fix process.platform to guess the platform and return
a reasonable value.

With this release, by default Trireme will use the "os.name" system property to provide a value for the
"process.platform" property. Supported values are:

* darwin
* freebsd
* linux
* sunos
* win32

In addition, if a "Sandbox" is configured on the NodeEnvironment and the "setHideOSDetails" function is called, then
process.platform will instead return "java" as it did in previous releases.

This is an important change for the Windows platform as many parts of Node.js and third-party modules try to do
things differently on Windows.

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
