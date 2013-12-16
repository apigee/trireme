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
var debugEnabled;
if (process.env.NODE_DEBUG && /tls/.test(process.env.NODE_DEBUG)) {
  debugEnabled = true;
  debug = function(x) { console.error('TLS:', x); };
} else {
  debug = function() { };
}

var END_SENTINEL = {};
var DEFAULT_REJECT_UNAUTHORIZED = ('0' !== process.env.NODE_TLS_REJECT_UNAUTHORIZED);
var DEFAULT_HANDSHAKE_TIMEOUT = 60000;

function toBuf(b) {
  if (typeof b === 'string') {
    return new Buffer(b, 'ascii');
  } else if (b instanceof Buffer) {
    return b;
  }
  throw 'Argument must be a string or a buffer';
}

// Store the SSL context for a client. We could do some caching here to speed up clients.
function getContext(opts, rejectUnauthorized) {
  var ctx = wrap.createContext();

  if (!rejectUnauthorized) {
    debug('Using SSL context that trusts everyone');
    ctx.setTrustEverybody();
  } else if (opts.truststore) {
    if (debugEnabled) {
      debug('Using Java trust store ' + opts.truststore);
    }
    ctx.setTrustStore(opts.truststore);
  }

  if (opts.ca) {
    if (debugEnabled) {
      debug('Client using array of ' + opts.ca.length + ' certs');
    }
    if (opts.ca.length === 0) {
      // Special case
      ctx.addTrustedCert(0, null);
    } else {
      for (var i = 0; i < opts.ca.length; i++) {
        ctx.addTrustedCert(i, toBuf(opts.ca[i]));
      }
    }
  }

  if (opts.keystore) {
    if (debugEnabled) {
      debug('Client using key store ' + opts.keystore);
    }
    ctx.setKeyStore(opts.keystore, opts.passphrase);
  } else if (opts.pfx) {
    if (debugEnabled) {
      debug('Client using PFX key and cert');
    }
    ctx.setPfx(toBuf(opts.pfx), opts.passphrase);
  } else {
    if (opts.key) {
      debug('Client using PEM key');
      ctx.setKey(toBuf(opts.key), opts.passphrase);
    }
    if (opts.cert) {
      debug('Client using PEM cert');
      ctx.setCert(toBuf(opts.cert));
    }
  }

  ctx.init();
  return ctx;
}

/*
 * tls.Server is a new class that delegates to a net.Server object for the real work.
 * It then replaces the guts of any connection that it receives so that it turns into a TLS connection.
 */

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
  if (listener) {
    self.on('secureConnection', listener);
  }
  self.closed = false;

  self.rejectUnauthorized = options.rejectUnauthorized;
  if (self.rejectUnauthorized === undefined) {
    self.rejectUnauthorized = DEFAULT_REJECT_UNAUTHORIZED;
  }

  self.context = wrap.createContext();

  if (options.keystore) {
    if (debugEnabled) {
      debug('Server using Java key store ' + options.keystore);
    }
    self.context.setKeyStore(options.keystore, options.passphrase);
  } else if (options.pfx) {
    if (debugEnabled) {
      debug('Server using PFX key and cert');
    }
    self.context.setPfx(toBuf(options.pfx), options.passphrase);
  } else {
    if (options.key) {
      debug('Server using PEM key');
      self.context.setKey(toBuf(options.key), options.passphrase);
    }
    if (options.cert) {
      debug('Server using PEM cert');
      self.context.setCert(toBuf(options.cert));
    }
  }
  if (!options.keystore && !options.pfx && !options.cert) {
    throw 'Missing certificate';
  }
  if (!options.keystore && !options.pfx && !options.key) {
    throw 'Missing key';
  }

  if (options.truststore) {
    // Use an explicit Java trust store
    if (debugEnabled) {
      debug('Server using trust store ' + options.truststore);
    }
    self.context.setTrustStore(options.truststore);
  } else if (!self.rejectUnauthorized || !options.requestCert) {
    // Client cert requested but we shouldn't reject everyone right away, but set "authorized".
    // Do this using an all-trusting trust manager
    self.context.setTrustEverybody();
  }
  // Otherwise, in "init" we will set up a Java trust manager only for the supplied CAs

  if (options.ca) {
    if (debugEnabled) {
      debug('Server using array of ' + options.ca.length + ' certs');
    }
    if (options.ca.length === 0) {
      // Special case
      self.context.addTrustedCert(0, null);
    } else {
      for (var i = 0; i < options.ca.length; i++) {
        self.context.addTrustedCert(i, toBuf(options.ca[i]));
      }
    }
  }

  if (options.crl) {
    debug('Server using CRL');
    self.context.setCRL(toBuf(options.crl));
  }

  self.context.init();

  if (options.ciphers) {
    var tmpEngine = self.context.createEngine(false);
    if (!tmpEngine.validateCiphers(options.ciphers)) {
      throw 'Invalid cipher list: ' + options.ciphers;
    }
  }

  self._handshakeTimeout = options.handshakeTimeout;
  if (!self._handshakeTimeout) {
    self._handshakeTimeout = DEFAULT_HANDSHAKE_TIMEOUT;
  }

  self.netServer = net.createServer(options, function(connection) {
    onServerConnection(self, connection, options);
  });
  return self;
}
util.inherits(Server, net.Server);
exports.Server = Server;

