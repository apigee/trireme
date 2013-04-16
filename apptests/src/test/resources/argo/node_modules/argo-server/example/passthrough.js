var argo = require('../');

argo()
  .target('http://weather.yahooapis.com')
  .listen(process.env.PORT || 3000);
