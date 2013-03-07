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
var NodeHttp = require('node_http');
var HttpWrap = process.binding('http_wrap');
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

  function ServerResponse(adapter) {
    if (!(this instanceof ServerResponse)) return new ServerResponse();
    events.EventEmitter.call(this);

    this.statusCode = 200;
    this.headersSent = false;
    this.ended = false;
    this.sendDate = true;
    this._headers = {};
    this._adapter = adapter;
    this._outstanding = 0;
  }

  util.inherits(ServerResponse, events.EventEmitter);

  exports.ServerResponse = ServerResponse;

  ServerResponse.prototype.writeContinue = function() {
    debug('writeContinue not implemented!');
    // TODO!
  }

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
  }

  ServerResponse.prototype.write = function(data, encoding) {
    if (!this._savedHeaders) {
      this.writeHead(this.statusCode);
    }

    if (typeof data !== 'string' && !Buffer.isBuffer(data)) {
      throw new TypeError('first argument must be a string or Buffer');
    }

    if (data.length === 0) return false;

    if (!this.headersSent) {
      // Just send one additional chunk of data
      debug('Sending http headers status = ' + this.statusCode);
      this._adapter.send(this.statusCode, this.sendDate, this._savedHeaders, null, null, null, false);
      this.headersSent = true;
    }
    var result = this._adapter.sendChunk(data, encoding, false);
    debug('Sent data result = ' + result);
    if (!result) {
      this._outstanding++;
    }
    return result;
  }

  ServerResponse.prototype._send = function() {
    // Nothing to do here -- included for test compatibility
  }

  ServerResponse.prototype.end = function(data, encoding) {
    if (!this._savedHeaders) {
      this.writeHead(this.statusCode);
    }

    if (data && (typeof data !== 'string' && !Buffer.isBuffer(data))) {
      throw new TypeError('first argument must be a string or Buffer');
    }

    var result;
    if (this.headersSent) {
      result = this._adapter.sendChunk(data, encoding, this._trailers, true);
      debug('Sent last data result = ' + result);
    } else {
      result = this._adapter.send(this.statusCode, this.sendDate, this._savedHeaders,
                                  data, encoding, this._trailers, true);
      this.headersSent = true;
      debug('Sent single message result = ' + result);
    }
    if (!result) {
      this._outstanding++;
    }
    this.ended = true;
    return result;
  }

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
  }

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
  };

  ServerResponse.prototype.setHeader = NodeHttp.OutgoingMessage.prototype.setHeader;
  ServerResponse.prototype.getHeader = NodeHttp.OutgoingMessage.prototype.getHeader;
  ServerResponse.prototype.removeHeader = NodeHttp.OutgoingMessage.prototype.removeHeader;
  ServerResponse.prototype._renderHeaders = NodeHttp.OutgoingMessage.prototype._renderHeaders;

  function ServerRequest(adapter) {
    if (!(this instanceof ServerRequest)) return new ServerRequest(adapter);
    NodeHttp.IncomingMessage.call(this);

    this._adapter = adapter;
    this.httpVersionMajor = adapter.requestMajorVersion;
    this.httpVersionMinor = adapter.requestMinorVersion;
    this.httpVersion = adapter.requestMajorVersion + '.' + adapter.requestMinorVersion;
    this.url = adapter.requestUrl;
    this._paused = false;
  }

  util.inherits(ServerRequest, NodeHttp.IncomingMessage);

  ServerRequest.prototype.pause = function() {
    this._paused = true;
    this._adapter.pause();
  }

  ServerRequest.prototype.resume = function() {
    this._paused = false;
    this._adapter.resume();
    this._emitPending();
  }

  // Copied from http.js, because END_OF_FILE is not exported and we need it
  ServerRequest.prototype._emitPending = function(callback) {
  if (this._pendings.length) {
    var self = this;
    process.nextTick(function() {
      while (!self._paused && self._pendings.length) {
        var chunk = self._pendings.shift();
        if (chunk !== END_OF_FILE) {
          assert(Buffer.isBuffer(chunk));
          self._emitData(chunk);
        } else {
          assert(self._pendings.length === 0);
          self.readable = false;
          self._emitEnd();
        }
      }

      if (callback) {
        callback();
      }
    });
  } else if (callback) {
    callback();
  }
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

  Server.prototype._onHeaders = function(info) {
    debug('onHeadersComplete');
    var headers = info.getRequestHeaders();
    var url = info.requestUrl;

    info.incoming = new ServerRequest(info);


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

    info.outgoing = new ServerResponse(info);

    info.onchannelclosed = function() {
      debug('Server channel closed');
      if (!info.outgoing.ended) {
        info.outgoing.emit('close');
      }
    };

    info.onwritecomplete = function(err) {
      debug('write complete: ' + err + ' outstanding: ' + info.outgoing._outstanding);
      if (err) {
        info.outgoing.emit('error', err);
      }
      if (info.outgoing._outstanding > 0) {
        if (--info.outgoing._outstanding == 0) {
          if (!err) {
            info.outgoing.emit('drain');
          }
        }
      }
    };

    this.emit('request', info.incoming, info.outgoing);
  }

  function onBody(info, b) {
    debug('onBody len = ' + b.length);
    var incoming = info.incoming;
    if (incoming._paused || incoming._pendings.length) {
      incoming._pendings.push(b);
    } else {
      incoming._emitData(b);
    }
  }

  function onMessageComplete(info) {
    debug('onMessageComplete');
    var incoming = info.incoming;

    // Emit any trailing headers.
    // TODO
    /*
    var headers = parser._headers;
    if (headers) {
      for (var i = 0, n = headers.length; i < n; i += 2) {
        var k = headers[i];
        var v = headers[i + 1];
        parser.incoming._addHeaderLine(k, v);
      }
      parser._headers = [];
      parser._url = '';
    }
    */

    if (!incoming.upgrade) {
      // For upgraded connections, also emit this after parser.execute
      if (incoming._paused || incoming._pendings.length) {
        incoming._pendings.push(END_OF_FILE);
      } else {
        incoming.readable = false;
        incoming._emitEnd();
      }
    }
  }

  function onClose(info) {
    debug('onClose outgoing.complete = ' + info.outgoing.complete);
    if (!info.outgoing.ended) {
      info.outgoing.ended = true;
      info.incoming.emit('close');
      info.outgoing.emit('close');
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
    }
    self._adapter.ondata = onBody;
    self._adapter.oncomplete = onMessageComplete;
    self._adapter.onclose = onClose;

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
    this._handle = null;

    process.nextTick(function() {
      this.emit('close');
    });

    return this;
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
