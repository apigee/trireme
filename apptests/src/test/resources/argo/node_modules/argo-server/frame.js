module.exports = function Frame() {
  this.url = null;
  this.routedResponseHandler = null;
  this.routed = false;
  this.routeUri = null;
  this.oncomplete = null;
};
