var qs = require('querystring');

function InMemoryUserStrategy(options) {
  this.options = options || {};
  this.options.users = this.options.users || [];
}

InMemoryUserStrategy.prototype.authorize = function() {
  var that = this;
  return function(addHandler) {
    addHandler('request', function(env, next) {
      if (env.request.method === 'GET') {
        var authRequest = env.oauth.getAuthRequestState(env);
        var body = '<html><body><form method="POST">' +
                   'Username: <input name="username"/><br/>' +
                   'Password: <input type="password" name="password"/><br/>' +
                   '<input type="submit" value="Login">' +
                   '<input type="hidden" name="state" value="' +
                   encodeURIComponent(authRequest) + '"/></form></html>';

        var headers = { 'Content-Type': 'text/html', 'Content-Length': body.length };

        env.response.writeHead(200, headers);
        env.response.end(body);
      } else if (env.request.method === 'POST') {
        env.request.getBody(function(err, body) {
          var loginInfo = qs.parse(body.toString());

          var username = loginInfo.username;
          var password = loginInfo.password;
          
          //TODO: Handle authentication issues with resource owner via user agent.

          var isAuthenticated = false;

          that.options.users.forEach(function(user) {
            if (user.username === username && user.password === password) {
              isAuthenticated = true;
            }
          });

          // In a real user strategy, be sure to authorize, as well.
          if (isAuthenticated) {
            env.oauth.authorize(env, next);
          } else {
            env.oauth.deny(env, next);
          }
        });
      }
    });
  };
};

module.exports = InMemoryUserStrategy;
