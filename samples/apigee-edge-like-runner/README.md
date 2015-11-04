Apigee uses Trireme to enable running [Node.js on Apigee Edge][node-on-edge].  When API developers are developing these
[Node.js][nodejs] API proxies locally, the litmus tests to see if the API will run on [Apigee Edge][apigee-edge]
typically involves using Trireme to run the application locally to see if things run as expected.  When Trireme runs the
API properly, the API developer will upload the Node.js API proxy to Apigee Edge and deploy it.  Unfortunately, this is
not enough.  The Trireme deployment on Apigee Edge uses a custom HTTP adapter which can result in things running locally
just fine but encountering runtime errors on Apigee Edge.  *(The reason for this is due to some Node.js modules using
undocumented/private properties/APIs, relying on V8 specific features, etc.)*

The purpose of this sample is to create an Apigee Edge-like launcher for Trireme.  *(While we cannot create an exact
Apigee Edge launcher for Trireme, we can come pretty close.)*  We've used this utility internally at Apigee and since
its inception, the Apigee Edge-like Launcher has been able to reproduce all reported issues related to Node.js on
Apigee Edge.  *(This is not to say that this utility will catch everything.)*  That being said, the hope is that this
little utility will become the new litmus test when Apigee Edge API developers test their Node.js proxies locally.

### Building and Running

This is your standard Maven-based project.  To build the Apigee Edge-like Launcher, just use `mvn package` and the build
artifact will be in `./target` as any other Maven project.  From there, you can run the Apigee Edge-like Launcher just
like you would the `trireme-shell`: `java -jar apigee-edge-like-launcher.jar [options] YOUR_SCRIPT`.

[nodejs]: https://nodejs.org
[apigee-edge]: http://apigee.com/docs/api-services/content/what-apigee-edge
[node-on-edge]: http://apigee.com/docs/api-services/content/getting-started-nodejs-apigee-edge
