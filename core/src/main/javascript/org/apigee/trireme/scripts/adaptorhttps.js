/*
 * Copyright 2013 Apigee Corporation.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
/*
 * This is a wrapper around the https module from node that uses our adaptors when configured, and native
 * Node code when not.
 */

var NodeHttps = require('node_https');
var HttpWrap = process.binding('http_wrap');

var debug;
if (process.env.NODE_DEBUG && /https/.test(process.env.NODE_DEBUG)) {
  debug = function(x) { console.error('HTTPS: %s', x); };
} else {
  debug = function() { };
}

if (HttpWrap.hasServerAdapter()) {
  debug('Using HTTP adapter for https');
  var http = require('http');
  var util = require('util');

  function Server(opts, requestListener) {
    if (!(this instanceof Server)) return new Server(opts, requestListener);
    http.Server.call(this, requestListener);
    this.tlsParams = opts;
  }
  util.inherits(Server, http.Server);

  exports.Server = Server;
  exports.createServer = function(opts, requestListener) {
    return new Server(opts, requestListener);
  };

} else {
  debug('Using Node HTTPS module');
  exports.Server = NodeHttps.Server;
  exports.createServer = NodeHttps.createServer;
}

exports.globalAgent = NodeHttps.globalAgent;
exports.Agent = NodeHttps.Agent;
exports.request = NodeHttps.request;
exports.get = NodeHttps.get;
