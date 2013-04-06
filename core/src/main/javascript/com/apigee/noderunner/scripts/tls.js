// This is a version of the interface for the "tls" module that works in the Noderunner environment.
// Why? Because the native node TLS module is a hand-written, in JavaScript, implementation of SSL on top
// of OpenSSL. The SSL functionality available in Java (SSLEngine) is very different, and mapping the two
// is going to be ugly. So, this module uses standard "net" to handle the ciphertext connection, and an internal
// Java module that wraps SSLEngine for the SSL bit.
// This also allows us to use standard Java mechanisms to replace SSLEngine with alternatives in highly
// secure environments.

var assert = require('assert');
var util = require('util');
var net = require('net');
var Stream = require('stream');
var wrap = process.binding('ssl_wrap');
var EventEmitter = require('events').EventEmitter;
var tlscheckidentity = require('tls_checkidentity');

var debug;
if (process.env.NODE_DEBUG && /tls/.test(process.env.NODE_DEBUG)) {
  debug = function(x) { console.error('TLS:', x); };
} else {
  debug = function() { };
}

var END_SENTINEL = {};
var DEFAULT_REJECT_UNAUTHORIZED = true;
var DEFAULT_HANDSHAKE_TIMEOUT = 60000;

// Store the SSL context for a client. We could do some caching here to speed up clients.
function getContext(opts, rejectUnauthorized) {
  var ctx;
  if (!opts || (!opts.keystore && !opts.truststore && rejectUnauthorized)) {
    debug('Creating default SSL context');
    ctx = wrap.createDefaultContext();
  } else {
    ctx = wrap.createContext();
    if (!rejectUnauthorized) {
      debug('Using SSL context that trusts everyone');
      ctx.setTrustEverybody();
    } else if (opts.truststore) {
      debug('Using trust store ' + opts.truststore);
      ctx.setTrustStore(opts.truststore);
    } else {
      debug('Context will not trust anybody');
    }
    if (opts.keystore) {
      debug('Client using key store ' + opts.keystore);
      ctx.setKeyStore(opts.keystore, opts.passphrase);
    }

    ctx.init();
  }
  return ctx;
}

function Server() {
  var options, listener;

  if (typeof arguments[0] == 'function') {
    options = {};
    listener = arguments[0];
  } else {
    options = arguments[0] || {};
    listener = arguments[1];
  }
  if (!(this instanceof Server)) return new Server(options, listener);

  var self = this;
  if ((options == undefined) || (options.keystore == undefined)) {
    throw 'missing keystore (must be in Java JKS format)';
  }
  if (listener) {
    self.on('secureConnection', listener);
  }
  self.closed = false;

  self.context = wrap.createContext();
  debug('Server using key store ' + options.keystore);
  self.context.setKeyStore(options.keystore, options.passphrase);
  if (options.truststore) {
    debug('Server using trust store ' + options.truststore);
    self.context.setTrustStore(options.truststore);
  }
  if (options.crl) {
    debug('Server using CRL ' + options.crl);
    self.context.setCRL(options.crl);
  }

  self.context.init();

  if (options.ciphers) {
    var tmpEngine = self.context.createEngine(false);
    if (!tmpEngine.validateCiphers(options.ciphers)) {
      throw 'Invalid cipher list: ' + options.ciphers;
    }
  }

  var handshakeTimeout = options.handshakeTimeout;
  if (!handshakeTimeout) {
    handshakeTimeout = DEFAULT_HANDSHAKE_TIMEOUT;
  }

  self.netServer = net.createServer(options, function(connection) {
    var engine = self.context.createEngine(false);
    if (options.requestCert) {
      if (options.rejectUnauthorized) {
        debug('Client auth required');
        engine.setClientAuthRequired(true);
      } else {
        debug('Client auth requested');
        engine.setClientAuthRequested(true);
      }
    }

    if (options.ciphers) {
      engine.setCiphers(options.ciphers);
    }
    var clearStream = new CleartextStream();
    clearStream.init(true, self, connection, engine);
    clearStream.setHandshakeTimeout(handshakeTimeout);
  });
  return self;
}
util.inherits(Server, net.Server);
exports.Server = Server;

exports.createServer = function () {
  return new Server(arguments[0], arguments[1]);
};

Server.prototype.listen = function() {
  var self = this;
  var port = arguments[0];
  var host;
  var callback;

  if (typeof arguments[1] === 'function') {
    callback = arguments[1];
  } else if (typeof arguments[2] === 'function') {
    host = arguments[1];
    callback = arguments[2];
  }

  if (callback !== undefined) {
    self.once('listening', callback);
  }
  this.netServer.on('listening', function() {
    debug('listening');
    self.emit('listening');
  });
  if (host === undefined) {
    self.netServer.listen(port);
  } else {
    self.netServer.listen(port, host);
  }
};

