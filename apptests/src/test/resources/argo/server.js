var argo = require('argo-server');

var port = 33334;
if (process.argv.length >= 3) {
  port = process.argv[2];
}

argo()
  .use(function(addHandler) {
    addHandler('response', function(env, next) {
      env.response.setHeader('X-Apigee-Ar', 'Go');
      next(env);
    });
  })
  .target('http://weather.yahooapis.com')
  .listen(port);
