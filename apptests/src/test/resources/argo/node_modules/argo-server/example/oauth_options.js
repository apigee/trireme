var oauth2 = require('argo-oauth2-package');

var clientStrategy = new oauth2.InMemoryClientStrategy({
  authStrategy: 'params',
  clients: [{
    id: 'client', 
    secret: 'secr3t',
    grantTypes: ['authorization_code', 'client_credentials'],
    redirectUris: ['http://localhost:3000/cb'],
    status: 'active'
  }]
});

var userStrategy = new oauth2.InMemoryUserStrategy({
  users: [{
    username: 'user',
    password: 'passw0rd'
  }]
});

module.exports = {
  clientStrategy: clientStrategy,
  userStrategy: userStrategy,
  supported: {
    'authorization_code': 'bearer'
  }
};
