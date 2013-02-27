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
var nodetls = require('node_tls');
var StringDecoder = require('string_decoder').StringDecoder;
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
  self.context.init();

  if (options.ciphers) {
    var tmpEngine = self.context.createEngine(false);
    if (!tmpEngine.validateCiphers(options.ciphers)) {
      throw 'Invalid cipher list: ' + options.ciphers;
    }
  }


  self.netServer = net.createServer(options, function(connection) {
    var engine = self.context.createEngine(false);
    if (options.ciphers) {
      engine.setCiphers(options.ciphers);
    }
    var clearStream = new CleartextStream();
    clearStream.init(true, self, connection, engine);
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
    if (arguments.length === 1) {
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
    rejectUnauthorized: '0' !== process.env.NODE_TLS_REJECT_UNAUTHORIZED
  }
  var hostname = options.servername || options.host || 'localhost';
  options.host = hostname;

  var sslContext;
  if (!options.keystore && !options.truststore && (rejectUnauthorized !== false)) {
    debug('Client using default SSL context');
    sslContext = wrap.createDefaultContext();
  } else {
    sslContext = wrap.createContext();
    if (options.rejectUnauthorized === false) {
      debug('Client using SSL context that trusts everyone');
      sslContext.setTrustEverybody();
    } else if (options.truststore) {
      debug('Client using trust store ' + options.truststore);
      sslContext.setTrustStore(options.truststore);
    }
    if (options.keystore) {
      debug('Client using key store ' + options.keystore);
      sslContext.setKeyStore(options.keystore, options.passphrase);
    }
    sslContext.init();
  }

  // TODO pass host and port to SSL engine init
  var engine = sslContext.createEngine(true);
  var sslConn = new CleartextStream();
  var netConn;
  if (options.socket) {
    netConn = options.socket;
  } else {
    netConn = net.connect(options, function() {
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
    this.netServer.close();
    this.emit('close');
    this.closed = true;
  }
};

Server.prototype.addContext = function(hostname, credentials) {
  // TODO something...
};

Server.prototype.address = function() {
  return this.netServer.address();
}

var counter = 0;

function CleartextStream() {
  this.id = ++counter;
}
util.inherits(CleartextStream, net.Socket);

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
  connection.ondata = function(data, offset, end) {
    debug(self.id + ' onData');
    readCiphertext(self, data, offset, end);
  };
  connection.onend = function() {
    debug(self.id + ' onEnd');
    handleEnd(self);
  };
  connection.on('error', function(err) {
    debug(self.id + ' onError');
    self.emit('error', err);
  });
  connection.on('close', function() {
    debug(self.id + ' onClose');
    if (!self.closed) {
      doClose(self);
    }
  });
  connection.on('timeout', function() {
    debug(self.id + ' onTimeout');
    self.emit('timeout');
  });
  connection.on('drain', function() {
    debug(self.id + ' onDrain');
    self.emit('drain');
  });
};

function doClose(self) {
  self.closed = true;
  self.socket.destroy();
  self.emit('close', false);
}

CleartextStream.prototype.address = function() {
  return this.socket.address();
};

CleartextStream.prototype.pause = function() {
  debug('pause');
  this.socket.pause();
}

CleartextStream.prototype.resume = function() {
  debug('resume');
  this.socket.resume();
}

CleartextStream.prototype.write = function(data, arg1, arg2) {
  debug(this.id + ' write(' + (data ? data.length : 0) + ')');
  var encoding, cb;

  // parse arguments
  if (arg1) {
    if (typeof arg1 === 'string') {
      encoding = arg1;
      cb = arg2;
    } else if (typeof arg1 === 'function') {
      cb = arg1;
    } else {
      throw new Error('bad arg');
    }
  }

  if (typeof data === 'string') {
    encoding = (encoding || 'utf8').toLowerCase();
    data = new Buffer(data, encoding);
  } else if (!Buffer.isBuffer(data)) {
    throw new TypeError('First argument must be a buffer or a string.');
  }

  if (!this.handshakeComplete) {
    addToWriteQueue(this, data);
    return false;
  }

  var remaining = (data ? data.length : 0);
  var complete;
  var writeData = data;
  do {
    complete = false;
    remaining -= writeCleartext(this, writeData, function() {
      if (remaining === 0) {
        complete = true
        if (cb !== undefined) {
          cb();
        }
      }
    });
    writeData = undefined;
  } while (remaining > 0);

  return complete;
};

CleartextStream.prototype.end = function(data, encoding) {
  debug(this.id + ' end');
  if (data) {
    this.write(data, encoding);
  }
  if (!this.handshakeComplete) {
    addToWriteQueue(this, END_SENTINEL);
  } else if (!this.closed && !this.ended) {
    debug(this.id + ' Closing SSL outbound');
    this.ended = true;
    this.engine.closeOutbound();
    while (!this.engine.isOutboundDone()) {
      writeCleartext(this);
    }
  }
};

// Got an end from the network
function handleEnd(self) {
  if (!self.closed) {
    if (self.ended) {
      doClose(self);
    } else {
      debug(self.id + ' Closing SSL inbound');
      self.ended = true;
      self.engine.closeInbound();
      while (!self.engine.isInboundDone()) {
        writeCleartext(self);
      }
      self.emit('end');
    }
  }
}

CleartextStream.prototype.destroy = function() {
  this.socket.destroy();
}

CleartextStream.prototype.justHandshaked = function() {
  debug(this.id + ' justHandshaked');
  this.readable = this.writable = true;
  this.handshakeComplete = true;
  if (this.writeQueue) {
    var nextWrite = this.writeQueue.shift();
    while (nextWrite) {
      if (nextWrite === END_SENTINEL) {
        this.end();
      } else {
        this.write(nextWrite);
      }
      nextWrite = this.writeQueue.shift();
    }
  }
  if (this.serverMode) {
    this.server.emit('secureConnection', this);
  } else {
    this.emit('secureConnect');
  }
}

function addToWriteQueue(self, item) {
  if (!self.writeQueue) {
    self.writeQueue = [];
  }
  debug(self.id + ' Adding item to write queue');
  self.writeQueue.push(item);
}

CleartextStream.prototype.setEncoding = function(encoding) {
  this.decoder = new StringDecoder(encoding);
}

CleartextStream.prototype.address = function() {
  return this.socket.address();
}

CleartextStream.prototype.getCipher = function() {
  return this.engine.getCipher();
}

CleartextStream.prototype.getPeerCertificate = function() {
  var cert = this.engine.getPeerCertificate();
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

// TODO offset and end
function readCiphertext(self, data) {
  var sslResult = self.engine.unwrap(data);
  debug(self.id + ' readCiphertext(' + (data ? data.length : 0) + '): SSL status ' + sslResult.status +
        ' consumed ' + sslResult.consumed + ' produced ' + (sslResult.data ? sslResult.data.length : 0));
  if (sslResult.justHandshaked) {
    self.justHandshaked();
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
        emitRawData(self, sslResult.data);
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
      debug('SSL error -- closing');
      doClose(self);
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
    self.socket.write(sslResult.data, cb);
  }
  if (sslResult.justHandshaked) {
    self.justHandshaked();
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
        writeCiphertext(self);
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
      debug('SSL error -- closing');
      doClose(self);
      break;
    default:
      throw 'Unexpected SSL engine status ' + sslResult.status;
  }
  return bytesConsumed;
}

function emitRawData(self, data) {
  debug(self.id + ' emitBuffer: ' + data.length);
  if (self.decoder) {
    var decoded = self.decoder.write(data);
    if (decoded) {
      debug(self.id + ' emitBuffer: decoded string ' + decoded.length);
      emitBuffer(self, decoded);
    }
  } else {
    emitBuffer(self, data);
  }
}

function emitBuffer(self, buf) {
  self.emit('data', buf);
  if (self.ondata) {
    self.ondata(buf, 0, buf.length);
  }
}