exports.connect = function() {
  var options;
  var callback;

  var lastArg = 0;
  if (typeof arguments[0] === 'object') {
    // options
    options = arguments[0];
  } else {
    var port = arguments[0];
    if (arguments.length === 2) {
      // port
      options = { port: port };
    } else if (typeof arguments[1] === 'object') {
      // port, options
      options = arguments[1];
      options.port = port;
      lastArg = 1;
    } else if (typeof arguments[1] === 'string') {
      if (typeof arguments[2] === 'object') {
        // port, host, options
        options = arguments[2];
        options.port = port;
        options.host = arguments[1];
        lastArg = 2;
      } else {
        // port, host
        options = { port: port, host: arguments[1] };
        lastArg = 1;
      }
    }
  }
  if (!options) {
    throw 'Invalid arguments to connect';
  }
  if (typeof arguments[lastArg + 1] === 'function') {
    callback = arguments[lastArg + 1];
  }

  var rejectUnauthorized = options.rejectUnauthorized;
  if (rejectUnauthorized === undefined) {
    rejectUnauthorized = DEFAULT_REJECT_UNAUTHORIZED;
  }
  var hostname = options.servername || options.host || 'localhost';
  options.host = hostname;

  var sslContext = getContext(options, rejectUnauthorized);

  var engine = sslContext.createEngine(true);
  var sslConn = new CleartextStream();
  var netConn;
  if (options.socket) {
    netConn = options.socket;
  } else {
    var netOptions = {
      host: options.host,
      port: options.port,
      localAddress: options.localAddress,
      path: options.path,
      allowHalfOpen: options.allowHalfOpen
    };
    // Fix up the HTTP case, where "path" is used to mean something else, not a UNIX socket path
    if (options.host || options.port) {
      netOptions.path = undefined;
    }
    debug('Connecting with ' + JSON.stringify(netOptions));
    netConn = net.connect(netOptions, function() {
      sslConn.remoteAddress = netConn.remoteAddress;
      sslConn.remotePort = netConn.remotePort;
      sslConn.engine.beginHandshake();
      writeCleartext(sslConn);
    });
  }
  sslConn.init(false, undefined, netConn, engine);
  if (callback) {
    sslConn.on('secureConnect', callback);
  }
  if (options.socket) {
    sslConn.engine.beginHandshake();
    writeCleartext(sslConn);
  }
  return sslConn;
};

exports.createSecurePair = function() {
  throw 'Not implemented';
};

exports.checkServerIdentity = tlscheckidentity.checkServerIdentity;

Server.prototype.close = function() {
  if (!this.closed) {
    debug('Server.close');
    this.netServer.close(function() {
      debug('Server close complete');
      this.emit('close');
    });
    this.closed = true;
  }
};

Server.prototype.addContext = function(hostname, credentials) {
  // TODO something...
};

Server.prototype.address = function() {
  return this.netServer.address();
};

var counter = 0;

function CleartextStream() {
  var options = { decodeStrings: true, encoding: null };
  Stream.Duplex.call(this, options);
  this.id = ++counter;
}
util.inherits(CleartextStream, Stream.Duplex);

CleartextStream.prototype.init = function(serverMode, server, connection, engine) {
  var self = this;
  self.serverMode = serverMode;
  self.server = server;
  self.socket = connection;
  self.engine = engine;
  self.closed = false;
  self.closing = false;
  self.remoteAddress = self.socket.remoteAddress;
  self.remotePort = self.socket.remotePort;
  self.encrypted = true;
  self.readQueue = [];

  connection.on('readable', function() {
    debug(self.id + ' onReadable');
    var data = connection.read();
    readCiphertext(self, data);
  });
  connection.on('end', function() {
    debug(self.id + ' onEnd');
    handleEnd(self);
  });

  connection.on('error', function(err) {
    debug(self.id + ' onError');
    self.emit('error', err);
  });
  connection.on('close', function() {
    debug(self.id + ' onClose');
    if (!self.closed) {
      self.closed = true;
      process.nextTick(function() {
          self.emit('close', false);
      });
    }
  });
  connection.on('timeout', function() {
    debug(self.id + ' onTimeout');
    self.emit('timeout');
  });
};

CleartextStream.prototype.setHandshakeTimeout = function(timeout) {
  var self = this;
  this.handshakeTimeout = setTimeout(function() {
    debug('Handshake timeout');
    self.server.emit('clientError', new Error('Handshake timeout'), self);
  }, timeout);
};