function onServerConnection(self, connection, options) {
  var engine = self.context.createEngine(false);
  if (options.requestCert) {
    if (self.rejectUnauthorized) {
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

  assert(connection._handle);

  connection._tlsServer = self;
  engine.setUpConnection(true, connection._handle);
  engine.onread = connection._handle.onread;
  connection._handle.onread = null;
  engine.owner = connection._handle.owner;
  connection._handle = engine;
  connection.socket = connection;
  initTlsMethods(connection);

  self.rejectUnauthorized = self.rejectUnauthorized && options.requestCert;

  if (debugEnabled) {
    debug('Setting handshake timeout to ' + self._handshakeTimeout);
  }
  connection._handshakeTimeoutKey = setTimeout(function() {
    onServerHandshakeTimeout(self, connection);
  }, self._handshakeTimeout);

  engine.onhandshake = function(err) {
    if (debugEnabled) {
      debug('Server handshake complete. err = ' + err);
    }
    if (connection._handshakeTimeoutKey) {
      clearTimeout(connection._handshakeTimeoutKey);
    }
    onHandshakeComplete(connection, err);
  };
}

function onServerHandshakeTimeout(self, connection) {
  if (debugEnabled) {
    debug('Firing handshake timeout');
  }
  connection._handle.forceClose();
  self.emit('clientError', new Error('Server-side handshake timeout'), connection);
}

exports.createServer = function () {
  return new Server(arguments[0], arguments[1]);
};

exports.getCiphers = function() {
  return wrap.getCiphers();
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

/*
 * Connect by opening a regular net.Socket, then replacing the guts with guts that will implement the TLS protocol.
 */
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
  var sslContext = getContext(options, rejectUnauthorized);

  var engine = sslContext.createEngine(true);
  var netConn;
  if (options.socket) {
    onClientConnect(engine, options.socket);
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
    if (debugEnabled) {
      debug('Connecting with ' + JSON.stringify(netOptions));
    }
    netConn = net.connect(netOptions, function() {
      onClientConnect(engine, netConn);
    });
  }
  if (callback) {
    netConn.on('secureConnect', callback);
  }
  return netConn;
};

function onClientConnect(engine, connection) {
  engine.setUpConnection(false, connection._handle);
  engine.onread = connection._handle.onread;
  connection._handle.onread = null;
  engine.owner = connection._handle.owner;
  connection._handle = engine;
  // A hack to make a few internal things work
  connection.socket = connection;
  initTlsMethods(connection);

  // Lots of tests expect that we'll actually handshake before the first write
  debug('Initiating TLS handshake');

  connection._handle.initiateHandshake(function(err) {
    onHandshakeComplete(connection, err);
  });
}

function onHandshakeComplete(conn, err) {
  conn.authorized = conn._handle.peerAuthorized;
  conn.handshakeComplete = true;
  // TODO clear timeout
  if (err) {
    debug('Error on handshake: ' + err);
    conn.authorizationError = conn._handle.authorizationError;
    if (conn._tlsServer) {
      // On the server -- just emit and close
      conn._tlsServer.emit('clientError', err, this);
      conn._handle.forceClose();
    } else {
      // On the client, just emit and we close later, right?
      conn.authorized = false;
      conn.authorizationError = err;
      conn.emit('error', err);
      conn._handle.forceClose();
    }
  } else if (conn._tlsServer) {
    debug('TLS secure connection established on server');
    conn._tlsServer.emit('secureConnection', conn);
  } else {
    debug('TLS secure connection established on client');
    conn.emit('secureConnect');
  }
}

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

function initTlsMethods(conn) {
  conn.getCipher = function() {
    return this._handle.getCipher();
  };

  conn.getSession = function() {
    return this._handle.getSession();
  };

  conn.isSessionReused = function() {
    return this._handle.isSessionReused();
  };

  conn.getPeerCertificate = function() {
    var cert = this._handle.getPeerCertificate();
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
}

/*

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
  self.bytesWritten = 0;

  connection.on('readable', function() {
    handleReadable(self);
  });
  connection.on('end', function() {
    if (debugEnabled) {
      debug(self.id + ' onEnd');
    }
    handleEnd(self);
  });

  connection.on('error', function(err) {
    if (debugEnabled) {
      debug(self.id + ' onError');
    }
    self.emit('error', err);
  });
  connection.on('close', function() {
    if (debugEnabled) {
      debug(self.id + ' onClose');
    }
    if (!self.closed) {
      self.closed = true;
      process.nextTick(function() {
          self.emit('close', false);
      });
    }
  });
  connection.on('timeout', function() {
    if (debugEnabled) {
      debug(self.id + ' onTimeout');
    }
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
  if (debugEnabled) {
    debug(self.id + ' destroy');
  }
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
  if (debugEnabled) {
    debug(this.id + ' _write(' + (data ? data.length : 0) + ')');
  }

  this.bytesWritten += data.length;
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
  writeCleartext(self, data, offset, function(written) {
    if (debugEnabled) {
      debug(self.id + ' Net wrote ' + written + ' bytes from ' + data.length);
    }
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
  if (debugEnabled) {
    debug(this.id + ' end(' + (data ? data.length : 0) + ')');
  }

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
      if (debugEnabled) {
        debug(self.id + ' onFinish: Closing SSL outbound');
      }
      self.ended = true;
      self.engine.closeOutbound();
      while (!self.engine.isOutboundDone()) {
        writeCleartext(self);
      }
    });
  }
  Stream.Writable.prototype.end.call(self, data, encoding);
}

function handleReadable(self) {
  if (debugEnabled) {
    debug(self.id + ' onReadable');
  }
  self._socketReadable = true;
  if (!self._clientPaused) {
    readLoop(self);
  }
}

// Call readCiphertext, which might take multiple calls and be async, until it reads everything
function processReading(self, d, offset, cb) {
  var newOffset = offset;

  var readData = d;
  if (self._underflowBuf) {
    readData = Buffer.concat([ self._underflowBuf, d ]);
    self._underflowBuf = undefined;
  }

  if (debugEnabled) {
    debug(self.id + ' processReading');
  }
  readCiphertext(self, readData, newOffset, function(readCount, underflow) {
    newOffset += readCount;
    if (underflow) {
      self._underflowBuf = readData.slice(newOffset);
      cb();
    } else if (newOffset < readData.length) {
      processReading(self, readData, newOffset, cb);
    } else {
      cb();
    }
  });
}

function readLoop(self) {
  var data = self.socket.read();
  if (debugEnabled) {
    debug(self.id + ' Read ' + (data == null ? 0 : data.length) + ' from the socket');
  }
  if (data === null) {
    this._socketReadable = false;
  } else {
    processReading(self, data, 0, function() {
      readLoop(self);
    });
  }
}

CleartextStream.prototype._read = function(maxLen) {
  if (debugEnabled) {
    debug(this.id + ' _read');
  }
  this._clientPaused = false;
  if (this._socketReadable) {
    readLoop(this);
  }
};

function pushRead(self, d) {
  if (debugEnabled) {
    debug(self.id + ' Pushing ' + (d ? d.length : 0));
  }
  if (d === END_SENTINEL) {
    if (self.onend) {
      self.onend();
    } else {
      self.push(null);
    }
  } else {
    if (self.ondata) {
      self.ondata(d, 0, d.length);
    } else {
      if (!self.push(d)) {
        if (debugEnabled) {
          debug(self.id + ' push queue full');
        }
        self._clientPaused = true;
      }
    }
  }
}

// Got an end from the network
function handleEnd(self) {
  if (!self.closed) {
    if (!self.handshakeComplete) {
      self.handleSSLError('Connection closed by peer');
    } else if (self.ended) {
      if (debugEnabled) {
        debug(self.id + ' received end from the other side after our own end');
      }
      doClose(self);
    } else {
      if (debugEnabled) {
        debug(self.id + ' Closing SSL inbound without a close from the client', self.id);
      }
      // assess if we need this.
      //self.engine.closeInbound();
      //while (!self.engine.isInboundDone()) {
      //  writeCleartext(self);
      //}

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

  this.authorized = this.engine.peerAuthorized;
  this.authorizationError = this.engine.authorizationError;
  this.handshakeComplete = true;

  if (this.serverMode) {
    this.server.emit('secureConnection', this);
  } else {
    this.emit('secureConnect');
    this.emit('connect');
  }
};

CleartextStream.prototype.handleSSLError = function(err) {
  if (debugEnabled) {
    debug(this.id + ' Received an error (handshake complete = ' +
          this.handshakeComplete + '): ' + err);
  }
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

function readCiphertext(self, data, offset, cb) {
  var newOffset = offset;
  var sslResult = self.engine.unwrap(data, offset);
  var bytesConsumed = sslResult.consumed;
  newOffset += bytesConsumed;

  if (debugEnabled) {
    debug(self.id + ' readCiphertext(' + (data ? data.length : 0) + ', ' + offset + '): SSL status ' + sslResult.status +
          ' consumed ' + sslResult.consumed + ' produced ' + (sslResult.data ? sslResult.data.length : 0));
  }
  if (sslResult.justHandshaked) {
    setImmediate(function() {
      self.justHandshaked();
    });
  }

  var lcb;
  var underflow;
  switch (sslResult.status) {
    case self.engine.NEED_WRAP:
      lcb = cb;
      cb = undefined;
      writeCleartext(self, data, newOffset, function() {
        if (lcb) {
          lcb(bytesConsumed);
        }
      });
      break;
    case self.engine.NEED_UNWRAP:
      lcb = cb;
      cb = undefined;
      readCiphertext(self, data, newOffset, function(readCount) {
        if (lcb) {
          lcb(readCount + bytesConsumed);
        }
      });
      break;
    case self.engine.NEED_TASK:
      lcb = cb;
      cb = undefined;
      var paused = self._clientPaused;
      self._clientPaused = true;

      self.engine.runTask(function() {
        self._clientPaused = paused;
        if (debugEnabled) {
          debug(self.id + ' task complete from read');
        }
        readCiphertext(self, data, newOffset, function(readCount) {
          if (lcb) {
            lcb(readCount + bytesConsumed);
          }
        });
      });
      break;
    case self.engine.UNDERFLOW:
      // Nothing to do -- wait until we get more data
      underflow = true;
      break;
    case self.engine.OK:
      if (sslResult.data) {
        pushRead(self, sslResult.data);
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

  if (cb) {
    cb(bytesConsumed, underflow);
  }
  return bytesConsumed;
}

function writeCleartext(self, data, offset, cb) {
  var newOffset = offset;
  var sslResult = self.engine.wrap(data, offset);
  var bytesConsumed = sslResult.consumed;
  newOffset += sslResult.consumed;

  if (debugEnabled) {
    debug(self.id + ' writeCleartext(' + (data ? data.length : 0) + ', ' + offset + '): SSL status ' + sslResult.status +
          ' length ' + (sslResult.data ? sslResult.data.length : 0) + ' consumed ' + bytesConsumed);
  }

  if (sslResult.data) {
    if (debugEnabled) {
      debug(self.id + ' Writing ' + sslResult.data.length);
    }
    // setImmediate because this may result in recursive nextTick calls otherwise
    setImmediate(function() {
      self.socket.write(sslResult.data, function() {
        if (debugEnabled) {
          debug(self.id + ' write complete');
        }
        continueWriteCleartext(self, sslResult.status, data, newOffset, bytesConsumed, cb);
      });
    });
  } else {
    continueWriteCleartext(self, sslResult.status, data, newOffset, bytesConsumed, cb);
  }
  if (sslResult.justHandshaked) {
    setImmediate(function() {
        self.justHandshaked();
    });
  }
}

function continueWriteCleartext(self, status, data, offset, bytesConsumed, c) {
  var cb = c;
  var lcb;
  switch (status) {
    case self.engine.NEED_WRAP:
      lcb = cb;
      cb = undefined;
      writeCleartext(self, data, offset, function(writeCount) {
        if (lcb) {
          lcb(writeCount + bytesConsumed);
        }
      });
      break;
    case self.engine.NEED_UNWRAP:
      lcb = cb;
      cb = undefined;
      readCiphertext(self, data, offset, function() {
        if (lcb) {
          lcb(bytesConsumed);
        }
      });
      break;
    case self.engine.NEED_TASK:
      lcb = cb;
      cb = undefined;
      var paused = self._clientPaused;
      self._clientPaused = true;

      self.engine.runTask(function() {
        self._clientPaused = paused;
        if (debugEnabled) {
          debug(self.id + ' task complete from write');
        }
        writeCleartext(self, data, offset, function(writeCount) {
          if (lcb) {
            lcb(writeCount + bytesConsumed);
          }
        });
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

  if (cb) {
    cb(bytesConsumed);
  }
  return bytesConsumed;
}
*/
