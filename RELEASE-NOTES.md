# 0.6.9 11-Jan-2013:

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
