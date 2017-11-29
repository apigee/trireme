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
 * This is our implementation of the "http" module. Normally, it just delegates to Node's normal
 * JavaScript-built HTTP module. However, it can also delegate to an HTTP adaptor, which means that it
 * gives up control of networking and HTTP to a third party Java library instead, although it
 * implements the same basic interface.
 *
 * This module is normally initialized by Noderunner to load when the "http" module is included.
 */

var HttpWrap = process.binding('http_wrap');
var NodeHttp = require('node_http');

var debug, debugOn;
if (process.env.NODE_DEBUG && /http/.test(process.env.NODE_DEBUG)) {
  debugOn = true;
  debug = function(x) { console.error('HTTP: %s', x); };
} else {
  debugOn = false;
  debug = function() { };
}

function toNumber(x) { return (x = Number(x)) >= 0 ? x : false; }

var STATUS_CODES = exports.STATUS_CODES = NodeHttp.STATUS_CODES;

exports.IncomingMessage = NodeHttp.IncomingMessage;
exports.OutgoingMessage = NodeHttp.OutgoingMessage;

exports.Agent = NodeHttp.Agent;
exports.globalAgent = NodeHttp.globalAgent;

exports.ClientRequest = NodeHttp.ClientRequest;

exports.request = NodeHttp.request;
exports.get = NodeHttp.get;

exports.Client = NodeHttp.Client;
exports.createClient = NodeHttp.createClient;

/*
 * Allow the tool embedding Trireme to configure the default
 * http.globalAgent.maxSockets.
 */
var customMaxSockets = toNumber(process.env.NODE_HTTP_MAX_SOCKETS);

if (customMaxSockets) {
  exports.globalAgent.maxSockets = customMaxSockets;
}

