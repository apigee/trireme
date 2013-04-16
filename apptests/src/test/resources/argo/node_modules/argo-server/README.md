# Argo

An extensible, asynchronous HTTP reverse proxy and origin server.

<!-- Argo is:

* An API-focused HTTP server.
* A reverse proxy to manage and modify HTTP requests and responses.
* Modular using handlers for request and response pipelines.
* Extensible using a package system.

As an API server:

* Route requests to handlers.
* Separate resources into modules.

As a reverse proxy:

* Route requests to backend servers.
* Transform HTTP messages on the fly.
* Add OAuth 2.0 support to an existing API.
* Create a RESTful API façade over legacy systems.
-->

## Examples

### Adding Cross-Origin Resource Sharing

Setup the server:

```javascript
var argo = require('argo-server');

argo()
  .use(function(addHandler) {
    addHandler('response', function(env, next) {
      env.response.setHeader('Access-Control-Allow-Origin', '*');
      next(env);
    });
  })
  .target('http://weather.yahooapis.com')
  .listen(1337);
```

Make a request:

```bash
$ curl -i http://localhost:1337/forecastrss?w=2467861

HTTP/1.1 200 OK
Date: Thu, 28 Feb 2013 20:55:03 GMT
Content-Type: text/xml;charset=UTF-8
Connection: keep-alive
Server: YTS/1.20.13
Access-Control-Allow-Origin: *
Content-Length: 2337

<?xml version="1.0" encoding="UTF-8" standalone="yes" ?>
<GiantXMLResponse/>
```

### Serving an API Response 

Setup the server: 

```javascript
var argo = require('argo-server');

argo()
  .get('/dogs', function(addHandler) {
    addHandler('request', function(env, next) {
      env.response.statusCode = 200;
      env.response.body = { dogs: ['Alfred', 'Rover', 'Dino'] };
      next(env);
    });
  })
  .listen(1337);
```

Make a request:

```bash
$ curl -i http://localhost:1337/dogs

HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 34 
Date: Thu, 28 Feb 2013 20:44:46 GMT
Connection: keep-alive

{"dogs":["Alfred","Rover","Dino"]}
```

## Install

```bash
$ npm install argo-server
```

## Usage

### use(addHandlerFunction)

Parameters:

`addHandler` has the signature `addHandler(type, [options], handler)`. The `addHandlerFunction` is used to set up request and response handlers.  

#### `addHandler` Parameters:

`type`: `'request'` or `'response'`

`options`: Mostly used for internal purposes.  Optional.

`handler`: A request or response handler.  `handler` has the signature `handler(env, next)`.

#### `handler` Parameters:

`env` is an environment context that is passed to every handler.

`next` is a reference to the next function in the pipeline.

When the handler is complete and wishes to pass to the next function in the pipeline, it must call `next(env)`.

It's implemented like so:

```javascript
argo()
  .use(function(addHandler) {
    addHandler('request', function(env, next) {
      env.request.headers['X-Custom-Header'] = 'Yippee!';
      next(env);
    });
  })
```

### use(package)

Alias for `include(package)`.

### target(uri)

`target` is used for proxying requests to a backend server.

Parameters:

`uri`: a string pointing to the target URI.

Example:

```javascript
argo()
  .target('http://weather.yahooapis.com')
```

### route(path, [options], addHandlerFunction)

Parameters:

`path`: a string used to match HTTP Request URI path.

`options`: an object with a `methods` property to filter HTTP methods (e.g., `{ methods: ['GET','POST'] }`).  Optional.

`addHandlerFunction`: Same as in `use`.

Example:

```javascript
argo()
  .route('/greeting', function(addHandler) {
    addHandler('request', function(env, next) {
      env.response.statusCode = 200;
      env.response.headers = { 'Content-Type': 'text/plain' };
      env.response.body = 'Hello World!';
 
      next(env);
    });
  })
```

### get(path, addHandlerFunction)
### post(path, addHandlerFunction)
### put(path, addHandlerFunction)
### del(path, addHandlerFunction)
### options(path, addHandlerFunction)
### trace(path, addHandlerFunction)

Method filters built on top of `route`.

Example:

```javascript
argo()
  .get('/puppies', function(addHandler) {
    addHandler('request', function(env, next) {
      env.response.body = JSON.stringify([{name: 'Sparky', breed: 'Fox Terrier' }]);
      next(env);
    });
  })
```

### map(path, [options], argoSegmentFunction)

`map` is used to delegate control to sub-Argo instances based on a request URI path.

Parameters:

`path`: a string used to match the HTTP Request URI path.

`options`: an object with a `methods` property to filter HTTP methods (e.g., `{ methods: ['GET','POST'] }`).  Optional.

`argoSegmentFunction`: a function that is passed an instance of `argo` for additional setup.

Example:

```javascript
argo()
  .map('/payments', function(server) {
    server
      .use(oauth)
      .target('http://backend_payment_server');
  })
```

### include(package)

Parameters:

`package`: An object that contains a `package` property.

The `package` property is a function that takes an argo instance as a paramter and returns an object that contains a `name` and an `install` function.

Example:

```javascript
var superPackage = function(argo) {
  return {
    name: 'Super Package',
    install: function() {
      argo
        .use(oauth)
        .route('/super', require('./super'));
    }
  };
};

argo()
  .include(superPackage)
```

### listen(port)

Parameters:

`port`: A port on which the server should listen.

## Tests

Unit tests: 

```bash
$ npm test
```

Test Coverage:

```bash
$ npm run-script coverage
```

## On the Roadmap

* HTTP Caching Support
* Collapsed Forwarding
* Parameterized Routing
* Rate Limiting

## License
MIT
