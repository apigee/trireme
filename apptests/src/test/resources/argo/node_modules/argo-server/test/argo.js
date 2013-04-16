var assert = require('assert');
var EventEmitter = require('events').EventEmitter;
var fs = require('fs');
var http = require('http');
var Stream = require('stream');
var argo = require('../');
var util = require('util');

function Request() {
  this.headers = {};
  Stream.call(this);
}
util.inherits(Request, Stream);

function Response() {
  this.headers = {};
  this.statusCode = 0;
  this.body = '';
  this.writable = true;
  Stream.call(this);
}
util.inherits(Response, Stream);

Response.prototype.setHeader = function(k, v) {
  this.headers[k] = v;
};

Response.prototype.writeHead = function(s, h) {
  this.statusCode = s;
  this.headers = h;
}

Response.prototype.getHeader = function(k) {
  return this.headers[k];
};

Response.prototype.end = function(b) {
  this.body = b;
};

function _getEnv() {
  return { 
    request: new Request(),
    response: new Response(),
    target: {},
    argo: {}
  };
}

describe('Argo', function() {
  describe('ctor', function() {
    it('alters http.IncomingMessage.prototype', function() {
      argo(http);
      assert.ok(http.IncomingMessage.prototype._argoModified);
    });
  });

  describe('#include', function() {
    it('evaluates a package', function() {
      var mixin = {
        package: function(server) {
          return { 
            install: function() {
              server.mixin = true;
            }
          };
        }
      };

      var server = argo().include(mixin);
      
      assert.ok(server.mixin);
    });
  });

  describe('#listen', function() {
    it('delegates to Runner#listen', function() {
      var runner = require('../runner');
      var wasCalled = false;
      var _listen = runner.listen;

      runner.listen = function() {
        wasCalled = true;
      };

      argo().listen(1234);

      runner.listen = _listen;

      assert.ok(wasCalled);
    });
  });

  describe('#use', function() {
    describe('when using middleware', function() {
      it('delegates to Builder#use', function() {
        var server = argo();
        var wasCalled = false;

        var _use = server.builder.use;
        server.builder.use = function(middleware) {
          wasCalled = true;
        };

        server.builder.use(function(addHandler) {});

        server.builder.use = _use;

        assert.ok(wasCalled);
      });

      it('enqueues a middleware request handler', function() {
        var server = argo();
        var wasCalled = false;

        server.use(function(addHandler) {
          addHandler('request', function(env, next) {
            wasCalled = true;
          });
        });

        server.call(_getEnv());

        assert.ok(wasCalled);
      });

      it('enqueues a middleware response handler', function() {
        var server = argo();
        var wasCalled = false;

        server.use(function(addHandler) {
          addHandler('response', function(env, next) {
            wasCalled = true;
          });
        });

        server.call(_getEnv());

        assert.ok(wasCalled);
      });
    });

    describe('when using a package', function() {
      it('delegates to #include', function() {
        var server = argo();
        var wasCalled = false;

        var _include = server.include;
        server.include = function() {
          wasCalled = true;
        };

        server.use({ package: function() { return { install: function() {} }; } });

        server.include = _include;

        assert.ok(wasCalled);
      });
    });
  });

  describe('#target', function() {
    it('sets env.target.url', function(done) {
      argo()
        .target('http://targeturl')
        .use(function(addHandler) {
          addHandler('request', function(env, next) {
            assert.equal(env.target.url, 'http://targeturl');
            done();
            //next(env);
          });
        })
        .call(_getEnv());
    });
  });

  describe('#route', function() {
    it('executes route request handler on matched route', function(done) {
      var env = _getEnv();
      env.request.url = '/route';
      env.request.method = 'GET';
      argo()
        .route('/route', function(addHandler) {
          addHandler('request', function(env, next) {
            assert.equal(env.request.url, '/route');
            done();
          });
        })
      .call(env);
    });

    it('executes route response handler on matched route', function(done) {
      var env = _getEnv();
      env.request.url = '/route';
      env.request.method = 'GET';

      argo()
        .route('/route', function(addHandler) {
          addHandler('response', function(env, next) {
            assert.equal(env.request.url, '/route');
            done();
          });
        })
      .call(env);
    });

    it('executes the next handler in the pipeline when no route handler exists', function(done) {
      var env = _getEnv();
      env.request.url = '/rout3';
      env.request.method = 'GET';

      argo()
        .use(function(addHandler) {
          addHandler('response', function(env, next) {
            assert.equal(env.request.url, '/rout3');
            done();
          });
        })
        .route('/route', function(addHandler) {
        })
        .call(env);
    });

    it('returns the longest possible match first', function(done) {
      var env = _getEnv();
      env.request.url = '/route/that/matches/first';
      env.request.method = 'GET';

      argo()
        .route('/route', function(addHandler) { })
        .route('/route/that/matches', function(addHandler) {
          addHandler('request', function(env, next) {
            assert.equal(env.request.url, '/route/that/matches/first');
            done();
          });
        })
      .call(env);
    });

    it('returns / match on root request', function(done) {
      var env = _getEnv();
      env.request.url = '/';
      env.request.method = 'GET';

      argo()
        .route('/route', function(addHandler) { })
        .route('/', function(addHandler) {
          addHandler('request', function(env, next) {
            assert.equal(env.request.url, '/');
            done();
          });
        })
      .call(env);
    });

    it('returns 404 when no match exists', function(done) {
      var env = _getEnv();
      env.request.url = '/404';
      env.request.method = 'GET';

      argo()
        .use(function(addHandler) {
          addHandler('response', function(env, next) {
            assert(env.response.statusCode, 404);
            done();
          });
        })
        .route('/', function(addHandler) { })
      .call(env);
    });

    it('returns wildcard when no match exists', function(done) {
      var env = _getEnv();
      env.request.url = '/404';
      env.request.method = 'GET';

      argo()
        .route('*', function(addHandler) {
          addHandler('request', function(env, next) {
            assert(env.request.url, '/404');
            done();
          });
        })
        .route('/', function(addHandler) { })
      .call(env);
    });
  });

  describe('#map', function() {
    describe('sub-routing', function() {
      it('executes the route without a trailing slash', function(done) {
        var env = _getEnv();
        env.request.url = '/map/sub';
        env.request.method = 'GET';

        argo()
          .map('/map', function(server) {
            server
              .route('/sub', function(addHandler) {
                addHandler('request', function(env, next) {
                  assert.equal(env.request.url, '/sub');
                  done();
                  next(env);
                });
              });
          })
          .call(env);
      });

      it('executes the route with a trailing slash', function(done) {
        var env = _getEnv();
        env.request.url = '/map/sub/';
        env.request.method = 'GET';

        argo()
          .map('/map', function(server) {
            server
              .route('/sub', function(addHandler) {
                addHandler('request', function(env, next) {
                  assert.equal(env.request.url, '/sub');
                  done();
                  next(env);
                });
              });
          })
          .call(env);
      });
    });
  });

  describe('request buffering', function() {
    it('only buffers once', function(done) {
      var env = _getEnv();
      env.request = new Request();
      env.target.response = new Response();
      env.response = new Response();

      var _http = {};
      _http.IncomingMessage = Request;
      _http.ServerResponse = Response;

      argo(_http)
        .use(function(addHandler) {
          addHandler('response', function(env, next) {
            assert.equal(env.request.body, 'Hello Buffered Request!');
            env.request.getBody(function(err, body) {
              assert.equal(body.toString(), 'Hello Buffered Request!');
              done();
            });
          });
        })
        .use(function(addHandler) {
          addHandler('response', function(env, next) {
            env.request.getBody(function(err, body) {
              assert.equal(body.toString(), 'Hello Buffered Request!');
              next(env);
            });
          });
        })
        .call(env);

      env.request.emit('data', new Buffer('Hello '));
      env.request.emit('data', new Buffer('Buffered '));
      env.request.emit('data', new Buffer('Request!'));
      env.request.emit('end');
    });
    describe('when emitting Buffers', function() {
      it('returns a full representation of the request body', function(done) {
        var env = _getEnv();
        env.request = new Request();

        var _http = {};
        _http.IncomingMessage = Request;
        _http.ServerResponse = Response;

        argo(_http)
          .use(function(addHandler) {
            addHandler('request', function(env, next) {
              env.request.getBody(function(err, body) {
                assert.equal(body.toString(), 'Hello Buffered Request!');
                done();
              });
            });
          })
          .call(env);

        env.request.emit('data', new Buffer('Hello '));
        env.request.emit('data', new Buffer('Buffered '));
        env.request.emit('data', new Buffer('Request!'));
        env.request.emit('end');
      });
    });

    describe('when emitting Strings', function() {
      it('returns a full representation of the request body', function(done) {
        var env = _getEnv();
        env.request = new Request();

        var _http = {};
        _http.IncomingMessage = Request;
        _http.ServerResponse = Response;

        argo(_http)
          .use(function(addHandler) {
            addHandler('request', function(env, next) {
              env.request.getBody(function(err, body) {
                assert.equal(body.toString(), 'Hello Buffered Request!');
                done();
              });
            });
          })
          .call(env);

        env.request.emit('data', 'Hello ');
        env.request.emit('data', 'Buffered ');
        env.request.emit('data', 'Request!');
        env.request.emit('end');
      });
    });
  });

  describe('response buffering', function() {
    it('only buffers once', function(done) {
      var env = _getEnv();
      env.target.response = new Response();
      env.response = new Response();

      var _http = {};
      _http.IncomingMessage = Request;
      _http.ServerResponse = Response;

      argo(_http)
        .use(function(addHandler) {
          addHandler('response', function(env, next) {
            assert.equal(env.target.response.body, 'Hello Buffered Response!');
            env.target.response.getBody(function(err, body) {
              assert.equal(body.toString(), 'Hello Buffered Response!');
              done();
            });
          });
        })
        .use(function(addHandler) {
          addHandler('response', function(env, next) {
            env.target.response.getBody(function(err, body) {
              assert.equal(body.toString(), 'Hello Buffered Response!');
              next(env);
            });
          });
        })
        .call(env);

      env.target.response.emit('data', new Buffer('Hello '));
      env.target.response.emit('data', new Buffer('Buffered '));
      env.target.response.emit('data', new Buffer('Response!'));
      env.target.response.emit('end');
    });

    describe('when emitting Buffers', function() {
      it('returns a full representation of the response body', function(done) {
        var env = _getEnv();
        env.target.response = new Response();
        env.response = new Response();

        var _http = {};
        _http.IncomingMessage = Request;
        _http.ServerResponse = Response;

        argo(_http)
          .use(function(addHandler) {
            addHandler('response', function(env, next) {
              env.target.response.getBody(function(err, body) {
                assert.equal(body.toString(), 'Hello Buffered Response!');
                done();
              });
            });
          })
          .call(env);

        env.target.response.emit('data', new Buffer('Hello '));
        env.target.response.emit('data', new Buffer('Buffered '));
        env.target.response.emit('data', new Buffer('Response!'));
        env.target.response.emit('end');
      });
    });
    
    describe('when emitting Strings', function() {
      it('returns a full representation of the response body', function(done) {
        var env = _getEnv();
        env.target.response = new Response();
        env.response = new Response();

        var _http = {};
        _http.IncomingMessage = Request;
        _http.ServerResponse = Response;

        argo(_http)
          .use(function(addHandler) {
            addHandler('response', function(env, next) {
              env.target.response.getBody(function(err, body) {
                assert.equal(body.toString(), 'Hello Buffered Response!');
                done();
              });
            });
          })
          .call(env);

        env.target.response.emit('data', 'Hello ');
        env.target.response.emit('data', 'Buffered ');
        env.target.response.emit('data', 'Response!');
        env.target.response.emit('end');
      });
    });
  });

  describe('response ender', function() {
    it('sets a response body when env.response.body is empty', function(done) {
      var env = _getEnv();
      env.target.response = new Response();
      env.response = new Response();
      env.response.setHeader = function() {};
      env.response.writeHead = function() {};
      env.response.end = function(body) {
        assert.equal(body, 'Horticulture Fancy');
        done();
      };

      argo()
        .call(env);

      env.target.response.emit('data', 'Horticulture Fancy');
      env.target.response.emit('end');
    });
  });

  describe('#get', function() {
    it('responds to a GET request', function(done) {
      var env = _getEnv();
      env.request.method = 'GET';
      env.request.url = '/sheep';

      argo()
        .get('/sheep', function(addHandler) {
          addHandler('request', function(env, next) {
            assert.equal(env.request.method, 'GET');
            done();
          });
        })
        .call(env);
    });
  });

  describe('#post', function() {
    it('responds to a POST request', function(done) {
      var env = _getEnv();
      env.request.method = 'POST';
      env.request.url = '/sheep';

      argo()
        .post('/sheep', function(addHandler) {
          addHandler('request', function(env, next) {
            assert.equal(env.request.method, 'POST');
            done();
          });
        })
        .call(env);
    });
  });

  describe('#put', function() {
    it('responds to a PUT request', function(done) {
      var env = _getEnv();
      env.request.method = 'PUT';
      env.request.url = '/sheep';

      argo()
        .put('/sheep', function(addHandler) {
          addHandler('request', function(env, next) {
            assert.equal(env.request.method, 'PUT');
            done();
          });
        })
        .call(env);
    });
  });

  describe('#del', function() {
    it('responds to a DELETE request', function(done) {
      var env = _getEnv();
      env.request.method = 'DELETE';
      env.request.url = '/sheep';

      argo()
        .del('/sheep', function(addHandler) {
          addHandler('request', function(env, next) {
            assert.equal(env.request.method, 'DELETE');
            done();
          });
        })
        .call(env);
    });
  });
  
  describe('#head', function() {
    it('responds to a HEAD request', function(done) {
      var env = _getEnv();
      env.request.method = 'HEAD';
      env.request.url = '/sheep';

      argo()
        .head('/sheep', function(addHandler) {
          addHandler('request', function(env, next) {
            assert.equal(env.request.method, 'HEAD');
            done();
          });
        })
        .call(env);
    });
  });

  describe('#options', function() {
    it('responds to a OPTIONS request', function(done) {
      var env = _getEnv();
      env.request.method = 'OPTIONS';
      env.request.url = '/sheep';

      argo()
        .options('/sheep', function(addHandler) {
          addHandler('request', function(env, next) {
            assert.equal(env.request.method, 'OPTIONS');
            done();
          });
        })
        .call(env);
    });
  });


  describe('#trace', function() {
    it('responds to a TRACE request', function(done) {
      var env = _getEnv();
      env.request.method = 'TRACE';
      env.request.url = '/sheep';

      argo()
        .trace('/sheep', function(addHandler) {
          addHandler('request', function(env, next) {
            assert.equal(env.request.method, 'TRACE');
            done();
          });
        })
        .call(env);
    });
  });

  describe('method routing', function() {
    it('returns a 405 Method Not Allowed on unsupported methods', function(done) {
      var env = _getEnv();
      env.request.method = 'POST';
      env.request.url = '/sheep';
      env.response.writeHead = function(status, headers) {
        assert.equal(status, 405);
        done();
      };

      argo()
        .get('/sheep', function(addHandler) { })
        .call(env);
    });
  });

  describe('request proxying', function() {
    it('passes through if response headers have already been sent', function(done) {
      var env = _getEnv();
      env.request.method = 'GET';
      env.request.url = '/proxy';
      env.response._headerSent = true;

      var _http = function() {};
      _http.Agent = function() {};
      _http.IncomingMessage = Request;
      _http.ServerResponse = Response;

      argo(_http)
        .use(function(addHandler) {
          addHandler('response', function(env, next) {
            assert.ok(!env.target.response);
            done();
          });
        })
        .target('http://argotest')
        .call(env);
    });

    it('forwards requests to a target', function(done) {
      var env = _getEnv();
      env.request.method = 'GET';
      env.request.url = '/proxy';

      var _http = function() {};
      _http.Agent = function() {};
      _http.IncomingMessage = Request;
      _http.ServerResponse = Response;
      _http.request = function(options, callback) {
        assert.equal(options.method, 'GET');
        assert.equal(options.hostname, 'argotest');
        assert.equal(options.headers['Connection'], 'keep-alive');
        assert.equal(options.headers['Host'], 'argotest');
        assert.equal(options.path, '/proxy');
        assert.equal(options.auth, 'argo:rocks');

        return {
          write: function(str) {
            assert.equal(str, 'body');
            done();
          },
          end: function() {}
        };
      };

      argo(_http)
        .target('http://argo:rocks@argotest')
        .call(env);

      env.request.emit('data', 'body');
      env.request.emit('end');
    });

    it('copies raw headers to the response', function(done) {
      var env = _getEnv();

      env.request.method = 'GET';
      env.request.url = '/proxy';
      env.response.setHeader = function(name, value) {
        if (name.toLowerCase() === 'x-stuff') {
          assert(name, 'X-Stuff');
        }
      };
      env.response.body = 'proxied!';

      var _http = function() {};
      _http.Agent = function() {};
      _http.IncomingMessage = Request;
      _http.ServerResponse = Response;
      _http.request = function(options, callback) {
        var res = new Response();
        res._rawHeaderNames = { 'x-stuff': 'X-Stuff' };
        res.headers = { 'X-Stuff': 'yep' };
        callback(res);
        return { end: done };
      };

      argo(_http)
        .target('http://google.com')
        .call(env);

      env.request.emit('end');
    });
  });

  describe('response serving', function() {
    it('serves streams', function(done) {
      var env = _getEnv();
      env.request = new Request();
      env.request.url = '/hello';
      env.request.method = 'GET';
      env.response = new Response();

      var test = '';
      env.response.write = function(chunk) {
        test += chunk.toString();
      };

      env.response.end = function() {
        assert.equal('Hello, World!', test);
        done();
      };

      var stream = new Stream();
      stream.readable = true;

      argo()
        .get('/hello', function(addHandler) {
          addHandler('request', function(env, next) {
            env.response.statusCode = 200;
            env.response.headers['Content-Type'] = 'text/plain';
            env.response.body = stream;
            next(env);
          });
        })
        .call(env);

      stream.emit('data', 'Hello, World!');
      stream.emit('end');
    });

    it('serves stringified JSON objects', function(done) {
      var env = _getEnv();
      env.request = new Request();
      env.request.url = '/hello';
      env.request.method = 'GET';
      env.response = new Response();

      env.response.end = function(body) {
        assert.equal('application/json; charset=UTF-8', env.response.getHeader('Content-Type'));
        assert.equal('{"hello":"World"}', body);
        done();
      };

      argo()
        .get('/hello', function(addHandler) {
          addHandler('request', function(env, next) {
            env.response.statusCode = 200;
            env.response.body = { hello: 'World' };
            next(env);
          });
        })
        .call(env);
    });

    it('serves serves an empty response when the Content-Length is 0', function(done) {
      var env = _getEnv();
      env.request = new Request();
      env.request.url = '/hello';
      env.request.method = 'GET';
      env.response = new Response();

      env.response.end = function(body) {
        assert.ok(!body);
        done();
      };

      argo()
        .get('/hello', function(addHandler) {
          addHandler('request', function(env, next) {
            env.response.statusCode = 200;
            env.response.headers['Content-Length'] = 0;
            next(env);
          });
        })
        .call(env);
    });
  });
});