function doClose(self, err) {
  debug(self.id + ' destroy');
  self.closed = true;
  self.socket.destroy();
  process.nextTick(function() {
    self.emit('close', err ? true : false);
  });
}

CleartextStream.prototype.destroySoon = function() {
  if (this.socket.writable)
    this.end();

  if (this.socket._writableState.finished)
    this.destroy();
  else
    this.once('finish', this.destroy);
};

CleartextStream.prototype.address = function() {
  return this.socket.address();
};

CleartextStream.prototype._write = function(data, encoding, cb) {
  debug(this.id + ' _write(' + (data ? data.length : 0) + ')');

  if (this.handshakeComplete) {
    writeData(this, data, 0, cb);

  } else {
    var self = this;
    debug('Waiting for handshake to write');
    this.on('secureConnect', function() {
      writeData(self, data, 0, cb);
    });
  }
};

function writeData(self, data, offset, cb) {
  var chunk = offset ? data.slice(offset) : data;
  writeCleartext(self, chunk, function(written) {
    debug('Net wrote ' + written + ' bytes from ' + data.length);
    var newOffset = offset + written;
    if (newOffset < data.length) {
      writeData(self, data, newOffset, cb);
    } else {
      if (cb != undefined) {
        cb();
      }
    }
  });
}

CleartextStream.prototype.end = function(data, encoding) {
  debug(this.id + ' end(' + (data ? data.length : 0) + ')');;

  var self = this;
  if (this.handshakeComplete) {
    doEnd(self, data, encoding);

  } else {
    debug('Waiting for handshake to send end');
    this.on('secureConnect', function() {
      doEnd(self, data, encoding);
    });
  }
};

function doEnd(self, data, encoding) {
  if (!self.ended) {
    self.on('finish', function() {
      debug(self.id + ' onFinish: Closing SSL outbound');
      self.ended = true;
      self.engine.closeOutbound();
      while (!self.engine.isOutboundDone()) {
        writeCleartext(self);
      }
    });
  }
  Stream.Writable.prototype.end.call(self, data, encoding);
}

CleartextStream.prototype._read = function(maxLen) {
  debug('_read');
  this.readPending = true;
  while (this.readPending && (this.readQueue.length)) {
    var chunk = this.readQueue.shift();
    pushRead(this, chunk);
  }
};

function pushRead(self, d) {
  debug('Pushing ' + (d ? d.length : 0) + ' readPending = ' + self.readPending);
  if (d === END_SENTINEL) {
    if (self.onend) {
      self.onend();
    } else if (self.readPending) {
      self.push(null);
    } else {
      self.readQueue.push(null);
    }
  } else {
    if (self.ondata) {
      self.ondata(d, 0, d.length);
    } else if (self.readPending) {
      self.readPending = self.push(d);
    } else {
      self.readQueue.push(d);
    }
  }
}

// Got an end from the network
function handleEnd(self) {
  if (!self.closed) {
    if (!self.handshakeComplete) {
      self.handleSSLError('Connection closed by peer');
    } else if (self.ended) {
      debug(self.id + ' received end from the other side after our own end');
      doClose(self);
    } else {
      debug(self.id + ' Closing SSL inbound without a close from the client', self.id);
      /* assess if we need this.
      self.engine.closeInbound();
      while (!self.engine.isInboundDone()) {
        writeCleartext(self);
      }
      */
      pushRead(self, END_SENTINEL);
      doClose(self);
    }
  }
}

CleartextStream.prototype.setTimeout = function(timeout, cb) {
  if (cb) {
    this.once('timeout', cb);
  }
  this.socket.setTimeout(timeout);
};

CleartextStream.prototype.setNoDelay = function(noDelay) {
  this.socket.setNoDelay(noDelay);
};

CleartextStream.prototype.setKeepAlive = function(keepAlive, ms) {
  this.socket.setKeepAlive(keepAlive, ms);
};

CleartextStream.prototype.destroy = function() {
  if (!this.closed) {
    debug('destroy');
    doClose(this);
  }
};

CleartextStream.prototype.justHandshaked = function() {
  if (this.handshakeTimeout) {
    clearTimeout(this.handshakeTimeout);
  }
  this.readable = this.writable = true;
  this.handshakeComplete = true;
  this.authorized = true;

  if (this.serverMode) {
    this.server.emit('secureConnection', this);
  } else {
    this.emit('secureConnect');
    this.emit('connect');
  }
};

