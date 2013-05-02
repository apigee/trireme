/*
 * This is our implementation of the "http" module. Normally, it just delegates to Node's normal
 * JavaScript-built HTTP module. However, it can also delegate to an HTTP adaptor, which means that it
 * gives up control of networking and HTTP to a third party Java library instead, although it
 * implements the same basic interface.
 *
 * This module is normally initialized by Noderunner to load when the "http" module is included.
 */

var util = require('util');
var events = require('events');
var stream = require('stream');
var timers = require('timers');
var NodeHttp = require('node_http');
var HttpWrap = process.binding('http_wrap');
var domain = require('domain');
var assert = require('assert');

var debug;
if (process.env.NODE_DEBUG && /http/.test(process.env.NODE_DEBUG)) {
  debug = function(x) { console.error('HTTP: %s', x); };
} else {
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

if (HttpWrap.hasServerAdapter()) {
  debug('Using server adapter');

  var END_OF_FILE = {};

  function DummySocket() {
    if (!(this instanceof DummySocket)) return new DummySocket();
    events.EventEmitter.call(this);
  }

  util.inherits(DummySocket, events.EventEmitter);

  DummySocket.prototype.setTimeout = function(msecs, callback) {
    if (msecs > 0 && !isNaN(msecs) && isFinite(msecs)) {
      debug('Enrolling timeout in ' + msecs);
      timers.enroll(this, msecs);
      timers.active(this);
      if (callback) {
        this.once('timeout', callback);
      }
    } else if (msecs === 0) {
      debug('Unenrolling timeout');
      timers.unenroll(this);
      if (callback) {
        this.removeListener('timeout', callback);
      }
    }
  };

  DummySocket.prototype.clearTimeout = function(cb) {
    this.setTimeout(0, cb);
  };

  DummySocket.prototype._onTimeout = function() {
    debug('_onTimeout');
    this.emit('timeout');
  };

  DummySocket.prototype.active = function() {
    timers.active(this);
  };

  DummySocket.prototype.close = function() {
    if (!this.closed) {
      timers.unenroll(this);
      this.closed = true;
    }
  };

  DummySocket.prototype.destroy = function() {
    if (this._outgoing) {
      this._outgoing.destroy();
    } else {
      this.close();
    }
    this.emit('close');
  };

  /*
   * This object represents the "http.ServerResponse" object from Node with code that wraps our
   * adapter. Frameworks like Express like to add to this object, take things away, and even
   * add and remove functions from the prototype. So for that reason we do NOT add any functions
   * to this object's prototype that are not documented in the Node docs.
   */

  function ServerResponse(adapter, conn) {
    if (!(this instanceof ServerResponse)) return new ServerResponse();
    stream.Writable.call(this, {decodeStrings: true});

    this.statusCode = 200;
    this.headersSent = false;
    this.ended = false;
    this.sendDate = true;
    this._headers = {};
    this._adapter = adapter;
    this.connection = conn;
  }

  util.inherits(ServerResponse, stream.Writable);
  exports.ServerResponse = ServerResponse;

  ServerResponse.prototype.writeContinue = function() {
    throw Error('writeContinue not implemented');
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

  ServerResponse.prototype._write = function(data, encoding, cb) {
    if (!this._savedHeaders) {
      this.writeHead(this.statusCode);
    }

    this.connection.active();
    if (!this.headersSent) {
      // Just send one additional chunk of data
      debug('Sending http headers status = ' + this.statusCode +
           ' data = ' + (data ? data.length : 0));
      // We should always get a buffer with no encoding in this case
      this._adapter.send(this.statusCode, this.sendDate, this._savedHeaders, data, encoding, null, false);
      this.headersSent = true;
    } else {
      debug('Sending data = ' + (data ? data.length : 0));
      this._adapter.sendChunk(data, encoding, false);
    }
    // TODO register a future rather than accepting everything
    cb();
  };

  ServerResponse.prototype._send = function() {
    // Nothing to do here -- included for test compatibility
  };

  ServerResponse.prototype.end = function(data, encoding) {
    debug('end');
    if (!this._savedHeaders) {
      this.writeHead(this.statusCode);
    }

    this.connection.active();
    var self = this;
    stream.Writable.prototype.end.call(this, data, encoding, function() {
      self.connection.close();
      if (self.headersSent) {
        debug('Sending end of response');
        self._adapter.sendChunk(null, null, self._trailers, true);
      } else {
        // We will only get here if we are sending an empty response
        self._adapter.send(self.statusCode, self.sendDate, self._savedHeaders,
                           null, null, self._trailers, true);
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
    this.connection.close();
  };

  ServerResponse.prototype.setTimeout = function(timeout, cb) {
    this.connection.setTimeout(timeout, cb);
  };

  ServerResponse.prototype.clearTimeout = function(cb) {
    this.connectio.clearTimeout(cb);
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
    if (!(this instanceof ServerRequest)) return new ServerRequest(adapter);
    NodeHttp.IncomingMessage.call(this);

    this._adapter = adapter;
    this._pendings = [];
    this.httpVersionMajor = adapter.requestMajorVersion;
    this.httpVersionMinor = adapter.requestMinorVersion;
    this.httpVersion = adapter.requestMajorVersion + '.' + adapter.requestMinorVersion;
    this.url = adapter.requestUrl;
    this.connection = conn;
    this.socket = conn;
  }

  util.inherits(ServerRequest, NodeHttp.IncomingMessage);

  function addPending(self, chunk) {
    self.connection.active();
    if (self._readPending && !self._pendings.length) {
      self._readPending = pushChunk(self, chunk);
    } else {
      debug('Adding ' + chunk.length + ' bytes to the push queue');
      self._pendings.push(chunk);
    }
  };

  function pushChunk(self, chunk) {
    debug('Pushing ' + chunk.length + ' bytes directly');
    if (chunk === END_OF_FILE) {
      return self.push(null);
    } else {
      return self.push(chunk);
    }
  }

  ServerRequest.prototype._read = function(maxLen) {
    this._readPending = true;
    while (this._readPending && this._pendings.length) {
      var chunk = this._pendings.shift();
      this._readPending = pushChunk(this, chunk);
    }
  };

  ServerRequest.prototype.setTimeout = function(timeout, cb) {
    this.connection.setTimeout(timeout, cb);
  };

  ServerRequest.prototype.clearTimeout = function(cb) {
    this.connectio.clearTimeout(cb);
  };

  function Server(requestListener) {
    if (!(this instanceof Server)) return new Server(requestListener);
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
  function handleError(err, info) {
    debug('Handling server error and sending to adapter');
    if (err.stack) {
      debug(err.message);
      debug(err.stack);
    }
    if (info.outgoing.headersSent) {
      debug('Response already sent -- closing');
      info.destroy();

    } else {
      var msg = getErrorMessage(err);
      var stack = err.stack ? err.stack : undefined;
      info.fatalError(msg, stack);
    }
  }

  /*
   * Called directly by the adapter when a message arrives and all the headers have been received.
   */
  Server.prototype._onHeaders = function(info) {
    debug('onHeadersComplete');
    var headers = info.getRequestHeaders();
    var url = info.requestUrl;
    var self = this;

    var conn = new DummySocket();
    if (this.timeout && (this.timeout > 0)) {
      conn.setTimeout(this.timeout, function() {
        self.timeoutCallback(conn);
      });
    }
    conn.active();

    info.incoming = new ServerRequest(info, conn);

    var n = headers.length;

    // If parser.maxHeaderPairs <= 0 - assume that there're no limit
    if (this.maxHeaderPairs > 0) {
      n = Math.min(n, parser.maxHeaderPairs);
    }

    for (var i = 0; i < n; i += 2) {
      var k = headers[i];
      var v = headers[i + 1];
      info.incoming._addHeaderLine(k, v);
    }

    info.incoming.method = info.requestMethod;

    info.outgoing = new ServerResponse(info, conn);
    conn._outgoing = info.outgoing;

    info.onchannelclosed = function() {
      debug('Server channel closed');
      if (!info.outgoing.ended) {
        info.outgoing.emit('close');
      }
      conn.close();
    };

    info.onwritecomplete = function(err) {
      debug('write complete: ' + err + ' outstanding: ' + info.outgoing._outstanding);
      if (err) {
        info.outgoing.emit('error', err);
      }
    };

    info.domain = domain.create();
    info.domain.on('error', function(err) {
      handleError(err, info);
    });
    info.domain.run(function() {
      self.emit('request', info.incoming, info.outgoing);
    });
  };

  /*
   * This is called directly by the adapter when data is received for the message body.
   */
  function onBody(info, b) {
    info.incoming.connection.active();
    info.domain.run(function() {
      addPending(info.incoming, b);
    });
  }

  /*
   * This is called directly by the adapter when the complete message has been received.
   */
  function onMessageComplete(info) {
    info.incoming.connection.active();
    var incoming = info.incoming;
    if (!incoming.upgrade) {
      info.domain.run(function() {
        addPending(incoming, END_OF_FILE);
      });
    }
  }

  function onClose(info) {
    debug('onClose');
    info.incoming.connection.close();
    if (!info.outgoing.ended) {
      info.domain.run(function() {
        info.outgoing.ended = true;
        info.incoming.emit('close');
        info.outgoing.emit('close');
      });
    }
  }

  function listen(self, address, port, addressType, backlog, fd) {
    self._adapter = HttpWrap.createServerAdapter();
    if (self.tlsParams) {
      self._adapter.setTLSParams(self.tlsParams);
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

    self._adapter.onheaders = function(info) {
      self._onHeaders(info);
    };
    self._adapter.ondata = onBody;
    self._adapter.oncomplete = onMessageComplete;
    self._adapter.onclose = onClose;

    process.on('uncaughtException', function(err) {
      if ((self._adapter !== null) && !self.exiting) {
        self.exiting = true;
        var msg = getErrorMessage(err);
        var stack = err.stack ? err.stack : undefined;
        self._adapter.fatalError(msg, stack);
      }
    });
    process.on('exit', function() {
      if ((self._adapter !== null) && !self.exiting) {
        self.exiting = true;
        self._adapter.fatalError('Premature script exit');
      }
    });

    process.nextTick(function() {
      self.emit('listening');
    });
  }

  Server.prototype.listen = function() {
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