if (HttpWrap.hasServerAdapter()) {
  var util = require('util');
  var net = require('net');
  var events = require('events');
  var stream = require('stream');
  var timers = require('timers');
  var domain = require('domain');
  var assert = require('assert');
  var tcp_wrap = process.binding('tcp_wrap');

  debug('Using server adapter');

  var END_OF_FILE = {};

  /*
   * This object represents the "http.ServerResponse" object from Node with code that wraps our
   * adapter. Frameworks like Express like to add to this object, take things away, and even
   * add and remove functions from the prototype. So for that reason we do NOT add any functions
   * to this object's prototype that are not documented in the Node docs.
   */

  function ServerResponse(adapter, conn) {
    if (!(this instanceof ServerResponse)) return new ServerResponse(adapter, conn);
    stream.Writable.call(this, {decodeStrings: true});

    this.statusCode = 200;
    this.headersSent = false;
    this.ended = false;
    this.sendDate = true;
    this._headers = {};
    this.connection = conn;
    this.socket = conn;
    this.finished = false;
    this.closeDelivered = false;

    Object.defineProperty(this, '_adapter', {
      value: adapter,
      enumerable: false
    });
    Object.defineProperty(this, 'attachment', {
      value: adapter.attachment,
      enumerable: false
    });
  }

  util.inherits(ServerResponse, stream.Writable);
  exports.ServerResponse = ServerResponse;

  ServerResponse.prototype.statusCode = 200;

  ServerResponse.prototype.writeContinue = function() {
    throw Error('writeContinue not implemented');
  };

  ServerResponse.prototype._implicitHeader = function() {
    this.writeHead(this.statusCode);
  };

  ServerResponse.prototype.writeHeader = function() {
    this.writeHead.apply(this, arguments);
  };

  ServerResponse.prototype.writeHead = function(statusCode) {
    debug('writeHead');
    // Argument and header parsing code from http.js
    if (typeof arguments[1] == 'string') {
      reasonPhrase = arguments[1];
      headerIndex = 2;
    } else {
      reasonPhrase = STATUS_CODES[statusCode] || 'unknown';
      headerIndex = 1;
    }
    this.statusCode = statusCode;

    var obj = arguments[headerIndex];
    headers = this._renderHeaders();

    if (obj) {
      headers = this._renderHeaders();
      if (Array.isArray(obj)) {
        // handle array case
        // TODO: remove when array is no longer accepted
        var field;
        for (var i = 0, len = obj.length; i < len; ++i) {
          field = obj[i][0];
          if (field in headers) {
            obj.push([field, headers[field]]);
          }
        }
        headers = obj;

      } else {
        // handle object case
        var keys = Object.keys(obj);
        for (var i = 0; i < keys.length; i++) {
          var k = keys[i];
          if (k) headers[k] = obj[k];
        }
      }
    }

    this._saveHeaders(headers);
  };

  function responseWriteComplete(self, err, cb) {
    if (debugOn) {
      debug('Response write complete: err ' + err);
    }
    if (err) {
      if (!self.ended && !self.closeDelivered) {
        self.closeDelivered = true;
        self.emit('close');
      }
    } else {
      cb();
    }
  }

  ServerResponse.prototype._write = function(data, encoding, cb) {
    if (!this._savedHeaders) {
      this.writeHead(this.statusCode);
    }

    var self = this;
    timers.active(this.connection);
    if (!this.headersSent) {
      // Just send one additional chunk of data
      if (debugOn) {
        debug('Sending http headers status = ' + this.statusCode +
             ' data = ' + (data ? data.length : 0));
      }

      // We should always get a buffer with no encoding in this case
      this._adapter.send(this.statusCode, this.sendDate, this._savedHeaders,
                         data, encoding, undefined, false, function(err) {
        responseWriteComplete(self, err, cb);
      });
      this.headersSent = true;

    } else {
      if (debugOn) {
        debug('Sending data = ' + (data ? data.length : 0));
      }
      this._adapter.sendChunk(data, encoding, undefined, false, function(err) {
        responseWriteComplete(self, err, cb);
      });
    }
  };

  ServerResponse.prototype._send = function() {
    return this.write(data, encoding);
  };

  function responseEndComplete(self) {
    debug('Response end completed');
    self.connection.emit('finish');
  }

  ServerResponse.prototype.end = function(data, encoding) {
    debug('end');
    if (!this._savedHeaders) {
      this.writeHead(this.statusCode);
    }

    timers.active(this.connection);
    var self = this;
    stream.Writable.prototype.end.call(this, data, encoding, function() {
      // The connection is a dummy connection, but it still must be ended to clean up state
      self.connection.end();
      // That does not "unref" it in all cases -- must do that so we don't hold server open.
      self.connection.unref();

      self.ended = true;
      self.finished = true;

      if (self.headersSent) {
        debug('Sending end of response');
        self._adapter.sendChunk(null, null, self._trailers, true, function() {
          responseEndComplete(self);
        });
      } else {
        // We will only get here if we are sending an empty response
        self._adapter.send(self.statusCode, self.sendDate, self._savedHeaders,
                           null, null, self._trailers, true, function() {
          responseEndComplete(self);
        });
      }
    });
  };

  ServerResponse.prototype._saveHeaders = function(headers) {
    this._savedHeaders = [];
    if (headers) {
      var keys = Object.keys(headers);
      var isArray = (Array.isArray(headers));
      var field, value;

      for (var i = 0, l = keys.length; i < l; i++) {
        var key = keys[i];
        if (isArray) {
          field = headers[key][0];
          value = headers[key][1];
        } else {
          field = key;
          value = headers[key];
        }

        if (Array.isArray(value)) {
          for (var j = 0; j < value.length; j++) {
            this._savedHeaders.push(field);
            this._savedHeaders.push(value[j]);
          }
        } else {
          this._savedHeaders.push(field);
          this._savedHeaders.push(value);
        }
      }
    }
  };

  ServerResponse.prototype.addTrailers = function(headers) {
    if (!this._trailers) {
      this._trailers = [];
    }
    var keys = Object.keys(headers);
    var isArray = (Array.isArray(headers));
    var field, value;
    for (var i = 0, l = keys.length; i < l; i++) {
      var key = keys[i];
      if (isArray) {
        this._trailers.push(headers[key][0]);
        this._trailers.push( headers[key][1]);
      } else {
        this._trailers.push(key);
        this._trailers.push(headers[key]);
      }
    }
  };

  ServerResponse.prototype.destroy = function() {
    debug('Destroy');
    if (this._adapter) {
      this._adapter.destroy();
    }
    this.connection.destroy();
  };

  ServerResponse.prototype.setTimeout = function(timeout, cb) {
    this.connection.setTimeout(timeout, cb);
  };

  ServerResponse.prototype.clearTimeout = function(cb) {
    this.connection.clearTimeout(cb);
  };

  ServerResponse.prototype.setHeader = NodeHttp.OutgoingMessage.prototype.setHeader;
  ServerResponse.prototype.getHeader = NodeHttp.OutgoingMessage.prototype.getHeader;
  ServerResponse.prototype.removeHeader = NodeHttp.OutgoingMessage.prototype.removeHeader;
  ServerResponse.prototype._renderHeaders = NodeHttp.OutgoingMessage.prototype._renderHeaders;

  /*
   * Like ServerResponse, frameworks like to mess with the prototype to this object, so add no
   * functions there unless they are publicly-documented in the Node docs.
   */

  function ServerRequest(adapter, conn) {
    if (!(this instanceof ServerRequest)) return new ServerRequest(adapter, conn);
    NodeHttp.IncomingMessage.call(this, conn);

    this.httpVersionMajor = adapter.requestMajorVersion;
    this.httpVersionMinor = adapter.requestMinorVersion;
    this.httpVersion = adapter.requestMajorVersion + '.' + adapter.requestMinorVersion;
    this.url = adapter.requestUrl;
    this.complete = false;
    this.reading = false;

    Object.defineProperty(this, '_adapter', {
      value: adapter,
      enumerable: false
    });
    Object.defineProperty(this, 'attachment', {
      value: adapter.attachment,
      enumerable: false
    });
  }

  util.inherits(ServerRequest, NodeHttp.IncomingMessage);

  function addPending(self, chunk) {
    // We are always readable in this implementation, so whenever we get incoming data,
    // add it to the read queue. We don't care if _read was already called.
    timers.active(self.connection);
    if (debugOn) {
      debug('Pushing ' + chunk.length + ' bytes to the request stream');
    }
    var ret;
    if (chunk === END_OF_FILE) {
      ret = self.push(null);
    } else {
      ret = self.push(chunk);
    }
    if (debugOn) {
      debug('Push result = ' + ret);
    }
    return ret;
  }

  ServerRequest.prototype.setTimeout = function(timeout, cb) {
    this.connection.setTimeout(timeout, cb);
  };

  ServerRequest.prototype.clearTimeout = function(cb) {
    this.connection.clearTimeout(cb);
  };

  ServerRequest.prototype._read = function(size) {
    if (!this.reading) {
      debug('Resuming request stream read');
      this.reading = true;
      this._adapter.resume();
    }
  };

  function Server(requestListener) {
    if (!(this instanceof Server)) return new Server(requestListener);
    debug('New HTTP server');
    events.EventEmitter.call(this);

    if (requestListener) {
      this.addListener('request', requestListener);
    }

    // Code from http.js
    this.httpAllowHalfOpen = false;
    this.addListener('clientError', function(err, conn) {
      conn.destroy(err);
    });
  }

  util.inherits(Server, events.EventEmitter);

  exports.Server = Server;

  exports.createServer = function(requestListener) {
    return new Server(requestListener);
  };

  function getErrorMessage(err) {
    var msg;
    if (typeof err === 'string') {
      return msg;
    } else if ((typeof err === 'object') && (err instanceof Error)) {
      return err.message;
    } else {
      return '';
    }
  }

  /**
   * Error callback for domain. This gets invoked if the user's code throws an exception while processing
   * a request.
   */
  function handleError(err, response) {
    debug('Handling server error and sending to adapter');
    if (err.stack && debugOn) {
      debug(err.message);
      debug(err.stack);
    }
    if (response.headersSent) {
      debug('Response already sent -- closing');
      response.destroy();

    } else {
      var msg = getErrorMessage(err);
      var stack = err.stack ? err.stack : undefined;
      response._adapter.fatalError(msg, stack);
    }
  }

  Server.prototype._makeSocket = function(handle) {
    var self = this;

    // The handle is just a Java object -- make it an actual socket handle
    var sockHandle = new tcp_wrap.TCP(handle);

    // And make that handle an actual socket
    var conn = new net.Socket({
      handle: sockHandle
    });
    if (this.sslContext) {
      conn.encrypted = true;
    }
    if (this.timeout && (this.timeout > 0)) {
      conn.setTimeout(this.timeout, function() {
        self.timeoutCallback(conn);
      });
    }
    conn.readable = conn.writable = true;
    timers.active(conn);
    this.emit('connection', conn);
    return conn;
  };

  // An adapter can call this method at any time to create the ServerRequest object,
  // backed by the supplied request adapter
  Server.prototype._makeRequest = function(reqAdapter, conn) {
    debug('_makeRequest');
    var headers = reqAdapter.getRequestHeaders();

    var ret = new ServerRequest(reqAdapter, conn);

    var n = headers.length;

    // If parser.maxHeaderPairs <= 0 - assume that there're no limit
    if (this.maxHeaderPairs > 0) {
      // TODO but "parser" is never set?
      n = Math.min(n, parser.maxHeaderPairs);
    }

    for (var i = 0; i < n; i += 2) {
      var k = headers[i];
      var v = headers[i + 1];
      ret._addHeaderLine(k, v);
    }

    ret.method = reqAdapter.requestMethod;
    return ret;
  };

  // Make the JavaScript "response" object based on the same adapter
  Server.prototype._makeResponse = function(respAdapter, conn, timeoutOpts) {
    debug('_makeResponse');
    var ret = new ServerResponse(respAdapter, conn);
    conn._outgoing = ret;

    if (timeoutOpts && (timeoutOpts.timeout > 0)) {
      ret.setTimeout(timeoutOpts.timeout, function() {
        var timeoutCode = (timeoutOpts.statusCode ? timeoutOpts.statusCode : 503);
        if (debugOn) {
          debug('Sending automatic response timeout of ' + timeoutCode);
        }
        if (ret.headersSent) {
          ret.end();
        } else {
          ret.writeHead(timeoutCode, {
            'content-type': (timeoutOpts.contentType ? timeoutOpts.contentType : 'text/plain')
          });
          ret.end(timeoutOpts.message ? timeoutOpts.message : undefined);
        }
      });
    }

    return ret;
  };

  /*
   * Called directly by the adapter when a message arrives and all the headers have been received.
   */
  Server.prototype._onHeaders = function(request, response) {
    debug('onHeadersComplete');
    var self = this;

    response._adapter.onchannelclosed = function() {
      debug('Server channel closed');
      // We decided to emit the close event elsewhere here.
    };

    response._adapter.onwritecomplete = function(err) {
      if (debugOn) {
        debug('write complete: ' + err + ' outstanding: ' + response._outstanding);
      }
      // We decided to emit the write error elsewhere.
    };

    request._adapter.domain = domain.create();
    request._adapter.domain.on('error', function(err) {
      handleError(err, response);
    });
    request._adapter.domain.run(function() {
      self.emit('request', request, response);
    });
  };

  /**
   * Called when the client sent a message containing an upgrade header.
   */
  var EMPTY_BUFFER = new Buffer(0);

  Server.prototype._onUpgrade = function(request, socket) {
    debug('onUpgrade');

    if (events.EventEmitter.listenerCount(this, 'upgrade') === 0) {
      debug('No listeners for upgrade event');
      socket.unref();
      return false;
    }

    this.emit('upgrade', request, socket, EMPTY_BUFFER);
    return true;
  };

  /*
   * This is called directly by the adapter when data is received for the message body.
   */
  function onBody(request, b) {
    debug('onBody');
    timers.active(request.connection);
    var keepReading;
    request._adapter.domain.run(function() {
      keepReading = addPending(request, b);
    });
    if (!keepReading && request.reading) {
      debug('Pausing further reads');
      request.reading = false;
      request._adapter.pause();
    }
  }

  /*
   * This is called directly by the adapter when the complete message has been received.
   */
  function onMessageComplete(request) {
    debug('onMessageComplete');
    timers.active(request.connection);
    request.complete = true;
    if (!request.upgrade) {
      request._adapter.domain.run(function() {
        addPending(request, END_OF_FILE);
      });
    }
  }

  function onClose(request, response) {
    debug('onClose');
    request.connection.destroy();
    if (!request.ended) {
      request._adapter.domain.run(function() {
        request.ended = true;
        request.emit('close');
      });
    }
  }

  function listen(self, address, port, addressType, backlog, fd) {
    if (debugOn) {
      debug('listen address = ' + address + ' port = ' + port);
    }
    var fam;
    if (addressType === 4) {
      fam = 'IPv4';
    } else if (addressType === 6) {
      fam = 'IPv6';
    } else {
      fam = 'IPv4';
    }

    self._adapter = HttpWrap.createServerAdapter();
    if (self.sslContext) {
      self._adapter.setSslContext(self.sslContext, self.rejectUnauthorized, self.requestCert);
    }
    var r = self._adapter.listen(address, port, backlog);

    if (r) {
      var ex = errnoException(r, 'listen');
      self._adapter.close();
      self._adapter = null;
      process.nextTick(function() {
        self.emit('error', ex);
      });
      return;
    }

    self._adapter.makeRequest = function(req, conn) {
      return self._makeRequest(req, conn);
    };
    self._adapter.makeResponse = function(resp, conn, timeoutOpts) {
      return self._makeResponse(resp, conn, timeoutOpts);
    };
    self._adapter.makeSocket = function(handle) {
      return self._makeSocket(handle);
    };
    self._adapter.onheaders = function(request, response) {
      self._onHeaders(request, response);
    };
    self._adapter.ondata = onBody;
    self._adapter.oncomplete = onMessageComplete;
    self._adapter.onupgrade = function(request, handle) {
      return self._onUpgrade(request, handle);
    };
    self._adapter.onclose = onClose;

    process.nextTick(function() {
      self.emit('listening');
    });
  }

  Server.prototype.address = function() {
    return this._adapter.localAddress();
  };

  Server.prototype.listen = function() {
    debug('server listen');
    // Run through the various arguments to "listen." This code is taken from "net.js".
    var self = this;

    var lastArg = arguments[arguments.length - 1];
    if (typeof lastArg == 'function') {
      self.once('listening', lastArg);
    }

    var port = toNumber(arguments[0]);

    // The third optional argument is the backlog size.
    // When the ip is omitted it can be the second argument.
    var backlog = toNumber(arguments[1]) || toNumber(arguments[2]);

    if (arguments.length == 0 || typeof arguments[0] == 'function') {
      // Bind to a random port.
      listen(self, '0.0.0.0', 0, null, backlog);

    } else if (typeof arguments[1] == 'undefined' ||
               typeof arguments[1] == 'function' ||
               typeof arguments[1] == 'number') {
      // The first argument is the port, no IP given.
      listen(self, '0.0.0.0', port, 4, backlog);

    } else {
      // The first argument is the port, the second an IP.
      require('dns').lookup(arguments[1], function(err, ip, addressType) {
        if (err) {
          self.emit('error', err);
        } else {
          listen(self, ip || '0.0.0.0', port, ip ? addressType : 4, backlog);
        }
      });
    }
    return self;
  };

  Server.prototype.close = function(cb) {
    if (!this._adapter) {
      // Throw error. Follows net_legacy behaviour.
      throw new Error('Not running');
    }

    if (cb) {
      this.once('close', cb);
    }
    this._adapter.close();
    this._adapter = null;

    var self = this;
    process.nextTick(function() {
      self.emit('close');
    });

    return this;
  };

  Server.prototype.setTimeout = function(timeout, cb) {
    this.timeout = timeout;
    this.timeoutCallback = cb;
  };

} else {
  debug('Using standard Node HTTP module');
  exports.Server = NodeHttp.Server;
  exports.ServerResponse = NodeHttp.ServerResponse;
  exports.createServer = function(requestListener) {
    return new NodeHttp.Server(requestListener);
  };
  exports._connectionListener = NodeHttp._connectionListener;
}
