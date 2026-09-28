# Hello World Example

A slightly modified version of the `helloworld` example from [gRPC Java examples](https://github.com/grpc/grpc-java/tree/master/examples).

The example consists of three modules:
- **`proto/`** — protobuf definitions
- **`service/`** — the gRPC service deployed as a WAR to WildFly
- **`client/`** — a Java client that calls the deployed service

## Prerequisites

Build the feature pack first so all artifacts are in your local Maven repository:

```shell
mvn install
```

## Service

From the `examples/helloworld/service` directory, build and provision the server with the gRPC service pre-deployed:

```shell
cd examples/helloworld/service
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

The `helloworld` client is a self-contained executable jar. Build it from the project root:

```shell
mvn package -pl examples/helloworld/client
```

Then run it with `java -jar`:

```shell
java -jar examples/helloworld/client/target/greeter-client.jar Bob <ssl>
```

where `<ssl>` is:
- **`none`** — plaintext, connects to port 8080 (h2c)
- **`oneway`** — TLS, connects to `127.0.0.1:8443`; server authenticates to client
- **`twoway`** — mutual TLS, connects to `127.0.0.1:8443`; both sides authenticate

The jar bundles the TLS certificates generated during the build — no additional setup needed.

## grpcurl

Alternatively, use [grpcurl](https://github.com/fullstorydev/grpcurl) to invoke the service directly.
From the `examples/helloworld` directory, pass the proto file with `-import-path` and `-proto`
since the server does not expose the gRPC reflection API:

```shell
cd examples/helloworld

# plaintext (port 8080, h2c)
grpcurl \
  -plaintext \
  -import-path proto/src/main/proto \
  -proto helloworld.proto \
  -d '{"name":"Bob"}' \
  localhost:8080 helloworld.Greeter/SayHello

# TLS, one-way (port 8443) — server authenticates to client, no client cert
grpcurl \
  -cacert ../../ssl/ca.pem \
  -import-path proto/src/main/proto \
  -proto helloworld.proto \
  -d '{"name":"Bob"}' \
  localhost:8443 helloworld.Greeter/SayHello

# TLS, two-way (port 8443) — mutual authentication, client presents a certificate
grpcurl \
  -cacert ../../ssl/ca.pem \
  -cert client/src/main/resources/client.keystore.pem \
  -key client/src/main/resources/client.key.pem \
  -import-path proto/src/main/proto \
  -proto helloworld.proto \
  -d '{"name":"Bob"}' \
  localhost:8443 helloworld.Greeter/SayHello
```