CleartextStream.prototype.handleSSLError = function(err) {
  debug(this.id + ' Received an error (handshake complete = ' +
        this.handshakeComplete + '): ' + err);
  if (this.handshakeComplete) {
    this.emit('error', err);
    doClose(this, true);
  } else {
    if (this.handshakeTimeout) {
      clearTimeout(this.handshakeTimeout);
    }
    if (this.serverMode) {
      // On the server -- just emit and close
      this.server.emit('clientError', err, this);
      this.destroy();
    } else {
      this.authorized = false;
      this.authorizationError = err;
      this.emit('error', err);
      doClose(this, true);
    }
  }
};

CleartextStream.prototype.address = function() {
  return this.socket.address();
};

CleartextStream.prototype.getCipher = function() {
  return this.engine.getCipher();
};

CleartextStream.prototype.getSession = function() {
  return this.engine.getSession();
};

CleartextStream.prototype.isSessionReused = function() {
  return this.engine.isSessionReused();
};

CleartextStream.prototype.getPeerCertificate = function() {
  var cert = this.engine.getPeerCertificate();
  if (cert === undefined) {
    return undefined;
  }
  if (cert.subject) {
    cert.subject = tlscheckidentity.parseCertString(cert.subject);
  }
  if (cert.issuer) {
    cert.issuer = tlscheckidentity.parseCertString(cert.issuer);
  }
  if (cert.subjectAltName) {
    cert.subject.subjectAltName = cert.subjectAltName;
  }
  return cert;
};

function readCiphertext(self, d, offset, end) {
  var data;
  if (offset || end) {
    data = d.slice(offset, end);
  } else {
    data = d;
  }
  var sslResult = self.engine.unwrap(data);
  debug(self.id + ' readCiphertext(' + (data ? data.length : 0) + '): SSL status ' + sslResult.status +
        ' consumed ' + sslResult.consumed + ' produced ' + (sslResult.data ? sslResult.data.length : 0));
  if (sslResult.justHandshaked) {
    setImmediate(function() {
      self.justHandshaked();
    });
  }

  switch (sslResult.status) {
    case self.engine.NEED_WRAP:
      writeCleartext(self);
      break;
    case self.engine.NEED_UNWRAP:
      // Sometimes we need to unwrap while we're unwrapping I guess
      readCiphertext(self);
      break;
    case self.engine.NEED_TASK:
      self.engine.runTask(function() {
        readCiphertext(self);
      });
      break;
    case self.engine.UNDERFLOW:
      // Nothing to do -- wait until we get more data
      break;
    case self.engine.OK:
      if (sslResult.data) {
        pushRead(self, sslResult.data);
      }
      if (sslResult.remaining > 0) {
        // Once handshaking is done, we might need to unwrap again with the same data
        readCiphertext(self);
      }
      break;
    case self.engine.CLOSED:
      if (!self.closed) {
        if (self.ended) {
          doClose(self);
        } else {
          self.ended = true;
          self.emit('end');
        }
      }
      break;
    case self.engine.ERROR:
      self.handleSSLError(sslResult.error);
      break;
    default:
      throw 'Unexpected SSL engine status ' + sslResult.status;
  }
}

function writeCleartext(self, data, cb) {
  var sslResult = self.engine.wrap(data);
  var bytesConsumed = sslResult.consumed;
  debug(self.id + ' writeCleartext(' + (data ? data.length : 0) + '): SSL status ' + sslResult.status +
        ' length ' + (sslResult.data ? sslResult.data.length : 0) + ' consumed ' + bytesConsumed);

  if (sslResult.data) {
    debug(self.id + ' Writing ' + sslResult.data.length);
    self.socket.write(sslResult.data, function() {
      debug(self.id + ' Write complete');
      if (cb) {
        cb(bytesConsumed);
      }
    });
  }
  if (sslResult.justHandshaked) {
    setImmediate(function() {
        self.justHandshaked();
    });
  }

  switch (sslResult.status) {
    case self.engine.NEED_WRAP:
      bytesConsumed += writeCleartext(self);
      break;
    case self.engine.NEED_UNWRAP:
      readCiphertext(self);
      break;
    case self.engine.NEED_TASK:
      self.engine.runTask(function() {
        writeCleartext(self);
      });
      break;
    case self.engine.UNDERFLOW:
    case self.engine.OK:
      break;
    case self.engine.CLOSED:
      if (!self.closed) {
        if (self.ended) {
          doClose(self);
        } else {
          self.ended = true;
          self.emit('end');
        }
      }
      break;
    case self.engine.ERROR:
      self.handleSSLError(sslResult.error);
      break;
    default:
      throw 'Unexpected SSL engine status ' + sslResult.status;
  }
  return bytesConsumed;
}
