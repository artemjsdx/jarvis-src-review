// TLS 1.3 terminators for FakeAdbd's pairing flows: OpenSSL exports the RFC 8446 keying
// material (no JVM TLS stack can on JDK <= 24), then relays plaintext to/from the Java
// pairing core over loopback TCP with an "EKMPROXY1 <base64>\n" header line.
//   server mode: node FakeAdbdEkmProxy.js server <pairingPort> <relayPort> <certPem> <keyPem>
//     TLS server with mandatory client cert; relays to the Java PAIRING_RELAY port.
//   client mode: node FakeAdbdEkmProxy.js client <listenPort> <targetPort> <certPem> <keyPem>
//     Plain listener; per connection dials TLS (with client cert) to the pairing port and
//     exports EKM from that session.
'use strict';
const tls = require('tls');
const net = require('net');
const fs = require('fs');

const [role, portA, portB, certFile, keyFile] = process.argv.slice(2);
if (!role || !portA || !portB || !certFile || !keyFile) {
  console.error('usage: FakeAdbdEkmProxy.js server|client <portA> <portB> <certPem> <keyPem>');
  process.exit(2);
}
const opts = {
  key: fs.readFileSync(keyFile),
  cert: fs.readFileSync(certFile),
  requestCert: true,
  rejectUnauthorized: false,
  minVersion: 'TLSv1.3',
  maxVersion: 'TLSv1.3',
};

function exportEkm(socket) {
  // adb's label is "adb-label\0" — 10 bytes including the NUL (sizeof in C).
  return socket.exportKeyingMaterial(64, 'adb-label\x00');
}

if (role === 'server') {
  const server = tls.createServer(opts, (socket) => {
    let ekm;
    try {
      ekm = exportEkm(socket);
    } catch (e) {
      socket.destroy();
      return;
    }
    const relay = net.connect({ port: Number(portB), host: '127.0.0.1' }, () => {
      relay.write('EKMPROXY1 ' + ekm.toString('base64') + '\n');
      socket.pipe(relay);
      relay.pipe(socket);
    });
    relay.on('error', (e) => { console.error('relay-error: ' + (e && e.message)); socket.destroy(); });
    socket.on('error', (e) => { console.error('tls-error: ' + (e && e.message)); relay.destroy(); });
  });
  server.listen(Number(portA), '127.0.0.1', () => console.log('READY'));
} else if (role === 'client') {
  const server = net.createServer((plain) => {
    const t = tls.connect({ ...opts, host: '127.0.0.1', port: Number(portB) }, () => {
      let ekm;
      try {
        ekm = exportEkm(t);
      } catch (e) {
        console.error('ekm-error: ' + (e && e.message));
        plain.destroy();
        t.destroy();
        return;
      }
      plain.write('EKMPROXY1 ' + ekm.toString('base64') + '\n');
      plain.pipe(t);
      t.pipe(plain);
    });
    t.on('error', (e) => { console.error('client-tls-error: ' + (e && e.message)); plain.destroy(); });
    plain.on('error', () => t.destroy());
  });
  server.listen(Number(portA), '127.0.0.1', () => console.log('READY'));
} else {
  console.error('unknown role ' + role);
  process.exit(2);
}
