# Chat Example

A bidirectional-streaming gRPC chat application, taken from [gRPC by example](https://github.com/saturnism/grpc-by-example-java).

The example consists of three modules:
- **`proto/`** — protobuf definitions
- **`service/`** — the gRPC service deployed as a WAR to WildFly
- **`client/`** — a JavaFX GUI client that connects to the service

## Prerequisites

Build the feature pack first so all artifacts are in your local Maven repository:

```shell
mvn install
```

## Service

From the `examples/chat/service` directory, build and provision the server with the gRPC service pre-deployed:

```shell
cd examples/chat/service
mvn clean package
```

Then start WildFly:

```shell
./target/wildfly/bin/standalone.sh --stability=preview
```

The server is provisioned with both listeners ready:
- **Port 8080** — HTTP/2 cleartext (h2c), no certificate required
- **Port 8443** — HTTPS with HTTP/2 via ALPN; the SSL context uses `want-client-auth=true` and `authentication-optional=true` so clients may optionally present a certificate for mutual TLS

## Client

The `chat` client is a JavaFX GUI application. From the `examples/chat/client` directory, run:

```shell
cd examples/chat/client
mvn javafx:run -Dexec.args="<ssl>"
```

where `<ssl>` is:
- **`none`** — plaintext, connects to port 8080 (h2c)
- **`oneway`** — TLS, connects to `127.0.0.1:8443`; server authenticates to client
- **`twoway`** — mutual TLS, connects to `127.0.0.1:8443`; both sides authenticate

To see the chat in action, start multiple client instances — messages sent by one client are broadcast to all connected clients.
