# ssl-test

A small app that reproduces the wildfly OpenSSL crashes we've been experiencing.

`App.initSsl` is hardcoded to look for the OpenSSL binaries at the default 64-bit Windows install path. If you want to use specific OpenSSL binaries, modify that path before running.

Uncommenting the call to `getHandshakeSession` in `Server.handleNextConnection` seems to avoid the crash.
