var querystring = require('querystring');

function InMemoryClientStrategy(options) {
  this.options = options || {};
  this.clients = this.options.clients || [];
  // basic, params
  this.options.authStrategy = this.options.authStrategy || 'basic';
  this.sessions = [];
}

InMemoryClientStrategy.prototype.authenticate = function() {
  var that = this;
  return function(env, next) {
    var clientId;
    var clientSecret;

    if (that.options.authStrategy === 'params') {
      env.request.getBody(function(err, body) {
        if (!body) {
          that._unauthorized(env);
          return;
        }
        var params = querystring.parse(body.toString());
        if (params.client_id && params.client_secret) {
          clientId = params.client_id;
          clientSecret = params.client_secret;
          that._sendAuthResponse(clientId, clientSecret, env, next);
        } else {
          that._unauthorized(env);
          return;
        }
      });
    } else if (that.options.authStrategy === 'basic') {
      var header = env.request.headers['authorization'];
      if (!header || header.split(' ')[0] !== 'Basic') {
        that._unauthorized(env);
      }
      var credentials = new Buffer(header.split(' ')[1], 'base64').toString('ascii').split(':');
      if (credentials[0] && credentials[1]) {
        clientId = credentials[0];
        clientSecret = credentials[1];
        that._sendAuthResponse(clientId, clientSecret, env, next);
      } else {
        that._unauthorized(env);
        return;
      }
    }
  };
};

InMemoryClientStrategy.prototype._sendAuthResponse = function(clientId, clientSecret, env, next) {
  if (!clientId || !clientSecret) {
    this._unauthorized(env);
    return;
  }

  var isAuthenticated = false;

  this.clients.forEach(function(client) {
    if (client.id === clientId && client.secret === clientSecret) {
      isAuthenticated = true;
    }
  });

  if (isAuthenticated) {
    next(env);
  } else {
    this._unauthorized(env);
  }
};

InMemoryClientStrategy.prototype._unauthorized = function(env) {
  var body = 'Unauthorized.';
  var headers = {
    'Content-Length': body.length,
    'Content-Type': 'text/plain'
  };

  if (this.options.authStrategy === 'basic') {
    headers['WWW-Authenticate'] = 'Basic';
  }

  env.response.writeHead(401, headers);
  env.response.end(body);
};

InMemoryClientStrategy.prototype.verifyAuthRequest = function(options, callback) {
  var clientId = options.clientId;
  var responseType = options.responseType;
  var grantType = options.grantType;
  var redirectUri = options.redirectUri;
  var tokenStrategy = options.tokenStrategy;

  var client = this._getClient(clientId);
  if (!client) {
    callback(new Error('Client does not exist.'));
    return;
  };

  if (client.status !== 'active') {
    callback(new Error('Client is not active.'));
    return;
  }

  if (client.grantTypes.indexOf(grantType) === -1) {
    callback(new Error('Client can not receive the requested response type.'));
    return;
  }

  if (client.redirectUris.indexOf(redirectUri) === -1) {
    callback(new Error('Redirect URI is not authorized.'));
    return;
  }

  var code = options.generateCode();
  
  this.sessions.push({
    clientId: client.id,
    authorized_at: new Date(),
    tokenType: tokenStrategy.type,
    code: code,
    grantType: grantType
  });

  console.log(this.sessions);

  callback(null, { verified: true, code: code });

  // TODO:
  // - Verify clientId is allowed to make the request.
  // - Verify redirectUri has been approved by the client.
  // - Verify responseType is supported.
};

InMemoryClientStrategy.prototype.validateAccessTokenRequest = function(options, callback) {
  // TODO:
  // - Verify code is authorized to clientId.
  // - Verify a token has not yet been granted.
  // - Verify redirectUri is the same as when the auth grant was requested.

  var client = this._getClient(options.clientId);
  if (!client) {
    callback(new Error('Client does not exist.'));
    return;
  };

  if (client.status !== 'active') {
    callback(new Error('Client is not active.'));
    return;
  }

  if (client.redirectUris.indexOf(options.redirectUri) === -1) {
    callback(new Error('Redirect URI is not authorized.'));
    return;
  }

  var token = options.tokenStrategy.generate();

  if (options.code) { 
    var found;
    for(var i = 0, len = this.sessions.length; i < len; i++) {
      var session = this.sessions[i];
      if (session.code === options.code) {
        found = session;
        break;
      }
    }

    if (!found) {
      callback(new Error('Authorization code not found.'));
      return;
    }

    var expires = Date.now()+(3600*1000);

    if (found && !found.token) {
      found.accessToken = token;
      found.expires = expires;
    } else {
      callback(new Error('Token has already been issued.'));
      return;
    }

    console.log(this.sessions);
  }


  // TODO: move body stuff to GrantStrategy
  var body = JSON.stringify({
    'access_token': token,
    'token_type': options.tokenStrategy.type,
    'expires_in': 3600
  });

  var res = {
    contentType: 'application/json; charset=UTF-8',
    body: body,
    validated: true
  };

  callback(null, res);
};

InMemoryClientStrategy.prototype.validateClientRequest = function(options, callback) {
  var found;
  for (var i = 0, len = this.sessions.length; i < len; i++) {
    var session = this.sessions[i];
    if (session.accessToken === options.token) {
      found = session;
      break;
    }
  }

  if (!found) {
    callback(new Error('Invalid access token.'));
    return;
  }

  if (found.expires <= Date.now()) {
    callback(new Error('Access token expired'));
    return;
  }

  callback(null, { validated: true });
};

InMemoryClientStrategy.prototype._getClient = function(id) {
  for(var i = 0, len = this.clients.length; i < len; i++) {
    var client = this.clients[i];
    if (client.id === id) {
      return client;
    }
  };
};

module.exports = InMemoryClientStrategy;
