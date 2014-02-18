# 0.7.0 18-Feb-2013:

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

# 0.6.9 22-Jan-2013:

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
